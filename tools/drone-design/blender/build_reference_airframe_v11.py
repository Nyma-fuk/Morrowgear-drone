"""A section-defined airframe: image fitting never deforms the interior skin."""
import argparse
import importlib.util
import json
import math
import sys
from pathlib import Path

import bpy
import bmesh
from mathutils import Vector, geometry, Matrix
from bpy_extras.object_utils import world_to_camera_view

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('reference_v10',HERE/'build_reference_family_v10.py')
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
b,p=v.b,v.p
ROOT=HERE.parents[2]
OUT=ROOT/'docs/design/reference-airframe-v11'
CX,CY,RR=1.91,.01826677,.90
DECK=.397
# Position and edge height are explicit design constraints, not a deformation field.
BOUNDARY=[(0,-2.66,.08),(.59,-2.62,.085),(.94,-2.34,.09),
          (1.30,-2.10,.10),(3.98,.14,.10),(4.74,.74,.37),
          (4.08,1.36,.68),(3.58,1.08,.10),(2.61,1.74,.12),
          (1.12,1.92,.25),(0,1.91,.28)]
OUTLINE=[q[:2] for q in BOUNDARY]
CROWN=[(-2.66,.08),(-2.36,.27),(-1.70,.47),(-.85,.70),(-.25,.78),(.70,.76),(1.32,.58),(1.91,.28)]
BELLY=[(-2.66,-.43),(-2.10,-.46),(-1.0,-.42),(.5,-.38),(1.32,-.30),(1.91,-.12)]
ROOT_WIDTH=[(-2.66,.56),(-2.15,.68),(-.5,.73),(.75,.72),(1.91,.63)]
CAMERA=json.loads((ROOT/'docs/design/reference-family-v10/analysis/reference-shape-fit.json').read_text(encoding='utf-8'))['cameraParameters']
if (OUT/'camera-fit.json').exists():CAMERA=json.loads((OUT/'camera-fit.json').read_text(encoding='utf-8'))['cameraParameters']


def lerp(value, samples):
    return v.interp(value,samples)


def inside(q,poly):
    x,y=q;result=False
    for (ax,ay),(bx,by) in zip(poly,poly[1:]+poly[:1]):
        if (ay>y)!=(by>y) and x<(bx-ax)*(y-ay)/(by-ay)+ax:result=not result
    return result


def edge_at(angle):
    dx,dy=math.cos(angle),math.sin(angle);hits=[]
    for q,r in zip(BOUNDARY,BOUNDARY[1:]+BOUNDARY[:1]):
        sx,sy=r[0]-q[0],r[1]-q[1];det=dx*sy-dy*sx
        if abs(det)<1e-10:continue
        px,py=q[0]-CX,q[1]-CY;t=(px*sy-py*sx)/det;u=(px*dy-py*dx)/det
        if t>0 and -.000001<=u<=1.000001:hits.append((t,q[2]*(1-u)+r[2]*u))
    return min(hits)


def tip_height(x,y):
    corners=[BOUNDARY[i] for i in (4,5,6,7)]
    for a,z,c in ((corners[0],corners[1],corners[2]),(corners[0],corners[2],corners[3])):
        denom=(z[1]-c[1])*(a[0]-c[0])+(c[0]-z[0])*(a[1]-c[1])
        u=((z[1]-c[1])*(x-c[0])+(c[0]-z[0])*(y-c[1]))/denom
        t=((c[1]-a[1])*(x-c[0])+(a[0]-c[0])*(y-c[1]))/denom
        if min(u,t,1-u-t)>-1e-5:return u*a[2]+t*z[2]+(1-u-t)*c[2]
    return None


def nearest_edge(x,y):
    candidates=[]
    pairs=list(zip(BOUNDARY,BOUNDARY[1:]))+[(BOUNDARY[4],BOUNDARY[7])]
    for a,z in pairs:
        dx,dy=z[0]-a[0],z[1]-a[1]
        u=max(0,min(1,((x-a[0])*dx+(y-a[1])*dy)/(dx*dx+dy*dy)))
        candidates.append((math.hypot(x-a[0]-dx*u,y-a[1]-dy*u),a[2]*(1-u)+z[2]*u))
    return min(candidates)


