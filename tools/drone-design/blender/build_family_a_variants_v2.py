import bpy
import importlib.util
import json
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = Path(__file__).resolve().parents[3]
BASE_OUTPUT = ROOT / "docs" / "design" / "drone-family-a" / "reference-v2"

spec = importlib.util.spec_from_file_location("family_a_scout_v2", SCRIPT_DIR / "build_family_a_scout_v2.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


def initialize_base():
    base.clear()
    bpy.ops.object.empty_add(type="PLAIN_AXES", location=(0, 0, 0))
    base.MIRROR_ORIGIN = bpy.context.object
    base.MIRROR_ORIGIN.name = "MG_V2_Centerline"
    base.MIRROR_ORIGIN.hide_render = True
    base.build()


def add_engineer():
    base.cube("ENG_ToolHousing.R", (.66, -.18, -.48), (.29, 1.18, .30), base.ARMOR_DARK, .040, mirrored=True)
    base.cube("ENG_ToolPanel.R", (.67, -.18, -.645), (.20, .82, .035), base.ARMOR, .016, mirrored=True)
    base.cube("ENG_ToolLatch.R", (.67, -.52, -.670), (.10, .18, .035), base.ORANGE, .010, mirrored=True)
    base.cylinder("ENG_EmitterHousing", (0, -.53, -.63), .20, .31, base.ARMOR_DARK, 32)
    base.cylinder("ENG_EmitterBezel", (0, -.53, -.81), .145, .065, base.EDGE, 32)
    base.cylinder("ENG_EmitterLens", (0, -.53, -.855), .095, .030, base.CYAN_CORE, 32)
    base.cube("ENG_EmitterRail", (0, -.05, -.53), (.18, 1.12, .16), base.MECH, .025)
    base.loft("ENG_PowerCowl", [
        (-.12, .20, .82, .91), (.04, .29, .82, .99),
        (.42, .30, .82, 1.02), (.67, .20, .82, .92)
    ], base.ARMOR_DARK, .030)
    base.cube("ENG_PowerPanel", (0, .32, 1.035), (.38, .34, .030), base.ARMOR, .012)
    base.cube("ENG_PowerCore", (0, .20, 1.058), (.13, .14, .018), base.CYAN, .006)
    base.cube("ENG_CowlVent.R", (.225, .48, .955), (.045, .16, .035), base.EDGE, .008, mirrored=True)
    base.cube("ENG_RearServiceMark.R", (.43, 1.52, .61), (.12, .24, .045), base.ORANGE, .012, mirrored=True)


def add_field():
    base.cube("FLD_ModuleRail.R", (.94, .14, .22), (.25, 1.92, .28), base.ARMOR_DARK, .045, mirrored=True)
    base.cube("FLD_RailFace.R", (1.075, .14, .22), (.035, 1.52, .18), base.ARMOR, .014, mirrored=True)
    base.cube("FLD_RailTop.R", (.94, .14, .375), (.16, 1.58, .035), base.EDGE, .012, mirrored=True)
    base.cube("FLD_FrontLatch.R", (.94, -.47, .410), (.13, .18, .035), base.ORANGE, .010, mirrored=True)
    base.cube("FLD_RearLatch.R", (.94, .75, .410), (.13, .18, .035), base.ORANGE, .010, mirrored=True)
    base.cube("FLD_SealedBay", (0, .24, -.55), (.68, 1.52, .22), base.ARMOR_DARK, .045)
    base.cube("FLD_BayPanel", (0, .24, -.68), (.50, 1.15, .035), base.ARMOR, .014)
    base.cube("FLD_BayLock", (0, -.12, -.705), (.15, .20, .030), base.ORANGE, .010)
    base.cube("FLD_Status.R", (1.078, -1.02, .22), (.030, .12, .10), base.CYAN, .010, mirrored=True)


def add_guard():
    base.cube("GRD_ProtectiveShoulder.R", (.82, -1.68, .10), (.46, .82, .50), base.ARMOR, .060, mirrored=True)
    base.cube("GRD_ShoulderInset.R", (1.060, -1.68, .10), (.035, .50, .27), base.MECH, .016, mirrored=True)
    base.cube("GRD_FrontThreatBezel.R", (.84, -2.105, .12), (.18, .040, .18), base.MECH, .020, mirrored=True)
    base.cube("GRD_FrontThreatSensor.R", (.84, -2.130, .12), (.09, .018, .09), base.CYAN, .012, mirrored=True)
    base.cube("GRD_SideThreatBezel.R", (1.065, -1.42, .08), (.036, .18, .16), base.MECH, .014, mirrored=True)
    base.cube("GRD_SideThreatSensor.R", (1.088, -1.42, .08), (.018, .085, .075), base.CYAN, .008, mirrored=True)
    base.cube("GRD_HardpointCover.R", (.98, .18, -.26), (.25, 1.16, .30), base.ARMOR_DARK, .045, mirrored=True)
    base.cube("GRD_HardpointPanel.R", (1.115, .18, -.26), (.030, .72, .17), base.ARMOR, .014, mirrored=True)
    base.cube("GRD_VentralArmor", (0, -1.52, -.48), (.72, .88, .24), base.ARMOR_DARK, .050)
    base.cube("GRD_VentralSensor", (0, -1.98, -.49), (.18, .030, .13), base.CYAN, .014)
    base.cube("GRD_ServiceMark.R", (.64, -1.62, .38), (.12, .20, .045), base.ORANGE, .012, mirrored=True)


ROLE_BUILDERS = {
    "engineer": add_engineer,
    "field": add_field,
    "guard": add_guard,
}


ROLE_REQUIRED = {
    "engineer": ["ENG_ToolHousing.R", "ENG_EmitterHousing", "ENG_EmitterLens"],
    "field": ["FLD_ModuleRail.R", "FLD_SealedBay", "FLD_BayLock"],
    "guard": ["GRD_ProtectiveShoulder.R", "GRD_HardpointCover.R", "GRD_VentralSensor"],
}


def render_views(role, camera):
    base.render(camera, "hero", (7.2, -9.4, 4.7), False)
    base.render(camera, "front", (0, -12, .05), True, 8.3)
    base.render(camera, "rear", (0, 12, .05), True, 8.3)
    base.render(camera, "top", (0, 0, 13), True, 9.3)
    base.render(camera, "bottom", (0, 0, -13), True, 9.3)
    base.render(camera, "left", (-13, 0, .15), True, 6.2)
    base.render(camera, "right", (13, 0, .15), True, 6.2)


def validate_and_export(role):
    objects = [o for o in bpy.context.scene.objects if o.type == "MESH" and o.name != "StudioFloor"]
    mirrored = [o for o in objects if any(m.type == "MIRROR" for m in o.modifiers)]
    missing = [name for name in ROLE_REQUIRED[role] if name not in bpy.data.objects]
    common = ["F01_Fuselage", "W02_DuctWing.R", "S01_EmbeddedVisor", "V01_RearHousing"]
    missing += [name for name in common if name not in bpy.data.objects]
    report = {
        "pass": not missing and len(objects) >= 68 and len(mirrored) >= 36,
        "role": role,
        "meshObjects": len(objects),
        "mirroredObjects": len(mirrored),
        "missingRequired": missing,
        "sharedScoutV2Base": True,
        "frontAxis": "-Y",
        "upAxis": "+Z",
        "gameImplementationApproved": False,
    }
    output = base.OUT
    (output / f"{role}-validation.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    if not report["pass"]:
        raise RuntimeError(f"{role} validation failed: {report}")
    bpy.ops.wm.save_as_mainfile(filepath=str(output / f"morrowgear-family-a-{role}-v2.blend"))
    bpy.ops.export_scene.gltf(filepath=str(output / f"morrowgear-family-a-{role}-v2.glb"), export_format="GLB")
    return report


def build_role(role):
    initialize_base()
    ROLE_BUILDERS[role]()
    base.OUT = BASE_OUTPUT / role
    base.OUTPUT_PREFIX = role
    base.OUT.mkdir(parents=True, exist_ok=True)
    camera = base.setup()
    render_views(role, camera)
    report = validate_and_export(role)
    print(json.dumps(report))


for role_name in ("engineer", "field", "guard"):
    build_role(role_name)
