"""Conservative, staged MGM4 LOD reduction. Never writes packaged assets.

Run with Blender --background --python this_file -- --source ... --output ... .
The existing atlas stays untouched. In conservative mode, all three poses are
reconstructed from verified source-patch rigid transforms after simplification.
Rotor/material/UV boundaries are split. These are experiments, not release assets;
numeric checks alone do not approve a candidate without rendered visual review.
"""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import struct
import sys

import numpy as np

MAGIC = 0x4D474D34
VERTEX = np.dtype([('values', '>f4', (20,)), ('color', '>u4'), ('group', 'u1'), ('flag', 'u1')])
POSE_STARTS = (0, 6, 12)
DEFAULT_TARGET_TRIANGLES = 4000
MAX_SURFACE_ERROR = .015
MAX_UV_ERROR = 4 / 2048


def read_mesh(path):
    data = Path(path).read_bytes()
    if len(data) < 12:
        raise ValueError('truncated MGM header')
    magic, count, rotors = struct.unpack_from('>III', data)
    if magic != MAGIC or count <= 0 or count % 3 or count > 400000 or not 2 <= rotors <= 8:
        raise ValueError('invalid MGM4 header')
    offset = 12 + rotors * 12
    if len(data) != offset + count * VERTEX.itemsize:
        raise ValueError('truncated or trailing MGM4 records')
    centers = np.frombuffer(data, dtype='>f4', count=rotors * 3, offset=12).reshape(-1, 3).copy()
    records = np.frombuffer(data, dtype=VERTEX, offset=offset).copy()
    values = records['values']
    if not np.isfinite(values).all() or not np.isfinite(centers).all():
        raise ValueError('non-finite MGM value')
    if np.any(records['group'] > rotors) or np.any(records['flag'] > 2):
        raise ValueError('invalid MGM material/rotor')
    if np.any(values[:, 18:] < -.001) or np.any(values[:, 18:] > 1.001):
        raise ValueError('invalid MGM UV')
    for field in ('color', 'group', 'flag'):
        if not np.all(records[field].reshape(-1, 3) == records[field][::3, None]):
            raise ValueError('mixed triangle material/rotor')
    return centers, records


def write_mesh(path, centers, records):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('wb') as stream:
        stream.write(struct.pack('>III', MAGIC, len(records), len(centers)))
        stream.write(np.asarray(centers, dtype='>f4').tobytes())
        stream.write(np.asarray(records, dtype=VERTEX).tobytes())


def topology(records):
    values = records['values'].astype(np.float64)
    # Normals and UVs are per-loop data in Blender. Welding an atlas seam is safe
    # because every output loop keeps its own UV; poses, material and rotor identity
    # still form hard topology boundaries.
    keys = np.column_stack([values[:, [0, 1, 2, 6, 7, 8, 12, 13, 14]],
                            records['color'], records['group'], records['flag']])
    _, first, inverse = np.unique(keys, axis=0, return_index=True, return_inverse=True)
    faces = inverse.reshape(-1, 3)
    parent = np.arange(len(first))

    def root(index):
        while parent[index] != index:
            parent[index] = parent[parent[index]]
            index = parent[index]
        return index

    for a, b, c in faces:
        for vertex in (b, c):
            parent[root(vertex)] = root(a)
    roots = np.array([root(face[0]) for face in faces])
    _, patches = np.unique(roots, return_inverse=True)
    return first, faces, patches


def bounds(records):
    return [[*records['values'][:, start:start + 3].min(axis=0).astype(float),
             *records['values'][:, start:start + 3].max(axis=0).astype(float)] for start in POSE_STARTS]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def audit(source, result, source_centers, result_centers, target=DEFAULT_TARGET_TRIANGLES):
    errors = []
    if not np.array_equal(source_centers, result_centers):
        errors.append('rotor centers changed')
    old_bounds, new_bounds = np.array(bounds(source)), np.array(bounds(result))
    if np.max(np.abs(old_bounds - new_bounds)) > .003:
        errors.append('three-pose envelope changed by more than 3mm')
    for field in ('color', 'group', 'flag'):
        if set(source[field]) != set(result[field]):
            errors.append(field + ' identity set changed')
    if len(result) // 3 > target:
        errors.append('triangle target not met')
    if len(result) >= len(source):
        errors.append('no geometry reduction')
    old_symmetry = old_bounds[:, 0] + old_bounds[:, 3]
    new_symmetry = new_bounds[:, 0] + new_bounds[:, 3]
    if np.max(np.abs(new_symmetry - old_symmetry)) > .001:
        errors.append('left/right envelope symmetry changed')
    return errors


