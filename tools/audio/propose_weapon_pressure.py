"""Offline V2: CC0 recording layers, pressure transients and fictional plasma texture.

Never writes runtime assets. V1 and the approved hybrid flight audition are kept.
Dependencies remain numpy and soundfile; no download occurs during generation.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, replace
import hashlib
import json
from pathlib import Path

import numpy as np
import soundfile as sf

from generate_drone_audio import OUTPUT, RATE, ROOT, bandlimit, measurements, noise, smoothstep, timeline, tone, window
from propose_flight_acoustics import digest, fade_edges, steady_sources
from propose_weapon_acoustics import cannon_cues, laser_cues, missile_cues, safe_pair


SOURCES = Path(__file__).parent / "source-recordings"
DEFAULT_OUTPUT = ROOT / "build/verification/weapon-pressure-v2"
PREVIOUS = ROOT / "build/verification/weapon-acoustics-proposal"
# Duration, loop, RMS ceiling, peak ceiling. Not Minecraft volume settings.
SPECS = {
    "autocannon_burst": (.40, False, .145, .70),
    "autocannon_impact": (.23, False, .065, .42),
    "laser_charge": (2.75, False, .09, .44),
    "laser_discharge": (.55, False, .14, .67),
    "laser_fire": (1.50, True, .10, .46),
    "laser_shutdown": (2.65, False, .075, .47),
    "laser_hit": (.45, False, .065, .42),
    "missile_launch": (.20, False, .12, .62),
    "missile_ignition": (.40, False, .16, .67),
    "missile_motor": (1.60, True, .12, .53),
    "missile_impact": (.25, False, .09, .52),
    "missile_explosion": (3.20, False, .17, .78),
    "missile_debris": (3.20, False, .055, .39),
}


def filter_one_shot(pcm, low, high):
    # FFT filtering otherwise wraps the opening blast into the end of a one-shot.
    padding = RATE // 4
    padded = np.pad(pcm, (padding, padding))
    return bandlimit(padded, low, high)[padding:padding + len(pcm)]


def load_sources():
    manifest = json.loads((SOURCES / "sources.json").read_text(encoding="utf-8"))
    result = {}
    for name, entry in manifest["sources"].items():
        path = SOURCES / entry["file"]
        if digest(path) != entry["sha256"]:
            raise ValueError("Recording hash mismatch: " + name)
        pcm, rate = sf.read(path, always_2d=True)
        pcm = pcm.mean(axis=1)
        if not np.isfinite(pcm).all() or len(pcm) < rate:
            raise ValueError("Invalid source recording: " + name)
        # Upsampling only, with output bandwidth below 8 kHz; no pitch shift.
        if rate != RATE:
            pcm = np.interp(np.arange(round(len(pcm) * RATE / rate)) * rate / RATE,
                            np.arange(len(pcm)), pcm)
        pcm = filter_one_shot(pcm, 30, 7800)
        result[name] = pcm / max(1e-9, np.max(abs(pcm)))
    return result, manifest


def segment(source, start, duration):
    begin, count = round(start * RATE), round(duration * RATE)
    if begin < 0 or count < 1 or begin + count > len(source):
        raise ValueError("Recording segment exceeds available samples")
    return source[begin:begin + count].copy()


def put(output, clip, start, gain=1.0):
    if not np.isfinite(start) or start < 0 or not np.isfinite(gain):
        raise ValueError("Invalid cue position or gain")
    begin = round(start * RATE)
    count = min(len(clip), len(output) - begin)
    if count > 0:
        output[begin:begin + count] += clip[:count] * gain


def periodic_recording(source, start, duration, crossfade=.12):
    count, overlap = round(duration * RATE), round(crossfade * RATE)
    pcm = segment(source, start, duration + crossfade)
    result = pcm[:count].copy()
    weight = smoothstep(np.arange(overlap) / (overlap - 1))
    result[:overlap] = pcm[count:count + overlap] * (1 - weight) + pcm[:overlap] * weight
    return result


def pressure(t, start, width, gain):
    # A short bipolar pulse rather than an audible pitched kick-drum oscillator.
    x = np.maximum(0, t - start) / width
    return gain * (t >= start) * smoothstep(x / .04) * (1 - x) * np.exp(-x)


def plasma_texture(t, rng):
    # Broadband, inharmonic texture: no pitch LFO and no repeated siren envelope.
    body = noise(rng, len(t), 170, 650)
    edge = noise(rng, len(t), 900, 2700)
    detail = noise(rng, len(t), 2200, 5100)
    flicker = np.tanh(noise(rng, len(t), 9, 45))
    return .38 * body + .34 * edge * (1 + .08 * flicker) + .065 * detail


def make_raw(name, rng, recordings):
    duration, loop, _, _ = SPECS[name]
    t = timeline(duration)
    pcm = np.zeros_like(t)
    gun = recordings["gunshot"]
    rocket = recordings["rocket"]
    blast = recordings["explosion"]
    if name == "autocannon_burst":
        shot_t = timeline(.1)
        shot = segment(gun, .055, .1) * np.exp(-shot_t * 23)
        shot += .15 * pressure(shot_t, 0, .004, 1)
        for index in range(4):
            put(pcm, shot, index * .10, (1.0, .96, 1.02, .97)[index])
        pcm += .012 * noise(rng, len(t), 160, 850)
    elif name == "autocannon_impact":
        pcm = .6 * segment(blast, .16, duration) * window(t, 0, .001, 31)
        pcm += .13 * noise(rng, len(t), 1300, 6000) * window(t, .002, .001, 110)
    elif name == "missile_launch":
        pcm = .70 * segment(gun, .055, duration) * np.exp(-t * 33)
        pcm += .18 * noise(rng, len(t), 200, 3500) * window(t, 0, .001, 48)
        pcm += pressure(t, .001, .009, .35)
    elif name == "missile_ignition":
        pcm = segment(rocket, .40, duration) * smoothstep(t / .007)
        pcm += .10 * noise(rng, len(t), 1100, 6800) * window(t, .002, .001, 70)
        pcm += pressure(t, .001, .016, .28)
    elif name == "missile_motor":
        pcm = periodic_recording(rocket, 2.0, duration)
        pcm = bandlimit(pcm, 90, 4800)
        pcm += .055 * noise(rng, len(t), 1200, 4500)
    elif name == "missile_impact":
        pcm = .55 * segment(gun, .056, duration) * np.exp(-t * 55)
        pcm += .25 * noise(rng, len(t), 600, 3900) * window(t, .009, .001, 85)
        pcm += pressure(t, 0, .003, .30)
    elif name == "missile_explosion":
        # The source has a sustained 67 Hz resonance; avoid another pitched bass note.
        frequencies = np.fft.rfftfreq(len(blast), 1 / RATE)
        response = 1 - .68 * np.exp(-.5 * ((frequencies - 67) / 9) ** 2)
        shaped = np.fft.irfft(np.fft.rfft(blast) * response, n=len(blast))
        put(pcm, shaped, 0, 1.0)
        pcm += .11 * noise(rng, len(t), 45, 280) * window(t, .014, .014, 3.8)
        pcm += .14 * noise(rng, len(t), 400, 4300) * window(t, 0, .0005, 140)
        pcm += pressure(t, .002, .025, .45)
    elif name == "missile_debris":
        pcm = .012 * noise(rng, len(t), 150, 2200) * window(t, 0, .06, 1.6)
        for start in sorted(rng.uniform(.01, 2.60, 44)):
            # Aperiodic, broad stone impacts; deliberately no metallic sine pings.
            dry = noise(rng, len(t), rng.uniform(250, 900), rng.uniform(2100, 5000))
            pcm += rng.uniform(.06, .17) * np.exp(-start * .7) * dry * window(t, start, .0005, rng.uniform(100, 260))
    elif name == "laser_charge":
        progress = smoothstep(t / 2.65)
        envelope = smoothstep(t / .22) * (.20 + .80 * progress)
        compression = noise(rng, len(t), 80, 550)
        energized = noise(rng, len(t), 550, 1800)
        phase = 2 * np.pi * np.cumsum(110 + 200 * progress) / RATE
        pcm = envelope * (.28 * compression * (1 - .5 * progress)
                          + .19 * energized * progress + .07 * np.sin(phase))
        pcm += .06 * pressure(t, 0, .003, 1)
    elif name == "laser_discharge":
        pcm = plasma_texture(t, rng) * window(t, .003, .002, 8)
        pcm += .45 * noise(rng, len(t), 400, 6000) * window(t, 0, .0005, 150)
        pcm += pressure(t, .001, .010, .70)
    elif name == "laser_fire":
        pcm = plasma_texture(t, rng)
        # Quiet fixed edge to retain the cutting identity without a pure-tone whistle.
        pcm += .065 * tone(t, 1180) + .022 * tone(t, 1760)
    elif name == "laser_shutdown":
        pcm = .16 * noise(rng, len(t), 1400, 5500) * window(t, .14, .055, 2.5)
        pcm += .12 * noise(rng, len(t), 100, 750) * window(t, .10, .05, 3.0)
        pcm += .12 * noise(rng, len(t), 600, 4300) * window(t, .018, .001, 110)
        pcm += pressure(t, 0, .003, .24)
    elif name == "laser_hit":
        pcm = .16 * noise(rng, len(t), 1300, 5800) * window(t, 0, .004, 11)
        for start in rng.uniform(.01, .32, 11):
            pcm += .06 * noise(rng, len(t), 750, 5500) * window(t, start, .0006, 150)
    else:
        raise ValueError("Unknown weapon clip: " + name)
    return pcm


def synthesize(name, recordings):
    duration, loop, rms, peak = SPECS[name]
    seed = int.from_bytes(hashlib.sha256(("pressure-v2:" + name).encode()).digest()[:8], "little")
    pcm = make_raw(name, np.random.default_rng(seed), recordings)
    if not loop:
        pcm = filter_one_shot(pcm, 35, 6800)
        t = timeline(duration)
        pcm *= smoothstep(t / .00025) * smoothstep((duration - t - 1 / RATE) / .014)
    else:
        pcm = bandlimit(pcm, 35, 6800)
        pcm -= pcm.mean()
    gain = min(rms / max(1e-9, np.sqrt(np.mean(pcm ** 2))), peak / max(1e-9, np.max(abs(pcm))))
    return (pcm * gain).astype(np.float32)


def preview_cues(kind, count=4):
    if kind.startswith("missile"):
        cues = missile_cues(count)
        if kind == "missile-single":
            cues = tuple(c for c in cues if c.actor == 0)
        gains = {"missile_launch": .80, "missile_ignition": .62, "missile_motor": .42,
                 "missile_impact": .55, "missile_explosion": .92, "missile_debris": .35}
        return tuple(replace(c, gain=gains[c.name]) for c in cues)
    if kind == "laser":
        return tuple(replace(c, gain={"laser_charge": .65, "laser_discharge": .9,
                                     "laser_fire": .9, "laser_shutdown": .7, "laser_hit": .28}[c.name])
                     for c in laser_cues())
    if kind == "autocannon":
        return tuple(replace(c, gain=.94 if c.source == "drone" else .18) for c in cannon_cues())
    raise ValueError("Unknown sequence: " + kind)


def render(clips, cues, duration, flight=None):
    if not np.isfinite(duration) or duration <= 0:
        raise ValueError("Invalid sequence duration")
    pcm = np.zeros(round(duration * RATE))
    if flight is not None:
        pcm += np.resize(flight, len(pcm)) * .23
    for cue in cues:
        source = clips[cue.name]
        if cue.duration is not None:
            if not SPECS[cue.name][1] or not np.isfinite(cue.duration) or cue.duration <= 0:
                raise ValueError("Only valid loops can repeat")
            source = fade_edges(np.resize(source, round(cue.duration * RATE)), .012)
        put(pcm, source, cue.time, cue.gain)
    return fade_edges(pcm)


def protected_hashes():
    paths = list(OUTPUT.glob("*.ogg")) + list(PREVIOUS.glob("hybrid-*"))
    return {str(p): digest(p) for p in sorted(paths)}


def validate_output(directory):
    directory = directory.resolve()
    # Restrict to descendants of verification, protecting sources, previous audio and installed mods.
    root = (ROOT / "build/verification").resolve()
    if root not in directory.parents or directory == PREVIOUS.resolve() or PREVIOUS.resolve() in directory.parents:
        raise ValueError("Write only a new build/verification proposal directory")
    return directory


def generate(directory=DEFAULT_OUTPUT):
    directory = validate_output(directory)
    before = protected_hashes()
    recordings, manifest = load_sources()
    directory.mkdir(parents=True, exist_ok=True)
    clips, records = {}, {}
    for name in SPECS:
        path = directory / (name + ".ogg")
        sf.write(path, synthesize(name, recordings), RATE, format="OGG", subtype="VORBIS")
        pcm, rate = sf.read(path)
        if rate != RATE or pcm.ndim != 1 or not np.isfinite(pcm).all() or np.max(abs(pcm)) >= .95:
            raise ValueError("Invalid decoded clip: " + name)
        clips[name] = pcm
        records[name] = {"sha256": digest(path), "duration": len(pcm) / RATE, **measurements(pcm)}
    _, _, (_, flight) = steady_sources(hybrid=True)
    previews = {}
    for kind, length in (("autocannon", 5), ("laser", 11), ("missile-single", 7), ("missile-salvo", 8)):
        cues = preview_cues(kind)
        dry = render(clips, cues, length)
        mixed = render(clips, cues, length, flight)
        gain = min(1, .84 / max(np.max(abs(dry)), np.max(abs(mixed)), 1e-9))
        for suffix, pcm in (("", dry), ("-with-flight", mixed)):
            sf.write(directory / (kind + suffix + ".wav"), pcm * gain, RATE, subtype="PCM_16")
        # Compare to the actual rejected V1 clips, not an older installed version.
        old = {}
        for cue in cues:
            path = PREVIOUS / (cue.name + ".ogg")
            if path.exists():
                old[cue.name], _ = sf.read(path)
        if len(old) == len({cue.name for cue in cues}):
            pair = safe_pair(render(old, cues, length), dry)
            sf.write(directory / (kind + "-v1-v2.wav"), pair, RATE, subtype="PCM_16")
        previews[kind] = {"duration": length, "gain": gain, "cues": [asdict(c) for c in cues],
                          **measurements(dry * gain)}
    after = protected_hashes()
    if before != after:
        raise RuntimeError("Protected flight/runtime audio changed")
    report = {"status": "audition_not_integrated", "sample_rate": RATE, "sources": manifest,
              "runtime_and_approved_flight_unchanged": True, "protected_hashes": before,
              "records": records, "previews": previews,
              "limits": ["No in-game or listening acceptance", "Mono dry cues, no simulated travel or room acoustics",
                         "Laser ionization is fictional sound design, not a physical acoustic prediction",
                         "Public lossy preview recordings, not original masters; some source coloration remains",
                         "RMS matching does not guarantee equal perceived loudness"]}
    (directory / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "clips": len(clips), "sequences": len(previews),
                      "protected_audio_unchanged": True, "output": str(directory)}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    generate(parser.parse_args().output)
