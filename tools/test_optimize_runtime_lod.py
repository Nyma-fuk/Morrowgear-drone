"""Offline parser and candidate-budget tests; no Blender, Gradle or game startup."""
from pathlib import Path
import struct
import sys
import tempfile
import unittest

import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent))
import optimize_runtime_lod as lod


def sample(triangles):
    records = np.zeros(triangles * 3, dtype=lod.VERTEX)
    points = np.tile(np.array([[-1, 0, 0], [1, 0, 0], [0, 1, 0]]), (triangles, 1))
    for start in lod.POSE_STARTS:
        records['values'][:, start:start + 3] = points
        records['values'][:, start + 5] = 1
    records['values'][:, 18:] = .5
    records['color'] = 0xFFFFFFFF
    return records


class RuntimeLodBudgetTest(unittest.TestCase):
    def setUp(self):
        self.centers = np.array([[-.8, .4, 0], [.8, .4, 0]], dtype='>f4')

    def test_target_is_ten_thousand_real_triangles_not_vertices(self):
        self.assertEqual(4000, lod.DEFAULT_TARGET_TRIANGLES)
        source = sample(20000)
        self.assertEqual([], lod.audit(source, sample(10000), self.centers, self.centers, 10000))
        self.assertIn('triangle target not met', lod.audit(source, sample(10001), self.centers, self.centers, 10000))

    def test_current_scout_density_cannot_be_misreported_as_an_accepted_lod(self):
        errors = lod.audit(sample(54401), sample(44373), self.centers, self.centers)
        self.assertIn('triangle target not met', errors)

    def test_reduction_and_all_three_pose_envelopes_are_required(self):
        source = sample(6)
        self.assertIn('no geometry reduction', lod.audit(source, source, self.centers, self.centers))
        for start in lod.POSE_STARTS:
            reduced = sample(3)
            reduced['values'][:, start + 1] += .01
            self.assertIn('three-pose envelope changed by more than 3mm', lod.audit(source, reduced, self.centers, self.centers))

    def test_rotor_centers_materials_and_emission_cannot_disappear(self):
        source = sample(9)
        source['group'][3:6] = 1
        source['group'][6:9] = 2
        source['flag'][3:6] = 1
        source['color'][6:9] = 0xFF112233
        errors = lod.audit(source, sample(3), self.centers, self.centers + .01)
        for error in ('rotor centers changed', 'color identity set changed', 'group identity set changed', 'flag identity set changed'):
            self.assertIn(error, errors)

    def test_symmetric_bounds_cannot_drift_inside_the_looser_envelope_tolerance(self):
        reduced = sample(3)
        reduced['values'][0, 0] -= .002
        self.assertIn('left/right envelope symmetry changed', lod.audit(sample(6), reduced, self.centers, self.centers))

    def test_binary_roundtrip_preserves_all_pose_uv_and_surface_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'candidate.mgm'
            original = sample(5)
            lod.write_mesh(path, self.centers, original)
            centers, records = lod.read_mesh(path)
            np.testing.assert_array_equal(self.centers, centers)
            self.assertEqual(original.tobytes(), records.tobytes())
            self.assertEqual(12 + len(self.centers) * 12 + len(original) * 86, path.stat().st_size)

    def test_parser_rejects_truncation_trailing_data_and_invalid_record_values(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'invalid.mgm'
            lod.write_mesh(path, self.centers, sample(2))
            valid = path.read_bytes()
            for broken in (valid[:8], valid[:-1], valid + b'x', b'BAD!' + valid[4:]):
                path.write_bytes(broken)
                with self.assertRaises(ValueError):
                    lod.read_mesh(path)
            for field, value in (('group', 3), ('flag', 3)):
                records = sample(2)
                records[field][:3] = value
                lod.write_mesh(path, self.centers, records)
                with self.assertRaises(ValueError):
                    lod.read_mesh(path)
            for offset, value in ((0, float('nan')), (18, 2)):
                records = sample(2)
                records['values'][0, offset] = value
                lod.write_mesh(path, self.centers, records)
                with self.assertRaises(ValueError):
                    lod.read_mesh(path)

    def test_mixed_triangle_surface_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'mixed.mgm'
            for field in ('group', 'flag', 'color'):
                records = sample(2)
                records[field][0] = 1
                lod.write_mesh(path, self.centers, records)
                with self.assertRaises(ValueError):
                    lod.read_mesh(path)

    def test_pose_aware_clustering_reduces_faces_without_losing_surface_identities(self):
        source = sample(8)
        source['values'][:, 0] += np.repeat(np.arange(8), 3) * .001
        source['flag'][-3:] = 1
        result, displacement = lod.clustered(source, .02)
        self.assertLess(len(result), len(source))
        self.assertLessEqual(displacement, .02)
        self.assertEqual({0, 1}, set(result['flag']))
        for start in lod.POSE_STARTS:
            self.assertTrue(np.isfinite(result['values'][:, start:start + 6]).all())

    def test_welding_keeps_loop_uvs_but_not_material_rotor_or_pose_boundaries(self):
        identical = sample(2)
        _, _, patches = lod.topology(identical)
        self.assertEqual(1, len(set(patches)))
        different_uv = identical.copy()
        different_uv['values'][3:, 18] += .1
        _, _, patches = lod.topology(different_uv)
        self.assertEqual(1, len(set(patches)))
        for column in (6, 12):
            different = identical.copy()
            different['values'][3:, column] += .1
            _, _, patches = lod.topology(different)
            self.assertEqual(2, len(set(patches)))
        for field in ('color', 'group', 'flag'):
            different = identical.copy()
            different[field][3:] = 1
            _, _, patches = lod.topology(different)
            self.assertEqual(2, len(set(patches)))


if __name__ == '__main__':
    unittest.main(argv=[__file__])
