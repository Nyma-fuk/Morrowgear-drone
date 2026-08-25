import bpy
import importlib.util
import json
import math
import sys
from mathutils import Vector
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = Path(__file__).resolve().parents[3]
BASE_OUTPUT = ROOT / "docs" / "design" / "drone-family-a" / "reference-v3"

spec = importlib.util.spec_from_file_location("family_a_scout_v2", SCRIPT_DIR / "build_family_a_scout_v2.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


def initialize_base():
    base.clear()
    bpy.ops.object.empty_add(type="PLAIN_AXES", location=(0, 0, 0))
    base.MIRROR_ORIGIN = bpy.context.object
    base.MIRROR_ORIGIN.name = "MG_V3_Centerline"
    base.MIRROR_ORIGIN.hide_render = True
    base.build()


def beam(name, start, end, width, depth, material, bevel=.025, mirrored=False):
    start_v, end_v = Vector(start), Vector(end)
    delta = end_v - start_v
    obj = base.cube(name, (start_v + end_v) / 2, (width, depth, delta.length), material, bevel)
    obj.rotation_mode = "QUATERNION"
    obj.rotation_quaternion = delta.to_track_quat("Z", "Y")
    if mirrored:
        base.mirror(obj)
    return obj


def wedge(name, x0, x1, sections, material, bevel=.04, mirrored=False):
    # Each section is (y, lower_z, upper_z); x tapers independently at both ends.
    verts = []
    for x in (x0, x1):
        for y, z0, z1 in sections:
            verts.extend([(x, y, z0), (x, y, z1)])
    n = len(sections)
    faces = []
    for side in range(2):
        offset = side * n * 2
        faces.append(tuple(offset + i for i in range(n * 2)))
    for i in range(n - 1):
        a, b = i * 2, (i + 1) * 2
        c, d = n * 2 + a, n * 2 + b
        faces.extend([(a, b, d, c), (a + 1, c + 1, d + 1, b + 1)])
    faces.extend([(0, n * 2, n * 2 + 1, 1), (n * 2 - 2, n * 4 - 2, n * 4 - 1, n * 2 - 1)])
    obj = base.mesh(name, verts, faces, material, bevel)
    return base.mirror(obj) if mirrored else obj


def annular_ring(name, cx, cy, outer_radius, inner_radius, z0, z1, material,
                 segments=64, mirrored=False, bevel=.018):
    """Build a genuinely open duct with visible top, bottom, inner, and outer walls."""
    verts = []
    for z in (z0, z1):
        for radius in (outer_radius, inner_radius):
            for index in range(segments):
                angle = math.tau * index / segments
                verts.append((cx + math.cos(angle) * radius,
                              cy + math.sin(angle) * radius, z))
    faces = []
    outer_bottom, inner_bottom = 0, segments
    outer_top, inner_top = segments * 2, segments * 3
    for index in range(segments):
        next_index = (index + 1) % segments
        faces.extend([
            (outer_top + index, outer_top + next_index,
             inner_top + next_index, inner_top + index),
            (outer_bottom + next_index, outer_bottom + index,
             inner_bottom + index, inner_bottom + next_index),
            (outer_bottom + index, outer_bottom + next_index,
             outer_top + next_index, outer_top + index),
            (inner_bottom + next_index, inner_bottom + index,
             inner_top + index, inner_top + next_index),
        ])
    # Separate edge lips provide the highlight; smooth shading avoids an expensive
    # bevel expansion in the runtime mesh.
    obj = base.mesh(name, verts, faces, material, 0, True)
    return base.mirror(obj) if mirrored else obj


def rotor_blade(name, cx, cy, angle, z0, z1, material, mirrored=True):
    """Create a thick swept blade with a shouldered hub root."""
    local = [
        (.145, -.115), (.285, -.145), (.665, -.190),
        (.790, -.060), (.720, .035), (.335, .120), (.155, .092),
    ]
    cosine, sine = math.cos(angle), math.sin(angle)
    ring = [(cx + x * cosine - y * sine, cy + x * sine + y * cosine)
            for x, y in local]
    verts = [(x, y, z) for z in (z0, z1) for x, y in ring]
    count = len(ring)
    faces = [tuple(range(count)), tuple(range(count * 2 - 1, count - 1, -1))]
    for index in range(count):
        next_index = (index + 1) % count
        faces.append((index, next_index, count + next_index, count + index))
    obj = base.mesh(name, verts, faces, material, .014)
    return base.mirror(obj) if mirrored else obj


def rotor_blade_inset(name, cx, cy, angle, z0, z1, material, mirrored=True):
    """Create a rigid luminous insert that overlaps the blade skin."""
    local = [
        (.285, -.028), (.565, -.052), (.645, -.027),
        (.610, .008), (.350, .038), (.295, .026),
    ]
    cosine, sine = math.cos(angle), math.sin(angle)
    ring = [(cx + x * cosine - y * sine, cy + x * sine + y * cosine)
            for x, y in local]
    verts = [(x, y, z) for z in (z0, z1) for x, y in ring]
    count = len(ring)
    faces = [tuple(range(count)), tuple(range(count * 2 - 1, count - 1, -1))]
    for index in range(count):
        next_index = (index + 1) % count
        faces.append((index, next_index, count + next_index, count + index))
    obj = base.mesh(name, verts, faces, material, .006)
    return base.mirror(obj) if mirrored else obj


def rotor_root_mount(name, cx, cy, angle, z0, z1, material, mirrored=True):
    """Mechanical shoe joining each blade to the rotating hub."""
    local = [(.105, -.135), (.285, -.150), (.335, .090), (.115, .125)]
    cosine, sine = math.cos(angle), math.sin(angle)
    ring = [(cx + x * cosine - y * sine, cy + x * sine + y * cosine)
            for x, y in local]
    verts = [(x, y, z) for z in (z0, z1) for x, y in ring]
    faces = [(0, 1, 2, 3), (7, 6, 5, 4),
             (0, 4, 5, 1), (1, 5, 6, 2),
             (2, 6, 7, 3), (3, 7, 4, 0)]
    obj = base.mesh(name, verts, faces, material, .010)
    return base.mirror(obj) if mirrored else obj


def arc_segment(name, cx, cy, radius, start_degrees, sweep_degrees, z,
                material, depth=.018, mirrored=True):
    # A flush annular strip is far cheaper in-game than a beveled Curve while
    # retaining the same luminous segment silhouette in the reference render.
    points = []
    for index in range(7):
        angle = math.radians(start_degrees + sweep_degrees * index / 6)
        points.append((angle, math.cos(angle), math.sin(angle)))
    verts = []
    half_width = depth
    half_height = .009
    for angle, cosine, sine in points:
        for height in (-half_height, half_height):
            for radial in (-half_width, half_width):
                r = radius + radial
                verts.append((cx + r * cosine, cy + r * sine, z + height))
    faces = []
    for index in range(len(points) - 1):
        a, b = index * 4, (index + 1) * 4
        faces.extend([
            (a + 2, b + 2, b + 3, a + 3),
            (a + 1, b + 1, b, a),
            (a, b, b + 2, a + 2),
            (a + 3, b + 3, b + 1, a + 1),
        ])
    faces.extend([(0, 2, 3, 1),
                  (len(verts) - 4, len(verts) - 3, len(verts) - 1, len(verts) - 2)])
    obj = base.mesh(name, verts, faces, material, 0)
    return base.mirror(obj) if mirrored else obj


def add_engineer():
    # A tall dorsal power plant makes the role readable from side and front.
    base.loft("ENG_PowerCowl", [
        (-.72, .45, .70, .94), (-.32, .56, .72, 1.18),
        (.42, .58, .73, 1.30), (1.06, .50, .70, 1.16), (1.50, .35, .66, .91),
    ], base.ARMOR_DARK, .045)
    base.loft("ENG_CowlArmor", [
        (-.48, .34, 1.00, 1.10), (.35, .42, 1.12, 1.34),
        (.94, .34, 1.04, 1.23),
    ], base.ARMOR, .025)
    for i, y in enumerate((.12, .38, .64, .90)):
        base.cube(f"ENG_CoolingLouver_{i}.R", (.39, y, 1.22 - abs(y - .45) * .13),
                  (.18, .055, .035), base.EDGE, .008, mirrored=True)
    base.cube("ENG_PowerCore", (0, -.26, 1.145), (.24, .18, .075), base.CYAN_CORE, .014)

    # Folded multi-joint work boom. Its vertical mass is visible even when stowed.
    base.cylinder("ENG_ShoulderJoint", (0, -.62, -.48), .28, .42, base.ARMOR_DARK, 32)
    base.cylinder("ENG_ShoulderRing", (0, -.62, -.70), .20, .07, base.EDGE, 32)
    beam("ENG_UpperBoom", (0, -.62, -.62), (0, -.20, -1.08), .22, .26, base.MECH, .030)
    base.cylinder("ENG_ElbowJoint", (0, -.20, -1.08), .22, .30, base.ARMOR, 32)
    beam("ENG_LowerBoom", (0, -.20, -1.08), (0, -.78, -1.30), .18, .22, base.MECH, .025)
    base.cylinder("ENG_ToolRotator", (0, -.78, -1.30), .24, .24, base.ARMOR_DARK, 32)
    base.cylinder("ENG_WorkEmitter", (0, -.78, -1.44), .18, .10, base.EDGE, 32)
    base.cylinder("ENG_WorkLens", (0, -.78, -1.505), .115, .035, base.CYAN_CORE, 32)

    # Paired tool guards underline the engineering silhouette without blocking the laser.
    wedge("ENG_ToolGuard.R", .46, .82, [
        (-1.18, -.82, -.44), (-.62, -1.02, -.36), (.08, -.88, -.30),
    ], base.ARMOR_DARK, .035, True)
    base.cube("ENG_ToolCartridge.R", (.66, -.58, -.70), (.20, .64, .22), base.ARMOR, .025, mirrored=True)
    base.cube("ENG_ToolIndicator.R", (.77, -.67, -.70), (.025, .18, .07), base.CYAN, .007, mirrored=True)


def add_field():
    # Deep, long side pods shift the visual center outward and rearward.
    wedge("FLD_CarrierPod.R", .86, 1.52, [
        (-1.55, -.34, .30), (-1.28, -.48, .48),
        (.95, -.50, .51), (1.65, -.31, .38), (1.92, -.20, .24),
    ], base.ARMOR_DARK, .055, True)
    wedge("FLD_PodArmor.R", 1.32, 1.58, [
        (-1.30, -.20, .25), (-1.02, -.30, .36),
        (.86, -.31, .39), (1.56, -.18, .30),
    ], base.ARMOR, .035, True)
    base.cube("FLD_PodRail.R", (1.47, .12, .45), (.12, 2.55, .09), base.EDGE, .018, mirrored=True)
    for y in (-.88, .10, 1.08):
        base.cube(f"FLD_PodLatch_{y}.R", (1.55, y, .28), (.055, .18, .15), base.ORANGE, .010, mirrored=True)
    base.cube("FLD_PodStatus.R", (1.582, -1.06, .07), (.022, .30, .10), base.CYAN, .008, mirrored=True)

    # A visibly deep center bay creates the carrier belly silhouette.
    base.loft("FLD_MissionBay", [
        (-1.08, .70, -.84, -.35), (-.62, .76, -.97, -.34),
        (.62, .76, -.98, -.34), (1.32, .65, -.82, -.31),
    ], base.ARMOR_DARK, .055)
    base.cube("FLD_BayDoor", (0, .08, -1.005), (1.10, 1.52, .065), base.ARMOR, .018)
    base.cube("FLD_BaySpine", (0, .08, -1.045), (.075, 1.28, .035), base.MECH, .008)
    base.cube("FLD_BayLock", (0, -.55, -1.052), (.24, .18, .035), base.ORANGE, .008)

    # Rear cargo stabilizer visually lengthens the aircraft without widening the Dock footprint.
    wedge("FLD_RearDeck", -.62, .62, [
        (1.48, .56, .77), (2.12, .47, .70), (2.72, .22, .48),
    ], base.ARMOR, .040)
    base.cube("FLD_RearDeckSpine", (0, 2.02, .77), (.09, 1.00, .08), base.MECH, .012)
    base.cube("FLD_RearMarker.R", (.40, 2.32, .66), (.16, .25, .04), base.ORANGE, .008, mirrored=True)


def add_guard():
    # Forward-biased wedge armor dominates the silhouette from front, top, and side.
    wedge("GRD_AegisShoulder.R", .62, 1.46, [
        (-2.72, -.32, .28), (-2.44, -.50, .56),
        (-1.36, -.48, .63), (-.82, -.30, .43),
    ], base.ARMOR, .060, True)
    wedge("GRD_ShoulderInset.R", 1.32, 1.49, [
        (-2.45, -.20, .29), (-2.18, -.31, .43), (-1.42, -.30, .47),
    ], base.MECH, .025, True)
    base.cube("GRD_ShoulderSensorBezel.R", (1.505, -2.12, .12), (.035, .36, .22), base.MECH, .012, mirrored=True)
    base.cube("GRD_ShoulderSensor.R", (1.528, -2.12, .12), (.018, .20, .10), base.CYAN_CORE, .006, mirrored=True)

    # Continuous chin armor replaces the small Scout chin with a strong frontal plane.
    base.loft("GRD_ChinArmor", [
        (-2.77, .52, -.57, -.12), (-2.48, .78, -.65, -.18),
        (-1.82, .83, -.61, -.22), (-1.23, .72, -.48, -.18),
    ], base.ARMOR_DARK, .055)
    base.cube("GRD_ChinPlate", (0, -2.53, -.53), (.72, .28, .10), base.ARMOR, .022)
    base.cube("GRD_VentralThreatBezel", (0, -2.69, -.43), (.28, .04, .18), base.MECH, .012)
    base.cube("GRD_VentralThreatSensor", (0, -2.716, -.43), (.16, .018, .08), base.CYAN_CORE, .006)

    # Armored duct brows make Guard readable in a head-on view.
    wedge("GRD_DuctBrow.R", 1.18, 3.42, [
        (-1.58, .26, .52), (-1.38, .27, .62), (-.92, .24, .56),
    ], base.ARMOR_DARK, .035, True)
    base.curve("GRD_DuctBrowEdge.R", [(1.30, -1.53, .54), (2.28, -1.51, .65), (3.34, -1.28, .57)],
               base.EDGE, .013, True)

    # Low deployable sensor spine; no exposed weapon until combat design is approved.
    base.loft("GRD_SensorSpine", [
        (-.10, .20, .83, .92), (.42, .27, .83, 1.14),
        (1.04, .23, .80, 1.10), (1.40, .16, .76, .90),
    ], base.ARMOR_DARK, .030)
    base.cube("GRD_SpineArray.R", (.16, .70, 1.10), (.12, .34, .10), base.CYAN, .010, mirrored=True)
    base.cube("GRD_ClosedHardpoint.R", (1.05, -.18, -.34), (.42, 1.22, .30), base.ARMOR_DARK, .045, mirrored=True)
    base.cube("GRD_HardpointCover.R", (1.28, -.18, -.34), (.055, .82, .18), base.ARMOR, .014, mirrored=True)
    base.cube("GRD_ThreatMarker.R", (1.312, -.48, -.34), (.018, .18, .07), base.ORANGE, .006, mirrored=True)


def add_salvage():
    # Replace the standard twin duct wing with the approved broad four-turbine rescue frame.
    for obj in list(bpy.context.scene.objects):
        if obj.name.startswith(("W02_", "R02_", "L02_", "W04_", "T02_")):
            bpy.data.objects.remove(obj, do_unlink=True)
    rotor_x = 2.32
    for front, y in ((True, -1.16), (False, 1.16)):
        prefix = "SAL_F" if front else "SAL_R"
        arm_angle = math.radians(-6 if front else 6)
        base.cube(f"{prefix}_ArmLower.R", (1.39, y, .02), (1.86, .39, .25),
                  base.ARMOR_DARK, .05, rot=(0, 0, arm_angle), mirrored=True)
        base.cube(f"{prefix}_ArmUpper.R", (1.48, y, .24), (1.70, .23, .20),
                  base.ARMOR, .035, rot=(0, 0, arm_angle), mirrored=True)
        base.cube(f"{prefix}_ArmInset.R", (1.46, y, .35), (1.24, .07, .055),
                  base.CYAN, .009, rot=(0, 0, arm_angle), mirrored=True)
        base.cube(f"{prefix}_RootLatch.R", (.78, y, .28), (.18, .46, .19),
                  base.ORANGE, .022, mirrored=True)

        annular_ring(f"{prefix}_Duct.R", rotor_x, y, .94, .715, .02, .39,
                     base.ARMOR, 64, True, .025)
        annular_ring(f"{prefix}_InnerStator.R", rotor_x, y, .708, .625, .105, .315,
                     base.MECH, 64, True, .012)
        annular_ring(f"{prefix}_UpperLip.R", rotor_x, y, .925, .885, .392, .435,
                     base.EDGE, 64, True, .008)
        annular_ring(f"{prefix}_LowerLip.R", rotor_x, y, .925, .875, -.025, .018,
                     base.EDGE, 64, True, .008)

        for support in range(4):
            angle = math.radians(45 + support * 90)
            center = (rotor_x + .39 * math.cos(angle), y + .39 * math.sin(angle), .085)
            base.cube(f"{prefix}_Stator_{support}.R", center, (.64, .075, .050),
                      base.EDGE, .010, rot=(0, 0, angle), mirrored=True)
        base.cylinder(f"{prefix}_HubLower.R", (rotor_x, y, .09), .215, .14,
                      base.MECH, 40, True)
        base.cylinder(f"{prefix}_Hub.R", (rotor_x, y, .245), .185, .285,
                      base.EDGE, 40, True)
        base.cylinder(f"{prefix}_HubCore.R", (rotor_x, y, .410), .105, .060,
                      base.CYAN_CORE, 40, True)

        for index in range(8):
            angle = math.radians(index * 45 + (9 if front else -9))
            rotor_root_mount(f"SAL_Blade_{'F' if front else 'R'}_Mount_{index}.R",
                             rotor_x, y, angle, .165, .305, base.MECH, True)
            rotor_blade(f"SAL_Blade_{'F' if front else 'R'}_{index}.R",
                        rotor_x, y, angle, .175, .305, base.ROTOR, True)
            rotor_blade_inset(f"SAL_Blade_{'F' if front else 'R'}_Glow_{index}.R",
                              rotor_x, y, angle, .294, .320, base.CYAN_CORE, True)
            rotor_blade_inset(f"SAL_Blade_{'F' if front else 'R'}_GlowLower_{index}.R",
                              rotor_x, y, angle, .160, .186, base.CYAN, True)

        for segment in range(8):
            start = segment * 45 + 8
            arc_segment(f"{prefix}_GlowTop_{segment}.R", rotor_x, y, .665,
                        start, 24, .345, base.CYAN_CORE, .026, True)
            arc_segment(f"{prefix}_GlowBottom_{segment}.R", rotor_x, y, .665,
                        start, 24, .072, base.CYAN, .019, True)
        for segment in (1, 5):
            arc_segment(f"{prefix}_Hazard_{segment}.R", rotor_x, y, .952,
                        segment * 180 + 70, 24, .28, base.ORANGE, .018, True)

    # Armored service spine, reinforced skids, and a mechanically readable rescue winch.
    base.loft("SAL_ServiceSpine", [(-.88, .46, .68, .88), (-.32, .56, .72, 1.01),
                                    (.56, .53, .70, .96), (1.58, .36, .63, .79)],
              base.ARMOR_DARK, .04)
    base.cube("SAL_SpineCap", (0, .18, 1.005), (.54, 1.05, .075), base.ARMOR, .018)
    for index, y in enumerate((-.60, -.28, .04, .36, .68)):
        base.cube(f"SAL_DorsalVent_{index}", (0, y, 1.05), (.34, .15, .035), base.MECH, .007)
    base.cube("SAL_RescueBeacon.R", (.39, -.38, 1.04), (.21, .30, .13),
              base.ORANGE, .025, mirrored=True)
    base.cube("SAL_BeaconLens.R", (.39, -.38, 1.115), (.12, .18, .025),
              base.CYAN_CORE, .007, mirrored=True)
    base.cube("SAL_SideArmor.R", (.77, .12, .45), (.17, 2.44, .28),
              base.ARMOR, .035, mirrored=True)
    for index, y in enumerate((-.78, -.18, .42, 1.02)):
        base.cube(f"SAL_SideFastener_{index}.R", (.865, y, .45), (.025, .16, .075),
                  base.EDGE if index % 2 == 0 else base.ORANGE, .006, mirrored=True)
    base.curve("SAL_DorsalSeam.R", [(.18, -1.48, .75), (.33, -.72, .94),
                                     (.36, .42, .98), (.30, 1.38, .77)],
               base.EDGE, .010, True)
    for index, y in enumerate((-1.36, 1.30)):
        base.cube(f"SAL_HazardPanel_{index}.R", (.68, y, .64), (.14, .34, .055),
                  base.ORANGE, .012, rot=(0, 0, math.radians(-18 if index == 0 else 18)),
                  mirrored=True)
    # Connected low-profile stabilizers replace the Scout's wide detached duct fairings.
    wedge("SAL_RearStabilizer.R", .54, 1.08, [
        (1.12, .50, .72), (1.72, .48, .85), (2.16, .38, 1.00), (2.34, .28, .79),
    ], base.ARMOR_DARK, .035, True)
    base.curve("SAL_RearStabilizerEdge.R", [
        (.55, 1.12, .73), (.72, 1.73, .86), (.85, 2.15, .98)
    ], base.ORANGE, .013, True)
    base.cube("SAL_VentralBay", (0, -.02, -.55), (1.02, 1.28, .28), base.ARMOR_DARK, .045)
    base.cube("SAL_VentralDoor", (0, -.02, -.705), (.72, .98, .055), base.ARMOR, .014)
    base.cylinder("SAL_WinchDrum", (0, -.05, -.90), .36, .76, base.MECH, 40)
    bpy.context.object.rotation_euler[1] = math.radians(90)
    base.cylinder("SAL_WinchAxle", (0, -.05, -.90), .15, .86, base.EDGE, 32)
    bpy.context.object.rotation_euler[1] = math.radians(90)
    base.cube("SAL_WinchHousing.R", (.45, -.05, -.84), (.20, .88, .66), base.ARMOR_DARK, .05, mirrored=True)
    base.cylinder("SAL_CableGuide", (0, -.20, -1.20), .13, .18, base.EDGE, 28)
    beam("SAL_HoistArm.R", (.30, -.20, -1.02), (.58, -.51, -1.34), .16, .18, base.MECH, .022, True)
    base.cylinder("SAL_JawPivot.R", (.59, -.52, -1.34), .12, .20, base.EDGE, 24, True)
    base.cube("SAL_FoldedJaw.R", (.68, -.61, -1.43), (.22, .62, .15), base.ORANGE, .025,
              rot=(0, math.radians(10), math.radians(-10)), mirrored=True)
    base.cube("SAL_JawTooth.R", (.73, -.91, -1.48), (.14, .20, .22), base.EDGE, .018,
              rot=(0, math.radians(-18), 0), mirrored=True)
    base.cube("SAL_Skid.R", (1.30, .06, -1.24), (.23, 3.78, .20), base.EDGE, .05, mirrored=True)
    base.cube("SAL_SkidInnerRail.R", (1.03, .06, -1.16), (.12, 3.20, .12), base.ARMOR_DARK, .025, mirrored=True)
    base.cube("SAL_SkidNose.R", (1.30, -1.82, -1.16), (.23, .48, .20), base.EDGE, .04,
              rot=(math.radians(-18), 0, 0), mirrored=True)
    base.cube("SAL_SkidTail.R", (1.30, 1.93, -1.16), (.23, .42, .20), base.EDGE, .04,
              rot=(math.radians(18), 0, 0), mirrored=True)
    for index, y in enumerate((-1.12, .02, 1.16)):
        beam(f"SAL_SkidBrace_{index}.R", (.62, y, -.66), (1.24, y, -1.17),
             .13, .15, base.MECH, .018, True)


ROLE_BUILDERS = {
    "scout": lambda: None,
    "engineer": add_engineer,
    "field": add_field,
    "guard": add_guard,
	"salvage": add_salvage,
}

ROLE_REQUIRED = {
    "scout": ["F01_Fuselage", "S01_EmbeddedVisor"],
    "engineer": ["ENG_PowerCowl", "ENG_UpperBoom", "ENG_WorkLens"],
    "field": ["FLD_CarrierPod.R", "FLD_MissionBay", "FLD_RearDeck"],
    "guard": ["GRD_AegisShoulder.R", "GRD_ChinArmor", "GRD_SensorSpine"],
	"salvage": ["SAL_ServiceSpine", "SAL_WinchDrum", "SAL_Skid.R", "SAL_F_Duct.R",
	            "SAL_R_Duct.R", "SAL_F_InnerStator.R", "SAL_R_InnerStator.R"],
}


def render_views(camera):
    base.render(camera, "hero", (7.6, -10.0, 5.0), False)
    base.render(camera, "front", (0, -13, .05), True, 9.0)
    base.render(camera, "rear", (0, 13, .05), True, 9.0)
    base.render(camera, "top", (0, 0, 14), True, 10.0)
    base.render(camera, "bottom", (0, 0, -14), True, 10.0)
    base.render(camera, "left", (-14, 0, .15), True, 7.2)
    base.render(camera, "right", (14, 0, .15), True, 7.2)


def validate_and_export(role):
    objects = [o for o in bpy.context.scene.objects if o.type == "MESH" and o.name != "StudioFloor"]
    mirrored = [o for o in objects if any(m.type == "MIRROR" for m in o.modifiers)]
    missing = [name for name in ROLE_REQUIRED[role] if name not in bpy.data.objects]
    common = ["F01_Fuselage", "S01_EmbeddedVisor", "V01_RearHousing"]
    if role != "salvage":
        common.append("W02_DuctWing.R")
    missing += [name for name in common if name not in bpy.data.objects]
    role_mesh_minimum = 45 if role == "scout" else 70
    report = {
        "pass": not missing and len(objects) >= role_mesh_minimum and len(mirrored) >= 18,
        "role": role,
        "meshObjects": len(objects),
        "mirroredObjects": len(mirrored),
        "missingRequired": missing,
        "sharedScoutV2Base": True,
        "roleSilhouetteV3": True,
        "frontAxis": "-Y",
        "upAxis": "+Z",
        "gameImplementationApproved": False,
    }
    if role == "salvage":
        overall_width = 2 * (2.32 + .94)
        overall_length = 2.70 + 2.58
        report["salvageProportions"] = {
            "overallWidth": round(overall_width, 3),
            "overallLength": round(overall_length, 3),
            "widthToLength": round(overall_width / overall_length, 3),
            "targetWidthToLength": [1.20, 1.27],
            "rotorOuterDiameter": 1.88,
            "rotorOpenDiameter": 1.43,
            "rotorOpenRatio": round(.715 / .94, 3),
            "openDuct": True,
            "bladeCountPerRotor": 8,
            "rotorCount": 4,
        }
        report["salvageRotorAssembly"] = {
            "bladeBodyZ": [.175, .305],
            "upperInsetZ": [.294, .320],
            "lowerInsetZ": [.160, .186],
            "statorZ": [.060, .110],
            "statorToBladeClearance": .065,
            "insetEmbeddedDepth": .011,
            "separateRotorAndStatorPlanes": True,
            "mirroredFromSharedCoordinates": True,
        }
    output = base.OUT
    (output / f"{role}-validation.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    if not report["pass"]:
        raise RuntimeError(f"{role} validation failed: {report}")
    bpy.ops.wm.save_as_mainfile(filepath=str(output / f"morrowgear-family-a-{role}-v3.blend"))
    bpy.ops.export_scene.gltf(filepath=str(output / f"morrowgear-family-a-{role}-v3.glb"), export_format="GLB")
    return report


def build_role(role):
    initialize_base()
    ROLE_BUILDERS[role]()
    base.OUT = BASE_OUTPUT / role
    base.OUTPUT_PREFIX = role
    base.OUT.mkdir(parents=True, exist_ok=True)
    camera = base.setup()
    render_views(camera)
    report = validate_and_export(role)
    print(json.dumps(report))


def main():
    requested = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    for role_name in (requested or ("scout", "engineer", "field", "guard", "salvage")):
        build_role(role_name)


if __name__ == "__main__":
    main()
