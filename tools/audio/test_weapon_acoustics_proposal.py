"""Technical audition contracts, separate from artistic and in-game acceptance."""

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import numpy as np
import soundfile as sf

from generate_drone_audio import OUTPUT, RATE, ROOT, measurements
from propose_flight_acoustics import digest, source_layers, steady_sources
from propose_weapon_acoustics import (
    Cue, SPECS, cannon_cues, generate, laser_cues, missile_cues, render_sequence, safe_pair, synthesize,
)


class WeaponAcousticsProposalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.clips = {name: synthesize(name) for name in SPECS}

    def test_samples_are_finite_deterministic_and_within_peak_rms_budgets(self):
        for name, (duration, _, rms, peak) in SPECS.items():
            with self.subTest(name=name):
                pcm = self.clips[name]
                self.assertEqual(pcm.shape, (round(duration * RATE),))
                self.assertTrue(np.isfinite(pcm).all())
                self.assertLessEqual(np.max(abs(pcm)), peak + 1e-6)
                self.assertLessEqual(np.sqrt(np.mean(pcm ** 2)), rms + 1e-6)
                self.assertLess(abs(float(pcm.mean())), .001)
                np.testing.assert_array_equal(pcm, synthesize(name))

    def test_laser_fire_carrier_stays_fixed_in_every_time_window(self):
        pcm = self.clips["laser_fire"]
        for section in np.array_split(pcm, 6):
            frequency = np.fft.rfftfreq(len(section), 1 / RATE)
            spectrum = abs(np.fft.rfft(section)) ** 2
            peak = np.argmax(spectrum * ((frequency >= 1050) & (frequency <= 1250)))
            self.assertAlmostEqual(frequency[peak], 1160, delta=4)
        self.assertLess(measurements(pcm)["energy_above_3khz"], .015)

    def test_loops_wrap_without_silence_and_one_shots_release(self):
        for name, (_, loop, _, _) in SPECS.items():
            with self.subTest(name=name):
                pcm = self.clips[name]
                if loop:
                    self.assertLess(abs(float(pcm[0] - pcm[-1])), .04)
                    rms = np.sqrt(np.mean(pcm ** 2))
                    for section in np.array_split(pcm, max(1, round(len(pcm) / RATE / .04))):
                        self.assertGreater(np.sqrt(np.mean(section ** 2)), rms * .4)
                else:
                    self.assertLess(abs(float(pcm[0])), .001)
                    self.assertLess(abs(float(pcm[-1])), .001)

    def test_cannon_has_four_matching_shot_attacks_and_no_metallic_ring_tail(self):
        pcm = self.clips["autocannon_burst"]
        for shot in range(4):
            begin = round(shot * .1 * RATE)
            attack = pcm[begin + 100:begin + 800]
            tail = pcm[begin + 3500:begin + 4600]
            self.assertGreater(np.sqrt(np.mean(attack ** 2)), np.sqrt(np.mean(tail ** 2)) * 1.5)

    def test_all_salvo_sizes_preserve_ejection_ignition_contact_fuse_and_debris_order(self):
        for count in (3, 4, 5):
            cues = missile_cues(count)
            self.assertEqual(len(cues), count * 6)
            launches = [cue.time for cue in cues if cue.name == "missile_launch"]
            np.testing.assert_allclose(np.diff(launches), .10)
            for index in range(count):
                by_name = {cue.name: cue for cue in cues if cue.actor == index}
                self.assertAlmostEqual(by_name["missile_ignition"].time - by_name["missile_launch"].time, .35)
                self.assertAlmostEqual(by_name["missile_explosion"].time - by_name["missile_impact"].time, .60)
                self.assertAlmostEqual(by_name["missile_debris"].time - by_name["missile_explosion"].time, .35)
                motor = by_name["missile_motor"]
                self.assertAlmostEqual(motor.time + motor.duration, by_name["missile_impact"].time)
                self.assertEqual(by_name["missile_launch"].source, "launcher")
                self.assertEqual(by_name["missile_ignition"].source, "missile")
        for bad in (0, 2, 6):
            with self.assertRaises(ValueError):
                missile_cues(bad)

    def test_preview_timing_matches_current_java_policy(self):
        micro = (ROOT / "src/main/java/jp/morrowgear/drone/MicroMissilePolicy.java").read_text()
        combat = (ROOT / "src/main/java/jp/morrowgear/drone/CombatPolicy.java").read_text()
        for contract in ("SALVO_INTERVAL_TICKS = 2", "EJECTION_TICKS = 6", "IMPACT_FUSE_TICKS = 12"):
            self.assertIn(contract, micro)
        self.assertIn("AUTOCANNON_FIRE_INTERVAL_TICKS = 2", combat)

    def test_laser_one_shots_do_not_repeat_during_sustained_firing(self):
        cues = laser_cues()
        for name in ("laser_charge", "laser_discharge", "laser_fire", "laser_shutdown"):
            self.assertEqual(sum(cue.name == name for cue in cues), 1)
        fire = next(cue for cue in cues if cue.name == "laser_fire")
        shutdown = next(cue for cue in cues if cue.name == "laser_shutdown")
        self.assertAlmostEqual(fire.time + fire.duration, shutdown.time)

    def test_hybrid_adds_gas_core_without_mutating_original_fan_design(self):
        original = source_layers()
        layers, _, mixes = steady_sources(hybrid=True)
        self.assertIn("combustor", layers)
        self.assertIn("turbine", layers)
        for name in ("lift_fans", "compressor", "inlet"):
            np.testing.assert_array_equal(layers[name], original[name])
        for pcm in mixes:
            self.assertLessEqual(np.max(abs(pcm)), .481)
            self.assertGreater(np.sqrt(np.mean(pcm ** 2)), .05)
            self.assertLess(measurements(pcm)["energy_above_3khz"], .025)

    def test_previews_include_flight_and_do_not_clip_for_three_to_five_missiles(self):
        _, _, (_, flight) = steady_sources(hybrid=True)
        for cues, length in ((cannon_cues(), 5), (laser_cues(), 11),
                             *((missile_cues(count), 8) for count in (3, 4, 5))):
            dry = render_sequence(self.clips, cues, length)
            wet = render_sequence(self.clips, cues, length, flight=flight)
            self.assertGreater(np.max(abs(wet - dry)), .005)
            self.assertLess(np.max(abs(wet)), .90)
        pair = safe_pair(np.ones(48000) * .7, np.ones(48000) * .01)
        self.assertAlmostEqual(np.sqrt(np.mean(pair[:RATE] ** 2)), np.sqrt(np.mean(pair[-RATE:] ** 2)))
        self.assertLess(np.max(abs(pair)), .79)

    def test_loop_duration_is_explicit_and_cannot_extend_one_shots(self):
        with self.assertRaises(ValueError):
            render_sequence(self.clips, (Cue(0, "missile_launch", 1, "launcher", duration=3),), 4)
        with self.assertRaises(ValueError):
            render_sequence(self.clips, (Cue(0, "laser_fire", 1, "drone", duration=float("nan")),), 4)

    def test_export_roundtrip_preserves_every_runtime_source(self):
        before = {p.name: digest(p) for p in OUTPUT.glob("*.ogg")}
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            with contextlib.redirect_stdout(io.StringIO()):
                generate(directory)
            report = json.loads((directory / "report.json").read_text())
            self.assertTrue(report["source_assets_unchanged"])
            self.assertEqual(report["status"], "proposal_not_integrated")
            for name, (duration, loop, _, _) in SPECS.items():
                pcm, rate = sf.read(directory / (name + ".ogg"))
                self.assertEqual((pcm.ndim, len(pcm), rate), (1, round(duration * RATE), RATE))
                self.assertLess(np.max(abs(pcm)), .85)
                if loop:
                    self.assertLess(abs(float(pcm[0] - pcm[-1])), .05)
            for name, seconds in (("autocannon", 5), ("laser", 11), ("missile", 8)):
                for suffix, duration in (("candidate", seconds), ("before-after", 2 * seconds + 1)):
                    info = sf.info(directory / (name + "-" + suffix + ".wav"))
                    self.assertEqual((info.channels, info.frames), (1, duration * RATE))
        self.assertEqual(before, {p.name: digest(p) for p in OUTPUT.glob("*.ogg")})

    def test_resource_directory_is_not_an_allowed_proposal_output(self):
        with self.assertRaises(ValueError):
            generate(OUTPUT)


if __name__ == "__main__":
    unittest.main()
