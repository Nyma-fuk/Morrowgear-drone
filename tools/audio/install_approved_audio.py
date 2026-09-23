"""Install the exact approved auditions, without re-encoding or changing the launcher."""

import hashlib
import json
import shutil

import soundfile as sf

from generate_drone_audio import OUTPUT, RATE, ROOT

# ID, audition path, approved Ogg SHA256, seconds, looping, attenuation distance.
APPROVED = (
    ("flight_idle", "weapon-acoustics-proposal/hybrid-hover", "9f81f235856e0e45f6d44206798061eee37079cf832ce724b43d082304fe5f63", 8, True, 48),
    ("flight_cruise", "weapon-acoustics-proposal/hybrid-cruise", "7ec9c5005e0979a3fe8763be233850b55048c980320ed1ab202ea3cbb2273fa0", 8, True, 64),
    ("autocannon_burst", "rotary-laser-v3/autocannon_start", "8c24bc89a2d23f6b9d4ac275f0fc115f1910cd0fac43db55ce23cf4abd489e58", .12, False, 64),
    ("autocannon_start", "rotary-laser-v3/autocannon_start", "8c24bc89a2d23f6b9d4ac275f0fc115f1910cd0fac43db55ce23cf4abd489e58", .12, False, 64),
    ("autocannon_fire", "rotary-laser-v3/autocannon_fire", "73d68e1f033ce05c0c59a096f07e3e173ba49815a60afa0bc1b212b790d7184e", 1.2, True, 64),
    ("autocannon_stop", "rotary-laser-v3/autocannon_stop", "4b958f966e505cfecaa3047dc6b707413bab0d48bf081456cde769dcb6a87b41", .38, False, 64),
    ("autocannon_impact", "weapon-pressure-v2/autocannon_impact", "a555b179e1aa32496d4a1689216e307045d8b87bbd05bfb2d64500a106878ace", .23, False, 40),
    ("laser_charge", "rotary-laser-v3/laser_charge", "37def43db58e5b635d00d19d643abf431bae73366894db821b0bb09fbc3dd5cc", 2.75, False, 36),
    ("laser_discharge", "rotary-laser-v3/laser_discharge", "a41ecbdb8500d2f7cd84c2738d8147c623f4e5bd5a0bb4d35530aac46383d6ab", .42, False, 48),
    ("laser_fire", "laser-edge-v4/laser_fire", "64125f11afed44f2247f6305168193326fae95f60cf1a1387e1cd3eadeac95ba", 1.6, True, 48),
    ("laser_shutdown", "rotary-laser-v3/laser_shutdown", "6f298077cf172ec9d5520438c3e33e1c302efd7afc299360b51f197aeb2d039d", 2.2, False, 40),
    ("laser_hit", "weapon-pressure-v2/laser_hit", "32b37b99915d69c4b489afa9289eb18471a7d812126ccba84aed3f7f2654a4a7", .45, False, 40),
    ("missile_launch", "weapon-pressure-v2/missile_launch", "d0abf043e8aca1fca6f394b2216c486ce64e2c25e9d37a6a1dc0b436d75bb931", .20, False, 64),
    ("missile_ignition", "weapon-pressure-v2/missile_ignition", "f581e84224288537d7ce6d6e176ec160ee5c1ae16dadf46fc5c03a8d1588191c", .40, False, 64),
    ("missile_motor", "weapon-pressure-v2/missile_motor", "9388b5e885c340ad0f3ae0ef43d0304cbef110a83f1b148655be792ff9016204", 1.6, True, 64),
    ("missile_impact", "weapon-pressure-v2/missile_impact", "d6729888ee84ffe928afe85343122e47be22f97fb8b843cde33d4a06ffa34d4e", .25, False, 48),
    ("missile_explosion", "weapon-pressure-v2/missile_explosion", "c543c3b8c0808c4d4b97f1cd769eb3f9fa8eac645c53afd51a82e2bc8d49c2ec", 3.2, False, 80),
    ("missile_debris", "weapon-pressure-v2/missile_debris", "be2e804d2f8ac3a08a97c021b508601b7b74e489c1ac9bb6fbe7b77c27c8b735", 3.2, False, 48),
)


def install():
    assets = {}
    for name, relative, digest, duration, loop, distance in APPROVED:
        source = ROOT / "build/verification" / (relative + ".ogg")
        if hashlib.sha256(source.read_bytes()).hexdigest() != digest:
            raise ValueError(f"Unapproved or changed audition: {source}")
        info = sf.info(source)
        if (info.format, info.subtype, info.channels, info.samplerate, info.frames) != (
                "OGG", "VORBIS", 1, RATE, round(duration * RATE)):
            raise ValueError(f"Invalid approved audio format: {source}")
        assets[name] = dict(source=relative, sha256=digest, duration=duration,
                            loop=loop, attenuation_distance=distance)
    # Validate the whole selection before touching the runtime resource folder.
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, asset in assets.items():
        shutil.copyfile(ROOT / "build/verification" / (asset["source"] + ".ogg"), OUTPUT / (name + ".ogg"))
    (OUTPUT.parent / "audio-approved.json").write_text(json.dumps(
        dict(approval="2026-09-22", sample_rate=RATE, assets=assets), indent=2) + "\n", encoding="utf-8")
    print(f"Installed {len(assets)} approved sound IDs; launcher unchanged.")


if __name__ == "__main__":
    install()