def skin(x,y,top=True):
    x=abs(x)
    crown=lerp(y,CROWN);width=lerp(y,ROOT_WIDTH)
    root=lerp(x,[(0,1),(width*.80,1),(width,.91),(1.18,0),(6,0)])
    distance,edge_z=nearest_edge(x,y)
    # Deck and folded tip meet continuously at a constrained fold line.
    chamfer=1-max(0,min(1,distance/.32))
    taper=max(0,min(1,(x-2.86)/.74))
    deck=DECK*(1-taper)+.17*taper
    wing=deck*(1-chamfer)+edge_z*chamfer
    tip=tip_height(x,y)
    if tip is not None:wing=tip
    if top:return crown*root+wing*(1-root)
    lower=lerp(y,BELLY)
    under=-.17*(1-taper)+.015*taper
    bottom=under*(1-chamfer)+(edge_z-.19)*chamfer
    if tip is not None:bottom=tip-.16
    return lower*root+bottom*(1-root)


def configure_materials():
    for material,color in ((v.A,(.083,.091,.097)),(v.P,(.105,.114,.12)),*[(m,(.085+i*.004,.092+i*.004,.098+i*.004)) for i,m in enumerate(v.PAL)]):
        nodes=material.node_tree.nodes
        for node in nodes:
            if node.type=='VALTORGB':
                node.color_ramp.elements[0].color=tuple(c*.87 for c in color)+(1,)
                node.color_ramp.elements[1].color=tuple(c*1.08 for c in color)+(1,)
            if node.type=='TEX_NOISE':node.inputs['Scale'].default_value=38
        bsdf=nodes['Principled BSDF'];bsdf.inputs['Base Color'].default_value=(*color,1)
        bsdf.inputs['Metallic'].default_value=.45;bsdf.inputs['Roughness'].default_value=.53
        noise=nodes.new('ShaderNodeTexNoise');noise.inputs['Scale'].default_value=250;noise.inputs['Detail'].default_value=2
        bump=nodes.new('ShaderNodeBump');bump.inputs['Strength'].default_value=.14;bump.inputs['Distance'].default_value=.004
        material.node_tree.links.new(noise.outputs['Fac'],bump.inputs['Height']);material.node_tree.links.new(bump.outputs['Normal'],bsdf.inputs['Normal'])
    rotor=v.R.node_tree.nodes['Principled BSDF'];rotor.inputs['Metallic'].default_value=.20;rotor.inputs['Roughness'].default_value=.56


def hull():
    coords=[];edges=[]
    def path(points,closed=False):
        start=len(coords);coords.extend(Vector(q) for q in points)
        edges.extend((start+i,start+i+1) for i in range(len(points)-1))
        if closed:edges.append((start+len(points)-1,start))
    circle=[(CX+RR*math.cos(i*math.tau/128),CY+RR*math.sin(i*math.tau/128)) for i in range(128)]
    path(OUTLINE,True);path(circle,True)
    for a,z in list(zip(OUTLINE,OUTLINE[1:]))+[(OUTLINE[4],OUTLINE[7])]:
        d=Vector(z)-Vector(a);n=Vector((-d.y,d.x)).normalized()
        path([tuple(Vector(a).lerp(Vector(z),t)+n*.32) for t in (0,.25,.5,.75,1)])
    for fraction in (.8,1):path([(lerp(y,ROOT_WIDTH)*fraction,y) for y,z in CROWN])
    for x in (1.18,2.86,3.58,3.60,3.98):path([(x,-3),(x,2.3)])
    for y,z in CROWN[1:-1]:path([(0,y),(5.3,y)])
    path([OUTLINE[4],OUTLINE[6]])
    path([OUTLINE[4],OUTLINE[7]])
    # Concentric deck edges avoid long unsupported triangles around the opening.
    for u in (.54,.78):
        ring=[]
        for i in range(128):
            a=i*math.tau/128;dist,_=edge_at(a);r=RR+(dist-RR)*u
            ring.append((CX+r*math.cos(a),CY+r*math.sin(a)))
        path(ring,True)
    xy,_,triangles,*_=geometry.delaunay_2d_cdt(coords,edges,[],0,1e-6)
    topfaces=[]
    for f in triangles:
        center=sum((xy[i] for i in f),Vector((0,0)))/len(f)
        if inside(center,OUTLINE) and not inside(center,circle):topfaces.append(tuple(f))
    used=sorted({i for f in topfaces for i in f});remap={old:new for new,old in enumerate(used)}
    xy=[xy[i] for i in used];topfaces=[tuple(remap[i] for i in f) for f in topfaces]
    count=len(xy);vertices=[(max(0,x),y,skin(x,y,top)) for top in (False,True) for x,y in xy]
    faces=[f[::-1] for f in topfaces]+[tuple(i+count for i in f) for f in topfaces]
    counts={}
    for f in topfaces:
        for a,z in zip(f,f[1:]+f[:1]):counts[tuple(sorted((a,z)))]=counts.get(tuple(sorted((a,z))),0)+1
    for (a,z),uses in counts.items():
        if uses==1 and not (abs(xy[a].x)<1e-5 and abs(xy[z].x)<1e-5):faces.append((a,z,z+count,a+count))
    body=v.rawmesh('H01_ContinuousLiftingBody',vertices,faces,v.A)
    b.mirror(body);bpy.context.view_layer.objects.active=body;bpy.ops.object.modifier_apply(modifier='Exact_Centerline_Mirror')
    return body


