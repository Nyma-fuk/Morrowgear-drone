"""V24 proposal only: dedicated supplies and a purpose-built Chunkbuster carrier.

All outputs stay under docs/design/supply-carrier-v24. Existing V22/V23
geometry, materials and studio helpers are read-only inputs. Sheet assembly is
a native Blender render of textured planes, never Python raster manipulation.
"""
import argparse
import hashlib
import importlib.util
import json
import math
import sys
from pathlib import Path

sys.dont_write_bytecode = True
import bpy
import bmesh
from mathutils import Matrix, Vector, kdtree

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = ROOT / 'docs/design/supply-carrier-v24'
spec = importlib.util.spec_from_file_location('equipment23', HERE / 'build_equipment_hmi_v23.py')
e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e)
d, v = e.d, e.v
ITEMS = ('autocannon_magazine', 'laser_cell', 'micro_missile_pack', 'chunkbuster')
VIEWS = (('hero', (4, -6, 4)), ('front', (0, -1, 0)), ('rear', (0, 1, 0)),
         ('top', (0, 0, 1)), ('bottom', (0, 0, -1)), ('left', (-1, 0, 0)), ('right', (1, 0, 0)))
CAPACITY = {'autocannon_magazine': (120, 'round refill'),
            'laser_cell': (1000, 'weapon energy'), 'micro_missile_pack': (5, 'missiles')}
DISPLAY_NAMES = {'autocannon_magazine':'Belt Magazine', 'laser_cell':'Weapon Energy Cell',
                 'micro_missile_pack':'Micro Missile Pack', 'chunkbuster':'Chunkbuster'}
STATES = ((1, 'closed'), (40, 'service'), (80, 'active'))
CARRIER_VERTICAL = (1.0, 0.0)
BAY_ROWS = (('Forward',7.15,-5.2,4.6,5.8), ('Aft',3.6,7.8,4.2,4.6))
BAY_CAPTURE_HEIGHT = 2.4
DRONE_ENTITY_HEIGHT = .75
BAY_OVERHEAD_CLEARANCE = .20
BAY_CLEAR_TOP = BAY_CAPTURE_HEIGHT + DRONE_ENTITY_HEIGHT + BAY_OVERHEAD_CLEARANCE
BAY_POCKET_TOP = -.16
BAY_CEILING_CENTER = -.10
SECTIONS = ((0, -16, 16, 3.25, 3.0), (3, -15.2, 14.2, 3.12, 2.9),
            (6, -12.8, 11.8, 2.72, 2.75), (9, -9.2, 11.5, 2.0, 2.25),
            (12, -5.9, 12.1, 1.36, 1.60), (16, -1.8, 13.2, .80, .75),
            (20, 3.1, 14, .45, .35), (24, 8.2, 12.3, .10, .08))


def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2), encoding='utf-8')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def mirror(obj):
    if not any(m.type == 'MIRROR' for m in obj.modifiers):
        m = obj.modifiers.new('V24_Bilateral_X0', 'MIRROR')
        m.mirror_object = v.MIRROR
        m.use_clip = True
        m.merge_threshold = .00001
    return obj


def prism(name, outline, bottom, top, material='armor', pair=False):
    n = len(outline)
    verts = [(x, y, z) for z in (bottom, top) for x, y in outline]
    faces = [tuple(reversed(range(n))), tuple(range(n, 2*n))]
    faces += [(i, (i+1) % n, (i+1) % n+n, i+n) for i in range(n)]
    return v.mesh(name, verts, faces, material, pair)


def canonical_halves():
    """Retain editable positive halves and a shared world-center Mirror origin."""
    bpy.context.view_layer.update()
    graph = bpy.context.evaluated_depsgraph_get()
    for obj in list(bpy.context.scene.objects):
        if obj.type not in ('MESH', 'CURVE'):
            continue
        ev = obj.evaluated_get(graph)
        data = bpy.data.meshes.new_from_object(ev, depsgraph=graph)
        data.transform(ev.matrix_world)
        name = obj.name
        props = dict(obj.items())
        bpy.data.objects.remove(obj, do_unlink=True)
        bm = bmesh.new()
        bm.from_mesh(data)
        bmesh.ops.bisect_plane(bm, geom=list(bm.verts)+list(bm.edges)+list(bm.faces),
                              plane_co=(0, 0, 0), plane_no=(1, 0, 0), dist=1e-6, clear_inner=True)
        for vert in bm.verts:
            if abs(vert.co.x) < 1e-5:
                vert.co.x = 0
        center_faces = [face for face in bm.faces if all(abs(p.co.x)<1e-6 for p in face.verts)]
        bmesh.ops.delete(bm, geom=center_faces, context='FACES_ONLY')
        bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
        bm.to_mesh(data)
        bm.free()
        if not data.vertices:
            bpy.data.meshes.remove(data)
            continue
        new = bpy.data.objects.new(name, data)
        bpy.context.collection.objects.link(new)
        for key, value in props.items():
            new[key] = value
        mirror(new)


def magazine():
    # The open crescent and visible linked cartridges survive small icon scales.
    for name, ro, ri, depth, mat in (('BeltCassette', .65, .36, .48, 'armor'),
                                   ('SilverBeltCheek', .66, .57, .51, 'edge')):
        n = 48
        verts = []
        for y in (-depth/2, depth/2):
            for radius in (ro, ri):
                for i in range(n+1):
                    t = math.radians(35+290*i/n)
                    verts.append((radius*math.sin(t), y, .73+radius*math.cos(t)))
        stride = n+1
        faces = []
        for i in range(n):
            faces += [(i, i+1, stride+i+1, stride+i),
                      (2*stride+i, 3*stride+i, 3*stride+i+1, 2*stride+i+1),
                      (i, 2*stride+i, 2*stride+i+1, i+1),
                      (stride+i, stride+i+1, 3*stride+i+1, 3*stride+i)]
        faces += [(0, stride, 3*stride, 2*stride), (n, 2*stride+n, 3*stride+n, stride+n)]
        v.mesh(name, verts, faces, mat)
    e.box('BeltFeedNeck', 0, .04, 1.16, .82, .50, .26, 'armor', .05)
    e.box('BeltFeedSilverLip', 0, .04, 1.305, .53, .54, .06, 'edge', .025)
    e.box('BeltFeedRecess', 0, -.243, 1.16, .32, .02, .12, 'dark', .01)
    for i in range(11):
        t = math.radians(105+150*i/10)
        x, z = .49*math.sin(t), .73+.49*math.cos(t)
        e.cyl('VisibleLinkedRound', x, -.279, z, .052, .20, 'amber', (0, 1, 0))
        e.cyl('BeltLinkRivet', x, -.393, z, .026, .018, 'edge', (0, 1, 0))
    e.line('AmberFeedWitness', [(-.17,-.23,1.29),(.17,-.23,1.29)], 'amber', .026)
    for side in (-1, 1):
        e.box('ReleaseLatch', side*.51, .01, .61, .075, .58, .16, 'amber', .018)
    e.contacts(.44, .50, 1.16)


def laser_cell():
    e.cyl('CapacitorCeramicCore', 0, 0, .76, .29, 1.28, 'edge')
    for z in (.16, .45, 1.08, 1.37):
        e.ring('CapacitorIsolationCollar', (0, 0, z), .34, .275, .10, 'armor')
    for z in (.37, 1.20):
        e.ring('CyanChargeWindow', (0, 0, z), .301, .284, .07, 'cyan')
    for x, y in ((.30, 0), (-.30, 0), (0, .30), (0, -.30)):
        e.box('AxialHeatFin', x, y, .76, .055 if x else .15,
              .15 if x else .055, .54, 'armor', .012)
    e.cyl('CapacitorEndSeal', 0, 0, .035, .34, .07, 'dark')
    e.cyl('TerminalInsulator', 0, 0, 1.45, .22, .08, 'dark')
    for x in (-.12, .12):
        e.cyl('PowerTerminal', x, 0, 1.525, .053, .11, 'copper')
    e.box('KeyedTerminalBridge', 0, .12, 1.465, .27, .08, .09, 'edge', .014)


