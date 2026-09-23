"""Original offline weapon auditions; no runtime asset registration or installation.

Fixed, documented timelines illustrate the proposal. They are not recordings of
Minecraft or claims of acoustically exact real weapons/laser beams.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import hashlib
import json
from pathlib import Path

import numpy as np
import soundfile as sf

from generate_drone_audio import OUTPUT, RATE, ROOT, bandlimit, measurements, noise, ring, smoothstep, timeline, tone, window
from propose_flight_acoustics import audition_ramp, digest, fade_edges, steady_sources, unit_rms


DEFAULT_OUTPUT = ROOT / "build/verification/weapon-acoustics-proposal"
# Duration, loop, RMS ceiling, peak ceiling. These are preview limits, not game gains.
SPECS = {
    "autocannon_burst": (.40, False, .14, .64),
    "autocannon_impact": (.23, False, .075, .47),
    "laser_charge": (2.75, False, .10, .48),
    "laser_discharge": (.55, False, .13, .63),
    "laser_fire": (1.50, True, .10, .42),
    "laser_shutdown": (2.65, False, .085, .52),
    "laser_hit": (.45, False, .075, .40),
    "missile_launch": (.20, False, .11, .55),
    "missile_ignition": (.40, False, .14, .64),
    "missile_motor": (.80, True, .085, .42),
    "missile_impact": (.25, False, .10, .55),
    "missile_explosion": (2.40, False, .165, .76),
    "missile_debris": (3.20, False, .07, .50),
}


def autocannon_burst(t, rng):
    # Four pressure transients match the current 2-tick shot cadence at nominal 20 TPS.
    result = np.zeros_like(t)
    for start in (0, .1, .2, .3):
        crack = noise(rng, len(t), 750, 7600) * window(t, start, .0007, 145)
        pressure = ring(t, start + .001, 105, 37, .80) + ring(t, start + .003, 58, 31, .36)
        mount = ring(t, start + .004, 210, 80, .16)
        result += .54 * crack + pressure + mount
    feed = (.05 * tone(t, 300) + .035 * tone(t, 150)) * smoothstep(t / .008)
    return result + feed


def autocannon_impact(t, rng):
    crack = noise(rng, len(t), 700, 6900) * window(t, 0, .001, 90)
    ground = noise(rng, len(t), 100, 750) * window(t, .006, .004, 28)
    return .5 * crack + .24 * ground + ring(t, .002, 148, 44, .40)


def laser_charge(t, rng):
    progress = smoothstep(t / 2.65)
    bus_phase = 2 * np.pi * np.cumsum(64 + 88 * progress) / RATE
    resonator_phase = 2 * np.pi * np.cumsum(380 + 780 * progress) / RATE
    envelope = smoothstep(t / .15) * smoothstep((2.75 - t) / .10)
    bus = np.sin(bus_phase) + .26 * np.sin(2 * bus_phase + .25)
    resonator = np.sin(resonator_phase) + .10 * np.sin(2 * resonator_phase)
    cooling = noise(rng, len(t), 140, 750) * (.05 + .05 * progress)
    return ring(t, 0, 170, 45, .12) + envelope * (
        .52 * bus + (.04 + .21 * progress) * resonator + cooling)


def laser_discharge(t, rng):
    pressure = ring(t, 0, 96, 19, .63) + ring(t, .005, 192, 32, .15)
    edge = ring(t, .008, 1160, 22, .27) + ring(t, .009, 2320, 36, .035)
    gate = noise(rng, len(t), 700, 4000) * window(t, .001, .001, 95)
    return pressure + edge + .15 * gate


def laser_fire(t, rng):
    # A fixed carrier plus a narrow texture, never a periodic up/down pitch sweep.
    power = tone(t, 96) + .24 * tone(t, 192) + .07 * tone(t, 288)
    edge = .72 * tone(t, 1160) + .07 * tone(t, 2320)
    edge += .075 * tone(t, 1144, .3) + .075 * tone(t, 1176, .7)
    aperture = noise(rng, len(t), 850, 2350)
    return .36 * power + .32 * edge + .055 * aperture


def laser_shutdown(t, rng):
    isolate = ring(t, 0, 185, 48, .26)
    valve = noise(rng, len(t), 550, 2600) * window(t, .035, .003, 58)
    coolant = noise(rng, len(t), 900, 3900) * window(t, .10, .09, 2.25)
    pump_phase = 2 * np.pi * (154 * t - 9 * t * t)
    pump = np.sin(pump_phase) * window(t, .08, .10, 2.0)
    return isolate + .055 * valve + .13 * coolant + .22 * pump


def laser_hit(t, rng):
    skin = noise(rng, len(t), 1100, 5200) * window(t, 0, .008, 13)
    result = .16 * skin + ring(t, .008, 220, 32, .28)
    for start in (.02, .069, .14):
        result += .045 * noise(rng, len(t), 1700, 5800) * window(t, start, .001, 135)
    return result


def missile_launch(t, rng):
    # Cold ejection only. Ignition belongs to the missile's later flight event.
    puff = noise(rng, len(t), 250, 4600) * window(t, 0, .001, 62)
    return .36 * puff + ring(t, .001, 145, 52, .52) + ring(t, .004, 290, 90, .10)


def missile_ignition(t, rng):
    snap = noise(rng, len(t), 600, 6700) * window(t, 0, .001, 100)
    jet = noise(rng, len(t), 280, 3800) * window(t, .009, .013, 9)
    return .3 * snap + .34 * jet + ring(t, .006, 88, 21, .42)


def missile_motor(t, rng):
    jet = noise(rng, len(t), 300, 2700)
    jet *= .97 + .03 * tone(t, 7.5)
    return .24 * jet + .10 * tone(t, 125) + .045 * tone(t, 250)


def missile_impact(t, rng):
    contact = noise(rng, len(t), 950, 6500) * window(t, 0, .0008, 115)
    crush = noise(rng, len(t), 220, 850) * window(t, .003, .004, 45)
    return .5 * contact + .17 * crush + ring(t, .002, 178, 45, .48)


def missile_explosion(t, rng):
    shock = noise(rng, len(t), 400, 7400) * window(t, 0, .0008, 95)
    body = noise(rng, len(t), 40, 530) * window(t, .005, .012, 4.8)
    pressure = ring(t, .003, 58, 8.0, .75) + ring(t, .011, 94, 11, .32)
    # No fixed room echoes: material debris and world reflections are separate.
    return .72 * shock + .35 * body + pressure


def missile_debris(t, rng):
    result = .012 * noise(rng, len(t), 180, 1600) * window(t, 0, .025, 1.4)
    for start in np.sort(rng.uniform(.015, 2.70, 26)):
        strength = float(rng.uniform(.12, .30) * np.exp(-start * .65))
        hz = float(rng.uniform(180, 650))
        result += ring(t, start, hz, 62, strength)
        result += ring(t, start + .055, hz * 1.13, 80, strength * .25)
        result += .13 * strength * noise(rng, len(t), 1700, 5400) * window(t, start, .001, 120)
    return result


def synthesize(name):
    duration, loop, rms, peak = SPECS[name]
    seed = int.from_bytes(hashlib.sha256(("weapons-v1:" + name).encode()).digest()[:8], "little")
    t = timeline(duration)
    signal = globals()[name](t, np.random.default_rng(seed))
    signal = bandlimit(signal, 25, 7600)
    if not loop:
        signal *= smoothstep(t / .0005) * smoothstep((duration - t - 1 / RATE) / .025)
    signal -= signal.mean()
    gain = min(rms / max(1e-9, np.sqrt(np.mean(signal ** 2))), peak / max(1e-9, np.max(abs(signal))))
    return (signal * gain).astype(np.float32)


@dataclass(frozen=True)
class Cue:
    time: float
    name: str
    gain: float
    source: str
    actor: int = 0
    duration: float | None = None


def missile_cues(count=4):
    if count not in (3, 4, 5):
        raise ValueError("A missile preview salvo contains 3-5 rounds")
    cues = []
    for index in range(count):
        launched = .45 + .10 * index
        impact = 2.0 + .19 * index
        cues.extend((Cue(launched, "missile_launch", .65, "launcher", index),
                     Cue(launched + .35, "missile_ignition", .45, "missile", index),
                     Cue(launched + .43, "missile_motor", .18, "missile", index,
                         impact - launched - .43),
                     Cue(impact, "missile_impact", .40, "impact", index),
                     Cue(impact + .60, "missile_explosion", .60, "impact", index),
                     Cue(impact + .95, "missile_debris", .36, "impact", index)))
    return tuple(sorted(cues, key=lambda cue: cue.time))


def laser_cues():
    return (Cue(.30, "laser_charge", .70, "drone"),
            Cue(3.05, "laser_discharge", .72, "drone"),
            Cue(3.05, "laser_fire", .72, "drone", duration=4.50),
            Cue(3.65, "laser_hit", .20, "target"),
            Cue(5.05, "laser_hit", .20, "target"),
            Cue(6.45, "laser_hit", .20, "target"),
            Cue(7.55, "laser_shutdown", .65, "drone"))


def cannon_cues():
    cues = [Cue(.60 + .40 * i, "autocannon_burst", .72, "drone") for i in range(6)]
    cues += [Cue(1.00 + .10 * i, "autocannon_impact", .16, "target") for i in range(24)]
    return tuple(sorted(cues, key=lambda cue: cue.time))


def render_sequence(clips, cues, duration, flight=None):
    pcm = np.zeros(round(duration * RATE), dtype=np.float64)
    if flight is not None:
        pcm += np.resize(flight, len(pcm)) * .23
    for cue in cues:
        start = round(cue.time * RATE)
        source = clips[cue.name]
        if cue.duration is not None:
            if not SPECS[cue.name][1] or not np.isfinite(cue.duration) or cue.duration <= 0:
                raise ValueError("Only a loop can have a positive finite preview duration")
            source = fade_edges(np.resize(source, round(cue.duration * RATE)), .020)
        end = min(len(pcm), start + len(source))
        if end > start:
            pcm[start:end] += source[:end-start] * cue.gain
    return fade_edges(pcm)


def safe_pair(before, after):
    normalized = [unit_rms(pcm) * .08 for pcm in (before, after)]
    gain = min(1.0, .78 / max(np.max(abs(pcm)) for pcm in normalized))
    return np.concatenate((normalized[0] * gain, np.zeros(RATE), normalized[1] * gain))


def generate(directory=DEFAULT_OUTPUT):
    directory = directory.resolve()
    resources = (ROOT / "src/main/resources").resolve()
    if directory == resources or resources in directory.parents:
        raise ValueError("This proposal must not write runtime resources")
    before_hashes = {str(path.relative_to(OUTPUT)): digest(path) for path in sorted(OUTPUT.glob("*.ogg"))}
    directory.mkdir(parents=True, exist_ok=True)
    records, clips = {}, {}
    for name in SPECS:
        pcm = synthesize(name)
        path = directory / (name + ".ogg")
        sf.write(path, pcm, RATE, format="OGG", subtype="VORBIS")
        decoded, rate = sf.read(path)
        if rate != RATE:
            raise ValueError("Unexpected codec sample rate")
        clips[name] = decoded
        records[name] = dict(duration=len(decoded) / rate, **measurements(decoded))
    _, _, (hover, cruise) = steady_sources(hybrid=True)
    sf.write(directory / "hybrid-flight.wav", fade_edges(audition_ramp(hover, cruise)), RATE, subtype="PCM_16")
    for name, pcm in (("hybrid-hover", hover), ("hybrid-cruise", cruise)):
        sf.write(directory / (name + ".ogg"), pcm, RATE, format="OGG", subtype="VORBIS")

    old = {}
    for name in SPECS:
        path = OUTPUT / (name + ".ogg")
        if path.exists():
            old[name], rate = sf.read(path)
            if rate != RATE or old[name].ndim != 1:
                raise ValueError("Comparison requires mono 48 kHz source assets")
    # The old implementation has neither a separate motor nor custom cannon impact.
    # Only the comparable source bursts are used for the cannon A/B; no invented vanilla recording.
    old["missile_ignition"] = old["missile_launch"]
    old["missile_motor"] = np.zeros(round(.80 * RATE))
    old["autocannon_impact"] = np.zeros(round(.23 * RATE))
    records["previews"] = {}
    for kind, cues, duration in (("autocannon", cannon_cues(), 5.0),
                                 ("laser", laser_cues(), 11.0), ("missile", missile_cues(), 8.0)):
        candidate = render_sequence(clips, cues, duration, flight=cruise)
        gain = min(1.0, .78 / max(1e-9, np.max(abs(candidate))))
        candidate *= gain
        sf.write(directory / (kind + "-candidate.wav"), candidate, RATE, subtype="PCM_16")
        comparable = tuple(cue for cue in cues if kind != "autocannon" or cue.source != "target")
        pair = safe_pair(render_sequence(old, comparable, duration),
                         render_sequence(clips, comparable, duration))
        sf.write(directory / (kind + "-before-after.wav"), pair, RATE, subtype="PCM_16")
        records["previews"][kind] = dict(duration=duration, mix_gain=gain, cues=[asdict(c) for c in cues],
                                           **measurements(candidate))
    after_hashes = {str(path.relative_to(OUTPUT)): digest(path) for path in sorted(OUTPUT.glob("*.ogg"))}
    if before_hashes != after_hashes:
        raise RuntimeError("Runtime source assets changed during an offline audition")
    report = {"status": "proposal_not_integrated", "sample_rate": RATE,
              "source_assets_unchanged": before_hashes == after_hashes, "source_hashes": before_hashes,
              "records": records, "limitations": ["no game recording or spatial simulation",
              "RMS matching is not perceptual loudness matching", "laser sound is fictional apparatus design",
              "missile A/B uses source clips, not exact old pitch-shifted runtime playback"]}
    (directory / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "clips": len(clips),
                      "source_assets_unchanged": report["source_assets_unchanged"],
                      "peaks": {key: round(value["peak"], 4) for key, value in records.items()
                                if key != "previews"}}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    generate(parser.parse_args().output)
