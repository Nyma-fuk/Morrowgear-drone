"""Approved V21 geometry, manufactured surface detail, block-scale dock and icon masters.

Design-stage assets only. One Blender unit in the exported V22 files is one block.
"""
import argparse
import hashlib
import importlib.util
import json
import math
import sys
from pathlib import Path

import bpy
import bmesh
from mathutils import Matrix, Vector, kdtree

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SOURCE = ROOT / 'docs/design/integrated-family-v21'
OUT = ROOT / 'docs/design/detail-scale-v22'
spec = importlib.util.spec_from_file_location('integrated_v21', HERE / 'build_integrated_family_v21.py')
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
v = base.v
FRAMES = (1, 20, 30, 40, 50, 60, 70, 80, 100, 110, 120)
DECK = .30


def geometry(objects=None):
    graph = bpy.context.evaluated_depsgraph_get()
    result = {}
    for obj in objects if objects is not None else bpy.context.scene.objects:
        if obj.type not in ('MESH', 'CURVE') or obj.hide_render:
            continue
        ev = obj.evaluated_get(graph)
        mesh = ev.to_mesh()
        result[obj.name] = [ev.matrix_world @ p.co for p in mesh.vertices]
        ev.to_mesh_clear()
    return result


def bounds(points):
    return [min(p[i] for p in points) for i in range(3)] + [max(p[i] for p in points) for i in range(3)]


def all_points(parts):
    return [p for points in parts.values() for p in points]


def aim(obj, target):
    obj.rotation_euler = (Vector(target) - obj.location).to_track_quat('-Z', 'Y').to_euler()


def studio():
    scene = bpy.context.scene
    for obj in list(scene.objects):
        if obj.type in ('LIGHT', 'CAMERA'):
            bpy.data.objects.remove(obj, do_unlink=True)
    scene.render.engine = 'CYCLES'
    scene.cycles.samples = 32
    scene.cycles.use_denoising = True
    scene.render.image_settings.file_format = 'PNG'
    scene.render.image_settings.color_mode = 'RGBA'
    scene.render.film_transparent = True
    scene.render.resolution_percentage = 100
    scene.world = bpy.data.worlds.new('V22_StudioWorld')
    scene.world.use_nodes = True
    scene.world.node_tree.nodes['Background'].inputs[0].default_value = (.17, .20, .23, 1)
    scene.world.node_tree.nodes['Background'].inputs[1].default_value = .45
    scene.view_settings.view_transform = 'AgX'
    for name, pos, energy, size in (
        ('Key', (2, -4, 6), 1100, 5), ('Fill', (-4, -2, 2), 700, 4),
        ('Rim', (2, 5, 4), 1300, 3), ('Belly', (0, -3, -3), 380, 3),
    ):
        bpy.ops.object.light_add(type='AREA', location=pos)
        obj = bpy.context.object
        obj.name = 'V22_Studio' + name
        obj.data.energy = energy
        obj.data.shape = 'DISK'
        obj.data.size = size
        aim(obj, (0, 0, .4))
    bpy.ops.object.camera_add(location=(4, -6, 3))
    scene.camera = bpy.context.object
    scene.camera.name = 'V22_ReviewCamera'
    scene.camera.data.type = 'ORTHO'


def render(path, axis, span, target=(0, 0, .35), size=(1280, 800), frame=40):
    scene = bpy.context.scene
    scene.frame_set(frame)
    scene.camera.data.ortho_scale = span
    scene.camera.location = Vector(target) + Vector(axis)
    aim(scene.camera, target)
    scene.render.resolution_x, scene.render.resolution_y = size
    scene.render.filepath = str(path)
    bpy.ops.render.render(write_still=True)


def flush_line(name, coordinates, material='dark', radius=.006):
    return v.line('V22_' + name, [(x, y, v.skin(x, y) + .009) for x, y in coordinates],
                  material, radius, True, True)


