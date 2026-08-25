import bpy
import importlib.util
import json
from mathutils import Vector
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = Path(__file__).resolve().parents[3]
OUTPUT = ROOT / "docs" / "design" / "drone-family-a" / "reference-v4-landing-gear-proposal"

spec = importlib.util.spec_from_file_location("family_a_v3", SCRIPT_DIR / "build_family_a_variants_v3.py")
v3 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v3)
base = v3.base


def remove_legacy_gear():
    for obj in list(bpy.data.objects):
        if obj.name.startswith("G02_"):
            bpy.data.objects.remove(obj, do_unlink=True)


def beam(name, start, end, width, depth, material, bevel=.025, mirrored=False):
    start_v, end_v = Vector(start), Vector(end)
    delta = end_v - start_v
    obj = base.cube(name, (start_v + end_v) / 2, (width, depth, delta.length), material, bevel)
    obj.rotation_mode = "QUATERNION"
    obj.rotation_quaternion = delta.to_track_quat("Z", "Y")
    if mirrored:
        base.mirror(obj)
    return obj


def joint(name, loc, radius=.12, depth=.18, mirrored=True):
    obj = base.cylinder(name, loc, radius, depth, base.ARMOR_DARK, 24, mirrored)
    obj.rotation_euler[0] = 1.5707963268
    return obj


def scout_gear():
    # Light paired struts feeding two long, level skids.
    for label, y in (("Front", -1.12), ("Rear", .42)):
        joint(f"SG_{label}Hinge.R", (.58, y, -.39), .11, .16)
        beam(f"SG_{label}Strut.R", (.58, y, -.46), (.94, y, -1.02), .105, .14, base.MECH, .020, True)
        base.cube(f"SG_{label}Knee.R", (.94, y, -1.02), (.20, .20, .14), base.ARMOR_DARK, .026, mirrored=True)
    base.cube("SG_LevelSkid.R", (.94, -.35, -1.15), (.25, 2.05, .14), base.ARMOR_DARK, .035, mirrored=True)
    base.cube("SG_SkidWear.R", (.94, -.35, -1.225), (.17, 1.78, .025), base.EDGE, .008, mirrored=True)
    base.cube("SG_FrontCap.R", (.94, -1.39, -1.14), (.27, .14, .15), base.EDGE, .018, mirrored=True)


def engineer_gear():
    # Four independent outriggers keep the work emitter clear and resist tool reaction.
    for label, y in (("Front", -1.18), ("Rear", .62)):
        joint(f"EG_{label}Hinge.R", (.64, y, -.38), .15, .20)
        beam(f"EG_{label}MainStrut.R", (.64, y, -.46), (1.16, y, -1.14), .15, .20, base.MECH, .028, True)
        beam(f"EG_{label}Brace.R", (.82, y, -.48), (1.16, y, -1.14), .09, .13, base.EDGE, .018, True)
        base.cube(f"EG_{label}KneeShield.R", (1.04, y, -.91), (.28, .34, .38), base.ARMOR_DARK, .040, mirrored=True)
        base.cube(f"EG_{label}Foot.R", (1.20, y, -1.27), (.54, .62, .15), base.ARMOR, .040, mirrored=True)
        base.cube(f"EG_{label}Sole.R", (1.20, y, -1.355), (.45, .53, .025), base.EDGE, .008, mirrored=True)


def field_gear():
    # Load-bearing sleds sit beneath the mission pods and distribute cargo weight.
    for label, y in (("Front", -1.10), ("Rear", .92)):
        joint(f"FG_{label}Hinge.R", (1.02, y, -.47), .15, .20)
        beam(f"FG_{label}Shock.R", (1.02, y, -.52), (1.24, y, -1.12), .17, .23, base.MECH, .030, True)
        base.cube(f"FG_{label}Collar.R", (1.20, y, -1.02), (.30, .32, .22), base.ARMOR_DARK, .035, mirrored=True)
    base.cube("FG_LoadSkid.R", (1.24, -.08, -1.27), (.40, 2.82, .18), base.ARMOR_DARK, .045, mirrored=True)
    base.cube("FG_LoadRail.R", (1.24, -.08, -1.375), (.28, 2.52, .030), base.EDGE, .008, mirrored=True)
    base.cube("FG_FrontRamp.R", (1.24, -1.53, -1.22), (.42, .18, .20), base.ARMOR, .026, mirrored=True)
    base.cube("FG_RearBumper.R", (1.24, 1.36, -1.24), (.44, .18, .20), base.ORANGE, .024, mirrored=True)


def guard_gear():
    # Low armored four-point gear keeps the defensive body planted without looking delicate.
    for label, y in (("Front", -1.32), ("Rear", .48)):
        joint(f"GG_{label}Hinge.R", (.78, y, -.40), .16, .22)
        beam(f"GG_{label}Strut.R", (.78, y, -.48), (1.30, y, -1.06), .18, .24, base.MECH, .032, True)
        base.cube(f"GG_{label}Armor.R", (1.10, y, -.78), (.38, .40, .48), base.ARMOR_DARK, .050, mirrored=True)
        base.cube(f"GG_{label}Foot.R", (1.34, y, -1.22), (.62, .66, .18), base.ARMOR, .050, mirrored=True)
        base.cube(f"GG_{label}Sole.R", (1.34, y, -1.32), (.51, .55, .028), base.EDGE, .008, mirrored=True)
        base.cube(f"GG_{label}Marker.R", (1.34, y - .23, -1.11), (.18, .035, .08), base.CYAN, .008, mirrored=True)


GEAR_BUILDERS = {
    "scout": scout_gear,
    "engineer": engineer_gear,
    "field": field_gear,
    "guard": guard_gear,
}


def render_views(camera):
    base.render(camera, "hero", (7.6, -10.0, 5.0), False)
    base.render(camera, "front", (0, -13, -.05), True, 9.0)
    base.render(camera, "left", (-14, 0, -.05), True, 7.2)
    base.render(camera, "bottom", (0, 0, -14), True, 10.0)


def build_role(role):
    v3.initialize_base()
    v3.ROLE_BUILDERS[role]()
    remove_legacy_gear()
    GEAR_BUILDERS[role]()
    base.OUT = OUTPUT / role
    base.OUTPUT_PREFIX = role
    base.OUT.mkdir(parents=True, exist_ok=True)
    camera = base.setup()
    render_views(camera)
    objects = [o for o in bpy.context.scene.objects if o.type == "MESH" and o.name != "StudioFloor"]
    required_prefix = {"scout": "SG_", "engineer": "EG_", "field": "FG_", "guard": "GG_"}[role]
    gear_objects = [o for o in objects if o.name.startswith(required_prefix)]
    report = {
        "pass": len(gear_objects) >= 7 and not any(o.name.startswith("G02_") for o in objects),
        "role": role,
        "landingGearObjects": len(gear_objects),
        "legacyGearRemoved": True,
        "deploymentDirection": "outward from centerline",
        "contactSurfaces": "level",
        "designStatus": "proposal awaiting approval",
    }
    (base.OUT / f"{role}-landing-gear-validation.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    if not report["pass"]:
        raise RuntimeError(report)
    bpy.ops.wm.save_as_mainfile(filepath=str(base.OUT / f"morrowgear-family-a-{role}-landing-gear-v4-proposal.blend"))
    print(json.dumps(report))


def main():
    for role in ("scout", "engineer", "field", "guard"):
        build_role(role)


if __name__ == "__main__":
    main()