def clustered(source, cell):
    values = source['values'].astype(np.float64)
    faces = np.arange(len(source)).reshape(-1, 3)
    surface = np.column_stack([source['color'], source['group'], source['flag']]).reshape(-1, 3, 3)[:, 0]
    surface_per_vertex = np.repeat(surface, 3, axis=0)
    pose_values = np.column_stack([values[:, start:start + 3] for start in POSE_STARTS])
    quantized = np.rint(pose_values / cell).astype(np.int64)
    boundary = np.zeros_like(pose_values, dtype=np.int8)
    for pose in range(3):
        xyz = pose_values[:, pose * 3:pose * 3 + 3]
        for axis in range(3):
            boundary[:, pose * 3 + axis] = np.where(np.isclose(xyz[:, axis], xyz[:, axis].min(), atol=1e-6), -1,
                np.where(np.isclose(xyz[:, axis], xyz[:, axis].max(), atol=1e-6), 1, 0))
    keys = np.column_stack([surface_per_vertex, quantized, boundary])
    _, inverse = np.unique(keys, axis=0, return_inverse=True)
    clusters = int(inverse.max()) + 1
    averaged = np.empty((clusters, 9), dtype=np.float64)
    counts = np.bincount(inverse)
    for column in range(9): averaged[:, column] = np.bincount(inverse, weights=pose_values[:, column]) / counts
    mapped = inverse.reshape(-1, 3)
    keep = np.logical_and.reduce((mapped[:, 0] != mapped[:, 1], mapped[:, 1] != mapped[:, 2], mapped[:, 2] != mapped[:, 0]))
    kept_faces = np.flatnonzero(keep)
    mapped = mapped[keep]
    face_keys = np.column_stack([np.sort(mapped, axis=1), surface[keep]])
    _, unique_faces = np.unique(face_keys, axis=0, return_index=True)
    unique_faces.sort()
    result = source.reshape(-1, 3)[kept_faces[unique_faces]].copy()
    mapped = mapped[unique_faces]
    for pose, start in enumerate(POSE_STARTS):
        result['values'][:, :, start:start + 3] = averaged[mapped, pose * 3:pose * 3 + 3]
        triangles = result['values'][:, :, start:start + 3].astype(np.float64)
        normals = np.cross(triangles[:, 1] - triangles[:, 0], triangles[:, 2] - triangles[:, 0])
        lengths = np.linalg.norm(normals, axis=1)
        valid = lengths > 1e-10
        result = result[valid]
        mapped = mapped[valid]
        normals = normals[valid] / lengths[valid, None]
        result['values'][:, :, start + 3:start + 6] = normals[:, None, :]
    result = result.reshape(-1)
    # Vertex clustering can collapse the one face carrying a wingtip or tail
    # extremum. Restore only those bounded witness faces so the far silhouette
    # keeps the exact three-pose envelope without retaining nearby detail.
    source_bounds = np.asarray(bounds(source))
    result_bounds = np.asarray(bounds(result))
    witnesses = set()
    for pose, start in enumerate(POSE_STARTS):
        for axis in range(3):
            if abs(result_bounds[pose, axis] - source_bounds[pose, axis]) > .001:
                witnesses.add(int(np.argmin(values[:, start + axis])) // 3)
            if abs(result_bounds[pose, axis + 3] - source_bounds[pose, axis + 3]) > .001:
                witnesses.add(int(np.argmax(values[:, start + axis])) // 3)
    if witnesses:
        result = np.concatenate([result, source.reshape(-1, 3)[sorted(witnesses)].ravel()])
    source_surfaces = {tuple(row) for row in surface.tolist()}
    result_surfaces = {tuple(row) for row in np.column_stack([result['color'], result['group'], result['flag']])[::3].tolist()}
    for missing in source_surfaces - result_surfaces:
        index = next(i for i, row in enumerate(surface.tolist()) if tuple(row) == missing)
        result = np.concatenate([result, source.reshape(-1, 3)[index].ravel()])
    displacement = 0.
    for pose in range(3):
        displacement = max(displacement, float(np.linalg.norm(
            pose_values[:, pose * 3:pose * 3 + 3] - averaged[inverse, pose * 3:pose * 3 + 3], axis=1).max()))
    return result, displacement


def optimize_cluster(source_path, output, target):
    centers, source = read_mesh(source_path)
    candidates = []
    for cell in (.01, .015, .02, .03, .04, .05, .065, .08, .10, .13, .16, .22, .30, .42, .60, .85, 1.2):
        result, displacement = clustered(source, cell)
        candidates.append((cell, result, displacement))
        if len(result) // 3 <= target:
            break
    cell, result, displacement = candidates[-1]
    errors = audit(source, result, centers, centers, target)
    report = {'source': str(source_path), 'sourceSha256': sha(source_path),
              'sourceTriangles': len(source) // 3, 'triangles': len(result) // 3, 'target': target,
              'method': 'pose-aware vertex clustering', 'cell': cell,
              'maxVertexDisplacement': displacement, 'bounds': bounds(result), 'sourceBounds': bounds(source),
              'errors': errors, 'numericChecksPassed': not errors, 'textureUnchanged': True}
    write_mesh(output, centers, result)
    report['outputSha256'] = sha(output)
    output.with_suffix('.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report), flush=True)
    return report


def restore_unsafe_patches(source, patches, candidate, output_patches):
    from mathutils import Vector
    from mathutils.bvhtree import BVHTree
    from mathutils.geometry import barycentric_transform
    old_groups, new_groups = defaultdict(list), defaultdict(list)
    for index, patch in enumerate(patches):
        old_groups[int(patch)].append(index)
    for index, patch in enumerate(output_patches):
        new_groups[int(patch)].append(index)
    result, reasons = [], defaultdict(int)
    maximum_surface, maximum_uv = 0., 0.

    def tree(points):
        return BVHTree.FromPolygons([Vector(point) for point in points],
                                   np.arange(len(points)).reshape(-1, 3).tolist(), all_triangles=True)

    for patch, old_indices in old_groups.items():
        original = source.reshape(-1, 3)[old_indices].ravel()
        indices = new_groups.get(patch, [])
        reason = None
        if not indices:
            reason = 'missing_component'
        elif len(indices) >= len(old_indices):
            reason = 'no_reduction'
        if reason:
            reasons[reason] += 1
            result.append(original)
            continue
        reduced = candidate.reshape(-1, 3)[indices].ravel().copy()
        old = original['values'].astype(np.float64)
        new = reduced['values'].astype(np.float64)
        base_center = old[:, :3].mean(axis=0)
        # Generic vector attributes do not follow a QEM vertex's optimized position.
        # Recover and verify the original rigid pose of each intact source patch.
        for start in (6, 12):
            pose_center = old[:, start:start + 3].mean(axis=0)
            u, _, vt = np.linalg.svd((old[:, :3] - base_center).T @ (old[:, start:start + 3] - pose_center))
            rotation = u @ vt
            if np.linalg.det(rotation) < 0:
                u[:, -1] *= -1
                rotation = u @ vt
            reconstructed = (old[:, :3] - base_center) @ rotation + pose_center
            if np.max(np.linalg.norm(reconstructed - old[:, start:start + 3], axis=1)) > 1e-5:
                reason = 'nonrigid_source_patch'
                break
            new[:, start:start + 3] = (new[:, :3] - base_center) @ rotation + pose_center
        if not reason:
            for start in POSE_STARTS:
                triangles = new[:, start:start + 3].reshape(-1, 3, 3)
                normals = np.cross(triangles[:, 1] - triangles[:, 0], triangles[:, 2] - triangles[:, 0])
                lengths = np.linalg.norm(normals, axis=1)
                if np.any(lengths < 1e-12):
                    reason = 'collapsed_surface'
                    break
                new[:, start + 3:start + 6] = np.repeat(normals / lengths[:, None], 3, axis=0)
                old_bounds = np.concatenate([old[:, start:start + 3].min(axis=0), old[:, start:start + 3].max(axis=0)])
                new_bounds = np.concatenate([triangles.min(axis=(0, 1)), triangles.max(axis=(0, 1))])
                if np.max(np.abs(old_bounds - new_bounds)) > .003:
                    reason = 'local_envelope'
                    break
        surface_error = uv_error = 0.
        if not reason:
            original_tree, reduced_tree = tree(old[:, :3]), tree(new[:, :3])
            # Every original vertex/face center is checked too, so removed faces cannot hide behind one-sided checks.
            old_samples = np.concatenate([old[:, :3], old[:, :3].reshape(-1, 3, 3).mean(axis=1)])
            for point in old_samples:
                hit = reduced_tree.find_nearest(Vector(point))
                if hit[0] is None or hit[3] > MAX_SURFACE_ERROR:
                    reason = 'source_surface_gap'
                    break
                surface_error = max(surface_error, hit[3])
            if not reason:
                # Project UVs to their original island, never to a neighboring material or rotor.
                for index, point in enumerate(new[:, :3]):
                    hit, _, triangle, distance = original_tree.find_nearest(Vector(point))
                    if hit is None or distance > MAX_SURFACE_ERROR:
                        reason = 'candidate_surface_error'
                        break
                    surface_error = max(surface_error, distance)
                    original_tri = old[triangle * 3:triangle * 3 + 3]
                    uv = barycentric_transform(hit, *[Vector(p[:3]) for p in original_tri],
                        *[Vector((p[18], p[19], 0)) for p in original_tri])
                    new[index, 18:] = uv[:2]
            if not reason:
                weights = ((1/3, 1/3, 1/3), (.5, .5, 0), (0, .5, .5), (.5, 0, .5))
                for triangle in new.reshape(-1, 3, 20):
                    for weights_row in weights:
                        weight = np.asarray(weights_row)
                        point = weight @ triangle[:, :3]
                        hit, normal, old_triangle, distance = original_tree.find_nearest(Vector(point))
                        if hit is None or distance > MAX_SURFACE_ERROR or np.dot(normal, triangle[0, 3:6]) <= 0:
                            reason = 'new_surface_or_winding'
                            break
                        original_tri = old[old_triangle * 3:old_triangle * 3 + 3]
                        uv = barycentric_transform(hit, *[Vector(p[:3]) for p in original_tri],
                            *[Vector((p[18], p[19], 0)) for p in original_tri])
                        error = np.max(np.abs(weight @ triangle[:, 18:] - np.array(uv[:2])))
                        if error > MAX_UV_ERROR:
                            reason = 'atlas_interpolation'
                            break
                        surface_error = max(surface_error, distance)
                        uv_error = max(uv_error, error)
                    if reason:
                        break
        if reason:
            reasons[reason] += 1
            result.append(original)
        else:
            reduced['values'] = new
            result.append(reduced)
            reasons['accepted'] += 1
            maximum_surface = max(maximum_surface, surface_error)
            maximum_uv = max(maximum_uv, uv_error)
    return np.concatenate(result), dict(reasons), maximum_surface, maximum_uv


def optimize(source_path, output, ratio, planar, conservative=False, target=DEFAULT_TARGET_TRIANGLES):
    import bpy
    centers, source = read_mesh(source_path)
    first, faces, patches = topology(source)
    values = source['values'].astype(np.float32)
    bpy.ops.wm.read_factory_settings(use_empty=True)
    mesh = bpy.data.meshes.new('Approved MGM4 LOD')
    mesh.from_pydata(values[first, :3].tolist(), [], faces.tolist())
    mesh.update()
    for pose, start in enumerate(POSE_STARTS[1:], 1):
        attribute = mesh.attributes.new('pose_' + str(pose), 'FLOAT_VECTOR', 'POINT')
        attribute.data.foreach_set('vector', values[first, start:start + 3].ravel())
    uv = mesh.uv_layers.new(name='ApprovedAtlas')
    uv.data.foreach_set('uv', values[:, 18:].ravel())
    material_keys, material_indices = np.unique(np.column_stack([
        source['color'][::3], source['group'][::3], source['flag'][::3]]), axis=0, return_inverse=True)
    for index in range(len(material_keys)):
        mesh.materials.append(bpy.data.materials.new('PreservedSurface_' + str(index)))
    mesh.polygons.foreach_set('material_index', material_indices)
    patch = mesh.attributes.new('source_patch', 'INT', 'FACE')
    patch.data.foreach_set('value', patches)
    obj = bpy.data.objects.new('LOD candidate', mesh)
    bpy.context.scene.collection.objects.link(obj)
    bpy.context.view_layer.objects.active = obj
    obj.select_set(True)
    modifier = obj.modifiers.new('UV and pose preserving candidate', 'DECIMATE')
    if planar:
        modifier.decimate_type = 'DISSOLVE'
        modifier.angle_limit = .0001
        modifier.delimit = {'NORMAL', 'MATERIAL', 'SEAM', 'UV'}
        modifier.use_dissolve_boundaries = False
    else:
        modifier.decimate_type = 'COLLAPSE'
        modifier.ratio = ratio
        modifier.use_collapse_triangulate = True
        # The source is already mirrored. Blender's global symmetry search is
        # quadratic on seam-heavy MGM meshes; the post-audit still rejects any
        # left/right envelope drift.
        modifier.use_symmetry = False
    evaluated = obj.evaluated_get(bpy.context.evaluated_depsgraph_get())
    simplified = evaluated.to_mesh(preserve_all_data_layers=True, depsgraph=bpy.context.evaluated_depsgraph_get())
    simplified.calc_loop_triangles()
    positions = []
    base = np.empty(len(simplified.vertices) * 3, dtype=np.float32)
    simplified.vertices.foreach_get('co', base)
    positions.append(base.reshape(-1, 3))
    for pose in (1, 2):
        attribute = simplified.attributes.get('pose_' + str(pose))
        if attribute is None:
            raise ValueError('Blender dropped a required pose attribute')
        data = np.empty(len(simplified.vertices) * 3, dtype=np.float32)
        attribute.data.foreach_get('vector', data)
        positions.append(data.reshape(-1, 3))
    preserved_patch = simplified.attributes.get('source_patch')
    if preserved_patch is None or simplified.uv_layers.active is None:
        raise ValueError('Blender dropped source patch or atlas coordinates')
    records = np.zeros(len(simplified.loop_triangles) * 3, dtype=VERTEX)
    output_patches = []
    for index, triangle in enumerate(simplified.loop_triangles):
        output_patches.append(preserved_patch.data[triangle.polygon_index].value)
        color, group, flag = material_keys[triangle.material_index]
        for pose, start in enumerate(POSE_STARTS):
            points = positions[pose][list(triangle.vertices)]
            normal = np.cross(points[1] - points[0], points[2] - points[0])
            length = np.linalg.norm(normal)
            if length < 1e-12:
                normal = np.zeros(3)
            else:
                normal /= length
            records['values'][index * 3:index * 3 + 3, start:start + 3] = points
            records['values'][index * 3:index * 3 + 3, start + 3:start + 6] = normal
        for corner, loop in enumerate(triangle.loops):
            row = index * 3 + corner
            records['values'][row, 18:] = simplified.uv_layers.active.data[loop].uv
            records['color'][row], records['group'][row], records['flag'][row] = color, group, flag
    result_patches = np.asarray(output_patches)
    restored, surface, uv_error = {}, None, None
    if conservative:
        records, restored, surface, uv_error = restore_unsafe_patches(source, patches, records, result_patches)
    errors = audit(source, records, centers, centers, target)
    missing = [] if conservative else sorted(set(patches) - set(result_patches))
    if missing:
        errors.append('source UV/component patches disappeared: ' + str(len(missing)))
    report = {'source': str(source_path), 'sourceSha256': sha(source_path), 'sourceTriangles': len(source) // 3,
              'triangles': len(records) // 3, 'target': target,
              'method': 'planar' if planar else 'collapse', 'ratio': ratio,
              'sourcePatches': int(max(patches) + 1), 'missingPatches': len(missing),
              'bounds': bounds(records), 'sourceBounds': bounds(source), 'errors': errors,
              'restoredPatches': restored, 'maxSampledSurfaceError': surface, 'maxSampledUVError': uv_error,
              'numericChecksPassed': conservative and not errors,
              'adoptable': False, 'textureUnchanged': True}
    write_mesh(output, centers, records)
    report['outputSha256'] = sha(output)
    output.with_suffix('.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    evaluated.to_mesh_clear()
    print(json.dumps(report), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--ratio', type=float, default=.12)
    parser.add_argument('--target', type=int, default=DEFAULT_TARGET_TRIANGLES)
    parser.add_argument('--planar', action='store_true')
    parser.add_argument('--conservative', action='store_true')
    parser.add_argument('--cluster', action='store_true')
    argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else sys.argv[1:]
    args = parser.parse_args(argv)
    root = Path(__file__).resolve().parents[1]
    build = (root / 'build').resolve()
    if build not in args.output.resolve().parents:
        parser.error('candidates must stay under this repository build directory')
    if not 0 < args.ratio <= 1:
        parser.error('ratio must be in (0, 1]')
    if not 1 <= args.target <= 10000:
        parser.error('target must be in [1, 10000] triangles')
    if args.cluster:
        optimize_cluster(args.source.resolve(), args.output.resolve(), args.target)
    else:
        optimize(args.source.resolve(), args.output.resolve(), args.ratio, args.planar, args.conservative, args.target)


if __name__ == '__main__':
    main()
