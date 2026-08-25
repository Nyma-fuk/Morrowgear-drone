import bpy
import importlib.util
import math
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "docs" / "design" / "solar-service-station-v2-proposal"
OUT.mkdir(parents=True, exist_ok=True)

spec = importlib.util.spec_from_file_location("station_v1", SCRIPT_DIR / "build_solar_service_station.py")
v1 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v1)
base = v1.base


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
    scene.world.color = (.004, .006, .008)
    scene.view_settings.look = "AgX - Medium High Contrast"
    for location, energy, color, size in (
        ((10, -13, 14), 1900, (1.0, .90, .76), 6.5),
        ((-11, -5, 8), 1250, (.10, .55, .75), 6.0),
        ((2, 12, 10), 1450, (.48, .65, .78), 5.5),
        ((0, 0, -9), 950, (.15, .50, .62), 5.0),
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
    scene.camera = camera
    return camera


def render(camera, filename, location, target=(0, 0, .8), ortho=19.0, size=(1000, 1000)):
    camera.location = location
    base.look(camera, target)
    camera.data.ortho_scale = ortho
    scene = bpy.context.scene
    scene.render.resolution_x, scene.render.resolution_y = size
    scene.render.resolution_percentage = 100
    scene.render.filepath = str(OUT / filename)
    bpy.ops.render.render(write_still=True)


def annulus(name, inner, outer, z, height, material, segments=96):
    return v1.annular_sector(name, 0, math.tau, inner, outer, z, height, material, segments)


def loft_outline(name, points, layers, material, bevel=.06):
    count = len(points)
    verts = []
    for z, scale in layers:
        verts.extend((x * scale, y * scale, z) for x, y in points)
    faces = [tuple(reversed(range(count))),
             tuple(range((len(layers) - 1) * count, len(layers) * count))]
    for layer in range(len(layers) - 1):
        start = layer * count
        upper = (layer + 1) * count
        for index in range(count):
            nxt = (index + 1) % count
            faces.append((start + index, start + nxt, upper + nxt, upper + index))
    return base.mesh(name, verts, faces, material, bevel)


def radial_cube(name, angle, radius, z, size, material, bevel=.05, tangent=True):
    rotation = angle + (math.pi / 2 if tangent else 0)
    x, y = math.cos(angle) * radius, math.sin(angle) * radius
    return base.cube(name, (x, y, z), size, material, bevel, rot=(0, 0, rotation))


def radial_cylinder(name, angle, radius, z, cylinder_radius, length, material, vertices=40):
    x, y = math.cos(angle) * radius, math.sin(angle) * radius
    obj = base.cylinder(name, (x, y, z), cylinder_radius, length, material, vertices)
    obj.rotation_euler = (0, math.pi / 2, angle)
    return obj


def bolt(name, angle, radius, z):
    x, y = math.cos(angle) * radius, math.sin(angle) * radius
    return base.cylinder(name, (x, y, z), .075, .055, base.EDGE, 10)


def solar_wing(index, angle):
    span = math.radians(87)
    v1.annular_sector(f"WingLowerFrame_{index}", angle, span, 3.15, 7.80, .68, .50,
                      base.ARMOR_DARK, 30)
    v1.annular_sector(f"WingUpperFrame_{index}", angle, span - .045, 3.38, 7.58, 1.03, .26,
                      base.ARMOR, 30)
    v1.annular_sector(f"WingOuterArmor_{index}", angle, span, 7.15, 7.82, 1.23, .34,
                      base.ARMOR_DARK, 30)
    v1.annular_sector(f"WingInnerArmor_{index}", angle, span, 3.12, 3.82, 1.24, .32,
                      base.MECH, 30)

    # Five independently framed cells keep the reference's dense panel rhythm.
    for cell in range(5):
        center = angle - span / 2 + math.radians(9.2 + cell * 17.1)
        v1.annular_sector(f"SolarCell_{index}_{cell}", center, math.radians(14.8), 3.92,
                          7.02, 1.24, .075,
                          base.mat(f"SolarCellMat_{index}_{cell}", (.006, .021, .032), .55, .19))
        for rail_offset in (-math.radians(6.8), math.radians(6.8)):
            v1.annular_sector(f"CellRail_{index}_{cell}_{rail_offset:+.2f}", center + rail_offset,
                              math.radians(.42), 4.02, 6.94, 1.30, .035, base.EDGE, 4)
        for band in (4.72, 5.58, 6.40):
            radial_cube(f"CellGrid_{index}_{cell}_{band}", center, band, 1.30,
                        (1.02, .035, .025), base.EDGE, .008)
        # Recessed collector traces remain visible at Minecraft viewing distance.
        for trace in (-.045, .045):
            radial_cube(f"CellTrace_{index}_{cell}_{trace:+.3f}", center + trace,
                        5.48, 1.326, (.045, 2.72, .022), base.EDGE, .008)
        radial_cube(f"CellJunction_{index}_{cell}", center, 4.08, 1.35,
                    (.34, .22, .07), base.ORANGE if cell == 2 else base.MECH, .025)

    # Segmented perimeter armor and fasteners.
    for segment in range(9):
        a = angle - span / 2 + span * (segment + .5) / 9
        v1.annular_sector(f"OuterPlate_{index}_{segment}", a, span / 9 - .012, 7.28,
                          7.83, 1.46, .12, base.ARMOR, 5)
        bolt(f"OuterBolt_{index}_{segment}", a, 7.56, 1.56)
        radial_cube(f"OuterLatch_{index}_{segment}", a, 7.18, 1.54,
                    (.18, .34, .08), base.EDGE, .018)

    # Hinges and power couplers visually explain how each wing connects to the core.
    for side in (-1, 1):
        a = angle + side * (span / 2 - .08)
        radial_cylinder(f"WingHinge_{index}_{side}", a, 3.48, 1.52, .22, .76,
                        base.EDGE, 24)
        radial_cube(f"WingHingeGlow_{index}_{side}", a, 3.48, 1.76,
                    (.13, .52, .08), base.CYAN, .018)


def service_emitter(index, angle):
    # A radial mechanical trunk fills each gap instead of a rectangular box.
    radial_cube(f"EmitterSpine_{index}", angle, 4.75, 1.18, (1.22, 4.00, .62),
                base.MECH, .13)
    radial_cube(f"EmitterArmor_{index}", angle, 5.25, 1.52, (1.72, 2.15, .72),
                base.ARMOR_DARK, .20)
    for ring, radius in enumerate((4.05, 4.38, 4.72, 5.06)):
        radial_cylinder(f"EmitterCoil_{index}_{ring}", angle, radius, 1.50,
                        .54 - ring * .025, .16, base.CYAN if ring % 2 else base.EDGE, 32)
        radial_cube(f"EmitterRingClampA_{index}_{ring}", angle - .075, radius, 1.82,
                    (.14, .38, .16), base.ARMOR, .025)
        radial_cube(f"EmitterRingClampB_{index}_{ring}", angle + .075, radius, 1.82,
                    (.14, .38, .16), base.ARMOR, .025)
    radial_cylinder(f"EmitterNozzleHousing_{index}", angle, 5.98, 1.48, .67, .90,
                    base.ARMOR, 40)
    radial_cylinder(f"EmitterNozzleGlow_{index}", angle, 6.46, 1.48, .43, .08,
                    base.CYAN_CORE, 32)
    for side in (-1, 1):
        radial_cube(f"EmitterBrace_{index}_{side}", angle + side * .075, 5.08, 1.77,
                    (.16, 2.50, .18), base.EDGE, .025)
        for vent in range(3):
            radial_cube(f"EmitterVent_{index}_{side}_{vent}", angle + side * .105,
                        4.36 + vent * .62, 1.61, (.13, .36, .07), base.MECH, .018)
    for n in range(4):
        radial_cube(f"EmitterAmber_{index}_{n}", angle + (n - 1.5) * .025,
                    4.10 + n * .56, 1.88, (.09, .20, .07), base.ORANGE, .012)


def central_reactor():
    annulus("CoreLowerMachine", 1.05, 3.45, .90, .65, base.MECH, 72)
    annulus("CoreOuterArmor", 2.86, 3.55, 1.42, .36, base.ARMOR_DARK, 72)
    annulus("CoreEnergyRing", 2.62, 2.76, 1.60, .10, base.CYAN, 72)
    annulus("CoreInnerArmor", 1.45, 2.52, 1.68, .34, base.ARMOR, 64)
    annulus("CoreInnerGlow", 1.27, 1.39, 1.86, .08, base.CYAN, 56)
    base.cylinder("CoreAperture", (0, 0, 1.92), 1.16, .30, base.ARMOR_DARK, 40)
    base.cylinder("CoreRecess", (0, 0, 2.10), .84, .08, base.MECH, 36)
    for sign in (-1, 1):
        base.cube(f"CoreX_{sign}", (0, 0, 2.18), (1.22, .16, .055), base.CYAN_CORE,
                  .018, rot=(0, 0, sign * math.radians(45)))
    for n in range(24):
        angle = n * math.tau / 24
        radial_cube(f"CoreGreeble_{n}", angle, 2.19, 1.91,
                    (.16 if n % 2 else .24, .44, .13),
                    base.ORANGE if n % 8 == 0 else base.EDGE, .018)
        if n % 2 == 0:
            bolt(f"CoreBolt_{n}", angle, 3.17, 1.73)
    # Three structural buses tie the reactor to each emitter notch.
    for index, angle in enumerate((math.radians(90), math.radians(210), math.radians(330))):
        radial_cube(f"CoreBusArmor_{index}", angle, 3.14, 1.68,
                    (.58, 1.40, .25), base.ARMOR_DARK, .07)
        radial_cube(f"CoreBusConductor_{index}", angle, 3.14, 1.83,
                    (.13, 1.08, .045), base.CYAN, .015)
        for bank in (-.18, .18):
            radial_cube(f"CoreBusCap_{index}_{bank:+.2f}", angle + bank,
                        2.75, 1.89, (.16, .31, .10), base.EDGE, .02)


def dorsal_service_detail():
    # Mid-ring access panels, warning tabs, and cooling banks establish scale.
    for n in range(18):
        angle = n * math.tau / 18
        radial_cube(f"MidAccessPanel_{n}", angle, 3.72, 1.28,
                    (.34, .46, .12), base.ARMOR if n % 3 else base.MECH, .025)
        if n % 3 == 1:
            radial_cube(f"MidWarningTab_{n}", angle, 3.92, 1.38,
                        (.10, .18, .055), base.ORANGE, .012)
    for group, center in enumerate((math.radians(150), math.radians(270), math.radians(30))):
        for slot in range(5):
            a = center + math.radians((slot - 2) * 5.2)
            radial_cube(f"CoolingBank_{group}_{slot}", a, 3.48, 1.52,
                        (.12, .42, .13), base.EDGE, .018)


def underside():
    annulus("VentralOuterRing", 6.82, 7.70, .18, .34, base.ARMOR_DARK, 72)
    annulus("VentralOuterRecess", 6.44, 6.78, .06, .18, base.MECH, 72)
    annulus("VentralCoreArmor", 1.18, 2.55, -.02, .46, base.ARMOR_DARK, 56)
    annulus("VentralCoreCoilA", .88, 1.16, -.29, .14, base.CYAN, 48)
    annulus("VentralCoreCoilB", .50, .79, -.35, .14, base.EDGE, 40)
    base.cylinder("VentralInductionNozzle", (0, 0, -.45), .44, .20, base.CYAN_CORE, 32)

    wing_angles = (math.radians(150), math.radians(270), math.radians(30))
    emitter_angles = (math.radians(90), math.radians(210), math.radians(330))
    for index, angle in enumerate(wing_angles):
        # Five overlapping armor cassettes replace each blank triangular underside.
        for panel in range(5):
            center = angle + math.radians((panel - 2) * 16.2)
            v1.annular_sector(f"VentralWingCassette_{index}_{panel}", center,
                              math.radians(13.8), 2.55, 6.42, .16, .18,
                              base.ARMOR if panel % 2 else base.ARMOR_DARK, 10)
            for vent in range(4):
                radial_cube(f"VentralHeatSlot_{index}_{panel}_{vent}", center,
                            3.20 + vent * .77, .045, (.48, .15, .045), base.MECH, .012)
        # Two recessed bus rails per wing communicate real power distribution.
        for side in (-.11, .11):
            radial_cube(f"VentralWingBus_{index}_{side:+.2f}", angle + side,
                        4.48, -.02, (.12, 4.05, .055), base.EDGE, .015)
        radial_cube(f"VentralWingJunction_{index}", angle, 2.72, -.12,
                    (.76, .62, .18), base.MECH, .06)

    for index, angle in enumerate(emitter_angles):
        radial_cube(f"VentralPowerTrunk_{index}", angle, 4.35, -.03,
                    (1.06, 4.62, .28), base.ARMOR, .10)
        radial_cube(f"VentralConductor_{index}", angle, 4.34, -.19,
                    (.18, 3.92, .055), base.CYAN, .018)
        for node in range(4):
            radial_cube(f"VentralTrunkClamp_{index}_{node}", angle,
                        2.95 + node * .79, -.18, (1.12, .15, .09), base.EDGE, .018)

    # Six downward vectoring emitters give the floating mass a believable lift system.
    for index in range(6):
        angle = math.radians(30 + index * 60)
        x, y = math.cos(angle) * 6.48, math.sin(angle) * 6.48
        base.cylinder(f"VentralLiftHousing_{index}", (x, y, -.05), .42, .30,
                      base.ARMOR_DARK, 28)
        base.cylinder(f"VentralLiftRecess_{index}", (x, y, -.23), .29, .10,
                      base.MECH, 24)
        base.cylinder(f"VentralLiftGlow_{index}", (x, y, -.30), .20, .045,
                      base.CYAN, 24)
        for side in (-1, 1):
            radial_cube(f"VentralLiftGuard_{index}_{side}", angle + side * .045,
                        6.48, -.14, (.10, .82, .12), base.EDGE, .018)


def build_station():
    initialize()
    underside()
    for index, angle in enumerate((math.radians(150), math.radians(270), math.radians(30)), 1):
        solar_wing(index, angle)
    for index, angle in enumerate((math.radians(90), math.radians(210), math.radians(330)), 1):
        service_emitter(index, angle)
    central_reactor()
    dorsal_service_detail()
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT / "morrowgear-solar-service-station-v2-proposal.blend"))
    camera = setup_render()
    views = (
        ("station-hero.png", (13, -15, 12), (0, 0, .9), 19.5, (1200, 900)),
        ("station-front.png", (0, -22, 2.2), (0, 0, .8), 18.0, (1200, 650)),
        ("station-rear.png", (0, 22, 2.2), (0, 0, .8), 18.0, (1200, 650)),
        ("station-left.png", (-22, 0, 2.2), (0, 0, .8), 18.0, (1200, 650)),
        ("station-right.png", (22, 0, 2.2), (0, 0, .8), 18.0, (1200, 650)),
        ("station-top.png", (0, 0, 23), (0, 0, .7), 18.0, (1000, 1000)),
        ("station-bottom.png", (0, 0, -23), (0, 0, .2), 18.0, (1000, 1000)),
    )
    for args in views:
        render(camera, *args)


