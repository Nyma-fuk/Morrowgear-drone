import bpy
import importlib.util
import json
import math
from mathutils import Vector
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "docs" / "design" / "equipment-v1-proposal"
OUT.mkdir(parents=True, exist_ok=True)

spec = importlib.util.spec_from_file_location("family_a_scout_v2", SCRIPT_DIR / "build_family_a_scout_v2.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


def initialize():
    base.clear()
    bpy.ops.object.empty_add(type="PLAIN_AXES", location=(0, 0, 0))
    base.MIRROR_ORIGIN = bpy.context.object
    base.MIRROR_ORIGIN.name = "MG_Equipment_Centerline"
    base.MIRROR_ORIGIN.hide_render = True


def octagonal_box(name, width, depth, height, chamfer, z, material, bevel=.035):
    x, y = width / 2, depth / 2
    outline = [(-x + chamfer, -y), (x - chamfer, -y), (x, -y + chamfer), (x, y - chamfer),
               (x - chamfer, y), (-x + chamfer, y), (-x, y - chamfer), (-x, -y + chamfer)]
    verts = [(px, py, z - height / 2) for px, py in outline] + [(px, py, z + height / 2) for px, py in outline]
    faces = [tuple(reversed(range(8))), tuple(range(8, 16))]
    for i in range(8):
        j = (i + 1) % 8
        faces.append((i, j, 8 + j, 8 + i))
    return base.mesh(name, verts, faces, material, bevel)


def beam(name, start, end, width, depth, material, bevel=.025):
    start_v, end_v = Vector(start), Vector(end)
    delta = end_v - start_v
    obj = base.cube(name, (start_v + end_v) / 2, (width, depth, delta.length), material, bevel)
    obj.rotation_mode = "QUATERNION"
    obj.rotation_quaternion = delta.to_track_quat("Z", "Y")
    return obj


def add_cartridge_shell(prefix, accent=base.CYAN):
    base.cube(f"{prefix}_Core", (0, 0, 0), (1.18, .52, 1.62), base.ARMOR_DARK, .12)
    base.cube(f"{prefix}_FrontPlate", (0, -.29, .02), (.91, .08, 1.30), base.ARMOR, .08)
    base.cube(f"{prefix}_TopCap", (0, 0, .88), (.80, .48, .18), base.EDGE, .06)
    base.cube(f"{prefix}_BottomLock", (0, -.31, -.73), (.38, .08, .15), base.ORANGE, .035)
    base.cube(f"{prefix}_SideRail.R", (.55, -.05, 0), (.10, .48, 1.22), base.MECH, .035, mirrored=True)
    base.cube(f"{prefix}_Status.R", (.49, -.315, .52), (.09, .035, .18), accent, .018, mirrored=True)


def build_scout_module():
    add_cartridge_shell("SCT")
    base.cube("SCT_SensorRecess", (0, -.35, .14), (.55, .055, .70), base.MECH, .06)
    base.cube("SCT_LongLens", (0, -.383, .18), (.22, .025, .56), base.CYAN_CORE, .025)
    base.cube("SCT_Antenna.R", (.26, -.02, 1.04), (.09, .12, .48), base.ARMOR_DARK, .025,
              rot=(math.radians(-8), 0, math.radians(-6)), mirrored=True)
    base.cube("SCT_AntennaTip.R", (.29, -.03, 1.27), (.07, .10, .12), base.CYAN, .020, mirrored=True)


def build_cargo_module():
    add_cartridge_shell("CRG")
    base.cube("CRG_CargoDoor", (0, -.35, .10), (.72, .06, .92), base.MECH, .055)
    for i, z in enumerate((.42, .10, -.22)):
        base.cube(f"CRG_Cell_{i}", (0, -.387, z), (.53, .028, .18), base.ORANGE if i == 1 else base.EDGE, .025)
    base.cube("CRG_LockBar", (0, -.405, -.48), (.24, .025, .13), base.CYAN, .020)
    base.cube("CRG_SideBumper.R", (.64, 0, -.10), (.14, .48, .74), base.ARMOR_DARK, .045, mirrored=True)


def build_engineer_module():
    add_cartridge_shell("ENG")
    base.cylinder("ENG_EmitterBezel", (0, -.34, .10), .35, .10, base.MECH, 32)
    bpy.context.object.rotation_euler[0] = math.radians(90)
    base.cylinder("ENG_EmitterRing", (0, -.40, .10), .25, .055, base.EDGE, 32)
    bpy.context.object.rotation_euler[0] = math.radians(90)
    base.cylinder("ENG_EmitterLens", (0, -.435, .10), .15, .025, base.CYAN_CORE, 32)
    bpy.context.object.rotation_euler[0] = math.radians(90)
    base.cube("ENG_ToolJaw.R", (.31, -.38, -.41), (.18, .08, .32), base.ARMOR_DARK, .035,
              rot=(0, 0, math.radians(-12)), mirrored=True)


def build_security_module():
    add_cartridge_shell("SEC")
    # Closed defensive plate: no exposed weapon and no permanent combat-red emission.
    base.cube("SEC_Shield", (0, -.36, .08), (.76, .07, .94), base.ARMOR_DARK, .14)
    base.cube("SEC_UpperArmor", (0, -.405, .34), (.53, .035, .32), base.ARMOR, .07)
    base.cube("SEC_ThreatBezel", (0, -.43, .02), (.42, .025, .30), base.MECH, .055)
    base.cube("SEC_ThreatSensor", (0, -.452, .02), (.24, .018, .13), base.CYAN_CORE, .025)
    base.cube("SEC_AegisCheek.R", (.43, -.38, -.18), (.18, .08, .58), base.ARMOR, .055,
              rot=(0, 0, math.radians(-8)), mirrored=True)


def build_drone_unit():
    # Folded Family A airframe supplied as a persistent unit, not a spawn egg.
    base.loft("UNIT_Fuselage", [(-.85, .20, -.10, .15), (-.45, .35, -.16, .28),
                                (.35, .38, -.15, .34), (.88, .22, -.08, .18)], base.ARMOR_DARK, .06)
    base.cube("UNIT_Wing.R", (.56, .06, .03), (.78, .58, .20), base.ARMOR, .08,
              rot=(0, 0, math.radians(8)), mirrored=True)
    base.cylinder("UNIT_Duct.R", (.58, .06, .15), .27, .12, base.MECH, 28, True)
    base.cylinder("UNIT_Hub.R", (.58, .06, .23), .07, .08, base.EDGE, 20, True)
    base.ribbon("UNIT_Visor", [(-.18, -.87, .06), (0, -.90, .04), (.18, -.87, .06)], .025, base.CYAN_CORE)
    base.cube("UNIT_CarryLock", (0, .42, .32), (.20, .22, .09), base.ORANGE, .025)
    base.cube("UNIT_FoldedSkid.R", (.23, -.05, -.31), (.11, .65, .10), base.ARMOR_DARK, .025, mirrored=True)


def build_dock_item():
    octagonal_box("DCKI_Base", 2.25, 2.25, .28, .30, -.15, base.ARMOR_DARK, .07)
    octagonal_box("DCKI_Deck", 1.92, 1.92, .15, .24, .05, base.ARMOR, .05)
    octagonal_box("DCKI_Cradle", .92, .92, .16, .18, .17, base.MECH, .04)
    base.cube("DCKI_ContactX", (0, 0, .27), (.68, .13, .035), base.CYAN_CORE, .018)
    base.cube("DCKI_ContactY", (0, 0, .27), (.13, .68, .035), base.CYAN_CORE, .018)
    for x, y, rz in ((0, -.82, 0), (0, .82, 0), (-.82, 0, 90), (.82, 0, 90)):
        base.cube(f"DCKI_Lock_{x}_{y}", (x, y, .20), (.34, .12, .08), base.ORANGE, .018,
                  rot=(0, 0, math.radians(rz)))


def build_controller():
    # Rugged one-hand tactical terminal with a readable screen and physical controls.
    base.cube("CTL_Body", (0, 0, .20), (2.28, .48, 1.42), base.ARMOR_DARK, .16)
    base.cube("CTL_FrontFrame", (-.18, -.285, .30), (1.56, .09, .94), base.ARMOR, .10)
    base.cube("CTL_ScreenRecess", (-.20, -.345, .38), (1.20, .035, .62), base.MECH, .07)
    base.cube("CTL_Screen", (-.20, -.373, .38), (1.04, .022, .49), base.CYAN, .045)
    base.cube("CTL_MapGridV", (-.20, -.388, .38), (.025, .015, .40), base.CYAN_CORE, .006)
    base.cube("CTL_MapGridH", (-.20, -.388, .38), (.80, .015, .025), base.CYAN_CORE, .006)
    base.cube("CTL_ControlBay", (.82, -.33, .25), (.40, .06, .82), base.MECH, .065)
    base.cylinder("CTL_Wheel", (.82, -.385, .46), .13, .055, base.EDGE, 24)
    bpy.context.object.rotation_euler[0] = math.radians(90)
    for x, z in ((.73, .16), (.91, .16), (.73, -.06), (.91, -.06)):
        base.cylinder(f"CTL_Key_{x}_{z}", (x, -.39, z), .055, .045, base.CYAN_CORE if z > 0 else base.ORANGE, 16)
        bpy.context.object.rotation_euler[0] = math.radians(90)
    base.cube("CTL_TopRail", (0, 0, 1.01), (1.42, .38, .17), base.EDGE, .055)
    base.cube("CTL_Antenna.R", (.76, .02, 1.22), (.10, .16, .46), base.ARMOR_DARK, .035,
              rot=(math.radians(-8), 0, 0), mirrored=True)
    base.cube("CTL_AntennaTip.R", (.76, -.01, 1.43), (.07, .13, .11), base.CYAN, .025, mirrored=True)
    base.cube("CTL_Grip", (.58, .08, -.78), (.72, .58, .82), base.ARMOR_DARK, .14,
              rot=(math.radians(-8), 0, math.radians(-5)))
    base.cube("CTL_GripInsert", (.58, -.225, -.78), (.48, .035, .56), base.MECH, .08,
              rot=(math.radians(-8), 0, math.radians(-5)))
    base.cube("CTL_Trigger", (.58, -.34, -.48), (.22, .09, .16), base.ORANGE, .035)


ITEM_BUILDERS = {
    "field_drone_unit": build_drone_unit,
    "scout_module": build_scout_module,
    "cargo_module": build_cargo_module,
    "engineer_module": build_engineer_module,
    "security_module": build_security_module,
    "dock_item": build_dock_item,
    "controller": build_controller,
}


def setup_scene(transparent=True):
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_EEVEE"
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = transparent
    scene.world.color = (.025, .028, .030)
    scene.view_settings.look = "AgX - Medium High Contrast"
    for loc, energy, color, size in [
        ((4, -6, 6), 1000, (1, .92, .82), 4.0),
        ((-4, -2, 3), 420, (.22, .48, .56), 3.0),
        ((1, 5, 4), 800, (.65, .72, .78), 3.0),
    ]:
        bpy.ops.object.light_add(type="AREA", location=loc)
        light = bpy.context.object
        light.data.energy = energy
        light.data.color = color
        light.data.shape = "DISK"
        light.data.size = size
        base.look(light)
    bpy.ops.object.camera_add()
    camera = bpy.context.object
    scene.camera = camera
    return camera


def render(camera, path, location, target=(0, 0, .1), ortho=3.5, size=(700, 700)):
    scene = bpy.context.scene
    camera.location = location
    base.look(camera, target)
    camera.data.type = "ORTHO"
    camera.data.ortho_scale = ortho
    scene.render.resolution_x, scene.render.resolution_y = size
    scene.render.resolution_percentage = 100
    scene.render.filepath = str(path)
    bpy.ops.render.render(write_still=True)


def build_item(name, builder):
    initialize()
    builder()
    camera = setup_scene(True)
    item_dir = OUT / "items" / name
    item_dir.mkdir(parents=True, exist_ok=True)
    scale = 3.6 if name in ("controller", "dock_item") else 2.9
    render(camera, item_dir / f"{name}-icon-proposal.png", (3.8, -6.5, 3.2), ortho=scale)
    render(camera, item_dir / f"{name}-front.png", (0, -7, .15), ortho=scale)
    bpy.ops.wm.save_as_mainfile(filepath=str(item_dir / f"morrowgear-{name}-v1-proposal.blend"))


def build_full_dock():
    initialize()
    octagonal_box("DOCK_Base", 9.0, 9.0, .42, .72, .21, base.ARMOR_DARK, .10)
    octagonal_box("DOCK_Deck", 8.35, 8.35, .28, .65, .56, base.ARMOR, .08)
    octagonal_box("DOCK_Recess", 5.55, 5.55, .22, .55, .74, base.MECH, .07)
    octagonal_box("DOCK_Cradle", 3.25, 3.25, .18, .42, .87, base.ARMOR_DARK, .055)
    # Four open approach ramps; no pillars or side obstruction.
    for name, loc, scale in [
        ("North", (0, -4.12, .70), (4.2, 1.15, .20)), ("South", (0, 4.12, .70), (4.2, 1.15, .20)),
        ("West", (-4.12, 0, .70), (1.15, 4.2, .20)), ("East", (4.12, 0, .70), (1.15, 4.2, .20)),
    ]:
        base.cube(f"DOCK_Approach_{name}", loc, scale, base.ARMOR_DARK, .10)
    base.cube("DOCK_ContactX", (0, 0, 1.01), (2.35, .23, .07), base.CYAN_CORE, .035)
    base.cube("DOCK_ContactY", (0, 0, 1.01), (.23, 2.35, .07), base.CYAN_CORE, .035)
    for x, y, rz in ((0, -2.10, 0), (0, 2.10, 0), (-2.10, 0, 90), (2.10, 0, 90)):
        base.cube(f"DOCK_Guide_{x}_{y}", (x, y, .96), (1.12, .28, .16), base.EDGE, .045,
                  rot=(0, 0, math.radians(rz)))
        base.cube(f"DOCK_Lock_{x}_{y}", (x, y, 1.06), (.34, .18, .05), base.ORANGE, .018,
                  rot=(0, 0, math.radians(rz)))
    # Flush perimeter light segments keep the platform readable at night.
    for x, y, sx, sy in ((0, -3.72, 3.2, .10), (0, 3.72, 3.2, .10), (-3.72, 0, .10, 3.2), (3.72, 0, .10, 3.2)):
        base.cube(f"DOCK_Perimeter_{x}_{y}", (x, y, .86), (sx, sy, .045), base.CYAN, .015)
    camera = setup_scene(False)
    bpy.ops.mesh.primitive_plane_add(size=30, location=(0, 0, -.03))
    floor = bpy.context.object
    floor.data.materials.append(base.mat("EquipmentFloor", (.055, .058, .058), .1, .75))
    dock_dir = OUT / "dock"
    dock_dir.mkdir(parents=True, exist_ok=True)
    render(camera, dock_dir / "dock-hero.png", (10.5, -12.5, 9.0), target=(0, 0, .45), ortho=13.0, size=(1200, 850))
    render(camera, dock_dir / "dock-top.png", (0, 0, 15), target=(0, 0, .45), ortho=11.0, size=(900, 900))
    render(camera, dock_dir / "dock-side.png", (13, 0, 2.7), target=(0, 0, .45), ortho=11.0, size=(1200, 650))
    bpy.ops.wm.save_as_mainfile(filepath=str(dock_dir / "morrowgear-dock-v1-proposal.blend"))


def build_controller_held():
    initialize()
    build_controller()
    # A block-proportioned hand/forearm is only a scale and grip reference.
    skin = base.mat("HeldPreviewSkin", (.36, .19, .12), .05, .72)
    sleeve = base.mat("HeldPreviewSleeve", (.055, .07, .075), .15, .58)
    base.cube("HAND_Palm", (.58, .18, -.80), (.78, .78, .72), skin, .08)
    base.cube("HAND_Forearm", (.78, .72, -1.24), (.88, 1.25, .90), sleeve, .08,
              rot=(math.radians(-20), 0, math.radians(-8)))
    camera = setup_scene(False)
    held_dir = OUT / "controller"
    held_dir.mkdir(parents=True, exist_ok=True)
    render(camera, held_dir / "controller-held-third-person.png", (4.2, -7.0, 2.8), target=(0, 0, 0), ortho=4.6, size=(900, 900))
    render(camera, held_dir / "controller-held-first-person.png", (2.4, -6.8, 2.1), target=(0, 0, .1), ortho=4.0, size=(1100, 800))
    bpy.ops.wm.save_as_mainfile(filepath=str(held_dir / "morrowgear-controller-held-v1-proposal.blend"))


def main():
    for name, builder in ITEM_BUILDERS.items():
        build_item(name, builder)
    build_full_dock()
    build_controller_held()
    report = {
        "pass": True,
        "items": list(ITEM_BUILDERS),
        "dock": "pillarless low-profile 3x3 platform",
        "controller": "shared icon and held-model source",
        "designStatus": "proposal awaiting approval",
    }
    (OUT / "equipment-v1-validation.json").write_text(json.dumps(report, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
