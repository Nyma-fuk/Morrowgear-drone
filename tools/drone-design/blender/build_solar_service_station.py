import bpy
import importlib.util
import math
import sys
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "docs" / "design" / "solar-service-station-v1"
OUT.mkdir(parents=True, exist_ok=True)

spec = importlib.util.spec_from_file_location("family_a_base", SCRIPT_DIR / "build_family_a_scout_v2.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


def initialize():
    base.clear()
    bpy.ops.object.empty_add(type="PLAIN_AXES", location=(0, 0, 0))
    base.MIRROR_ORIGIN = bpy.context.object
    base.MIRROR_ORIGIN.hide_render = True


def setup_render():
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_EEVEE"
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    scene.world.color = (.008, .010, .012)
    scene.view_settings.look = "AgX - Medium High Contrast"
    for location, energy, color, size in (
        ((10, -12, 12), 1500, (1.0, .91, .78), 6.0),
        ((-9, -4, 7), 850, (.12, .55, .70), 5.0),
        ((2, 10, 9), 1000, (.55, .66, .75), 5.0),
    ):
        bpy.ops.object.light_add(type="AREA", location=location)
        light = bpy.context.object
        light.data.energy = energy
        light.data.color = color
        light.data.shape = "DISK"
        light.data.size = size
        base.look(light)
    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.data.type = "ORTHO"
    bpy.context.scene.camera = camera
    return camera


def render(camera, filename, location, target, ortho, size=(900, 900)):
    camera.location = location
    base.look(camera, target)
    camera.data.ortho_scale = ortho
    scene = bpy.context.scene
    scene.render.resolution_x, scene.render.resolution_y = size
    scene.render.resolution_percentage = 100
    scene.render.filepath = str(OUT / filename)
    bpy.ops.render.render(write_still=True)


def cylinder(name, radius, depth, z, material, vertices=48):
    return base.cylinder(name, (0, 0, z), radius, depth, material, vertices)


def radial_cube(name, angle, radius, z, dimensions, material, bevel=.06):
    x, y = math.cos(angle) * radius, math.sin(angle) * radius
    return base.cube(name, (x, y, z), dimensions, material, bevel,
                     rot=(0, 0, angle + math.pi / 2))


def annular_sector(name, center, span, inner, outer, z, height, material, segments=18):
    angles = [center - span / 2 + span * index / segments for index in range(segments + 1)]
    verts = []
    for layer in (-height / 2, height / 2):
        for radius in (inner, outer):
            verts.extend((math.cos(angle) * radius, math.sin(angle) * radius, z + layer)
                         for angle in angles)
    stride = segments + 1
    faces = []
    for index in range(segments):
        a, b = index, index + 1
        faces.append((2 * stride + a, 2 * stride + b, 3 * stride + b, 3 * stride + a))
        faces.append((b, a, stride + a, stride + b))
    for index in range(segments):
        faces.append((index, index + 1, 2 * stride + index + 1, 2 * stride + index))
        faces.append((stride + index + 1, stride + index,
                      3 * stride + index, 3 * stride + index + 1))
    faces.extend(((0, 2 * stride, 3 * stride, stride),
                  (segments, stride + segments, 3 * stride + segments, 2 * stride + segments)))
    return base.mesh(name, verts, faces, material, .035)


def extruded_outline(name, points, bottom, top, material, bevel=.05):
    count = len(points)
    verts = [(x, y, bottom) for x, y in points] + [(x, y, top) for x, y in points]
    faces = [tuple(reversed(range(count))), tuple(range(count, count * 2))]
    for index in range(count):
        next_index = (index + 1) % count
        faces.append((index, next_index, count + next_index, count + index))
    return base.mesh(name, verts, faces, material, bevel)


def rotor(index, angle, radius, z, scale=1.0):
    x, y = math.cos(angle) * radius, math.sin(angle) * radius
    base.cylinder(f"RotorHousing_{index}", (x, y, z), .92 * scale, .28 * scale, base.MECH, 40)
    base.cylinder(f"RotorGlow_{index}", (x, y, z + .17 * scale), .70 * scale, .07 * scale,
                  base.CYAN, 40)
    base.cylinder(f"RotorHub_{index}", (x, y, z + .23 * scale), .16 * scale, .15 * scale,
                  base.EDGE, 24)
    for blade in range(7):
        phase = blade * math.tau / 7
        bx = x + math.cos(phase) * .42 * scale
        by = y + math.sin(phase) * .42 * scale
        base.cube(f"MG_Rotor_{index}_Blade_{blade}", (bx, by, z + .23 * scale),
                  (.52 * scale, .15 * scale, .055 * scale), base.CYAN_CORE, .025,
                  rot=(0, 0, phase + .25))


def solar_sector(index, angle):
    annular_sector(f"SolarFrame_{index}", angle, math.radians(84), 3.10, 7.42, 1.26,
                   .30, base.ARMOR_DARK)
    for lane in range(3):
        lane_center = angle + math.radians((lane - 1) * 26)
        annular_sector(f"SolarCell_{index}_{lane}", lane_center, math.radians(23), 3.38,
                       7.12, 1.44, .055,
                       base.mat(f"SolarMat_{index}_{lane}", (.008, .028, .040), .12, .24))
        annular_sector(f"SolarEdge_{index}_{lane}", lane_center, math.radians(1.2), 3.42,
                       7.08, 1.48, .018, base.CYAN)


def relay_bay(index, angle):
    radial_cube(f"BayArmor_{index}", angle, 5.45, 1.35, (2.35, 2.15, .72), base.ARMOR_DARK, .18)
    radial_cube(f"BayRecess_{index}", angle, 5.70, 1.52, (1.38, 1.25, .20), base.MECH, .12)
    radial_cube(f"BayRailL_{index}", angle - .075, 5.83, 1.65, (.18, 1.05, .16), base.CYAN, .035)
    radial_cube(f"BayRailR_{index}", angle + .075, 5.83, 1.65, (.18, 1.05, .16), base.CYAN, .035)
    radial_cube(f"BayLatch_{index}", angle, 6.18, 1.66, (.42, .22, .13), base.ORANGE, .025)


def build_station():
    initialize()
    cylinder("StationLowerArmor", 7.95, .72, .55, base.ARMOR_DARK, 72)
    cylinder("StationOuterBevel", 7.55, .48, 1.05, base.ARMOR_DARK, 72)
    cylinder("StationMechanicalRing", 4.55, .52, 1.36, base.MECH, 64)
    cylinder("StationCoreArmor", 2.20, .76, 1.62, base.ARMOR_DARK, 48)
    cylinder("StationCorePlate", 1.62, .24, 2.12, base.ARMOR, 32)
    cylinder("StationCoreGlow", 1.18, .08, 2.29, base.CYAN_CORE, 32)
    base.cube("StationCoreX1", (0, 0, 2.36), (1.30, .18, .05), base.CYAN, .02,
              rot=(0, 0, math.radians(45)))
    base.cube("StationCoreX2", (0, 0, 2.36), (1.30, .18, .05), base.CYAN, .02,
              rot=(0, 0, math.radians(-45)))
    for index in range(3):
        bay_angle = math.radians(90 + index * 120)
        solar_angle = bay_angle + math.radians(60)
        relay_bay(index + 1, bay_angle)
        solar_sector(index + 1, solar_angle)
        rotor(index + 1, bay_angle + math.radians(60), 5.25, .18, .92)
        annular_sector(f"VentralArmor_{index}", bay_angle, math.radians(92), 2.0, 7.3,
                       .18, .12, base.ARMOR)
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT / "morrowgear-solar-service-station-v1.blend"))
    camera = setup_render()
    render(camera, "station-hero.png", (13, -15, 12), (0, 0, 1), 20, (1200, 900))
    render(camera, "station-top.png", (0, 0, 22), (0, 0, 1), 18)
    render(camera, "station-side.png", (22, 0, 3), (0, 0, 1), 18, (1200, 600))
    render(camera, "station-bottom.png", (0, 0, -22), (0, 0, .8), 18)
    bpy.context.scene.render.film_transparent = True
    render(camera, "station-item-source.png", (9, -12, 10), (0, 0, 1), 19, (512, 512))


