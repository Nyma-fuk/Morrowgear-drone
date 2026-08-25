import bpy
import json
import math
from mathutils import Vector
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
OUTPUT = ROOT / "docs" / "design" / "drone-family-a" / "blender-v1"
OUTPUT.mkdir(parents=True, exist_ok=True)


def clear_scene():
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    for data in (bpy.data.meshes, bpy.data.curves, bpy.data.materials, bpy.data.cameras, bpy.data.lights):
        pass


def node_input(node, *names):
    for name in names:
        if name in node.inputs:
            return node.inputs[name]
    return None


def material(name, color, metallic, roughness, emission=None):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    links = mat.node_tree.links
    bsdf = nodes.get("Principled BSDF")
    base = node_input(bsdf, "Base Color")
    metal = node_input(bsdf, "Metallic")
    rough = node_input(bsdf, "Roughness")
    if metal:
        metal.default_value = metallic
    if rough:
        rough.default_value = roughness
    if emission:
        if base:
            base.default_value = (*color, 1)
        emit_color = node_input(bsdf, "Emission Color", "Emission")
        emit_strength = node_input(bsdf, "Emission Strength")
        if emit_color:
            emit_color.default_value = (*emission, 1)
        if emit_strength:
            emit_strength.default_value = 2.6
        return mat

    noise = nodes.new("ShaderNodeTexNoise")
    noise.inputs["Scale"].default_value = 18.0
    noise.inputs["Detail"].default_value = 3.0
    noise.inputs["Roughness"].default_value = 0.55
    ramp = nodes.new("ShaderNodeValToRGB")
    dark = tuple(max(0.0, channel * 0.72) for channel in color)
    light = tuple(min(1.0, channel * 1.16 + 0.015) for channel in color)
    ramp.color_ramp.elements[0].color = (*dark, 1)
    ramp.color_ramp.elements[1].color = (*light, 1)
    links.new(noise.outputs["Fac"], ramp.inputs["Fac"])
    if base:
        links.new(ramp.outputs["Color"], base)
    return mat


ARMOR = material("MG_Armor", (0.055, 0.09, 0.105), 0.86, 0.34)
ARMOR_MID = material("MG_Armor_Mid", (0.13, 0.19, 0.22), 0.82, 0.3)
MECHANISM = material("MG_Mechanism", (0.018, 0.028, 0.034), 0.76, 0.4)
SILVER = material("MG_Alloy_Edge", (0.34, 0.43, 0.47), 0.92, 0.24)
ORANGE = material("MG_Service_Orange", (0.78, 0.21, 0.035), 0.72, 0.3)
CYAN = material("MG_Cyan", (0.01, 0.64, 0.72), 0.28, 0.18, (0.01, 0.64, 0.72))
CYAN_CORE = material("MG_Cyan_Core", (0.18, 0.9, 0.94), 0.15, 0.12, (0.18, 0.9, 0.94))
ROTOR = material("MG_Rotor", (0.008, 0.012, 0.016), 0.8, 0.24)


def mesh_object(name, vertices, faces, mat, bevel=0.0, smooth=False):
    mesh = bpy.data.meshes.new(f"{name}_Mesh")
    mesh.from_pydata(vertices, [], faces)
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.collection.objects.link(obj)
    obj.data.materials.append(mat)
    if bevel > 0:
        modifier = obj.modifiers.new("Edge_Bevel", "BEVEL")
        modifier.width = bevel
        modifier.segments = 2
        modifier.limit_method = "ANGLE"
    if smooth:
        for polygon in mesh.polygons:
            polygon.use_smooth = True
    return obj


def chamfer_ring(width, height, chamfer, y):
    w = width / 2
    h = height / 2
    return [
        (-w + chamfer, y, -h), (w - chamfer, y, -h),
        (w, y, -h + chamfer), (w, y, h - chamfer),
        (w - chamfer, y, h), (-w + chamfer, y, h),
        (-w, y, h - chamfer), (-w, y, -h + chamfer)
    ]


