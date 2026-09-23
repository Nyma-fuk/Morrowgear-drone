"""Native V25 heavy carrier proposal. V24 models and all runtime assets are read-only."""
import argparse
import importlib.util
import json
import math
import sys
from pathlib import Path

sys.dont_write_bytecode = True
import bpy
from mathutils import Vector, kdtree
from mathutils.bvhtree import BVHTree

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[2]
spec = importlib.util.spec_from_file_location('proposal24', ROOT/'tools/drone-design/blender/build_supply_carrier_v24.py')
q = importlib.util.module_from_spec(spec)
spec.loader.exec_module(q)
e, d, v = q.e, q.d, q.v
HEIGHT = 14
VIEWS = q.VIEWS


def loft(name, outline, levels, material='armor', paired=False):
    cx = sum(p[0] for p in outline)/len(outline)
    cy = sum(p[1] for p in outline)/len(outline)
    n = len(outline)
    verts = [(cx+(x-cx)*scale, cy+(y-cy)*scale, z) for z,scale in levels for x,y in outline]
    faces = [tuple(reversed(range(n))), tuple(range((len(levels)-1)*n,len(levels)*n))]
    for j in range(len(levels)-1):
        for i in range(n):
            a = j*n+i
            b = j*n+(i+1)%n
            faces.append((a,b,b+n,a+n))
    return v.mesh(name, verts, faces, material, paired)


def box(name, loc, size, material='armor', paired=False, bevel=.08):
    return v.box(name,loc,size,material,paired,bevel)


def ring(name, loc, outer, inner, depth, material='edge', paired=False):
    obj = e.ring(name,loc,outer,inner,depth,material)
    return q.mirror(obj) if paired else obj


def bay(row,cx,cy,width,length,detail):
    box('BayCeiling'+row,(cx,cy,5.82),(width-.12,length-.12,.24),'dark',True)
    for x in (cx-width/2+.17,cx+width/2-.17):
        box('HangarFrameSide'+row,(x,cy,3.65),(.30,length,4.1),'edge',True,.06)
        if detail:
            for y in (cy-length/2+.7,cy+length/2-.7):
                box('HangarDepthRib'+row,(x, y,3.75),(.43,.20,3.8),'armor',True,.03)
            for y in (cy-length/2+.55,cy+length/2-.55):
                box('BayCaptureContact'+row,(x, y,2.65),(.31,.42,.22),'amber',True,.04)
    for y in (cy-length/2+.14,cy+length/2-.14):
        box('HangarFrameEnd'+row,(cx,y,3.65),(width,.25,4.1),'edge',True,.06)
        box('HangarLintel'+row,(cx,y,1.60),(width,.36,.32),'armor',True,.06)
    box('HangarCeilingLamp'+row,(cx,cy-length/2+.26,5.57),(width-.95,.12,.12),'cyan',True,.025)
    door=box('BayShutter'+row,(cx,cy,1.45),(width-.65,length-.65,.17),'armor',True,.04)
    door['slide_y']=0.0
    door['lift_z']=4.65


def optic(detail):
    outline=[(5.12*math.cos(i*math.tau/16),5.12*math.sin(i*math.tau/16)) for i in range(16)]
    loft('CentralEmitterArmoredDrum',outline,[(.52,.84),(1.12,1),(3.6,1)],'armor')
    ring('EmitterApertureRim',(0,0,.55),4.35,3.82,.75,'edge')
    e.cyl('CentralRecessedEmitterLens',0,0,.17,3.81,.16,'glass')
    ring('EmitterBellyDatum',(0,0,.08),2.6,2.44,.16,'dark')
    e.cyl('EmitterPupil',0,0,.13,2.43,.10,'glass')
    if detail:
        for i in range(12):
            a=i*math.tau/12
            e.line('EmitterSegment',[(4.08*math.cos(a+t),4.08*math.sin(a+t),.135) for t in (-.045,0,.045)],'cyan',.045)
            e.cyl('EmitterCaptiveLock',4.63*math.cos(a),4.63*math.sin(a),.83,.16,.12,'edge')