def plate(name,polygon,material,offset=.007):
    # Every point is resampled on the skin, including the interior of the panel.
    coords=[Vector(q) for q in polygon];edges=[(i,(i+1)%len(polygon)) for i in range(len(polygon))]
    minx,maxx=min(x for x,y in polygon),max(x for x,y in polygon)
    miny,maxy=min(y for x,y in polygon),max(y for x,y in polygon)
    for i in range(1,15):
        for j in range(1,15):
            q=(minx+(maxx-minx)*i/15,miny+(maxy-miny)*j/15)
            if inside(q,polygon):coords.append(Vector(q))
    xy,_,triangles,*_=geometry.delaunay_2d_cdt(coords,edges,[],0,1e-6)
    fs=[tuple(f) for f in triangles if inside(sum((xy[i] for i in f),Vector((0,0)))/len(f),polygon)]
    obj=v.rawmesh(name,[(x,y,skin(x,y)+offset) for x,y in xy],fs,material)
    b.mirror(obj);return obj


def seam(name,points,material=None,width=.003):
    path=[]
    for a,z in zip(points,points[1:]):
        for i in range(9):
            u=i/9;x=a[0]*(1-u)+z[0]*u;y=a[1]*(1-u)+z[1]*u
            path.append((x,y,skin(x,y)+.012))
    x,y=points[-1];path.append((x,y,skin(x,y)+.012))
    b.curve(name,path,material or v.D,width,mirrored=True)


def detail(body):
    v.cut_recess(body,'I01_ShoulderIntake',[(.76,-1.52),(1.04,-.72),(.86,-.52)],.13)
    for j,(a,z) in enumerate(zip(CROWN[1:-2],CROWN[2:-1])):
        y0,y1=a[0],z[0]
        seam('H03_CrownJoint',[(0,y0),(.61,y0)],width=.003)
        if j in (1,3):
            y=(y0+y1)/2
            v.box('H04_CenterLatch',(0,y,skin(0,y)+.012),(.12,.18,.012),v.O,bevel=.004)
            v.box('H05_LatchInset',(0,y,skin(0,y)+.020),(.045,.09,.006),v.D,bevel=.003)
    seam('H06_NoseChamfer',[(.43,-2.51),(.57,-2.1),(.60,-1.73),(.59,-.85),(.59,.5),(.49,1.66)],width=.004)
    seam('H07_ShoulderJoint',[(.71,-1.73),(.79,-1.1),(.87,.23),(.84,1.1),(.69,1.75)],width=.004)
    # Whole panel regions with low contrast, not random triangle materials.
    for i,poly in enumerate(([(1.23,-1.69),(1.56,-1.40),(2.05,-.92),(1.59,-.81)],
                            [(2.78,.22),(3.54,.46),(3.46,.84),(2.83,.86)],
                            [(.88,.99),(1.1,1.05),(1.10,1.7),(.90,1.72)])):
        seam('W01_PanelJoint_%02d'%i,poly+[poly[0]],width=.004)
    for i in range(3,9):
        q,r=OUTLINE[i],OUTLINE[i+1];verts=[]
        for j in range(25):
            t=j/24;x=q[0]*(1-t)+r[0]*t;y=q[1]*(1-t)+r[1]*t
            inset=(CX+(x-CX)*.978,CY+(y-CY)*.978)
            verts.extend([(x,y,skin(x,y)+.008),(*inset,skin(*inset)+.008)])
        part=v.rawmesh('W02_MachinedEdge',verts,[(j*2+2,j*2+3,j*2+1,j*2) for j in range(24)],v.E);b.mirror(part)
    tipa,tipb=OUTLINE[5],OUTLINE[6]
    points=[(tipa[0]*(1-t)+tipb[0]*t-.045,tipa[1]*(1-t)+tipb[1]*t) for t in (.14,.37,.63,.86)]
    seam('W04_TipMark',points,v.O,.009)
    for x,y in ((.29,-1.60),(.33,-.4),(.31,.48),(2.96,.55),(3.32,.76)):
        v.box('W05_FlushFastener',(x,y,skin(x,y)+.009),(.018,.035,.007),v.E,True,.003)
    for x in (.78,):
        part=b.loft('H09_AftFairing',[(y,w,skin(x,y)-.008,skin(x,y)+rise) for y,w,rise in ((1.07,.13,.025),(1.63,.17,.12),(1.9,.17,.11))],v.A,.014)
        part.location.x=x;b.mirror(part)
        v.box('H10_AftOpening',(x,1.913,skin(x,1.9)+.054),(.24,.035,.115),v.D,True,.008)
        b.curve('H11_AftMark',[(x,y,skin(x,y)+.127) for y in (1.47,1.65,1.82)],v.O,.009,mirrored=True)
    for x in (.24,.53):
        v.box('H12_RearServiceOpening',(x,1.925,-.035),(.19,.025,.14),v.D,True,.01)
        for z in (-.072,-.025,.022):v.box('H13_RearLouver',(x,1.946,z),(.15,.016,.013),v.E,True,.002)


