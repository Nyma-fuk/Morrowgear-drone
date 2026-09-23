"""Reconstruct the approved concept as geometry; no live game assets are written."""
import argparse
import importlib.util
import json
import math
import sys
from pathlib import Path

import bpy
import bmesh
from bpy_extras.object_utils import world_to_camera_view
from mathutils import Vector, kdtree, geometry, Matrix

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('reference_shared', HERE / 'build_unified_family_v8.py')
shared = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shared)
b, p = shared.b, shared.p
ROOT = HERE.parents[2]
OUT = ROOT / 'docs/design/reference-family-v10'

A = b.mat('V10_GraphiteArmor', (.054, .061, .067), .65, .43, weather=True)
P = b.mat('V10_PanelArmor', (.078, .085, .092), .68, .41, weather=True)
E = b.mat('V10_MachinedEdge', (.22, .245, .26), .88, .32)
D = b.mat('V10_Recess', (.008, .012, .015), .3, .4)
R = b.mat('V10_RotorComposite', (.012, .018, .022), .45, .44)
G = b.mat('V10_SmokedOpticalCover', (.003, .005, .009), .22, .105)
C = b.mat('V10_OpticCyan', (.08, .48, .58), .18, .23, (.20, .75, .88))
O = b.mat('V10_ServiceAmber', (.72, .29, .015), .6, .4)
PAL = [b.mat('V10_SkinPanel_%d' % i, tuple(c * n for c in (.062, .070, .077)), .67, .42, weather=True)
       for i, n in enumerate((.80, .9, 1, 1.08, 1.17))]
for material in (A,P,*PAL):
    for node in material.node_tree.nodes:
        if node.type=='TEX_NOISE':
            node.inputs['Scale'].default_value=22
            node.inputs['Detail'].default_value=2
        elif node.type=='VALTORGB':
            midpoint=node.color_ramp.evaluate(.5)
            for element in node.color_ramp.elements:
                rgba=element.color
                element.color=tuple((midpoint[i]+(rgba[i]-midpoint[i])*.30)*.75 for i in range(3))+(1,)
    ramp=next(n for n in material.node_tree.nodes if n.type=='VALTORGB')
    # glTF cannot serialize the procedural shader graph. Avoid its unrelated
    # default white fallback; exact texture baking remains a separate gate.
    material.node_tree.nodes['Principled BSDF'].inputs['Base Color'].default_value=ramp.color_ramp.evaluate(.5)
CX, CY, FAN_R = 1.91, -.20, .985
OUTLINE = [(0, -2.65), (.68, -2.60), (1.04, -2.29), (1.29, -2.08),
           (3.98, -.10), (4.40, .31), (4.43, 1.28), (3.60, 1.02),
           (2.61, 1.40), (1.12, 1.81), (0, 1.86)]
FITTED=None
CAGE=None
CAGE_MODULE=None


def fit_tip_height(x,y,default):
    if FITTED is None:return default+.46*max(0,(x-4.0)/.43)
    front=FITTED['frontTip'];rear=FITTED['rearTip']
    corners=[(3.98,.24,.025),front,rear,(3.60,1.02,.025)]
    q=Vector((x,y))
    for a,z,c in ((corners[0],corners[1],corners[2]),(corners[0],corners[2],corners[3])):
        av,bv,cv=Vector(a[:2]),Vector(z[:2]),Vector(c[:2])
        denom=(bv.y-cv.y)*(av.x-cv.x)+(cv.x-bv.x)*(av.y-cv.y)
        u=((bv.y-cv.y)*(q.x-cv.x)+(cv.x-bv.x)*(q.y-cv.y))/denom
        t=((cv.y-av.y)*(q.x-cv.x)+(av.x-cv.x)*(q.y-cv.y))/denom
        if min(u,t,1-u-t)>-1e-5:return u*a[2]+t*z[2]+(1-u-t)*c[2]
    return default


def interp(value, samples):
    for (a, x), (z, y) in zip(samples, samples[1:]):
        if value <= z:
            t = max(0, min(1, (value - a) / (z - a)))
            return x + (y - x) * t
    return samples[-1][1]


def skin(x, y, top=True):
    x = abs(x)
    root = interp(x, [(0, 1), (.48, 1), (.72, .94), (1.05, .40), (1.32, 0), (5, 0)])
    crown = interp(y, [(-2.65, .07), (-2.35, .25), (-1.55, .52), (-.5, .77),
                        (.6, .79), (1.32, .63), (1.86, .26)])
    lift=(FITTED['fanHubZ']-.145) if FITTED else 0
    wing = .18 + lift + .035 * max(0, 1 - abs(y) / 1.8)
    if FITTED:
        radius=math.hypot(x-CX,y-CY)
        angle=math.atan2(y-CY,x-CX)
        bx,by=ray_edge(angle);boundary=math.hypot(bx-CX,by-CY)
        blend=max(0,min(1,(radius-FAN_R)/(boundary-FAN_R)))
        wing=.025+(.21+lift-.025)*(1-blend)**1.15
    tip_top=fit_tip_height(x,y,wing)
    if top:
        return tip_top * (1-root) + crown * root
    return (tip_top-(.17 if FITTED else .275))*(1-root) - .476 * root


def rawmesh(name, verts, faces, mat=A, smooth=False):
    obj = b.mesh(name, verts, faces, mat, smooth=smooth)
    bm = bmesh.new(); bm.from_mesh(obj.data)
    bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
    bm.to_mesh(obj.data); bm.free()
    return obj


def half(obj):
    bm = bmesh.new(); bm.from_mesh(obj.data)
    bmesh.ops.bisect_plane(bm, geom=list(bm.verts)+list(bm.edges)+list(bm.faces),
                          plane_co=(0, 0, 0), plane_no=(1, 0, 0), dist=1e-7, clear_inner=True)
    bm.to_mesh(obj.data); bm.free()
    return b.mirror(obj)


def box(name, loc, size, material=A, pair=False, bevel=.02):
    return b.cube(name, loc, size, material, bevel, mirrored=pair)


