"""Generate Dock-only conservative LODs from the shipped MGM4 and unchanged atlas.

Run in Blender: blender --background --threads 1 --python tools/generate_dock_lod.py
Candidates and sampled surface/UV audit reports remain in build/dock-lod.
Pass -- --publish to write only Dock LOD assets after all numeric checks pass.
This does not deploy a mod or claim in-game visual/FPS verification.
"""
import argparse
import faulthandler
import json
from pathlib import Path
import shutil
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import numpy as np
from optimize_runtime_lod import clustered, read_mesh, write_mesh, topology, audit, bounds, sha


def surface_error(source, candidate):
    from mathutils import Vector
    from mathutils.bvhtree import BVHTree
    maximum = 0.
    for old, new in ((source, candidate), (candidate, source)):
        points = new['values'][:, :3].astype(float)
        tree = BVHTree.FromPolygons([Vector(p) for p in points],
            np.arange(len(points)).reshape(-1, 3).tolist(), all_triangles=True)
        triangles = old['values'][:, :3].astype(float).reshape(-1, 3, 3)
        samples = np.concatenate([triangles.reshape(-1, 3), triangles.mean(axis=1),
            (triangles[:, 0] + triangles[:, 1]) / 2,
            (triangles[:, 1] + triangles[:, 2]) / 2,
            (triangles[:, 2] + triangles[:, 0]) / 2])
        for point in samples:
            hit = tree.find_nearest(Vector(point))
            if hit[0] is None:
                return float('inf')
            maximum = max(maximum, hit[3])
    return maximum


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--publish', action='store_true')
    args = parser.parse_args(sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else [])
    root = Path(__file__).resolve().parents[1]
    models = root / 'src/main/resources/assets/morrowgear_drone/models/runtime'
    source = models / 'dock.mgm'
    reports = []
    centers, original = read_mesh(source)
    if np.any(original['group'] != 0) or not all(np.array_equal(
            original['values'][:, :6], original['values'][:, start:start + 6]) for start in (6, 12)):
        raise ValueError('Dock reduction requires a static, non-rotating source')
    _, _, patches = topology(original)
    faulthandler.dump_traceback_later(60, repeat=True)
    policies = (
        # Medium keeps deck equipment and major service features; far keeps the
        # manufactured silhouette, corner masses and every emissive component.
        ('dock_lod', .010, 6000, .30, .08),
        ('dock_far', .025, 2000, .70, .12),
    )
    previous = {}
    for name, cell, limit, horizontal_threshold, height_threshold in policies:
        output = root / 'build/dock-lod' / (name + '.mgm')
        print('Generating ' + name, flush=True)
        results, errors, accepted, kept, luminous_kept = [], [], 0, 0, 0
        max_surface = 0.
        # Cluster each connected component independently; never bridge separate parts.
        # Keep each surviving face's exact original UV corners, rather than
        # projecting them to another atlas island. Audit both surface directions.
        for patch in range(int(patches.max()) + 1):
            part = original.reshape(-1, 3)[patches == patch].ravel()
            xyz = part['values'][:, :3]
            size = xyz.max(axis=0) - xyz.min(axis=0)
            luminous = bool(np.any(part['flag'] == 1))
            primary = max(size[0], size[2]) > horizontal_threshold or size[1] > height_threshold
            if not (luminous or primary):
                continue
            kept += 1
            luminous_kept += int(luminous)
            candidate, _ = clustered(part, cell)
            surface = surface_error(part, candidate)
            reduced = candidate if surface <= cell * 2.2 else part
            if reduced is part:
                surface = 0.
            if patch in previous and len(previous[patch]) < len(reduced):
                reduced = previous[patch]
                surface = surface_error(part, reduced)
            previous[patch] = reduced
            if len(reduced) < len(part):
                accepted += 1
            results.append(reduced)
            max_surface = max(max_surface, surface)
            if patch % 25 == 0:
                print(f'{name}: checked {patch + 1}/{int(patches.max()) + 1} components', flush=True)
        result = np.concatenate(results)
        errors = [error for error in audit(original, result, centers, centers, limit)
            if error != 'color identity set changed']
        source_luminous = sum(bool(np.any(original.reshape(-1, 3)[patches == patch]['flag'] == 1))
            for patch in range(int(patches.max()) + 1))
        if luminous_kept != source_luminous:
            errors.append('emissive recognition component disappeared')
        if kept >= int(patches.max()) + 1:
            errors.append('LOD did not remove distance-invisible components')
        write_mesh(output, centers, result)
        report = {'sourceSha256': sha(source), 'outputSha256': sha(output),
                  'textureSha256': sha(models.parents[1] / 'textures/runtime/dock.png'),
                  'sourceTriangles': len(original) // 3, 'triangles': len(result) // 3,
                  'sourcePatches': int(patches.max()) + 1, 'keptPatches': kept,
                  'droppedPatches': int(patches.max()) + 1 - kept,
                  'sourceLuminousPatches': source_luminous, 'keptLuminousPatches': luminous_kept,
                  'acceptedPatches': accepted, 'maxKeptSurfaceError': max_surface,
                  'keptSurfaceErrorLimit': cell * 2.2,
                  'primaryHorizontalThreshold': horizontal_threshold,
                  'primaryHeightThreshold': height_threshold,
                  'uvCorners': 'unchanged source triangle corners', 'cell': cell, 'bounds': bounds(result),
                  'numericChecksPassed': not errors, 'errors': errors, 'textureUnchanged': True}
        output.with_suffix('.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        print(json.dumps(report), flush=True)
        if not report['numericChecksPassed']:
            raise ValueError(report['errors'])
        reports.append((output, report))
    faulthandler.cancel_dump_traceback_later()
    if reports[1][1]['triangles'] > reports[0][1]['triangles']:
        raise ValueError('far LOD must not cost more than medium')
    if args.publish:
        for output, _ in reports:
            shutil.copyfile(output, models / output.name)


if __name__ == '__main__':
    main()
