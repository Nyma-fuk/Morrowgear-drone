"""Export the approved Blender masters, not their preview images, to MGM4.

MGM4 stores one shared topology with flight / landed / deployed positions,
UVs, explicit luminous surfaces and rotor membership. UV color is baked from
the source materials; Minecraft supplies its own world lighting.
"""
import argparse
import json
import math
from pathlib import Path
import struct
import sys

import bpy
from mathutils import Vector


def mc(v):
    return (v.x, v.z, -v.y)


def srgb(v):
    return round(255 * (12.92 * v if v <= .0031308 else 1.055 * v ** (1 / 2.4) - .055))


def material_signal(mat):
    if not mat or not mat.use_nodes:
        return (255, 255, 255, 255), 0
    node = next((n for n in mat.node_tree.nodes if n.type == 'BSDF_PRINCIPLED'), None)
    if not node:
        return (255, 255, 255, 255), 0
    strength = node.inputs.get('Emission Strength')
    luminous = strength and strength.default_value > .1
    if luminous and node.inputs['Emission Color'].is_linked:
        return (255, 255, 255, 255), 2
    color = node.inputs['Emission Color' if luminous else 'Base Color'].default_value
    return (255, *(max(0, min(255, srgb(c))) for c in color[:3])), int(bool(luminous))


AIRCRAFT_NEAR_LARGE_RATIO = .010
AIRCRAFT_NEAR_PART_RATIO = .060
AIRCRAFT_LOD_LARGE_RATIO = .004
AIRCRAFT_LOD_PART_RATIO = .020
# Blender's collapse modifier can move a bevel vertex a few source millimetres
# while preserving the rendered silhouette. The final MGM envelope is audited
# separately in Minecraft units, so do not fall back to the 60k-triangle source
# merely because one internal bevel crossed the old 1mm threshold.
SIMPLIFIED_ENVELOPE_TOLERANCE = .05


