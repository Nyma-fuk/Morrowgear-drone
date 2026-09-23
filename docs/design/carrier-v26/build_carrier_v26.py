"""V26 native massing only. No runtime export or installation."""
import importlib.util
import json
import math
import sys
from pathlib import Path

sys.dont_write_bytecode=True
import bpy
from mathutils import Vector, Matrix, kdtree
from mathutils.bvhtree import BVHTree

OUT=Path(__file__).resolve().parent
ROOT=OUT.parents[2]
spec=importlib.util.spec_from_file_location('v24_helpers',ROOT/'tools/drone-design/blender/build_supply_carrier_v24.py')
q=importlib.util.module_from_spec(spec)
spec.loader.exec_module(q)
e,d,v=q.e,q.d,q.v
SECTIONS=[(-34,.55,5.1,4.45),(-30,4.2,6.6,3.4),(-22,8,8.5,2.0),
          (-14,12,8.5,1.5),(-6,16,8.5,1.5),(3,23,8.5,1.4),(7,23,8.5,1.4),
          (9,18,8.5,1.5),(10.5,18,8.5,1.5),(12,14.5,8.5,1.6),
          (22,12.4,8.5,2.0),(30,11,8.0,2.8),(34,9.5,7.1,3.3)]


def width_at(y):
    for a,b in zip(SECTIONS,SECTIONS[1:]):
        if y<=b[0]:
            t=(y-a[0])/(b[0]-a[0])
            return a[1]+t*(b[1]-a[1])
    return SECTIONS[-1][1]


def loft_hull():
    verts=[]
    for y,w,top,bottom in SECTIONS:
        depth=top-bottom
        half=[(0,top),(.57*w,top),(.82*w,top-depth*.12),(w,top-depth*.31),
              (w,bottom+depth*.27),(.76*w,bottom),(0,bottom)]
        cross=half+[(-x,z) for x,z in reversed(half[1:-1])]
        verts.extend((x,y,z) for x,z in cross)
    n=12
    faces=[tuple(reversed(range(n))),tuple(range((len(SECTIONS)-1)*n,len(SECTIONS)*n))]
    for row in range(len(SECTIONS)-1):
        for i in range(n):
            a=row*n+i
            b=row*n+(i+1)%n
            faces.append((a,b,b+n,a+n))
    return v.mesh('ContinuousAttackCarrierHull',verts,faces,'armor')


