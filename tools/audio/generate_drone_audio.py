"""Original, deterministic mono synthesis. Dependencies: numpy, soundfile.

No recordings, downloads, or game installation. Loops are periodic signals, not
one-shots with silence at their boundaries. OpenAL supplies spatial positioning.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import soundfile as sf


RATE = 48_000
ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "src/main/resources/assets/morrowgear_drone/sounds"
SPECS = {
    "flight_idle": (4.0, True, 48, 0.090, 0.38),
    "flight_cruise": (4.0, True, 64, 0.100, 0.40),
    "autocannon_burst": (0.40, False, 64, 0.140, 0.64),
    "laser_charge": (2.75, False, 36, 0.100, 0.48),
    "laser_fire": (1.5, True, 48, 0.105, 0.42),
    "laser_discharge": (0.55, False, 48, 0.130, 0.63),
    "laser_shutdown": (2.65, False, 40, 0.085, 0.52),
    "laser_hit": (0.45, False, 40, 0.075, 0.40),
    "missile_launch": (0.85, False, 64, 0.150, 0.70),
    "missile_impact": (0.65, False, 48, 0.110, 0.65),
    "missile_explosion": (2.40, False, 80, 0.165, 0.76),
    "missile_debris": (3.20, False, 48, 0.080, 0.54),
}


def timeline(duration):
    return np.arange(round(duration * RATE), dtype=np.float64) / RATE


def smoothstep(value):
    x = np.clip(value, 0.0, 1.0)
    return x * x * (3.0 - 2.0 * x)


def bandlimit(signal, low, high):
    frequencies = np.fft.rfftfreq(len(signal), 1 / RATE)
    response = 1 / np.sqrt(1 + (frequencies / high) ** 12)
    if low:
        response *= smoothstep((frequencies - low * 0.5) / low)
    response[0] = 0
    return np.fft.irfft(np.fft.rfft(signal) * response, n=len(signal))


def noise(rng, length, low, high):
    result = bandlimit(rng.standard_normal(length), low, high)
    return result / max(1e-8, np.sqrt(np.mean(result * result)))


def tone(t, hz, phase=0.0):
    return np.sin(2 * np.pi * hz * t + phase)


def ring(t, start, hz, decay, amplitude):
    local = np.maximum(0.0, t - start)
    return amplitude * (t >= start) * smoothstep(local / 0.0015) * np.exp(-decay * local) * tone(local, hz)


def window(t, start, attack, decay):
    local = np.maximum(0, t - start)
    return (t >= start) * smoothstep(local / attack) * np.exp(-local * decay)


def flight(t, rng, cruise):
    # Crossfaded loops share their mechanical fundamentals, avoiding two competing engines.
    # Every oscillator and modulator still has an integer number of cycles per loop.
    rotor_hz = 24.0
    blade = (0.5 + 0.5 * tone(t, rotor_hz)) ** 3
    rotor = 0.07 * (0.35 + 0.65 * blade) * noise(rng, len(t), 45, 420)
    rotor += 0.52 * tone(t, rotor_hz * 2) + 0.24 * tone(t, rotor_hz * 4)
    rotor += 0.08 * tone(t, rotor_hz * 6)
    combustion = noise(rng, len(t), 36, 290)
    combustion *= 0.88 + 0.12 * tone(t, 2.25)
    turbine = tone(t, 184) + 0.30 * tone(t, 368) + (0.12 if cruise else 0.06) * tone(t, 736)
    exhaust = noise(rng, len(t), 800, 6200)
    exhaust *= 0.92 + 0.08 * tone(t, 3.75)
    return 0.035 * combustion + 0.62 * rotor + 0.32 * turbine + (0.018 if cruise else 0.009) * exhaust


def autocannon_burst(t, rng):
    envelope = smoothstep(t / 0.006) * smoothstep((0.40 - t) / 0.070)
    phase = np.mod(t * 58, 1.0)
    pulses = smoothstep(phase / 0.055) * np.exp(-phase * 18)
    crack = noise(rng, len(t), 420, 7600) * pulses
    pressure = tone(t, 58) + 0.42 * tone(t, 116) + 0.14 * tone(t, 232)
    mechanism = 0.10 * tone(t, 464) * (0.5 + pulses)
    return envelope * (0.42 * pressure + mechanism + 0.65 * crack)


def laser_charge(t, rng):
    progress = smoothstep(t / 2.55)
    bus_phase = 2 * np.pi * np.cumsum(48 + 48 * progress) / RATE
    optical_phase = 2 * np.pi * np.cumsum(240 + 480 * progress) / RATE
    envelope = smoothstep(t / 0.20) * smoothstep((2.75 - t) / 0.20)
    bus = np.sin(bus_phase) + 0.25 * np.sin(2 * bus_phase)
    optics = np.sin(optical_phase) + 0.18 * np.sin(2 * optical_phase)
    return ring(t, 0, 96, 24, 0.25) + envelope * (0.40 * bus
        + (0.08 + 0.20 * progress) * optics + 0.008 * noise(rng, len(t), 900, 5500))


def laser_fire(t, rng):
    # Stable harmonic pressure and a narrow cutting edge, not a pitch siren.
    power = tone(t, 96) + 0.34 * tone(t, 192) + 0.16 * tone(t, 288)
    optical = tone(t, 720) + 0.28 * tone(t, 1440) + 0.075 * tone(t, 2160) + 0.022 * tone(t, 3600)
    return 0.25 * power + 0.36 * optical + 0.009 * noise(rng, len(t), 1800, 6500)


def laser_discharge(t, rng):
    impulse = ring(t, 0, 96, 15, 0.65) + ring(t, 0.007, 720, 12, 0.40)
    impulse += ring(t, 0.010, 1440, 20, 0.10)
    ionization = noise(rng, len(t), 1600, 7000) * window(t, 0.005, 0.007, 30)
    return impulse + 0.065 * ionization


def laser_shutdown(t, rng):
    isolation = ring(t, 0, 116, 22, 0.48) + ring(t, 0.012, 730, 45, 0.12)
    valve = noise(rng, len(t), 450, 2200) * window(t, 0.045, 0.008, 24)
    purge = noise(rng, len(t), 1200, 6800) * window(t, 0.15, 0.10, 2.2)
    pump_phase = 2 * np.pi * (168 * t - 15 * t * t)
    pump = np.sin(pump_phase) * window(t, 0.11, 0.12, 1.9)
    return isolation + 0.05 * valve + 0.10 * purge + 0.24 * pump


def laser_hit(t, rng):
    sear = noise(rng, len(t), 1000, 6500) * window(t, 0.004, 0.006, 16)
    thermal = ring(t, 0, 180, 22, 0.32) + ring(t, 0.012, 540, 30, 0.08)
    return thermal + 0.11 * sear


def missile_launch(t, rng):
    eject = ring(t, 0, 92, 25, 0.7)
    motor = noise(rng, len(t), 180, 6200) * window(t, 0.018, 0.004, 25)
    pressure = ring(t, 0.052, 62, 8, 0.55)
    jet = noise(rng, len(t), 1100, 6500) * window(t, 0.055, 0.025, 8)
    return eject + 0.25 * motor + pressure * 0.5 + 0.045 * jet


def missile_impact(t, rng):
    crunch = noise(rng, len(t), 500, 7200) * window(t, 0, 0.002, 38)
    crush = ring(t, 0.003, 140, 28, 0.9) + ring(t, 0.01, 430, 38, 0.35)
    return 0.38 * crunch + crush


def missile_explosion(t, rng):
    shock = noise(rng, len(t), 100, 7500) * window(t, 0, 0.002, 17)
    body = noise(rng, len(t), 32, 440) * window(t, 0.012, 0.03, 2.8)
    pressure = ring(t, 0.006, 54, 5.5, 0.75) + ring(t, 0.024, 87, 7, 0.4)
    reflections = np.zeros_like(t)
    for start, weight in ((0.13, 0.18), (0.28, 0.12), (0.47, 0.065)):
        reflections += weight * noise(rng, len(t), 50, 1000) * window(t, start, 0.012, 5)
    return 0.65 * shock + 0.24 * body + pressure + reflections * 0.55


def missile_debris(t, rng):
    result = 0.018 * noise(rng, len(t), 160, 1600) * window(t, 0, 0.08, 1.3)
    # Independent stone knocks with smaller rebounds, followed by fine gravel.
    for start in np.sort(rng.uniform(0.03, 2.65, 34)):
        weight = float(rng.uniform(0.10, 0.36) * np.exp(-start * 0.5))
        hz = float(rng.uniform(210, 1050))
        for bounce, gain in ((0, 1), (0.065, 0.38), (0.105, 0.14)):
            result += ring(t, start + bounce, hz, 45, weight * gain)
        result += weight * 0.34 * noise(rng, len(t), 1600, 7500) * window(t, start, 0.0015, 85)
    return result


def synthesize(name):
    duration, loop, _, rms, ceiling = SPECS[name]
    rng = np.random.default_rng(int.from_bytes(hashlib.sha256(name.encode()).digest()[:8], "little"))
    t = timeline(duration)
    if name.startswith("flight_"):
        signal = flight(t, rng, name == "flight_cruise")
    else:
        signal = globals()[name](t, rng)
    # Linear mastering retains transients; no saturation or radio-band global low-pass.
    signal = bandlimit(signal, 25, 8000)
    if not loop:
        signal *= smoothstep(t / 0.002) * smoothstep((duration - 1 / RATE - t) / 0.12)
    signal -= signal.mean()
    gain = min(rms / max(1e-8, np.sqrt(np.mean(signal * signal))),
               ceiling / max(1e-8, np.max(np.abs(signal))))
    return (signal * gain).astype(np.float32)


def measurements(signal):
    frequencies = np.fft.rfftfreq(len(signal), 1 / RATE)
    energy = np.abs(np.fft.rfft(signal)) ** 2
    return {
        "peak": float(np.max(np.abs(signal))),
        "rms": float(np.sqrt(np.mean(signal ** 2))),
        "dc": float(np.mean(signal)),
        "energy_above_3khz": float(energy[frequencies >= 3000].sum() / max(1e-12, energy.sum())),
        "boundary_jump": float(abs(signal[0] - signal[-1])),
    }


def generate(output=OUTPUT, report=None, preview=None):
    if output.resolve() == OUTPUT.resolve() and (OUTPUT.parent / "audio-approved.json").exists():
        raise ValueError("Approved audio is installed; generate legacy comparisons in a separate folder.")
    output.mkdir(parents=True, exist_ok=True)
    records = {}
    clips = []
    for name, (duration, loop, distance, _, _) in SPECS.items():
        pcm = synthesize(name)
        path = output / (name + ".ogg")
        sf.write(path, pcm, RATE, format="OGG", subtype="VORBIS")
        decoded, rate = sf.read(path, dtype="float32")
        records[name] = dict(duration=len(decoded) / rate, channels=1, sample_rate=rate,
                             loop=loop, attenuation_distance=distance,
                             pcm_sha256=hashlib.sha256(pcm.tobytes()).hexdigest(),
                             **measurements(decoded))
        clips.extend((pcm, np.zeros(RATE // 2, dtype=np.float32)))
        print(f"{name}: {duration:.2f}s, mono, peak={records[name]['peak']:.3f}, rms={records[name]['rms']:.3f}")
    if report:
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")
    if preview:
        preview.parent.mkdir(parents=True, exist_ok=True)
        sf.write(preview, np.concatenate(clips), RATE, subtype="PCM_16")
    return records


def comparison_previews(output, before, directory):
    """Raw mono auditions, old then new, without simulating in-game attenuation."""
    directory.mkdir(parents=True, exist_ok=True)

    def clip(folder, name):
        pcm, rate = sf.read(folder / (name + ".ogg"), dtype="float32")
        if rate != RATE or pcm.ndim != 1:
            raise ValueError("Preview expects mono 48 kHz: " + name)
        return pcm

    def sequence(folder, kind):
        if kind == "flight":
            segments = [clip(folder, "flight_idle")[:2 * RATE], clip(folder, "flight_cruise")[:2 * RATE]]
        else:
            firing = np.tile(clip(folder, "laser_fire"), 2)
            discharge = clip(folder, "laser_discharge")
            firing[:len(discharge)] += discharge * 0.7
            segments = [clip(folder, "laser_charge"), firing, clip(folder, "laser_shutdown")]
        for pcm in segments:
            fade = np.linspace(0, 1, round(RATE * .015), dtype=np.float32)
            pcm[:len(fade)] *= fade
            pcm[-len(fade):] *= fade[::-1]
        return np.concatenate(segments) * 0.7

    for kind in ("flight", "laser"):
        audition = np.concatenate([sequence(before, kind), np.zeros(RATE // 2), sequence(output, kind)])
        sf.write(directory / (kind + "-before-after.wav"), audition, RATE, subtype="PCM_16")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--preview", type=Path)
    parser.add_argument("--compare-before", type=Path)
    parser.add_argument("--comparison-dir", type=Path)
    args = parser.parse_args()
    generate(args.output, args.report, args.preview)
    if args.compare_before and args.comparison_dir:
        comparison_previews(args.output, args.compare_before, args.comparison_dir)
