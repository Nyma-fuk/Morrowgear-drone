import bpy
import json
import math
from mathutils import Vector
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "docs" / "design" / "drone-family-a" / "reference-v2"
OUT.mkdir(parents=True, exist_ok=True)
OUTPUT_PREFIX = "scout"


def clear():
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    # Materials are constructed at module load time and must survive scene cleanup.
    for datablocks in (bpy.data.meshes, bpy.data.curves):
        for block in list(datablocks):
            if block.users == 0:
                datablocks.remove(block)


def input_named(node, *names):
    for name in names:
        if name in node.inputs:
            return node.inputs[name]


def mat(name, color, metallic=.75, rough=.34, emission=None, weather=False):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nodes, links = m.node_tree.nodes, m.node_tree.links
    bsdf = nodes.get("Principled BSDF")
    input_named(bsdf, "Metallic").default_value = metallic
    input_named(bsdf, "Roughness").default_value = rough
    if emission:
        input_named(bsdf, "Base Color").default_value = (*color, 1)
        input_named(bsdf, "Emission Color", "Emission").default_value = (*emission, 1)
        input_named(bsdf, "Emission Strength").default_value = 2.15
    elif weather:
        noise = nodes.new("ShaderNodeTexNoise")
        noise.inputs["Scale"].default_value = 7.5
        noise.inputs["Detail"].default_value = 5.0
        noise.inputs["Roughness"].default_value = .68
        ramp = nodes.new("ShaderNodeValToRGB")
        ramp.color_ramp.elements[0].position = .24
        ramp.color_ramp.elements[0].color = (*(c * .56 for c in color), 1)
        ramp.color_ramp.elements[1].position = .78
        ramp.color_ramp.elements[1].color = (*(min(1, c * 1.18 + .018) for c in color), 1)
        links.new(noise.outputs["Fac"], ramp.inputs["Fac"])
        links.new(ramp.outputs["Color"], input_named(bsdf, "Base Color"))
    else:
        input_named(bsdf, "Base Color").default_value = (*color, 1)
    return m


ARMOR = mat("MG_V2_Armor", (.15, .16, .16), .78, .41, weather=True)
ARMOR_DARK = mat("MG_V2_DarkArmor", (.065, .074, .077), .80, .43, weather=True)
MECH = mat("MG_V2_Mechanism", (.012, .016, .018), .77, .4)
EDGE = mat("MG_V2_Edge", (.43, .46, .46), .94, .24)
ORANGE = mat("MG_V2_ServiceOrange", (.72, .235, .035), .77, .31)
CYAN = mat("MG_V2_Cyan", (.004, .40, .54), .25, .2, (.01, .52, .68))
CYAN_CORE = mat("MG_V2_CyanCore", (.08, .72, .84), .12, .14, (.08, .72, .84))
ROTOR = mat("MG_V2_Rotor", (.008, .009, .01), .7, .22)
MIRROR_ORIGIN = None


def mesh(name, verts, faces, material, bevel=0, smooth=False):
    data = bpy.data.meshes.new(name + "_Mesh")
    data.from_pydata(verts, [], faces)
    data.update()
    obj = bpy.data.objects.new(name, data)
    bpy.context.collection.objects.link(obj)
    obj.data.materials.append(material)
    if bevel:
        mod = obj.modifiers.new("Reference_Edge_Bevel", "BEVEL")
        mod.width = bevel
        mod.segments = 2
        mod.limit_method = "ANGLE"
    if smooth:
        for p in data.polygons:
            p.use_smooth = True
    return obj


def mirror(obj):
    mod = obj.modifiers.new("Exact_Centerline_Mirror", "MIRROR")
    mod.use_axis[0] = True
    mod.use_clip = True
    mod.merge_threshold = .0001
    mod.mirror_object = MIRROR_ORIGIN
    return obj


def cube(name, loc, scale, material, bevel=.035, rot=(0, 0, 0), mirrored=False):
    bpy.ops.mesh.primitive_cube_add(location=loc, rotation=rot)
    obj = bpy.context.object
    obj.name = name
    obj.scale = tuple(v / 2 for v in scale)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    obj.data.materials.append(material)
    if bevel:
        mod = obj.modifiers.new("Reference_Edge_Bevel", "BEVEL")
        mod.width = bevel
        mod.segments = 2
    return mirror(obj) if mirrored else obj


def cylinder(name, loc, radius, depth, material, vertices=32, mirrored=False):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=depth, location=loc)
    obj = bpy.context.object
    obj.name = name
    obj.data.materials.append(material)
    return mirror(obj) if mirrored else obj