def anchor(name, loc):
    bpy.ops.object.empty_add(location=loc)
    obj = bpy.context.object; obj.name = name
    return obj


def ray_edge(angle):
    dx, dy = math.cos(angle), math.sin(angle)
    hits = []
    for q, r in zip(OUTLINE, OUTLINE[1:]+OUTLINE[:1]):
        sx, sy = r[0]-q[0], r[1]-q[1]
        det = dx*sy-dy*sx
        if abs(det) < 1e-9: continue
        px, py = q[0]-CX, q[1]-CY
        t, u = (px*sy-py*sx)/det, (px*dy-py*dx)/det
        if t > 0 and -1e-6 <= u <= 1.000001: hits.append(t)
    if not hits: raise ValueError('Missing outline intersection')
    distance = min(hits)
    if distance < FAN_R+.035: raise ValueError('Duct crosses silhouette')
    return CX+distance*dx, CY+distance*dy


def panel(name, points, material=P, offset=.010, pair=True):
    obj = rawmesh(name, [(x, y, skin(x, y)+offset) for x, y in points],
                  [tuple(range(len(points)))], material)
    return b.mirror(obj) if pair else obj


def line(name, xy, material=D, width=.006, pair=True):
    return b.curve(name, [(x, y, skin(x, y)+.018) for x, y in xy], material, width, mirrored=pair)


def hull():
    # Constrain the mesh to the crown/shoulder crease lines. A radial fan grid
    # across the fuselage produced stair-step normals instead of broad armor faces.
    coords=[];edges=[]
    def path(points,closed=False):
        start=len(coords);coords.extend(Vector(q) for q in points)
        edges.extend((start+i,start+i+1) for i in range(len(points)-1))
        if closed:edges.append((start+len(points)-1,start))
    circle=[(CX+FAN_R*math.cos(i*math.tau/128),CY+FAN_R*math.sin(i*math.tau/128)) for i in range(128)]
    path(OUTLINE,True);path(circle,True)
    # Interior samples resolve the wing camber without changing its outer contour.
    # Otherwise long triangles bridge the duct lip directly to the leading edge.
    for ix in range(1,24):
        for iy in range(22):coords.append(Vector((ix*.21,-2.65+iy*.215)))
    for x in (.48,.72,1.05,1.32,3.6,3.98):path([(x,-3),(x,2.3)])
    for y in (-2.35,-1.55,-.5,.6,1.32):path([(0,y),(5.5,y)])
    if FITTED:path([OUTLINE[4],OUTLINE[6]])
    xy,_,triangles,*_=geometry.delaunay_2d_cdt(coords,edges,[],0,1e-6)
    def inside(q,poly):
        x,y=q;result=False
        for (ax,ay),(bx,by) in zip(poly,poly[1:]+poly[:1]):
            if (ay>y)!=(by>y) and x < (bx-ax)*(y-ay)/(by-ay)+ax:result=not result
        return result
    topfaces=[]
    for f in triangles:
        center=sum((xy[i] for i in f),Vector((0,0)))/len(f)
        if inside(center,OUTLINE) and not inside(center,circle):topfaces.append(tuple(f))
    used=sorted({i for f in topfaces for i in f});mapping={old:new for new,old in enumerate(used)}
    xy=[xy[i] for i in used];topfaces=[tuple(mapping[i] for i in f) for f in topfaces]
    count=len(xy);verts=[(max(0,x),y,skin(x,y,top)) for top in (False,True) for x,y in xy]
    faces=[f[::-1] for f in topfaces]+[tuple(i+count for i in f) for f in topfaces]
    boundary={}
    for face in topfaces:
        for a,z in zip(face,face[1:]+face[:1]):
            edge=tuple(sorted((a,z)));boundary[edge]=boundary.get(edge,0)+1
    for (a,z),uses in boundary.items():
        if uses!=1 or (abs(xy[a].x)<1e-5 and abs(xy[z].x)<1e-5):continue
        faces.append((a,z,z+count,a+count))
    obj=rawmesh('H01_ContinuousLiftingBody',verts,faces,A)
    b.mirror(obj)
    # Panel boundaries follow manufactured parts, not arbitrary triangulation.
    bpy.context.view_layer.objects.active=obj
    bpy.ops.object.modifier_apply(modifier='Exact_Centerline_Mirror')
    return obj


def cut_recess(body, name, polygon, depth=.16):
    # The mouth is below the surrounding skin, not a black decal on top of it.
    n = len(polygon)
    verts = [(x, y, skin(x, y)+z) for z in (-depth, .10) for x, y in polygon]
    faces = [tuple(range(n-1, -1, -1)), tuple(range(n, n*2))]
    faces += [(i, (i+1)%n, (i+1)%n+n, i+n) for i in range(n)]
    cutter = rawmesh(name+'_Cutter', verts, faces, D); b.mirror(cutter)
    bpy.context.view_layer.objects.active = body
    mod = body.modifiers.new(name+'_Cut', 'BOOLEAN'); mod.operation = 'DIFFERENCE'; mod.solver = 'EXACT'; mod.object = cutter
    bpy.ops.object.modifier_apply(modifier=mod.name)
    bpy.data.objects.remove(cutter, do_unlink=True)
    panel(name+'_Interior', polygon, D, -depth+.004)
    line(name+'_Bezel', polygon+[polygon[0]], E, .009)


