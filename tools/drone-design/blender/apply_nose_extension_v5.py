import argparse
import importlib.util
import json
import sys
from pathlib import Path

import bpy
from mathutils import Vector


ROOT = Path(__file__).resolve().parents[3]
SCRIPT_DIR = Path(__file__).resolve().parent
OUTPUT_ROOT = ROOT / "docs" / "design" / "drone-family-a" / "reference-v5-nose-extended"
EXTENSION = 0.35
FRONT_ANCHOR_Y = -1.45
FRONT_TIP_Y = -2.82
DEFORM_PREFIXES = ("F01_", "F02_", "F04_", "S01_", "S02_", "M01_Front")


def load_base_module():
    spec = importlib.util.spec_from_file_location("family_a_base_v5", SCRIPT_DIR / "build_family_a_scout_v2.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--role", required=True, choices=("scout", "engineer", "field", "guard"))
    separator = sys.argv.index("--") if "--" in sys.argv else len(sys.argv)
    return parser.parse_args(sys.argv[separator + 1 :])


def extended_world_y(world_y):
    if world_y >= FRONT_ANCHOR_Y:
        return world_y
    ratio = min(1.0, max(0.0, (FRONT_ANCHOR_Y - world_y) / (FRONT_ANCHOR_Y - FRONT_TIP_Y)))
    return world_y - EXTENSION * ratio * ratio


def deform_point(obj, point):
    world = obj.matrix_world @ point
    world.y = extended_world_y(world.y)
    return obj.matrix_world.inverted() @ world


def deform_nose():
    changed_objects = []
    for obj in bpy.context.scene.objects:
        if not obj.name.startswith(DEFORM_PREFIXES):
            continue
        changed_objects.append(obj.name)
        if obj.type == "MESH":
            for vertex in obj.data.vertices:
                vertex.co = deform_point(obj, vertex.co)
            obj.data.update()
        elif obj.type == "CURVE":
            for spline in obj.data.splines:
                if spline.type == "BEZIER":
                    for point in spline.bezier_points:
                        point.co = deform_point(obj, point.co)
                        point.handle_left = deform_point(obj, point.handle_left)
                        point.handle_right = deform_point(obj, point.handle_right)
                else:
                    for point in spline.points:
                        local = deform_point(obj, Vector(point.co[:3]))
                        point.co = (*local, point.co.w)
    return changed_objects


def world_bounds(objects):
    positions = []
    for obj in objects:
        if obj.type not in {"MESH", "CURVE"} or obj.name == "StudioFloor":
            continue
        positions.extend(obj.matrix_world @ Vector(corner) for corner in obj.bound_box)
    return {
        "minX": min(point.x for point in positions),
        "maxX": max(point.x for point in positions),
        "minY": min(point.y for point in positions),
        "maxY": max(point.y for point in positions),
        "minZ": min(point.z for point in positions),
        "maxZ": max(point.z for point in positions),
    }


def render_views(base, role, output):
    cameras = [obj for obj in bpy.context.scene.objects if obj.type == "CAMERA"]
    if not cameras:
        raise RuntimeError("Canonical v4 model has no camera")
    camera = cameras[0]
    base.OUT = output
    base.OUTPUT_PREFIX = role
    base.render(camera, "hero", (7.6, -10.2, 5.0), False)
    base.render(camera, "front", (0, -13, 0.0), True, 9.0)
    base.render(camera, "rear", (0, 13, 0.0), True, 9.0)
    base.render(camera, "top", (0, 0, 14), True, 10.0)
    base.render(camera, "bottom", (0, 0, -14), True, 10.0)
    base.render(camera, "left", (-14, 0, 0.0), True, 7.2)
    base.render(camera, "right", (14, 0, 0.0), True, 7.2)


def save_and_validate(role, output, changed_objects):
    meshes = [obj for obj in bpy.context.scene.objects if obj.type == "MESH" and obj.name != "StudioFloor"]
    bounds = world_bounds(meshes)
    rotors = [obj for obj in meshes if obj.name.startswith("R02_Blade_")]
    report = {
        "pass": bounds["minY"] < -3.10 and len(rotors) == 5 and len(changed_objects) >= 12,
        "role": role,
        "frontAxis": "-Y",
        "upAxis": "+Z",
        "noseExtensionModelUnits": EXTENSION,
        "noseExtensionPercent": 6,
        "deformationAnchorY": FRONT_ANCHOR_Y,
        "changedObjects": changed_objects,
        "rotorSourceObjects": len(rotors),
        "rotorRenderedCount": len(rotors) * 2,
        "bounds": bounds,
        "unchangedSystems": ["duct wings", "turbines", "landing gear", "role equipment"],
    }
    (output / f"{role}-nose-v5-validation.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    if not report["pass"]:
        raise RuntimeError(report)
    blend = output / f"morrowgear-family-a-{role}-v5.blend"
    glb = output / f"morrowgear-family-a-{role}-v5.glb"
    bpy.ops.wm.save_as_mainfile(filepath=str(blend))
    bpy.ops.export_scene.gltf(filepath=str(glb), export_format="GLB")
    print(json.dumps(report))


def main():
    args = parse_args()
    base = load_base_module()
    output = OUTPUT_ROOT / args.role
    output.mkdir(parents=True, exist_ok=True)
    changed_objects = deform_nose()
    render_views(base, args.role, output)
    save_and_validate(args.role, output, changed_objects)


if __name__ == "__main__":
    main()