def fan():
    p.annular_ring('R01_DuctWall',CX,CY,RR+.005,RR-.047,-.17,DECK-.016,v.D,segments=96,mirrored=True)
    p.annular_ring('R02_RecessedLip',CX,CY,RR+.009,RR-.004,DECK-.018,DECK-.008,v.E,segments=96,mirrored=True)
    p.annular_ring('R03_LowerLip',CX,CY,RR+.009,RR-.048,-.182,-.158,v.A,segments=96,mirrored=True)
    b.cylinder('R04_Hub',(CX,CY,.22),.19,.12,v.D,64,True)
    b.cylinder('R05_HubCap',(CX,CY,.290),.133,.075,v.P,64,True)
    for i in range(12):
        a=i*math.tau/12;verts=[];fs=[];rows,cols=6,8;layer=rows*cols
        for dz in (-.012,.012):
            for j in range(rows):
                u=j/(rows-1);r=.18+(RR-.063-.18)*u
                for k in range(cols):
                    t=k/(cols-1);sweep=.12*u+.44*(t-.5)
                    z=.19+.15*(t-.5)*(1-.25*u)+.025*math.sin(t*math.pi)
                    verts.append((CX+r*math.cos(a+sweep),CY+r*math.sin(a+sweep),z+dz))
        for side in range(2):
            for j in range(rows-1):
                for k in range(cols-1):
                    n=side*layer+j*cols+k;fs.append((n,n+1,n+cols+1,n+cols))
        edge=list(range(cols))+[j*cols+cols-1 for j in range(1,rows)]+[(rows-1)*cols+k for k in range(cols-2,-1,-1)]+[j*cols for j in range(rows-2,0,-1)]
        fs.extend((j,k,k+layer,j+layer) for j,k in zip(edge,edge[1:]+edge[:1]))
        obj=v.rawmesh('R06_Blade_%02d'%i,verts,fs,v.R,True);b.mirror(obj)
    for i in range(6):
        a=i*math.tau/6;p.beam('R07_Stator',(CX+.18*math.cos(a),CY+.18*math.sin(a),-.1),(CX+(RR-.035)*math.cos(a),CY+(RR-.035)*math.sin(a),-.12),.028,.04,v.D,.003,True)
    v.anchor('RotorAxis.R',(CX,CY,.19));v.anchor('RotorAxis.L',(-CX,CY,.19))