def add_bevel(obj, width, segments=2):
    modifier = obj.modifiers.new("Edge_Bevel", "BEVEL")
    modifier.width = width
    modifier.segments = segments
    modifier.limit_method = "ANGLE"
    return modifier


def loft_body(name, sections, mat, bevel=0.055):
    vertices = []
    for y, width, center_z, height, chamfer in sections:
        vertices.extend((x, y, z + center_z) for x, _, z in chamfer_ring(width, height, chamfer, y))
    faces = []
    ring = 8
    for section in range(len(sections) - 1):
        start = section * ring
        next_start = (section + 1) * ring
        for index in range(ring):
            following = (index + 1) % ring
            faces.append((start + index, start + following, next_start + following, next_start + index))
    faces.append(tuple(reversed(range(ring))))
    final = (len(sections) - 1) * ring
    faces.append(tuple(final + index for index in range(ring)))
    return mesh_object(name, vertices, faces, mat, bevel=bevel)


def carve_recess(target, name, location, scale, bevel=0.035):
    bpy.ops.mesh.primitive_cube_add(location=location)
    cutter = bpy.context.object
    cutter.name = name
    cutter.scale = (scale[0] / 2, scale[1] / 2, scale[2] / 2)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    add_bevel(cutter, bevel, 3)
    bpy.context.view_layer.objects.active = cutter
    bpy.ops.object.modifier_apply(modifier="Edge_Bevel")
    modifier = target.modifiers.new(f"{name}_Boolean", "BOOLEAN")
    modifier.operation = "DIFFERENCE"
    modifier.solver = "EXACT"
    modifier.object = cutter
    bpy.context.view_layer.objects.active = target
    target.select_set(True)
    bpy.ops.object.modifier_apply(modifier=modifier.name)
    bpy.data.objects.remove(cutter, do_unlink=True)


def add_beveled_cube(name, location, scale, mat, bevel=0.04, rotation=(0, 0, 0)):
    bpy.ops.mesh.primitive_cube_add(location=location, rotation=rotation)
    obj = bpy.context.object
    obj.name = name
    obj.scale = (scale[0] / 2, scale[1] / 2, scale[2] / 2)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    obj.data.materials.append(mat)
    if bevel:
        modifier = obj.modifiers.new("Edge_Bevel", "BEVEL")
        modifier.width = bevel
        modifier.segments = 2
    return obj


def mirror_origin():
    bpy.ops.object.empty_add(type="PLAIN_AXES", location=(0, 0, 0))
    obj = bpy.context.object
    obj.name = "MG_Mirror_Origin"
    obj.hide_render = True
    return obj


MIRROR = None


def add_mirror(obj):
    modifier = obj.modifiers.new("Exact_X_Mirror", "MIRROR")
    modifier.use_axis[0] = True
    modifier.use_clip = True
    modifier.merge_threshold = 0.0001
    modifier.mirror_object = MIRROR
    return obj


def wing_duct_right():
    count = 16
    center_x = 2.42
    center_y = 0.02
    z_bottom = -0.06
    z_top = 0.25
    vertices = []
    outer = []
    inner = []
    for index in range(count):
        angle = 2 * math.pi * index / count
        outward = max(0.0, math.cos(angle))
        rear_sweep = max(0.0, math.sin(angle))
        front_cut = max(0.0, -math.sin(angle))
        radius = 1.4 + 0.4 * outward + 0.2 * rear_sweep + 0.06 * front_cut
        radius *= 1.0 - 0.055 * math.cos(4 * angle)
        outer.append((center_x + radius * math.cos(angle), center_y + radius * math.sin(angle)))
        inner_radius = 1.07
        inner.append((center_x + inner_radius * math.cos(angle), center_y + inner_radius * math.sin(angle)))
    for z in (z_bottom, z_top):
        vertices.extend((x, y, z) for x, y in outer)
        vertices.extend((x, y, z) for x, y in inner)
    faces = []
    ob, ib, ot, it = 0, count, count * 2, count * 3
    for i in range(count):
        n = (i + 1) % count
        faces.append((ot + i, ot + n, it + n, it + i))
        faces.append((ob + n, ob + i, ib + i, ib + n))
        faces.append((ob + i, ob + n, ot + n, ot + i))
        faces.append((ib + n, ib + i, it + i, it + n))
    duct = mesh_object("Rotor_Duct.R", vertices, faces, ARMOR_MID, bevel=0.045)
    add_mirror(duct)
    return outer, inner


