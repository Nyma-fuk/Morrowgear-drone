"""Build the isolated V31 ring-carrier proposal without publishing runtime assets."""
import sys
sys.dont_write_bytecode = True

import bpy
import bmesh
import hashlib
import importlib.util
import json
import math
from pathlib import Path

from mathutils import Vector, kdtree
from mathutils.bvhtree import BVHTree


ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'docs/design/carrier-v31-ring-proposal'
OUT.mkdir(parents=True, exist_ok=True)
BASE_PATH = ROOT / 'tools/drone-design/blender/build_carrier_v30_proposal.py'
RING_CENTER_Y = 0.8
RING_OUTER_RADIUS = 6.35
RING_INNER_RADIUS = 4.55
RING_BOTTOM_Z = 0.55
RING_TOP_Z = 6.35
EMITTER_COUNT = 16
VIEW_NAMES = ('front', 'rear', 'top', 'bottom', 'left', 'right', 'underside')


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


base = load_module('carrier_v30_base', BASE_PATH)
base.OUT = OUT


def remove_v30_center_emitter():
    prefixes = ('Belly_emitter_', 'Emitter_buried_', 'Emitter_hydraulic_',
                'Emitter_service_')
    for obj in list(bpy.context.scene.objects):
        if obj.type == 'MESH' and obj.name.startswith(prefixes):
            bpy.data.objects.remove(obj, do_unlink=True)


def cylinder_cutter(name, radius, depth, center):
    bpy.ops.mesh.primitive_cylinder_add(vertices=96, radius=radius, depth=depth,
                                       location=center)
    obj = bpy.context.object
    obj.name = name
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    return obj


def subtract_full_aperture(objects, cutter):
    """Join mirrored halves before cutting so the circular wall remains manifold."""
    cutter_points = [cutter.matrix_world @ vertex.co for vertex in cutter.data.vertices]
    cutter_low = [min(point[axis] for point in cutter_points) for axis in range(3)]
    cutter_high = [max(point[axis] for point in cutter_points) for axis in range(3)]
    for obj in objects:
        points = [obj.matrix_world @ vertex.co for vertex in obj.data.vertices]
        if not points or any(max(point[axis] for point in points) < cutter_low[axis] or
                             min(point[axis] for point in points) > cutter_high[axis]
                             for axis in range(3)):
            continue
        bpy.context.view_layer.objects.active = obj
        obj.select_set(True)
        was_mirrored = False
        for modifier in list(obj.modifiers):
            if modifier.type == 'MIRROR':
                was_mirrored = True
                bpy.ops.object.modifier_apply(modifier=modifier.name)
        modifier = obj.modifiers.new('V31_circular_aperture', 'BOOLEAN')
        modifier.operation = 'DIFFERENCE'
        modifier.solver = 'EXACT'
        modifier.object = cutter
        bpy.ops.object.modifier_apply(modifier=modifier.name)
        if was_mirrored:
            bm = bmesh.new()
            bm.from_mesh(obj.data)
            bmesh.ops.bisect_plane(
                bm, geom=list(bm.verts)+list(bm.edges)+list(bm.faces), dist=1e-6,
                plane_co=(0, 0, 0), plane_no=(1, 0, 0),
                clear_inner=True, clear_outer=False)
            bm.to_mesh(obj.data)
            bm.free()
            obj.data.update()
            mirror = obj.modifiers.new('Bilateral_X0', 'MIRROR')
            mirror.mirror_object = bpy.data.objects['Symmetry_X0']
            mirror.use_clip = True
            mirror.use_mirror_merge = True
        obj.select_set(False)
    bpy.data.objects.remove(cutter, do_unlink=True)


