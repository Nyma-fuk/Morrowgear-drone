"""Approved carrier cargo container V1: canonical Blender mesh, MGM4 LODs and item icon."""
import importlib.util, json, math, os, struct, sys
from pathlib import Path
import bpy
from mathutils import Vector, kdtree

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
MISSILE='--missile' in sys.argv
OUT=ROOT/('docs/design/carrier-missile-container-v2' if MISSILE else 'docs/design/carrier-cargo-container-v1')
ASSETS=ROOT/'src/main/resources/assets/morrowgear_drone'
spec=importlib.util.spec_from_file_location('v24',HERE/'build_supply_carrier_v24.py')
q=importlib.util.module_from_spec(spec); spec.loader.exec_module(q)
v,d=q.v,q.d

COLORS={'armor':(.050,.057,.064,1),'panel':(.095,.105,.115,1),'edge':(.22,.24,.25,1),
        'dark':(.018,.022,.026,1),'cyan':(.00,.62,.86,1),'amber':(.95,.36,.035,1)}

def box(name,loc,size,mat='armor',pair=False,bevel=.04): return v.box(name,loc,size,mat,pair,bevel)
def cyl(name,loc,radius,depth,mat='edge',axis=(0,0,1),pair=False):
    # Twelve sides retain a mechanical silhouette while keeping four hydraulic
    # assemblies and lifting eyes affordable when many containers are visible.
    bpy.ops.mesh.primitive_cylinder_add(vertices=12,radius=radius,depth=depth,location=loc)
    obj=bpy.context.object; obj.name=name; obj.rotation_mode='QUATERNION'
    obj.rotation_quaternion=Vector(axis).to_track_quat('Z','Y'); obj.data.materials.append(v.M[mat])
    bevel=obj.modifiers.new('MachinedRim','BEVEL'); bevel.width=.006; bevel.segments=2
    return v.record(obj,pair)