def export(source, destination, aircraft=False, lod=False):
    bpy.ops.wm.open_mainfile(filepath=str(source.resolve()))
    scene = bpy.context.scene
    scene.frame_set(40 if aircraft else 1)
    objects = [o for o in scene.objects if o.type in {'MESH', 'CURVE'}
               and not o.hide_render and not any(s in o.name.lower() for s in ('floor', 'studio', 'backdrop'))]
    if lod:
        objects = [o for o in objects if max(o.dimensions) > .13 or o.name.startswith('GEAR_ContactPad')
                   or any(material_signal(m)[1] for m in o.data.materials)]
    # Parent matrices, including mirrored mechanisms, are sampled before flattening.
    matrices = {}
    for frame in ((40, 1, 80) if aircraft else (1, 1, 40)):
        scene.frame_set(frame)
        matrices.setdefault(frame, {o.name: o.matrix_world.copy() for o in objects})
    frames = (40, 1, 80) if aircraft else (1, 1, 40)
    scene.frame_set(frames[0])
    axes = [o for o in scene.objects if o.name.startswith('RotorAxis.')]
    rotor_centers = sorted([mc(o.matrix_world.translation) for o in axes])
    if not rotor_centers:
        rotor_centers = [(0., 0., 0.), (0., 0., 0.)]
    vertices, poses, faces, material_indices, groups = [], [], [], [], []
    materials = []
    source_uvs = []
    part_bounds = {}
    material_map = {}
    for obj in sorted(objects, key=lambda o: o.name):
        graph = bpy.context.evaluated_depsgraph_get()
        original = obj.evaluated_get(graph)
        original_mesh = original.to_mesh()
        source_bounds = [(min(v.co[i] for v in original_mesh.vertices), max(v.co[i] for v in original_mesh.vertices)) for i in range(3)] if original_mesh.vertices else []
        original.to_mesh_clear()
        mod = None
        if (obj.type == 'MESH' and len(obj.data.polygons) > 2
                and not obj.name.startswith(('GEAR_ContactPad', 'GEAR_SoleTread'))):
            mod = obj.modifiers.new('Runtime bounded geometry', 'DECIMATE')
            mod.ratio = (AIRCRAFT_LOD_LARGE_RATIO if len(obj.data.polygons) > 20000
                         else AIRCRAFT_LOD_PART_RATIO) if lod else (
                AIRCRAFT_NEAR_LARGE_RATIO if len(obj.data.polygons) > 20000
                else AIRCRAFT_NEAR_PART_RATIO)
            mod.use_collapse_triangulate = True
        evaluated = obj.evaluated_get(bpy.context.evaluated_depsgraph_get())
        mesh = evaluated.to_mesh()
        # Collapse can shoot vertices outside thin mirrored housings. Reject that simplification.
        if lod and mod and source_bounds and any(v.co[i] < source_bounds[i][0] - SIMPLIFIED_ENVELOPE_TOLERANCE
                or v.co[i] > source_bounds[i][1] + SIMPLIFIED_ENVELOPE_TOLERANCE
                for v in mesh.vertices for i in range(3)):
            evaluated.to_mesh_clear()
            mod.ratio = AIRCRAFT_NEAR_LARGE_RATIO if len(obj.data.polygons) > 20000 else AIRCRAFT_NEAR_PART_RATIO
            evaluated = obj.evaluated_get(bpy.context.evaluated_depsgraph_get())
            mesh = evaluated.to_mesh()
        if mod and source_bounds and any(v.co[i] < source_bounds[i][0] - SIMPLIFIED_ENVELOPE_TOLERANCE
                or v.co[i] > source_bounds[i][1] + SIMPLIFIED_ENVELOPE_TOLERANCE
                for v in mesh.vertices for i in range(3)):
            evaluated.to_mesh_clear()
            obj.modifiers.remove(mod)
            evaluated = obj.evaluated_get(bpy.context.evaluated_depsgraph_get())
            mesh = evaluated.to_mesh()
        base = len(vertices)
        transforms = [matrices[f][obj.name] for f in frames]
        for v in mesh.vertices:
            points = [m @ v.co for m in transforms]
            vertices.append(points[0])
            poses.append(points)
        if len(vertices) > base:
            part_bounds[obj.name] = [min(mc(v)[i] for v in vertices[base:]) for i in range(3)] + [max(mc(v)[i] for v in vertices[base:]) for i in range(3)]
        for poly in mesh.polygons:
            face = [base + i for i in poly.vertices]
            uvs = [tuple(mesh.uv_layers.active.data[i].uv) if mesh.uv_layers.active else (0., 0.)
                   for i in poly.loop_indices]
            if transforms[0].determinant() < 0:
                face.reverse()
                uvs.reverse()
            faces.append(face)
            source_uvs.extend(uvs)
            mat = mesh.materials[poly.material_index] if mesh.materials else None
            key = mat.name if mat else 'fallback'
            if key not in material_map:
                material_map[key] = len(materials)
                materials.append(mat or bpy.data.materials.new('Runtime fallback'))
            material_indices.append(material_map[key])
            # Both halves can live in one mirrored source mesh. Classify each face.
            center = sum((vertices[i] for i in face), Vector()) / len(face)
            is_blade = 'blade' in obj.name.lower() or obj.name.startswith(('R04_Hub', 'R05_HubCap'))
            groups.append(1 + min(range(len(rotor_centers)), key=lambda i:
                          sum((mc(center)[j] - rotor_centers[i][j]) ** 2 for j in range(3))) if is_blade else 0)
        evaluated.to_mesh_clear()
    merged = bpy.data.meshes.new('Runtime surface')
    merged.from_pydata(vertices, [], faces)
    merged.update()
    source_uv = merged.uv_layers.new(name='SourceUV')
    for loop, uv in zip(source_uv.data, source_uvs):
        loop.uv = uv
    merged.uv_layers.new(name='GameUV')
    merged.uv_layers.active_index = 1
    model = bpy.data.objects.new('Runtime bake', merged)
    scene.collection.objects.link(model)
    for material in materials:
        merged.materials.append(material)
    for poly, index in zip(merged.polygons, material_indices):
        poly.material_index = index
        poly.use_smooth = True
    for obj in objects:
        obj.hide_render = True
    bpy.ops.object.select_all(action='DESELECT')
    model.select_set(True)
    bpy.context.view_layer.objects.active = model
    bpy.ops.object.mode_set(mode='EDIT')
    bpy.ops.mesh.select_all(action='SELECT')
    bpy.ops.uv.smart_project(angle_limit=math.radians(66), island_margin=.003)
    bpy.ops.object.mode_set(mode='OBJECT')
    atlas = bpy.data.images.new('Runtime albedo', width=2048 if aircraft else 1024,
                                height=2048 if aircraft else 1024, alpha=False)
    for mat in materials:
        mat.use_nodes = True
        for original in list(mat.node_tree.nodes):
            if original.type == 'TEX_IMAGE' and not original.inputs['Vector'].is_linked:
                uv_node = mat.node_tree.nodes.new('ShaderNodeUVMap')
                uv_node.uv_map = 'SourceUV'
                mat.node_tree.links.new(uv_node.outputs['UV'], original.inputs['Vector'])
        node = mat.node_tree.nodes.new('ShaderNodeTexImage')
        node.image = atlas
        mat.node_tree.nodes.active = node
    scene.render.engine = 'CYCLES'
    scene.cycles.samples = 1
    scene.cycles.device = 'CPU'
    scene.render.bake.use_pass_direct = False
    scene.render.bake.use_pass_indirect = False
    scene.render.bake.use_pass_color = True
    scene.render.bake.margin = 4
    bpy.ops.object.bake(type='DIFFUSE')
    destination.parent.mkdir(parents=True, exist_ok=True)
    atlas.filepath_raw = str(destination.with_suffix('.png').resolve())
    atlas.file_format = 'PNG'
    atlas.save()
    merged.calc_loop_triangles()
    count = len(merged.loop_triangles) * 3
    if count > 400000:
        raise ValueError(f'Geometry budget exceeded: {count}')
    signals = [material_signal(m) for m in materials]
    bounds = [[float('inf')] * 3 + [float('-inf')] * 3 for _ in range(3)]
    with destination.open('wb') as output:
        output.write(struct.pack('>III', 0x4D474D34, count, len(rotor_centers)))
        for center in rotor_centers:
            output.write(struct.pack('>3f', *center))
        for tri in merged.loop_triangles:
            color, luminous = signals[tri.material_index]
            for loop_index in tri.loops:
                index = merged.loops[loop_index].vertex_index
                values = []
                for pose_index in range(3):
                    point = mc(poses[index][pose_index])
                    a, b, c = [poses[i][pose_index] for i in tri.vertices]
                    normal = mc((b - a).cross(c - a).normalized())
                    values.extend((*point, *normal))
                    for axis in range(3):
                        bounds[pose_index][axis] = min(bounds[pose_index][axis], point[axis])
                        bounds[pose_index][axis + 3] = max(bounds[pose_index][axis + 3], point[axis])
                uv = merged.uv_layers.active.data[loop_index].uv
                output.write(struct.pack('>20f6B', *values, uv.x, 1 - uv.y,
                                         *color, groups[tri.polygon_index], luminous))
    report = {'source': str(source), 'format': 'MGM4', 'vertices': count,
              'bounds': bounds, 'rotorCenters': rotor_centers, 'poses': list(frames),
              'luminousTriangles': sum(signals[t.material_index][1] != 0 for t in merged.loop_triangles),
              'parts': part_bounds}
    destination.with_suffix('.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print('EXPORTED', destination.name, count, flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--only', default='')
    parser.add_argument('--lod', action='store_true')
    args = parser.parse_args(sys.argv[sys.argv.index('--') + 1:])
    jobs = {r: (args.root / f'docs/design/detail-scale-v22/{r}/airframe.blend', True)
            for r in ('field', 'scout', 'cargo', 'engineer', 'security', 'salvage')}
    jobs['dock'] = (args.root / 'docs/design/detail-scale-v22/dock/dock.blend', False)
    for name in ('controller', 'tactical_visor', 'recovery_tool', 'solar_service_station', 'charging_relay', 'service_light'):
        jobs[name] = (args.root / f'docs/design/equipment-hmi-v23/models/{name}/{name}.blend', False)
    for name, (source, aircraft) in jobs.items():
        if args.only and name not in args.only.split(','):
            continue
        export(source, args.root / f'build/runtime-v23/{name}{"_lod" if args.lod else ""}.mgm', aircraft, args.lod)