def loft(name, sections, material, bevel=.045):
    # Sections: y, half-width, lower-z, upper-z. Eight points preserve the angular reference silhouette.
    verts = []
    for y, hw, z0, z1 in sections:
        c = min(hw * .27, .18)
        verts.extend([
            (-hw + c, y, z0), (hw - c, y, z0),
            (hw, y, z0 + c), (hw, y, z1 - c),
            (hw - c, y, z1), (-hw + c, y, z1),
            (-hw, y, z1 - c), (-hw, y, z0 + c),
        ])
    faces, ring = [], 8
    for s in range(len(sections) - 1):
        a, b = s * ring, (s + 1) * ring
        for i in range(ring):
            n = (i + 1) % ring
            faces.append((a + i, a + n, b + n, b + i))
    faces += [tuple(reversed(range(8))), tuple((len(sections)-1)*8+i for i in range(8))]
    return mesh(name, verts, faces, material, bevel)


def polygon_ring(name, cx, cy, outer, inner_radius, z0, z1, material, mirrored=True):
    # Outer points run clockwise from the front-inner shoulder and form the characteristic swept pentagonal wing.
    n = len(outer)
    inner = []
    for i in range(n):
        angle = 2 * math.pi * i / n - math.pi / 2
        inner.append((cx + math.cos(angle) * inner_radius, cy + math.sin(angle) * inner_radius))
    verts = []
    for z in (z0, z1):
        verts += [(x, y, z) for x, y in outer]
        verts += [(x, y, z) for x, y in inner]
    faces = []
    ob, ib, ot, it = 0, n, n*2, n*3
    for i in range(n):
        j = (i + 1) % n
        faces += [
            (ot+i, ot+j, it+j, it+i),
            (ob+j, ob+i, ib+i, ib+j),
            (ob+i, ob+j, ot+j, ot+i),
            (ib+j, ib+i, it+i, it+j),
        ]
    obj = mesh(name, verts, faces, material, .045)
    return mirror(obj) if mirrored else obj


def curve(name, pts, material, depth=.018, mirrored=False, cyclic=False):
    data = bpy.data.curves.new(name, "CURVE")
    data.dimensions = "3D"
    data.bevel_depth = depth
    data.bevel_resolution = 2
    spline = data.splines.new("POLY")
    spline.points.add(len(pts)-1)
    for p, co in zip(spline.points, pts):
        p.co = (*co, 1)
    spline.use_cyclic_u = cyclic
    obj = bpy.data.objects.new(name, data)
    bpy.context.collection.objects.link(obj)
    obj.data.materials.append(material)
    return mirror(obj) if mirrored else obj


def ribbon(name, centerline, half_height, material):
    """Create a flush faceted band on the nose instead of a protruding tube."""
    verts = []
    for x, y, z in centerline:
        verts.append((x, y, z + half_height))
        verts.append((x, y, z - half_height))
    faces = []
    for i in range(len(centerline) - 1):
        a = i * 2
        faces.append((a, a + 2, a + 3, a + 1))
    return mesh(name, verts, faces, material, 0)


def fin(name, x0, x1, profile, material=ARMOR_DARK, mirrored=True):
    verts = [(x, y, z) for x in (x0, x1) for y, z in profile]
    faces = [(0,1,2,3), (7,6,5,4), (0,4,5,1), (1,5,6,2), (2,6,7,3), (3,7,4,0)]
    obj = mesh(name, verts, faces, material, .025)
    return mirror(obj) if mirrored else obj


def add_panel(name, loc, scale, mirrored=False, orange=False):
    return cube(name, loc, scale, ORANGE if orange else ARMOR, .025, mirrored=mirrored)


