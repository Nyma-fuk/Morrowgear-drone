"""Deterministic carrier cargo and heavy-missile machinery synthesis.

All assets are original procedural audio.  The layers model actuator torque,
gear/drum vibration, cable tension, hydraulics, pressure ejection and rocket
combustion instead of pitch-shifting the small-drone sounds.
"""
from pathlib import Path
import numpy as np
import shutil
import subprocess
import tempfile
import wave

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/main/resources/assets/morrowgear_drone/sounds"
RATE = 48_000
RNG = np.random.default_rng(0x4D475243)
FFMPEG = shutil.which("ffmpeg") or str(
    ROOT.parent / "morrowgear-drone/node_modules/@ffmpeg-installer/win32-x64/ffmpeg.exe")


def time(seconds):
    return np.arange(int(seconds * RATE), dtype=np.float64) / RATE


def noise(seconds, cutoff=1800):
    data = RNG.normal(0, 1, len(time(seconds)))
    spectrum = np.fft.rfft(data)
    frequency = np.fft.rfftfreq(len(data), 1 / RATE)
    spectrum *= np.clip(1 - (frequency - cutoff * .65) / (cutoff * .35), 0, 1) ** 2
    return np.fft.irfft(spectrum, len(data))


def tone(t, frequency, phase=0):
    return np.sin(2 * np.pi * frequency * t + phase)


def edge(t, attack=.015, release=.12):
    return np.minimum(1, t / attack) * np.minimum(1, (t[-1] - t) / release)


def pulse(t, at, width, frequency, gain=1):
    x = np.maximum(0, t - at)
    return gain * np.exp(-x / width) * tone(t, frequency) * (t >= at)


def write(name, signal):
    signal = np.tanh(signal * 1.25)
    peak = np.max(np.abs(signal)) or 1
    pcm = np.round(np.clip(signal / peak * .78, -1, 1) * 32767).astype("<i2")
    with tempfile.TemporaryDirectory() as folder:
        source = Path(folder) / f"{name}.wav"
        with wave.open(str(source), "wb") as output:
            output.setnchannels(1); output.setsampwidth(2); output.setframerate(RATE)
            output.writeframes(pcm.tobytes())
        subprocess.run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y", "-i", str(source),
                        "-c:a", "libvorbis", "-q:a", "5", str(OUT / f"{name}.ogg")], check=True)


def actuator(name, seconds, base, load=.35, loop=False):
    t = time(seconds)
    rpm = tone(t, base) + .42 * tone(t, base * 2) + .18 * tone(t, base * 5)
    gear = .18 * tone(t, base * 9) * (1 + .35 * tone(t, 2.0))
    body = .25 * tone(t, base / 2) + .08 * noise(seconds, 750)
    env = np.ones_like(t) if loop else edge(t, .06, .16)
    write(name, env * (load * rpm + gear + body))


def impact(name, seconds, hits, resonance=58, hydraulic=False):
    t = time(seconds)
    s = .03 * noise(seconds, 2400)
    for at, gain in hits:
        s += pulse(t, at, .055, 680, .22 * gain)
        s += pulse(t, at, .18, resonance, .9 * gain)
        s += pulse(t, at, .11, resonance * 2.1, .35 * gain)
    if hydraulic:
        gate = (t > .12) * np.exp(-np.maximum(0, t - .12) / .55)
        s += gate * (.18 * noise(seconds, 560) + .24 * tone(t, 92))
    write(name, edge(t, .006, .14) * s)


def missile(name, seconds, kind):
    t = time(seconds)
    if kind == "hatch":
        s = edge(t, .03, .12) * (.24 * tone(t, 42) + .22 * tone(t, 118)
            + .1 * tone(t, 640) + .08 * noise(seconds, 1300))
    elif kind == "eject":
        s = edge(t, .004, .18) * (.9 * np.exp(-t / .18) * noise(seconds, 900)
            + .55 * pulse(t, .015, .22, 48) + .22 * pulse(t, .08, .12, 210))
    elif kind == "ignition":
        s = edge(t, .003, .28) * ((1-np.exp(-t/.035)) * np.exp(-t/.8)
            * (.65 * noise(seconds, 1200) + .48 * tone(t, 55) + .18 * tone(t, 165)))
    elif kind == "motor":
        s = edge(t, .04, .18) * (.46 * noise(seconds, 980) + .38 * tone(t, 46)
            + .2 * tone(t, 92) + .08 * tone(t, 690))
    elif kind == "penetration":
        s = edge(t, .003, .2) * (.5 * noise(seconds, 1500) + .5 * tone(t, 52)
            + sum(pulse(t, .05+i*.115, .07, 96+i*7, .55) for i in range(7)))
    else:
        s = edge(t, .002, .65) * (1.15 * np.exp(-t/.6) * noise(seconds, 620)
            + 1.2 * pulse(t, .015, .65, 34) + .7 * pulse(t, .12, .5, 51)
            + .22 * np.exp(-np.maximum(0,t-.35)/1.0) * noise(seconds, 1300) * (t>.35))
    write(name, s)


OUT.mkdir(parents=True, exist_ok=True)
impact("carrier_cargo_hatch_unlock", .75, [(0.05,.75),(.22,1)], 72)
actuator("carrier_cargo_hatch_move", 1.45, 31, .48)
actuator("carrier_winch_start", .95, 39, .38)
actuator("carrier_winch_hoist", 2.0, 36, .62, True)
actuator("carrier_winch_lower", 2.0, 29, .52, True)
impact("carrier_winch_brake", .72, [(0.03,.75),(.17,.48)], 64)
impact("carrier_cable_tension", 1.0, [(0.04,.45),(.16,.58),(.3,.72),(.44,1)], 84)
impact("carrier_container_rail", 1.25, [(0.06,.42),(.28,.52),(.5,.68),(.75,.9)], 49)
impact("carrier_container_latch", .95, [(0.05,.7),(.23,1),(.38,.62)], 58)
impact("carrier_cargo_hatch_seal", .85, [(0.05,.62),(.2,.92),(.42,.38)], 61, True)
impact("carrier_container_ground", 1.35, [(0.02,.55),(.16,.72),(.3,.86),(.47,1)], 39)
impact("carrier_container_hydraulic", 1.55, [(.12,.35),(.42,.48),(.8,.58)], 47, True)
missile("carrier_missile_hatch", 1.5, "hatch")
missile("carrier_missile_eject", 1.0, "eject")
missile("carrier_missile_ignition", 1.35, "ignition")
missile("carrier_missile_motor", 2.0, "motor")
missile("carrier_missile_penetration", 1.35, "penetration")
missile("carrier_missile_explosion", 2.8, "explosion")
print("generated carrier mechanism audio", OUT)