def skin_detail(body):
    cut_recess(body, 'I01_ShoulderIntake', [(.81,-1.55), (1.17,-.86), (.85,-.62)], .14)
    # Crown panels meet edge-to-edge instead of stacking boxes on the roof.
    stations = [(-2.49,.30),(-2.03,.45),(-1.42,.49),(-.65,.49),(.18,.48),(.83,.43),(1.47,.30)]
    for index, ((y0,w0),(y1,w1)) in enumerate(zip(stations,stations[1:])):
        if index in (0,5): panel('H02_CrownPanel_%02d'%index, [(0,y0+.008),(w0-.014,y0+.008),(w1-.014,y1-.008),(0,y1-.008)], PAL[2+(index%2)], .006)
        line('H03_CrownJoint',[(0,y0),(w0,y0)],D,.004)
        if index in (2,4):
            z=skin(.11,y0+.20)
            box('H04_CenterLatch', (0,y0+.20,z+.017), (.12,.18,.015),O,bevel=.008)
            box('H05_LatchInset', (0,y0+.20,z+.026), (.055,.09,.009),D,bevel=.004)
    line('H06_CheekJoint',[(.49,-2.43),(.60,-2.08),(.72,-1.68)],D,.004)
    line('H07_ShoulderJoint',[(.71,-.46),(.75,-.08),(.76,.34),(.69,1.24)],D,.004)
    for y in (-1.97,-1.34,-.56,.25,.96):
        for x in (.23,.43):
            box('H08_FlushScrew', (x,y,skin(x,y)+.019), (.022,.04,.008),E,True,.003)
    for a in (0,35,75,116,159,207,249,291,328):
        angle=math.radians(a); ox,oy=ray_edge(angle)
        pts=[]
        for i in range(9):
            u=.07+.87*i/8
            ix,iy=CX+FAN_R*math.cos(angle),CY+FAN_R*math.sin(angle)
            pts.append((ix*(1-u)+ox*u,iy*(1-u)+oy*u))
        if min(x for x,y in pts) > 1.13: line('W01_ServiceJoint',pts,D,.005)
    # The inlaid rim follows the actual outer edge, leaving the intake/fan unobstructed.
    for index in range(3,len(OUTLINE)-2):
        q,r=OUTLINE[index:index+2]
        points=[]
        for i in range(9):
            t=i/8; x=q[0]*(1-t)+r[0]*t; y=q[1]*(1-t)+r[1]*t
            points.extend([(x,y,skin(x,y)+.006),(x,y,skin(x,y)-.035)])
        strip=rawmesh('W02_LeadingEdge',points,[(i*2,i*2+1,i*2+3,i*2+2) for i in range(8)],E)
        b.mirror(strip)
        inset=[(CX+(x-CX)*.978,CY+(y-CY)*.978) for x,y in (q,r)]
        panel('W02_Chamfer', [q,r,inset[1],inset[0]], E,.007)
    tip_poly=[(4.04,.34),(4.40,.34),(4.41,1.21),(4.05,1.10)]
    if FITTED:
        center=Vector((4.09,.92))
        tip_poly=[tuple(Vector(q)*.97+center*.03) for q in OUTLINE[4:8]]
    tip = panel('W03_UpturnedWingtip', tip_poly,PAL[0],.012)
    mod=tip.modifiers.new('WingtipSkin','SOLIDIFY');mod.thickness=.035;mod.offset=-1
    if FITTED:
        front=Vector(FITTED['frontTip'][:2]);rear=Vector(FITTED['rearTip'][:2]);center=Vector((4.09,.92))
        line('W04_TipMark',[tuple((front*(1-t)+rear*t)*.91+center*.09) for t in (.18,.40,.65,.83)],O,.012)
    else:line('W04_TipMark',[(4.32,.50),(4.33,1.03)],O,.012)
    for x,y in ((1.48,-1.45),(2.86,.79),(3.39,.81)):
        box('W05_MaintenanceFastener',(x,y,skin(x,y)+.018),(.024,.06,.012),E,True,.004)
    # Two small rear raised fairings are present in the approved common silhouette.
    for x in (.79,):
        z=skin(x,1.37)
        part=b.loft('H09_AftFairing',[(y,w,skin(x,y)-.045,skin(x,y)+rise) for y,w,rise in ((1.04,.13,.03),(1.53,.16,.12),(1.79,.13,.08))],A,.012)
        part.location.x=x; b.mirror(part)
        box('H10_AftVent',(x,1.802,skin(x,1.79)+.025),(.18,.03,.10),D,True,.009)
        b.curve('H11_AftMark',[(x,y,skin(x,y)+.13) for y in (1.35,1.52)],O,.01,mirrored=True)
    for x in (.22,.50,.76):
        box('H12_RearExhaust',(x,1.855,-.065),(.16,.025,.18),D,True,.015)
        for z in (-.1,-.045,.01): box('H13_ExhaustLouver',(x,1.874,z),(.12,.014,.015),E,True,.002)


def fan():
    p.annular_ring('R01_DuctWall',CX,CY,.984,.934,-.20,.211,D,mirrored=True,bevel=.006)
    p.annular_ring('R02_MachinedLip',CX,CY,1.008,.981,.205,.222,E,mirrored=True,bevel=.003)
    p.annular_ring('R03_LowerLip',CX,CY,.985,.932,-.215,-.19,A,mirrored=True,bevel=.004)
    b.cylinder('R04_Hub',(CX,CY,-.018),.215,.19,D,64,True)
    b.cylinder('R05_HubCap',(CX,CY,.1),.145,.09,P,64,True)
    # Finite-thickness, twisted blades with substantial chord and a clear tip gap.
    for index in range(12):
        angle=index*math.tau/12
        verts=[];rows=6;cols=7;layer=rows*cols;faces=[]
        for zoff in (-.015,.015):
            for j in range(rows):
                u=j/(rows-1);radius=.20+.72*u
                for k in range(cols):
                    t=k/(cols-1);skew=.10*u+.43*(t-.5)
                    z=-.027+.13*(t-.5)*(1-.3*u)+.022*math.sin(t*math.pi)
                    verts.append((CX+radius*math.cos(angle+skew),CY+radius*math.sin(angle+skew),z+zoff))
        for side in range(2):
            for j in range(rows-1):
                for k in range(cols-1):
                    n=side*layer+j*cols+k;faces.append((n,n+1,n+cols+1,n+cols))
        edge=list(range(cols))+[j*cols+cols-1 for j in range(1,rows)]+[(rows-1)*cols+k for k in range(cols-2,-1,-1)]+[j*cols for j in range(rows-2,0,-1)]
        faces.extend((i,k,k+layer,i+layer) for i,k in zip(edge,edge[1:]+edge[:1]))
        blade=rawmesh('R06_Blade_%02d'%index,verts,faces,R,True); b.mirror(blade)
    for index in range(4):
        angle=index*math.tau/4
        pts=[(CX+.2*math.cos(angle),CY+.2*math.sin(angle),-.15),(CX+.94*math.cos(angle),CY+.94*math.sin(angle),-.17)]
        p.beam('R07_FixedStator',pts[0],pts[1],.05,.04,D,.007,True)
    for index in range(24):
        a=index*math.tau/24
        box('R08_LipSegment',(CX+1.006*math.cos(a),CY+1.006*math.sin(a),.225),(.012,.012,.012),E,True,.002)
    anchor('RotorAxis.R',(CX,CY,0));anchor('RotorAxis.L',(-CX,CY,0))