def build():
    # Front is -Y, rear is +Y, vertical is +Z.
    body = loft("F01_Fuselage", [
        (-2.70, .48, -.20, .18),
        (-2.38, .66, -.25, .30),
        (-1.45, .78, -.28, .48),
        (-.15, .84, -.30, .62),
        (1.25, .78, -.28, .67),
        (2.15, .60, -.24, .55),
        (2.58, .37, -.17, .32),
    ], ARMOR_DARK, .055)

    # Layered upper armor and panel rhythm visible in the reference top view.
    loft("F02_UpperArmor", [
        (-2.30, .48, .24, .40), (-1.35, .62, .42, .69),
        (.15, .66, .56, .82), (1.45, .59, .58, .78), (2.08, .40, .43, .60)
    ], ARMOR, .035)
    for i, y in enumerate((-1.55, -.48, .62, 1.52)):
        add_panel(f"F02_TopPanel_{i}", (0, y, .80 if y > -.8 else .72), (.82, .72, .055))
    cube("F02_CenterSpine", (0, .28, .835), (.065, 3.05, .045), MECH, .012)
    cube("M01_FrontLatch", (0, -1.08, .78), (.18, .22, .055), ORANGE, .014)
    cube("M01_RearLatch", (0, 1.20, .84), (.18, .22, .055), ORANGE, .014)

    # Angular wing ducts. Coordinates are sampled from the top-view proportions.
    cx, cy = 2.10, -.05
    outer = [
        (1.10, -1.52), (2.30, -1.63), (3.63, -1.22), (3.95, -.30),
        (3.65, .88), (3.16, 1.68), (2.65, 1.10), (1.45, 1.18),
        (1.05, .55), (.98, -.52)
    ]
    polygon_ring("W02_DuctWing.R", cx, cy, outer, 1.14, -.12, .28, ARMOR, True)
    curve("W02_OuterAlloyEdge.R", [(x,y,.305) for x,y in outer], EDGE, .013, True, True)
    curve("W02_ServiceSeam.R", [
        (1.24,-1.34,.318),(2.27,-1.47,.318),(3.45,-1.10,.318),
        (3.69,-.28,.318),(3.43,.78,.318),(3.08,1.38,.318)
    ], ORANGE, .010, True, False)

    # Raised segmented panels are separate pieces, matching the layered worn armor.
    panel_specs = [
        ((2.04,-1.42,.32),(1.42,.34,.07),-5),
        ((3.28,-.91,.32),(.62,.58,.07),-22),
        ((3.55,.22,.32),(.50,.75,.07),5),
        ((3.17,1.12,.32),(.65,.62,.07),22),
        ((1.67,1.03,.32),(.72,.34,.07),5),
    ]
    for i, (loc, scale, rz) in enumerate(panel_specs):
        cube(f"W02_ArmorPanel_{i}.R", loc, scale, ARMOR, .025, (0,0,math.radians(rz)), True)

    # Inner lip, cyan ring, hub and blades.
    cylinder("R02_InnerWell.R", (cx, cy, .10), 1.12, .16, MECH, 48, True)
    ring_pts=[]
    # The reference does not use a complete neon ring; the rear-inboard section is interrupted.
    for i in range(41):
        a=math.radians(-170 + i * (160/40))
        ring_pts.append((cx+1.13*math.cos(a), cy+1.13*math.sin(a), .31))
    curve("L02_CyanRing.R", ring_pts, CYAN, .018, True, False)
    cylinder("R02_HubBase.R", (cx,cy,.31), .25, .13, MECH, 32, True)
    cylinder("R02_HubCap.R", (cx,cy,.41), .15, .11, EDGE, 32, True)
    for index in range(5):
        a = math.radians(index * 72)
        blade_center = (cx + .48 * math.cos(a), cy + .48 * math.sin(a), .28)
        cube(f"R02_Blade_{index}.R", blade_center, (.82,.16,.045), ROTOR, .018, (0,0,a), True)

    # Short mechanical wing roots keep the duct visually integrated with the fuselage.
    cube("W02_FrontRoot.R", (1.20,-.83,.05), (1.23,.38,.34), ARMOR_DARK, .045, (0,0,math.radians(-7)), True)
    cube("W02_RearRoot.R", (1.18,.82,.10), (1.15,.36,.34), ARMOR_DARK, .045, (0,0,math.radians(7)), True)
    cube("M01_WingLatch.R", (.88,-.68,.25), (.12,.20,.12), ORANGE, .018, mirrored=True)

    # Angular shoulder housings bridge the fuselage and ducts, as visible beside the reference nose.
    cube("F04_ShoulderHousing.R", (.76,-1.24,.08), (.32,.68,.42), ARMOR, .040, mirrored=True)
    cube("F04_ShoulderFace.R", (.76,-1.575,.08), (.18,.030,.18), MECH, .018, mirrored=True)
    cube("F04_ShoulderFastener.R", (.76,-1.596,.08), (.065,.018,.065), EDGE, .010, mirrored=True)

    # The visor follows the actual nose face. A broad dark recess contains a much thinner emissive insert.
    ribbon("S01_VisorRecess", [(-.50,-2.716,.090),(0,-2.728,.060),(.50,-2.716,.090)], .055, MECH)
    ribbon("S01_EmbeddedVisor", [(-.48,-2.724,.088),(0,-2.738,.065),(.48,-2.724,.088)], .022, CYAN_CORE)
    # Continue the same sensor band around both nose corners and along the side armor.
    ribbon("S01_SideVisorRecess.R", [
        (.49,-2.710,.090),(.585,-2.560,.103),(.645,-2.380,.118),(.690,-2.155,.132)
    ], .052, MECH)
    mirror(bpy.data.objects["S01_SideVisorRecess.R"])
    ribbon("S01_SideVisor.R", [
        (.502,-2.716,.090),(.598,-2.566,.103),(.658,-2.386,.118),(.703,-2.160,.132)
    ], .020, CYAN_CORE)
    mirror(bpy.data.objects["S01_SideVisor.R"])
    cube("S02_ChinHousing", (0,-2.65,-.20), (.39,.24,.31), ARMOR_DARK, .045)
    cube("S02_ChinLensBezel", (0,-2.785,-.19), (.25,.040,.20), EDGE, .020)
    cube("S02_ChinLens", (0,-2.812,-.19), (.15,.022,.11), CYAN_CORE, .012)
    cube("S01_CheekSensor.R", (.55,-2.51,-.10), (.13,.11,.14), MECH, .02, mirrored=True)
    cube("S01_CheekAmber.R", (.55,-2.575,-.10), (.045,.022,.055), ORANGE, .008, mirrored=True)

    # Only the paired body fins are vertical. The outer triangular forms are low duct fairings.
    fin("W04_RearTipFairing.R", 3.16, 3.29, [(.96,.27),(1.55,.30),(1.78,.82),(1.27,.62)], ARMOR_DARK, True)
    fin("T02_CenterFin.R", .38, .49, [(1.50,.58),(2.02,.60),(2.33,1.30),(1.78,1.10)], ARMOR_DARK, True)
    curve("W04_RearTipSeam.R", [(3.30,1.05,.31),(3.30,1.76,.79)], ORANGE, .010, True)
    curve("T02_CenterFinEdge.R", [(.50,1.57,.64),(.50,2.32,1.28)], ORANGE, .013, True)

    # Narrow articulated strut and a single fore-aft skid as seen in the reference side view.
    cube("G02_UpperHinge.R", (.47,-1.02,-.39), (.19,.22,.17), ARMOR_DARK, .030, mirrored=True)
    cube("G02_Strut.R", (.56,-1.02,-.67), (.14,.17,.63), MECH, .024, (0,math.radians(21),0), True)
    cube("G02_StrutEdge.R", (.585,-1.025,-.67), (.035,.18,.56), EDGE, .010, (0,math.radians(21),0), True)
    cube("G02_LowerKnuckle.R", (.67,-1.02,-.92), (.19,.21,.17), ARMOR_DARK, .030, mirrored=True)
    cube("G02_LongitudinalSkid.R", (.67,-1.02,-1.03), (.22,.76,.13), ARMOR_DARK, .030, mirrored=True)
    cube("G02_SkidFrontCap.R", (.67,-1.39,-1.02), (.23,.10,.14), EDGE, .016, mirrored=True)
    cube("G02_SkidRearCap.R", (.67,-.65,-1.02), (.23,.10,.14), ARMOR, .016, mirrored=True)

    # Layered rear service and cooling assembly.
    cube("V01_RearHousing", (0,2.50,.05), (.99,.25,.78), ARMOR_DARK, .055)
    cube("V01_AlloyBezel", (0,2.642,.05), (.79,.040,.65), EDGE, .030)
    cube("V01_Recess", (0,2.668,.05), (.68,.028,.56), MECH, .024)
    cube("V01_ServicePanel", (0,2.688,.25), (.48,.020,.19), ARMOR_DARK, .018)
    cube("V01_ServiceLatch", (0,2.704,.25), (.10,.012,.055), ORANGE, .008)
    for i in range(4):
        cube(f"V01_Vent_{i}", (0,2.702,-.15+i*.075), (.46,.020,.036), ARMOR_DARK, .006)
    cube("V01_LowerBumper", (0,2.635,-.40), (.54,.18,.15), ARMOR_DARK, .025)
    cube("V01_UpperCap", (0,2.36,.55), (.45,.34,.18), ARMOR, .035)
    cube("V02_RearLampPod.R", (.67,2.45,-.08), (.27,.28,.27), ARMOR_DARK, .035, mirrored=True)
    cube("V02_RearLampBezel.R", (.67,2.606,-.08), (.15,.035,.15), ARMOR, .018, mirrored=True)
    cube("V02_RearLamp.R", (.67,2.630,-.08), (.075,.018,.075), CYAN, .012, mirrored=True)
    cube("F03_UndersideSpine", (0,.10,-.39), (.54,3.65,.20), MECH, .045)
    for y in (-.88,.05,.96):
        cube(f"F03_BellyPanel_{y}", (0,y,-.505), (.39,.64,.035), ARMOR_DARK, .015)

    return body


