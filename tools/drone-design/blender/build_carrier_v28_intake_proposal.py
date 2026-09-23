"""Read-only V27 derivative: native intake proposal, never runtime publication."""
import importlib.util
import json
import sys
from pathlib import Path

sys.dont_write_bytecode = True
import bpy
from mathutils import Vector, kdtree
from mathutils.bvhtree import BVHTree

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = ROOT / 'docs/design/carrier-v28-intake-proposal'
BASE = ROOT / 'docs/design/carrier-v27'
SOURCE = BASE / 'carrier-v27.blend'
REVISION = 'V28_INTAKE_PROPOSAL_01'
spec = importlib.util.spec_from_file_location('carrier27', HERE / 'build_carrier_v27.py')
c = importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)
q, d, v = c.q, c.d, c.v


def meshes():
    return [obj for obj in bpy.context.scene.objects if obj.type == 'MESH']


def object_points(objects):
    return d.all_points(d.geometry(objects))


def open_source(path):
    c.e.setup()
    names = {key: material.name for key, material in v.M.items()}
    names.update(panel='V20_CarrierCastPanel', deck='V20_CarrierFlightDeck', mark='V20_CarrierPaleLane',
                 warm='V20_CarrierWarmIllumination', boarding='V20_CarrierBoardingCyan')
    bpy.ops.wm.open_mainfile(filepath=str(path))
    v.M = {key: bpy.data.materials[name] for key, name in names.items() if name in bpy.data.materials}
    v.MIRROR = bpy.data.objects['Symmetry_X0']
    bpy.context.preferences.filepaths.save_version = 0


def protected_files():
    assets = ROOT / 'src/main/resources/assets/morrowgear_drone'
    paths = [SOURCE, BASE / 'carrier-v27.glb']
    paths += [path for path in assets.rglob('carrier*') if path.is_file()]
    paths += [assets / 'textures/item' / (item + '.png') for item in
              ('autocannon_magazine', 'laser_cell', 'micro_missile_pack')]
    return {str(path.relative_to(ROOT)): q.digest(path) for path in paths}


def loop(width, bottom, top, y, rake=0):
    chamfer = .32
    xz = [(-width/2+chamfer, bottom), (width/2-chamfer, bottom),
          (width/2, bottom+chamfer), (width/2, top-chamfer),
          (width/2-chamfer, top), (-width/2+chamfer, top),
          (-width/2, top-chamfer), (-width/2, bottom+chamfer)]
    return [(x, y+rake*(z-(bottom+top)/2), z) for x, z in xz]


def skin_between(name, sections, material, closed=False):
    n = len(sections[0])
    vertices = [point for section in sections for point in section]
    faces = [(row*n+i, row*n+(i+1)%n, (row+1)*n+(i+1)%n, (row+1)*n+i)
             for row in range(len(sections)-1) for i in range(n)]
    if closed:
        faces += [tuple(reversed(range(n))), tuple(range(len(vertices)-n, len(vertices)))]
    obj = v.mesh(name, vertices, faces, material)
    obj['intake_proposal'] = True
    obj['carrier_runtime'] = False
    return obj


def geometry_signature(parts):
    points = object_points(parts)
    return sorted(set(tuple(round(value, 6) for value in p) for p in points))


