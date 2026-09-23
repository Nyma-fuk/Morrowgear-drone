"""Staging-only propulsion restyle from V15. Dimensions are art units, not engineering data."""
import argparse
import hashlib
import importlib.util
import json
import math
import sys
from pathlib import Path

import bpy
import bmesh
from mathutils import Vector, kdtree
from mathutils.bvhtree import BVHTree

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / 'docs/design/reference-nose-v15/airframe.blend'
OUT = ROOT / 'docs/design/reference-propulsion-v16'
MATERIALS = {}
sys.path.insert(0,str(ROOT/'tools'))
import reference_wingtip_sections_v14 as sections


def smooth(t):
    t = max(0., min(1., t))
    return t*t*(3-2*t)


def aft_shape(p):
    x, y, z = p
    lateral = 1-smooth((abs(x)-1.20)/1.00)
    aft = smooth((y-.95)/1.30)
    lo,hi=sections.skin(x,y,False),sections.skin(x,y,True)
    u=max(0,min(1,(z-lo)/max(.01,hi-lo)))
    thickness = (-.085+.30*u) * aft * lateral
    outz=z+thickness
    # Upper and lower aft skins taper together into the mouth. The edge is not
    # thickened into a vertical wall across the entire rear wing.
    transition=smooth((y-1.73)/.62)*(1-smooth((abs(x)-1.15)/.60))
    target=(-.103*(1-u)+.316*u)
    outz=outz*(1-transition)+target*transition
    return Vector((x, y+.26*aft*lateral, outz))


def mesh(name, verts, faces, material, mirrored=False, smooth_faces=False):
    if mirrored:
        n = len(verts)
        verts = list(verts)+[(-x,y,z) for x,y,z in verts]
        faces = list(faces)+[tuple(n+i for i in reversed(f)) for f in faces]
    data = bpy.data.meshes.new(name)
    data.from_pydata(verts, [], faces)
    data.update()
    obj = bpy.data.objects.new(name, data)
    bpy.context.collection.objects.link(obj)
    data.materials.append(material)
    for poly in data.polygons:
        poly.use_smooth = smooth_faces
    return obj


def weld_normals(obj, split=False):
    bm = bmesh.new()
    bm.from_mesh(obj.data)
    bmesh.ops.remove_doubles(bm, verts=list(bm.verts), dist=1e-6)
    bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
    if split:
        sharp = [e for e in bm.edges if e.is_manifold and e.calc_face_angle() > math.radians(35)]
        bmesh.ops.split_edges(bm, edges=sharp)
    bm.to_mesh(obj.data)
    bm.free()
    obj.data.update()


def tube(name, rings, material, closed=False, mirrored=False):
    count = len(rings[0])
    verts = [p for r in rings for p in r]
    faces = [(j*count+i,j*count+(i+1)%count,(j+1)*count+(i+1)%count,(j+1)*count+i)
             for j in range(len(rings)-1) for i in range(count)]
    if closed:
        faces += [tuple(reversed(range(count))), tuple((len(rings)-1)*count+i for i in range(count))]
    obj = mesh(name, verts, faces, material, mirrored)
    weld_normals(obj)
    return obj


def octagon(xc, y, width, bottom, top, chamfer=.045, sweep=0):
    # Clockwise around the aft-facing opening. Beveled corners remain deliberately broad.
    pts = [(-width+chamfer,bottom),(width-chamfer,bottom),(width,bottom+chamfer),
           (width,top-chamfer),(width-chamfer,top),(-width+chamfer,top),
           (-width,top-chamfer),(-width,bottom+chamfer)]
    return [(xc+x,y-sweep*abs(x)/width,z) for x,z in pts]


def subtract(body, cutter):
    bpy.context.view_layer.objects.active = body
    mod = body.modifiers.new('Actual_recess_'+cutter.name, 'BOOLEAN')
    mod.operation = 'DIFFERENCE'
    mod.solver = 'EXACT'
    mod.object = cutter
    bpy.ops.object.modifier_apply(modifier=mod.name)
    bpy.data.objects.remove(cutter, do_unlink=True)


def snapshot(obj):
    graph = bpy.context.evaluated_depsgraph_get()
    ev = obj.evaluated_get(graph)
    data = ev.to_mesh()
    vs = [ev.matrix_world@v.co for v in data.vertices]
    fs = [list(p.vertices) for p in data.polygons]
    ev.to_mesh_clear()
    return vs,fs


