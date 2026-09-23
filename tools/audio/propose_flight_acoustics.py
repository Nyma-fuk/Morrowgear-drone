"""Offline audition only; never writes game resources or installs a mod.

Five causally related sources, with separate optional environment reflections.
Frequencies, resonances and loads are art-direction assumptions, not CFD results.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import soundfile as sf

from generate_drone_audio import OUTPUT, RATE, ROOT, bandlimit, measurements, noise, smoothstep, timeline, tone


PERIOD = 8.0
BLADE_COUNT = 12
SHAFT_HZ = 40.0
BLADE_PASS_HZ = SHAFT_HZ * BLADE_COUNT
DEFAULT_OUTPUT = ROOT / "build/verification/flight-acoustics-proposal"
SOURCE_NAMES = ("lift_fans", "compressor", "exhaust", "structure", "inlet")
HOVER_WEIGHTS = (0.60, 0.21, 0.16, 0.17, 0.07)
CRUISE_WEIGHTS = (0.44, 0.28, 0.34, 0.19, 0.14)
HYBRID_HOVER_WEIGHTS = (.55, .17, .17, .07, .16, .15, .06)
HYBRID_CRUISE_WEIGHTS = (.39, .22, .20, .11, .34, .17, .10)


def unit_rms(signal):
    return signal / max(1e-9, np.sqrt(np.mean(signal * signal)))


def resonant_body(excitation):
    # A damped structural transfer, driven by the fan/exhaust, not an extra engine.
    hz = np.fft.rfftfreq(len(excitation), 1 / RATE)
    response = np.zeros_like(hz, dtype=complex)
    for center, q, weight in ((78, 2.3, .55), (156, 3.0, .30), (312, 2.0, .15)):
        ratio = hz / center
        response += weight / (1 - ratio * ratio + 1j * ratio / q)
    response[0] = 0
    return np.fft.irfft(np.fft.rfft(excitation) * response, n=len(excitation))


def source_layers():
    t = timeline(PERIOD)
    rng = np.random.default_rng(20260922)
    # All slow modulation frequencies fit the loop. Phase variation is deliberately small.
    phase = 2 * np.pi * BLADE_PASS_HZ * t + .065 * tone(t, .375) + .025 * tone(t, .875)
    blade_tones = np.sin(phase) + .23 * np.sin(2 * phase + .4) + .07 * np.sin(3 * phase + .8)
    shaft_phase = 2 * np.pi * SHAFT_HZ * t
    passage = (1 + np.cos(shaft_phase)) ** 2 / 4
    loading = noise(rng, len(t), 70, 1050) * (.72 + .28 * passage)
    fan_tone = .24 * tone(t, 80) + .16 * tone(t, 160) + .42 * blade_tones
    # A quieter second fan adds texture without deep cancellation or a pitch siren.
    fan_tone += .07 * tone(t, BLADE_PASS_HZ + 1.5, .6)
    fans = unit_rms(.32 * loading + fan_tone)

    compressor = .46 * tone(t, 1280, .3) + .13 * tone(t, 2560, .6)
    compressor += .15 * noise(rng, len(t), 650, 3300)
    compressor *= .97 + .03 * tone(t, .625, .7)
    compressor = unit_rms(compressor)

    pressure = noise(rng, len(t), 45, 660)
    pressure *= .94 + .035 * tone(t, .375) + .025 * tone(t, 1.125, .5)
    exhaust = unit_rms(.80 * pressure + .16 * noise(rng, len(t), 500, 3900))
    structure = unit_rms(resonant_body(.50 * fan_tone + .22 * loading + .28 * pressure))
    inlet = unit_rms(noise(rng, len(t), 200, 2600) * (.92 + .08 * passage))
    return dict(zip(SOURCE_NAMES, (fans, compressor, exhaust, structure, inlet)))


def hybrid_source_layers():
    """User-directed gas-turbine concept; does not add a gameplay fuel system."""
    base = source_layers()
    t = timeline(PERIOD)
    rng = np.random.default_rng(20260923)
    core = noise(rng, len(t), 50, 430)
    core *= .95 + .03 * tone(t, .375) + .02 * tone(t, .875, .5)
    core = unit_rms(core + .13 * noise(rng, len(t), 220, 950))
    turbine = .46 * tone(t, 912, .7) + .15 * tone(t, 1824) + .08 * tone(t, 2736, .2)
    turbine += .26 * noise(rng, len(t), 1200, 3600)
    turbine = unit_rms(turbine)
    structure = unit_rms(.70 * base["structure"] + .30 * unit_rms(resonant_body(core)))
    return {"lift_fans": base["lift_fans"], "compressor": base["compressor"],
            "combustor": core, "turbine": turbine, "exhaust": base["exhaust"],
            "structure": structure, "inlet": base["inlet"]}


def raw_mix(layers, weights):
    if len(layers) != len(weights):
        raise ValueError("Every acoustic layer needs an explicit weight")
    return sum(pcm * weight for pcm, weight in zip(layers.values(), weights))


def steady_sources(hybrid=False):
    layers = hybrid_source_layers() if hybrid else source_layers()
    weight_profiles = (HYBRID_HOVER_WEIGHTS, HYBRID_CRUISE_WEIGHTS) if hybrid else (HOVER_WEIGHTS, CRUISE_WEIGHTS)
    mixes = [bandlimit(raw_mix(layers, weights), 25, 7400)
             for weights in weight_profiles]
    # One gain for both states retains the load contrast instead of mastering it away.
    gain = min(.10 / max(np.sqrt(np.mean(pcm * pcm)) for pcm in mixes),
               .48 / max(np.max(abs(pcm)) for pcm in mixes))
    return layers, gain, tuple((pcm * gain).astype(np.float32) for pcm in mixes)


def audition_ramp(hover, cruise):
    # 0-4 hover, 4-8 acceleration, 8-14 cruise, 14-18 deceleration, 18-20 hover.
    t = timeline(20)
    load = smoothstep((t - 4) / 4) * (1 - smoothstep((t - 14) / 4))
    indices = np.arange(len(t)) % len(hover)
    return hover[indices] * (1 - load) + cruise[indices] * load


def fade_edges(pcm, seconds=.04):
    result = pcm.copy()
    length = min(round(seconds * RATE), len(result) // 2)
    ramp = smoothstep(np.arange(length) / max(1, length - 1))
    result[:length] *= ramp
    result[-length:] *= ramp[::-1]
    return result


def reflected_preview(pcm, surface):
    """Static illustrative impulse response, not a Minecraft acoustic simulation."""
    if surface not in ("open", "apron", "hangar"):
        raise ValueError("Unknown environment: " + surface)
    if surface == "open":
        return pcm.copy()
    # Flat hard floor: source 8 m, listener 1.6 m, horizontal separation 12 m.
    direct = np.hypot(12, 8 - 1.6)
    reflected = np.hypot(12, 8 + 1.6)
    ground_delay = (reflected - direct) / 343
    taps = [(ground_delay, .28, 2800)]
    if surface == "hangar":
        # Additional path lengths are hypothetical walls, not a fixed effect for all worlds.
        taps += [(.042, .17, 2100), (.073, .12, 1700), (.119, .07, 1350), (.181, .035, 1000)]
    result = pcm.copy()
    for delay, gain, cutoff in taps:
        samples = round(delay * RATE)
        # A causal low-pass avoids FFT pre-ringing before the reflected arrival.
        decay = np.exp(-2 * np.pi * cutoff / RATE)
        kernel = decay ** np.arange(96)
        kernel /= kernel.sum()
        reflected_signal = np.convolve(pcm, kernel, mode="full")[:len(pcm)]
        result[samples:] += gain * reflected_signal[:-samples]
    return result


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def generate(directory=DEFAULT_OUTPUT):
    directory = directory.resolve()
    resources = ROOT / "src/main/resources"
    if directory == resources.resolve() or resources.resolve() in directory.parents:
        raise ValueError("This proposal must not write runtime resources")
    before = {name: digest(OUTPUT / (name + ".ogg")) for name in ("flight_idle", "flight_cruise")}
    directory.mkdir(parents=True, exist_ok=True)
    layers, gain, (hover, cruise) = steady_sources()
    records = {}
    for name, pcm in (("candidate-hover", hover), ("candidate-cruise", cruise)):
        path = directory / (name + ".ogg")
        sf.write(path, pcm, RATE, format="OGG", subtype="VORBIS")
        decoded, _ = sf.read(path)
        records[name] = measurements(decoded)
    candidate = audition_ramp(hover, cruise)
    sf.write(directory / "candidate-flight.wav", fade_edges(candidate), RATE, subtype="PCM_16")

    # Compare against the actual source assets, not a re-synthesis that could drift.
    old = []
    for name in before:
        pcm, rate = sf.read(OUTPUT / (name + ".ogg"))
        if rate != RATE or pcm.ndim != 1:
            raise ValueError("Current source must be mono 48 kHz")
        old.append(np.tile(pcm, int(np.ceil(PERIOD * RATE / len(pcm))))[:round(PERIOD * RATE)])
    current = audition_ramp(*old)
    # Equal RMS is a level-control aid, not a claim of perceptually matched loudness.
    compare = [fade_edges(unit_rms(pcm) * .085) for pcm in (current, candidate)]
    comparison = np.concatenate((compare[0], np.zeros(RATE), compare[1]))
    sf.write(directory / "current-then-candidate.wav", comparison, RATE, subtype="PCM_16")

    environment = [reflected_preview(cruise, surface) for surface in ("open", "apron", "hangar")]
    environment_peak = max(np.max(abs(pcm)) for pcm in environment)
    environment_gain = min(1, .65 / max(1e-9, environment_peak))
    sf.write(directory / "open-apron-hangar.wav",
             np.concatenate([part for pcm in environment for part in
                             (fade_edges(pcm * environment_gain), np.zeros(RATE // 2))]),
             RATE, subtype="PCM_16")
    for name, weight in zip(SOURCE_NAMES, CRUISE_WEIGHTS):
        sf.write(directory / ("layer-" + name + ".wav"),
                 fade_edges(layers[name] * weight * gain), RATE, subtype="PCM_16")
    after = {name: digest(OUTPUT / (name + ".ogg")) for name in before}
    if before != after:
        raise RuntimeError("Runtime resources changed during an offline audition")
    report = {"status": "proposal_not_integrated", "sample_rate": RATE,
              "period_seconds": PERIOD, "assumed_rpm": SHAFT_HZ * 60,
              "assumed_blades": BLADE_COUNT, "assumed_blade_pass_hz": BLADE_PASS_HZ,
              "current_source_hashes": before, "source_assets_unchanged": before == after,
              "candidate": records, "comparison": measurements(comparison),
              "comparison_segments": [{"start": 0, "end": 20, "name": "current_source"},
                                      {"start": 21, "end": 41, "name": "candidate"}],
              "limitations": ["not CFD", "RMS is not perceptual loudness", "no in-game verification",
                              "reflections are static illustrations, not runtime world acoustics"]}
    (directory / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    generate(parser.parse_args().output)
