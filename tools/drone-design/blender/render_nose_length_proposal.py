import argparse
import math
import sys
from pathlib import Path

import bpy
from mathutils import Vector


FRONT_ANCHOR_Y = -1.45
FRONT_TIP_Y = -2.82
DEFORM_PREFIXES = ("F01_", "F02_", "F04_", "S01_", "S02_", "M01_Front")


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--extension", type=float, default=0.0)
    separator = sys.argv.index("--") if "--" in sys.argv else len(sys.argv)
    args = parser.parse_args(sys.argv[separator + 1 :])
    return args


def extended_world_y(world_y, extension):
    if extension <= 0.0 or world_y >= FRONT_ANCHOR_Y:
        return world_y
    ratio = min(1.0, max(0.0, (FRONT_ANCHOR_Y - world_y) / (FRONT_ANCHOR_Y - FRONT_TIP_Y)))
    return world_y - extension * ratio * ratio


def deform_point(obj, point, extension):
    world = obj.matrix_world @ point
    world.y = extended_world_y(world.y, extension)
    return obj.matrix_world.inverted() @ world


def deform_nose(extension):
    for obj in bpy.context.scene.objects:
        if not obj.name.startswith(DEFORM_PREFIXES):
            continue
        if obj.type == "MESH":
            for vertex in obj.data.vertices:
                vertex.co = deform_point(obj, vertex.co, extension)
            obj.data.update()
        elif obj.type == "CURVE":
            for spline in obj.data.splines:
                if spline.type == "BEZIER":
                    for point in spline.bezier_points:
                        point.co = deform_point(obj, point.co, extension)
                        point.handle_left = deform_point(obj, point.handle_left, extension)
                        point.handle_right = deform_point(obj, point.handle_right, extension)
                else:
                    for point in spline.points:
                        local = deform_point(obj, Vector(point.co[:3]), extension)
                        point.co = (*local, point.co.w)


def point_camera(camera, location, target):
    camera.location = location
    camera.rotation_euler = (Vector(target) - camera.location).to_track_quat("-Z", "Y").to_euler()


def configure_render(output):
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x = 860
    scene.render.resolution_y = 620
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    scene.render.filepath = str(output)
    camera = bpy.data.objects.get("Camera")
    if camera is None:
        camera_data = bpy.data.cameras.new("Camera")
        camera = bpy.data.objects.new("Camera", camera_data)
        scene.collection.objects.link(camera)
    scene.camera = camera
    camera.data.type = "ORTHO"
    camera.data.ortho_scale = 8.6
    point_camera(camera, (7.8, -10.8, 4.8), (0.0, -0.25, 0.0))


def main():
    args = parse_args()
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    deform_nose(args.extension)
    configure_render(output)
    bpy.ops.render.render(write_still=True)
    print(f"NOSE_PROPOSAL extension={args.extension:.3f} output={output}")


if __name__ == "__main__":
    main()