def missiles():
    # Five individually modeled sealed tubes, arranged as 3 + 2, not a painted box.
    centers = ((-.29,.27), (0,.27), (.29,.27), (-.145,.56), (.145,.56))
    for x, z in centers:
        e.cyl('MissileTube', x, 0, z, .148, 1.18, 'armor', (0, 1, 0))
        e.ring('TubeFrontRim', (x,-.602,z), .153,.119,.055,'edge',(0,1,0))
        e.cyl('SealedMicroMissileNose',x,-.625,z,.112,.047,'dark',(0,1,0))
        e.cyl('AmberArmingCap',x,-.655,z,.046,.013,'amber',(0,1,0))
        e.cyl('TubeRearSeal',x,.60,z,.131,.05,'edge',(0,1,0))
    for y in (-.28, .33):
        e.box('ContouredPodSaddle', 0,y,.18,.80,.10,.13,'edge',.05)
        e.box('PodTopBridge',0,y,.64,.52,.10,.11,'armor',.03)
    e.box('SupplyLockRail',0,.13,.785,.24,.78,.16,'armor',.03)
    e.box('AmberPodRelease',0,-.29,.865,.15,.20,.035,'amber',.012)


def section(x):
    x = abs(x)
    for a, b in zip(SECTIONS, SECTIONS[1:]):
        if x <= b[0]:
            q = (x-a[0])/(b[0]-a[0])
            return tuple(a[k]+q*(b[k]-a[k]) for k in range(1,5))
    return SECTIONS[-1][1:]


def skin(x, y, top=True):
    front, rear, up, down = section(x)
    t = max(0, min(1, (y-front)/(rear-front)))
    profile = math.sin(math.pi*t)**.73
    return .05 + (.12+up*profile if top else -.12-down*profile)


def hull():
    xs = [i*.375 for i in range(65)]
    n = 49
    verts = []
    for top in (True, False):
        for x in xs:
            front, rear, _, _ = section(x)
            for j in range(n):
                y = front+(rear-front)*j/(n-1)
                verts.append((x, y, skin(x,y,top)))
    count = len(xs)*n
    faces = []
    for i in range(len(xs)-1):
        for j in range(n-1):
            p = i*n+j
            faces += [(p,p+n,p+n+1,p+1),(count+p,count+p+1,count+p+n+1,count+p+n)]
        for j in (0,n-1):
            p = i*n+j
            faces.append((p,p+n,count+p+n,count+p))
    for i in (0,len(xs)-1):
        for j in range(n-1):
            p = i*n+j
            faces.append((p,p+1,count+p+1,count+p))
    body = v.mesh('CarrierContinuousBlendedWing',verts,faces,'armor',smooth=True)
    body['purpose_built'] = True
    return body


def subtract(body, cutter):
    bpy.context.view_layer.objects.active = body
    mod = body.modifiers.new('Recess_'+cutter.name, 'BOOLEAN')
    mod.operation = 'DIFFERENCE'
    mod.solver = 'EXACT'
    mod.object = cutter
    bpy.ops.object.modifier_apply(modifier=mod.name)
    bpy.data.objects.remove(cutter, do_unlink=True)


def carrier_panel(name, outline, top=True, material='dark', offset=.022):
    verts, faces = [], []
    center = tuple(sum(p[k] for p in outline)/len(outline) for k in (0,1))
    def triangle(a,b,c):
        if max(math.dist(a,b),math.dist(a,c),math.dist(b,c))>.28:
            ab,bc,ca = [tuple((p[k]+q[k])/2 for k in (0,1)) for p,q in ((a,b),(b,c),(c,a))]
            for points in ((a,ab,ca),(ab,b,bc),(ca,bc,c),(ab,bc,ca)):
                triangle(*points)
            return
        start=len(verts)
        verts.extend((x,y,skin(x,y,top)+(offset if top else -offset)) for x,y in (a,b,c))
        faces.append((start,start+1,start+2))
    for a,b in zip(outline,outline[1:]+outline[:1]):
        triangle(center,a,b)
    if not top:
        faces=[tuple(reversed(face)) for face in faces]
    obj=v.mesh(name,verts,faces,material,True,smooth=True)
    bm=bmesh.new()
    bm.from_mesh(obj.data)
    bmesh.ops.remove_doubles(bm,verts=list(bm.verts),dist=.00001)
    bmesh.ops.recalc_face_normals(bm,faces=list(bm.faces))
    bm.to_mesh(obj.data)
    bm.free()
    return obj


def conformal_duct():
    verts, faces=[],[]
    n=128
    for radius,top in ((4.82,True),(4.40,True),(4.82,False),(4.40,False)):
        for i in range(n):
            angle=i*math.tau/n
            x,y=11+radius*math.cos(angle),4+radius*math.sin(angle)
            verts.append((x,y,skin(x,y,top)+(.04 if top else -.04)))
    for i in range(n):
        j=(i+1)%n
        faces.extend(((i,j,n+j,n+i),(2*n+i,3*n+i,3*n+j,2*n+j),
                      (n+i,n+j,3*n+j,3*n+i),(i,2*n+i,2*n+j,j)))
    return v.mesh('IntegratedRotorDuct',verts,faces,'edge',True,smooth=True)


def carrier_line(name, points, material='dark', radius=.028, top=True):
    sampled=[]
    for a,b in zip(points,points[1:]):
        steps=max(1,math.ceil(math.dist(a,b)/.25))
        sampled.extend(tuple(a[k]+(b[k]-a[k])*j/steps for k in (0,1)) for j in range(steps))
    sampled.append(points[-1])
    return v.line(name, [(x,y,skin(x,y,top)+(.025 if top else -.025)) for x,y in sampled],
                  material, radius, True)


def docked_drone():
    source = ROOT/'docs/design/detail-scale-v22/field/airframe.blend'
    with bpy.data.libraries.load(str(source), link=False) as (src, dst):
        dst.objects = src.objects
    loaded = [obj for obj in dst.objects if obj is not None]
    for obj in loaded:
        bpy.context.collection.objects.link(obj)
    bpy.context.scene.frame_set(40)
    bpy.context.view_layer.update()
    graph = bpy.context.evaluated_depsgraph_get()
    pieces = []
    for obj in loaded:
        if obj.type not in ('MESH','CURVE') or obj.name.startswith('FX_') or obj.hide_render:
            continue
        ev = obj.evaluated_get(graph)
        for row,x,y,_,_ in BAY_ROWS:
            data = bpy.data.meshes.new_from_object(ev, depsgraph=graph)
            data.transform(Matrix.Translation((x,y,0)) @ ev.matrix_world)
            new = bpy.data.objects.new('DockedV22_'+row+'_'+obj.name, data)
            pieces.append(new)
    for obj in loaded:
        bpy.data.objects.remove(obj, do_unlink=True)
    for obj in pieces:
        bpy.context.collection.objects.link(obj)
    bpy.context.scene.frame_set(1)
    return source


def service_bay(row,cx,cy,width,length):
    v.box('BayCeiling'+row,(cx,cy,BAY_CEILING_CENTER),(width-.15,length-.15,.13),'dark',True,.18)
    for x in (cx-width/2+.32,cx+width/2-.32):
        v.box('BayCaptureRail'+row,(x,cy,-.92),(.18,length-.5,.20),'edge',True,.045)
        for y in (cy-length/2+.55,cy+length/2-.55):
            v.box('BayMagneticContact'+row,(x,y,-1.12),(.27,.50,.12),'amber',True,.035)
    front=cy-length/2+.08
    v.box('BayGuideRecess'+row,(cx,front,-1.37),(width-.9,.12,.22),'dark',True,.045)
    v.box('BayGuideLight'+row,(cx,front-.055,-1.37),(width-1.4,.055,.065),'cyan',True,.017)
    for j in range(5):
        y=cy-length/2+.06+j*(length-.12)/5
        slat=(length-.12)/5-.03
        door=carrier_panel('BayShutter'+row,[(cx-width/2+.06,y),(cx+width/2-.06,y),
                            (cx+width/2-.06,y+slat),(cx-width/2+.06,y+slat)],
                           top=False,material='armor',offset=.035)
        solid=door.modifiers.new('ShutterThickness','SOLIDIFY')
        solid.thickness=.065
        door['slide_y']=cy+length/2+(.35 if row=='Forward' else -1.25)-y
        door['lift_z']=(1.6+j*.13) if row=='Forward' else (2.6-j*.08)