def curve_from_points(name, points, mat, bevel_depth, mirror=True):
    curve = bpy.data.curves.new(name, "CURVE")
    curve.dimensions = "3D"
    curve.bevel_depth = bevel_depth
    curve.bevel_resolution = 2
    spline = curve.splines.new("POLY")
    spline.points.add(len(points) - 1)
    for point, coordinate in zip(spline.points, points):
        point.co = (*coordinate, 1)
    obj = bpy.data.objects.new(name, curve)
    bpy.context.collection.objects.link(obj)
    obj.data.materials.append(mat)
    if mirror:
        add_mirror(obj)
    return obj


def add_cylinder(name, location, radius, depth, mat, vertices=24, mirror=False):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=depth, location=location)
    obj = bpy.context.object
    obj.name = name
    obj.data.materials.append(mat)
    if mirror:
        add_mirror(obj)
    return obj


def make_fin_right():
    x0, x1 = 0.42, 0.54
    profile = [
        (1.35, 0.42),
        (2.15, 0.44),
        (2.35, 1.25),
        (1.82, 1.08)
    ]
    vertices = [(x, y, z) for x in (x0, x1) for y, z in profile]
    faces = [
        (0, 1, 2, 3), (7, 6, 5, 4),
        (0, 4, 5, 1), (1, 5, 6, 2),
        (2, 6, 7, 3), (3, 7, 4, 0)
    ]
    fin = mesh_object("Tail_Fin.R", vertices, faces, ARMOR_MID, bevel=0.025)
    add_mirror(fin)
    assert profile[2][0] > profile[0][0], "Tail fin tip must lean toward rear (+Y)"
    return fin


