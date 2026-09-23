"""V2 signal, provenance and non-installation contracts; not listening acceptance."""

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
from propose_flight_acoustics import steady_sources
from propose_weapon_acoustics import Cue
from propose_weapon_pressure import (
    PREVIOUS, SOURCES, SPECS, generate, load_sources, preview_cues,
    protected_hashes, render, segment, synthesize, validate_output,
)


def rms(pcm):
    return float(np.sqrt(np.mean(pcm ** 2)))


class WeaponPressureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.recordings, cls.manifest = load_sources()
        cls.clips = {name: synthesize(name, cls.recordings) for name in SPECS}

    def test_sources_have_verified_hashes_cc0_and_public_preview_provenance(self):
        self.assertEqual(self.manifest["license"], "CC0-1.0")
        self.assertEqual(set(self.recordings), {"gunshot", "rocket", "explosion"})
        for entry in self.manifest["sources"].values():
            self.assertIn("cdn.freesound.org/previews/", entry["download"])
            self.assertTrue(entry["page"].startswith("https://freesound.org/people/"))
            self.assertEqual(len(entry["sha256"]), 64)
            self.assertGreater(len(entry["provenance"]), 30)

    def test_tampered_recording_is_rejected(self):
        with patch("propose_weapon_pressure.digest", return_value="incorrect"):
            with self.assertRaisesRegex(ValueError, "hash mismatch"):
                load_sources()

    def test_shapes_peaks_rms_dc_determinism_and_ends(self):
        for name, (duration, loop, limit, peak) in SPECS.items():
            with self.subTest(name=name):
                pcm = self.clips[name]
                self.assertEqual(pcm.shape, (round(duration * RATE),))
                self.assertTrue(np.isfinite(pcm).all())
                self.assertLessEqual(rms(pcm), limit + 1e-6)
                self.assertLessEqual(np.max(abs(pcm)), peak + 1e-6)
                self.assertGreater(rms(pcm), .015)
                self.assertLess(abs(float(pcm.mean())), .003)
                np.testing.assert_array_equal(pcm, synthesize(name, self.recordings))
                if not loop:
                    self.assertLess(abs(float(pcm[0])), 1e-6)
                    self.assertLess(abs(float(pcm[-1])), 1e-6)

    def test_decoded_loops_are_continuous_without_silent_seams(self):
        for name in ("laser_fire", "missile_motor"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "loop.ogg"
                sf.write(path, self.clips[name], RATE, format="OGG", subtype="VORBIS")
                pcm, rate = sf.read(path)
                self.assertEqual(rate, RATE)
                # The seam must be no larger than ordinary adjacent waveform motion.
                self.assertLess(abs(pcm[0] - pcm[-1]), max(.03, np.quantile(abs(np.diff(pcm)), .99)))
                for section in np.array_split(pcm, round(len(pcm) / RATE / .04)):
                    self.assertGreater(rms(section), rms(pcm) * .35)

    def test_cannon_has_four_pressure_attacks_not_continuous_white_noise(self):
        for index in range(4):
            start = round(index * .10 * RATE)
            pcm = self.clips["autocannon_burst"]
            attack = pcm[start + 200:start + 1200]
            tail = pcm[start + 3600:start + 4500]
            self.assertGreater(rms(attack), rms(tail) * 1.8)

    def test_explosion_has_attack_and_short_broad_decay_not_a_sine_note(self):
        pcm = self.clips["missile_explosion"]
        self.assertGreater(rms(pcm[:RATE // 2]), rms(pcm[RATE:2 * RATE]) * 3)
        spectrum = abs(np.fft.rfft(pcm[:RATE] * np.hanning(RATE))) ** 2
        self.assertLess(spectrum.max() / spectrum.sum(), .10)
        self.assertLess(rms(pcm[-RATE // 2:]), rms(pcm) * .02)

    def test_laser_is_broadband_not_dominated_by_single_high_pitch(self):
        pcm = self.clips["laser_fire"]
        spectrum = abs(np.fft.rfft(pcm)) ** 2
        freq = np.fft.rfftfreq(len(pcm), 1 / RATE)
        self.assertLess(spectrum.max() / spectrum.sum(), .06)
        self.assertLess(spectrum[freq > 4000].sum() / spectrum.sum(), .09)
        # Stable spectral envelope across windows, without the earlier up/down siren.
        centroids = []
        for part in np.array_split(pcm, 6):
            energy = abs(np.fft.rfft(part * np.hanning(len(part)))) ** 2
            hz = np.fft.rfftfreq(len(part), 1 / RATE)
            centroids.append(float(np.sum(energy * hz) / energy.sum()))
        self.assertLess(max(centroids) / min(centroids), 1.25)

    def test_salvos_single_round_and_source_positions_preserve_fuse_contract(self):
        single = preview_cues("missile-single")
        self.assertEqual(len(single), 6)
        self.assertEqual({c.actor for c in single}, {0})
        for count in (3, 4, 5):
            cues = preview_cues("missile-salvo", count)
            self.assertEqual(len(cues), count * 6)
            for index in range(count):
                by_name = {c.name: c for c in cues if c.actor == index}
                launch = by_name["missile_launch"]
                impact = by_name["missile_impact"]
                self.assertAlmostEqual(by_name["missile_ignition"].time - launch.time, .35)
                self.assertAlmostEqual(by_name["missile_explosion"].time - impact.time, .60)
                self.assertAlmostEqual(by_name["missile_debris"].time - impact.time, .95)
                motor = by_name["missile_motor"]
                self.assertAlmostEqual(motor.time + motor.duration, impact.time)
                self.assertEqual(launch.source, "launcher")
                self.assertEqual(motor.source, "missile")

    def test_three_to_five_missiles_and_flight_mix_do_not_clip(self):
        _, _, (_, flight) = steady_sources(hybrid=True)
        for count in (3, 4, 5):
            cues = preview_cues("missile-salvo", count)
            pcm = render(self.clips, cues, 8, flight)
            self.assertTrue(np.isfinite(pcm).all())
            # Export uses uniform gain, never hard clipping or compression pumping.
            gain = min(1, .84 / max(np.max(abs(pcm)), 1e-9))
            self.assertLessEqual(np.max(abs(pcm * gain)), .840001)
            self.assertGreater(gain, .30)

    def test_malformed_cues_and_recording_segments_fail_explicitly(self):
        for duration in (-1, float("nan")):
            with self.assertRaises(ValueError):
                render(self.clips, (), duration)
        for cue in (Cue(-1, "laser_fire", 1, "drone"),
                    Cue(0, "missile_launch", 1, "drone", duration=2),
                    Cue(0, "laser_fire", 1, "drone", duration=float("inf"))):
            with self.assertRaises(ValueError):
                render(self.clips, (cue,), 4)
        with self.assertRaises(ValueError):
            segment(self.recordings["gunshot"], 1, 5)

    def test_proposal_cannot_target_resources_previous_audio_or_installed_mods(self):
        for path in (OUTPUT, SOURCES, PREVIOUS, PREVIOUS / "nested", ROOT / "build/verification"):
            with self.assertRaises(ValueError):
                validate_output(path)

    def test_export_preserves_approved_flight_and_game_audio_and_roundtrips(self):
        before = protected_hashes()
        root = ROOT / "build/verification"
        root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root, prefix="weapon-pressure-test-") as temporary:
            path = Path(temporary)
            with contextlib.redirect_stdout(io.StringIO()):
                generate(path)
            report = json.loads((path / "report.json").read_text())
            self.assertEqual(report["status"], "audition_not_integrated")
            self.assertTrue(report["runtime_and_approved_flight_unchanged"])
            for name, (length, _, _, _) in SPECS.items():
                pcm, rate = sf.read(path / (name + ".ogg"))
                self.assertEqual((pcm.ndim, rate, len(pcm)), (1, RATE, round(length * RATE)))
                self.assertLess(np.max(abs(pcm)), .95)
            for kind, seconds in (("autocannon", 5), ("laser", 11), ("missile-single", 7), ("missile-salvo", 8)):
                dry, _ = sf.read(path / (kind + ".wav"))
                mixed, _ = sf.read(path / (kind + "-with-flight.wav"))
                self.assertEqual(len(dry), seconds * RATE)
                self.assertLess(np.max(abs(mixed)), .85)
                self.assertGreater(np.max(abs(dry - mixed)), .003)
        self.assertEqual(before, protected_hashes())


if __name__ == "__main__":
    unittest.main()