def deform_existing():
    deleted = ('H09_', 'H10_', 'H11_', 'H12_', 'H13_', 'H14_', 'B01_', 'B02_', 'W01_PanelJoint_02')
    protected = {}
    old_front = []
    for obj in list(bpy.context.scene.objects):
        if obj.name.startswith(deleted):
            bpy.data.objects.remove(obj, do_unlink=True)
            continue
        if obj.type not in ('MESH','CURVE'):
            continue
        vs,fs = snapshot(obj)
        if obj.name.startswith(('R0','W04','W07','W08','S0')):
            protected[obj.name] = vs
        if obj.name.startswith('H01_'):
            old_front = [p for p in vs if p.y <= .95]
        changed = [aft_shape(p) for p in vs]
        if max((a-b).length for a,b in zip(vs,changed)) < 1e-8:
            continue
        name = obj.name
        graph = bpy.context.evaluated_depsgraph_get()
        data = bpy.data.meshes.new_from_object(obj.evaluated_get(graph), preserve_all_data_layers=True, depsgraph=graph)
        for v,p in zip(data.vertices,changed):
            v.co = p
        new = bpy.data.objects.new(name+'_rebuilt',data)
        bpy.context.collection.objects.link(new)
        bpy.data.objects.remove(obj,do_unlink=True)
        new.name = name
    bpy.context.view_layer.update()
    body = next(o for o in bpy.context.scene.objects if o.name.startswith('H01_'))
    weld_normals(body)
    return body,protected,old_front


def surface_tree(body):
    return BVHTree.FromPolygons(*snapshot(body))


def surface(tree,x,y,top=True):
    p,*_ = tree.ray_cast(Vector((x,y,3 if top else -3)),Vector((0,0,-1 if top else 1)))
    if p is None:
        raise ValueError(('No attachment surface',x,y,top))
    return p.z


def intake(body,tree):
    xc = .57
    # The open mouth leads into a downward-curved blind throat, not a black cap at the lip.
    inner = [octagon(xc,1.30,.32,.473,.694,.038),
             octagon(xc,1.44,.32,.455,.674,.038),
             octagon(xc,1.66,.29,.383,.590,.038),
             octagon(xc,1.96,.24,.300,.488,.035)]
    outer = [octagon(xc,1.295,.378,.448,.746,.055),
             octagon(xc,1.43,.408,.421,.731,.058),
             octagon(xc,1.70,.390,.313,.642,.055),
             octagon(xc,2.02,.350,.246,.532,.050),
             octagon(xc,2.40,.285,.180,.324,.044)]
    tube('P01_IntakeCowl',outer,MATERIALS['hull'],closed=True,mirrored=True)
    cowl = bpy.data.objects['P01_IntakeCowl']
    for sign in (-1,1):
        cutter_rings = [[(sign*x,y,z) for x,y,z in ring] for ring in inner]
        cutter_rings[0] = [(x,y-.08,z) for x,y,z in cutter_rings[0]]
        subtract(cowl,tube('IntakeCutter',cutter_rings,MATERIALS['dark'],closed=True))
        subtract(body,tube('BodyIntakeCutter',cutter_rings,MATERIALS['dark'],closed=True))
    tube('P02_IntakeLip',[outer[0],inner[0]],MATERIALS['edge'],mirrored=True)
    tube('P03_IntakeThroat',inner,MATERIALS['dark'],mirrored=True)
    mesh('P04_IntakeBack',inner[-1],[tuple(range(8))],MATERIALS['dark'],True)
    # Side skirts blend the upper cowl into the actual wing surface.
    for direction in (-1,1):
        verts=[]
        anchors=[(1.18,.335,.45),(1.295,.378,.691),(1.43,.408,.673),
                 (1.70,.390,.587),(2.02,.350,.482),(2.40,.285,.280)]
        rows=[]
        for a,b in zip(anchors,anchors[1:]):
            for i in range(9):
                t=i/9;rows.append(tuple(a[j]*(1-t)+b[j]*t for j in range(3)))
        rows.append(anchors[-1])
        for y,w,h in rows:
            innerx=xc+direction*w
            outerx=max(.015,innerx+direction*.27)
            z0=max(surface(tree,innerx,y)+.002,h)
            for j in range(9):
                t=j/8;x=innerx*(1-t)+outerx*t
                base=surface(tree,x,y)
                verts.append((x,y,base+.002+(z0-base)*(1-smooth(t))))
        fs=[(r*9+j,r*9+j+1,(r+1)*9+j+1,(r+1)*9+j) for r in range(len(rows)-1) for j in range(8)]
        mesh('P05_WingCowlBlend',verts,fs,MATERIALS['hull'],True,True)
    # Short ribs recessed inside the mouth show depth without sealing the inlet.
    for dx in (-.12,.12):
        x=xc+dx
        v=[(x-.010,1.65,.39),(x+.010,1.65,.39),(x+.010,1.84,.335),(x-.010,1.84,.335),
           (x-.010,1.65,.578),(x+.010,1.65,.578),(x+.010,1.84,.523),(x-.010,1.84,.523)]
        mesh('P06_IntakeGuide',v,[(0,1,2,3),(4,7,6,5),(0,4,5,1),(3,2,6,7)],MATERIALS['titanium'],True)
    for y0,y1,z0,z1 in ((1.50,1.92,.709,.568),):
        mesh('P07_CowlWarning',[(xc+.08,y0,z0),(xc+.104,y0,z0),(xc+.104,y1,z1),(xc+.08,y1,z1)],
             [(0,1,2,3)],MATERIALS['amber'],True)