def build_model():
    sections = [
        (-2.42, 0.72, -0.05, 0.48, 0.12),
        (-1.85, 1.02, 0.02, 0.62, 0.16),
        (-0.9, 1.34, 0.08, 0.76, 0.2),
        (0.35, 1.48, 0.13, 0.84, 0.22),
        (1.48, 1.32, 0.16, 0.78, 0.2),
        (2.22, 0.9, 0.1, 0.62, 0.15)
    ]
    fuselage = loft_body("Fuselage_Main", sections, ARMOR, bevel=0.0)
    carve_recess(fuselage, "Visor_Recess_Cutter", (0, -2.39, 0.06), (0.86, 0.34, 0.24), 0.055)
    add_bevel(fuselage, 0.055, 3)
    top_sections = [(y, width * 0.7, center_z + height * 0.36, height * 0.32, chamfer * 0.7)
                    for y, width, center_z, height, chamfer in sections[1:-1]]
    loft_body("Fuselage_Upper_Armor", top_sections, ARMOR_MID)
    outer, _ = wing_duct_right()
    trim_points = [(x, y, 0.31) for x, y in outer] + [(outer[0][0], outer[0][1], 0.31)]
    curve_from_points("Duct_Outer_Trim.R", trim_points, SILVER, 0.025)
    cyan_points = []
    for index in range(25):
        angle = math.radians(198 + index * (144 / 24))
        cyan_points.append((2.42 + 1.11 * math.cos(angle), 0.02 + 1.11 * math.sin(angle), 0.335))
    curve_from_points("Duct_Cyan_Arc.R", cyan_points, CYAN, 0.035)

    add_beveled_cube("Rotor_Arm_Front.R", (1.28, -0.55, 0.05), (1.65, 0.18, 0.18), MECHANISM, 0.03, (0, 0, math.radians(-8)))
    add_mirror(bpy.context.object)
    add_beveled_cube("Rotor_Arm_Rear.R", (1.28, 0.62, 0.05), (1.65, 0.18, 0.18), MECHANISM, 0.03, (0, 0, math.radians(8)))
    add_mirror(bpy.context.object)

    add_cylinder("Rotor_Hub_Lower.R", (2.42, 0.02, 0.34), 0.25, 0.14, MECHANISM, 32, True)
    add_cylinder("Rotor_Hub.R", (2.42, 0.02, 0.43), 0.2, 0.18, SILVER, 32, True)
    add_cylinder("Rotor_Hub_Cap.R", (2.42, 0.02, 0.54), 0.11, 0.08, ARMOR_MID, 24, True)
    for angle in (0, 60, 120):
        blade = add_beveled_cube(f"Rotor_Blade_{angle}.R", (2.42, 0.02, 0.34), (1.75, 0.12, 0.055), ROTOR, 0.018,
                                 (0, 0, math.radians(angle)))
        add_mirror(blade)

    make_fin_right()
    add_beveled_cube("Visor_Recess_Back", (0, -2.29, 0.06), (0.73, 0.055, 0.17), MECHANISM, 0.025)
    add_beveled_cube("Visor_Eye.Left", (-0.18, -2.325, 0.055), (0.34, 0.03, 0.055), CYAN, 0.018,
                     (0, math.radians(-4), 0))
    add_beveled_cube("Visor_Eye.Right", (0.18, -2.325, 0.055), (0.34, 0.03, 0.055), CYAN, 0.018,
                     (0, math.radians(4), 0))
    add_beveled_cube("Sensor_Chin", (0, -2.4, -0.22), (0.34, 0.24, 0.25), MECHANISM, 0.04)
    add_beveled_cube("Sensor_Core", (0, -2.53, -0.22), (0.16, 0.025, 0.11), CYAN_CORE, 0.008)
    add_cylinder("Scout_Optic", (0, -1.42, -0.58), 0.13, 0.18, CYAN_CORE, 24)

    for x in (-0.31, 0.31):
        add_beveled_cube(f"Top_Inlay_{x:+.2f}", (x, -0.12, 0.595), (0.49, 1.22, 0.035), ARMOR_MID, 0.014)
    add_beveled_cube("Top_Center_Groove", (0, -0.12, 0.61), (0.035, 1.35, 0.018), MECHANISM, 0.005)
    add_beveled_cube("Service_Latch_Front", (0, -0.65, 0.635), (0.18, 0.24, 0.035), ORANGE, 0.012)
    add_beveled_cube("Service_Latch_Rear", (0, 0.95, 0.635), (0.18, 0.24, 0.035), ORANGE, 0.012)

    intake = add_beveled_cube("Side_Intake.R", (0.64, -1.48, 0.02), (0.055, 0.48, 0.2), MECHANISM, 0.012,
                              (0, 0, math.radians(-6)))
    add_mirror(intake)
    fastener = add_cylinder("Upper_Fastener.R", (0.46, 0.62, 0.64), 0.035, 0.025, SILVER, 16, True)

    strut = add_beveled_cube("Landing_Strut.R", (0.56, -0.9, -0.66), (0.13, 0.18, 0.95), MECHANISM, 0.025,
                             (0, math.radians(19), 0))
    add_mirror(strut)
    skid = add_beveled_cube("Landing_Skid.R", (0.73, -0.9, -1.12), (0.72, 0.2, 0.12), SILVER, 0.025)
    add_mirror(skid)


def look_at(obj, target=(0, 0, 0.05)):
    direction = Vector(target) - obj.location
    obj.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()