def reinforced_panels():
    for name,outline in (
        ('WingReinforcement',[(16.3,4.1),(17.2,4.35),(19.0,6.35),(19.1,8.45),(17.0,8.1),(16.3,7.3)]),
        ('ForwardReinforcement',[(3.6,-11.0),(4.25,-10.65),(5.7,-9.05),(5.5,-8.5),(3.85,-9.6)])):
        carrier_panel(name,outline,material='armor',offset=.052)
        carrier_line(name+'Seal',outline+[outline[0]],'dark',.025)
        for x,y in (outline[0],outline[2],outline[-2]):
            v.cylinder(name+'CaptiveFastener',(x,y,skin(x,y)+.085),.095,.035,'edge',True)
            v.line(name+'LockSlot',[(x-.047,y,skin(x,y)+.108),(x+.047,y,skin(x,y)+.108)],'dark',.010,True)
        x,y=outline[1]
        carrier_line(name+'ReleaseWitness',[(x+.12,y+.1),(x+.36,y+.32)],'amber',.034)


def chunkbuster():
    body = hull()
    subtract(body, e.cyl('RotorOpening', 11,4,0,4.6,14,'dark'))
    for row,cx,cy,width,length in BAY_ROWS:
        bay=e.box('BayPocket'+row,cx,cy,(BAY_POCKET_TOP-7.6)/2,width,length,BAY_POCKET_TOP+7.6,'dark',.32)
        for mod in list(bay.modifiers):
            bpy.context.view_layer.objects.active=bay
            bpy.ops.object.modifier_apply(modifier=mod.name)
        subtract(body,bay)
    subtract(body, e.cyl('BellyLensPocket',0,0,-4.6,5.02,6.7,'dark'))
    mirror(body)
    # The large optical assembly is recessed upward into the centerbody.
    e.ring('BellyOpticOuterBezel',(0,0,-2.8),5.0,4.50,.72,'edge')
    e.ring('BellyOpticIsolation',(0,0,-3.17),4.52,3.94,.30,'dark')
    e.cyl('BellyCentralBigLens',0,0,-3.40,3.92,.20,'glass')
    e.ring('BellyOpticInnerRing',(0,0,-3.53),3.20,3.10,.035,'edge')
    e.cyl('BellyOpticPupil',0,0,-3.60,2.0,.10,'glass')
    for i in range(12):
        a = i*math.tau/12
        pts = [(4.15*math.sin(a+t), 4.15*math.cos(a+t), -3.36) for t in (-.065,0,.065)]
        e.line('RecessedLensSegment',pts,'cyan',.063)
    # Large fans are new geometry sized to the wing, not scaled small-drone components.
    conformal_duct()
    mirror(e.ring('DuctDarkLiner',(11,4,.02),4.41,4.27,2.37,'dark'))
    mirror(e.cyl('RotorHub',11,4,.10,.79,.72,'armor'))
    for i in range(12):
        a = i*math.tau/12
        outline = ((.73,-.12),(1.35,-.19),(4.24,.15),(4.20,.82),(2.65,.62),(.87,.25))
        pts = [(11+x*math.cos(a)-y*math.sin(a),4+x*math.sin(a)+y*math.cos(a)) for x,y in outline]
        prism('RotorBlade',pts,-.05,.12,'edge',True)
    for i in range(4):
        a = i*math.tau/4
        v.line('RotorLowerSupport',[(11+.75*math.cos(a),4+.75*math.sin(a),-.90),
                                   (11+4.38*math.cos(a),4+4.38*math.sin(a),-.90)],'armor',.115,True)
    # Internal bay contacts are not landing gear and do not extend below the body.
    for row in BAY_ROWS:
        service_bay(*row)
    docked_drone()
    # Low conformal dorsal fairings and real rear exhaust mouths.
    for name, outline in (
        ('DorsalSpine',[(.55,-11),(.70,-6.7),(2.75,7.9),(3.85,10.8),(1.75,12.9),(.55,10)]),
        ('WingServicePanel',[(15.9,2.8),(19.3,6.1),(21.1,10.8),(16.2,9.4)]),
        ('NoseAccess',[(.4,-14.8),(2.5,-13.8),(4.15,-10.8),(.4,-12.0)])):
        carrier_panel(name,outline,material='edge' if name=='DorsalSpine' else 'armor')
        carrier_line(name+'Seal',outline+[outline[0]])
    for j in range(7):
        y = 6.5+j*.48
        carrier_line('DorsalCoolingLouver',[(3.3,y),(5.0,y+.22)],'dark',.085)
    # Rear-facing recessed twin propulsion outlets stay inside the planform.
    for x in (2.9,):
        v.box('RearExhaustRecess',(x,13.0,.10),(2.55,.48,.88),'dark',True,.18)
        for j in range(7):
            v.box('RearExhaustVane',(x-.91+j*.303,13.27,.10),(.09,.10,.63),'edge',True,.022)
        v.box('RearLamp',(x,13.30,.65),(1.4,.04,.055),'cyan',True,.012)
    carrier_line('WingLeadingEdge',[(x,section(x)[0]+.12) for x in [i*.3 for i in range(80)]+[23.94]], 'edge',.038)
    carrier_line('NoseVisorRecess',[(0,-15.78),(.9,-15.54),(1.8,-15.30),(3,-14.86),(4.5,-13.69)],'dark',.15)
    carrier_line('NoseVisorLight',[(0,-15.79),(.9,-15.55),(1.8,-15.31),(3,-14.87),(4.35,-13.81)],'cyan',.058)
    carrier_line('WingAmberWitness',[(22.6,9.0),(23.0,11.2)],'amber',.055)
    carrier_line('SpineAmberWitness',[(2.0,5.8),(2.2,7.4)],'amber',.047)
    reinforced_panels()
    bpy.context.scene['small_drone_reference'] = 'Unscaled V22 field frame 40, including stowed gear; paired by X0 mirror'


def carrier_coordinate_contract():
    global CARRIER_VERTICAL
    bb=d.bounds(d.all_points(d.geometry()))
    scale=7/(bb[5]-bb[2])
    offset=-bb[2]*scale
    CARRIER_VERTICAL=(scale,offset)
    for obj in bpy.context.scene.objects:
        if obj.type!='MESH':
            continue
        for vert in obj.data.vertices:
            # Keep the small canonical witnesses at their original physical size.
            vert.co.z=vert.co.z+BAY_CAPTURE_HEIGHT if obj.name.startswith('DockedV22_') else vert.co.z*scale+offset
        if 'lift_z' in obj:
            obj['lift_z']*=scale
    origin=v.empty('RuntimeOriginBellyCenter',(0,0,0))
    origin['runtime_axes']='X starboard / Y up / Z aft'
    beam=v.empty('BeamOrigin',(0,0,-.1))
    beam['runtime_offset']='0,-0.1,0'
    bays=[]
    for row,cx,cy,width,length in BAY_ROWS:
        for sign in (-1,1):
            slot=len(bays)
            x=sign*cx
            marker=v.empty('ServiceBay_'+str(slot),(x,cy,BAY_CAPTURE_HEIGHT))
            marker['slot']=slot
            bays.append({'slot':slot,'name':('port-' if sign<0 else 'starboard-')+row.lower(),
                         'runtimePosition':[x,BAY_CAPTURE_HEIGHT,cy],
                         'blenderPosition':[x,cy,BAY_CAPTURE_HEIGHT],
                         'approachRuntime':[x,-2,cy],'receivingFootprint':[3,3],
                         'runtimeClearVolume':[x-1.5,BAY_CAPTURE_HEIGHT,cy-1.5,x+1.5,BAY_CLEAR_TOP,cy+1.5],
                         'pocketWidth':width,'pocketLength':length})
    contract={'status':'PROPOSAL_PENDING_SEPARATE_CARRIER_APPROVAL',
              'blender':{'widthX':48,'lengthY':32,'heightZ':7,'bounds':[-24,-16,0,24,16,7],
                         'front':'-Y','up':'+Z','origin':'belly plane center','beamOrigin':[0,0,-.1]},
              'runtime':{'widthX':48,'lengthZ':32,'heightY':7,'bounds':[-24,0,-16,24,7,16],
                         'front':'-Z','up':'+Y','starboard':'+X','beamOffset':[0,-.1,0],
                         'yaw':'neutral / fixed','cabin':'separate dimension; not moving blocks'},
              'mapping':'Runtime(x,y,z) = Blender(x,z,y)',
              'serviceBays':bays,'serviceBayCount':4,
              'receivingClearance':{'entitySize':[3,DRONE_ENTITY_HEIGHT,3],
                                    'overheadAllowance':BAY_OVERHEAD_CLEARANCE,
                                    'clearHeight':BAY_CLEAR_TOP-BAY_CAPTURE_HEIGHT,
                                    'gearStowedV22Frame':40,'gearIncluded':True},
              'windingNote':'Axis exchange changes handedness; use existing exporter winding/normal convention.',
              'fitAdjustment':{'carrierVerticalScale':scale,'carrierVerticalOffset':offset,
                               'dockedV22Geometry':'translation only; scale unchanged'},
              'runtimeExport':'Deferred to coordinating task after explicit shown approval.'}
    bpy.context.scene['runtime_contract']=json.dumps(contract)
    dump(OUT/'runtime-contract.json',contract)