def exhaust(body):
    outer=octagon(0,2.644,1.092,-.106,.316,.154,.055)
    inner=octagon(0,2.641,1.035,-.056,.258,.122,.055)
    throat=[inner,octagon(0,2.43,.985,-.070,.260,.124),
            octagon(0,2.17,.900,-.054,.282,.126),octagon(0,1.91,.780,-.034,.300,.124)]
    cutter=[[(x,y+.16,z) for x,y,z in inner],*throat]
    subtract(body,tube('ExhaustCavityCutter',cutter,MATERIALS['dark'],closed=True))
    # Cowls overlap the hull for a sealed attachment. Their hidden back caps
    # must not re-seal the upper part of the exhaust cavity.
    subtract(bpy.data.objects['P01_IntakeCowl'],tube('CowlExhaustCutter',cutter,MATERIALS['dark'],closed=True))
    tube('P10_ExhaustRim',[outer,inner],MATERIALS['rim'])
    tube('P11_ExhaustThroat',throat,MATERIALS['dark'])
    mesh('P12_ExhaustBack',throat[-1],[tuple(range(8))],MATERIALS['dark'])
    # Segmented upper/lower nozzle petals, tapered into the rear wing shoulder.
    for side in (-1,1):
        for i in range(8):
            a=-.952+i*1.904/8+.006;b=a+1.904/8-.012
            z = .269 if side==1 else -.068
            frontz=.273 if side==1 else -.083
            pts=[(a,2.37,frontz),(b,2.37,frontz),(b,2.626-.055*abs(b)/1.13,z),
                 (a,2.626-.055*abs(a)/1.13,z)]
            mesh('P13_HeatPetal',pts,[(0,1,2,3)],MATERIALS['petal'])
    for x in (-.45,0,.45):
        tube('P14_InternalMixer',[octagon(x,2.03,.015,-.024,.250,.009),
                                  octagon(x,1.96,.015,-.024,.250,.009)],MATERIALS['petal'],closed=True)
    # A shallow aft apron carries the lower wing line under the exhaust.
    xs=[-1.15,-.95,-.50,0,.50,.95,1.15]
    vs=[]
    for row in range(3):
        for x in xs:
            y0=2.41-.04*abs(x)
            y1=2.76-.18*abs(x)/1.15
            t=row/2
            vs.append((x,y0*(1-t)+y1*t,-.143+.028*t))
    apron=mesh('P15_ExhaustApron',vs,[(r*7+j,r*7+j+1,(r+1)*7+j+1,(r+1)*7+j) for r in range(2) for j in range(6)],MATERIALS['hull'])
    solid=apron.modifiers.new('Apron_actual_thickness','SOLIDIFY');solid.thickness=.025


def belly_panels(body):
    tree=surface_tree(body)
    for index,yc in enumerate((-1.24,-.48,.32,1.05)):
        vs=[]
        for row in range(17):
            y=yc-.275+.55*row/16
            for col in range(9):
                x=-.325+.65*col/8
                vs.append((x,y,surface(tree,x,y,False)-.004))
        obj=mesh('B01_ConformalServicePanel_%d'%index,vs,
                 [(r*9+c,(r+1)*9+c,(r+1)*9+c+1,r*9+c+1) for r in range(16) for c in range(8)],MATERIALS['hull'],smooth_faces=True)
        solid=obj.modifiers.new('Panel_thickness_into_body','SOLIDIFY');solid.thickness=.008