def detail_airframe(root):
    v.ADDED.clear()
    v.materials()
    v.MIRROR = v.empty('V22_DetailMirror')
    v.HULL = next(o for o in bpy.context.scene.objects if o.name.startswith('H01_'))
    base.sync_surface()
    # Service features follow the real skin and avoid the intake, gear and weapon openings.
    for name, poly in (
        ('ForwardAccess', [(.72, -2.15), (.99, -1.95), (1.17, -1.68), (.95, -1.67), (.69, -1.94)]),
        ('OutboardAccess', [(3.12, .88), (3.39, 1.04), (3.58, 1.41), (3.33, 1.31)]),
        ('AvionicsService', [(.12, -2.57), (.37, -2.45), (.48, -2.21), (.12, -2.32)]),
    ):
        v.panel('V22_' + name + 'Skin', poly, 'armor', .004, True)
        flush_line(name + 'Seal', poly)
        cx = sum(p[0] for p in poly) / len(poly)
        cy = sum(p[1] for p in poly) / len(poly)
        v.cylinder('V22_' + name + 'QuarterTurnLock', (cx, cy, v.skin(cx, cy) + .018), .022, .012, 'edge', True)
        v.line('V22_' + name + 'LockSlot', [(cx - .013, cy, v.skin(cx, cy) + .026),
                                          (cx + .013, cy, v.skin(cx, cy) + .026)], 'dark', .004, True)
    for radius, material, thickness in ((1.064, 'dark', .008), (1.098, 'edge', .004)):
        ring = [(1.91 + radius * math.cos(i * math.tau / 96), .018267 + radius * math.sin(i * math.tau / 96))
                for i in range(96)]
        flush_line('FanServiceRim', ring, material, thickness)
    for i in range(12):
        angle = (i + .5) * math.tau / 12
        x, y = 1.91 + 1.085 * math.cos(angle), .018267 + 1.085 * math.sin(angle)
        z = v.skin(x, y)
        v.cylinder('V22_FanCaptiveFastener', (x, y, z + .017), .021, .012, 'edge', True)
        v.line('V22_FanFastenerSlot', [(x - .01, y, z + .026), (x + .01, y, z + .026)], 'dark', .003, True)
    for x, y in ((.89, -1.94), (3.35, 1.15)):
        v.panel('V22_ReleaseWitness', [(x, y), (x + .09, y + .04), (x + .085, y + .06), (x - .005, y + .02)], 'amber', .017)
    # Bake only new static mirrors. Existing animated port mechanisms remain untouched.
    for obj in v.ADDED:
        if obj.type == 'CURVE':
            bpy.ops.object.select_all(action='DESELECT')
            obj.select_set(True)
            bpy.context.view_layer.objects.active = obj
            bpy.ops.object.convert(target='MESH')
        for mod in list(obj.modifiers):
            if mod.type == 'MIRROR':
                bpy.context.view_layer.objects.active = obj
                bpy.ops.object.modifier_apply(modifier=mod.name)
        obj.parent = root
    bpy.data.objects.remove(v.MIRROR, do_unlink=True)
    return len(v.ADDED)