def superstructure(detail):
    deck=[(-7.7,-10),(7.7,-10),(9.6,-6),(9.6,8),(7.2,12),(-7.2,12),(-9.6,8),(-9.6,-6)]
    loft('UpperMachineryDeck',deck,[(9.45,1),(10.15,1),(11.45,.86),(11.70,.82)],'armor')
    tower=[(-4.4,-5.5),(4.4,-5.5),(5.8,-3),(5.8,3.7),(4.0,5.8),(-4.0,5.8),(-5.8,3.7),(-5.8,-3)]
    citadel=loft('UncrewedSensorCitadel',tower,[(11.3,1),(12.2,1),(13.65,.82),(14,.73)],'armor')
    if detail:
        q.subtract(citadel,box('SensorRoofServiceVoid',(0,.1,14),(4.7,4.7,.30),'dark',False,.10))
        box('SensorRoofServiceBed',(0,.1,13.865),(4.5,4.5,.04),'dark',False,.08)
        box('SensorRoofServiceLid',(0,.1,13.92),(4.20,4.20,.07),'edge',False,.13)
        for x in (1.5,):
            for y in (-1.4,1.6):
                box('RoofPanelCaptiveLatch',(x,y,13.976),(.32,.44,.04),'armor',True,.035)
    box('SensorRecess',(0,-5.10,12.88),(7.3,.30,.73),'dark',False,.10)
    box('SensorGlass',(0,-5.28,12.89),(5.9,.055,.34),'glass',False,.04)
    box('SensorStatus',(0,-5.32,12.84),(2.0,.04,.05),'cyan',False,.01)
    if detail:
        for x in (6.4,):
            outline=[(x-1.2,5.2),(x+1.2,5.2),(x+1.65,9.8),(x+.9,11.3),(x-1.2,10.8)]
            loft('HeatExchangerHousing',outline,[(10.6,1),(12.35,1),(12.75,.79)],'edge',True)
            for j in range(8):
                box('HeatExchangerLouver',(x,5.65+j*.62,12.52),(1.9,.29,.22),'dark',True,.025)
        for x in (8.7,):
            for y in (-5.0,-1.0,3.0,7.0):
                box('DeckStructuralRib',(x,y,10.0),(.55,.45,2.1),'edge',True,.08)
        for x in (6.4,):
            for y in (-6.8,-3.5,0,3.5):
                v.line('MachineryDeckJoint',[(x-.6,y,11.715),(x+.8,y,11.715)],'dark',.045,True)


def shoulder_hatches():
    for cx,cy,width,length in ((11.85,2.8,2.3,4.9),(15.75,-2.9,3.5,1.75)):
        box('ShoulderMaintenanceSeal',(cx,cy,10.62),(width,length,.09),'dark',True,.14)
        box('ShoulderMaintenanceHatch',(cx,cy,10.70),(width-.22,length-.22,.12),'edge',True,.12)
        box('HatchInsetHandle',(cx,cy,10.77),(width*.42,.18,.04),'dark',True,.025)
        for x in (cx-width/2+.27,cx+width/2-.27):
            for y in (cy-length/2+.27,cy+length/2-.27):
                q.mirror(e.cyl('HatchCaptiveFastener',x,y,10.79,.085,.05,'armor'))
        box('HatchRelease',(cx,cy-length/2+.2,10.80),(.45,.16,.05),'amber',True,.025)
    for points in (
        [(10.9,-3.9),(13.7,-3.9),(14.2,-4.6),(18.3,-4.6),(20.2,-2.0)],
        [(10.8,7.2),(13.7,7.2),(14.2,7.7),(18.4,7.7),(20.1,6.8)],
        [(22.0,.2),(22.8,.8),(22.8,6.4),(21.9,7.1)]):
        v.line('ShoulderPanelJoint',[(x,y,10.61) for x,y in points],'dark',.052,True)