def main():
    e.setup()
    bpy.context.preferences.filepaths.save_version=0
    body=loft_hull()
    # A single connected hull separates only at the twin stern channels.
    q.subtract(body,q.prism('TwinSternSeparation',[(0,30.5),(1.0,32),(2.1,35),(-2.1,35),(-1.0,32)],-1,15,'dark'))
    for sign in (-1,1):
        q.subtract(body,e.box('ContinuousSideHangarVoid',sign*20.1,4.0,4.8,20,28,3.0,'dark',.10))
        q.subtract(body,e.box('SternThroatVoid',sign*6.45,33.9,5.2,5.45,3.0,3.05,'dark',.14))
        q.subtract(body,e.box('DeckElevatorVoid',sign*9.1,1.0,8.45,3.5,5.0,.65,'dark',.10))
        q.subtract(body,e.box('EngineHeatWell',sign*4.7,26,8.25,3.5,4.5,.90,'dark',.08))
    q.subtract(body,e.cyl('IntegratedBellyOpticRecess',0,0,.5,5.10,6.5,'dark'))
    for row,cx,cy,width,length in q.BAY_ROWS:
        for sign in (-1,1):
            q.subtract(body,e.box('DedicatedServiceBayVoid',sign*cx,cy,.4,width,length,7.2,'dark',.10))
        v.box('DedicatedBayCeiling'+row,(cx,cy,3.88),(width-.08,length-.08,.18),'dark',True,.04)
        for x in (cx-width/2+.12,cx+width/2-.12):
            v.box('DedicatedBayFrame'+row,(x,cy,2.50),(.20,length,2.60),'edge',True,.03)
        v.box('DedicatedBayGuide'+row,(cx,cy-length/2+.15,3.63),(width-.75,.10,.10),'cyan',True,.015)
    # Long, real side decks and load frames define the carrier rather than an airplane wing.
    side_outline=[(10.12,-10),(width_at(-10),-10)]+[(w,y) for y,w,_,_ in SECTIONS if -10<y<18]+[(width_at(18),18),(10.12,18)]
    q.prism('ContinuousLaunchGallery',side_outline,3.20,3.38,'edge',True)
    for y in (-9.8,-2.5,5.0,12.5,17.8):
        outer=width_at(y)-.30
        q.prism('HangarStructuralWeb',[(10.12,y-.22),(outer,y-.22),(outer,y+.22),(10.12,y+.22)],3.3,6.35,'edge',True)
    v.box('HangarInnerWall',(10.15,4.0,4.8),(.10,27.5,2.9),'dark',True,.02)
    deck_outline=[(-4.25,-21),(4.25,-21),(6.5,-15),(7.5,-8),(7.5,18),(5.9,21),
                  (-5.9,21),(-7.5,18),(-7.5,-8),(-6.5,-15)]
    q.prism('LongUncrewedFlightDeck',deck_outline,8.50,8.59,'edge')
    for x in (5.5,):
        v.line('FlightDeckInsetGuide',[(x,-12,8.601),(x,17.5,8.601)],'dark',.06,True)
    for x in (6.45,):
        v.box('TwinEngineDarkThroat',(x,32.49,5.2),(5.30,.08,2.90),'dark',True,.10)
        v.box('TwinEngineCore',(x,32.55,5.2),(3.3,.08,.30),'cyan',True,.03)
    # Low canted fins are ship stabilizers, not a cockpit or aircraft tail tower.
    fin=[(7.8,20.5,8.18),(8.7,32.0,7.72),(9.9,32.0,9.08),(9.3,24.5,9.65)]
    thickness=.30
    verts=fin+[(x+thickness,y,z) for x,y,z in fin]
    faces=[(3,2,1,0),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)]
    v.mesh('CantedSternShipFin',verts,faces,'armor',True)
    optic=[(5.04*math.cos(i*math.tau/16),5.04*math.sin(i*math.tau/16)) for i in range(16)]
    q.prism('IntegratedEmitterArmor',optic,.50,2.3,'armor')
    e.ring('EmitterRim',(0,0,.32),4.10,3.60,.50,'edge')
    e.cyl('CentralEmitterLens',0,0,.12,3.59,.20,'glass')
    e.ring('BellyZeroDatum',(0,0,.06),2.5,2.35,.12,'dark')
    deck_equipment()
    q.docked_drone()
    q.canonical_halves()
    for obj in bpy.context.scene.objects:
        if obj.type=='MESH' and obj.name.startswith('DockedV22_'):
            for vertex in obj.data.vertices:
                vertex.co.z+=2.4
    deck_witnesses()
    for row,cx,cy,_,_ in q.BAY_ROWS:
        for sign in (-1,1):
            slot=(0 if row=='Forward' else 2)+(0 if sign<0 else 1)
            v.empty('ServiceBay_'+str(slot),(cx*sign,cy,2.4))
    v.empty('BeamOrigin',(0,0,-.1))
    v.empty('RuntimeOriginBellyCenter',(0,0,0))
    q.studio('chunkbuster')
    scene=bpy.context.scene
    scene.world.node_tree.nodes['Background'].inputs[0].default_value=(.25,.25,.25,1)
    scene.world.node_tree.nodes['Background'].inputs[1].default_value=.75
    scene.view_settings.exposure=.35
    for light in scene.objects:
        if light.type=='LIGHT':
            light.data.energy*=1.20
            if 'Fill' in light.name or 'Belly' in light.name:
                light.data.energy*=1.35
    scene.frame_set(40)
    scene['asset_status']='V26_PROPOSAL_AWAITING_SHOWN_APPROVAL'
    scene['front_axis']='-Y'
    scene['up_axis']='+Z'
    points=d.all_points(d.geometry())
    bounds=d.bounds(points)
    tree=kdtree.KDTree(len(points))
    for i,p in enumerate(points):
        tree.insert(p,i)
    tree.balance()
    error=max(tree.find(Vector((-p.x,p.y,p.z)))[2] for p in points)
    report={'stage':'MODERATE_DETAIL_PROPOSAL_NOT_APPROVED','bounds':bounds,'dimensions':[bounds[k+3]-bounds[k] for k in range(3)],
            'mirrorError':error,'units':'blocks','blenderFront':'-Y','runtimeFront':'-Z',
            'runtimeDimensions':{'widthX':46,'lengthZ':68,'heightY':11},
            'dedicatedBayCount':4,'serviceBayCoordinates':'unchanged from V24/V25',
            'contractDelta':{'fromV25':{'width':[48,46],'length':[32,68],'height':[14,11]},
                             'origin':'unchanged belly zero','beamOffset':[0,-.1,0],'backend':'NOT MODIFIED'},
            'deckExhibitionCount':6,'dockedWitnessCount':4,
            'limitations':['No moving mechanism, arbitrary flight collision or runtime validation.','Deck witnesses are display only, not six new backend slots.','No runtime changes.']}
    if error>.0001 or any(abs(a-b)>.001 for a,b in zip(report['dimensions'],(46,68,11))):
        raise RuntimeError(report)
    q.dump(OUT/'rough-dimensions.json',report)
    report['bays']=bay_checks()
    report['passed']=all(not b['blockedRays'] for b in report['bays']) and error<.0001
    if not report['passed']:
        raise RuntimeError(report['bays'])
    report['budget']=budget()
    q.dump(OUT/'validation.json',report)
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'chunkbuster-v26.blend'))
    q.export(OUT/'chunkbuster-v26.glb')
    for name,axis,size in (('hero',(4,-6,3.5),(1440,1100)),('front',(0,-1,0),(1200,850)),
                           ('rear',(0,1,0),(1200,850)),('top',(0,0,1),(1000,1400)),
                           ('bottom',(0,0,-1),(1000,1400)),('left',(-1,0,0),(1440,850)),
                           ('right',(1,0,0),(1440,850)),('side',(1,0,0),(1440,850)),
                           ('belly-hero',(4,-6,-3.5),(1440,1100))):
        scene.render.film_transparent=name!='hero'
        q.render(OUT/(name+'.png'),axis,86,(0,0,5.5),size)
    verify_roundtrip(report)
    sheets()
    final_sheets()
    print('V26_ROUGH_READY',json.dumps(report),flush=True)