def nose():
    path=[(0,-2.668,-.10),(.57,-2.625,-.08),(.71,-2.58,-.05),(.99,-2.34,.015)]
    visor=b.ribbon('S01_VisorRecess',path,.053,D);b.mirror(visor)
    band=b.ribbon('S02_VisorOptic',[(x,y-.011,z) for x,y,z in path],.019,C);b.mirror(band)
    box('S03_ChinSensor',(0,-2.65,-.365),(.28,.18,.245),D,bevel=.035)
    box('S04_ChinBezel',(0,-2.75,-.365),(.215,.03,.18),E,bevel=.021)
    box('S05_ChinGlass',(0,-2.77,-.365),(.137,.012,.117),C,bevel=.017)
    box('S06_CheekRecess',(.71,-2.51,-.275),(.18,.09,.15),D,True,.018)
    obj=b.cylinder('S07_CheekOptic',(.71,-2.57,-.275),.037,.016,G,32)
    obj.rotation_euler.x=math.pi/2;b.mirror(obj)


def belly():
    for y in (-1.4,-.57,.3,1.07):
        box('B01_ServicePanel',(0,y,-.476),(.64,.64,.042),A,bevel=.044)
        box('B02_PanelLatch',(.19,y,-.504),(.065,.15,.016),D,True,.009)
    anchor('MountPayload',(0,-.1,-.50))
    anchor('LandingPlane',(0,0,-1.25))


def pose(obj, prop, values):
    for frame,value in values:
        setattr(obj,prop,value)
        obj.keyframe_insert(data_path=prop,frame=frame)


def child(obj,parent):
    obj.parent=parent
    return obj


def lbox(parent,name,loc,size,material=A,bevel=.02):
    return child(box(name,loc,size,material,bevel=bevel),parent)


def lcyl(parent,name,loc,radius,depth,material=E,axis='Z'):
    obj=b.cylinder(name,loc,radius,depth,material,32)
    if axis=='Y':obj.rotation_euler.x=math.pi/2
    if axis=='X':obj.rotation_euler.y=math.pi/2
    return child(obj,parent)


def landing_gear():
    for sign in (-1,1):
        for y,direction in ((-1.16,1),(.82,-1)):
            prefix='Gear_%s_%s'%('R' if sign>0 else 'L','F' if y<0 else 'A')
            root=anchor(prefix+'_Root',(sign*.74,y,-.30))
            pose(root,'rotation_euler',[(1,(direction*math.pi/2,0,0)),(40,(0,0,0)),(70,(direction*math.pi/2,0,0)),(120,(direction*math.pi/2,0,0))])
            axis=Vector((sign*.25,0,-math.sqrt(1-.25**2)))
            obj=p.beam(prefix+'_OuterStrut',(0,0,0),axis*.28,.125,.14,A,.014);child(obj,root)
            lcyl(root,prefix+'_Hinge',(0,0,.008),.10,.18,E,'X')
            for number,start,length,travel,width in ((1,.025,.29,.26,.080),(2,.05,.30,.56,.048)):
                obj=p.beam(prefix+'_Piston%d'%number,axis*start,axis*(start+length),width,width,E,.006);child(obj,root)
                pose(obj,'delta_location',[(1,(0,0,0)),(40,axis*travel),(70,(0,0,0)),(120,(0,0,0))])
                collar=lbox(root,prefix+'_Collar%d'%number,axis*(start+length),(.13-number*.025,.13-number*.025,.045),D,.008)
                pose(collar,'delta_location',[(1,(0,0,0)),(40,axis*travel),(70,(0,0,0)),(120,(0,0,0))])
            footroot=anchor(prefix+'_FootLevel',axis*.36);footroot.parent=root
            pose(footroot,'delta_location',[(1,(0,0,0)),(40,axis*.56),(70,(0,0,0)),(120,(0,0,0))])
            pose(footroot,'rotation_euler',[(1,(-direction*math.pi/2,0,0)),(40,(0,0,0)),(70,(-direction*math.pi/2,0,0)),(120,(-direction*math.pi/2,0,0))])
            lbox(footroot,'Gear_Foot_'+prefix,(0,0,0),(.32,.44,.115),D,.022)
            lbox(footroot,prefix+'_FootTread',(0,0,.06),(.22,.28,.026),P,.013)
            for dy in (-.10,0,.10):lbox(footroot,prefix+'_ContactGroove',(0,dy,-.058),(.24,.023,.008),A,.001)
            box(prefix+'_BayHousing',(sign*.74,y+direction*.21,-.30),(.36,.88,.10),D,bevel=.025)


