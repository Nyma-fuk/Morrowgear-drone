"""V3 audition checks; player listening remains an explicit separate gate."""

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np
import soundfile as sf

from generate_drone_audio import OUTPUT, RATE, ROOT
from propose_weapon_acoustics import Cue
from propose_rotary_laser import (
    LASER_HZ, SPECS, V2, cannon_cues, charge_frequency, generate, laser_cues,
    load_rotary, render, snapshot, synthesize, validate_output,
)


def rms(pcm):
    return float(np.sqrt(np.mean(pcm ** 2)))


class RotaryLaserTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rotary, cls.manifest = load_rotary()
        cls.clips = {name: synthesize(name, cls.rotary) for name in SPECS}

    def test_recording_identity_and_license_and_no_unverified_f35_claim(self):
        self.assertEqual(self.manifest["license"], "CC0-1.0")
        self.assertEqual(self.manifest["title"], "A-10.ogg")
        self.assertEqual(len(self.rotary), round(89.3575 * RATE))
        self.assertIn("No F-35 recording", self.manifest["edit"])
        with patch("propose_rotary_laser.digest", return_value="wrong"):
            with self.assertRaises(ValueError):
                load_rotary()

    def test_deterministic_mono_finite_limits_and_silent_one_shot_ends(self):
        for name, (duration, loop, limit, peak) in SPECS.items():
            with self.subTest(name=name):
                pcm = self.clips[name]
                self.assertEqual(pcm.shape, (round(duration * RATE),))
                self.assertTrue(np.isfinite(pcm).all())
                self.assertLessEqual(rms(pcm), limit + 1e-6)
                self.assertGreater(rms(pcm), .025)
                self.assertLessEqual(np.max(abs(pcm)), peak + 1e-6)
                self.assertLess(abs(float(pcm.mean())), .002)
                np.testing.assert_array_equal(pcm, synthesize(name, self.rotary))
                if not loop:
                    self.assertLess(max(abs(pcm[0]), abs(pcm[-1])), .00001)

    def test_rotary_body_has_continuous_density_without_ten_hz_shot_gaps(self):
        pcm = self.clips["autocannon_fire"]
        # Unlike V2, the sustained recording must not decay to a gap every 100 ms.
        for part in np.array_split(pcm, 60):
            self.assertGreater(rms(part), rms(pcm) * .33)
        env = np.array([rms(part) for part in np.array_split(pcm, 120)])
        self.assertLess(np.std(env) / np.mean(env), .50)

    def test_laser_charge_rises_once_and_holds_at_fire_frequency(self):
        t = np.arange(round(2.75 * RATE)) / RATE
        hz = charge_frequency(t)
        self.assertTrue(np.all(np.diff(hz) >= 0))
        self.assertEqual(hz[0], 110)
        np.testing.assert_allclose(hz[-RATE // 5:], LASER_HZ)

    def test_laser_fire_fixed_pitch_and_low_hiss_not_a_siren(self):
        pcm = self.clips["laser_fire"]
        for part in np.array_split(pcm, 8):
            p = abs(np.fft.rfft(part * np.hanning(len(part)))) ** 2
            freq = np.fft.rfftfreq(len(part), 1 / RATE)
            self.assertAlmostEqual(freq[np.argmax(p)], LASER_HZ, delta=5)
            self.assertLess(p[freq > 3000].sum() / p.sum(), .015)
        envelope = np.array([rms(part) for part in np.array_split(pcm, 80)])
        self.assertLess(envelope.std() / envelope.mean(), .06)

    def test_loop_encode_decode_seams_and_nonzero_windows(self):
        for name in ("autocannon_fire", "laser_fire"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "clip.ogg"
                sf.write(path, self.clips[name], RATE, format="OGG", subtype="VORBIS")
                pcm, rate = sf.read(path)
                self.assertEqual(rate, RATE)
                self.assertLess(abs(pcm[0] - pcm[-1]), max(.03, np.quantile(abs(np.diff(pcm)), .995)))
                for part in np.array_split(pcm, round(len(pcm) / RATE / .02)):
                    self.assertGreater(rms(part), rms(pcm) * .30)

    def test_cues_have_one_start_and_stop_per_burst_no_restarted_charge(self):
        cues = cannon_cues()
        for offset in (0, 3):
            start, fire, stop = cues[offset:offset + 3]
            self.assertAlmostEqual(fire.time + fire.duration, stop.time)
            self.assertLess(start.time, fire.time)
        charge, release, beam, cool = laser_cues()
        self.assertAlmostEqual(charge.time + SPECS[charge.name][0], beam.time)
        self.assertEqual(release.time, beam.time)
        self.assertAlmostEqual(beam.time + beam.duration, cool.time)
        self.assertEqual(sum(c.name == "laser_charge" for c in laser_cues()), 1)

    def test_rejects_one_shot_looping_invalid_duration_and_negative_cues(self):
        for cue in (Cue(0, "autocannon_stop", 1, "drone", duration=3),
                    Cue(-1, "laser_fire", 1, "drone"),
                    Cue(0, "laser_fire", 1, "drone", duration=float("nan"))):
            with self.assertRaises(ValueError):
                render(self.clips, (cue,), 5)
        with self.assertRaises(ValueError):
            render(self.clips, (), -1)

    def test_never_targets_runtime_flight_or_previous_weapon_auditions(self):
        for path in (OUTPUT, V2, V2 / "nested", ROOT / "build/verification/flight-acoustics-proposal"):
            with self.assertRaises(ValueError):
                validate_output(path)

    def test_export_protects_missile_flight_and_runtime_with_roundtrip_checks(self):
        before = snapshot()
        with tempfile.TemporaryDirectory(dir=ROOT / "build/verification", prefix="rotary-test-") as temporary:
            directory = Path(temporary)
            with contextlib.redirect_stdout(io.StringIO()):
                generate(directory)
            report = json.loads((directory / "report.json").read_text())
            self.assertTrue(report["protected_audio_unchanged"])
            self.assertEqual(report["status"], "audition_not_integrated")
            for name, (duration, _, _, _) in SPECS.items():
                pcm, rate = sf.read(directory / (name + ".ogg"))
                self.assertEqual((pcm.ndim, rate, len(pcm)), (1, RATE, round(duration * RATE)))
                self.assertLess(np.max(abs(pcm)), .9)
            for kind, seconds in (("gatling", 6), ("laser", 11)):
                dry, _ = sf.read(directory / (kind + ".wav"))
                mixed, _ = sf.read(directory / (kind + "-with-flight.wav"))
                self.assertEqual(len(dry), seconds * RATE)
                self.assertLess(np.max(abs(mixed)), .83)
                self.assertGreater(np.max(abs(dry - mixed)), .003)
        self.assertEqual(before, snapshot())


if __name__ == "__main__":
    unittest.main()