def build_relay():
    initialize()
    visor = base.mat("RelayVisor", (.004, .038, .052), .62, .16,
                     emission=(.006, .14, .19))
    shell = [(-.72, 1.56), (.72, 1.56), (1.12, .98), (1.34, .30),
             (1.18, -.78), (.55, -1.36), (-.55, -1.36), (-1.18, -.78),
             (-1.34, .30), (-1.05, 1.05)]
    loft_outline("RelayMonocoque", shell,
                 ((-.38, .70), (-.18, 1.00), (.38, .98), (.78, .72)),
                 base.ARMOR_DARK, .12)
    # A pointed dorsal spine establishes a clear front and replaces the stacked-puck silhouette.
    dorsal = [(-.48, 1.34), (.48, 1.34), (.70, .68), (.62, -.58),
              (0, -1.04), (-.62, -.58), (-.70, .68)]
    loft_outline("RelayDorsalSpine", dorsal,
                 ((.38, .96), (.70, .88), (.92, .56)), base.ARMOR, .09)
    for side in (-1, 1):
        side_points = [(0, 1.10), (.48, .72), (.62, -.62), (.22, -.98),
                       (-.12, -.80), (-.18, .36)]
        obj = v1.extruded_outline(f"RelayShoulder_{side}", side_points, .38, .70,
                                  base.ARMOR, .07)
        obj.scale.x = side
        obj.location.x = side * .52
        for vent in range(3):
            base.cube(f"RelaySideVent_{side}_{vent}",
                      (side * 1.17, -.28 - vent * .27, .22),
                      (.08, .18, .12), base.MECH, .015)

    # Embedded wraparound sensor: dark bezel first, smaller luminous glass second.
    base.cube("RelayVisorBezel", (0, 1.555, .37), (1.24, .10, .27),
              base.MECH, .055)
    base.cube("RelayVisorGlass", (0, 1.612, .37), (1.02, .025, .13),
              visor, .025)
    for side in (-1, 1):
        base.cube(f"RelayVisorGuard_{side}", (side * .69, 1.51, .38),
                  (.12, .18, .25), base.ARMOR, .035)

    # Recessed top status aperture and three armor seams.
    base.cylinder("RelayStatusBezel", (0, .18, .83), .42, .12, base.MECH, 30)
    base.cylinder("RelayStatusAperture", (0, .18, .91), .22, .05, base.CYAN, 28)
    for side in (-1, 1):
        base.cube(f"RelayDorsalSeam_{side}", (side * .46, .12, .73),
                  (.045, 1.62, .035), base.EDGE, .010,
                  rot=(0, 0, side * math.radians(8)))

    # Ventral charging assembly is nested inside armor instead of hanging below a flat disc.
    annulus("RelayVentralArmor", .55, .96, -.32, .18, base.ARMOR_DARK, 36)
    annulus("RelayVentralCoil", .38, .54, -.43, .10, base.CYAN, 32)
    base.cylinder("RelayVentralEmitter", (0, 0, -.50), .32, .08, base.CYAN_CORE, 28)
    for index, x in enumerate((-.70, .70)):
        base.cube(f"RelayThrusterHousing_{index}", (x, -.72, .05),
                  (.42, .72, .34), base.ARMOR_DARK, .10)
        base.cube(f"RelayThrusterGlow_{index}", (x, -1.10, .05),
                  (.25, .055, .17), base.CYAN_CORE, .025)
        for fin in (-.19, .19):
            base.cube(f"RelayThrusterFin_{index}_{fin:+.2f}", (x + fin, -.72, .20),
                      (.07, .82, .16), base.EDGE, .018)
    base.cube("RelayRearHeatSink", (0, -1.30, .22), (1.02, .14, .32),
              base.MECH, .035)
    for index in range(5):
        base.cube(f"RelayRearFin_{index}", ((index - 2) * .19, -1.39, .23),
                  (.08, .12, .38), base.EDGE, .015)
    for index, x in enumerate((-.73, .73)):
        base.cube(f"RelayDockContact_{index}", (x, .82, .12),
                  (.18, .34, .16), base.ORANGE, .03)
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT / "morrowgear-charging-relay-v2-proposal.blend"))
    camera = setup_render()
    for args in (
        ("relay-hero.png", (5, 7, 5), (0, 0, .35), 4.6, (1000, 1000)),
        ("relay-front.png", (0, 7, 1), (0, 0, .35), 4.0, (1000, 700)),
        ("relay-rear.png", (0, -7, 1), (0, 0, .35), 4.0, (1000, 700)),
        ("relay-left.png", (-7, 0, 1), (0, 0, .35), 4.0, (1000, 700)),
        ("relay-right.png", (7, 0, 1), (0, 0, .35), 4.0, (1000, 700)),
        ("relay-top.png", (0, 0, 8), (0, 0, .2), 4.0, (900, 900)),
        ("relay-bottom.png", (0, 0, -8), (0, 0, .1), 4.0, (900, 900)),
    ):
        render(camera, *args)


if __name__ == "__main__":
    build_station()
    build_relay()