def role_scout():
    perimeter=OUTLINE+ [(-x,y) for x,y in OUTLINE[-2:0:-1]]
    track=[]
    for i,(x,y) in enumerate(perimeter):
        prev=Vector(perimeter[i-1]);nxt=Vector(perimeter[(i+1)%len(perimeter)])
        normal=Vector((nxt.y-prev.y,prev.x-nxt.x)).normalized()
        x+=normal.x*.012;y+=normal.y*.012
        track.append((x,y,(skin(x,y)+skin(x,y,False))*.5))
    band=b.ribbon('Scout_RecessedScanBand',track+[track[0]],.035,D)
    b.ribbon('Scout_OpticalScanBand',track+[track[0]],.008,C)
    bpy.ops.mesh.primitive_uv_sphere_add(segments=16,ring_count=8,radius=.027)
    point=bpy.context.object;point.name='ScanPoint';point.data.materials.append(C)
    lengths=[0.];closed=track+[track[0]]
    for a,z in zip(closed,closed[1:]):lengths.append(lengths[-1]+(Vector(z)-Vector(a)).length)
    for i,xyz in enumerate(closed):
        point.location=xyz;point.keyframe_insert(data_path='location',frame=70+48*lengths[i]/lengths[-1])
    pose(point,'scale',[(1,(0,0,0)),(69,(0,0,0)),(70,(1,1,1)),(118,(1,1,1)),(119,(0,0,0))])
    for x in (-.70,.70):
        obj=b.cylinder('Scout_StereoLens',(x,-2.575,-.275),.055,.018,G,32);obj.rotation_euler.x=math.pi/2
    panel('Scout_FlushDorsalArray',[(0,-.28),(.37,-.24),(.38,.52),(0,.60)],D,.018)
    for y in (-.10,.12,.34):line('Scout_ArraySegment',[(.06,y),(.31,y)],A,.01)
    anchor('ScannerLocalOrigin',(0,0,.05))


def arm(root_name,sign,root_pos):
    root=anchor(root_name,root_pos)
    pose(root,'rotation_euler',[(1,(0,-sign*math.pi/2,0)),(40,(0,-sign*math.pi/2,0)),(80,(0,-sign*.72,0)),(120,(0,-sign*math.pi/2,0))])
    lcyl(root,'Arm_ShoulderMotor',(0,0,0),.15,.20,D,'Y')
    lcyl(root,'Arm_ShoulderRing',(0,-.112,0),.123,.033,O,'Y')
    lbox(root,'Arm_UpperLink',(0,0,-.28),(.16,.17,.47),A,.035)
    lbox(root,'Arm_LinkInlay',(0,-.091,-.28),(.074,.015,.30),E,.009)
    for x in (-.092,.092):lcyl(root,'Arm_HydraulicRod',(x,0,-.29),.020,.38,E)
    elbow=anchor('Arm_ElbowPivot',(0,-.14,-.56));elbow.parent=root
    pose(elbow,'rotation_euler',[(1,(0,sign*math.pi,0)),(40,(0,sign*math.pi,0)),(80,(0,sign*.32,0)),(120,(0,sign*math.pi,0))])
    lcyl(elbow,'Arm_ElbowMotor',(0,0,0),.13,.18,D,'Y')
    lcyl(elbow,'Arm_ElbowCap',(0,-.10,0),.10,.025,O,'Y')
    lbox(elbow,'Arm_Forearm',(0,0,-.27),(.13,.15,.44),P,.03)
    lbox(elbow,'Arm_Wrist',(0,0,-.55),(.18,.19,.12),D,.025)
    lcyl(elbow,'Arm_WristRing',(0,0,-.50),.11,.035,O)
    for finger in (-1,1):
        obj=p.beam('Arm_GripperProximal',(finger*.06,0,-.59),(finger*.13,0,-.72),.042,.07,E,.009);child(obj,elbow)
        obj=p.beam('Arm_GripperTip',(finger*.13,0,-.72),(finger*.055,0,-.82),.03,.052,D,.007);child(obj,elbow)
    child(b.curve('Arm_ServiceCable',[(.09,.08,-.1),(.13,.09,-.27),(.09,.08,-.43)],D,.018),elbow)
    return root


def role_engineer():
    box('Engineer_LoadBearingKeel',(0,-1.72,-.42),(1.65,.33,.20),A,bevel=.038)
    for sign in (-1,1):arm('Engineer_ArmRoot',sign,(sign*.78,-1.72,-.44))
    p.annular_ring('Engineer_WorkOpticBezel',0,-1.38,.17,.11,-.59,-.48,D,mirrored=False)
    b.cylinder('Engineer_WorkOptic',(0,-1.38,-.581),.095,.020,C,48)
    anchor('EngineerBeamOrigin',(0,-1.38,-.595))


def role_cargo():
    box('Cargo_Ceiling',(0,-.13,-.51),(1.45,1.96,.13),D,bevel=.04)
    box('Cargo_Floor',(0,-.13,-.96),(1.46,1.96,.12),A,bevel=.04)
    box('Cargo_SideWall',(.73,-.13,-.73),(.12,1.91,.50),A,True,.03)
    box('Cargo_RearWall',(0,.84,-.74),(1.36,.12,.48),P,bevel=.027)
    for y in (-.84,-.14,.59):
        box('Cargo_FrameRib',(.785,y,-.72),(.06,.095,.46),P,True,.008)
        box('Cargo_ServiceLock',(.82,y,-.73),(.027,.065,.14),O,True,.007)
    doorroot=anchor('Cargo_RampHinge',(0,-1.13,-1.00))
    pose(doorroot,'rotation_euler',[(1,(0,0,0)),(40,(0,0,0)),(80,(math.pi/2,0,0)),(120,(0,0,0))])
    lbox(doorroot,'Cargo_FrontRamp',(0,0,.26),(1.36,.075,.51),A,.025)
    for x in (-.45,-.15,.15,.45):lbox(doorroot,'Cargo_RampTread',(x,-.044,.26),(.10,.025,.40),E,.006)
    for x in (-.34,.34):
        for y in (-.62,.06):
            box('Cargo_ReferenceCase',(x,y,-.746),(.55,.55,.30),P,bevel=.035)
            for dx in (-.18,.18):box('Cargo_CaseStrap',(x+dx,y,-.741),(.042,.57,.31),D,bevel=.005)
            box('Cargo_CaseLatch',(x,y-.286,-.744),(.065,.018,.12),O,bevel=.006)
    anchor('CargoDoorOrigin',(0,-1.14,-.73))