def prow_panel():
    def front_y(x):
        return -16+(x-2.8)*3.2/6-.035
    a,b=4.05,8.40
    verts=[(a,front_y(a),4.15),(b,front_y(b),4.15),(b,front_y(b),6.60),(a,front_y(a),6.60)]
    v.mesh('ProwFacetArmorFrame',verts,[(0,1,2,3)],'edge',True)
    a,b=4.22,8.23
    verts=[(a,front_y(a)-.012,4.32),(b,front_y(b)-.012,4.32),
           (b,front_y(b)-.012,6.43),(a,front_y(a)-.012,6.43)]
    v.mesh('ProwFacetServicePanel',verts,[(0,1,2,3)],'dark',True)
    for z in (4.67,5.09,5.51,5.93):
        v.line('ProwVentLouver',[(4.45,front_y(4.45)-.035,z),(7.95,front_y(7.95)-.035,z)],'edge',.11,True)


def shoulder(detail):
    outline=[(9,-10.8),(15.9,-10.3),(19.3,-7.2),(19.3,-5.7),(22.2,-5.7),(24,-2),
             (24,8.5),(21.8,8.5),(21.8,11.4),(19.8,13.0),(10,14.1),(9,4)]
    body=loft('ShoulderMainArmor',outline,[(3.65,.83),(5.1,1),(8.9,1),(10.6,.89)],'armor')
    q.subtract(body,e.cyl('FanVoid',18,2.8,7,3.8,18,'dark'))
    q.subtract(body,box('ShoulderVentCut',(23.7,3.1,7.0),(2.2,7.2,1.65),'dark'))
    if detail:
        q.subtract(body,box('ShoulderRadiatorVoid',(15.7,-6.8,10.3),(3.3,3.0,3.0),'dark',False,.08))
        q.subtract(body,box('ShoulderAftVentVoid',(16.0,9.6,10.3),(4.0,2.9,3.0),'dark',False,.08))
        for y in (-1.45,2.8,7.9):
            q.subtract(body,box('LoadFrameSocket',(23.8,y,7.0),(1.3,1.00,4.10),'dark',False,.06))
    q.mirror(body)
    ring('PropulsionUpperArmoredLip',(18,2.8,9.56),4.20,3.75,.85,'edge',True)
    ring('PropulsionDeepDuct',(18,2.8,7.05),3.77,3.60,4.1,'dark',True)
    q.mirror(e.cyl('RotorHub',18,2.8,6.5,.71,.65,'armor'))
    for i in range(12):
        a=i*math.tau/12
        pts=[(18+x*math.cos(a)-y*math.sin(a),2.8+x*math.sin(a)+y*math.cos(a))
             for x,y in ((.65,-.15),(3.52,.0),(3.5,.64),(1.2,.55))]
        q.prism('RotorBlade',pts,6.28,6.43,'edge',True)
    if detail:
        shoulder_hatches()
        for cx,cy,width in ((15.7,-6.8,3.1),(16.0,9.6,3.8)):
            box('ShoulderRadiatorFloor',(cx,cy,8.98),(width,2.8,.15),'dark',True,.025)
            for j in range(5):
                louver=box('ShoulderLargeLouver',(cx,cy-1.08+j*.54,9.47),(width,.25,.90),'edge',True,.025)
                louver.rotation_euler.x=math.radians(-22)
        for j in range(7):
            box('ShoulderVentLouver',(23.3,-.1+j*1.05,7.0),(.75,.20,1.45),'edge',True,.035)
        for y in (-5.8,8.0):
            outline=[(12.8,y-1.0),(20.2,y-.2),(21.5,y+.65),(20.9,y+1.1),(12.4,y+.6)]
            loft('ShoulderArmorStrake',outline,[(9.1,1),(10.7,.92)],'edge',True)
        for y in (-1.45,2.8,7.9):
            box('OuterLoadFrameSocket',(23.18,y,7.0),(.10,.98,4.05),'dark',True,.04)
            box('OuterLoadFrame',(23.72,y,7.0),(.55,.49,3.90),'edge',True,.08)
            for z in (5.35,8.65):
                box('OuterLoadFrameLock',(23.90,y,z),(.15,.65,.25),'armor',True,.04)
        for x,y in ((15,-7.8),(21.4,8.4)):
            box('HeavyArmorRelease',(x,y,10.19),(.75,.75,.28),'amber',True,.05)


