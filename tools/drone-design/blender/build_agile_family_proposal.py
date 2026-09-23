"""Approval-only symmetric family. Does not write game resources."""
import importlib.util
import json
import math
from pathlib import Path

import bmesh
import bpy
from mathutils import Matrix, Vector, kdtree

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = ROOT / "docs/design/agile-family-v4"
spec = importlib.util.spec_from_file_location("parts", HERE / "build_family_a_variants_v3.py")
parts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(parts)
b = parts.base
OUT.mkdir(parents=True, exist_ok=True)
ARMOR = b.mat("V4_Graphite", (.13, .16, .18), .75, .32)
SHELL = b.mat("V4_Titanium", (.32, .36, .39), .8, .3)
DARK = b.mat("V4_Recess", (.015, .022, .025), .45, .4)
LIGHT = b.mat("V4_Link", (.02, .5, .65), .2, .3, (.02, .5, .65))
AMBER = b.mat("V4_Service", (.8, .32, .035), .5, .38)


def box(name, pos, size, material=ARMOR, pair=False):
    return b.cube(name, pos, size, material, .025, mirrored=pair)


def build(role):
    b.clear()
    bpy.ops.object.empty_add()
    b.MIRROR_ORIGIN = bpy.context.object
    b.MIRROR_ORIGIN.name = "Symmetry_X0"
    b.loft("Keel", [(-2.7, .12, -.12, .04), (-1.6, .49, -.3, .32),
                       (.5, .68, -.32, .42), (1.8, .36, -.16, .28)], ARMOR)
    b.loft("Dorsal_Shell", [(-2.45, .11, .04, .14), (-1.2, .42, .22, .46),
                               (.55, .48, .34, .56), (1.65, .22, .22, .4)], SHELL)
    box("Visor_Recess", (0, -2.28, .055), (.52, .13, .16), DARK)
    box("Embedded_Visor", (0, -2.355, .055), (.4, .018, .045), LIGHT)
    for j, y in enumerate((-.95, 1.0)):
        parts.beam(f"Load_Boom_{j}", (.43, y-.35, -.25), (1.6, y+.1, -.25), .25, .18, ARMOR, mirrored=True)
        parts.annular_ring(f"Duct_{j}", 1.62, y+.15, .78, .65, -.12, .18, ARMOR, mirrored=True)
        parts.annular_ring(f"Duct_Lip_{j}", 1.62, y+.15, .77, .68, .17, .22, SHELL, mirrored=True)
        parts.annular_ring(f"Duct_Link_{j}", 1.62, y+.15, .782, .773, -.03, .0, LIGHT, mirrored=True)
        b.cylinder(f"Rotor_Hub_{j}", (1.62, y+.15, .07), .13, .21, SHELL, mirrored=True)
        for n in range(8):
            a = n * math.tau / 8
            # Solid blade, with no floating highlight layer.
            r0, r1 = .13, .61
            pts = []
            for z in (.035, .085):
                for r, offset in ((r0,-.13), (r1,-.05), (r1,.17), (r0,.45)):
                    pts.append((1.62 + r*math.cos(a+offset), y+.15+r*math.sin(a+offset), z))
            o = b.mesh(f"Blade_{j}_{n}", pts,
                       [(0,3,2,1),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)], DARK, .006)
            b.mirror(o)
        for offset in (-.31, .31):
            box(f"Latch_{j}_{offset}", (1.62+offset, y+.83, .16), (.1,.15,.08), SHELL, True)
    for y in (-1.25, 1.05):
        parts.beam("Landing_Strut", (.49,y,-.22), (.8,y+.15,-.67), .13,.19,SHELL,mirrored=True)
    box("Skid", (.81,-.02,-.7), (.22,2.3,.11), DARK, True)
    for y in (.55,.75,.95,1.15):
        box("Cooling_Slat", (.39,y,.44), (.2,.055,.07), DARK, True)
    box("Service_Catch", (.57,.4,.22), (.06,.2,.09), AMBER, True)
    if role == "scout":
        b.cylinder("Sensor_Lens", (0,-1.55,-.34), .25,.12,LIGHT)
        parts.beam("Sensor_Fin", (.48,.45,.42), (.74,1.1,.7), .055,.22,ARMOR,mirrored=True)
    elif role == "cargo":
        b.loft("Cargo_Bay", [(-1.3,.39,-.62,-.19),(.85,.5,-.62,-.18),(1.2,.32,-.46,-.17)],ARMOR)
        for y in (-.8,-.4,0,.4,.8):
            box("Cargo_Rib", (.42,y,-.43), (.06,.065,.32), SHELL, True)
    elif role == "engineer":
        parts.annular_ring("Work_Aperture",0,-.65,.31,.21,-.48,-.28,SHELL,mirrored=False)
        b.cylinder("Work_Lens",(0,-.65,-.46),.2,.035,LIGHT)
        parts.beam("Tool_Rail",(.55,-.9,-.27),(.62,.55,-.28),.14,.16,ARMOR,mirrored=True)
    elif role == "security":
        parts.beam("Weapon_Rail",(.64,-.55,-.19),(.7,.85,-.12),.21,.25,ARMOR,mirrored=True)
        box("Weapon_Housing",(.7,-.48,-.29),(.28,1.05,.24),DARK,True)
        box("Forward_Barrel",(.7,-1.2,-.28),(.1,.65,.1),SHELL,True)
        b.cylinder("Center_Optic",(0,-.5,-.34),.21,.12,LIGHT)
    elif role == "salvage":
        box("Winch_Bay",(0,-.2,-.45),(.65,1.1,.35),ARMOR)
        box("Winch_Drum",(0,-.2,-.59),(.45,.42,.19),SHELL)
        box("Stowed_Clamp",(.19,-.2,-.71),(.12,.55,.16),AMBER,True)
        box("Load_Skid",(.93,-.05,-.77),(.25,2.6,.14),DARK,True)
    else:
        box("Service_Bay",(0,-.1,-.36),(.62,.85,.15),ARMOR)
        box("Service_Contacts",(.19,-.1,-.45),(.065,.43,.03),AMBER,True)