def deck_equipment():
    for center in (3.6,):
        for edge in (center-1.65,center+1.65):
            v.line('LaunchLaneEdge',[(edge,-17,8.604),(edge,14.5,8.604)],'dark',.045,True)
        for y in (-17,14.5):
            v.box('LaneThreshold',(center,y,8.63),(3.15,.16,.055),'amber' if y>0 else 'cyan',True,.01)
    v.box('ElevatorDarkBed',(9.1,1.0,8.16),(3.4,4.9,.09),'dark',True,.08)
    v.box('StorageElevatorPlatform',(9.1,1.0,8.26),(3.20,4.70,.12),'edge',True,.08)
    for y in (-1.10,3.10):
        v.box('ElevatorSafetyStop',(9.1,y,8.345),(2.90,.14,.04),'amber',True,.015)
    for x,y in ((6.2,-11),(8.8,7.0)):
        v.box('MaintenanceHatchSeal',(x,y,8.62),(1.8,2.3,.10),'dark',True,.13)
        v.box('MaintenanceHatch',(x,y,8.70),(1.6,2.1,.11),'edge',True,.10)
        v.box('HatchLock',(x,y-.70,8.78),(.45,.13,.06),'amber',True,.02)
    for x in (4.8,):
        outline=[(x-1.2,16.8),(x+1.2,16.8),(x+1.5,18.2),(x+1.5,21.0),(x+.9,22.0),(x-1.2,21.6)]
        q.prism('LowUncrewedControlBlock',outline,8.50,10.20,'armor',True)
        v.box('SensorFaceRecess',(x,16.77,9.55),(1.8,.10,.45),'dark',True,.04)
        v.box('SensorFace',(x,16.705,9.55),(1.25,.03,.16),'cyan',True,.02)
        v.box('FlatSensorAntenna',(x,19.2,10.60),(1.0,2.2,.80),'edge',True,.25)
    v.box('EngineHeatWellFloor',(4.7,26,7.86),(3.4,4.4,.08),'dark',True,.04)
    for j in range(7):
        v.box('EngineExhaustLouver',(4.7,24.2+j*.60,8.01),(3.1,.25,.20),'edge',True,.035)
    for y in (-7.5,-.5,6.5,13.5):
        outer=width_at(y)-.9
        v.box('HangarCeilingLight',(10.65,y,6.20),(.16,2.1,.12),'cyan',True,.025)
        v.box('GalleryFrameHead',((10.2+outer)/2,y,6.27),(outer-10.2,.32,.22),'edge',True,.03)


def deck_witnesses():
    for obj in list(bpy.context.scene.objects):
        if obj.type!='MESH' or not obj.name.startswith('DockedV22_Forward_'):
            continue
        for i,y in enumerate((-11,-1,9)):
            copy=obj.copy()
            copy.data=obj.data.copy()
            copy.name='DeckWitness_Row'+str(i)+'_'+obj.name
            copy.data.transform(Matrix.Translation((3.6-7.15,y+5.2,8.59-2.6596557736)))
            bpy.context.collection.objects.link(copy)


def bay_checks():
    graph=bpy.context.evaluated_depsgraph_get()
    structures=[(o,BVHTree.FromObject(o,graph)) for o in bpy.context.scene.objects
                if o.type=='MESH' and not o.name.startswith(('DeckWitness_','DockedV22_'))]
    reports=[]
    for row,cx,cy,_,_ in q.BAY_ROWS:
        for sign in (-1,1):
            blocked=[]
            for i in range(13):
                for j in range(13):
                    origin=Vector((cx*sign-1.5+i*.25,cy-1.5+j*.25,-1))
                    for obj,tree in structures:
                        inverse=obj.matrix_world.inverted()
                        point,_,_,_=tree.ray_cast(inverse@origin,inverse.to_3x3()@Vector((0,0,1)))
                        if point is not None and (obj.matrix_world@point).z<3.35-.001:
                            blocked.append({'part':obj.name,'xy':list(origin)[:2]})
            reports.append({'slot':len(reports),'runtimeXYZ':[cx*sign,2.4,cy],'rays':169,'blockedRays':blocked})
    return reports


