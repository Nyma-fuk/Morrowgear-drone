import argparse
import math
import sys
from pathlib import Path

import bpy
from mathutils import Vector


def look_at(obj, target):
    obj.rotation_euler = (Vector(target) - obj.location).to_track_quat("-Z", "Y").to_euler()


def render_bounds():
    points = []
    for obj in bpy.context.scene.objects:
        if obj.type != "MESH" or obj.hide_render:
            continue
        if any(token in obj.name.lower() for token in ("floor", "ground", "backdrop", "studio")):
            obj.hide_render = True
            continue
        points.extend(obj.matrix_world @ Vector(corner) for corner in obj.bound_box)
    if not points:
        raise RuntimeError("No renderable mesh objects found")
    low = Vector((min(p.x for p in points), min(p.y for p in points), min(p.z for p in points)))
    high = Vector((max(p.x for p in points), max(p.y for p in points), max(p.z for p in points)))
    return (low + high) * 0.5, high - low


def add_area_light(name, center, offset, energy, size, color):
    bpy.ops.object.light_add(type="AREA", location=center + Vector(offset))
    light = bpy.context.object
    light.name = name
    light.data.energy = energy
    light.data.shape = "DISK"
    light.data.size = size
    light.data.color = color
    look_at(light, center)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--size", type=int, default=768)
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    args = parser.parse_args(argv)

    center, extent = render_bounds()
    span = max(extent.x, extent.y, extent.z)

    for obj in list(bpy.context.scene.objects):
        if obj.type in {"CAMERA", "LIGHT"}:
            bpy.data.objects.remove(obj, do_unlink=True)

    bpy.ops.object.camera_add(location=center + Vector((1.45, -2.15, 1.30)) * span)
    camera = bpy.context.object
    camera.data.type = "ORTHO"
    camera.data.ortho_scale = max(extent.x * 1.22, extent.y * 1.05, extent.z * 1.65)
    look_at(camera, center)

    add_area_light("Icon_Key", center, (1.7 * span, -2.3 * span, 2.5 * span), 1250, 0.9 * span, (0.95, 0.98, 1.0))
    add_area_light("Icon_Fill", center, (-2.0 * span, -0.5 * span, 1.1 * span), 650, 1.1 * span, (0.20, 0.65, 0.78))
    add_area_light("Icon_Rim", center, (0.5 * span, 2.2 * span, 1.8 * span), 900, 0.8 * span, (0.95, 0.55, 0.18))

    scene = bpy.context.scene
    scene.camera = camera
    scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x = args.size
    scene.render.resolution_y = args.size
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    scene.render.film_transparent = True
    scene.render.filepath = str(Path(args.output).resolve())
    scene.view_settings.look = "AgX - Medium High Contrast"
    scene.view_settings.exposure = 1.15
    scene.render.image_settings.color_depth = "8"
    bpy.ops.render.render(write_still=True)


if __name__ == "__main__":
    main()