def intake():
    body = next(obj for obj in meshes() if obj.name.startswith('CarrierContinuousHull'))
    bpy.context.view_layer.objects.active = body
    for modifier in list(body.modifiers):
        bpy.ops.object.modifier_apply(modifier=modifier.name)
    # The relief grows continuously under the retained chined nose into the mouth.
    relief = skin_between('IntakeApproachVoid', [
        loop(3.2, -3, 2.35, -23.6), loop(10.7, -3, 3.50, -20.2),
        loop(14.3, -3, 4.35, -16.0), loop(13.7, -3, 4.23, -12.5),
        loop(11.8, -3, 3.95, -10.5)], 'dark', True)
    q.subtract(body, relief)
    for obj in meshes():
        if obj.name.startswith('BellyEquipmentBus') and d.bounds(object_points([obj]))[4] < -8:
            bpy.data.objects.remove(obj, do_unlink=True)

    outer = loop(14.3, 1.05, 4.37, -16.3, .54)
    face = loop(13.65, 1.34, 4.09, -16.48, .54)
    inner = loop(13.2, 1.54, 3.99, -15.80, .40)
    # A thick continuous rim, then an enclosed tapered duct, not an applied box.
    skin_between('IntakeIntegratedArmorLip', [outer, face, inner], 'edge')
    throat = loop(11.55, 2.08, 3.89, -11.2)
    skin_between('IntakeDuctWalls', [inner, loop(12.8, 1.84, 3.94, -13.4), throat], 'dark')
    skin_between('IntakeBellyTransition', [
        loop(14.3, 1.05, 4.37, -16.3, .54),
        loop(12.7, 1.45, 4.18, -12.5), loop(11.8, 1.50, 3.96, -10.3)], 'armor')
    # Close the aft end before the separate forward service-room envelope.
    skin_between('IntakeServiceIsolationBulkhead', [
        loop(11.8, 1.50, 3.96, -10.5), loop(11.8, 1.50, 3.96, -10.3)], 'panel', True)
    divider = c.loft('IntakeCentralDivider', [(-17.35,.08,3.68,1.31),
        (-16.1,.22,4.06,1.25), (-13.4,.24,3.97,1.64), (-10.35,.24,3.91,1.56)], 'panel')
    divider['intake_proposal'] = True
    divider['carrier_runtime'] = False
    for x in (1.45, 2.9, 4.35):
        obj = c.prism('IntakeRecessedGuideVane', [(x,-13.3),(x+.085,-11.3),(x-.085,-11.3)],
                      2.12,3.82,'edge',True)
        obj['intake_proposal'] = True
        obj['carrier_runtime'] = False
    for x in (3.1,):
        obj = c.box('IntakeAftHeatMatrix', (x,-10.91,2.99), (5.1,.15,1.43), 'dark', True, .0)
        obj['intake_proposal'] = True
        for height in (2.52, 2.96, 3.40):
            obj = c.box('IntakeAftFlowStraightener', (x,-11.02,height), (4.95,.10,.065), 'panel', True, .0)
            obj['intake_proposal'] = True
    q.canonical_halves()
    for obj in meshes():
        if obj.name.startswith('Intake'):
            obj['intake_proposal'] = True
            obj['carrier_runtime'] = False


def area(name, location, energy, size):
    data = bpy.data.lights.new(name, 'AREA')
    data.energy, data.shape, data.size = energy, 'DISK', size
    obj = bpy.data.objects.new(name, data)
    bpy.context.collection.objects.link(obj)
    obj.location = location
    d.aim(obj, (0,-12,2))


def lighting():
    scene = bpy.context.scene
    scene.world.node_tree.nodes['Background'].inputs[0].default_value = (.28,.28,.28,1)
    scene.render.film_transparent = False
    scene.cycles.samples = 20
    for sign in (-1,1):
        area('ProposalBellyFill', (sign*18,-32,-18), 50000, 22)


def validation(baseline):
    c.REVISION = REVISION
    report = c.validate()
    errors = report['errors']
    graph = bpy.context.evaluated_depsgraph_get()
    duct = [obj for obj in meshes() if obj.get('intake_proposal')]
    rooms = [obj for obj in meshes() if obj.name.startswith('Service') and 'Forward' in obj.name]
    duct_bounds = d.bounds(object_points(duct))
    room_bounds = d.bounds(object_points(rooms))
    overlaps = []
    for a in duct:
        ta = BVHTree.FromObject(a, graph)
        for b in rooms:
            hits = ta.overlap(BVHTree.FromObject(b, graph))
            if hits:
                overlaps.append({'intake':a.name,'bay':b.name,'trianglePairs':len(hits)})
    gap = room_bounds[1]-duct_bounds[4]
    if gap < 1.5 or overlaps:
        errors.append({'intakeRoomSeparation':gap,'intersections':overlaps})
    room_volumes = [{'slot':slot,'runtimeBounds':[sign*7.15-2.3,1.1,-8.1,
                    sign*7.15+2.3,3.80,-2.3], 'longitudinalGap':-8.1-duct_bounds[4]}
                   for slot,sign in ((0,-1),(1,1))]
    emitter = geometry_signature([obj for obj in meshes() if 'Emitter' in obj.name])
    if emitter != baseline['emitter']:
        errors.append('central emitter geometry changed')
    anchors = {obj.name:list(obj.location) for obj in bpy.context.scene.objects
               if obj.name.startswith(('ServiceBay_','BeamOrigin','BoardingProjection'))}
    if anchors != baseline['anchors']:
        errors.append('service, beam or boarding anchor changed')
    rays = []
    for sign in (-1,1):
        for x in (1.8,3.4,5.0):
            origin = Vector((sign*x,-16.0,2.72))
            hit, point, _, _, obj, _ = bpy.context.scene.ray_cast(graph,origin,Vector((0,1,0)))
            good = bool(hit and point.y > -13.4 and obj.name.startswith('Intake'))
            rays.append({'x':sign*x,'clearDepth':point.y+16 if hit else None,
                         'target':obj.name if hit else None,'passed':good})
    if not all(ray['passed'] for ray in rays):
        errors.append({'intakeDepthRays':rays})
    report.update(passed=not errors, status='PROPOSAL_AWAITING_USER_APPROVAL',
        intake={'boundsBlender':duct_bounds,'clearMouthWidth':13.65,'clearMouthHeight':2.75,
                'centralDivider':True,'forwardServiceBoundsBlender':room_bounds,
                'bayRoomEnvelopeChecks':room_volumes,'longitudinalSeparation':gap,
                'triangleIntersections':overlaps,'recessDepthRays':rays},
        centralEmitterUnchanged=emitter==baseline['emitter'], anchorsUnchanged=anchors==baseline['anchors'],
        sourceSha256=q.digest(SOURCE),
        scope='Static proposal geometry and service/boarding spaces only; no airflow, CFD, moving mechanism or game validation')
    return report


