"""Generate Morrowgear laser sounds from modeled mechanical components.

Dependencies: numpy, scipy, soundfile
All waveforms are synthesized. No sampled weapon or copyrighted source audio is used.
"""

from pathlib import Path

import numpy as np
import soundfile as sf
from scipy.signal import butter, sosfilt


SAMPLE_RATE = 48_000
ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "src/main/resources/assets/morrowgear_drone/sounds"
RNG = np.random.default_rng(0x4D4F5252)


def timeline(duration: float) -> np.ndarray:
    return np.arange(round(duration * SAMPLE_RATE), dtype=np.float64) / SAMPLE_RATE


def smoothstep(value: np.ndarray) -> np.ndarray:
    value = np.clip(value, 0.0, 1.0)
    return value * value * (3.0 - 2.0 * value)


def fade(signal: np.ndarray, attack: float, release: float) -> np.ndarray:
    result = signal.copy()
    attack_samples = min(len(result), round(attack * SAMPLE_RATE))
    release_samples = min(len(result), round(release * SAMPLE_RATE))
    if attack_samples:
        result[:attack_samples] *= np.linspace(0.0, 1.0, attack_samples)
    if release_samples:
        result[-release_samples:] *= np.linspace(1.0, 0.0, release_samples)
    return result


def filtered_noise(length: int, low: float, high: float) -> np.ndarray:
    noise = RNG.standard_normal(length)
    nyquist = SAMPLE_RATE * 0.5
    if low > 0.0:
        noise = sosfilt(butter(3, low / nyquist, btype="highpass", output="sos"), noise)
    if high < nyquist:
        noise = sosfilt(butter(3, high / nyquist, btype="lowpass", output="sos"), noise)
    return noise


def oscillator(frequency: np.ndarray, modulation: np.ndarray | float = 0.0) -> np.ndarray:
    phase = np.cumsum(2.0 * np.pi * frequency / SAMPLE_RATE)
    return np.sin(phase + modulation)


def transient(t: np.ndarray, start: float, frequency: float, decay: float,
              amplitude: float, phase: float = 0.0) -> np.ndarray:
    local = np.maximum(0.0, t - start)
    active = t >= start
    return active * amplitude * np.exp(-local * decay) * np.sin(
        2.0 * np.pi * frequency * local + phase
    )


def master(signal: np.ndarray, peak: float = 0.78) -> np.ndarray:
    signal = np.tanh(signal * 1.35)
    maximum = np.max(np.abs(signal))
    return (signal * peak / maximum).astype(np.float32) if maximum else signal.astype(np.float32)


def write(name: str, signal: np.ndarray) -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    sf.write(OUTPUT / f"{name}.ogg", signal, SAMPLE_RATE, format="OGG", subtype="VORBIS")


def charge() -> np.ndarray:
	"""Contactor close, capacitor-bank rise, inverter and coolant-pump spool."""
	duration = 2.75
	t = timeline(duration)
	progress = smoothstep(t / duration)

	contactor = transient(t, 0.015, 64.0, 15.0, 1.05)
	contactor += transient(t, 0.026, 920.0, 62.0, 0.24)
	contactor += transient(t, 0.042, 1_480.0, 78.0, 0.13)

	# Capacitor banks load as a dense low-frequency pulse, not a clean pitch sweep.
	bus_progress = smoothstep(t / 1.75)
	bus_frequency = 46.0 + 48.0 * bus_progress
	bus_mod = 0.22 * np.sin(2.0 * np.pi * 11.0 * t)
	bus = oscillator(bus_frequency, bus_mod)
	bus += 0.46 * oscillator(bus_frequency * 2.01, bus_mod * 0.45)
	bus += 0.20 * oscillator(bus_frequency * 3.06)
	bus_envelope = (0.30 + 0.70 * smoothstep(t / 0.62)) * (1.0 - smoothstep((t - 2.52) / 0.22))

	# A rough inverter buzz stays in the mechanical mid-band for most of the cycle.
	inverter_progress = smoothstep((t - 0.28) / 1.90)
	inverter_frequency = 210.0 + 440.0 * inverter_progress
	inverter_mod = 0.42 * np.sin(2.0 * np.pi * (17.0 + 3.0 * progress) * t)
	inverter = oscillator(inverter_frequency, inverter_mod)
	inverter += 0.38 * oscillator(inverter_frequency * 1.49, inverter_mod * 0.5)
	inverter += 0.15 * oscillator(inverter_frequency * 2.12)
	inverter_envelope = smoothstep((t - 0.20) / 0.72) * (1.0 - smoothstep((t - 2.57) / 0.18))

	# Pump and turbulent coolant give the emitter physical mass and internal motion.
	pump_frequency = 78.0 + 96.0 * smoothstep(t / 1.55)
	pump = oscillator(pump_frequency, 0.24 * np.sin(2.0 * np.pi * 5.3 * t))
	coolant = filtered_noise(len(t), 95.0, 2_100.0)
	coolant *= 0.08 + 0.19 * progress

	# Only the final optical lock climbs into the high band.
	lock_progress = smoothstep((t - 1.92) / 0.68)
	lock_frequency = 920.0 + 1_260.0 * lock_progress
	lock_tone = oscillator(lock_frequency, 0.12 * np.sin(2.0 * np.pi * 23.0 * t))
	lock_envelope = smoothstep((t - 1.88) / 0.38) * (1.0 - smoothstep((t - 2.62) / 0.12))
	clamp = transient(t, 2.57, 1_760.0, 52.0, 0.20)

	signal = contactor + 0.68 * bus * bus_envelope + 0.24 * inverter * inverter_envelope
	signal += 0.20 * pump * smoothstep(t / 0.58) + coolant
	signal += 0.12 * lock_tone * lock_envelope + clamp
	return master(fade(signal, 0.004, 0.10), 0.78)