def hull(detail):
    outline=[(-2.8,-16),(2.8,-16),(8.8,-12.8),(11.5,-9.5),(12.5,-4),(12.5,10.5),
             (9,16),(-9,16),(-12.5,10.5),(-12.5,-4),(-11.5,-9.5),(-8.8,-12.8)]
    body=loft('CentralLoadBearingHull',outline,[(1.55,.88),(3.55,1),(8.6,1),(10.0,.89)])
    for row,cx,cy,width,length in q.BAY_ROWS:
        for sign in (-1,1):
            q.subtract(body,box('HangarVoid',(cx*sign,cy,1.2),(width,length,9.6),'dark',False,.12))
    q.subtract(body,e.cyl('EmitterRecess',0,0,0,5.14,7.6,'dark'))
    for row in q.BAY_ROWS:
        bay(*row,detail)
    if detail:
        prow_panel()
        top=[(0,-16,7.95),(2.6,-14.5,9.35),(4.6,-10.3,10.22),(-4.6,-10.3,10.22),(-2.6,-14.5,9.35)]
        points=[(x,y,z-.65) for x,y,z in top]+top
        faces=[(4,3,2,1,0),(5,6,7,8,9)]+[(i,(i+1)%5,(i+1)%5+5,i+5) for i in range(5)]
        v.mesh('ArmoredProwWedge',points,faces,'edge')
        box('ProwSensorRecess',(0,-15.96,6.85),(3.9,.08,.62),'dark',False,.035)
        box('ProwSensorGlass',(0,-15.998,6.85),(2.8,.003,.30),'glass',False,.015)
        for x in (4.0,):
            for name,loc,size,mat in (('NoseNavigationRecess',(x,-15.38,5.55),(1.65,.11,.42),'dark'),
                                      ('NoseNavigationLamp',(x,-15.44,5.55),(1.0,.015,.075),'cyan')):
                obj=box(name,loc,size,mat,True,.012)
                obj.rotation_euler.z=math.atan2(3.2,6)
        for x in (4.7,):
            box('SternExhaustRecess',(x,15.82,6.8),(6.5,.28,3.25),'dark',True,.1)
            for j in range(8):
                box('SternExhaustLouver',(x-2.65+j*.76,15.95,6.8),(.26,.08,2.80),'edge',True,.02)
            box('SternStructuralLintel',(x,15.85,8.70),(6.8,.3,.38),'armor',True,.04)
        q.prism('ContinuousKeelGirder',[(9.8,-7.8),(10.6,-7.1),(10.8,6.8),(9.6,11.3),(9.1,11.3),(9.9,6.3)],
                1.24,1.78,'edge',True)
        for y in (-10.5,12.0):
            box('KeelTransverseGirder',(5.5,y,1.53),(4.4,.52,.45),'edge',True,.07)
    return body


def make(height=14,detail=True):
    e.setup()
    bpy.context.preferences.filepaths.save_version=0
    hull(detail)
    shoulder(detail)
    superstructure(detail)
    optic(detail)
    q.docked_drone()
    q.canonical_halves()
    for obj in bpy.context.scene.objects:
        if obj.type!='MESH':
            continue
        for vertex in obj.data.vertices:
            if obj.name.startswith('DockedV22_'):
                vertex.co.z+=2.4
            elif vertex.co.z>6:
                vertex.co.z=6+(vertex.co.z-6)*(height-6)/8
    q.animate_states()
    v.empty('RuntimeOriginBellyCenter',(0,0,0))
    v.empty('BeamOrigin',(0,0,-.1))
    for row,cx,cy,_,_ in q.BAY_ROWS:
        for sign in (-1,1):
            slot=(0 if row=='Forward' else 2)+(0 if sign<0 else 1)
            v.empty('ServiceBay_'+str(slot),(cx*sign,cy,2.4))
    q.studio('chunkbuster')
    bpy.context.scene['asset_status']='V25_PROPOSAL_AWAITING_SHOWN_APPROVAL'
    bpy.context.scene['front_axis']='-Y'
    bpy.context.scene['up_axis']='+Z'
    bpy.context.scene['runtime_contract']='48 x 32 x 14; belly origin; unchanged four bay anchors; backend not changed'


