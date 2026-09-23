"""Offline proposal checks. These do not certify perceived sound quality."""

import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
import soundfile as sf

from generate_drone_audio import OUTPUT, RATE, measurements
from propose_flight_acoustics import (
    BLADE_COUNT, BLADE_PASS_HZ, PERIOD, SHAFT_HZ, SOURCE_NAMES, audition_ramp,
    digest, generate, reflected_preview, source_layers, steady_sources,
)


class FlightAcousticsProposalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.layers, cls.gain, cls.mixes = steady_sources()

    def test_blade_pass_is_derived_not_an_unrelated_electronic_note(self):
        self.assertEqual(BLADE_PASS_HZ, BLADE_COUNT * SHAFT_HZ)
        hz = np.fft.rfftfreq(len(self.layers["lift_fans"]), 1 / RATE)
        spectrum = abs(np.fft.rfft(self.layers["lift_fans"])) ** 2
        peak = np.argmax(spectrum * ((hz > 300) & (hz < 700)))
        self.assertAlmostEqual(hz[peak], BLADE_PASS_HZ, places=1)

    def test_sources_are_repeatable_finite_and_individually_available(self):
        again = source_layers()
        self.assertEqual(tuple(again), SOURCE_NAMES)
        for name, pcm in self.layers.items():
            with self.subTest(layer=name):
                self.assertEqual(pcm.shape, (round(PERIOD * RATE),))
                self.assertTrue(np.isfinite(pcm).all())
                np.testing.assert_array_equal(pcm, again[name])

    def test_loop_headroom_and_no_silent_sections(self):
        for pcm in self.mixes:
            stats = measurements(pcm)
            self.assertLess(stats["peak"], .50)
            self.assertLess(abs(stats["dc"]), .001)
            self.assertGreater(stats["rms"], .05)
            self.assertLess(stats["rms"], .105)
            self.assertLess(stats["boundary_jump"], .04)
            for section in np.array_split(pcm, round(PERIOD * 25)):
                self.assertGreater(np.sqrt(np.mean(section * section)), stats["rms"] * .40)

    def test_no_continuous_high_frequency_wash(self):
        for pcm in self.mixes:
            self.assertLess(measurements(pcm)["energy_above_3khz"], .025)

    def test_arbitrary_state_blends_remain_audible_and_bounded(self):
        hover, cruise = self.mixes
        for ratio in np.linspace(0, 1, 21):
            pcm = hover * (1 - ratio) + cruise * ratio
            self.assertGreater(np.sqrt(np.mean(pcm * pcm)), .05)
            self.assertLess(np.max(abs(pcm)), .50)

    def test_transition_ramp_preserves_stationary_lift(self):
        hover, cruise = self.mixes
        sequence = audition_ramp(hover, cruise)
        self.assertEqual(len(sequence), 20 * RATE)
        np.testing.assert_array_equal(sequence[:4 * RATE], hover[:4 * RATE])
        np.testing.assert_array_equal(sequence[8 * RATE:14 * RATE], cruise[:6 * RATE])
        self.assertGreater(np.sqrt(np.mean(sequence[-RATE:] ** 2)), .05)

    def test_reflections_are_optional_causal_and_do_not_modify_the_source(self):
        # Impulse after silence distinguishes a delayed reflection from a new oscillator.
        impulse = np.zeros(RATE)
        impulse[RATE // 4] = .1
        original = impulse.copy()
        np.testing.assert_array_equal(reflected_preview(impulse, "open"), impulse)
        for surface in ("apron", "hangar"):
            wet = reflected_preview(impulse, surface)
            self.assertGreater(np.max(abs(wet - impulse)), .001)
            np.testing.assert_array_equal(wet[:RATE // 4], np.zeros(RATE // 4))
        np.testing.assert_array_equal(impulse, original)
        with self.assertRaises(ValueError):
            reflected_preview(impulse, "unknown")

    def test_exports_decode_and_leave_runtime_assets_untouched(self):
        before = {p: digest(p) for p in OUTPUT.glob("*.ogg")}
        with tempfile.TemporaryDirectory() as directory:
            generate(Path(directory))
            report = json.loads((Path(directory) / "report.json").read_text())
            self.assertTrue(report["source_assets_unchanged"])
            for name in ("candidate-hover", "candidate-cruise"):
                pcm, rate = sf.read(Path(directory) / (name + ".ogg"))
                self.assertEqual((rate, pcm.ndim, len(pcm)), (RATE, 1, round(PERIOD * RATE)))
                self.assertLess(np.max(abs(pcm)), .55)
                self.assertLess(measurements(pcm)["boundary_jump"], .04)
            for name, seconds in (("candidate-flight", 20), ("current-then-candidate", 41),
                                  ("open-apron-hangar", 25.5)):
                info = sf.info(Path(directory) / (name + ".wav"))
                self.assertEqual((info.channels, info.samplerate, info.frames), (1, RATE, seconds * RATE))
        self.assertEqual(before, {p: digest(p) for p in OUTPUT.glob("*.ogg")})

    def test_proposal_refuses_to_write_into_runtime_resources(self):
        with self.assertRaises(ValueError):
            generate(OUTPUT)


if __name__ == "__main__":
    unittest.main()