def role_salvage():
    box('Salvage_WinchKeel',(0,-.60,-.45),(1.23,1.05,.18),A,bevel=.045)
    for sign in (-1,1):
        box('Salvage_WinchBracket',(sign*.51,-.64,-.68),(.12,.55,.45),P,bevel=.04)
        obj=b.cylinder('Salvage_DrumFlange',(sign*.445,-.64,-.71),.255,.065,E,64);obj.rotation_euler.y=math.pi/2
        obj=b.cylinder('Salvage_DrumRim',(sign*.47,-.64,-.71),.235,.025,D,64);obj.rotation_euler.y=math.pi/2
        for angle in range(0,360,60):
            a=math.radians(angle)
            obj=b.cylinder('Salvage_FlangeBolt',(sign*.49,-.64+.19*math.sin(a),-.71+.19*math.cos(a)),.020,.013,O,12);obj.rotation_euler.y=math.pi/2
    drum=b.cylinder('Salvage_WinchDrum',(0,-.64,-.71),.20,.84,D,64);drum.rotation_euler.y=math.pi/2
    # Two mirrored winding halves retain exact structural symmetry.
    pts=[]
    for i in range(961):
        t=i/960;a=math.tau*9*t
        pts.append((.012+.38*t,-.64+.208*math.sin(a),-.71+.208*math.cos(a)))
    b.curve('Salvage_WoundCable',pts,E,.013,mirrored=True)
    hookroot=anchor('Salvage_HookTravel',(0,-.64,-1.00))
    pose(hookroot,'delta_location',[(1,(0,0,0)),(40,(0,0,0)),(80,(0,0,-.90)),(120,(0,0,0))])
    lbox(hookroot,'Salvage_GrappleHead',(0,0,0),(.26,.21,.15),A,.027)
    lcyl(hookroot,'Salvage_GrapplePivot',(0,-.12,-.04),.09,.03,E,'Y')
    for sign in (-1,1):
        verts=[(sign*x,y,z) for y in (-.07,.07) for x,z in ((.04,-.05),(.18,-.12),(.16,-.27),(.02,-.34),(.01,-.27),(.095,-.21),(.105,-.145),(.025,-.105))]
        faces=[tuple(range(7,-1,-1)),tuple(range(8,16))]+[(i,(i+1)%8,(i+1)%8+8,i+8) for i in range(8)]
        child(rawmesh('Salvage_GrappleJaw',verts,faces,O),hookroot)
    cable=b.cylinder('Salvage_PayoutCable',(0,-.64,-.958),.015,.13,E,16)
    pose(cable,'delta_location',[(1,(0,0,0)),(40,(0,0,0)),(80,(0,0,-.45)),(120,(0,0,0))])
    pose(cable,'scale',[(1,(1,1,1)),(40,(1,1,1)),(80,(1,1,1+.90/.13)),(120,(1,1,1))])
    # The hook nests into its front recess for landing rather than projecting below the feet.
    pose(hookroot,'delta_location',[(1,(0,0,.36)),(40,(0,0,.36)),(80,(0,0,-.90)),(120,(0,0,.36))])
    box('Salvage_HookCradle',(0,-.76,-.63),(.39,.38,.15),D,bevel=.035)
    for sign in (-1,1):
        obj=p.beam('Salvage_LoadStabilizer',(sign*.61,-.23,-.50),(sign*.92,-.45,-.85),.12,.12,A,.02)
        box('Salvage_StabilizerPad',(sign*.93,-.45,-.87),(.29,.38,.08),D,bevel=.02)
    anchor('SalvageLoadOrigin',(0,-.64,-1.95))


def role_security(body):
    center=Vector((0,-2.13,-.45));radius=.34
    verts=[tuple(center+Vector((0,0,-radius)))];faces=[]
    for j in range(1,25):
        t=math.pi*j/48
        for i in range(64):
            a=i*math.tau/64;verts.append(tuple(center+Vector((radius*math.sin(t)*math.cos(a),radius*math.sin(t)*math.sin(a),-radius*math.cos(t)))))
    faces.extend((0,1+(i+1)%64,1+i) for i in range(64))
    for j in range(23):
        for i in range(64):
            a=1+j*64+i;z=1+j*64+(i+1)%64;faces.append((a,z,z+64,a+64))
    half(rawmesh('Security_BlackHemisphericalCover',verts,faces,G,True))
    p.annular_ring('Security_FlushDomeRetainer',0,-2.13,.36,.335,-.47,-.443,D,mirrored=False)
    anchor('LaserOpticalOrigin',(0,-2.13,-.64));anchor('LaserCoverExit',(0,-2.13,-.793))
    for sign in (-1,1):
        box('Security_GunReceiver',(sign*.90,-2.00,-.295),(.22,.49,.20),A,bevel=.029)
        gun=b.cylinder('Security_EmbeddedGunMuzzle',(sign*.90,-2.258,-.285),.059,.035,D,48);gun.rotation_euler.x=math.pi/2
        rim=b.cylinder('Security_GunCollar',(sign*.90,-2.247,-.285),.075,.026,E,48);rim.rotation_euler.x=math.pi/2
        anchor('GunMuzzle.'+('R' if sign>0 else 'L'),(sign*.90,-2.278,-.285))
    cut_recess(body,'Security_MissileMagazine',[(.14,.20),(.53,.20),(.53,1.02),(.14,1.02)],.36)
    for sign in (-1,1):
        pivot=anchor('Security_MissileHatchHinge',(sign*.57,.6,skin(.57,.6)+.025))
        poly=[(sign*.135,.19),(sign*.55,.19),(sign*.55,1.035),(sign*.135,1.035)]
        pts=[(x-pivot.location.x,y-pivot.location.y,skin(x,y)+.008-pivot.location.z) for x,y in poly]
        door=child(rawmesh('Security_MissileHatch',pts,[(0,1,2,3)],A),pivot)
        solid=door.modifiers.new('HatchThickness','SOLIDIFY');solid.thickness=.035;solid.offset=-1
        pose(pivot,'rotation_euler',[(1,(0,0,0)),(40,(0,0,0)),(80,(0,0,0)),(96,(0,-sign*1.4,0)),(106,(0,-sign*1.4,0)),(120,(0,0,0))])
        for index,y in enumerate((.34,.60,.86)):
            z=skin(.335,y)-.14
            b.cylinder('Security_LaunchCell',(sign*.335,y,z),.086,.24,D,48)
            missile=anchor('Security_MicroMissile',(sign*.335,y,z+.028))
            lcyl(missile,'Security_MissileBody',(0,0,-.057),.044,.16,P)
            bpy.ops.mesh.primitive_cone_add(vertices=32,radius1=.044,radius2=.014,depth=.073,location=(0,0,.06))
            obj=bpy.context.object;obj.name='Security_MissileNose';obj.data.materials.append(D);obj.parent=missile
            pose(missile,'delta_location',[(1,(0,0,0)),(96+index,(0,0,0)),(100+index,(0,0,.8)),(106+index,(0,0,1.8)),(120,(0,0,0))])
            anchor('MissileExit_%s_%s'%(sign,index),(sign*.335,y,skin(.335,y)+.02))