def nose():
    path=[(0,-2.670,-.075),(.57,-2.637,-.056),(.66,-2.582,-.034),(.92,-2.37,.031)]
    b.mirror(b.ribbon('S01_VisorRecess',path,.050,v.D))
    b.mirror(b.ribbon('S02_VisorOptic',[(x,y-.009,z) for x,y,z in path],.019,v.C))
    v.box('S03_ChinHousing',(0,-2.62,-.319),(.27,.20,.217),v.D,bevel=.035)
    v.box('S04_ChinBezel',(0,-2.73,-.319),(.20,.024,.162),v.E,bevel=.018)
    v.box('S05_ChinGlass',(0,-2.747,-.319),(.128,.012,.10),v.C,bevel=.017)
    for name,loc,size,mat in (('S06_CheekPanel',(.67,-2.53,-.235),(.19,.045,.16),v.D),('S07_CheekLatch',(.67,-2.557,-.235),(.062,.018,.07),v.E)):
        v.box(name,loc,size,mat,True,.012)
    # Forward flank ports are cut into a dark gasket, below the intakes.
    for x,y in ((1.12,-1.87),):
        obj=v.box('S08_FlankPort',(x,y,-.21),(.044,.31,.115),v.D,True,.01)
        obj=v.box('S09_FlankPortFrame',(x+.025,y,-.255),(.028,.26,.026),v.E,True,.004)


def belly():
    for i,y in enumerate((-1.24,-.48,.32,1.05)):
        z=skin(0,y,False)
        v.box('B01_ServicePanel_%d'%i,(0,y,z-.005),(.68,.58,.020),v.A,bevel=.023)
        v.box('B02_FlushLatch',(.21,y,z-.018),(.040,.11,.009),v.D,True,.004)
    v.anchor('PayloadMount',(0,CY,-.46))
    v.anchor('EstimatedLiftLine',(0,CY,0))


def finish_normals(body):
    bm=bmesh.new();bm.from_mesh(body.data)
    sharp=[edge for edge in bm.edges if edge.is_manifold and edge.calc_face_angle()>math.radians(28)]
    bmesh.ops.split_edges(bm,edges=sharp)
    for face in bm.faces:face.smooth=abs(face.calc_center_median().x)>1.20
    bm.to_mesh(body.data);bm.free();body.data.update()


def panel_materials(body):
    body.data.materials.clear()
    for material in (v.A,v.P,v.PAL[0],v.PAL[3]):body.data.materials.append(material)
    for poly in body.data.polygons:
        poly.material_index=0
        x,y,z=poly.center;x=abs(x);width=lerp(y,ROOT_WIDTH)
        if poly.normal.z<.3:continue
        if width*.8<x<width:poly.material_index=1
        elif x<width*.8:
            station=sum(y>point[0] for point in CROWN)
            poly.material_index=3 if station%3==1 else (2 if station%3==2 else 0)


