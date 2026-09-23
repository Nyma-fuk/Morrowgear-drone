"""Small additive V4 laser adjustment, not a rewrite of the V3 base."""

import contextlib
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np
import soundfile as sf

from generate_drone_audio import OUTPUT, RATE, ROOT
from propose_laser_edge import BASE, EDGE_HZ, EDGE_RATIO, add_edge, generate, load_base, protected


class LaserEdgeTest(unittest.TestCase):
    def test_only_a_small_deterministic_high_layer_is_added(self):
        base = load_base()["laser_fire"]
        copy = base.copy()
        adjusted = add_edge(base)
        delta = adjusted - base
        self.assertAlmostEqual(np.linalg.norm(delta) / np.linalg.norm(base), EDGE_RATIO, places=6)
        np.testing.assert_array_equal(base, copy)
        np.testing.assert_array_equal(add_edge(base, 0), base)
        np.testing.assert_array_equal(adjusted, add_edge(base))
        self.assertLess(np.max(abs(adjusted)), .5)
        frequencies = np.fft.rfftfreq(len(delta), 1 / RATE)
        spectrum = abs(np.fft.rfft(delta)) ** 2
        self.assertLess(spectrum[frequencies < 2000].sum() / spectrum.sum(), 1e-12)

    def test_fixed_pitch_and_seam_without_periodic_warble(self):
        base = load_base()["laser_fire"]
        adjusted = add_edge(base)
        delta = adjusted - base
        for part in np.array_split(delta, 8):
            p = abs(np.fft.rfft(part)) ** 2
            hz = np.fft.rfftfreq(len(part), 1 / RATE)
            self.assertEqual(hz[np.argmax(p)], EDGE_HZ)
        self.assertLess(abs(adjusted[0] - adjusted[-1]), .04)

    def test_invalid_level_and_changed_source_fail(self):
        base = load_base()["laser_fire"]
        for ratio in (-1, .31, float("nan")):
            with self.assertRaises(ValueError):
                add_edge(base, ratio)
        with patch("propose_laser_edge.digest", return_value="modified"):
            with self.assertRaises(ValueError):
                load_base()

    def test_original_and_runtime_outputs_are_rejected(self):
        for path in (BASE, BASE / "nested", OUTPUT):
            with self.assertRaises(ValueError):
                generate(path)

    def test_export_only_changes_the_firing_part_and_preserves_protected_audio(self):
        before = protected()
        with tempfile.TemporaryDirectory(dir=ROOT / "build/verification", prefix="laser-edge-test-") as temporary:
            directory = Path(temporary)
            with contextlib.redirect_stdout(io.StringIO()):
                generate(directory)
            final, rate = sf.read(directory / "laser.wav")
            pair, _ = sf.read(directory / "laser-before-after.wav")
            self.assertEqual((rate, len(final), len(pair)), (RATE, 11 * RATE, 23 * RATE))
            np.testing.assert_array_equal(final, pair[12 * RATE:])
            old = pair[:11 * RATE]
            np.testing.assert_array_equal(old[:3 * RATE], final[:3 * RATE])
            np.testing.assert_array_equal(old[8 * RATE:], final[8 * RATE:])
            self.assertGreater(np.max(abs(old[4 * RATE:7 * RATE] - final[4 * RATE:7 * RATE])), .01)
            self.assertLess(np.max(abs(final)), .83)
            loop, _ = sf.read(directory / "laser_fire.ogg")
            self.assertLess(abs(loop[0] - loop[-1]), .045)
        self.assertEqual(before, protected())


if __name__ == "__main__":
    unittest.main()