def role_field():
    box('Field_ServiceKeel',(0,-.27,-.51),(.87,1.29,.20),A,bevel=.065)
    for y in (-.68,-.18,.29):
        box('Field_ServiceConnector',(.22,y,-.623),(.15,.24,.029),D,True,.015)
        box('Field_Contact',(.22,y,-.642),(.045,.16,.015),O,True,.006)


def setup():
    scene=bpy.context.scene;scene.render.engine='BLENDER_EEVEE'
    scene.render.resolution_x=1680;scene.render.resolution_y=1050;scene.render.resolution_percentage=100
    scene.render.image_settings.file_format='PNG';scene.render.image_settings.color_mode='RGBA'
    scene.render.film_transparent=True
    scene.world.use_nodes=True
    scene.world.node_tree.nodes['Background'].inputs[0].default_value=(.16,.18,.21,1)
    scene.world.node_tree.nodes['Background'].inputs[1].default_value=.30
    scene.view_settings.look='AgX - Medium High Contrast'
    scene.view_settings.exposure=-.3
    for loc,energy,size in (((-5,-6,9),900,7),((6,-1,6),700,7),((0,6,7),1250,6),((0,-4,-4),220,5)):
        bpy.ops.object.light_add(type='AREA',location=loc);li=bpy.context.object;li.data.energy=energy;li.data.shape='DISK';li.data.size=size;b.look(li)
    bpy.ops.object.camera_add();cam=bpy.context.object;cam.data.type='ORTHO';cam.data.ortho_scale=9.7;scene.camera=cam
    return cam


def build(role='base'):
    shared.reset()
    body=hull();skin_detail(body);fan();nose();belly()
    if FITTED:
        lift=FITTED['fanHubZ']-.145
        radial=FAN_R/.985
        bpy.context.view_layer.update()
        for obj in bpy.context.scene.objects:
            if obj.name.startswith('R0') and obj.type=='MESH':
                transform=obj.matrix_world.copy()
                for vertex in obj.data.vertices:
                    point=transform@vertex.co
                    recessed=.027 if obj.name.startswith(('R01','R02','R08')) else 0
                    vertex.co=(CX+(point.x-CX)*radial,CY+(point.y-CY)*radial,point.z+lift-recessed)
                obj.matrix_world=Matrix.Identity(4)
            elif obj.name.startswith('RotorAxis'):obj.location.z+=lift
    if CAGE:
        bpy.context.view_layer.update()
        for obj in bpy.context.scene.objects:
            if obj.type=='MESH':
                coords=[tuple(obj.matrix_world@v.co) for v in obj.data.vertices]
                rigid=obj.name.startswith(('S03','S04','S05','S06','S07','H04','H05','H08','W05','R0'))
                if rigid:
                    center=sum((Vector(xyz) for xyz in coords),Vector())/len(coords)
                    delta=Vector(CAGE_MODULE.deform([center],CAGE['values'],CAGE['fanY'])[0])-center
                    result=[Vector(xyz)+delta for xyz in coords]
                else:result=CAGE_MODULE.deform(coords,CAGE['values'],CAGE['fanY'])
                for vertex,xyz in zip(obj.data.vertices,result):vertex.co=xyz
                obj.matrix_world=Matrix.Identity(4)
            elif obj.type=='CURVE':
                transform=obj.matrix_world.copy()
                for spline in obj.data.splines:
                    coords=[tuple(transform@Vector(p.co[:3])) for p in spline.points]
                    result=CAGE_MODULE.deform(coords,CAGE['values'],CAGE['fanY'])
                    for point,xyz in zip(spline.points,result):point.co=(*xyz,1)
                obj.matrix_world=Matrix.Identity(4)
            elif obj.type=='EMPTY' and obj != b.MIRROR_ORIGIN:
                obj.location=CAGE_MODULE.deform([tuple(obj.location)],CAGE['values'],CAGE['fanY'])[0]
    # Share normals on continuous skin, but retain real sharp bends and recesses.
    # Splitting only these edges works in both Blender and exported glTF normals.
    bm=bmesh.new();bm.from_mesh(body.data)
    sharp=[edge for edge in bm.edges if edge.is_manifold and edge.calc_face_angle()>math.radians(32)]
    bmesh.ops.split_edges(bm,edges=sharp)
    for face in bm.faces:face.smooth=True
    bm.to_mesh(body.data);bm.free();body.data.update()
    return body


def fit_camera(camera,scene):
    az,el,scale,distance,tx,ty=FITTED['cameraParameters']
    width,height=1681,936
    scene.render.resolution_x=width;scene.render.resolution_y=height
    camera.location=Vector((math.sin(az)*math.cos(el),-math.cos(az)*math.cos(el),math.sin(el)))*distance
    b.look(camera,(0,0,0));camera.data.type='PERSP';camera.data.sensor_fit='HORIZONTAL';camera.data.sensor_width=36
    camera.data.lens=scale*distance*36/width
    camera.data.shift_x=(width/2-tx)/width;camera.data.shift_y=(ty-height/2)/width