def animate_states():
    for obj in bpy.context.scene.objects:
        if 'slide_y' not in obj:
            continue
        for frame, y, z in ((1,0,0),(15,0,0),(30,obj['slide_y'],0),
                             (40,obj['slide_y'],obj['lift_z']),(80,obj['slide_y'],obj['lift_z'])):
            obj.location.y,obj.location.z = y,z
            obj.keyframe_insert('location',frame=frame)
    scene = bpy.context.scene
    scene.frame_start, scene.frame_end = 1,80
    for frame, name in STATES:
        scene.timeline_markers.new(name.upper(),frame=frame)
    scene.frame_set(40)


def bay_clearance_checks(parts):
    from mathutils.bvhtree import BVHTree
    graph=bpy.context.evaluated_depsgraph_get()
    body=bpy.data.objects['CarrierContinuousBlendedWing']
    tree=BVHTree.FromObject(body,graph)
    fixtures={obj.name:BVHTree.FromObject(obj,graph) for obj in bpy.context.scene.objects
              if obj.type=='MESH' and obj.name.startswith(('BayCeiling','BayCapture','BayMagnetic','BayGuide','BayShutter'))}
    scale,offset=CARRIER_VERTICAL
    entries=[]
    errors=[]
    for row,cx,cy,width,length in BAY_ROWS:
        for sign in (-1,1):
            x=cx*sign
            points=[p for n,ps in parts.items() if n.startswith('DockedV22_'+row+'_') for p in ps if p.x*sign>0]
            actual=d.bounds(points)
            volume=[x-1.5,cy-1.5,BAY_CAPTURE_HEIGHT,x+1.5,cy+1.5,BAY_CLEAR_TOP]
            inside=all(actual[k]>=volume[k]-.0001 and actual[k+3]<=volume[k+3]+.0001 for k in range(3))
            pocket=[x-width/2,cy-length/2,x+width/2,cy+length/2]
            def distance_to_pocket(px,py):
                return math.hypot(max(pocket[0]-px,0,px-pocket[2]),max(pocket[1]-py,0,py-pocket[3]))
            lens_clearance=distance_to_pocket(0,0)-5.02
            rotor_clearance=min(distance_to_pocket(fan,4)-4.82 for fan in (-11,11))
            hits=[]
            fixture_hits=[]
            roof_thickness=[]
            first_surfaces=[]
            for i in range(13):
                for j in range(13):
                    xy=(x-1.5+i*.25,cy-1.5+j*.25)
                    hit,_,_,_=tree.ray_cast(Vector((*xy,-1)),Vector((0,0,1)))
                    if hit is None or hit.z<BAY_CLEAR_TOP-.0001:
                        hits.append({'xy':xy,'firstHullZ':None if hit is None else hit.z})
                    if hit is not None:
                        first_surfaces.append(hit.z)
                    for name,fixture in fixtures.items():
                        obj=bpy.data.objects[name]
                        local_origin=obj.matrix_world.inverted() @ Vector((*xy,-1))
                        local_direction=obj.matrix_world.inverted().to_3x3() @ Vector((0,0,1))
                        contact,_,_,_=fixture.ray_cast(local_origin,local_direction)
                        if contact is not None:
                            height=(obj.matrix_world @ contact).z
                            first_surfaces.append(height)
                            if height<BAY_CLEAR_TOP-.0001:
                                fixture_hits.append({'xy':xy,'part':name,'firstSurfaceZ':height})
                    roof,_,_,_=tree.ray_cast(Vector((*xy,8)),Vector((0,0,-1)))
                    roof_thickness.append(0 if roof is None else roof.z-((BAY_CEILING_CENTER+.065)*scale+offset))
            actual_height=actual[5]-actual[2]
            entry={'slot':len(entries),'row':row,'blenderClearVolume':volume,'dockedBounds':actual,
                   'unscaledV22Inside3x3':inside,'pocketXY':pocket,'lensPlanClearance':lens_clearance,
                   'nearestRotorPlanClearance':rotor_clearance,'entryRaySamples':169,
                   'blockedEntryRays':hits,'blockedFixtureRays':fixture_hits,
                   'minimumReceivingCeilingZ':min(first_surfaces),
                   'ceilingUndersideZ':(BAY_CEILING_CENTER-.065)*scale+offset,
                   'minimumRoofThicknessOverReceivingArea':min(roof_thickness),
                   'entityHeight':DRONE_ENTITY_HEIGHT,'entityTopZ':BAY_CAPTURE_HEIGHT+DRONE_ENTITY_HEIGHT,
                   'reservedOverheadClearance':BAY_OVERHEAD_CLEARANCE,
                   'actualGearStowedMeshHeight':actual_height,
                   'actualMeshHeadroom':min(first_surfaces)-actual[5],
                   'captureAnchor':list(bpy.data.objects['ServiceBay_'+str(len(entries))].location)}
            entries.append(entry)
            if not inside or lens_clearance<=0 or rotor_clearance<=0 or hits or fixture_hits or min(roof_thickness)<.25:
                errors.append(entry)
    pairwise=[]
    for i,a in enumerate(entries):
        for b in entries[i+1:]:
            p,q=a['pocketXY'],b['pocketXY']
            overlap=min(p[2],q[2])>max(p[0],q[0]) and min(p[3],q[3])>max(p[1],q[1])
            pairwise.append({'slots':[a['slot'],b['slot']],'pocketsOverlap':overlap})
            if overlap:
                errors.append(pairwise[-1])
    ceilings=[]
    for name,points in parts.items():
        if not name.startswith('BayCeiling'):
            continue
        margins=[]
        for point in points:
            roof,_,_,_=tree.ray_cast(Vector((point.x,point.y,8)),Vector((0,0,-1)))
            margins.append(-1 if roof is None else roof.z-point.z)
        ceilings.append({'part':name,'minimumOuterRoofMargin':min(margins)})
        if min(margins)<.25:
            errors.append(ceilings[-1])
    stored=[]
    for name,points in parts.items():
        if not name.startswith('BayShutter'):
            continue
        violations=sum(p.z<skin(p.x,p.y,False)*scale+offset-.001 or
                       p.z>skin(p.x,p.y,True)*scale+offset+.001 for p in points)
        stored.append({'part':name,'verticesOutsideOuterHull':violations})
        if violations:
            errors.append(stored[-1])
    return {'passed':not errors,'bays':entries,'pairwise':pairwise,'ceilingOuterRoofClearance':ceilings,
            'stowedShutterContainment':stored,'errors':errors,
            'scope':'3x3 vertical hull/fixture opening rays, full gear-stowed witness fit, roof thickness and plan separation; not continuous mechanism collision proof'}