def render(name,axis=(4,-6,4),size=(1280,960),frame=40):
    q.render(OUT/(name+'.png'),axis,62,(0,0,7),size,frame)


def blockout():
    for height in (12,14,16):
        make(height,False)
        render('mass-'+str(height),(4,-6,2.8),(1100,800))
    q.sheet_start(1500,840)
    q.sheet_text('V25 / HEAVY CARRIER MASSING / 48 x 32',24,20,31)
    for i,height in enumerate((12,14,16)):
        q.sheet_text('HEIGHT '+str(height)+(' / SELECTED' if height==14 else ''),i*500+25,92,23)
        q.sheet_image(OUT/('mass-'+str(height)+'.png'),i*500,150,500,530)
    q.sheet_text('14: deep central hull + armored shoulders; low unmanned sensor deck',25,731,25)
    q.sheet_text('Canonical polygon mass study / proposal / unchanged 4 bay anchors',25,780,22)
    q.sheet_save(OUT/'massing-comparison.png')


def validate():
    scene=bpy.context.scene
    errors=[]
    samples=[]
    for frame,state in q.STATES:
        scene.frame_set(frame)
        points=d.all_points(d.geometry())
        bb=d.bounds(points)
        tree=kdtree.KDTree(len(points))
        for i,p in enumerate(points):
            tree.insert(p,i)
        tree.balance()
        deviation=max(tree.find(Vector((-p.x,p.y,p.z)))[2] for p in points)
        dims=[bb[k+3]-bb[k] for k in range(3)]
        sample={'frame':frame,'state':state,'bounds':bb,'dimensions':dims,'mirrorError':deviation}
        samples.append(sample)
        if deviation>.0001 or any(abs(a-b)>.001 for a,b in zip(dims,(48,32,14))) or abs(bb[2])>.0001:
            errors.append(sample)
    scene.frame_set(40)
    graph=bpy.context.evaluated_depsgraph_get()
    objects=[o for o in scene.objects if o.type=='MESH']
    missing=[o.name for o in objects if not any(m.type=='MIRROR' and m.mirror_object==v.MIRROR for m in o.modifiers)]
    if missing:
        errors.append({'missingMirror':missing})
    parts=d.geometry()
    structures={o.name:(o,BVHTree.FromObject(o,graph)) for o in objects if not o.name.startswith('DockedV22_')}
    bays=[]
    for row,cx,cy,w,l in q.BAY_ROWS:
        for sign in (-1,1):
            x=cx*sign
            blocked=[]
            first=[]
            for i in range(13):
                for j in range(13):
                    xy=(x-1.5+i*.25,cy-1.5+j*.25)
                    hits=[]
                    for name,(obj,tree) in structures.items():
                        inverse=obj.matrix_world.inverted()
                        point,_,_,_=tree.ray_cast(inverse@Vector((*xy,-1)),inverse.to_3x3()@Vector((0,0,1)))
                        if point is not None:
                            hits.append(((obj.matrix_world@point).z,name))
                    nearest=min(hits) if hits else (-1,'missing roof')
                    first.append(nearest[0])
                    if nearest[0]<3.35-.001:
                        blocked.append({'xy':xy,'surface':nearest})
            witness=d.bounds([p for name,ps in parts.items() if name.startswith('DockedV22_'+row+'_') for p in ps if p.x*sign>0])
            volume=[x-1.5,cy-1.5,2.4,x+1.5,cy+1.5,3.35]
            fits=all(witness[k]>=volume[k]-.001 and witness[k+3]<=volume[k+3]+.001 for k in range(3))
            entry={'slot':len(bays),'runtimeAnchor':[x,2.4,cy],'clearVolumeBlender':volume,'entryRays':169,
                   'blockedRays':blocked,'minimumCeilingZ':min(first),'gearStowedWitnessBounds':witness,'witnessFits':fits}
            bays.append(entry)
            if blocked or not fits:
                errors.append(entry)
    triangles=0
    witness_triangles=0
    evaluated_vertices=0
    for obj in objects:
        ev=obj.evaluated_get(graph)
        mesh=ev.to_mesh()
        mesh.calc_loop_triangles()
        triangles+=len(mesh.loop_triangles)
        evaluated_vertices+=len(mesh.vertices)
        if obj.name.startswith('DockedV22_'):
            witness_triangles+=len(mesh.loop_triangles)
        ev.to_mesh_clear()
    report={'status':'PROPOSAL_NOT_APPROVED','passed':not errors,'errors':errors,'states':samples,
            'bays':bays,'entityDimensions':[3,.75,3],'overheadAllowance':.20,'rotorCount':2,'rotorBlades':24,
            'landingGearCount':0,'mirrorObjects':len(objects)-len(missing),
            'budget':{'meshObjects':len(objects),'evaluatedVertices':evaluated_vertices,'triangles':triangles,
                      'witnessTriangles':witness_triangles,'carrierOnlyTriangles':triangles-witness_triangles},
            'limitations':['No runtime integration or in-game physics test.','Static end states; door transition is not a validated mechanism.']}
    q.dump(OUT/'validation.json',report)
    if errors:
        raise RuntimeError(json.dumps(errors))
    return report


