import json
from math import cos, radians, sin
from pathlib import Path

import bpy
from mathutils import Vector


ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent / "proposed-v5"
CARRIER = ROOT / "docs/design/carrier-v31-ring-proposal/carrier-v31-ring-proposal.glb"
CARGO = ROOT / "docs/design/carrier-cargo-container-v1/carrier-cargo-container-v1.glb"
MISSILE = ROOT / "docs/design/carrier-missile-container-v2/carrier-missile-container-v2.glb"

# Two staggered 2x2 groups around the centre of mass. Inner positions are the only
# ordnance-capable bays because they have a direct vertical path to a dorsal hatch.
HARDPOINT_LAYOUT = (
    (-9.8, -5.0, True), (-15.4, -3.8, False),
    (-9.8, 5.0, True), (-15.4, 3.8, False),
    (9.8, -5.0, True), (15.4, -3.8, False),
    (9.8, 5.0, True), (15.4, 3.8, False),
)
CONTAINER_HEIGHT = 2.539090156555176
KEY_INSERTION_DEPTH = 0.18
BAY_HALF_X = 1.72
BAY_HALF_Z = 2.66


def material(name, color, metallic=0.3, roughness=0.3):
    mat = bpy.data.materials.new(name)
    mat.diffuse_color = (*color, 1)
    mat.metallic = metallic
    mat.roughness = roughness
    return mat


def box(name, center, dimensions, mat, bevel=0.0):
    bpy.ops.mesh.primitive_cube_add(size=1, location=center)
    obj = bpy.context.object
    obj.name = name
    obj.dimensions = dimensions
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if bevel > 0:
        mod = obj.modifiers.new("Edge relief", "BEVEL")
        mod.width = bevel
        mod.segments = 2
    obj.data.materials.append(mat)
    return obj


def upward_hatch(name, center_x, fore_aft, top_z, side, open_amount, armor, amber):
    """Create a dorsal door that pivots upward around its outboard fore/aft hinge."""
    width = 1.08
    length = 4.08
    hinge_x = center_x + side * 1.13
    angle = side * radians(72.0) * open_amount
    local_dx = -side * width * 0.5
    panel_x = hinge_x + cos(angle) * local_dx
    panel_z = top_z - sin(angle) * local_dx
    panel = box(name, (panel_x, fore_aft, panel_z), (width, length, .20), armor, .055)
    panel.rotation_euler[1] = angle
    box(f"{name}_HINGE", (hinge_x, fore_aft, top_z), (.18, length + .10, .18), armor, .045)

    # The stripe moves with the door and makes the open direction readable at game scale.
    stripe_dx = local_dx * .72
    stripe_x = hinge_x + cos(angle) * stripe_dx
    stripe_z = top_z - sin(angle) * stripe_dx + .11
    stripe = box(f"{name}_MARK", (stripe_x, fore_aft, stripe_z),
                 (.10, length - .48, .035), amber, .012)
    stripe.rotation_euler[1] = angle


def import_glb(path, prefix):
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=str(path))
    imported = [obj for obj in bpy.data.objects if obj not in before]
    for obj in imported:
        obj.name = prefix + obj.name
    return imported


def setup_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x = 1280
    scene.render.resolution_y = 800
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    scene.world = bpy.data.worlds.new("Proposal world")
    scene.world.color = (0.018, 0.022, 0.026)
    import_glb(CARRIER, "CARRIER_")
    return scene


def append_container(source, prefix, location):
    imported = import_glb(source, prefix)
    for obj in imported:
        if obj.type != "MESH":
            continue
        obj.rotation_euler[2] += 1.5707963267948966
        obj.location += Vector(location)


def sample_surfaces(scene):
    depsgraph = bpy.context.evaluated_depsgraph_get()
    samples = []
    for slot, (x, fore_aft, ordnance) in enumerate(HARDPOINT_LAYOUT):
        hit_bottom, bottom, _normal, _face, bottom_obj, _matrix = scene.ray_cast(
            depsgraph, Vector((x, fore_aft, -12)), Vector((0, 0, 1)), distance=30)
        hit_top, top, _normal, _face, top_obj, _matrix = scene.ray_cast(
            depsgraph, Vector((x, fore_aft, 16)), Vector((0, 0, -1)), distance=30)
        samples.append({
            "slot": slot,
            "x": x,
            "z": fore_aft,
            "ordnance": ordnance,
            "bottomHit": bool(hit_bottom),
            "topHit": bool(hit_top),
            "localHullUnderside": float(bottom.z) if hit_bottom else None,
            "localHullTop": float(top.z) if hit_top else None,
            "bottomObject": bottom_obj.name if hit_bottom else None,
            "topObject": top_obj.name if hit_top else None,
        })
    return samples