def canonicalize():
    # Evaluate each component, retain +X half and mirror around a common world origin.
    for obj in list(bpy.context.scene.objects):
        if obj.type != "MESH":
            continue
        mesh = bpy.data.meshes.new_from_object(obj.evaluated_get(bpy.context.evaluated_depsgraph_get()))
        mesh.transform(obj.matrix_world)
        bm = bmesh.new()
        bm.from_mesh(mesh)
        bmesh.ops.bisect_plane(bm, geom=list(bm.verts)+list(bm.edges)+list(bm.faces),
                              dist=1e-7, plane_co=(0,0,0), plane_no=(1,0,0), clear_inner=True)
        bm.to_mesh(mesh)
        bm.free()
        obj.modifiers.clear()
        obj.data = mesh
        obj.matrix_world = Matrix.Identity(4)
        b.mirror(obj)


def validate(role):
    errors, count = [], 0
    deps = bpy.context.evaluated_depsgraph_get()
    for obj in bpy.context.scene.objects:
        if obj.type != "MESH":
            continue
        ev = obj.evaluated_get(deps)
        mesh = ev.to_mesh()
        coords = [obj.matrix_world @ v.co for v in mesh.vertices]
        kd = kdtree.KDTree(len(coords))
        for i,p in enumerate(coords): kd.insert(p,i)
        kd.balance()
        error = max((kd.find(Vector((-p.x,p.y,p.z)))[2] for p in coords), default=0)
        count += len(coords)
        if error > 1e-5: errors.append({"object":obj.name,"maxError":error})
        ev.to_mesh_clear()
    report = {"role":role,"verticesChecked":count,"tolerance":1e-5,
              "geometrySymmetryPass":not errors,"errors":errors,
              "visualApproval":False,"gameVerified":False,"animationVerified":False}
    (OUT/role/"symmetry.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
    if errors: raise RuntimeError(report)


def render_role(role):
    target = OUT/role
    target.mkdir(exist_ok=True)
    build(role)
    canonicalize()
    validate(role)
    scene = bpy.context.scene
    scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x, scene.render.resolution_y = 1000, 750
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.world.use_nodes = True
    scene.world.node_tree.nodes["Background"].inputs[0].default_value = (.22,.24,.26,1)
    scene.world.node_tree.nodes["Background"].inputs[1].default_value = .6
    scene.view_settings.look = "AgX - Medium High Contrast"
    for x in (-4,4):
        bpy.ops.object.light_add(type="AREA",location=(x,-3,7))
        light=bpy.context.object
        light.data.energy=1000
        light.data.shape="DISK"
        light.data.size=6
        b.look(light)
    bpy.ops.object.light_add(type="AREA",location=(0,2,-5))
    bpy.context.object.data.energy=450
    bpy.context.object.data.size=5
    b.look(bpy.context.object)
    bpy.ops.object.camera_add()
    camera=bpy.context.object
    camera.data.type="ORTHO"
    camera.data.ortho_scale=6.9
    scene.camera=camera
    views={"hero":(7,-9,6),"front":(0,-12,0),"rear":(0,12,0),"top":(0,-.25,12),
           "bottom":(0,-.25,-12),"left":(-12,-.25,0),"right":(12,-.25,0)}
    for name,pos in views.items():
        camera.location=pos
        b.look(camera,(0,-.25,0))
        scene.render.filepath=str(target/f"{name}.png")
        bpy.ops.render.render(write_still=True)
    camera.location=views["hero"]
    b.look(camera,(0,-.25,0))
    bpy.ops.wm.save_as_mainfile(filepath=str(target/f"{role}-proposal.blend"))
    bpy.ops.export_scene.gltf(filepath=str(target/f"{role}-proposal.glb"),export_format="GLB")
    print("PROPOSAL_COMPLETE",role)


for role in ("field","scout","cargo","engineer","security","salvage"):
    render_role(role)