def annular_sector(name, center_angle, angular_width, inner_radius, outer_radius,
                   bottom_z, top_z, material):
    segments = 5
    angles = [center_angle-angular_width/2 + angular_width*i/segments
              for i in range(segments+1)]
    verts = []
    for z in (bottom_z, top_z):
        for radius in (inner_radius, outer_radius):
            verts.extend((max(0.0, radius*math.cos(a)),
                          RING_CENTER_Y+radius*math.sin(a), z) for a in angles)
    row = segments+1
    faces = []
    for i in range(segments):
        # Bottom, top, inner wall, outer wall.
        faces.extend(((i, row+i, row+i+1, i+1),
                      (2*row+i, 2*row+i+1, 3*row+i+1, 3*row+i),
                      (i, i+1, 2*row+i+1, 2*row+i),
                      (row+i, 3*row+i, 3*row+i+1, row+i+1)))
    faces.extend(((0, 2*row, 3*row, row),
                  (row-1, 2*row-1, 4*row-1, 3*row-1)))
    return base.mesh(name, verts, faces, material)


def beveled_annulus(name, profiles, material):
    count = 49
    verts = []
    for z, outer, inner in profiles:
        for radius in (outer, inner):
            for index in range(count):
                angle = -math.pi/2+math.pi*index/(count-1)
                verts.append((max(0.0, radius*math.cos(angle)),
                              RING_CENTER_Y+radius*math.sin(angle), z))
    faces = []
    layer = count*2
    for level in range(len(profiles)-1):
        start = level*layer
        next_start = (level+1)*layer
        for index in range(count-1):
            faces.extend(((start+index, start+index+1,
                           next_start+index+1, next_start+index),
                          (start+count+index+1, start+count+index,
                           next_start+count+index, next_start+count+index+1)))
    bottom = 0
    top = (len(profiles)-1)*layer
    for index in range(count-1):
        faces.extend(((bottom+index+1, bottom+index,
                       bottom+count+index, bottom+count+index+1),
                      (top+index, top+index+1,
                       top+count+index+1, top+count+index)))
    return base.mesh(name, verts, faces, material)


def top_surface(x, y, offset=.0):
    front, rear, unused_bottom, unused_top = base.station(x)
    u = max(0.0, min(1.0, (y-front)/(rear-front)))
    return base.skinpoint(x, u)+Vector((0, 0, offset))


def add_integrated_ring():
    ring = beveled_annulus('MiningRing_integrated_pressure_frame', (
        (RING_BOTTOM_Z, 6.08, 4.42), (.72, RING_OUTER_RADIUS, RING_INNER_RADIUS),
        (6.18, RING_OUTER_RADIUS, RING_INNER_RADIUS),
        (RING_TOP_Z, 6.08, 4.42)), 'armor')
    ring['component'] = 'central_mining_ring'
    ring['state'] = 'fixed'

    base.ring('MiningRing_lower_armor_lip', RING_CENTER_Y, 6.28, 4.43,
              .38, .22, 'edge')['component'] = 'central_mining_ring'

    # Sixteen shallow top bezels break up the annulus and expose the same emitter channels.
    for index in range(8):
        angle = -math.pi/2 + (index+.5)*math.pi/8
        bezel = annular_sector('MiningRing_upper_bezel_%02d' % (index+1),
                               angle, .335, 4.43, 6.17, 6.27, 6.37,
                               'panel' if index % 2 else 'armor')
        bezel['component'] = 'mining_ring_segmented_bezel'
        window = annular_sector('MiningRing_emitter_upper_window_%02d' % (index+1),
                                angle, .10, 4.60, 5.18, 6.36, 6.405, 'light')
        window['component'] = 'mining_emitter_channel_upper'
        window['logical_pair_count'] = 2

    # Eight positive-X recesses are mirrored about X=0, yielding 16 exact positions.
    for index in range(8):
        angle = -math.pi/2 + (index+.5)*math.pi/8
        bezel = annular_sector('MiningRing_emitter_recess_%02d' % (index+1),
                               angle, .20, 4.68, 5.72, .28, .53, 'dark')
        bezel['component'] = 'mining_emitter_recess'
        bezel['logical_pair_count'] = 2
        optic = annular_sector('MiningRing_emitter_optic_%02d' % (index+1),
                               angle, .13, 4.82, 5.57, .20, .29, 'light')
        optic['component'] = 'mining_emitter_optic'
        optic['logical_pair_count'] = 2

    # Broad armor feeders continue the hull surface into the ring on every quadrant.
    feeder_outlines = (
        ((.10, -8.2), (2.9, -7.1), (5.35, -3.7), (3.05, -4.72), (.10, -5.55)),
        ((5.55, -3.45), (12.8, -5.8), (14.8, -2.3), (6.28, -.25)),
        ((6.22, -.05), (15.8, -.9), (15.2, 3.1), (6.05, 2.7)),
        ((5.88, 3.0), (13.8, 5.1), (11.5, 8.9), (3.8, 5.9)),
    )
    for index, coords in enumerate(feeder_outlines):
        outline = [top_surface(x, y, .055) for x, y in coords]
        panel = base.slab('MiningRing_integrated_armor_feeder_%02d' % (index+1),
                          outline, 'panel' if index in (1, 3) else 'armor', .055)
        panel['component'] = 'ring_load_path'

    # Load paths visibly tie the ring into the flying hull instead of leaving a free donut.
    braces = [
        ((5.75, -2.05, 5.85), (10.5, -5.9, 5.72)),
        ((6.18, 0.15, 5.62), (13.2, -1.8, 5.48)),
        ((6.05, 2.65, 5.30), (14.8, 4.3, 5.18)),
        ((4.95, 5.00, 4.82), (11.8, 8.4, 4.78)),
    ]
    for a, b in braces:
        obj = base.rod('MiningRing_radial_load_rib', a, b, .18, 'armor')
        obj['component'] = 'ring_load_path'
    for angle in (-1.18, -.40, .40, 1.18):
        x = 5.86*math.cos(angle)
        y = RING_CENTER_Y+5.86*math.sin(angle)
        base.rod('MiningRing_lower_service_truss', (x, y, .66),
                 (x+3.6, y+1.1*math.sin(angle), 2.35), .10, 'edge')


