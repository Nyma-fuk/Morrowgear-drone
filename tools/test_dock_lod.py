"""Dock asset regression tests; run with Blender's bundled Python (NumPy)."""
import json
from pathlib import Path
import unittest

import numpy as np
from optimize_runtime_lod import read_mesh, bounds, sha, topology

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / 'src/main/resources/assets/morrowgear_drone/models/runtime'


def uv_faces(records):
    return {tuple(face['values'][:, 18:].ravel()) + tuple(face['color']) +
            tuple(face['group']) + tuple(face['flag']) for face in records.reshape(-1, 3)}


class DockLodTest(unittest.TestCase):
    def test_budgets_bounds_uvs_materials_static_poses_and_reports(self):
        centers, source = read_mesh(MODELS / 'dock.mgm')
        faces = uv_faces(source)
        source_patches = topology(source)[2]
        source_luminous = sum(bool(np.any(source.reshape(-1, 3)[source_patches == patch]['flag'] == 1))
            for patch in range(int(source_patches.max()) + 1))
        for name, limit in (('dock_lod', 6000), ('dock_far', 2000)):
            with self.subTest(name=name):
                path = MODELS / (name + '.mgm')
                result_centers, result = read_mesh(path)
                report = json.loads((ROOT / 'build/dock-lod' / (name + '.json')).read_text())
                self.assertLessEqual(len(result) // 3, limit)
                source_bounds, result_bounds = np.asarray(bounds(source)), np.asarray(bounds(result))
                self.assertTrue(np.array_equal(source_bounds[:, [0, 2, 3, 5]],
                    result_bounds[:, [0, 2, 3, 5]]))
                self.assertLessEqual(float(np.max(np.abs(source_bounds[:, [1, 4]] -
                    result_bounds[:, [1, 4]]))), .002)
                self.assertTrue(np.array_equal(centers, result_centers))
                self.assertTrue(uv_faces(result) <= faces)
                self.assertEqual(set(result['flag']), set(source['flag']))
                self.assertEqual(set(result['group']), {0})
                for start in (6, 12):
                    self.assertTrue(np.array_equal(result['values'][:, :6], result['values'][:, start:start + 6]))
                self.assertEqual(report['sourceSha256'], sha(MODELS / 'dock.mgm'))
                self.assertEqual(report['outputSha256'], sha(path))
                self.assertEqual(report['textureSha256'], sha(MODELS.parents[1] / 'textures/runtime/dock.png'))
                self.assertTrue(report['numericChecksPassed'])
                self.assertEqual(report['sourcePatches'], 206)
                self.assertLess(report['keptPatches'], report['sourcePatches'])
                self.assertEqual(report['keptLuminousPatches'], report['sourceLuminousPatches'])
                self.assertEqual(report['sourceLuminousPatches'], source_luminous)
                self.assertLessEqual(report['maxKeptSurfaceError'], report['keptSurfaceErrorLimit'])
                signals = np.count_nonzero(result['flag'][::3] == 1)
                vertices = 4 * (len(result) // 3 + signals)
                print(f'{name}: triangles={len(result)//3}, signals={signals}, submittedVertices={vertices}')
        self.assertLessEqual(json.loads((ROOT / 'build/dock-lod/dock_far.json').read_text())['triangles'] * 32, 64000)


if __name__ == '__main__':
    unittest.main()
