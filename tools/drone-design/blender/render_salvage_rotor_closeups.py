import bpy
from mathutils import Vector
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "docs" / "design" / "drone-family-a" / "reference-v3" / "salvage"
CAMERA = bpy.data.objects["Camera"]
TARGET = Vector((2.32, -1.16, .20))


def render(name, location):
    CAMERA.location = location
    CAMERA.rotation_euler = (TARGET - CAMERA.location).to_track_quat("-Z", "Y").to_euler()
    CAMERA.data.type = "PERSP"
    CAMERA.data.lens = 72
    scene = bpy.context.scene
    scene.camera = CAMERA
    scene.render.resolution_x = 1200
    scene.render.resolution_y = 1200
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.filepath = str(OUT / name)
    bpy.ops.render.render(write_still=True)


render("salvage-rotor-closeup-top.png", Vector((4.55, -3.40, 3.25)))
bpy.data.objects["StudioFloor"].hide_render = True
render("salvage-rotor-closeup-bottom.png", Vector((4.55, -3.40, -2.25)))