def build_relay():
    initialize()
    outline = [(0, 2.05), (1.52, .62), (1.30, -.84), (.48, -1.42),
               (-.48, -1.42), (-1.30, -.84), (-1.52, .62)]
    extruded_outline("RelayTriArmor", outline, .12, .66, base.ARMOR_DARK, .14)
    inner = [(0, 1.35), (.90, .40), (.72, -.54), (0, -.92), (-.72, -.54), (-.90, .40)]
    extruded_outline("RelayDorsalPlate", inner, .64, .84, base.ARMOR, .10)
    for index, angle in enumerate((math.radians(90), math.radians(210), math.radians(330))):
        radial_cube(f"RelayEdge_{index}", angle, 1.35, .78, (.34, .78, .12), base.EDGE, .06)
        radial_cube(f"RelayAmber_{index}", angle, 1.72, .80, (.16, .26, .08), base.ORANGE, .025)
    cylinder("RelayCoilHousing", .86, .28, .06, base.MECH, 36)
    cylinder("RelayCoilGlow", .60, .07, -.12, base.CYAN_CORE, 36)
    cylinder("RelayDorsal", .52, .13, .92, base.CYAN, 30)
    for index, angle in enumerate((0, math.pi)):
        rotor(index + 1, angle, .92, .37, .34)
    base.cube("RelayDockContact", (0, 1.42, .40), (.48, .20, .18), base.ORANGE, .04)
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT / "morrowgear-charging-relay-v1.blend"))
    camera = setup_render()
    render(camera, "relay-hero.png", (5, -7, 5), (0, 0, .35), 5.4)
    render(camera, "relay-top.png", (0, 0, 8), (0, 0, .35), 4.5)
    render(camera, "relay-bottom.png", (0, 0, -8), (0, 0, .25), 4.5)


if __name__ == "__main__":
    requested = sys.argv[sys.argv.index("--") + 1] if "--" in sys.argv else "all"
    if requested in ("station", "all"):
        build_station()
    if requested in ("relay", "all"):
        build_relay()
