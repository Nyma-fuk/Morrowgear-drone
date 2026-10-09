"""Generate deterministic carrier mining sequence audio without external samples."""
from pathlib import Path
import hashlib
import shutil
import subprocess
import wave
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/main/resources/assets/morrowgear_drone/sounds"
RATE = 48_000
FFMPEG = shutil.which("ffmpeg") or str(
    ROOT.parent / "morrowgear-drone/node_modules/@ffmpeg-installer/win32-x64/ffmpeg.exe")


def time(duration):
    return np.arange(round(duration * RATE), dtype=np.float64) / RATE


def smooth(value):
    value = np.clip(value, 0.0, 1.0)
    return value * value * (3.0 - 2.0 * value)


def noise(rng, count, low, high):
    raw = rng.standard_normal(count)
    spectrum = np.fft.rfft(raw)
    frequencies = np.fft.rfftfreq(count, 1 / RATE)
    edge = max(15.0, min(180.0, (high - low) * .12))
    response = smooth((frequencies - low) / edge) * smooth((high - frequencies) / edge)
    return np.fft.irfft(spectrum * response, n=count)


def envelope(t, attack, release):
    return smooth(t / attack) * smooth((t[-1] - t) / release)


def normalize(pcm, rms, peak):
    pcm -= pcm.mean()
    gain = min(rms / max(1e-9, np.sqrt(np.mean(pcm * pcm))),
               peak / max(1e-9, np.max(np.abs(pcm))))
    return (pcm * gain).astype(np.float32)


def emitter(rng):
    t = time(.58)
    contact = np.sin(2 * np.pi * (210 * t + 1050 * t * t)) * np.exp(-t * 18)
    relay = np.sin(2 * np.pi * 72 * t) * np.exp(-t * 9)
    arc = noise(rng, len(t), 650, 6800) * np.exp(-t * 15)
    pressure = noise(rng, len(t), 45, 260) * np.exp(-t * 11)
    return normalize((.28 * contact + .21 * relay + .18 * arc + .14 * pressure)
                     * envelope(t, .001, .025), .075, .48)


def core(rng):
    duration = 2.0
    t = time(duration)
    phase = 2 * np.pi * np.cumsum(92 + 8 * np.sin(2 * np.pi * t / duration)) / RATE
    low = .20 * np.sin(phase) + .11 * np.sin(phase * 1.51)
    plasma = .20 * noise(rng, len(t), 280, 1900) + .055 * noise(rng, len(t), 2100, 6500)
    pcm = low + plasma
    cross = round(.18 * RATE)
    weight = smooth(np.arange(cross) / max(1, cross - 1))
    joined = pcm.copy()
    joined[:cross] = pcm[-cross:] * (1 - weight) + pcm[:cross] * weight
    joined[-cross:] = joined[:cross]
    return normalize(joined, .082, .38)


def release(rng):
    t = time(3.35)
    p = smooth(t / 3.0)
    shear = noise(rng, len(t), 170, 5200) * (.05 + .30 * p * p)
    compression = noise(rng, len(t), 38, 310) * (.12 + .24 * p)
    phase = 2 * np.pi * np.cumsum(74 - 22 * p) / RATE
    mass = np.sin(phase) * (.18 + .12 * p)
    latch = noise(rng, len(t), 500, 7000) * np.exp(-t * 35)
    return normalize((shear + compression + mass + .22 * latch) * envelope(t, .003, .08), .105, .55)


def impact(rng):
    t = time(4.6)
    shock = noise(rng, len(t), 35, 380) * np.exp(-t * 2.0)
    crack = noise(rng, len(t), 420, 6800) * np.exp(-t * 7.5)
    plasma = noise(rng, len(t), 1300, 7200) * np.exp(-t * 2.8)
    ground = noise(rng, len(t), 55, 800) * np.exp(-np.maximum(0, t - .12) * 1.25)
    debris = np.zeros_like(t)
    for start in rng.uniform(.18, 3.5, 54):
        debris += noise(rng, len(t), 220, 3600) * (t >= start) * np.exp(-np.maximum(0, t - start) * rng.uniform(18, 55))
    pcm = .40 * shock + .24 * crack + .12 * plasma + .20 * ground + .018 * debris
    return normalize(pcm * envelope(t, .0006, .12), .145, .72)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    if not Path(FFMPEG).is_file():
        raise RuntimeError("ffmpeg executable not found")
    makers = {"carrier_mining_emitter": emitter, "carrier_mining_core": core,
              "carrier_mining_release": release, "carrier_mining_impact": impact}
    report = []
    for name, maker in makers.items():
        seed = int.from_bytes(hashlib.sha256(name.encode()).digest()[:8], "little")
        pcm = maker(np.random.default_rng(seed))
        path = OUT / f"{name}.ogg"
        wav = OUT / f".{name}.wav"
        encoded = np.clip(pcm * 32767, -32768, 32767).astype("<i2")
        with wave.open(str(wav), "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(RATE)
            output.writeframes(encoded.tobytes())
        subprocess.run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y", "-i", str(wav),
                        "-c:a", "libvorbis", "-q:a", "6", str(path)], check=True)
        wav.unlink()
        if not path.is_file() or path.stat().st_size < 1024:
            raise RuntimeError(f"invalid encoded audio: {name}")
        report.append((name, len(pcm) / RATE, float(np.sqrt(np.mean(pcm * pcm))), float(np.max(np.abs(pcm)))))
    for row in report:
        print("CARRIER_MINING_AUDIO", *row)


if __name__ == "__main__":
    main()