def normalize_and_check(root, role):
    scene = bpy.context.scene
    sampled = []
    for frame in FRAMES:
        scene.frame_set(frame)
        sampled.extend(all_points(geometry()))
    envelope = bounds(sampled)
    scale = 3.0 / max(envelope[3] - envelope[0], envelope[4] - envelope[1])
    scene.frame_set(1)
    parts = geometry()
    feet = [p for name, points in parts.items() if name.startswith(('GEAR_ContactPad', 'GEAR_SoleTread')) for p in points]
    contact = min(p.z for p in feet)
    root.scale = (scale,) * 3
    root.location = (0, -(envelope[1] + envelope[4]) * scale / 2, -contact * scale)
    errors, samples = [], []
    for frame in FRAMES:
        scene.frame_set(frame)
        parts = geometry()
        points = all_points(parts)
        bb = bounds(points)
        structural = [p for name, ps in parts.items() if not name.startswith('FX_') for p in ps]
        tree = kdtree.KDTree(len(structural))
        for i, p in enumerate(structural):
            tree.insert(p, i)
        tree.balance()
        symmetry = max(tree.find(Vector((-p.x, p.y, p.z)))[2] for p in structural)
        if symmetry > 1e-4:
            errors.append({'frame': frame, 'symmetry': symmetry})
        if bb[3] - bb[0] > 3.0001 or bb[4] - bb[1] > 3.0001:
            errors.append({'frame': frame, 'oversize': bb})
        item = {'frame': frame, 'bounds': bb, 'symmetryError': symmetry}
        if frame == 1:
            body = [p for n, ps in parts.items() if not n.startswith('GEAR_') for p in ps]
            item['nonGearDeckClearance'] = min(p.z for p in body)
            item['contactZ'] = min(p.z for n, ps in parts.items() if n.startswith('GEAR_') for p in ps)
            if item['nonGearDeckClearance'] < .09 or abs(item['contactZ']) > 1e-4:
                errors.append({'landingClearance': item})
        samples.append(item)
    # All animation frames are checked for the horizontal footprint with conservative evaluated boxes.
    sweep = []
    graph = bpy.context.evaluated_depsgraph_get()
    for frame in range(1, 121):
        scene.frame_set(frame)
        points = [o.evaluated_get(graph).matrix_world @ Vector(p)
                  for o in scene.objects if o.type in ('MESH', 'CURVE') and not o.hide_render for p in o.evaluated_get(graph).bound_box]
        bb = bounds(points)
        if bb[0] < -1.5001 or bb[3] > 1.5001 or bb[1] < -1.5001 or bb[4] > 1.5001:
            # Curves with a rotated local box can overestimate the true footprint.
            exact = bounds(all_points(geometry()))
            if exact[0] < -1.5001 or exact[3] > 1.5001 or exact[1] < -1.5001 or exact[4] > 1.5001:
                sweep.append({'frame': frame, 'bounds': exact})
    if sweep:
        errors.append({'conservativeSweep': sweep})
    report = {'role': role, 'passed': not errors, 'errors': errors, 'blocksPerSourceUnit': scale,
              'rootTranslation': list(root.location), 'frameSamples': samples, 'sweepFrames': 120,
              'horizontalLimit': [3, 3], 'origin': 'horizontal center / landed sole contact plane',
              'symmetryExcludes': 'FX_ animated scan dot / energy focus only',
              'limitations': ['Not a full swept-volume collision proof', 'No in-game validation or aerodynamics']}
    print('V22_GEOMETRY', role, json.dumps(report), flush=True)
    if errors:
        raise RuntimeError(errors)
    return report


def save_export(folder, basename):
    scene = bpy.context.scene
    scene.frame_set(40)
    scene['asset_status'] = 'V22_DESIGN_STAGE_NOT_INSTALLED'
    scene.unit_settings.system = 'METRIC'
    scene.unit_settings.scale_length = 1
    bpy.ops.wm.save_as_mainfile(filepath=str(folder / (basename + '.blend')))
    bpy.ops.object.select_all(action='DESELECT')
    for obj in scene.objects:
        if obj.type in ('MESH', 'CURVE', 'EMPTY'):
            obj.select_set(True)
    bpy.ops.export_scene.gltf(filepath=str(folder / (basename + '.glb')), export_format='GLB', use_selection=True,
                             export_apply=True, export_animations=True, export_animation_mode='SCENE',
                             export_anim_scene_split_object=False, export_frame_range=True, export_force_sampling=True)