def check(item):
    scene = bpy.context.scene
    errors, samples = [], []
    for frame, name in STATES:
        scene.frame_set(frame)
        bpy.context.view_layer.update()
        parts = d.geometry()
        points = d.all_points(parts)
        bb = d.bounds(points)
        tree = kdtree.KDTree(len(points))
        for i,p in enumerate(points):
            tree.insert(p,i)
        tree.balance()
        deviation = max(tree.find(Vector((-p.x,p.y,p.z)))[2] for p in points)
        sample = {'frame':frame,'state':name,'bounds':bb,'dimensions':[bb[k+3]-bb[k] for k in range(3)],
                  'mirrorError':deviation,'vertices':len(points)}
        samples.append(sample)
        if deviation > .0001:
            errors.append({'mirror':sample})
        if item == 'chunkbuster' and (any(abs(a-b)>.0001 for a,b in zip(sample['dimensions'],(48,32,7)))
                                      or abs(bb[2])>.0001 or abs(bb[5]-7)>.0001):
            errors.append({'envelope':sample})
    mesh_objects = [o for o in scene.objects if o.type=='MESH']
    missing = [o.name for o in mesh_objects if not any(m.type=='MIRROR' and m.mirror_object==v.MIRROR for m in o.modifiers)]
    if missing:
        errors.append({'missing_shared_mirror':missing})
    contacts = {'mode':'floating / no ground contact' if item=='chunkbuster' else 'loose inventory supply',
                'landingGearCount':sum(o.name.startswith('GEAR_') for o in mesh_objects)}
    if item == 'chunkbuster':
        contacts['bayCaptureContacts'] = 2*sum(o.name.startswith('BayMagneticContact') for o in mesh_objects)
        contacts['rotorCount'] = 2*sum(o.name=='RotorHub' for o in mesh_objects)
        contacts['rotorBladeCount'] = 2*sum(o.name.startswith('RotorBlade') for o in mesh_objects)
        contacts['bayCount'] = 2*sum(o.name.startswith('BayCeiling') for o in mesh_objects)
        contacts['smallDroneCount'] = 4
        contacts['clearances']=bay_clearance_checks(d.geometry())
        if not contacts['clearances']['passed']:
            errors.append({'bay_fit':contacts['clearances']})
        if contacts['landingGearCount']:
            errors.append({'landing_gear_forbidden':True})
        if (contacts['rotorCount'],contacts['rotorBladeCount'],contacts['bayCount'],contacts['bayCaptureContacts'])!=(2,24,4,16):
            errors.append({'component_counts':contacts})
    report = {'id':item,'status':'PROPOSAL_AWAITING_SHOWN_USER_APPROVAL','passed':not errors,
              'displayName':DISPLAY_NAMES[item],
              'errors':errors,'axis':{'front':'-Y','up':'+Z','symmetry':'X=0','units':'blocks'},
              'states':samples,'meshObjects':len(mesh_objects),'sharedMirrorObjects':len(mesh_objects)-len(missing),
              'contacts':contacts,'capacity':CAPACITY.get(item),
              'limitations':['No runtime, launcher, installation or in-game validation.',
                            'Static GLB state exports; animated RuntimeDroneMesh adaptation is deferred.',
                            'No flight physics or arbitrary attitude collision proof.']}
    return report


def studio(item):
    d.studio()
    scene = bpy.context.scene
    scene.cycles.samples = 20
    scene.render.image_settings.color_mode = 'RGBA'
    if item == 'chunkbuster':
        for obj in scene.objects:
            if obj.type=='LIGHT':
                obj.location *= 15
                obj.data.energy *= 225
                obj.data.size *= 15
                d.aim(obj,(0,0,0))
    scene.camera.data.clip_end = 1000


def render(path, axis, span, target, size, frame=40):
    bpy.context.scene.frame_set(frame)
    rotation=(-Vector(axis)).to_track_quat('-Z','Y').inverted()
    projected=[rotation@(p-Vector(target)) for p in d.all_points(d.geometry())]
    width=2*max(abs(p.x) for p in projected)
    height=2*max(abs(p.y) for p in projected)
    required=max(width,height*size[0]/size[1]) if size[0]>=size[1] else max(height,width*size[1]/size[0])
    span=max(span,required*(1.32 if max(size)<=16 else 1.12))
    d.render(path,Vector(axis).normalized()*span*3,span,target=target,size=size,frame=frame)


def refresh_framing(item):
    folder=OUT/'models'/item
    bpy.ops.wm.open_mainfile(filepath=str(folder/(item+'.blend')))
    studio(item)
    bpy.context.scene.frame_set(40)
    bb=d.bounds(d.all_points(d.geometry()))
    target=tuple((bb[k]+bb[k+3])/2 for k in range(3))
    span=max(bb[k+3]-bb[k] for k in range(3))*1.4
    render(folder/'hero.png',(4,-6,4),span,target,(1100,850))
    if item!='chunkbuster':
        render(folder/'icon-16.png',(4,-6,4),span*.87,target,(16,16))


def export(path):
    bpy.ops.object.select_all(action='DESELECT')
    for obj in bpy.context.scene.objects:
        if obj.type in ('MESH','EMPTY'):
            obj.select_set(True)
    bpy.ops.export_scene.gltf(filepath=str(path),export_format='GLB',use_selection=True,
                            export_apply=True,export_animations=False,export_current_frame=True,
                            export_cameras=False,export_lights=False)


def export_existing(item):
    folder=OUT/'models'/item
    bpy.ops.wm.open_mainfile(filepath=str(folder/(item+'.blend')))
    bpy.context.scene.frame_set(40)
    export(folder/(item+'.glb'))
    if item=='chunkbuster':
        bpy.context.scene.frame_set(1)
        export(folder/'chunkbuster-closed.glb')
    report=json.loads((folder/'validation.json').read_text())
    report['glbSha256']=digest(folder/(item+'.glb'))
    dump(folder/'validation.json',report)


def build(item, quick=False):
    folder = OUT/'models'/item
    folder.mkdir(parents=True,exist_ok=True)
    e.setup()
    bpy.context.preferences.filepaths.save_version = 0
    globals()[{'autocannon_magazine':'magazine','micro_missile_pack':'missiles'}.get(item,item)]()
    canonical_halves()
    if item=='chunkbuster':
        carrier_coordinate_contract()
    animate_states()
    report = check(item)
    dump(folder/'validation.json',report)
    if not report['passed']:
        raise RuntimeError(report['errors'])
    studio(item)
    scene = bpy.context.scene
    scene['asset_status'] = 'V24_PROPOSAL_NOT_APPROVED_NOT_INSTALLED'
    scene['display_name'] = DISPLAY_NAMES[item]
    if item=='laser_cell':
        scene['display_name_ja'] = '\u5175\u88c5\u96fb\u529b\u30bb\u30eb'
        scene['energy_scope'] = 'Laser use, gun drive, missile ejection'
    scene['front_axis'], scene['up_axis'] = '-Y','+Z'
    scene.unit_settings.system = 'METRIC'
    scene.unit_settings.scale_length = 1
    bb = report['states'][1]['bounds']
    target = tuple((bb[k]+bb[k+3])/2 for k in range(3))
    span = max(bb[k+3]-bb[k] for k in range(3))*1.40
    scene.frame_set(40)
    export(folder/(item+'.glb'))
    if item=='chunkbuster':
        scene.frame_set(1)
        export(folder/'chunkbuster-closed.glb')
        scene.frame_set(40)
    bpy.ops.wm.save_as_mainfile(filepath=str(folder/(item+'.blend')))
    if quick:
        views = (VIEWS[0],VIEWS[1],VIEWS[3],VIEWS[5])
    else:
        views = VIEWS
    for name,axis in views:
        render(folder/(name+'.png'),axis,span,target,(1100,850))
    if item=='chunkbuster':
        render(folder/'belly-hero.png',(4,-6,-4),span,target,(1280,900))
        if not quick:
            render(folder/'closed.png',(4,-6,-4),span,target,(1100,850),1)
            render(folder/'service.png',(4,-6,-4),span,target,(1100,850),40)
            render(folder/'active.png',(0,0,-1),span,target,(1100,850),80)
    else:
        for size in ((64,1024) if quick else (16,32,64,1024)):
            render(folder/('icon-%d.png'%size),(4,-6,4),span*.87,target,(size,size))
    report['blendSha256'] = digest(folder/(item+'.blend'))
    report['glbSha256'] = digest(folder/(item+'.glb'))
    dump(folder/'validation.json',report)
    print('V24_COMPLETE',item,flush=True)