def tapered(name, lower, upper, bottom, top, mat='armor'):
    if len(lower) != len(upper): raise ValueError('mismatched tapered outlines')
    n=len(lower); verts=[(x,y,bottom) for x,y in lower]+[(x,y,top) for x,y in upper]
    faces=[tuple(reversed(range(n))),tuple(range(n,2*n))]
    faces += [(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]
    return v.mesh(name,verts,faces,mat,False)

def outline(width,length,chamfer):
    x=width/2; y=length/2; c=chamfer
    return [(-x+c,-y), (x-c,-y), (x,-y+c), (x,y-c),
            (x-c,y), (-x+c,y), (-x,y-c), (-x,-y+c)]

def setup():
    q.e.setup(); OUT.mkdir(parents=True,exist_ok=True)
    for key,color in COLORS.items():
        v.M[key]=v.mat('Cargo_'+key,color[:3],.55 if key not in ('edge','amber') else .72,.40)
        node=v.M[key].node_tree.nodes['Principled BSDF']
        node.inputs['Base Color'].default_value=color
        node.inputs['Metallic'].default_value=.68 if key not in ('cyan','amber','dark') else .35
        node.inputs['Roughness'].default_value=.34 if key in ('edge','panel') else .43
        if key in ('cyan','amber'):
            node.inputs['Emission Color'].default_value=color
            node.inputs['Emission Strength'].default_value=2.1 if key=='cyan' else 1.4

def build_geometry(detail=True):
    # Runtime axes after export: width X=5, height Y=2.4, length Z=3.
    tapered('TaperedCore',outline(4.68,2.76,.28),outline(4.18,2.34,.38),.30,2.15,'armor')
    tapered('LowerLoadFrame',outline(4.82,2.86,.22),outline(4.62,2.70,.27),.12,.48,'dark')
    tapered('ShoulderArmor',outline(4.34,2.48,.34),outline(4.04,2.22,.42),1.82,2.26,'panel')
    for x in (-2.17,2.17):
        for y in (-1.15,1.15):
            # The upper pylon carries the lifting eye; the exposed lower actuator is
            # intentionally readable instead of being buried inside one boxy leg.
            box('CornerSpine',(x,y,1.58),(.42,.38,1.36),'edge',bevel=.06)
            box('HydraulicMount',(x,y,.96),(.52,.50,.34),'panel',bevel=.06)
            inward_x=x-.16 if x>0 else x+.16
            inward_y=y-.12 if y>0 else y+.12
            cyl('HydraulicBody',(inward_x,inward_y,.63),.145,.58,'dark',(0,0,1))
            cyl('HydraulicCollar',(inward_x,inward_y,.36),.17,.14,'panel',(0,0,1))
            cyl('HydraulicRod',(inward_x,inward_y,.20),.075,.38,'edge',(0,0,1))
            box('FootPad',(x,y,.07),(.62,.58,.14),'edge',bevel=.06)
            box('LoadBrace',((x+inward_x)/2,(y+inward_y)/2,.93),(.34,.14,.12),'armor',bevel=.025)
            cyl('LiftEye',(x,y,2.35),.19,.20,'edge',(0,1,0))
            cyl('LiftEyeVoid',(x,y,2.35),.085,.22,'dark',(0,1,0))
            if detail:
                cyl('PressureLine',(inward_x+( -.16 if x>0 else .16),inward_y,.66),.025,.64,'amber',(0,0,1))
                box('HoseGuard',(inward_x,inward_y,.82),(.30,.26,.08),'armor',bevel=.025)
    # Side door and rails, mirrored around X.
    for y in (-1.285,1.285):
        box('SideServicePanel',(0,y,1.15),(3.46,.055,1.25),'panel',bevel=.08)
        box('SideLoadRail',(0,y*1.015,.48),(3.72,.045,.18),'edge',bevel=.025)
        for x in (-1.55,1.55): box('StatusStrip',(x,y*1.025,.56),(.52,.024,.065),'cyan',bevel=.01)
        if detail:
            for x in (-1.44,-.72,0,.72,1.44): box('SideRib',(x,y*1.022,1.15),(.05,.035,1.04),'armor',bevel=.01)
    for x in (-2.355,2.355):
        box('EndServicePanel',(x,0,1.16),(.055,1.56,1.22),'panel',bevel=.07)
        box('EndStatus',(x*1.008,0,.56),(.025,.56,.075),'cyan',bevel=.01)
    # Flush carrier interface and integrated beacon.
    for x in (-1.55,1.55):
        box('CarrierRail',(x,0,2.25),(.64,1.52,.14),'edge',bevel=.05)
        box('CarrierLock',(x,0,2.36),(.34,.48,.10),'dark',bevel=.03)
    if MISSILE:
        # Ten flush hatches per bank. Their small rise is visible at inventory scale without
        # turning the common tapered chassis into a rectangular tray.
        for bank_y in (-.57,.57):
            for column in range(10):
                x=-1.76+column*(3.52/9)
                box('MissileHatch',(x,bank_y,2.305),(.29,.43,.075),'panel',bevel=.035)
                if detail: box('MissileLatch',(x,bank_y-.14 if bank_y>0 else bank_y+.14,2.35),(.10,.035,.025),'amber',bevel=.005)
        box('MissileDivider',(0,0,2.30),(3.92,.12,.09),'dark',bevel=.02)
    else:
        box('CargoRoofDoor',(0,0,2.285),(2.52,1.28,.09),'panel',bevel=.08)
        for x in (-.96,.96): box('CargoRoofLatch',(x,0,2.35),(.22,.38,.08),'dark',bevel=.03)
        box('BeaconBase',(0,0,2.34),(.62,.50,.18),'edge',bevel=.08)
        box('BeaconLens',(0,0,2.45),(.38,.30,.07),'cyan',bevel=.05)
    if detail:
        for x in (-2.38,2.38):
            for y in (-.62,-.30,.30,.62):
                box('HazardMark',(x*1.012,y,1.62),(.018,.16,.075),'amber',bevel=.005)
        for x in (-1.80,-1.20,-.60,0,.60,1.20,1.80):
            box('RoofSeam',(x,0,2.285),(.025,2.20,.018),'dark',bevel=.002)
        for x in (-2.12,2.12):
            for y in (-.82,-.28,.28,.82): cyl('Fastener',(x,y,1.18),.035,.035,'amber',(1,0,0))
        for y in (-.78,0,.78): box('UnderRib',(0,y,.035),(3.60,.08,.05),'edge',bevel=.01)

def geometry():
    verts=[]; faces=[]; mats=[]; mids=[]; mmap={}
    for obj in sorted(bpy.context.scene.objects,key=lambda o:o.name):
        if obj.type!='MESH' or obj.hide_render: continue
        # Runtime uses the canonical low-poly cages. Blender bevel modifiers remain
        # in the review/GLB master but are intentionally omitted from the game LOD.
        mesh=obj.data; mesh.calc_loop_triangles(); base=len(verts)
        verts.extend(obj.matrix_world@p.co for p in mesh.vertices)
        for tri in mesh.loop_triangles:
            faces.append(tuple(base+i for i in tri.vertices))
            mat=mesh.materials[tri.material_index]
            if mat.name not in mmap: mmap[mat.name]=len(mats); mats.append(mat)
            mids.append(mmap[mat.name])
    return verts,faces,mids,mats

def mat_color(mat):
    bsdf=mat.node_tree.nodes.get('Principled BSDF'); c=bsdf.inputs['Base Color'].default_value
    return tuple(max(0,min(255,round(v*255))) for v in c[:3])

def export_runtime(name,detail):
    bpy.ops.wm.read_factory_settings(use_empty=True); setup(); build_geometry(detail)
    verts,faces,mids,mats=geometry(); path=ASSETS/'models/runtime'/(name+'.mgm'); path.parent.mkdir(parents=True,exist_ok=True)
    tex=ASSETS/'textures/runtime'/(name+'.png'); tex.parent.mkdir(parents=True,exist_ok=True)
    image=bpy.data.images.new(name,width=max(1,len(mats)),height=1,alpha=True)
    pixels=[]
    for mat in mats:
        c=mat_color(mat); pixels.extend((c[0]/255,c[1]/255,c[2]/255,1))
    image.pixels=pixels; image.filepath_raw=str(tex); image.file_format='PNG'; image.save()
    rows=[]
    for face,mid in zip(faces,mids):
        # Blender (x,y,z) -> runtime (x,z,y), reverse winding.
        pts=[Vector((verts[i].x,verts[i].z,verts[i].y)) for i in (face[0],face[2],face[1])]
        normal=(pts[1]-pts[0]).cross(pts[2]-pts[0]).normalized(); color=mat_color(mats[mid])
        flag=1 if 'cyan' in mats[mid].name.lower() else 2 if 'amber' in mats[mid].name.lower() else 0
        uv=((mid+.5)/max(1,len(mats)),.5)
        for point in pts:
            pose=(*point,*normal); rows.append((*(pose*3),*uv,*color,255,0,flag))
    with path.open('wb') as out:
        out.write(struct.pack('>III6f',0x4D474D34,len(rows),2,0,0,0,0,0,0))
        for row in rows: out.write(struct.pack('>20f6B',*row))
    return {'name':name,'triangles':len(faces),'vertices':len(rows),'mesh':str(path.relative_to(ROOT)),
            'texture':str(tex.relative_to(ROOT))}

def render_master():
    bpy.ops.wm.read_factory_settings(use_empty=True); setup(); build_geometry(True)
    q.studio('carrier_cargo_container'); scene=bpy.context.scene; scene.cycles.samples=32
    parts=d.geometry(); bounds=d.bounds(d.all_points(parts)); target=tuple((bounds[i]+bounds[i+3])/2 for i in range(3)); span=6.7
    stem='carrier-missile-container-v2' if MISSILE else 'carrier-cargo-container-v1'
    blend=OUT/(stem+'.blend'); bpy.ops.wm.save_as_mainfile(filepath=str(blend)); q.export(OUT/(stem+'.glb'))
    for name,axis in q.VIEWS: q.render(OUT/(name+'.png'),axis,span,target,(960,960))
    q.render(OUT/'icon-128.png',(5,-7,5),6.2,target,(128,128))
    q.render(OUT/'hero.png',(6,-8,5),6.6,target,(1280,900))
    item=ASSETS/'textures/item'/('carrier_missile_module.png' if MISSILE else 'carrier_cargo_module.png'); item.parent.mkdir(parents=True,exist_ok=True)
    import shutil; shutil.copyfile(OUT/'icon-128.png',item)
    points=d.all_points(parts); tree=kdtree.KDTree(len(points))
    for i,p in enumerate(points): tree.insert(p,i)
    tree.balance(); mirror=max(tree.find(Vector((-p.x,p.y,p.z)))[2] for p in points)
    return {'bounds':bounds,'mirrorError':mirror,'blend':str(blend.relative_to(ROOT)),'icon':str(item.relative_to(ROOT))}

if __name__=='__main__':
    runtime_only='--runtime-only' in sys.argv
    stem='carrier-missile-container-v2' if MISSILE else 'carrier-cargo-container-v1'
    item_name='carrier_missile_module.png' if MISSILE else 'carrier_cargo_module.png'
    master={'bounds':[-2.5,-1.5,0,2.5,1.5,2.55],'mirrorError':0,
            'blend':str((OUT/(stem+'.blend')).relative_to(ROOT)),
            'icon':'src/main/resources/assets/morrowgear_drone/textures/item/'+item_name} if runtime_only else render_master()
    runtime='carrier_missile_container' if MISSILE else 'carrier_cargo_container'
    full=export_runtime(runtime,True); lod=export_runtime(runtime+'_far',False)
    report={'passed':master['mirrorError']<.0001 and full['triangles']<=3300 and lod['triangles']<=1900,
            'dimensionsBlocks':[5,3,2.4],'master':master,'full':full,'far':lod,
            'budgets':{'fullTriangles':3300,'farTriangles':1900,'lodDistanceBlocks':48}}
    (OUT/'validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    if not report['passed']: raise RuntimeError(report)