def setup_render():
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_EEVEE"
    scene.render.image_settings.file_format = "PNG"
    scene.render.resolution_percentage = 100
    scene.render.film_transparent = False
    scene.world.color = (0.018, 0.025, 0.03)
    scene.view_settings.look = "AgX - Medium High Contrast"

    bpy.ops.object.light_add(type="AREA", location=(4.5, -5.5, 7))
    key = bpy.context.object
    key.name = "Key_Light"
    key.data.energy = 1150
    key.data.shape = "DISK"
    key.data.size = 5.0
    look_at(key)
    bpy.ops.object.light_add(type="AREA", location=(-4, -1, 3))
    fill = bpy.context.object
    fill.name = "Cyan_Fill"
    fill.data.energy = 600
    fill.data.color = (0.08, 0.65, 0.8)
    fill.data.size = 4
    look_at(fill)
    bpy.ops.object.light_add(type="AREA", location=(0, 5, 4))
    rim = bpy.context.object
    rim.name = "Rear_Rim"
    rim.data.energy = 850
    rim.data.size = 3
    look_at(rim)

    bpy.ops.mesh.primitive_plane_add(size=30, location=(0, 0, -1.2))
    floor = bpy.context.object
    floor.name = "Studio_Floor"
    floor.data.materials.append(material("Studio", (0.025, 0.035, 0.04), 0.1, 0.7))

    bpy.ops.object.camera_add()
    camera = bpy.context.object
    camera.name = "Design_Camera"
    scene.camera = camera
    return camera


def render_view(camera, name, location, ortho=False, ortho_scale=7.5):
    scene = bpy.context.scene
    camera.location = location
    camera.data.type = "ORTHO" if ortho else "PERSP"
    if ortho:
        camera.data.ortho_scale = ortho_scale
    else:
        camera.data.lens = 58
    look_at(camera)
    scene.render.resolution_x = 1200
    scene.render.resolution_y = 800
    scene.render.filepath = str(OUTPUT / f"scout-{name}.png")
    bpy.ops.render.render(write_still=True)


def validate_and_export():
    mesh_objects = [obj for obj in bpy.context.scene.objects if obj.type == "MESH" and obj.name != "Studio_Floor"]
    mirrored = [obj for obj in mesh_objects if any(mod.type == "MIRROR" for mod in obj.modifiers)]
    fin = bpy.data.objects["Tail_Fin.R"]
    rearward_tip = max(vertex.co.y for vertex in fin.data.vertices) > min(vertex.co.y for vertex in fin.data.vertices)
    vertices = sum(len(obj.data.vertices) for obj in mesh_objects)
    faces = sum(len(obj.data.polygons) for obj in mesh_objects)
    report = {
        "pass": len(mirrored) >= 10 and rearward_tip and vertices > 300 and faces > 250,
        "meshObjects": len(mesh_objects),
        "mirrorModifierObjects": len(mirrored),
        "verticesBeforeModifiers": vertices,
        "facesBeforeModifiers": faces,
        "tailFinLeansRearward": rearward_tip,
        "frontAxis": "-Y",
        "rearAxis": "+Y"
    }
    (OUTPUT / "scout-mesh-validation.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    if not report["pass"]:
        raise RuntimeError(f"Mesh validation failed: {report}")
    bpy.ops.wm.save_as_mainfile(filepath=str(OUTPUT / "morrowgear-family-a-scout.blend"))
    bpy.ops.export_scene.gltf(filepath=str(OUTPUT / "morrowgear-family-a-scout.glb"), export_format="GLB")


clear_scene()
MIRROR = mirror_origin()
build_model()
camera = setup_render()
render_view(camera, "hero", (6.2, -8.2, 5.4), False)
render_view(camera, "front", (0, -10, 0.2), True, 6.0)
render_view(camera, "rear", (0, 10, 0.2), True, 6.0)
render_view(camera, "top", (0, 0, 11), True, 7.4)
render_view(camera, "bottom", (0, 0, -11), True, 7.4)
render_view(camera, "left", (-11, 0, 0.4), True, 5.5)
render_view(camera, "right", (11, 0, 0.4), True, 5.5)
validate_and_export()