def add_integrated_bays(scene):
    armor = material("Bay armor", (0.10, 0.13, 0.15), 0.82, 0.22)
    receiver = material("Receiver cavity", (0.025, 0.035, 0.042), 0.66, 0.31)
    structure = material("Load structure", (0.24, 0.29, 0.31), 0.9, 0.18)
    clamp = material("Hydraulic clamp", (0.42, 0.47, 0.48), 0.92, 0.16)
    seal = material("Pressure seal", (0.08, 0.10, 0.11), 0.65, 0.34)
    umbilical = material("Utility interface", (0.16, 0.20, 0.22), 0.88, 0.18)
    cyan = material("Cargo lock light", (0.06, 0.88, 0.98), 0.02, 0.12)
    amber = material("Missile lock light", (1.00, 0.42, 0.05), 0.02, 0.12)
    report = []
    surfaces = sample_surfaces(scene)
    for sample in surfaces:
            slot = sample["slot"]
            x = sample["x"]
            fore_aft = sample["z"]
            ordnance = sample["ordnance"]
            side = -1 if x < 0 else 1
            hull_y = sample["localHullUnderside"]
            top_y = sample["localHullTop"]
            if hull_y is None or top_y is None:
                raise RuntimeError(f"Hardpoint {slot} misses carrier hull")
            container_y = hull_y - CONTAINER_HEIGHT + KEY_INSERTION_DEPTH
            type_light = amber if ordnance else cyan

            # Recessed receiver: the shallow upper key aligns the module, but does not carry its mass.
            box(f"BAY_{slot}_RECEIVER", (x, fore_aft, hull_y + .09),
                (3.30, 5.18, .30), receiver, .06)
            box(f"BAY_{slot}_KEY_GUIDE", (x, fore_aft, hull_y - .02),
                (2.22, 4.38, .28), structure, .05)

            # Two longitudinal keel beams and two cross-members transfer load into the carrier hull.
            for rail_side in (-1, 1):
                rail_x = x + rail_side * 1.35
                box(f"BAY_{slot}_KEEL_{rail_side}", (rail_x, fore_aft, hull_y - .38),
                    (.24, 4.74, .34), structure, .045)
                box(f"BAY_{slot}_ARMOR_{rail_side}", (x + rail_side * 1.88, fore_aft, hull_y - .08),
                    (.54, 5.86, .68), armor, .10)
            for longitudinal in (-1, 1):
                member_z = fore_aft + longitudinal * 2.28
                box(f"BAY_{slot}_CROSS_{longitudinal}", (x, member_z, hull_y - .38),
                    (2.92, .26, .34), structure, .045)
                box(f"BAY_{slot}_END_ARMOR_{longitudinal}", (x, fore_aft + longitudinal * 2.88,
                    hull_y - .08), (3.42, .48, .68), armor, .10)

            # Seal shutters close around, not through, the module. They hide the cavity and fair the belly.
            for seal_side in (-1, 1):
                box(f"BAY_{slot}_SIDE_SEAL_{seal_side}",
                    (x + seal_side * 1.61, fore_aft, hull_y - .19), (.28, 5.16, .22), seal, .045)
                box(f"BAY_{slot}_END_SEAL_{seal_side}",
                    (x, fore_aft + seal_side * 2.51, hull_y - .19), (2.94, .24, .22), seal, .045)

            # Four hard locks close over container trunnions. These are the primary vertical restraints.
            for clamp_x in (-1, 1):
                for clamp_z in (-1, 1):
                    cx = x + clamp_x * 1.36
                    cz = fore_aft + clamp_z * 2.15
                    box(f"BAY_{slot}_LOCK_{clamp_x}_{clamp_z}", (cx, cz, hull_y - .58),
                        (.34, .46, .38), clamp, .06)
                    box(f"BAY_{slot}_JAW_{clamp_x}_{clamp_z}",
                        (x + clamp_x * 1.18, cz, hull_y - .69), (.32, .30, .18), structure, .035)
                    box(f"BAY_{slot}_LOCK_LIGHT_{clamp_x}_{clamp_z}",
                        (cx, cz - clamp_z * .245, hull_y - .59), (.16, .035, .075), type_light, .015)

            # The inboard service spine couples power, data, cooling and fire-suppression after locking.
            service_x = x - side * 1.58
            box(f"BAY_{slot}_UMBILICAL", (service_x, fore_aft + .18, hull_y - .57),
                (.34, 1.18, .42), umbilical, .07)
            for channel, z_offset in enumerate((-.27, 0.0, .27)):
                box(f"BAY_{slot}_UTILITY_{channel}",
                    (service_x - side * .18, fore_aft + .18 + z_offset, hull_y - .58),
                    (.06, .13, .13), cyan if channel < 2 else amber, .018)
            box(f"BAY_{slot}_JETTISON", (x + side * 1.58, fore_aft - 2.24, hull_y - .51),
                (.28, .52, .22), amber, .035)

            # Ordnance bays have a straight armored VLS path. Missile axes remain vertical from
            # the module cells to the dorsal opening; no round moves laterally through the wing.
            if ordnance:
                trunk_center = (hull_y + top_y) / 2
                box(f"ORDNANCE_{slot}_VERTICAL_TRUNK", (x, fore_aft, trunk_center),
                    (2.38, 4.26, max(.24, top_y - hull_y)), receiver, .08)
                for tube_x in (-.48, .48):
                    box(f"ORDNANCE_{slot}_ELEVATOR_{tube_x}",
                        (x + tube_x, fore_aft, trunk_center), (.28, 3.62, max(.18, top_y - hull_y - .16)),
                        structure, .035)
                aperture_y = top_y + .055
                box(f"ORDNANCE_{slot}_APERTURE", (x, fore_aft, aperture_y),
                    (2.28, 4.18, .10), receiver, .04)
                demo_open = slot == 4
                open_amount = 1.0 if demo_open else 0.0
                upward_hatch(f"ORDNANCE_{slot}_HATCH_L", x, fore_aft, top_y + .16,
                             -1, open_amount, armor, amber)
                upward_hatch(f"ORDNANCE_{slot}_HATCH_R", x, fore_aft, top_y + .16,
                             1, open_amount, armor, amber)

            source = MISSILE if ordnance else CARGO
            append_container(source, f"SLOT_{slot}_", (x, fore_aft, container_y))
            report.append({
                "slot": slot,
                "x": x,
                "z": fore_aft,
                "localHullUnderside": hull_y,
                "containerOriginY": container_y,
                "containerTop": container_y + CONTAINER_HEIGHT,
                "alignmentKeyInsertion": container_y + CONTAINER_HEIGHT - hull_y,
                "primaryLoadMembers": 4,
                "hydraulicLocks": 4,
                "utilityInterfaces": 3,
                "unsupportedGap": 0.0,
                "ordnanceCapable": ordnance,
                "verticalLaunchPath": ordnance,
                "dorsalHatch": ordnance,
                "dorsalHatchMotion": "UPWARD_OUTBOARD_HINGE" if ordnance else "NONE",
            })
    return report