def render_views():
    scene = bpy.context.scene
    scene.render.film_transparent = False
    q.render(OUT/'front-belly-hero.png',(4,-7,-3.6),61,(0,0,5.5),(1440,1080))
    print('FIRST_INTAKE_RENDER_READY', flush=True)
    d.render(OUT/'intake-detail.png',Vector((2,-5,-1.9)).normalized()*75,24,
             target=(0,-14,2.5),size=(1440,960),frame=40)
    for name,axis in q.VIEWS:
        q.render(OUT/(name+'.png'),axis,61,(0,0,5.5),(1280,1024))
    q.render(OUT/'belly-overview.png',(3,5,-5),61,(0,0,5.5),(1440,1080))


def sheets():
    q.sheet_start(1440,1100)
    q.sheet_text('V28 / LOWER FORWARD INTAKE / PROPOSAL ONLY',24,18,28)
    for i,(name,_) in enumerate(q.VIEWS):
        x,y=i%3*480,i//3*335+70
        q.sheet_text(name.upper(),x+20,y,18)
        q.sheet_image(OUT/(name+'.png'),x+8,y+24,464,290)
    q.sheet_text('26 W / 50 L / 11 H / exact X0 mirror',503,814,21)
    q.sheet_text('4 service bays + central emitter + aft boarding',503,860,19)
    q.sheet_text('Approved runtime assets unchanged',503,902,21)
    q.sheet_save(OUT/'contact-sheet.png')
    q.sheet_start(1440,1120)
    q.sheet_text('V27 / V28 INTAKE PROPOSAL / SAME CAMERA',24,18,28)
    for i,(before,after) in enumerate((('before-front-belly.png','front-belly-hero.png'),
                                     ('before-front.png','front.png'))):
        y=84+i*505
        q.sheet_text('BEFORE',20,y,20)
        q.sheet_text('INTAKE PROPOSAL',750,y,20)
        q.sheet_image(OUT/before,15,y+25,700,455)
        q.sheet_image(OUT/after,745,y+25,680,455)
    q.sheet_save(OUT/'before-after.png')
    q.sheet_start(900,1570)
    q.sheet_text('V28 / INTAKE PROPOSAL',25,18,30)
    q.sheet_image(OUT/'front-belly-hero.png',15,76,870,630)
    q.sheet_image(OUT/'intake-detail.png',15,735,870,580)
    q.sheet_text('13.65 wide / 2.75 high / two recessed ducts',26,1353,25)
    q.sheet_text('4 supply bays + belly emitter + aft boarding retained',26,1407,22)
    q.sheet_text('Geometry proposal / no runtime asset changes',26,1461,24)
    q.sheet_save(OUT/'approval-mobile.png')