def autocannon() -> np.ndarray:
    """Synthesize a dense rotary-cannon pressure wave without metallic clatter."""
    duration = 0.40
    t = timeline(duration)
    envelope = smoothstep(t / 0.010) * (1.0 - smoothstep((t - 0.315) / 0.085))

    # Around 58 pressure impulses per second merge into a continuous BRRRT texture.
    cadence = 58.0
    phase = np.mod(t * cadence, 1.0)
    pressure_pulses = np.exp(-phase * 15.0)
    pressure_pulses -= np.mean(pressure_pulses)
    pulse_noise = filtered_noise(len(t), 95.0, 2_400.0)
    pulse_noise *= 0.34 + 0.66 * pressure_pulses

    # Low-frequency gas pressure and rotor drive provide body without ringing metal.
    gas = oscillator(np.full_like(t, 58.0), 0.18 * np.sin(2.0 * np.pi * 7.0 * t))
    gas += 0.52 * oscillator(np.full_like(t, 116.0))
    drive = oscillator(np.full_like(t, 520.0), 0.25 * np.sin(2.0 * np.pi * cadence * t))
    drive += 0.24 * oscillator(np.full_like(t, 1_040.0))

    blast = filtered_noise(len(t), 55.0, 1_350.0)
    blast_envelope = smoothstep(t / 0.006) * np.exp(-t * 5.2)

    signal = envelope * (0.48 * pulse_noise + 0.42 * gas + 0.17 * drive)
    signal += 0.34 * blast * blast_envelope
    return master(fade(signal, 0.003, 0.045), 0.74)


def fire() -> np.ndarray:
    """Power-converter load, optical resonance, and ionized-air shear during firing."""
    duration = 1.50
    t = timeline(duration)
    attack = smoothstep(t / 0.035)
    release = 1.0 - smoothstep((t - 1.40) / 0.10)
    envelope = attack * release

    # Keep sustained energy below the ear-fatiguing 3-6 kHz band. Slightly
    # inharmonic mid-band components retain the laser identity without a piercing whistle.
    # Frequencies stay fixed: cyclic pitch modulation reads as a cheap siren in a long beam.
    carrier = oscillator(np.full_like(t, 1_420.0))
    carrier += 0.46 * oscillator(np.full_like(t, 1_880.0))
    carrier += 0.18 * oscillator(np.full_like(t, 2_430.0))

    # The emitter assembly still moves real power; retain a restrained low mechanical body.
    power = oscillator(np.full_like(t, 96.0))
    power += 0.46 * oscillator(np.full_like(t, 192.0))
    power += 0.18 * oscillator(np.full_like(t, 640.0))

    air = filtered_noise(len(t), 520.0, 4_200.0)
    air *= 0.16

    # A brief high-frequency ignition edge communicates beam formation, then
    # decays before it can become a sustained uncomfortable tone.
    ignition_edge = oscillator(np.full_like(t, 3_280.0))
    ignition_edge *= 0.12 * np.exp(-t * 12.0)

    ignition = transient(t, 0.0, 840.0, 36.0, 0.34)
    signal = envelope * (0.38 * carrier + 0.30 * power + air + ignition_edge) + ignition
    return master(signal, 0.70)


def shutdown() -> np.ndarray:
    """Bus isolation, thermal cartridge ejection, then forced coolant purge."""
    duration = 2.65
    t = timeline(duration)

    isolation = transient(t, 0.0, 82.0, 22.0, 0.55)
    isolation += transient(t, 0.012, 1_050.0, 68.0, 0.34)

    # Latch release followed by a resonant metal cartridge leaving its receiver.
    eject = transient(t, 0.075, 1_760.0, 18.0, 0.54)
    eject += transient(t, 0.078, 2_940.0, 15.0, 0.46, 0.5)
    eject += transient(t, 0.082, 4_680.0, 22.0, 0.34, 1.1)
    eject += transient(t, 0.090, 6_240.0, 31.0, 0.18, 0.2)
    eject += transient(t, 0.205, 1_320.0, 28.0, 0.25, 0.7)

    latch_noise = filtered_noise(len(t), 1_300.0, 10_000.0)
    latch_window = np.exp(-np.maximum(0.0, t - 0.065) * 52.0) * (t >= 0.065)

    # A valve opens after ejection. Broadband gas dominates, with pump run-down beneath it.
    purge_start = 0.23
    local = np.maximum(0.0, t - purge_start)
    purge_window = (t >= purge_start) * smoothstep(local / 0.055) * np.exp(-local * 0.72)
    purge = filtered_noise(len(t), 520.0, 8_200.0) * purge_window
    pressure = filtered_noise(len(t), 75.0, 760.0) * purge_window
    pump_frequency = np.maximum(180.0, 1_180.0 - 430.0 * local)
    pump = oscillator(pump_frequency) * purge_window

    signal = isolation + eject + 0.18 * latch_noise * latch_window
    signal += 0.44 * purge + 0.24 * pressure + 0.10 * pump
    return master(fade(signal, 0.002, 0.18), 0.82)


if __name__ == "__main__":
    write("autocannon_burst", autocannon())
    write("laser_charge", charge())
    write("laser_fire", fire())
    write("laser_shutdown", shutdown())
    print(f"Generated laser sounds in {OUTPUT}")