def look(obj, target=(0,0,.05)):
    obj.rotation_euler = (Vector(target)-obj.location).to_track_quat("-Z","Y").to_euler()


def setup():
    scene=bpy.context.scene
    scene.render.engine="BLENDER_EEVEE"
    scene.render.image_settings.file_format="PNG"
    scene.render.resolution_percentage=100
    scene.world.color=(.035,.035,.035)
    scene.view_settings.look="AgX - Medium High Contrast"
    for typ,loc,energy,color,size in [
        ("AREA",(5,-7,8),1250,(1,.92,.82),5.5),
        ("AREA",(-6,-2,4),360,(.22,.48,.56),4.0),
        ("AREA",(1,7,5),1050,(.65,.72,.78),4.0),
        ("AREA",(0,-2,-7),720,(.42,.58,.62),4.0)]:
        bpy.ops.object.light_add(type=typ, location=loc)
        l=bpy.context.object; l.data.energy=energy; l.data.color=color; l.data.shape="DISK"; l.data.size=size; look(l)
    bpy.ops.mesh.primitive_plane_add(size=30, location=(0,0,-1.38))
    floor=bpy.context.object; floor.name="StudioFloor"; floor.data.materials.append(mat("Studio",(.075,.075,.073),.05,.72))
    bpy.ops.object.camera_add(); cam=bpy.context.object; scene.camera=cam
    return cam


