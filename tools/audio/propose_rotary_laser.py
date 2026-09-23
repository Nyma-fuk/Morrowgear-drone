"""V3 audition only: recorded rotary-cannon body and a clearly pitched stable beam.

No gameplay fire-rate change, asset installation or rewriting earlier auditions.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict
import hashlib
import json
from pathlib import Path

import numpy as np
import soundfile as sf

from generate_drone_audio import RATE, ROOT, bandlimit, measurements, noise, ring, smoothstep, timeline, window
from propose_flight_acoustics import digest, fade_edges, steady_sources, unit_rms
from propose_weapon_acoustics import Cue, safe_pair
from propose_weapon_pressure import SOURCES, filter_one_shot, periodic_recording, protected_hashes, put, segment


DEFAULT_OUTPUT = ROOT / "build/verification/rotary-laser-v3"
V2 = ROOT / "build/verification/weapon-pressure-v2"
SPECS = {
    "autocannon_start": (.12, False, .13, .64),
    "autocannon_fire": (1.20, True, .15, .70),
    "autocannon_stop": (.38, False, .10, .52),
    "laser_charge": (2.75, False, .075, .40),
    "laser_discharge": (.42, False, .13, .64),
    "laser_fire": (1.60, True, .095, .43),
    "laser_shutdown": (2.20, False, .055, .40),
}
LASER_HZ = 880.0


def load_rotary():
    manifest = json.loads((SOURCES / "rotary-source.json").read_text(encoding="utf-8"))
    path = SOURCES / manifest["file"]
    if digest(path) != manifest["sha256"]:
        raise ValueError("Rotary recording hash mismatch")
    # Decode before cropping so MP3 frame seek rounding cannot change the onset.
    pcm, rate = sf.read(path, always_2d=True)
    if rate != RATE or len(pcm) != round(89.3575 * RATE) or not np.isfinite(pcm).all():
        raise ValueError("Incomplete or unexpected rotary recording")
    mono = pcm.mean(axis=1)
    return filter_one_shot(mono, 50, 4800), manifest


def beam_core(phase):
    # Stable, phase-locked harmonics instead of detuned beating or a pitch siren.
    return (.68 * np.sin(phase + .72 * np.sin(phase / 4))
            + .17 * np.sin(phase / 4) + .10 * np.sin(phase / 8)
            + .10 * np.sin(phase * 2))


def charge_frequency(t):
    return 110 + (LASER_HZ - 110) * smoothstep(t / 2.50)


def synthesize(name, rotary):
    duration, loop, rms_limit, peak_limit = SPECS[name]
    t = timeline(duration)
    seed = int.from_bytes(hashlib.sha256(("rotary-laser-v3:" + name).encode()).digest()[:8], "little")
    rng = np.random.default_rng(seed)
    if name == "autocannon_start":
        pcm = segment(rotary, .79, duration) * smoothstep(t / .010)
    elif name == "autocannon_fire":
        pcm = periodic_recording(rotary, 1.12, duration, .08)
        # Preserve the recorded sustained firing texture, not four shotgun hits.
        pcm = .80 * unit_rms(pcm) + .20 * unit_rms(bandlimit(pcm, 260, 2900))
    elif name == "autocannon_stop":
        pcm = segment(rotary, 2.45, duration) * np.exp(-t * 7)
    elif name == "laser_charge":
        phase = 2 * np.pi * np.cumsum(charge_frequency(t)) / RATE
        phase -= phase[-1]
        envelope = smoothstep(t / .18) * (.18 + .82 * smoothstep(t / 2.40))
        pcm = envelope * beam_core(phase)
        pcm += .025 * noise(rng, len(t), 200, 1300) * envelope
    elif name == "laser_discharge":
        pcm = .60 * noise(rng, len(t), 280, 2200) * window(t, 0, .0008, 80)
        pcm += ring(t, .003, 110, 25, .65)
        pcm += .20 * beam_core(2 * np.pi * LASER_HZ * t) * window(t, .004, .002, 17)
    elif name == "laser_fire":
        pcm = beam_core(2 * np.pi * LASER_HZ * t)
        pcm += .025 * noise(rng, len(t), 1300, 3800)
    elif name == "laser_shutdown":
        # One isolation click, then a short purge; no falling 'pew' pitch.
        pcm = .14 * noise(rng, len(t), 550, 2600) * window(t, 0, .001, 140)
        pcm += ring(t, .025, 1650, 36, .055)
        pcm += .14 * noise(rng, len(t), 1000, 3600) * window(t, .16, .055, 3.2)
    else:
        raise ValueError("Unknown audition clip")
    if loop:
        pcm = bandlimit(pcm, 40, 5200)
        pcm -= pcm.mean()
    else:
        pcm = filter_one_shot(pcm, 40, 5200)
        pcm *= smoothstep(t / .0005) * smoothstep((duration - t - 1 / RATE) / .015)
    gain = min(rms_limit / max(1e-9, np.sqrt(np.mean(pcm ** 2))), peak_limit / max(1e-9, np.max(abs(pcm))))
    return (pcm * gain).astype(np.float32)


def cannon_cues():
    cues = []
    for start, duration in ((.40, .80), (2.40, 2.40)):
        cues += [Cue(start, "autocannon_start", .80, "drone"),
                 Cue(start + .045, "autocannon_fire", .90, "drone", duration=duration),
                 Cue(start + .045 + duration, "autocannon_stop", .70, "drone")]
    return tuple(cues)


def laser_cues():
    return (Cue(.30, "laser_charge", .78, "drone"),
            Cue(3.05, "laser_discharge", .90, "drone"),
            Cue(3.05, "laser_fire", .88, "drone", duration=4.50),
            Cue(7.55, "laser_shutdown", .60, "drone"))


def render(clips, cues, duration, flight=None):
    if not np.isfinite(duration) or duration <= 0:
        raise ValueError("Invalid duration")
    pcm = np.zeros(round(duration * RATE))
    if flight is not None:
        pcm += np.resize(flight, len(pcm)) * .23
    for cue in cues:
        source = clips[cue.name]
        if cue.duration is not None:
            if not SPECS[cue.name][1] or not np.isfinite(cue.duration) or cue.duration <= 0:
                raise ValueError("Only positive finite loop durations are valid")
            source = fade_edges(np.resize(source, round(cue.duration * RATE)), .012)
        put(pcm, source, cue.time, cue.gain)
    return fade_edges(pcm)


def snapshot():
    result = protected_hashes()
    for path in sorted(V2.glob("*")):
        if path.is_file():
            result[str(path)] = digest(path)
    return result


def validate_output(directory):
    resolved = directory.resolve()
    root = (ROOT / "build/verification").resolve()
    forbidden = [V2.resolve(), (ROOT / "build/verification/weapon-acoustics-proposal").resolve(),
                 (ROOT / "build/verification/flight-acoustics-proposal").resolve()]
    if root not in resolved.parents or any(resolved == path or path in resolved.parents for path in forbidden):
        raise ValueError("Output must be a separate verification directory")
    return resolved


def generate(directory=DEFAULT_OUTPUT):
    directory = validate_output(directory)
    before = snapshot()
    rotary, manifest = load_rotary()
    clips, records = {}, {}
    directory.mkdir(parents=True, exist_ok=True)
    for name in SPECS:
        path = directory / (name + ".ogg")
        sf.write(path, synthesize(name, rotary), RATE, format="OGG", subtype="VORBIS")
        pcm, rate = sf.read(path)
        if rate != RATE or pcm.ndim != 1 or not np.isfinite(pcm).all() or np.max(abs(pcm)) > .9:
            raise ValueError("Invalid decoded audio: " + name)
        clips[name] = pcm
        records[name] = {"sha256": digest(path), "duration": len(pcm) / RATE, **measurements(pcm)}
    _, _, (_, flight) = steady_sources(hybrid=True)
    previews = {}
    for kind, cues, seconds in (("gatling", cannon_cues(), 6), ("laser", laser_cues(), 11)):
        dry = render(clips, cues, seconds)
        mixed = render(clips, cues, seconds, flight)
        gain = min(1, .82 / max(np.max(abs(dry)), np.max(abs(mixed)), 1e-9))
        for suffix, pcm in (("", dry), ("-with-flight", mixed)):
            sf.write(directory / (kind + suffix + ".wav"), pcm * gain, RATE, subtype="PCM_16")
        previous = V2 / ("autocannon.wav" if kind == "gatling" else "laser.wav")
        if previous.exists():
            old, rate = sf.read(previous)
            if rate != RATE or old.ndim != 1:
                raise ValueError("Unexpected earlier audition format")
            sf.write(directory / (kind + "-v2-v3.wav"), safe_pair(old, dry), RATE, subtype="PCM_16")
        previews[kind] = {"duration": seconds, "cues": [asdict(c) for c in cues],
                          "gain": gain, **measurements(dry * gain)}
    if before != snapshot():
        raise RuntimeError("Flight, missile or previous audio was changed")
    report = {"status": "audition_not_integrated", "source": manifest, "records": records,
              "previews": previews, "protected_audio_unchanged": True, "protected_hashes": before,
              "limits": ["No hearing or in-game acceptance", "A-10 inspired, not an F-35 recording",
                         "Acoustic firing density is not gameplay projectile rate; damage and ammo unchanged",
                         "Stable fictional laser apparatus, not measured plasma acoustics",
                         "Dry mono; no target impacts, source motion or distance simulation",
                         "RMS comparisons are not perceptual loudness matching"]}
    (directory / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "clips": len(clips),
                      "protected_audio_unchanged": True, "output": str(directory)}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    generate(parser.parse_args().output)