def force_body_symmetry(body):
    bm=bmesh.new();bm.from_mesh(body.data)
    bmesh.ops.bisect_plane(bm,geom=list(bm.verts)+list(bm.edges)+list(bm.faces),dist=1e-6,
                          plane_co=(0,0,0),plane_no=(1,0,0),clear_inner=True)
    bm.to_mesh(body.data);bm.free()
    mod=body.modifiers.new('Exact_centerline_mirror','MIRROR')
    mod.use_clip=True
    bpy.context.view_layer.objects.active=body
    bpy.ops.object.modifier_apply(modifier=mod.name)
    weld_normals(body,True)


def inspect(body,protected,front):
    vs,fs=snapshot(body)
    tree=BVHTree.FromPolygons(vs,fs)
    bm=bmesh.new();bm.from_mesh(body.data)
    bmesh.ops.remove_doubles(bm,verts=list(bm.verts),dist=1e-5)
    bad=sum(not e.is_manifold for e in bm.edges);volume=bm.calc_volume(signed=True);bm.free()
    allpoints=[];allvs=[];allfs=[];preserved={};new_points=[];panel_gaps=[]
    for obj in bpy.context.scene.objects:
        if obj.type not in ('MESH','CURVE'):continue
        points,faces=snapshot(obj)
        offset=len(allvs);allvs.extend(points);allfs.extend([[offset+i for i in f] for f in faces])
        allpoints.extend(points)
        if obj.name.startswith('P'):
            new_points.extend(points)
        if obj.name.startswith('B01_Conformal'):
            panel_tree=BVHTree.FromPolygons(points,faces)
            ys=[p.y for p in points]
            for x in (-.25,0,.25):
                for i in range(1,12):
                    y=min(ys)+(max(ys)-min(ys))*i/12
                    hp,*_=tree.ray_cast(Vector((x,y,-2)),Vector((0,0,1)))
                    pp,*_=panel_tree.ray_cast(Vector((x,y,2)),Vector((0,0,-1)))
                    if hp is None or pp is None:raise RuntimeError('Panel attachment probe missed')
                    panel_gaps.append(hp.z-pp.z)
        if obj.name in protected:
            old=protected[obj.name]
            preserved[obj.name]=len(old)==len(points) and max((a-b).length for a,b in zip(old,points))<1e-6
    kd=kdtree.KDTree(len(allpoints))
    for i,p in enumerate(allpoints):kd.insert(p,i)
    kd.balance()
    mirror=max(kd.find(Vector((-p.x,p.y,p.z)))[2] for p in allpoints)
    front_err=max(kd.find(p)[2] for p in front)
    scene_tree=BVHTree.FromPolygons(allvs,allfs)
    probes=[]
    for s in (-1,1):
        for dx in (-.19,0,.19):
            for z in (.535,.585,.635):
                origin=Vector((s*(.57+dx),1.299,z))
                p,n,idx,d=scene_tree.ray_cast(origin,Vector((0,1,-.21)).normalized())
                probes.append({'type':'intake','origin':list(origin),'depth':d,'hit':list(p) if p else None})
        for x in (0,.24,.67,.85):
            for z in (.01,.10,.20):
                origin=Vector((s*x,2.637-.055*x/1.035,z))
                p,n,idx,d=scene_tree.ray_cast(origin,Vector((0,-1,0)))
                probes.append({'type':'exhaust','origin':list(origin),'depth':d,'hit':list(p) if p else None})
    fan_gap=min(math.hypot(abs(p.x)-1.91,p.y-.01826677)-.90 for p in new_points)
    checks={'finiteGeometry':all(math.isfinite(c) for p in allpoints for c in p),
            'bilateralGeometry':mirror<1e-5,'protectedPartsUnchanged':all(preserved.values()),
            'frontHullUnchanged':front_err<1e-5,'watertightHull':bad==0,'positiveHullVolume':volume>0,
            'newPartsOutsideFanEnvelope':fan_gap>.05,
            'allBellyPanelsAttached':max(panel_gaps)<.001,
            'intakeHasRecess':all(p['depth'] is not None and p['depth']>.20 for p in probes if p['type']=='intake'),
            'exhaustHasRecess':all(p['depth'] is not None and p['depth']>(.30 if abs(p['origin'][0])>.8 else .40)
                                  for p in probes if p['type']=='exhaust')}
    return {'checks':checks,'passed':all(checks.values()),'mirrorError':mirror,'frontHullError':front_err,
            'nonmanifoldHullEdges':bad,'hullVolume':volume,'probes':probes,'protectedParts':preserved,
            'newPartMinimumFanRadialGap':fan_gap,'panelProbeCount':len(panel_gaps),'worstPanelGap':max(panel_gaps),
            'sourceSha256':hashlib.sha256(BASE.read_bytes()).hexdigest(),
            'parameters':{'intakeMouthWidth':.640,'oldIntakeFaceWidth':.260,'intakeMouthHeight':.221,
                          'exhaustMouthWidth':2.070,'exhaustMouthHeight':.314,'span':9.67327334,
                          'exhaustMouthWidthToSpan':2.070/9.67327334},
            'referenceFidelityApproved':False,'gameVerified':False,'aerodynamicsSimulated':False,
            'scope':'Visual proportion and cavity/attachment geometry. Not aircraft engineering.'}


