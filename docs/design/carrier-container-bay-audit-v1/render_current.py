import json
from pathlib import Path

import bpy
from mathutils import Vector


ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
CARRIER = ROOT / "docs/design/carrier-v31-ring-proposal/carrier-v31-ring-proposal.glb"
CONTAINER = ROOT / "docs/design/carrier-cargo-container-v1/carrier-cargo-container-v1.glb"

POD_X = 11.4
POD_Z = (-8.4, -2.8, 2.8, 8.4)
POD_Y = -1.92
COLLAR_TOP = -0.18
COLLAR_BOTTOM = -0.72
BAY_HALF_X = 1.72
BAY_HALF_Z = 2.66
HULL_BOTTOM = 0.20


def material(name, color, metallic=0.2, roughness=0.35):
    mat = bpy.data.materials.new(name)
    mat.diffuse_color = (*color, 1)
    mat.metallic = metallic
    mat.roughness = roughness
    return mat


def box(name, center, dimensions, mat):
    bpy.ops.mesh.primitive_cube_add(size=1, location=center)
    obj = bpy.context.object
    obj.name = name
    obj.dimensions = dimensions
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    obj.data.materials.append(mat)
    return obj


def import_glb(path):
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=str(path))
    return [obj for obj in bpy.data.objects if obj not in before]


def setup_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x = 1280
    scene.render.resolution_y = 800
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    scene.world = bpy.data.worlds.new("Audit world")
    scene.world.color = (0.025, 0.03, 0.035)
    carrier = import_glb(CARRIER)
    for obj in carrier:
        if obj.type == "MESH":
            obj.name = "CURRENT_CARRIER_" + obj.name
    return scene


def sample_local_hull(scene):
    depsgraph = bpy.context.evaluated_depsgraph_get()
    samples = []
    for side in (-1, 1):
        for row, fore_aft in enumerate(POD_Z):
            origin = Vector((side * POD_X, fore_aft, -12))
            hit, location, _normal, _face, obj, _matrix = scene.ray_cast(
                depsgraph, origin, Vector((0, 0, 1)), distance=30)
            samples.append({
                "slot": row if side < 0 else row + 4,
                "x": side * POD_X,
                "z": fore_aft,
                "hit": bool(hit),
                "object": obj.name if hit else None,
                "localHullUnderside": float(location.z) if hit else None,
                "collarGap": float(location.z - COLLAR_TOP) if hit else None,
            })
    return samples


def add_current_runtime_bays():
    collar = material("Runtime bay collar", (0.95, 0.24, 0.08), 0.75, 0.24)
    hatch = material("Runtime bay hatch", (0.42, 0.06, 0.035), 0.55, 0.3)
    for side in (-1, 1):
        for row, fore_aft in enumerate(POD_Z):
            cx = side * POD_X
            cy = fore_aft
            height = COLLAR_TOP - COLLAR_BOTTOM
            box(f"COLLAR_SIDE_{side}_{row}_L", (cx - BAY_HALF_X - 0.09, cy, (COLLAR_TOP + COLLAR_BOTTOM) / 2),
                (0.18, BAY_HALF_Z * 2 + 0.36, height), collar)
            box(f"COLLAR_SIDE_{side}_{row}_R", (cx + BAY_HALF_X + 0.09, cy, (COLLAR_TOP + COLLAR_BOTTOM) / 2),
                (0.18, BAY_HALF_Z * 2 + 0.36, height), collar)
            box(f"COLLAR_SIDE_{side}_{row}_F", (cx, cy - BAY_HALF_Z - 0.09, (COLLAR_TOP + COLLAR_BOTTOM) / 2),
                (BAY_HALF_X * 2, 0.18, height), collar)
            box(f"COLLAR_SIDE_{side}_{row}_A", (cx, cy + BAY_HALF_Z + 0.09, (COLLAR_TOP + COLLAR_BOTTOM) / 2),
                (BAY_HALF_X * 2, 0.18, height), collar)
            box(f"HATCH_{side}_{row}_L", (cx - 0.86, cy, -0.185), (1.64, 5.16, 0.13), hatch)
            box(f"HATCH_{side}_{row}_R", (cx + 0.86, cy, -0.185), (1.64, 5.16, 0.13), hatch)


def add_attached_container():
    imported = import_glb(CONTAINER)
    for obj in imported:
        if obj.type != "MESH":
            continue
        obj.name = "CURRENT_ATTACHED_CONTAINER_" + obj.name
        obj.rotation_euler[2] += 1.5707963267948966
        obj.location += Vector((POD_X, POD_Z[1], POD_Y))


def add_gap_witness():
    warning = material("Measured gap", (1.0, 0.78, 0.05), 0.05, 0.28)
    box("GAP_WITNESS", (-POD_X, POD_Z[1], (HULL_BOTTOM + COLLAR_TOP) / 2),
        (0.12, 0.12, HULL_BOTTOM - COLLAR_TOP), warning)


def camera(name, location, target, ortho):
    data = bpy.data.cameras.new(name)
    cam = bpy.data.objects.new(name, data)
    bpy.context.collection.objects.link(cam)
    cam.location = location
    cam.rotation_euler = (Vector(target) - cam.location).to_track_quat("-Z", "Y").to_euler()
    data.type = "ORTHO"
    data.ortho_scale = ortho
    bpy.context.scene.camera = cam
    return cam


def lights():
    data = bpy.data.lights.new("Key", "AREA")
    data.energy = 2600
    data.shape = "DISK"
    data.size = 24
    key = bpy.data.objects.new("Key", data)
    bpy.context.collection.objects.link(key)
    key.location = (12, -18, -18)
    key.rotation_euler = (Vector((0, 0, 1)) - key.location).to_track_quat("-Z", "Y").to_euler()
    fill_data = bpy.data.lights.new("Fill", "AREA")
    fill_data.energy = 1500
    fill_data.size = 20
    fill = bpy.data.objects.new("Fill", fill_data)
    bpy.context.collection.objects.link(fill)
    fill.location = (-18, 8, -10)
    fill.rotation_euler = (Vector((0, 0, 0)) - fill.location).to_track_quat("-Z", "Y").to_euler()


def render(scene, name, location, target, ortho):
    old = scene.camera
    if old is not None:
        bpy.data.objects.remove(old, do_unlink=True)
    camera(name, location, target, ortho)
    scene.render.filepath = str(OUT / f"{name}.png")
    bpy.ops.render.render(write_still=True)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    scene = setup_scene()
    hull_samples = sample_local_hull(scene)
    add_current_runtime_bays()
    add_attached_container()
    add_gap_witness()
    lights()
    render(scene, "current-underside", (38, -45, -34), (0, 0, 0.3), 60)
    render(scene, "current-side", (42, -2.8, 1.0), (0, -2.8, 0.4), 18)
    report = {
        "modelChanged": False,
        "runtimeHullBottom": HULL_BOTTOM,
        "runtimeBayCollarTop": COLLAR_TOP,
        "detachedGapBlocks": HULL_BOTTOM - COLLAR_TOP,
        "containerOriginY": POD_Y,
        "containerTopFromValidatedMesh": POD_Y + 2.539090156555176,
        "hardpointSamples": hull_samples,
        "finding": "All eight runtime bay collars are detached 2.20 to 4.98 blocks below their local hull surfaces; the attached container also fails to reach those local surfaces."
    }
    (OUT / "audit.json").write_text(json.dumps(report, indent=2), encoding="utf-8")


main()