def render(cam,name,loc,ortho=False,scale=8.8):
    scene=bpy.context.scene; cam.location=loc; look(cam)
    cam.data.type="ORTHO" if ortho else "PERSP"
    if ortho: cam.data.ortho_scale=scale
    else: cam.data.lens=58
    scene.render.resolution_x=1400; scene.render.resolution_y=900
    scene.render.filepath=str(OUT/f"{OUTPUT_PREFIX}-{name}.png")
    floor = bpy.data.objects.get("StudioFloor")
    if floor:
        floor.hide_render = name == "bottom"
    bpy.ops.render.render(write_still=True)
    if floor:
        floor.hide_render = False


def validate():
    objects=[o for o in bpy.context.scene.objects if o.type=="MESH" and o.name!="StudioFloor"]
    mirrored=[o for o in objects if any(m.type=="MIRROR" for m in o.modifiers)]
    required=["F01_Fuselage","W02_DuctWing.R","S01_EmbeddedVisor","W04_RearTipFairing.R","T02_CenterFin.R","G02_LongitudinalSkid.R","V01_RearHousing"]
    missing=[n for n in required if n not in bpy.data.objects]
    report={
        "pass": not missing and len(objects)>=45 and len(mirrored)>=18,
        "reference":"exec-a2e4ec83-d18d-4cca-82e1-b39d7e6c2221.png",
        "meshObjects":len(objects), "mirroredObjects":len(mirrored), "missingRequired":missing,
        "frontAxis":"-Y", "upAxis":"+Z", "geometrySymmetry":"exact mirror modifiers",
        "asymmetryPolicy":"surface wear and maintenance markings only",
        "states":{"rotors":"flight rotating / dock stopped","landingGear":"deployed","visor":"emissive","fins":"fixed"}
    }
    (OUT/"scout-reference-validation.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
    if not report["pass"]: raise RuntimeError(report)
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/"morrowgear-family-a-scout-reference-v2.blend"))
    bpy.ops.export_scene.gltf(filepath=str(OUT/"morrowgear-family-a-scout-reference-v2.glb"),export_format="GLB")


def main():
    global MIRROR_ORIGIN
    clear()
    bpy.ops.object.empty_add(type="PLAIN_AXES", location=(0, 0, 0))
    MIRROR_ORIGIN = bpy.context.object
    MIRROR_ORIGIN.name = "MG_V2_Centerline"
    MIRROR_ORIGIN.hide_render = True
    build()
    camera = setup()
    render(camera,"hero",(7.2,-9.4,4.7),False)
    render(camera,"front",(0,-12,.05),True,8.3)
    render(camera,"rear",(0,12,.05),True,8.3)
    render(camera,"top",(0,0,13),True,9.3)
    render(camera,"bottom",(0,0,-13),True,9.3)
    render(camera,"left",(-13,0,.15),True,6.2)
    render(camera,"right",(13,0,.15),True,6.2)
    validate()


if __name__ == "__main__":
    main()
