"""Add only a quiet, fixed high edge to the existing V3 laser audition."""

import argparse
import json
from pathlib import Path

import numpy as np
import soundfile as sf

from generate_drone_audio import RATE, ROOT, measurements
from propose_flight_acoustics import digest, unit_rms
from propose_rotary_laser import (
    DEFAULT_OUTPUT as BASE, SPECS, laser_cues, render, snapshot, validate_output,
)

DEFAULT_OUTPUT = ROOT / "build/verification/laser-edge-v4"
EDGE_HZ = 2200.0
EDGE_RATIO = .18


def load_base():
    report = json.loads((BASE / "report.json").read_text(encoding="utf-8"))
    clips = {}
    for name in ("laser_charge", "laser_discharge", "laser_fire", "laser_shutdown"):
        path = BASE / (name + ".ogg")
        if digest(path) != report["records"][name]["sha256"]:
            raise ValueError("V3 audio does not match its report: " + name)
        pcm, rate = sf.read(path)
        if rate != RATE or pcm.ndim != 1 or len(pcm) != round(SPECS[name][0] * RATE) or not np.isfinite(pcm).all():
            raise ValueError("Invalid base laser: " + name)
        clips[name] = pcm
    return clips


def add_edge(pcm, ratio=EDGE_RATIO):
    if not np.isfinite(ratio) or not 0 <= ratio <= .30:
        raise ValueError("Keep the added layer between zero and 30 percent RMS")
    if pcm.ndim != 1 or not len(pcm) or not np.isfinite(pcm).all():
        raise ValueError("Expected finite mono audio")
    t = np.arange(len(pcm)) / RATE
    # Integer cycles in the 1.6 s loop; no pitch modulation or detuned beating.
    edge = np.sin(2 * np.pi * EDGE_HZ * t) + .055 * np.sin(2 * np.pi * 3300 * t)
    return pcm + unit_rms(edge) * np.sqrt(np.mean(pcm ** 2)) * ratio


def protected():
    result = snapshot()
    result.update({str(p): digest(p) for p in BASE.glob("*") if p.is_file()})
    return result


def generate(directory=DEFAULT_OUTPUT):
    directory = validate_output(directory)
    if directory == BASE.resolve() or BASE.resolve() in directory.parents:
        raise ValueError("Preserve V3 for comparison")
    before = protected()
    clips = load_base()
    original = render(clips, laser_cues(), 11)
    enhanced = add_edge(clips["laser_fire"])
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "laser_fire.ogg"
    sf.write(path, enhanced, RATE, format="OGG", subtype="VORBIS")
    clips["laser_fire"], rate = sf.read(path)
    if rate != RATE or np.max(abs(clips["laser_fire"])) >= .50:
        raise ValueError("Unexpected decoded level")
    candidate = render(clips, laser_cues(), 11)
    gain = min(1, .82 / max(np.max(abs(original)), np.max(abs(candidate)), 1e-9))
    sf.write(directory / "laser.wav", candidate * gain, RATE, subtype="PCM_16")
    pair = np.concatenate((original, np.zeros(RATE), candidate)) * gain
    sf.write(directory / "laser-before-after.wav", pair, RATE, subtype="PCM_16")
    if before != protected():
        raise RuntimeError("A protected audio file changed")
    report = {"status": "audition_not_integrated", "base": str(BASE),
              "edge_hz": EDGE_HZ, "edge_rms_ratio": EDGE_RATIO,
              "changed_clip": "laser_fire", "common_comparison_gain": gain,
              "protected_audio_unchanged": True, "protected_hashes": before,
              "decoded_fire": measurements(clips["laser_fire"]),
              "candidate": measurements(candidate * gain),
              "limits": ["No listening or in-game acceptance", "RMS ratio is not perceived loudness",
                         "Fixed high tones may still be unpleasant; no hearing safety claim"]}
    (directory / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "output": str(directory),
                      "protected_audio_unchanged": True}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    generate(parser.parse_args().output)
