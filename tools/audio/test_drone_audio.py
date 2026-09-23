"""Contracts for the approved runtime selection, not the superseded synthetic set."""

import hashlib
import json
import unittest

import numpy as np
import soundfile as sf

from generate_drone_audio import OUTPUT, RATE, ROOT, generate, measurements
from install_approved_audio import APPROVED
from propose_rotary_laser import SPECS as V3
from propose_weapon_pressure import SPECS as V2


class DroneAudioAssetsTest(unittest.TestCase):
    def test_exact_approved_bytes_and_manifest_are_installed(self):
        manifest = json.loads((OUTPUT.parent / "audio-approved.json").read_text())
        self.assertEqual(set(manifest["assets"]), {row[0] for row in APPROVED})
        for name, source, digest, duration, loop, distance in APPROVED:
            with self.subTest(sound=name):
                self.assertEqual(hashlib.sha256((OUTPUT / (name + ".ogg")).read_bytes()).hexdigest(), digest)
                self.assertEqual(manifest["assets"][name], dict(source=source, sha256=digest,
                    duration=duration, loop=loop, attenuation_distance=distance))

    def test_catalog_template_subtitles_and_spatial_ranges(self):
        catalog = json.loads((OUTPUT.parent / "sounds.json").read_text(encoding="utf-8"))
        template = json.loads((ROOT / "tools/audio/sounds.integration.json").read_text())
        for name, _, _, _, _, distance in APPROVED:
            with self.subTest(sound=name):
                self.assertEqual(template[name], catalog[name])
                entry = catalog[name]["sounds"][0]
                self.assertEqual(entry["name"], "morrowgear_drone:" + name)
                self.assertEqual(entry["attenuation_distance"], distance)
                self.assertFalse(entry.get("stream", False))
                for lang in ("ja_jp", "en_us"):
                    captions = json.loads((OUTPUT.parent / "lang" / (lang + ".json")).read_text(encoding="utf-8"))
                    self.assertTrue(captions[catalog[name]["subtitle"]])
        self.assertEqual(catalog["missile_explosion"]["sounds"][0]["volume"], .24)

    def test_spatial_mono_vorbis_duration_and_decoded_headroom(self):
        for name, source, _, duration, loop, _ in APPROVED:
            with self.subTest(sound=name):
                path = OUTPUT / (name + ".ogg")
                info = sf.info(path)
                self.assertEqual((info.format, info.subtype, info.channels, info.samplerate, info.frames),
                                 ("OGG", "VORBIS", 1, RATE, round(duration * RATE)))
                self.assertGreater(path.stat().st_size, 4000)
                pcm, _ = sf.read(path)
                self.assertTrue(np.isfinite(pcm).all())
                stats = measurements(pcm)
                # Keep each adopted proposal's limits, allowing 6% Vorbis reconstruction overshoot.
                if name.startswith("flight_"):
                    rms_limit, peak_limit = .10, .48
                elif source.startswith("rotary-laser") or name == "laser_fire":
                    rms_limit, peak_limit = V3["autocannon_start" if name == "autocannon_burst" else name][2:]
                else:
                    rms_limit, peak_limit = V2[name][2:]
                self.assertLess(stats["peak"], min(.9, peak_limit * 1.06))
                self.assertLess(stats["rms"], rms_limit * 1.06)
                self.assertGreater(stats["rms"], .01)
                self.assertLess(abs(stats["dc"]), .002)
                if loop and name != "missile_motor":
                    self.assertLess(stats["energy_above_3khz"], .02)

    def test_loop_seams_have_no_impulse_or_silent_gap(self):
        for name, _, _, _, loop, _ in APPROVED:
            if not loop:
                continue
            with self.subTest(sound=name):
                pcm, _ = sf.read(OUTPUT / (name + ".ogg"))
                jump = abs(float(pcm[-1] - pcm[0]))
                self.assertLess(jump, .045)  # Adopted V4 limit, including its 2200 Hz layer.
                self.assertLess(jump, float(np.quantile(abs(np.diff(pcm)), .999)) * 2)
                rms = np.sqrt(np.mean(pcm * pcm))
                for chunk in np.array_split(pcm, round(len(pcm) / (RATE * .04))):
                    self.assertGreater(np.sqrt(np.mean(chunk * chunk)), rms * .40)

    def test_laser_retains_fixed_body_and_subtle_high_edge_without_siren(self):
        pcm, _ = sf.read(OUTPUT / "laser_fire.ogg")
        for chunk in np.array_split(pcm, 8):
            hz = np.fft.rfftfreq(len(chunk), 1 / RATE)
            energy = abs(np.fft.rfft(chunk)) ** 2
            self.assertEqual(hz[np.argmax(energy)], 880)
            edge = energy[abs(hz - 2200) <= 5].sum() / energy.sum()
            self.assertGreater(edge, .005)
            self.assertLess(edge, .08)

    def test_short_impacts_retain_attack_and_cooling_fades(self):
        for name in ("autocannon_start", "missile_impact", "missile_explosion"):
            pcm, _ = sf.read(OUTPUT / (name + ".ogg"))
            self.assertGreater(np.max(abs(pcm)) / np.sqrt(np.mean(pcm * pcm)), 3)
        for name in ("laser_shutdown", "missile_explosion", "missile_debris"):
            pcm, _ = sf.read(OUTPUT / (name + ".ogg"))
            self.assertLess(np.sqrt(np.mean(pcm[-RATE // 10:] ** 2)), .004)
        debris, _ = sf.read(OUTPUT / "missile_debris.ogg")
        self.assertGreater(np.sqrt(np.mean(debris[RATE:RATE * 2] ** 2)), .005)

    def test_legacy_generator_cannot_overwrite_approved_resources(self):
        before = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in OUTPUT.glob("*.ogg")}
        with self.assertRaises(ValueError):
            generate()
        after = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in OUTPUT.glob("*.ogg")}
        self.assertEqual(before, after)

    def test_source_license_notice_is_packaged(self):
        text = (ROOT / "src/main/resources/licenses/AUDIO_SOURCES.md").read_text(encoding="utf-8")
        for sound_id in ("205582", "427595", "162379", "182429"):
            self.assertIn("/sounds/" + sound_id + "/", text)
        self.assertIn("CC0-1.0", text)


if __name__ == "__main__":
    unittest.main()