def verify_roundtrip(item):
    folder = OUT/'models'/item
    bpy.ops.wm.open_mainfile(filepath=str(folder/(item+'.blend')))
    bpy.context.scene.frame_set(40)
    source_parts=d.geometry()
    expected = d.bounds(d.all_points(source_parts))
    part_bounds={name:d.bounds(points) for name,points in source_parts.items()}
    original_count = len(source_parts)
    scene_axes=(bpy.context.scene.get('front_axis'),bpy.context.scene.get('up_axis'))
    expected_beam=list(bpy.data.objects['BeamOrigin'].location) if item=='chunkbuster' else None
    expected_bays={o.name:list(o.location) for o in bpy.context.scene.objects if o.name.startswith('ServiceBay_')}
    if item=='chunkbuster':
        bpy.context.scene.frame_set(1)
        closed_source={name:d.bounds(points) for name,points in d.geometry().items()}
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(folder/(item+'.glb')))
    imported=d.geometry()
    actual = d.bounds(d.all_points(imported))
    # glTF axes are converted back to Blender by its importer.
    error = max(abs(a-b) for a,b in zip(actual,expected))
    missing=sorted(set(part_bounds)-set(imported))
    part_error=max((max(abs(a-b) for a,b in zip(part_bounds[name],d.bounds(points)))
                    for name,points in imported.items() if name in part_bounds),default=0)
    beam=list(bpy.data.objects['BeamOrigin'].location) if item=='chunkbuster' else None
    beam_error=max(abs(a-b) for a,b in zip(beam,expected_beam)) if beam else 0
    bay_anchors={o.name:list(o.location) for o in bpy.context.scene.objects if o.name.startswith('ServiceBay_')}
    bay_anchor_error=max((max(abs(a-b) for a,b in zip(coords,bay_anchors.get(name,[999,999,999])))
                          for name,coords in expected_bays.items()),default=0)
    result = {'id':item,'passed':error<.0002 and part_error<.0002 and beam_error<.0002 and not missing and scene_axes==('-Y','+Z'),
              'beamAnchor':beam,'beamAnchorError':beam_error,
              'serviceBayAnchors':bay_anchors,'serviceBayAnchorError':bay_anchor_error,
              'maxBoundsError':error,'maxPerPartBoundsError':part_error,'missingParts':missing,'sourceBounds':expected,
              'importBounds':actual,'sourceMeshCount':original_count,'importMeshCount':len(d.geometry()),
              'state':'service / static GLB','frontAfterImport':'-Y','upAfterImport':'+Z'}
    result['passed']=result['passed'] and bay_anchor_error<.0002 and len(expected_bays)==len(bay_anchors)
    if item=='chunkbuster':
        bpy.ops.wm.read_factory_settings(use_empty=True)
        bpy.ops.import_scene.gltf(filepath=str(folder/'chunkbuster-closed.glb'))
        closed_import=d.geometry()
        closed_missing=sorted(set(closed_source)-set(closed_import))
        closed_error=max((max(abs(a-b) for a,b in zip(closed_source[name],d.bounds(points)))
                          for name,points in closed_import.items() if name in closed_source),default=0)
        result['closedStateCheck']={'passed':not closed_missing and closed_error<.0002,
                                    'maxPerPartBoundsError':closed_error,'missingParts':closed_missing,
                                    'sha256':digest(folder/'chunkbuster-closed.glb')}
        result['passed']=result['passed'] and result['closedStateCheck']['passed']
    dump(folder/'export-roundtrip.json',result)
    if not result['passed']:
        raise RuntimeError(result)
    print('V24_ROUNDTRIP',item,json.dumps(result),flush=True)


def reference_audit():
    sources = [ROOT/'docs/design/detail-scale-v22/field/airframe.blend',
               ROOT/'docs/design/equipment-hmi-v23/models/power_cell/power_cell.blend',
               ROOT/'docs/design/equipment-hmi-v23/models/autocannon_module/autocannon_module.blend',
               ROOT/'docs/design/equipment-hmi-v23/models/missile_module/missile_module.blend']
    audits = []
    for path in sources:
        before = digest(path)
        bpy.ops.wm.open_mainfile(filepath=str(path))
        bpy.context.scene.frame_set(40)
        parts = d.geometry()
        audits.append({'path':str(path.relative_to(ROOT)),'sha256':before,
                       'bounds':d.bounds(d.all_points(parts)),'meshCount':len(parts),
                       'materials':[m.name for m in bpy.data.materials],
                       'unchanged':before==digest(path)})
    dump(OUT/'reference-audit.json',audits)


def sheet_start(width,height):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene=bpy.context.scene
    scene.render.engine='BLENDER_EEVEE'
    scene.render.resolution_x,scene.render.resolution_y=width,height
    scene.render.resolution_percentage=100
    scene.render.image_settings.file_format='PNG'
    scene.render.image_settings.color_mode='RGBA'
    scene.view_settings.view_transform='Standard'
    scene.render.film_transparent=False
    scene.world=bpy.data.worlds.new('SheetWorld')
    scene.world.use_nodes=True
    scene.world.node_tree.nodes['Background'].inputs[0].default_value=(.020,.026,.030,1)
    scene.world.node_tree.nodes['Background'].inputs[1].default_value=1
    bpy.ops.object.camera_add(location=(width/2,height/2,100))
    scene.camera=bpy.context.object
    scene.camera.data.type='ORTHO'
    scene.camera.data.ortho_scale=max(width,height)
    scene.camera.data.clip_end=1000
    return scene


def flat_material(name,color=None,path=None):
    material=bpy.data.materials.new(name)
    material.use_nodes=True
    nodes=material.node_tree.nodes
    nodes.clear()
    out=nodes.new('ShaderNodeOutputMaterial')
    emission=nodes.new('ShaderNodeEmission')
    emission.inputs['Color'].default_value=color or (.8,.88,.91,1)
    if path:
        tex=nodes.new('ShaderNodeTexImage')
        tex.image=bpy.data.images.load(str(path),check_existing=True)
        tex.interpolation='Closest' if 'icon-' in str(path) else 'Linear'
        material.node_tree.links.new(tex.outputs['Color'],emission.inputs[0])
        transparent=nodes.new('ShaderNodeBsdfTransparent')
        mix=nodes.new('ShaderNodeMixShader')
        material.node_tree.links.new(tex.outputs['Alpha'],mix.inputs[0])
        material.node_tree.links.new(transparent.outputs[0],mix.inputs[1])
        material.node_tree.links.new(emission.outputs[0],mix.inputs[2])
        material.node_tree.links.new(mix.outputs[0],out.inputs[0])
    else:
        material.node_tree.links.new(emission.outputs[0],out.inputs[0])
    return material


def sheet_image(path,x,y,width,height,uv=(0,0,1,1)):
    import numpy as np
    source=bpy.data.images.load(str(path),check_existing=True)
    sw,sh=source.size
    # Read alpha only to frame a texture plane; no raster is modified or saved.
    if uv==(0,0,1,1) and not Path(path).name.startswith('icon-'):
        pixels=np.empty(sw*sh*4,dtype=np.float32)
        source.pixels.foreach_get(pixels)
        yy,xx=np.where(pixels.reshape(sh,sw,4)[:,:,3]>.03)
        if len(xx):
            pad=max(sw,sh)*.018
            uv=(max(0,(xx.min()-pad)/sw),max(0,(yy.min()-pad)/sh),
                min(1,(xx.max()+1+pad)/sw),min(1,(yy.max()+1+pad)/sh))
    ratio=(sw*(uv[2]-uv[0]))/(sh*(uv[3]-uv[1]))
    fitted_width=min(width,height*ratio)
    fitted_height=fitted_width/ratio
    x+=(width-fitted_width)/2
    y+=(height-fitted_height)/2
    width,height=fitted_width,fitted_height
    canvas_height=bpy.context.scene.render.resolution_y
    y=canvas_height-y-height
    data=bpy.data.meshes.new('SheetImage')
    data.from_pydata([(x,y,0),(x+width,y,0),(x+width,y+height,0),(x,y+height,0)],[],[(0,1,2,3)])
    data.materials.append(flat_material('Image_'+Path(path).stem,path=path))
    layer=data.uv_layers.new()
    u0,v0,u1,v1=uv
    for loop,coord in zip(layer.data,((u0,v0),(u1,v0),(u1,v1),(u0,v1))):
        loop.uv=coord
    obj=bpy.data.objects.new('SheetImage',data)
    bpy.context.collection.objects.link(obj)