def build():
    base.OUT = OUT
    base.STATIONS = [
        (0, -16, 14.0, 2.25, 6.35), (3.8, -14.7, 14.0, 2.22, 6.55),
        (7, -12.2, 13.6, 2.30, 6.45), (10, -10.4, 13.0, 2.42, 6.15),
        (14, -7.8, 12.0, 2.62, 5.78), (18, -5.2, 11.0, 2.88, 5.25),
        (21.5, -3.0, 9.8, 3.15, 4.75), (23.6, -1.2, 8.5, 3.34, 4.40),
        (25, .3, 7.2, 3.54, 4.05),
    ]
    base.build()
    remove_v30_center_emitter()

    # Cut a true vertical aperture through every intersecting inherited component.
    cutter = cylinder_cutter('TEMP_V31_central_aperture', RING_OUTER_RADIUS-.05,
                             20.0, (0, RING_CENTER_Y, 4.0))
    targets = [obj for obj in bpy.context.scene.objects if obj.type == 'MESH'
               and obj != cutter]
    subtract_full_aperture(targets, cutter)
    add_integrated_ring()

    origin = bpy.data.objects.get('beamOrigin')
    if origin:
        origin.location = (0, RING_CENTER_Y, -.10)
        origin['runtime_local_xyz_proposal'] = [0, -.10, -RING_CENTER_Y]
        origin['proposal_contract_note'] = 'V31 proposal only; runtime mapping requires approval'


def evaluated_geometry():
    depsgraph = bpy.context.evaluated_depsgraph_get()
    verts, faces, nonmanifold = [], [], {}
    for obj in bpy.context.scene.objects:
        if obj.type != 'MESH' or obj.hide_render:
            continue
        data = obj.evaluated_get(depsgraph).data
        offset = len(verts)
        verts.extend(obj.matrix_world @ vertex.co for vertex in data.vertices)
        faces.extend(tuple(offset+i for i in face.vertices) for face in data.polygons)
        bm = bmesh.new()
        bm.from_mesh(data)
        bad = sum(not edge.is_manifold for edge in bm.edges)
        if bad:
            nonmanifold[obj.name] = bad
        bm.free()
    return verts, faces, nonmanifold


def points():
    return evaluated_geometry()[0]