def sheets():
    q.sheet_start(1440,1080)
    q.sheet_text('Morrowgear / V25 HEAVY CARRIER / PROPOSAL',25,20,28)
    for i,(name,_) in enumerate(VIEWS):
        x,y=i%3*480,i//3*328+74
        q.sheet_text(name.upper(),x+22,y,18)
        q.sheet_image(OUT/(name+'.png'),x+4,y+28,470,284)
    q.sheet_text('48 x 32 x 14 / belly origin',507,790,25)
    q.sheet_text('4 deep hangars / exact X0 mirror',507,834,23)
    q.sheet_text('Native 3D / approval pending',507,879,23)
    q.sheet_save(OUT/'contact-sheet.png')
    q.sheet_start(1200,1100)
    q.sheet_text('V24 REJECTED / V25 HEAVY CARRIER',24,20,30)
    q.sheet_image(ROOT/'docs/design/supply-carrier-v24/models/chunkbuster/hero.png',8,90,575,460)
    q.sheet_image(OUT/'hero.png',610,90,575,460)
    q.sheet_text('V24 / thin blended wing / height 7',24,561,23)
    q.sheet_text('V25 / layered hull / height 14',630,561,23)
    q.sheet_image(ROOT/'docs/design/supply-carrier-v24/models/chunkbuster/belly-hero.png',8,610,575,410)
    q.sheet_image(OUT/'belly-hero.png',610,610,575,410)
    q.sheet_text('Same width 48 and length 32 / bay XYZ retained',24,1040,24)
    q.sheet_save(OUT/'v24-comparison.png')
    q.sheet_start(840,1320)
    q.sheet_text('Morrowgear / V25',25,20,32)
    q.sheet_text('HEAVY CARRIER / PROPOSAL',25,65,25)
    q.sheet_image(OUT/'hero.png',0,120,840,520)
    q.sheet_image(OUT/'belly-hero.png',0,665,840,500)
    q.sheet_text('48 x 32 x 14 / 4 deep service hangars',25,1190,25)
    q.sheet_text('User approval pending / no runtime changes',25,1245,23)
    q.sheet_save(OUT/'approval-mobile.png')
    q.sheet_start(1400,1000)
    q.sheet_text('V25 / TRUE-SCALE X47 COMPARISON',25,20,31)
    q.sheet_image(OUT/'small-drone-comparison.png',0,100,1400,800)
    q.sheet_text('Carrier width 48 / X47 width 3 / ratio 16:1',25,922,27)
    q.sheet_text('Four docked witnesses + one unscaled external witness',25,965,20)
    q.sheet_save(OUT/'scale-comparison.png')