def sheet_text(text,x,y,size=24,color=(.76,.86,.88,1)):
    data=bpy.data.curves.new('SheetLabel','FONT')
    data.body=text
    data.size=size
    data.materials.append(flat_material('Label',color))
    obj=bpy.data.objects.new('SheetLabel',data)
    bpy.context.collection.objects.link(obj)
    obj.location=(x,bpy.context.scene.render.resolution_y-y-size,2)


def sheet_save(path):
    bpy.context.scene.render.filepath=str(path)
    bpy.ops.render.render(write_still=True)


def sheets():
    for item in ITEMS:
        folder=OUT/'models'/item
        sheet_start(1440,1080)
        sheet_text('Morrowgear / '+DISPLAY_NAMES[item].upper()+' / PROPOSAL',28,18,27)
        for i,(name,_) in enumerate(VIEWS):
            x,y=(i%3)*480,(i//3)*332+78
            sheet_text(name.upper(),x+20,y,18)
            sheet_image(folder/(name+'.png'),x+7,y+24,460,280)
        sheet_text('FRONT -Y / UP +Z / MIRROR X=0',504,778,20)
        sheet_text('SAME CANONICAL MESH',504,816,20)
        sheet_save(folder/'contact-sheet.png')
    sheet_start(840,1440)
    sheet_text('Morrowgear / V24',28,20,33)
    sheet_text('PROPOSAL / USER APPROVAL PENDING',28,65,20,(.85,.49,.17,1))
    sheet_text('Chunkbuster / 48 x 32 x 7 blocks',28,106,23)
    folder=OUT/'models/chunkbuster'
    sheet_image(folder/'hero.png',0,136,840,480)
    sheet_image(folder/'belly-hero.png',0,548,840,480)
    sheet_text('4 recessed service bays / central optic / paired fans',28,994,21)
    for i,item in enumerate(ITEMS[:3]):
        x=10+i*276
        sheet_image(OUT/'models'/item/'icon-1024.png',x,1044,268,268)
        sheet_text(DISPLAY_NAMES[item],x+14,1316,21)
        count,unit=CAPACITY[item]
        sheet_text(str(count)+' '+unit,x+14,1350,18)
    sheet_text('Native 3D / no runtime changes',28,1398,18)
    sheet_save(OUT/'proposal-mobile.png')
    sheet_start(1500,1080)
    sheet_text('REFERENCE / PROPOSAL COMPARISON',25,20,30)
    sheet_text('V5 older concept: underside evidence',25,75,20)
    sheet_image(ROOT/'docs/design/blended-wing-v5/family-equipment.png',25,108,700,480,
                (1000/1681,0,1,480/936))
    sheet_text('V24 purpose-built underside',780,75,20)
    sheet_image(folder/'belly-hero.png',750,104,740,495)
    sheet_text('V22 actual canonical field / family reference',25,638,20)
    sheet_image(ROOT/'docs/design/detail-scale-v22/field/hero.png',20,682,700,355)
    sheet_text('V24 top silhouette / new 48-block structure',780,638,20)
    sheet_image(folder/'hero.png',750,674,740,363)
    sheet_save(OUT/'reference-comparison.png')
    sheet_start(1320,650)
    sheet_text('SUPPLY ICONS / NATIVE 16 / 32 / 64 / HIGH RESOLUTION',24,18,26)
    for i,item in enumerate(ITEMS[:3]):
        x=i*440
        sheet_text(DISPLAY_NAMES[item].upper(),x+22,71,20)
        sheet_image(OUT/'models'/item/'icon-1024.png',x+42,102,348,348)
        for size,dx in ((16,52),(32,133),(64,245)):
            sheet_text(str(size)+'px',x+dx,477,18)
            sheet_image(OUT/'models'/item/('icon-%d.png'%size),x+dx,521,size,size)
    sheet_save(OUT/'native-icons.png')
    sheet_start(1440,670)
    sheet_text('CHUNKBUSTER / STATE AND CONTACT REVIEW',25,18,28)
    for i,(name,label) in enumerate((('closed','CLOSED / SHUTTERS FLUSH'),('service','SERVICE / FOUR RECESSED BAYS'),('active','ACTIVE / BELLY OPTIC'))):
        sheet_text(label,i*480+18,72,18)
        sheet_image(folder/(name+'.png'),i*480,118,480,370)
    sheet_text('No landing gear. Internal magnetic capture points only.',26,535,24)
    sheet_text('Front -Y / up +Z / symmetry X=0 / static state GLBs',26,578,21)
    sheet_save(OUT/'states-contact.png')
    sheet_start(1280,860)
    sheet_text('CHUNKBUSTER / FOUR PHYSICAL SERVICE BAYS',24,20,30)
    sheet_image(folder/'bottom.png',12,125,725,655)
    sheet_text('FRONT -Z',295,788,22)
    sheet_text('Runtime local XYZ / belly origin',775,110,24)
    for i,bay in enumerate(json.loads((OUT/'runtime-contract.json').read_text())['serviceBays']):
        sheet_text('%02d  %s'%(i,bay['name'].upper()),775,184+i*110,22)
        sheet_text(str(tuple(bay['runtimePosition'])),775,222+i*110,23)
    sheet_text('Clear volume: 3 x 3 x 0.95',775,648,21)
    sheet_text('Y 2.4 to 3.35 / entity height 0.75',775,688,21)
    sheet_text('Staging height -2 / capture 2.4',775,728,21)
    sheet_text('4 openings / no rotor or lens overlap',775,768,19)
    sheet_save(OUT/'bay-layout.png')
    supply_sheet()


def supply_sheet():
    sheet_start(1020,600)
    sheet_text('Morrowgear / Dedicated Supplies',24,18,30)
    sheet_text('PROPOSAL / APPROVAL SEPARATE FROM CARRIER',24,65,18,(.85,.49,.17,1))
    for i,item in enumerate(ITEMS[:3]):
        x=i*340
        sheet_image(OUT/'models'/item/'icon-1024.png',x+10,114,320,320)
        sheet_text(DISPLAY_NAMES[item],x+23,445,23)
        count,unit=CAPACITY[item]
        sheet_text(str(count)+' '+unit,x+23,485,21)
        for size,dx in ((16,24),(32,122),(64,234)):
            sheet_image(OUT/'models'/item/('icon-%d.png'%size),x+dx,533,size,size)
            sheet_text(str(size),x+dx+size+5,541,13)
    sheet_save(OUT/'supplies-approval.png')


def validate_outputs():
    import numpy as np
    errors, images, models, icon_masks = [], [], [], {}
    for item in ITEMS:
        folder=OUT/'models'/item
        for name in (item+'.blend',item+'.glb','contact-sheet.png','validation.json','export-roundtrip.json'):
            if not (folder/name).is_file():
                errors.append({'missing':str(folder/name)})
        report=json.loads((folder/'validation.json').read_text())
        exported=json.loads((folder/'export-roundtrip.json').read_text())
        if not report['passed'] or not exported['passed']:
            errors.append({'model':item})
        if report['blendSha256']!=digest(folder/(item+'.blend')) or report['glbSha256']!=digest(folder/(item+'.glb')):
            errors.append({'stale_model_hash':item})
        bpy.ops.wm.open_mainfile(filepath=str(folder/(item+'.blend')))
        scene=bpy.context.scene
        sweep=[]
        if item=='chunkbuster':
            for frame in range(1,81):
                scene.frame_set(frame)
                graph=bpy.context.evaluated_depsgraph_get()
                pts=[o.evaluated_get(graph).matrix_world@Vector(p) for o in scene.objects
                     if o.type=='MESH' for p in o.evaluated_get(graph).bound_box]
                bb=d.bounds(pts)
                if bb[0]<-24.0001 or bb[3]>24.0001 or bb[1]<-16.0001 or bb[4]>16.0001 or bb[2]<-.0001 or bb[5]>7.0001:
                    sweep.append({'frame':frame,'bounds':bb})
            if sweep:
                errors.append({'state_envelope_sweep':sweep})
            movement=[]
            for obj in scene.objects:
                if 'slide_y' in obj:
                    scene.frame_set(1)
                    closed=list(obj.location)
                    scene.frame_set(40)
                    opened=list(obj.location)
                    movement.append({'object':obj.name,'closed':closed,'service':opened})
            if len(movement)!=10 or any(p['closed']==p['service'] for p in movement):
                errors.append({'shutter_state_motion':movement})
            if tuple(round(n,5) for n in bpy.data.objects['BeamOrigin'].location)!=(0,0,-.1):
                errors.append({'beam_anchor':'incorrect'})
        else:
            movement=[]
        models.append({'id':item,'geometryPassed':report['passed'],'roundtripPassed':exported['passed'],
                       'sweepFrames':80 if item=='chunkbuster' else 0,'sweepFailures':sweep,'stateMovement':movement})
        paths=[folder/(name+'.png') for name,_ in VIEWS]
        if item!='chunkbuster':
            paths += [folder/('icon-%d.png'%size) for size in (16,32,64,1024)]
        else:
            paths += [folder/(name+'.png') for name in ('belly-hero','closed','service','active')]
        for path in paths:
            img=bpy.data.images.load(str(path),check_existing=False)
            w,h=img.size
            pixels=np.empty(w*h*4,dtype=np.float32)
            img.pixels.foreach_get(pixels)
            pixels=pixels.reshape(h,w,4)
            alpha=pixels[:,:,3]
            ys,xs=np.where(alpha>.08)
            occupied=int(len(xs))
            clipped=bool(occupied and (xs.min()==0 or ys.min()==0 or xs.max()==w-1 or ys.max()==h-1))
            nonblank=occupied>max(2,w*h*.003)
            if clipped or not nonblank:
                errors.append({'image':str(path.relative_to(OUT)),'clipped':clipped,'nonblank':nonblank})
            images.append({'path':str(path.relative_to(OUT)),'size':[w,h],'occupiedPixels':occupied,
                           'clipped':clipped,'nonblank':nonblank,'sha256':digest(path)})
            if path.name=='icon-16.png':
                icon_masks[item]=alpha>.15
            bpy.data.images.remove(img)
    silhouette=[]
    for i,a in enumerate(ITEMS[:3]):
        for b in ITEMS[i+1:3]:
            left,right=icon_masks[a],icon_masks[b]
            iou=float(np.logical_and(left,right).sum()/np.logical_or(left,right).sum())
            silhouette.append({'a':a,'b':b,'alphaIntersectionOverUnion16px':iou})
            if iou>.90:
                errors.append({'indistinct_16px_silhouette':[a,b,iou]})
    references=json.loads((OUT/'reference-audit.json').read_text())
    for source in references:
        if digest(ROOT/source['path'])!=source['sha256']:
            errors.append({'source_changed':source['path']})
    for name in ('build.log','carrier-build.log','verify.log','sheets.log'):
        contents=(OUT/name).read_text(encoding='utf-8',errors='replace')
        if 'Traceback (most recent call last)' in contents:
            errors.append({'traceback':name})
    for name in ('proposal-mobile.png','supplies-approval.png','reference-comparison.png','states-contact.png','native-icons.png','bay-layout.png'):
        if not (OUT/name).is_file():
            errors.append({'missing_sheet':name})
    output_times={str(path.relative_to(OUT)):path.stat().st_mtime for path in OUT.rglob('*')
                  if path.is_file() and path.suffix in ('.png','.blend','.glb')}
    result={'passed':not errors,'errors':errors,'models':models,'renderChecks':images,
            'distinctSilhouetteChecks':silhouette,'protectedCanonicalSources':references,
            'approval':{'supplies':'pending user-visible approval','carrier':'pending separate user-visible approval'},
            'runtimeChanges':False,'imageEditing':'None; native Blender rendering and read-only pixel checks',
            'outputModifiedUnixSeconds':output_times,
            'limitations':['Not a runtime or game test.','No continuous bay-mechanism collision proof.',
                           'Three states have symmetry checks; all 80 frames have envelope checks.',
                           'Tiny-icon alpha difference is not a human recognition study.']}
    dump(OUT/'output-checks.json',result)
    print('V24_OUTPUT_CHECKS',json.dumps({'passed':result['passed'],'errors':errors,'renders':len(images),
                                         'models':len(models),'silhouettes':silhouette}),flush=True)
    if errors:
        raise RuntimeError(errors)


def budget_stats():
    import struct
    entries=[]
    for item in ITEMS:
        folder=OUT/'models'/item
        bpy.ops.wm.open_mainfile(filepath=str(folder/(item+'.blend')))
        bpy.context.scene.frame_set(40)
        graph=bpy.context.evaluated_depsgraph_get()
        objects=[o for o in bpy.context.scene.objects if o.type=='MESH']
        evaluated_vertices=triangles=witness_triangles=0
        materials=set()
        for obj in objects:
            ev=obj.evaluated_get(graph)
            mesh=ev.to_mesh()
            mesh.calc_loop_triangles()
            evaluated_vertices+=len(mesh.vertices)
            triangles+=len(mesh.loop_triangles)
            if obj.name.startswith('DockedV22_'):
                witness_triangles+=len(mesh.loop_triangles)
            materials.update(m.name for m in mesh.materials if m)
            ev.to_mesh_clear()
        binary=(folder/(item+'.glb')).read_bytes()
        length,kind=struct.unpack_from('<II',binary,12)
        if kind!=0x4E4F534A:
            raise ValueError('Missing GLB JSON chunk')
        gltf=json.loads(binary[20:20+length])
        primitives=[p for mesh in gltf['meshes'] for p in mesh['primitives']]
        position_accessors={p['attributes']['POSITION'] for p in primitives}
        glb_vertices=sum(gltf['accessors'][a]['count'] for a in position_accessors)
        glb_triangles=sum(gltf['accessors'][p['indices']]['count']//3 for p in primitives if p.get('mode',4)==4)
        entries.append({'id':item,'displayName':DISPLAY_NAMES[item],
                        'meshObjects':len(objects),'editableHalfVertices':sum(len(o.data.vertices) for o in objects),
                        'evaluatedVertices':evaluated_vertices,'evaluatedTriangles':triangles,
                        'includedDockedWitnessTriangles':witness_triangles,'materialsUsed':len(materials),
                        'glbPrimitives':len(primitives),'glbVertices':glb_vertices,'glbTriangles':glb_triangles,
                        'blendBytes':(folder/(item+'.blend')).stat().st_size,'glbBytes':len(binary),
                        'texturesInGlb':len(gltf.get('textures',[]))})
    result={'models':entries,'status':'Proposal budgets, not a runtime performance approval',
            'notes':['GLB vertices include normal/material splits.',
                     'Carrier totals include four actual V22 docked scale witnesses.',
                     'No LOD, runtime texture baking, draw-call merge or in-game profiling is included.',
                     'Use simplified inventory/held geometry and dedicated carrier LODs after approval.']}
    dump(OUT/'model-budget.json',result)
    print('V24_BUDGET',json.dumps(result),flush=True)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--item',choices=ITEMS)
    parser.add_argument('--quick',action='store_true')
    parser.add_argument('--audit',action='store_true')
    parser.add_argument('--verify',action='store_true')
    parser.add_argument('--sheets',action='store_true')
    parser.add_argument('--supply-sheet',action='store_true')
    parser.add_argument('--validate',action='store_true')
    parser.add_argument('--export-only',action='store_true')
    parser.add_argument('--refresh-framing',action='store_true')
    parser.add_argument('--stats',action='store_true')
    args=parser.parse_args(sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else [])
    OUT.mkdir(parents=True,exist_ok=True)
    if args.stats:
        budget_stats()
    elif args.refresh_framing:
        for item in ([args.item] if args.item else ITEMS[:3]):
            refresh_framing(item)
    elif args.export_only:
        for item in ([args.item] if args.item else ITEMS):
            export_existing(item)
    elif args.validate:
        validate_outputs()
    elif args.supply_sheet:
        supply_sheet()
    elif args.audit:
        reference_audit()
    elif args.sheets:
        sheets()
    elif args.verify:
        for item in ([args.item] if args.item else ITEMS):
            verify_roundtrip(item)
    else:
        for item in ([args.item] if args.item else ITEMS):
            build(item,args.quick)


if __name__=='__main__':
    main()