def validate(write=True):
    verts, faces, nonmanifold = evaluated_geometry()
    tree = kdtree.KDTree(len(verts))
    for index, point in enumerate(verts):
        tree.insert(point, index)
    tree.balance()
    symmetry_error = max(tree.find(Vector((-p.x, p.y, p.z)))[2] for p in verts)
    bounds = [[min(p[axis] for p in verts), max(p[axis] for p in verts)]
              for axis in range(3)]
    bvh = BVHTree.FromPolygons(verts, faces)

    aperture_samples = []
    for x, y in ((0, RING_CENTER_Y), (1.8, RING_CENTER_Y),
                 (-1.8, RING_CENTER_Y), (0, RING_CENTER_Y-1.8),
                 (0, RING_CENTER_Y+1.8)):
        hit = bvh.ray_cast(Vector((x, y, -2.0)), Vector((0, 0, 1)), 12.0)
        aperture_samples.append(hit[0] is None)

    bay_checks = []
    for x in (-15, -10, 10, 15):
        clear = []
        for dx in (-1.45, 0, 1.45):
            for z in (2.75, 3.55, 4.35):
                hit = bvh.ray_cast(Vector((x+dx, -18, z)), Vector((0, 1, 0)), 14.0)
                clear.append(hit[0] is None)
        bay_checks.append({'centerX': x, 'samples': len(clear),
                           'approachSamplesClear': all(clear)})

    intake_checks = []
    for x in (-3.4, 0, 3.4):
        for z in (2.45, 3.25):
            hit = bvh.ray_cast(Vector((x, -18, z)), Vector((0, 1, 0)), 4.6)
            intake_checks.append(hit[0] is None)

    emitter_recesses = [obj for obj in bpy.context.scene.objects
                        if obj.type == 'MESH' and
                        obj.name.startswith('MiningRing_emitter_recess_')]
    emitter_optics = [obj for obj in bpy.context.scene.objects
                      if obj.type == 'MESH' and
                      obj.name.startswith('MiningRing_emitter_optic_')]
    upper_windows = [obj for obj in bpy.context.scene.objects
                     if obj.type == 'MESH' and
                     obj.name.startswith('MiningRing_emitter_upper_window_')]
    mirror_modifiers = sum(mod.type == 'MIRROR' and mod.mirror_object and
                           mod.mirror_object.name == 'Symmetry_X0'
                           for obj in bpy.context.scene.objects for mod in obj.modifiers)
    logical_emitters = sum(int(obj.get('logical_pair_count', 0))
                           for obj in emitter_recesses)
    ring_objects = [obj for obj in bpy.context.scene.objects
                    if obj.type == 'MESH' and obj.name.startswith('MiningRing_')]
    intake_objects = [obj for obj in bpy.context.scene.objects
                      if obj.type == 'MESH' and obj.name.startswith('LowerFrontIntake_')]

    report = {
        'proposalOnly': True,
        'revision': 'V31_INTEGRATED_MINING_RING_01',
        'axes': {'front': '-Y', 'up': '+Z', 'mirror': 'X=0'},
        'bounds': bounds,
        'dimensions': [high-low for low, high in bounds],
        'symmetryMaxError': symmetry_error,
        'evaluatedVertices': len(verts),
        'polygons': len(faces),
        'nonManifoldEdgesByObject': nonmanifold,
        'mirrorModifierCount': mirror_modifiers,
        'centralAperture': {
            'center': [0, RING_CENTER_Y],
            'innerDiameter': RING_INNER_RADIUS*2,
            'outerDiameter': RING_OUTER_RADIUS*2,
            'verticalSamplesClear': all(aperture_samples),
            'samples': len(aperture_samples),
        },
        'miningRing': {
            'objectCount': len(ring_objects),
            'emitterRecessMeshPairs': len(emitter_recesses),
            'emitterOpticMeshPairs': len(emitter_optics),
            'upperEmitterWindowMeshPairs': len(upper_windows),
            'logicalEmitterCount': logical_emitters,
            'structurallyIntegrated': any(obj.name.startswith('MiningRing_radial_load_rib')
                                          for obj in ring_objects),
        },
        'serviceBays': {
            'count': 4,
            'centersX': [-15, -10, 10, 15],
            'clearEnvelope': list(base.BAY_SIZE),
            'approachChecks': bay_checks,
        },
        'lowerFrontIntake': {
            'exists': bool(intake_objects),
            'objectCount': len(intake_objects),
            'approachSamplesClear': all(intake_checks),
            'samples': len(intake_checks),
        },
        'rearNozzleCount': 8,
        'beamOriginProposal': [0, RING_CENTER_Y, -.1],
    }
    report['passed'] = (
        symmetry_error < 1e-5 and report['dimensions'][0] <= 50.001 and
        not nonmanifold and all(aperture_samples) and logical_emitters == EMITTER_COUNT and
        len(emitter_optics)*2 == EMITTER_COUNT and len(upper_windows)*2 == EMITTER_COUNT and
        report['miningRing']['structurallyIntegrated'] and
        all(item['approachSamplesClear'] for item in bay_checks) and
        all(intake_checks)
    )
    if write:
        (OUT/'validation.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    return report


def write_component_spec():
    spec = {
        'proposalOnly': True,
        'revision': 'V31_INTEGRATED_MINING_RING_01',
        'coordinateSystem': {'front': '-Y', 'up': '+Z', 'bilateralPlane': 'X=0'},
        'recognitionFeatures': [
            '50m low horizontal armored span',
            'structurally integrated hollow central mining aperture',
            '16 recessed mining emitters around the lower ring',
            'four recessed fore/underside drone service bays',
            'central lower-front propulsion intake and eight aft vector ducts',
        ],
        'components': [
            {'id': 'flying_hull', 'state': 'fixed', 'symmetry': 'exact bilateral',
             'connection': 'continuous spanwise monocoque'},
            {'id': 'mining_ring', 'state': 'fixed', 'symmetry': 'exact bilateral',
             'connection': 'intersects hull pressure frame and eight mirrored load paths',
             'innerDiameter': 9.1, 'outerDiameter': 12.7},
            {'id': 'mining_emitters', 'state': 'dark/ready/active',
             'count': 16, 'mount': 'recessed lower annulus'},
            {'id': 'service_bays', 'state': 'open/service', 'count': 4,
             'centersX': [-15, -10, 10, 15], 'clearEnvelope': [3.6, 4.4, 2.4]},
            {'id': 'lower_front_intake', 'state': 'open',
             'connection': 'converging propulsion duct with flow straighteners'},
            {'id': 'aft_vector_ducts', 'state': 'throttle/vector', 'count': 8},
        ],
        'states': {
            'transit': {'emitterOptics': 'dark', 'verticalBeam': 'absent'},
            'miningReady': {'emitterOptics': 'energized', 'verticalBeam': 'absent'},
            'miningActivePresentation': {
                'emitterOptics': 'energized', 'verticalBeam': 'visible witness only',
                'canonicalMeshContainsBeam': False,
            },
        },
        'runtimeStatus': 'not installed; approval required',
    }
    (OUT/'component-state-spec.json').write_text(json.dumps(spec, indent=2), encoding='utf-8')


def role_fit_witness():
    records = {}
    for role in base.ROLES:
        item = base.import_role(role, (10, -4.65, base.BAY_FLOOR_Z))
        dims = item['dimensions']
        clearance = item['clearance']
        records[role] = {
            'source': str(item['source'].relative_to(ROOT)),
            'sourceSha256': hashlib.sha256(item['source'].read_bytes()).hexdigest(),
            'dimensions': dims,
            'clearance': clearance,
            'fits': all(value >= -.0001 for value in clearance),
        }
        base.remove_loaded(item['objects'])

    loaded = []
    for role, x in zip(('field', 'scout', 'cargo', 'engineer'), (-15, -10, 10, 15)):
        loaded.append(base.import_role(role, (x, -4.65, base.BAY_FLOOR_Z)))
    base.render('bay-scale-witness', (2.3, -6, 1.15), target=(0, -3.0, 3.3),
                span=58, size=(1600, 1000))
    for item in loaded:
        base.remove_loaded(item['objects'])
    result = {
        'proposalOnly': True,
        'sourcesAreCanonicalV22Glb': True,
        'scale': 1.0,
        'pose': 'gear-stowed export, frame 40',
        'bayClearEnvelope': list(base.BAY_SIZE),
        'roles': records,
        'passed': all(item['fits'] for item in records.values()),
    }
    (OUT/'bay-role-fit.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    if not result['passed']:
        raise RuntimeError('One or more canonical role meshes do not fit V31 bays')
    return result


def add_active_beam():
    bpy.ops.mesh.primitive_cylinder_add(vertices=64, radius=3.65, depth=34,
                                       location=(0, RING_CENTER_Y, -16.7))
    beam = bpy.context.object
    beam.name = 'PRESENTATION_ONLY_vertical_mining_beam'
    beam.data.materials.append(base.M['beam'])
    beam['presentation_only'] = True
    return beam


def render_outputs():
    base.studio()
    base.render('hero', (1.35, -6.3, 2.2), target=(0, -.2, 3.45), span=58)
    axes = {
        'front': (0, -1, 0), 'rear': (0, 1, 0), 'top': (0, 0, 1),
        'bottom': (0, 0, -1), 'left': (-1, 0, 0), 'right': (1, 0, 0),
        'underside': (3, -5, -3),
    }
    for name, axis in axes.items():
        base.render(name, axis, target=(0, 0, 3.3), span=60)
    base.render('bottom-functional-closeup', (2.2, -3.7, -5.2),
                target=(0, -.4, 1.65), span=27, size=(1500, 1050))
    base.render('ring-functional-closeup', (1.8, -2.8, -4.2),
                target=(0, RING_CENTER_Y, 2.4), span=17, size=(1300, 1000))
    base.render('bay-intake-functional-closeup', (1.4, -6, -1.45),
                target=(0, -7.2, 2.65), span=36, size=(1500, 1000))
    beam = add_active_beam()
    base.render('mining-active-state', (3, -5, -1.2),
                target=(0, RING_CENTER_Y, -8), span=70, size=(1400, 1200))
    bpy.data.objects.remove(beam, do_unlink=True)


def create_sheets():
    sheet = load_module('carrier_sheet_helpers',
                        ROOT/'tools/drone-design/blender/build_supply_carrier_v24.py')
    sheet.sheet_start(1800, 1460)
    sheet.sheet_text('MORROWGEAR / CARRIER V31 RING / PROPOSAL ONLY', 35, 18, 31)
    names = ('hero',)+VIEW_NAMES
    for index, name in enumerate(names):
        x = 20+(index % 3)*595
        y = 80+(index // 3)*445
        sheet.sheet_text(name.upper(), x+10, y, 22)
        sheet.sheet_image(OUT/(name+'.png'), x, y+32, 575, 392)
    sheet.sheet_save(OUT/'contact-sheet.png')

    sheet.sheet_start(1800, 1060)
    base.japanese_sheet_text(sheet, 'V31 採掘リング機能確認 / 提案のみ・ランタイム未反映',
                             30, 18, 29)
    panels = (
        ('貫通採掘リング', '内径9.1m / 下部16基埋込エミッター', 'ring-functional-closeup'),
        ('下面機能配置', 'リング・荷重リブ・整備機器', 'bottom-functional-closeup'),
        ('ベイ・吸気系', '4整備ベイ / 独立した中央前方吸気口', 'bay-intake-functional-closeup'),
    )
    for index, (title, subtitle, name) in enumerate(panels):
        x = 20+index*595
        base.japanese_sheet_text(sheet, title, x+8, 75, 24)
        base.japanese_sheet_text(sheet, subtitle, x+8, 108, 17)
        sheet.sheet_image(OUT/(name+'.png'), x, 145, 575, 820)
    base.japanese_sheet_text(sheet, '状態: 航行 / 採掘待機 / 採掘稼働。ビームは提示専用で正本モデルに含みません。',
                             30, 1005, 18)
    sheet.sheet_save(OUT/'functional-sheet-ja.png')


def image_checks(protected_before, protected_paths):
    import numpy as np
    names = ('hero',)+VIEW_NAMES+('bottom-functional-closeup',
            'ring-functional-closeup', 'bay-intake-functional-closeup',
            'mining-active-state', 'bay-scale-witness')
    images = {}
    for name in names:
        image = bpy.data.images.load(str(OUT/(name+'.png')), check_existing=False)
        pixels = np.asarray(image.pixels[:], dtype=np.float32).reshape(
            image.size[1], image.size[0], 4)
        border = np.concatenate((pixels[:, :12, :3], pixels[:, -12:, :3]), axis=1)
        background = np.median(border, axis=1)[:, None, :]
        yy, xx = np.where(np.max(np.abs(pixels[:, :, :3]-background), axis=2) > .018)
        images[name] = {
            'size': list(image.size),
            'visiblePixels': len(xx),
            'notClipped': bool(len(xx) and xx.min() > 0 and yy.min() > 0 and
                               xx.max() < image.size[0]-1 and yy.max() < image.size[1]-1),
        }
        bpy.data.images.remove(image)
    protected_after = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                       for path in protected_paths}
    strict = {'hero', *VIEW_NAMES}
    result = {
        'images': images,
        'protectedFileCount': len(protected_before),
        'protectedUnchanged': protected_before == protected_after,
        'changedProtectedFiles': [path for path in protected_before
                                  if protected_before[path] != protected_after[path]],
    }
    result['passed'] = (all(item['visiblePixels'] > 1000 for item in images.values()) and
                        all(images[name]['notClipped'] for name in strict) and
                        result['protectedUnchanged'])
    (OUT/'output-checks.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    if not result['passed']:
        raise RuntimeError('V31 image or protected-file checks failed')


def protected_files():
    paths = list((ROOT/'src/main/resources').rglob('*'))
    for revision in ('carrier-v28-intake-proposal', 'carrier-v29-proposal',
                     'carrier-v30-proposal'):
        paths.extend(ROOT/'docs/design'/revision/(revision+suffix)
                     for suffix in ('.blend', '.glb'))
    return [path for path in paths if path.is_file()]


def write_artifacts():
    files = [path for path in OUT.iterdir()
             if path.suffix in ('.blend', '.glb', '.png', '.json', '.md')]
    data = {path.name: {'bytes': path.stat().st_size,
                        'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
            for path in files}
    (OUT/'artifacts.json').write_text(json.dumps(data, indent=2), encoding='utf-8')


def main():
    protected = protected_files()
    before = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
              for path in protected}
    build()
    report = validate()
    print('GEOMETRY_VALIDATION', json.dumps(report), flush=True)
    if not report['passed']:
        raise RuntimeError('V31 geometry validation failed')
    write_component_spec()
    render_outputs()

    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'carrier-v31-ring-proposal.blend'))
    bpy.ops.object.select_all(action='DESELECT')
    for obj in bpy.context.scene.objects:
        if obj.type in ('MESH', 'EMPTY') and not obj.name.startswith('PRESENTATION_ONLY_'):
            obj.select_set(True)
    bpy.ops.export_scene.gltf(filepath=str(OUT/'carrier-v31-ring-proposal.glb'),
        export_format='GLB', use_selection=True, export_apply=True,
        export_animations=False, export_cameras=False, export_lights=False)

    role_fit_witness()
    create_sheets()
    image_checks(before, protected)
    write_artifacts()


def verify_export():
    bpy.ops.wm.open_mainfile(filepath=str(OUT/'carrier-v31-ring-proposal.blend'))
    native_report = validate()
    original = points()
    tree = kdtree.KDTree(len(original))
    for index, point in enumerate(original):
        tree.insert(point, index)
    tree.balance()
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(OUT/'carrier-v31-ring-proposal.glb'))
    imported = points()
    bounds = [[min(point[axis] for point in imported), max(point[axis] for point in imported)]
              for axis in range(3)]
    nearest_error = max(tree.find(point)[2] for point in imported)
    bounds_error = max(abs(bounds[axis][side]-native_report['bounds'][axis][side])
                       for axis in range(3) for side in range(2))
    result = {
        'nativeGeometryPassed': native_report['passed'],
        'glbImported': True,
        'importedVertices': len(imported),
        'maxNearestNativeVertexError': nearest_error,
        'boundsMaxError': bounds_error,
        'passed': native_report['passed'] and nearest_error < 1e-4 and bounds_error < 1e-4,
    }
    (OUT/'glb-readback.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print('GLB_READBACK', json.dumps(result), flush=True)
    if not result['passed']:
        raise RuntimeError('V31 GLB readback failed')


if __name__ == '__main__':
    if '--verify' in sys.argv:
        verify_export()
    else:
        main()