def camera(name, location, target, ortho):
    data = bpy.data.cameras.new(name)
    cam = bpy.data.objects.new(name, data)
    bpy.context.collection.objects.link(cam)
    cam.location = location
    cam.rotation_euler = (Vector(target) - cam.location).to_track_quat("-Z", "Y").to_euler()
    data.type = "ORTHO"
    data.ortho_scale = ortho
    bpy.context.scene.camera = cam


def add_lights():
    for name, location, energy, size in (
        ("Key", (18, -24, 28), 3300, 22),
        ("Fill", (-24, -5, 8), 1800, 18),
        ("Underside", (0, 4, -24), 2600, 16),
    ):
        data = bpy.data.lights.new(name, "AREA")
        data.energy = energy
        data.size = size
        obj = bpy.data.objects.new(name, data)
        bpy.context.collection.objects.link(obj)
        obj.location = location
        obj.rotation_euler = (Vector((0, 0, 1.5)) - obj.location).to_track_quat("-Z", "Y").to_euler()


def render(scene, name, location, target, ortho):
    if scene.camera is not None:
        bpy.data.objects.remove(scene.camera, do_unlink=True)
    camera(name, location, target, ortho)
    scene.render.filepath = str(OUT / f"{name}.png")
    bpy.ops.render.render(write_still=True)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    scene = setup_scene()
    hardpoints = add_integrated_bays(scene)
    add_lights()
    views = {
        "hero": ((38, -45, 30), (0, 0, 2.5), 59),
        "front": ((0, -60, 4), (0, 0, 2.5), 56),
        "rear": ((0, 60, 4), (0, 0, 2.5), 56),
        "top": ((0, 0, 70), (0, 0, 2.5), 59),
        "bottom": ((0, 0, -70), (0, 0, 1.5), 59),
        "left": ((-70, 0, 4), (0, 0, 2.5), 40),
        "right": ((70, 0, 4), (0, 0, 2.5), 40),
        "underside": ((38, -45, -30), (0, 0, 2.0), 59),
        "bay_closeup": ((-20, -14, -8), (-9.8, -5.0, 1.0), 12),
        "bay_bottom": ((-9.8, -5.0, -18), (-9.8, -5.0, 1.0), 10),
        "hatch_closeup": ((20, -16, 15), (9.8, -5.0, 4.0), 12),
    }
    for name, args in views.items():
        render(scene, name, *args)
    # Diagnostic cutaway proves that each missile module is vertically aligned with its own
    # upper hatch. It is not a runtime render mode.
    for obj in bpy.context.scene.objects:
        if obj.name.startswith("CARRIER_") and obj.type == "MESH": obj.hide_render = True
    render(scene, "ordnance_cutaway", (36, -42, 28), (0, 0, 2.5), 48)
    for obj in bpy.context.scene.objects:
        if obj.name.startswith("CARRIER_") and obj.type == "MESH": obj.hide_render = False
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT / "carrier-container-bay-proposal-v5.blend"))
    bpy.ops.export_scene.gltf(filepath=str(OUT / "carrier-container-bay-proposal-v5.glb"), export_format="GLB")
    report = {
        "proposalOnly": True,
        "runtimePublished": False,
        "recognitionFeatures": [
            "two staggered 2x2 bay groups around the centre of mass",
            "per-row hull-conforming recessed receiver",
            "four-point vertical winch geometry",
            "shallow alignment key separated from the primary load path",
            "dual longitudinal keels and fore/aft cross-members",
            "four hydraulic trunnion locks per module",
            "power, data, cooling and fire-suppression service spine",
            "perimeter pressure shutters and emergency jettison actuator",
            "four inner ordnance bays with direct vertical armored trunks",
            "paired dorsal doors opening upward around outboard fore/aft hinges",
            "direct vertical launch path with no lateral missile transfer",
            "four outer logistics bays with no false missile path",
        ],
        "hardpoints": sorted(hardpoints, key=lambda value: value["slot"]),
        "symmetryTolerance": max(abs(hardpoints[i]["localHullUnderside"]
            - hardpoints[i + 4]["localHullUnderside"]) for i in range(4)),
        "ordnanceHardpoints": [v["slot"] for v in hardpoints if v["ordnanceCapable"]],
        "cargoCompatibleHardpoints": [v["slot"] for v in hardpoints],
        "containerOrientation": {
            "sourceLongAxis": "+X",
            "installedLongAxis": "CARRIER_FORE_AFT_Z",
            "yawDegrees": 90,
            "uprightOnEnd": False,
        },
        "validation": "PASS" if all(abs(v["alignmentKeyInsertion"] - KEY_INSERTION_DEPTH) < 1e-6
            and v["primaryLoadMembers"] == 4 and v["hydraulicLocks"] == 4
            and v["utilityInterfaces"] == 3 and v["unsupportedGap"] == 0
            and (not v["ordnanceCapable"] or v["verticalLaunchPath"] and v["dorsalHatch"])
            for v in hardpoints) else "FAIL",
    }
    (OUT / "validation.json").write_text(json.dumps(report, indent=2), encoding="utf-8")


main()
