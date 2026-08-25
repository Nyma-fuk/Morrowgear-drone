import bpy
import math
import os
import struct
import sys
import re


SCALE = 0.22
MAGIC_V2 = b"MGM2"
MAGIC_V3 = b"MGM3"
DECIMATE_RATIO = 0.5


def material_color(obj, material_index):
    if material_index < len(obj.data.materials):
        material = obj.data.materials[material_index]
        if material is not None:
            color = material.diffuse_color
            if material.use_nodes and material.node_tree:
                shader = material.node_tree.nodes.get("Principled BSDF")
                if shader is not None:
                    base = shader.inputs.get("Base Color")
                    if base and base.is_linked and base.links[0].from_node.type == "VALTORGB":
                        ramp_colors = [element.color for element in base.links[0].from_node.color_ramp.elements]
                        color = tuple(sum(entry[channel] for entry in ramp_colors) / len(ramp_colors) for channel in range(4))
                    elif base:
                        color = base.default_value
            r, g, b, a = color
            return tuple(max(0, min(255, round(channel * 255))) for channel in (a, r, g, b))
    return 255, 68, 76, 80


def collect_vertices(decimate_ratio=DECIMATE_RATIO):
    depsgraph = bpy.context.evaluated_depsgraph_get()
    records = []
    min_z = math.inf

    for source in bpy.context.scene.objects:
        if source.type not in {"MESH", "CURVE"} or source.name == "StudioFloor" or source.hide_render:
            continue
        decimate = None
        polygon_threshold = 4 if decimate_ratio < 0.3 else 12
        if source.type == "MESH" and len(source.data.polygons) > polygon_threshold:
            decimate = source.modifiers.new(name="MorrowgearGameDecimate", type="DECIMATE")
            decimate.ratio = decimate_ratio
        evaluated = source.evaluated_get(depsgraph)
        mesh = evaluated.to_mesh()
        mesh.calc_loop_triangles()
        world = evaluated.matrix_world
        normal_matrix = world.to_3x3().inverted().transposed()
        colors = [material_color(evaluated, index) for index in range(max(1, len(mesh.materials)))]

        for triangle in mesh.loop_triangles:
            color = colors[min(triangle.material_index, len(colors) - 1)]
            triangle_positions = [world @ mesh.vertices[mesh.loops[index].vertex_index].co for index in triangle.loops]
            rotor_group = 0
            center_x = sum(position.x for position in triangle_positions) / 3
            generic_rotor = re.match(r"MG_Rotor_([1-8])_", source.name)
            if generic_rotor:
                rotor_group = int(generic_rotor.group(1))
            elif source.name.startswith("R02_Blade_") or source.name.startswith("SAL_Blade_F"):
                rotor_group = 1 if center_x < 0 else 2
            elif source.name.startswith("SAL_Blade_R"):
                rotor_group = 3 if center_x < 0 else 4
            for loop_index in triangle.loops:
                loop = mesh.loops[loop_index]
                position = world @ mesh.vertices[loop.vertex_index].co
                normal = (normal_matrix @ loop.normal).normalized()
                min_z = min(min_z, position.z)
                records.append((position.x, position.y, position.z, normal.x, normal.y, normal.z, color, rotor_group))
        evaluated.to_mesh_clear()
        if decimate is not None:
            source.modifiers.remove(decimate)

    floor_offset = -min_z + 0.04
    vertices = [
        (x * SCALE, (z + floor_offset) * SCALE, -y * SCALE, nx, nz, -ny, color, rotor_group)
        for x, y, z, nx, ny, nz, color, rotor_group in records
    ]
    centers = []
    rotor_count = max(vertex[-1] for vertex in vertices)
    for rotor_group in range(1, rotor_count + 1):
        positions = [vertex[:3] for vertex in vertices if vertex[-1] == rotor_group]
        if not positions:
            raise RuntimeError(f"missing rotor group {rotor_group}")
        centers.append(tuple(sum(position[axis] for position in positions) / len(positions) for axis in range(3)))
    # Static stations and independently animated relay craft have no mesh rotor group.
    # MGM2 still carries two center slots; neutral centers keep those vertices static.
    while len(centers) < 2:
        centers.append((0.0, 0.0, 0.0))
    return vertices, centers


def write_mesh(path, vertices, centers):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as output:
        output.write(MAGIC_V3 if len(centers) > 2 else MAGIC_V2)
        output.write(struct.pack(">I", len(vertices)))
        if len(centers) > 2:
            output.write(struct.pack(">I", len(centers)))
            output.write(struct.pack(f">{len(centers) * 3}f", *(component for center in centers for component in center)))
        else:
            output.write(struct.pack(">6f", *(centers[0] + centers[1])))
        for x, y, z, nx, ny, nz, (a, r, g, b), rotor_group in vertices:
            output.write(struct.pack(">6f5B", x, y, z, nx, ny, nz, a, r, g, b, rotor_group))
    print(f"MORROWGEAR_MESH {path} vertices={len(vertices)} triangles={len(vertices) // 3}")


if __name__ == "__main__":
    args = sys.argv[sys.argv.index("--") + 1 :]
    if len(args) != 1:
        raise SystemExit("usage: blender --background model.blend --python export_drone_meshes.py -- output.mgm")
    output_path = os.path.abspath(args[0])
    # The four-rotor salvage source keeps a high-detail canonical Blend, while
    # its runtime mesh uses a stronger reduction to remain viable in large wings.
    runtime_ratio = 0.24 if "salvage" in os.path.basename(output_path).lower() else DECIMATE_RATIO
    exported_vertices, rotor_centers = collect_vertices(runtime_ratio)
    write_mesh(output_path, exported_vertices, rotor_centers)