def main():
    global FITTED,CY,OUTLINE,FAN_R,CAGE,CAGE_MODULE
    args=argparse.ArgumentParser();args.add_argument('--quick',action='store_true');args.add_argument('--role',default='base',choices=['base']);args.add_argument('--fitted',action='store_true')
    args.add_argument('--cage',action='store_true')
    options=args.parse_args(sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else [])
    if options.fitted:
        FITTED=json.loads((OUT/'analysis/reference-shape-fit.json').read_text(encoding='utf-8'))
        CY=FITTED['fanY'];FAN_R=.88
        OUTLINE[4]=(3.98,.24);OUTLINE[5]=tuple(FITTED['frontTip'][:2]);OUTLINE[6]=tuple(FITTED['rearTip'][:2])
    if options.cage:
        if not options.fitted:raise ValueError('--cage requires --fitted')
        CAGE=json.loads((OUT/'analysis/reference-surface-cage.json').read_text(encoding='utf-8'))
        cage_spec=importlib.util.spec_from_file_location('surface_cage',ROOT/'tools/reference_surface_cage.py')
        CAGE_MODULE=importlib.util.module_from_spec(cage_spec);cage_spec.loader.exec_module(CAGE_MODULE)
    path=OUT/options.role;path.mkdir(parents=True,exist_ok=True)
    build(options.role)
    camera=setup();scene=bpy.context.scene
    views={'hero':(6,-12,8),'front':(0,-14,.2),'top':(0,0,14),'bottom':(0,0,-14),'rear':(0,14,.2),'left':(-14,0,.2),'right':(14,0,.2),'underside':(6,-12,-7)}
    for name,loc in views.items():
        if options.quick and name!='hero':continue
        camera.data.type='ORTHO';camera.data.shift_x=0;camera.data.shift_y=0
        camera.location=loc;b.look(camera,(0,-.25,.10))
        if name=='hero' and FITTED:fit_camera(camera,scene)
        scene.render.filepath=str(path/(name+'.png'));bpy.ops.render.render(write_still=True)
    report=shared.validation(options.role);report['referenceFidelityApproved']=False
    report['glbTextureParityVerified']=False
    (path/'validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    camera.location=views['hero'];b.look(camera,(0,-.25,.10))
    if FITTED:fit_camera(camera,scene)
    bpy.context.view_layer.update()
    points={'nose_visor':(0,-2.668,-.10),'nose_right_corner':(.57,-2.625,-.08),'chin_sensor':(0,-2.77,-.365),
            'fan_left':(-CX,CY,.145),'fan_right':(CX,CY,.145),
            'tip_left_front':(-4.40,.31,skin(4.40,.31)),
            'tip_left_rear':(-4.43,1.28,skin(4.43,1.28)),
            'tip_right_front':(4.40,.31,skin(4.40,.31)),
            'tip_right_rear':(4.43,1.28,skin(4.43,1.28)),
            'rear_crown':(0,1.60,skin(0,1.60))}
    if FITTED:
        for side,sign in (('left',-1),('right',1)):
            points['fan_'+side]=(sign*CX,CY,FITTED['fanHubZ'])
            for end,key in (('front','frontTip'),('rear','rearTip')):
                x,y,z=FITTED[key];points['tip_'+side+'_'+end]=(sign*x,y,z)
    if CAGE:points={key:list(CAGE_MODULE.deform([xyz],CAGE['values'],CAGE['fanY'])[0]) for key,xyz in points.items()}
    landmarks={}
    for key,xyz in points.items():
        uv=world_to_camera_view(scene,camera,Vector(xyz))
        landmarks[key]={'xyz':xyz,'xy':[uv.x*scene.render.resolution_x,(1-uv.y)*scene.render.resolution_y]}
    (path/'landmarks.json').write_text(json.dumps(landmarks,indent=2),encoding='utf-8')
    obj=bpy.data.objects['H01_ContinuousLiftingBody'];ev=obj.evaluated_get(bpy.context.evaluated_depsgraph_get());me=ev.to_mesh()
    vertices=[list(obj.matrix_world@v.co) for v in me.vertices]
    (path/'hull-mesh.json').write_text(json.dumps({'vertices':vertices,'faces':[list(p.vertices) for p in me.polygons]}),encoding='utf-8');ev.to_mesh_clear()
    if not options.quick:
        clay=b.mat('Diagnostic_Clay',(.22,.22,.22),0,.65)
        scene.view_layers[0].material_override=clay
        scene.render.filepath=str(path/'clay.png');bpy.ops.render.render(write_still=True)
        wire=bpy.data.materials.new('Diagnostic_VisibleWire');wire.use_nodes=True
        nodes=wire.node_tree.nodes;nodes.clear();links=wire.node_tree.links
        output=nodes.new('ShaderNodeOutputMaterial');emission=nodes.new('ShaderNodeEmission')
        color=nodes.new('ShaderNodeMixRGB');color.inputs[1].default_value=(0,0,0,1);color.inputs[2].default_value=(.10,.65,.85,1)
        edges=nodes.new('ShaderNodeWireframe');edges.use_pixel_size=True;edges.inputs['Size'].default_value=.7
        links.new(edges.outputs['Fac'],color.inputs[0]);links.new(color.outputs[0],emission.inputs['Color']);links.new(emission.outputs[0],output.inputs['Surface'])
        scene.view_layers[0].material_override=wire
        scene.render.filepath=str(path/'visible-wire.png');bpy.ops.render.render(write_still=True)
        scene.view_layers[0].material_override=None
    scene['asset_status']='UNAPPROVED_REFERENCE_RECONSTRUCTION'
    scene['reference_image']='docs/design/blended-wing-v5/concept.png'
    bpy.ops.wm.save_as_mainfile(filepath=str(path/(options.role+'.blend')))
    if not options.quick:
        bpy.ops.object.select_all(action='DESELECT')
        for obj in scene.objects:
            if obj.type in ('MESH','CURVE','EMPTY'):obj.select_set(True)
        bpy.ops.export_scene.gltf(filepath=str(path/(options.role+'.glb')),export_format='GLB',use_selection=True)
    print('MODEL_REPORT',json.dumps(report),flush=True)


if __name__=='__main__':main()