def look(obj,target):
    obj.rotation_euler=(Vector(target)-obj.location).to_track_quat('-Z','Y').to_euler()


def renders(quick):
    spec=importlib.util.spec_from_file_location('nose_v15',Path(__file__).with_name('build_pointed_nose_v15.py'))
    prior=importlib.util.module_from_spec(spec);spec.loader.exec_module(prior)
    scene=bpy.context.scene;camera=scene.camera
    saved=camera.matrix_world.copy();data=camera.data.copy()
    if not quick:
        prior.OUT=OUT;prior.render_views()
    else:
        scene.render.filepath=str(OUT/'hero.png');bpy.ops.render.render(write_still=True)
    for name,loc,target,scale in (
        ('rear-quarter',(6,12,7),(0,-.3,.08),9.9),
        ('propulsion-close',(4,7,2.8),(0,1.9,.20),3.7),
        ('intake-close',(2,-5,3),(0,1.55,.39),3.25),
        ('rear-close',(0,7,.70),(0,1.85,.12),3.1)):
        camera.data.type='ORTHO';camera.data.ortho_scale=scale;camera.data.shift_x=0;camera.data.shift_y=0
        camera.location=loc;look(camera,target)
        bpy.ops.object.light_add(type='AREA',location=(0,5,-3));li=bpy.context.object
        li.data.energy=850;li.data.size=5;look(li,(0,1.8,0))
        scene.render.filepath=str(OUT/(name+'.png'));bpy.ops.render.render(write_still=True)
        bpy.data.objects.remove(li,do_unlink=True)
    camera.data=data;camera.matrix_world=saved


def main():
    import sys
    p=argparse.ArgumentParser();p.add_argument('--quick',action='store_true')
    options=p.parse_args(sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else [])
    OUT.mkdir(parents=True,exist_ok=True)
    bpy.ops.wm.open_mainfile(filepath=str(BASE))
    original_hash=hashlib.sha256(BASE.read_bytes()).hexdigest()
    for key,prefix in (('hull','H01_'),('dark','H10_AftOpening'),('edge','W02_'),('amber','W04_')):
        MATERIALS[key]=next(o for o in bpy.context.scene.objects if o.name.startswith(prefix)).data.materials[0]
    for key,color,roughness,metallic in (('titanium',(.11,.12,.125,1),.42,.65),
                                       ('petal',(.055,.061,.067,1),.60,.50),
                                       ('rim',(.021,.026,.032,1),.62,.28)):
        mat=bpy.data.materials.new('V16_'+key);mat.use_nodes=True
        node=mat.node_tree.nodes.get('Principled BSDF');node.inputs['Base Color'].default_value=color
        node.inputs['Roughness'].default_value=roughness;node.inputs['Metallic'].default_value=metallic
        MATERIALS[key]=mat
    body,protected,front=deform_existing()
    intake(body,surface_tree(body));exhaust(body);force_body_symmetry(body);belly_panels(body)
    bpy.context.view_layer.update()
    report=inspect(body,protected,front)
    report['sourceUnchanged']=hashlib.sha256(BASE.read_bytes()).hexdigest()==original_hash
    (OUT/'validation.json').write_text(json.dumps(report,indent=2))
    print('PROPULSION_REPORT',json.dumps(report),flush=True)
    renders(options.quick)
    scene=bpy.context.scene;scene['asset_status']='UNAPPROVED_PROPULSION_DESIGN_PROPOSAL'
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'airframe.blend'))
    if not options.quick:
        bpy.ops.object.select_all(action='DESELECT')
        for obj in scene.objects:
            if obj.type in ('MESH','CURVE','EMPTY'):obj.select_set(True)
        bpy.ops.export_scene.gltf(filepath=str(OUT/'airframe.glb'),export_format='GLB',use_selection=True)
    if not report['passed']:raise RuntimeError(report['checks'])


if __name__=='__main__':main()