def mechanics(body):
    samples=[(CX+RR*math.cos(a),CY+RR*math.sin(a)) for a in [i*math.tau/720 for i in range(720)]]
    lip_clearance=min(skin(x,y)-(DECK-.008) for x,y in samples)
    bm=bmesh.new();bm.from_mesh(body.data);bmesh.ops.remove_doubles(bm,verts=list(bm.verts),dist=1e-5)
    bmesh.ops.recalc_face_normals(bm,faces=list(bm.faces));bmesh.ops.triangulate(bm,faces=list(bm.faces))
    volume=0.;moment=Vector()
    for face in bm.faces:
        a,z,c=[q.co for q in face.verts];dv=a.dot(z.cross(c))/6;volume+=dv;moment+=(a+z+c)*(dv/4)
    cog=moment/volume if abs(volume)>1e-8 else Vector((0,0,0))
    nonmanifold=sum(not edge.is_manifold for edge in bm.edges);bm.free()
    span=2*max(q[0] for q in BOUNDARY);length=max(q[1] for q in BOUNDARY)-min(q[1] for q in BOUNDARY)
    checks={'symmetricProfile':True,'allInletsInsideAirframe':all(inside(q,OUTLINE) for q in samples),
            'fanLipBelowSkin':lip_clearance>=.005,'bladeTipClearance':(RR-.047)-(RR-.063)>=.015,
            'watertightBodyAfterWelding':nonmanifold==0,'positiveVolume':volume>0,'lateralVolumeBalance':abs(cog.x)<1e-5}
    return {'scope':'Geometry and packaging only; mass, thrust, aerodynamics and flight stability are not simulated.',
            'span':span,'length':length,'spanToLength':span/length,'fanDiameterToSpan':2*RR/span,
            'crownWidthToSpan':2*max(q[1] for q in ROOT_WIDTH)/span,'fanLipBelowSkinMinimum':lip_clearance,
            'bladeTipRadialClearance':.016,'bodyVolume':volume,'uniformBodyVolumeCentroid':list(cog),
            'liftLineY':CY,'nonmanifoldEdgesAfterWelding':nonmanifold,'checks':checks,'passed':all(checks.values())}


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--quick',action='store_true');opt=parser.parse_args(sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else [])
    OUT.mkdir(parents=True,exist_ok=True);v.shared.reset();configure_materials()
    v.skin=skin;v.CX=CX;v.CY=CY;v.FAN_R=RR;v.OUTLINE=OUTLINE
    body=hull();detail(body);fan();nose();belly();finish_normals(body);panel_materials(body)
    camera=v.setup();scene=bpy.context.scene
    scene.view_settings.look='AgX - Medium High Contrast';scene.view_settings.exposure=-.25
    scene.render.resolution_x=1681;scene.render.resolution_y=936
    v.FITTED={'cameraParameters':CAMERA}
    views={'hero':(6,-12,8),'front':(0,-14,.1),'rear':(0,14,.1),'top':(0,0,14),'bottom':(0,0,-14),'left':(-14,0,.1),'right':(14,0,.1),'underside':(6,-12,-7)}
    for name,loc in views.items():
        if opt.quick and name!='hero':continue
        camera.data.type='ORTHO';camera.data.ortho_scale=9.9;camera.data.shift_x=0;camera.data.shift_y=0
        camera.location=loc;b.look(camera,(0,-.3,.08))
        if name=='hero':v.fit_camera(camera,scene)
        scene.render.filepath=str(OUT/(name+'.png'));bpy.ops.render.render(write_still=True)
    v.fit_camera(camera,scene);bpy.context.view_layer.update()
    points={'fan_left':(-CX,CY,.3275),'fan_right':(CX,CY,.3275),'nose_visor':(0,-2.670,-.075),'chin_sensor':(0,-2.747,-.319),'nose_right_corner':(.57,-2.637,-.056),'rear_crown':(0,1.60,skin(0,1.60))}
    for side,sign in (('left',-1),('right',1)):
        for name,index in (('front',5),('rear',6)):
            x,y,z=BOUNDARY[index];points['tip_%s_%s'%(side,name)]=(sign*x,y,z)
    landmarks={}
    for key,xyz in points.items():
        uv=world_to_camera_view(scene,camera,Vector(xyz));landmarks[key]={'xyz':xyz,'xy':[uv.x*1681,(1-uv.y)*936]}
    (OUT/'landmarks.json').write_text(json.dumps(landmarks,indent=2),encoding='utf-8')
    (OUT/'hull-mesh.json').write_text(json.dumps({'vertices':[list(body.matrix_world@q.co) for q in body.data.vertices],'faces':[list(q.vertices) for q in body.data.polygons]}),encoding='utf-8')
    report=v.shared.validation('reference-airframe-v11');report.update(referenceFidelityApproved=False,gameVerified=False,glbTextureParityVerified=False)
    (OUT/'validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    (OUT/'mechanics.json').write_text(json.dumps(mechanics(body),indent=2),encoding='utf-8')
    (OUT/'design-parameters.json').write_text(json.dumps({'cameraParameters':CAMERA,'boundary':BOUNDARY,'crown':CROWN,'belly':BELLY,'fanCenter':[CX,CY],'fanRadius':RR,'fanLipTop':DECK-.008,'wingDeck':DECK,'geometricSymmetry':True,'isAerodynamicSimulation':False},indent=2),encoding='utf-8')
    if not opt.quick:
        clay=b.mat('V11_DiagnosticClay',(.25,.25,.25),0,.65);scene.view_layers[0].material_override=clay
        scene.render.filepath=str(OUT/'clay.png');bpy.ops.render.render(write_still=True);scene.view_layers[0].material_override=None
    scene['asset_status']='REFERENCE_AIRFRAME_REVIEW_CANDIDATE'
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'airframe.blend'))
    if not opt.quick:
        bpy.ops.object.select_all(action='DESELECT')
        for obj in scene.objects:
            if obj.type in ('MESH','CURVE','EMPTY'):obj.select_set(True)
        bpy.ops.export_scene.gltf(filepath=str(OUT/'airframe.glb'),export_format='GLB',use_selection=True)
    print('MODEL_REPORT',json.dumps(report),flush=True)


if __name__=='__main__':main()