def budget():
    graph=bpy.context.evaluated_depsgraph_get()
    report={'vertices':0,'triangles':0,'witnessTriangles':0,'objects':0}
    for obj in bpy.context.scene.objects:
        if obj.type!='MESH':
            continue
        ev=obj.evaluated_get(graph)
        mesh=ev.to_mesh()
        mesh.calc_loop_triangles()
        report['vertices']+=len(mesh.vertices)
        report['triangles']+=len(mesh.loop_triangles)
        report['objects']+=1
        if obj.name.startswith(('DeckWitness_','DockedV22_')):
            report['witnessTriangles']+=len(mesh.loop_triangles)
        ev.to_mesh_clear()
    report['carrierOnlyTriangles']=report['triangles']-report['witnessTriangles']
    return report


def verify_roundtrip(report):
    expected={name:d.bounds(points) for name,points in d.geometry().items()}
    anchors={o.name:list(o.location) for o in bpy.context.scene.objects if o.name.startswith(('ServiceBay_','BeamOrigin'))}
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(OUT/'chunkbuster-v26.glb'))
    imported=d.geometry()
    missing=sorted(set(expected)-set(imported))
    error=max(max(abs(a-b) for a,b in zip(bounds,d.bounds(imported[name]))) for name,bounds in expected.items() if name in imported)
    marker_error=max(max(abs(a-b) for a,b in zip(coords,bpy.data.objects[name].location)) for name,coords in anchors.items())
    result={'passed':not missing and error<.0002 and marker_error<.0002,'missingParts':missing,'maxPartBoundsError':error,'maxMarkerError':marker_error}
    q.dump(OUT/'export-roundtrip.json',result)
    if not result['passed']:
        raise RuntimeError(result)
    report['blendSha256']=q.digest(OUT/'chunkbuster-v26.blend')
    report['glbSha256']=q.digest(OUT/'chunkbuster-v26.glb')
    report['budget']['glbBytes']=(OUT/'chunkbuster-v26.glb').stat().st_size
    q.dump(OUT/'validation.json',report)


def final_sheets():
    q.sheet_start(1440,1080)
    q.sheet_text('Morrowgear / V26 ATTACK CARRIER / PROPOSAL',25,20,28)
    for i,(name,_) in enumerate(q.VIEWS):
        x,y=i%3*480,i//3*328+75
        q.sheet_text(name.upper(),x+20,y,18)
        q.sheet_image(OUT/(name+'.png'),x+8,y+28,464,278)
    q.sheet_text('46 x 68 x 11 / X0 symmetry',510,810,24)
    q.sheet_text('4 service bays + 6 deck witnesses',510,854,23)
    q.sheet_text('Proposal / not installed',510,898,23)
    q.sheet_save(OUT/'contact-sheet.png')
    q.sheet_start(840,1330)
    q.sheet_text('Morrowgear / V26',25,20,32)
    q.sheet_text('ATTACK CARRIER / APPROVAL PENDING',25,67,22)
    q.sheet_image(OUT/'hero.png',0,130,840,580)
    q.sheet_image(OUT/'belly-hero.png',0,745,840,420)
    q.sheet_text('Length 68 / width 46 / height 11',25,1210,25)
    q.sheet_text('X47 width 3 / six true-scale deck witnesses',25,1261,22)
    q.sheet_save(OUT/'approval-mobile.png')


def sheets():
    q.sheet_start(1440,1080)
    q.sheet_text('V26 / ATTACK CARRIER / ROUGH MASSING',25,20,30)
    q.sheet_text('46 WIDTH x 68 LENGTH x 11 HEIGHT / NOT APPROVED',25,67,22)
    q.sheet_text('TOP / FRONT AT BOTTOM',25,119,20)
    q.sheet_image(OUT/'top.png',10,158,690,825)
    q.sheet_text('FRONT',750,119,20)
    q.sheet_image(OUT/'front.png',735,160,695,350)
    q.sheet_text('SIDE / FRONT AT LEFT',750,552,20)
    q.sheet_image(OUT/'side.png',735,600,695,370)
    q.sheet_text('Belly origin 0 / mirror X=0 / four service anchors retained',25,1020,23)
    q.sheet_save(OUT/'rough-3view.png')


if __name__=='__main__':
    sheets() if '--sheets' in sys.argv else main()