def finish():
    make(14,True)
    report=validate()
    scene=bpy.context.scene
    scene.frame_set(40)
    q.export(OUT/'chunkbuster-v25.glb')
    scene.frame_set(1)
    q.export(OUT/'chunkbuster-v25-closed.glb')
    scene.frame_set(40)
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'chunkbuster-v25.blend'))
    for name,axis in VIEWS:
        render(name,axis)
    render('belly-hero',(4,-6,-3.5))
    render('closed',(4,-6,-3.5),frame=1)
    # This extra true-scale witness belongs only to the comparison render.
    for obj in list(bpy.context.scene.objects):
        if obj.type=='MESH' and obj.name.startswith('DockedV22_Forward_'):
            copy=obj.copy()
            copy.data=obj.data.copy()
            copy.name='ComparisonOnly_'+obj.name
            copy.modifiers.clear()
            copy.animation_data_clear()
            copy.location=Vector((-7.15,-15.6,0))
            bpy.context.collection.objects.link(copy)
    render('small-drone-comparison',(4,-6,2.8),(1600,1150))
    report['blendSha256']=q.digest(OUT/'chunkbuster-v25.blend')
    report['glbSha256']=q.digest(OUT/'chunkbuster-v25.glb')
    report['budget']['blendBytes']=(OUT/'chunkbuster-v25.blend').stat().st_size
    report['budget']['glbBytes']=(OUT/'chunkbuster-v25.glb').stat().st_size
    q.dump(OUT/'validation.json',report)
    contract=json.loads((ROOT/'docs/design/supply-carrier-v24/runtime-contract.json').read_text())
    contract['status']='V25_PROPOSAL_NOT_APPROVED_BACKEND_UNCHANGED'
    contract['blender']['heightZ']=14
    contract['blender']['bounds']=[-24,-16,0,24,16,14]
    contract['runtime']['heightY']=14
    contract['runtime']['bounds']=[-24,0,-16,24,14,16]
    contract['fitAdjustment']={'carrierVerticalScale':1,'carrierVerticalOffset':0,'dockedV22Geometry':'translation only; scale unchanged'}
    contract['contractDelta']={'heightY':{'previous':7,'proposed':14},'serviceBays':'unchanged','origin':'unchanged','front':'unchanged','backend':'not modified'}
    q.dump(OUT/'runtime-contract.json',contract)
    sheets()
    print('V25_COMPLETE',json.dumps(report['budget']),flush=True)