def aircraft(role, quick=False):
    source = SOURCE / role / 'airframe.blend'
    bpy.ops.wm.open_mainfile(filepath=str(source))
    root = bpy.data.objects['V21_AircraftRoot']
    root.name = 'V22_AircraftRoot'
    root.scale = (1,) * 3
    bpy.context.scene.frame_set(40)
    bpy.context.view_layer.update()
    count = detail_airframe(root)
    report = normalize_and_check(root, role)
    report['sourceSha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
    report['newDetailParts'] = count
    folder = OUT / role
    folder.mkdir(parents=True, exist_ok=True)
    (folder / 'validation.json').write_text(json.dumps(report, indent=2))
    studio()
    views = [('hero', (4, -6, 3.1), 40), ('active', (4, -6, -3), 80), ('landed', (4, -6, 3), 1),
             ('front', (0, -8, 0), 1), ('rear', (0, 8, 0), 1), ('top', (0, 0, 8), 40),
             ('bottom', (0, 0, -8), 40), ('left', (-8, 0, 0), 1), ('right', (8, 0, 0), 1)]
    for name, axis, frame in views[:3] if quick else views:
        render(folder / (name + '.png'), axis, 4.4, frame=frame)
    render(folder / 'detail.png', (2, -4, 5), 2.0, target=(.35, -.23, .5))
    render(folder / 'icon-master.png', (4, -6, 4), 3.65, size=(512, 512))
    render(folder / 'silhouette-master.png', (0, 0, 8), 3.4, size=(512, 512))
    save_export(folder, 'airframe')


def chamfer_box(name, width, depth, height, chamfer, z, material):
    x, y, c = width / 2, depth / 2, chamfer
    perimeter = [(-x+c, -y), (x-c, -y), (x, -y+c), (x, y-c), (x-c, y), (-x+c, y), (-x, y-c), (-x, -y+c)]
    verts = [(px, py, pz) for pz in (z, z+height) for px, py in perimeter]
    faces = [tuple(reversed(range(8))), tuple(range(8, 16))] + [(i, (i+1)%8, (i+1)%8+8, i+8) for i in range(8)]
    obj = v.mesh(name, verts, faces, material)
    bevel = obj.modifiers.new('EdgeRadius', 'BEVEL')
    bevel.width = .012
    bevel.segments = 3
    obj.modifiers.new('WeightedNormals', 'WEIGHTED_NORMAL')
    return obj


def mirror_static_dock(root):
    bpy.context.view_layer.update()
    graph = bpy.context.evaluated_depsgraph_get()
    for obj in list(bpy.context.scene.objects):
        if obj.type not in ('MESH', 'CURVE'):
            continue
        ev = obj.evaluated_get(graph)
        data = bpy.data.meshes.new_from_object(ev, depsgraph=graph)
        data.transform(ev.matrix_world)
        name = obj.name
        bpy.data.objects.remove(obj, do_unlink=True)
        bm = bmesh.new()
        bm.from_mesh(data)
        bmesh.ops.bisect_plane(bm, geom=list(bm.verts)+list(bm.edges)+list(bm.faces),
                              plane_co=(0,0,0), plane_no=(1,0,0), dist=1e-6, clear_inner=True)
        for p in bm.verts:
            if abs(p.co.x)<1e-5:
                p.co.x=0
        bm.to_mesh(data)
        bm.free()
        if not data.vertices:
            bpy.data.meshes.remove(data)
            continue
        new = bpy.data.objects.new(name, data)
        bpy.context.collection.objects.link(new)
        new.parent = root
        mod = new.modifiers.new('DockBilateralSymmetry', 'MIRROR')
        mod.use_clip = True
        mod.merge_threshold = 1e-5
        bpy.context.view_layer.objects.active = new
        bpy.ops.object.modifier_apply(modifier=mod.name)


def dock():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    v.ADDED.clear()
    v.materials()
    root = v.empty('V22_DockRoot')
    chamfer_box('DockBase', 5, 5, .12, .36, 0, 'dark')
    chamfer_box('DockPerimeter', 4.94, 4.94, .10, .36, .12, 'edge')
    chamfer_box('DockUpperFrame', 4.84, 4.84, .07, .35, .22, 'armor')
    chamfer_box('DockLandingDeck', 3.38, 3.38, .06, .23, DECK-.06, 'dark')
    chamfer_box('DockCentralContact', 1.15, 1.15, .009, .22, DECK, 'armor')
    for sign in (-1, 1):
        v.line('DockCenterCross', [(-.30, sign*-.30, DECK+.014), (.30, sign*.30, DECK+.014)], 'cyan', .024)
    for quadrant in range(4):
        angle = quadrant * math.pi / 2
        created = len(v.ADDED)
        v.box('DockEdgeCassette', (0, -2.03, .31), (2.60, .57, .17), 'armor', bevel=.07)
        v.box('DockEdgeLensRecess', (0, -2.332, .31), (1.95, .012, .042), 'dark', bevel=.005)
        v.box('DockEdgeGuide', (0, -2.344, .314), (1.82, .014, .018), 'cyan', bevel=.006)
        v.box('DockUpperGuide', (0, -2.07, .404), (1.82, .020, .010), 'cyan', bevel=.005)
        for x in (-.65, .65):
            v.box('DockCassetteSeam', (x, -2.03, .400), (.009, .43, .006), 'dark', bevel=.001)
        for j in range(11):
            v.box('DockHeatExchanger', (-.60+j*.12, -1.94, .400), (.058, .10, .007), 'dark', bevel=.004)
        v.box('DockCornerShoulder', (-2.00, -2.00, .305), (.58, .58, .19), 'armor', bevel=.07)
        for j in range(7):
            v.box('DockCoolingSlot', (-2.15+j*.05, -2.03, .406), (.018, .27, .008), 'dark', bevel=.003)
        for x in (-.92, .92):
            v.cylinder('DockPanelCaptiveBolt', (x, -1.85, .402), .026, .010, 'edge')
        v.box('DockBatteryAccess', (-1.65, -1.50, .32), (.35, .22, .06), 'dark', bevel=.02)
        v.box('DockBatteryLatch', (-1.65, -1.52, .355), (.12, .035, .012), 'amber', bevel=.003)
        v.box('DockServiceWell', (-1.64, -1.52, .310), (.45, .47, .025), 'dark', bevel=.03)
        for dx in (-.14, .14):
            v.box('DockActuatorRail', (-1.64+dx, -1.52, .355), (.048, .39, .040), 'edge', bevel=.008)
        v.cylinder('DockChargeCoupler', (-1.64, -1.52, .365), .045, .27, 'edge', axis=(0,1,0))
        for y in (-1.68, -1.37):
            v.box('DockContactInsulator', (-1.64, y, .370), (.16, .065, .055), 'amber', bevel=.012)
        for dx in (-.21, .21):
            v.line('DockRecessedCable', [(-1.64+dx,-1.73,.334),(-1.64+dx,-1.45,.334),
                                       (-1.64+dx*.6,-1.33,.334)], 'rubber', .013)
        for x in (-1.2, -.9, -.6, .6, .9, 1.2):
            v.cylinder('DockRimBolt', (x, -2.40, .293), .018, .009, 'dark')
        v.line('DockDeckPanelJoint', [(-1.34,-1.36,.303),(1.34,-1.36,.303),(1.51,-1.19,.303)], 'edge', .003)
        for x in (-1.25, 1.25):
            v.line('DockLandingWitness', [(x,-.91,.304),(x,-1.14,.304),(x*.86,-1.14,.304)], 'amber', .009)
        pivot = v.empty('DockQuarter', (0, 0, 0))
        for obj in v.ADDED[created:]:
            obj.parent = pivot
        pivot.rotation_euler.z = angle
        pivot.parent = root
    for x in (-.45, .45):
        for y in (-.55, .55):
            v.box('DockGearContact', (x, y, DECK+.006), (.30, .60, .012), 'edge', bevel=.02)
            for k in (-1, 0, 1):
                v.box('DockContactElectrode', (x+k*.047, y, DECK+.014), (.015, .49, .003), 'amber', bevel=0)
    for obj in v.ADDED:
        if obj.parent is None:
            obj.parent = root
    mirror_static_dock(root)
    studio()
    folder = OUT / 'dock'
    folder.mkdir(parents=True, exist_ok=True)
    render(folder / 'hero.png', (6, -8, 6), 7.8, target=(0, 0, .15))
    render(folder / 'top.png', (0, 0, 8), 6.0, target=(0, 0, .15), size=(1000, 1000))
    render(folder / 'front.png', (0, -8, 0), 6.0, target=(0, 0, .15))
    render(folder / 'icon-master.png', (6, -8, 6), 6.4, target=(0, 0, .15), size=(512, 512))
    points = all_points(geometry())
    tree = kdtree.KDTree(len(points))
    for i, p in enumerate(points):
        tree.insert(p, i)
    tree.balance()
    symmetry = max(tree.find(Vector((-p.x,p.y,p.z)))[2] for p in points)
    report = {'bounds': bounds(points), 'symmetryError': symmetry, 'landingDeckZ': DECK, 'footprintBlocks': [5, 5],
              'recommendedPitchBlocks': 7, 'continuousLandingDeckHalfWidth': 1.69,
              'cornerColumns': 0, 'status': 'design only / not installed'}
    (folder / 'validation.json').write_text(json.dumps(report, indent=2))
    if symmetry>1e-5:
        raise RuntimeError(report)
    save_export(folder, 'dock')


def fit_dock():
    reports = {}
    for role in v.ROLES:
        bpy.ops.wm.open_mainfile(filepath=str(OUT / 'dock/dock.blend'))
        with bpy.data.libraries.load(str(OUT / role / 'airframe.blend'), link=False) as (src, dst):
            dst.objects = [name for name in src.objects if not name.startswith(('V22_Studio', 'V22_Review'))]
        for obj in dst.objects:
            if obj is not None:
                bpy.context.collection.objects.link(obj)
        root = bpy.data.objects['V22_AircraftRoot']
        root.location.z += DECK + .0155
        bpy.context.scene.frame_set(1)
        objs = [root, *root.children_recursive]
        parts = geometry(objs)
        feet = [p for n, ps in parts.items() if n.startswith(('GEAR_ContactPad', 'GEAR_SoleTread')) for p in ps]
        bb = bounds(all_points(parts))
        report = {'landedBounds': bb, 'soleDeckGap': min(p.z for p in feet) - (DECK+.0155),
                  'feetOnContinuousDeck': all(abs(p.x)<1.69 and abs(p.y)<1.69 for p in feet),
                  'feetInsideContactPads': all(abs(abs(p.x)-.45)<.15 and abs(abs(p.y)-.55)<.30 for p in feet),
                  'footBounds': bounds(feet),
                  'aircraftInside5x5': bb[0]>-2.5 and bb[3]<2.5 and bb[1]>-2.5 and bb[4]<2.5,
                  'minimumSoleZForSideEntry': .54, 'approachCeilingAtRest': bb[5]}
        reports[role] = report
        render(OUT / 'dock' / (role+'-landed.png'), (6, -8, 6), 7.8, target=(0, 0, .4), frame=1)
        if role == 'cargo':
            render(OUT / 'dock/scale-top.png', (0, 0, 9), 7.0, target=(0, 0, .4), size=(1200, 1200), frame=1)
            render(OUT / 'dock/scale-side.png', (0, -9, 0), 7.0, target=(0, 0, .6), size=(1400, 500), frame=1)
    passed = all(r['feetOnContinuousDeck'] and r['feetInsideContactPads'] and r['aircraftInside5x5'] and abs(r['soleDeckGap'])<1e-4 for r in reports.values())
    (OUT / 'dock/fit-checks.json').write_text(json.dumps({'passed': passed, 'roles': reports}, indent=2))
    if not passed:
        raise RuntimeError(reports)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--role', choices=v.ROLES)
    parser.add_argument('--quick', action='store_true')
    parser.add_argument('--dock-only', action='store_true')
    parser.add_argument('--fit-only', action='store_true')
    args = parser.parse_args(sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else [])
    OUT.mkdir(parents=True, exist_ok=True)
    if args.fit_only:
        fit_dock()
        return
    if not args.dock_only:
        for role in ([args.role] if args.role else v.ROLES):
            aircraft(role, args.quick)
    if not args.role:
        dock()
        fit_dock()


if __name__ == '__main__':
    main()