def inspection():
    open_source(OUT/'carrier-v28-intake-proposal.blend')
    for obj in meshes():
        keep = obj.name.startswith('Intake') or (obj.name.startswith('Service') and 'Forward' in obj.name)
        obj.hide_render = not keep or obj.name.startswith(('IntakeBellyTransition','IntakeDuctWalls'))
    q.render(OUT/'internal-front.png',(2,-4,3),30,(0,-11,2.5),(1400,900))
    q.render(OUT/'bulkhead-rear.png',(2,4,-3),30,(0,-11,2.5),(1400,900))
    q.sheet_start(1400,1060)
    q.sheet_text('V28 / INTERNAL SEPARATION / INSPECTION ONLY',24,18,26)
    q.sheet_text('Hull + duct covers hidden / no parts moved or scaled',24,58,20)
    q.sheet_image(OUT/'internal-front.png',16,104,680,680)
    q.sheet_image(OUT/'bulkhead-rear.png',704,104,680,680)
    q.sheet_text('CENTRAL DIVIDER + RECESSED VANES',24,812,22)
    q.sheet_text('CLOSED AFT BULKHEAD + SERVICE ROOMS',714,812,21)
    q.sheet_text('Intake aft Z=-10.30 / bay assembly front Z=-8.31 / gap 1.99',24,888,25)
    q.sheet_text('Room envelope gap 2.20 / triangle intersections 0',24,941,25)
    q.sheet_text('Geometric separation only; not an airflow or physical-performance simulation',24,995,20)
    q.sheet_save(OUT/'isolation-inspection.png')
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(OUT/'carrier-v28-intake-proposal.glb'))
    graph=bpy.context.evaluated_depsgraph_get()
    regions=[('bay'+str(slot),x,y,3.35) for slot,(x,y) in enumerate(
        ((-7.15,-5.2),(7.15,-5.2),(-3.6,7.8),(3.6,7.8)))]
    regions.append(('boarding',0,18,4.4))
    checks=[]
    for name,x,y,top in regions:
        blocked=[]
        for i in range(13):
            for j in range(13):
                start=Vector((x-1.5+i*.25,y-1.5+j*.25,-1))
                hit,point,_,_,obj,_=bpy.context.scene.ray_cast(graph,start,Vector((0,0,1)))
                if hit and point.z<top-.0001:
                    blocked.append({'part':obj.name,'point':list(point)})
        checks.append({'name':name,'rays':169,'blocked':blocked})
    protected=json.loads((OUT/'protected-before.json').read_text())
    unchanged=protected==protected_files()
    passed=unchanged and not any(check['blocked'] for check in checks)
    q.dump(OUT/'clearance-readback.json',{'passed':passed,'glbSha256':q.digest(OUT/'carrier-v28-intake-proposal.glb'),
        'regions':checks,'protectedAssetsUnchanged':unchanged,
        'inspectionVisibility':'Hull and duct covers hidden; remaining native parts not moved or scaled'})
    if not passed:
        raise RuntimeError('GLB clearances or protected inputs changed')


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    protected = protected_files()
    q.dump(OUT/'protected-before.json',protected)
    open_source(SOURCE)
    baseline = {'emitter':geometry_signature([obj for obj in meshes() if 'Emitter' in obj.name]),
                'anchors':{obj.name:list(obj.location) for obj in bpy.context.scene.objects
                           if obj.name.startswith(('ServiceBay_','BeamOrigin','BoardingProjection'))}}
    lighting()
    q.render(OUT/'before-front-belly.png',(4,-7,-3.6),61,(0,0,5.5),(1440,1080))
    q.render(OUT/'before-front.png',(0,-1,0),61,(0,0,5.5),(1280,1024))
    intake()
    bpy.context.scene['revision'] = REVISION
    bpy.context.scene['status'] = 'PROPOSAL_AWAITING_USER_APPROVAL'
    bpy.context.scene['source_blend'] = str(SOURCE)
    report = validation(baseline)
    q.dump(OUT/'validation.json',report)
    if not report['passed']:
        raise RuntimeError(report['errors'])
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'carrier-v28-intake-proposal.blend'))
    q.export(OUT/'carrier-v28-intake-proposal.glb')
    render_views()
    states=[]
    for frame in (1,40,80):
        bpy.context.scene.frame_set(frame)
        states.append({'frame':frame,'bounds':d.bounds(d.all_points(d.geometry()))})
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(OUT/'carrier-v28-intake-proposal.glb'))
    points = d.all_points(d.geometry())
    bounds = d.bounds(points)
    tree = kdtree.KDTree(len(points))
    for index,point in enumerate(points):
        tree.insert(point,index)
    tree.balance()
    mirror = max(tree.find(Vector((-p.x,p.y,p.z)))[2] for p in points)
    error = max(abs(a-b) for a,b in zip(bounds,report['boundsBlender']))
    sheets()
    final = protected_files()
    errors=[]
    if protected != final:
        errors.append('protected approved assets changed')
    if error > .0001 or mirror > .0001:
        errors.append('GLB geometry mismatch')
    if any(state['bounds']!=states[0]['bounds'] for state in states):
        errors.append('static bounds changed')
    images=[]
    for name in ('front-belly-hero','intake-detail','front','bottom','left','approval-mobile'):
        image=bpy.data.images.load(str(OUT/(name+'.png')),check_existing=False)
        pixels=list(image.pixels)
        nonblank=max(pixels[::4])-min(pixels[::4]) > .03
        images.append({'path':name+'.png','size':list(image.size),'nonblank':nonblank})
        if not nonblank:
            errors.append(name+' blank')
    q.dump(OUT/'verification.json',{'passed':not errors,'errors':errors,'native':report,
        'glbBoundsError':error,'glbMirrorError':mirror,'states':states,'images':images,
        'protectedAssetsUnchanged':protected==final,'protectedFiles':final,
        'blendSha256':q.digest(OUT/'carrier-v28-intake-proposal.blend'),
        'glbSha256':q.digest(OUT/'carrier-v28-intake-proposal.glb')})
    if errors:
        raise RuntimeError(errors)
    print('PROPOSAL_COMPLETE_APPROVED_ASSETS_UNCHANGED',flush=True)


if __name__ == '__main__':
    if '--inspection-only' in sys.argv:
        inspection()
    else:
        main()
        inspection()