def verify():
    bpy.ops.wm.open_mainfile(filepath=str(OUT/'chunkbuster-v25.blend'))
    expected_anchors={o.name:list(o.location) for o in bpy.context.scene.objects if o.name.startswith(('ServiceBay_','BeamOrigin'))}
    references={}
    for frame,name in ((40,'service'),(1,'closed')):
        bpy.context.scene.frame_set(frame)
        references[name]={n:d.bounds(ps) for n,ps in d.geometry().items()}
    sweep=[]
    for frame in range(1,81):
        bpy.context.scene.frame_set(frame)
        graph=bpy.context.evaluated_depsgraph_get()
        points=[o.evaluated_get(graph).matrix_world@Vector(p) for o in bpy.context.scene.objects
                if o.type=='MESH' for p in o.evaluated_get(graph).bound_box]
        bb=d.bounds(points)
        if any(bb[k]<limit-.0001 for k,limit in enumerate((-24,-16,0))) or any(bb[k+3]>limit+.0001 for k,limit in enumerate((24,16,14))):
            sweep.append({'frame':frame,'bounds':bb})
    q.dump(OUT/'state-envelope.json',{'passed':not sweep,'frames':80,'errors':sweep,'scope':'Envelope only, not continuous collision proof'})
    if sweep:
        raise RuntimeError(sweep)
    results=[]
    for name,file in (('service','chunkbuster-v25.glb'),('closed','chunkbuster-v25-closed.glb')):
        bpy.ops.wm.read_factory_settings(use_empty=True)
        bpy.ops.import_scene.gltf(filepath=str(OUT/file))
        actual=d.geometry()
        missing=sorted(set(references[name])-set(actual))
        error=max(max(abs(a-b) for a,b in zip(bb,d.bounds(actual[n]))) for n,bb in references[name].items() if n in actual)
        anchors={o.name:list(o.location) for o in bpy.context.scene.objects if o.name.startswith(('ServiceBay_','BeamOrigin'))}
        anchor_error=max(max(abs(a-b) for a,b in zip(coords,anchors.get(n,[999,999,999]))) for n,coords in expected_anchors.items())
        results.append({'state':name,'maxPartBoundsError':error,'missingParts':missing,'anchors':anchors,
                        'maxAnchorError':anchor_error,'passed':error<.0002 and anchor_error<.0002 and not missing and len(anchors)==5})
    q.dump(OUT/'export-roundtrip.json',results)
    if not all(r['passed'] for r in results):
        raise RuntimeError(results)
    import numpy as np
    images=[]
    for name in [n for n,_ in VIEWS]+['belly-hero','closed','small-drone-comparison']:
        img=bpy.data.images.load(str(OUT/(name+'.png')))
        sw,sh=img.size
        pixels=np.empty(sw*sh*4,dtype=np.float32)
        img.pixels.foreach_get(pixels)
        yy,xx=np.where(pixels.reshape(sh,sw,4)[:,:,3]>.03)
        passed=len(xx)>0 and xx.min()>0 and yy.min()>0 and xx.max()<sw-1 and yy.max()<sh-1
        images.append({'view':name,'passed':bool(passed),'occupiedPixels':int(len(xx))})
    q.dump(OUT/'render-checks.json',images)
    if not all(i['passed'] for i in images):
        raise RuntimeError(images)
    import struct
    raw=(OUT/'chunkbuster-v25.glb').read_bytes()
    size=struct.unpack_from('<I',raw,12)[0]
    gltf=json.loads(raw[20:20+size])
    primitives=[p for m in gltf['meshes'] for p in m['primitives']]
    budget=json.loads((OUT/'validation.json').read_text())['budget']
    budget.update({'glbVertices':sum(gltf['accessors'][p['attributes']['POSITION']]['count'] for p in primitives),
                   'glbTriangles':sum(gltf['accessors'][p['indices']]['count']//3 for p in primitives),
                   'glbPrimitives':len(primitives),'glbMaterials':len(gltf.get('materials',[])),
                   'status':'Design proposal, not a runtime budget approval'})
    q.dump(OUT/'model-budget.json',budget)
    preserved=[]
    old=ROOT/'docs/design/supply-carrier-v24'
    for item in q.ITEMS:
        folder=old/'models'/item
        report=json.loads((folder/'validation.json').read_text())
        for suffix,key in (('.blend','blendSha256'),('.glb','glbSha256')):
            path=folder/(item+suffix)
            preserved.append({'path':str(path.relative_to(ROOT)),'unchanged':q.digest(path)==report[key]})
    for source in json.loads((old/'reference-audit.json').read_text()):
        preserved.append({'path':source['path'],'unchanged':q.digest(ROOT/source['path'])==source['sha256']})
    q.dump(OUT/'preserved-source-checks.json',preserved)
    if not all(p['unchanged'] for p in preserved):
        raise RuntimeError(preserved)
    print('V25_VERIFY_PASS',flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--blockout',action='store_true')
    parser.add_argument('--verify',action='store_true')
    args=parser.parse_args(sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else [])
    if args.blockout:
        blockout()
    elif args.verify:
        verify()
    else:
        finish()
