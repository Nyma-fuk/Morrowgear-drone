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
    if (OUTPUT.parent / "audio-approved.json").exists():
        raise ValueError("Approved audio is installed; this legacy writer must not replace it.")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    sf.write(OUTPUT / f"{name}.ogg", signal, SAMPLE_RATE, format="OGG", subtype="VORBIS")


def charge() -> np.ndarray:
    from generate_drone_audio import synthesize
    return synthesize("laser_charge")


def autocannon() -> np.ndarray:
    """Synthesize a dense rotary-cannon pressure wave without metallic clatter."""
    from generate_drone_audio import synthesize
    return synthesize("autocannon_burst")


def fire() -> np.ndarray:
    """Keep the legacy entry point aligned with the integrated-lens loop."""
    from generate_drone_audio import synthesize
    return synthesize("laser_fire")


def shutdown() -> np.ndarray:
    """The sealed integrated lens vents coolant instead of ejecting a cartridge."""
    from generate_drone_audio import synthesize
    return synthesize("laser_shutdown")


if __name__ == "__main__":
    from generate_drone_audio import generate
    write("autocannon_burst", autocannon())
    write("laser_charge", charge())
    generate(OUTPUT)
    print(f"Generated drone sounds in {OUTPUT}")
