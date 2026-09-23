"""Native, isolated carrier proposal. Never publishes runtime assets."""
import sys
sys.dont_write_bytecode = True
import bpy
import bmesh
import json
import math
import hashlib
import importlib.util
from pathlib import Path
from mathutils import Vector, kdtree
from mathutils.bvhtree import BVHTree

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'docs/design/carrier-v29-proposal'
OUT.mkdir(parents=True, exist_ok=True)
M = {}
ROLES = ('field','scout','cargo','engineer','security','salvage')
BAY_SIZE = Vector((3.5,4.0,2.3))
BAY_FLOOR_Z = 2.36


def material(name, color, metal=.5, emission=0):
    mat = bpy.data.materials.new(name)
    mat.diffuse_color = (*color, 1)
    mat.use_nodes = True
    node = mat.node_tree.nodes.get('Principled BSDF')
    node.inputs['Base Color'].default_value = (*color, 1)
    node.inputs['Metallic'].default_value = metal
    node.inputs['Roughness'].default_value = .43
    node.inputs['Emission Color'].default_value = (*color, 1)
    node.inputs['Emission Strength'].default_value = emission
    M[name] = mat


def mesh(name, verts, faces, mat, mirror=True):
    if mirror:
        faces = [face for face in faces if not all(abs(verts[i][0]) < 1e-7 for i in face)]
    data = bpy.data.meshes.new(name)
    data.from_pydata(verts, [], faces)
    data.update()
    bm = bmesh.new()
    bm.from_mesh(data)
    bmesh.ops.recalc_face_normals(bm, faces=list(bm.faces))
    bm.to_mesh(data)
    bm.free()
    obj = bpy.data.objects.new(name, data)
    bpy.context.collection.objects.link(obj)
    obj.data.materials.append(M[mat])
    obj['proposal_only'] = True
    if mirror:
        mod = obj.modifiers.new('Bilateral_X0', 'MIRROR')
        mod.mirror_object = bpy.data.objects['Symmetry_X0']
        mod.use_clip = True
        mod.use_mirror_merge = True
    return obj


def loft(name, outline, sections, mat):
    cx = sum(p[0] for p in outline)/len(outline)
    cy = sum(p[1] for p in outline)/len(outline)
    verts = [(0 if x == 0 else cx+(x-cx)*scale, cy+(y-cy)*scale, z)
             for z, scale in sections for x, y in outline]
    n = len(outline)
    faces = [tuple(reversed(range(n))), tuple(range((len(sections)-1)*n,len(verts)))]
    faces += [(j*n+i,j*n+(i+1)%n,(j+1)*n+(i+1)%n,(j+1)*n+i)
              for j in range(len(sections)-1) for i in range(n)]
    return mesh(name, verts, faces, mat)


def pod(name, x, y, w, length, z, height, mat):
    bevel=min(.15,height*.25)
    chamfer=min(.2,w*.2,length*.2)
    return loft(name, [(x-w/2+chamfer,y-length/2),(x+w/2-chamfer,y-length/2),
                      (x+w/2,y-length/2+chamfer),(x+w/2,y+length/2-chamfer),
                      (x+w/2-chamfer,y+length/2),(x-w/2+chamfer,y+length/2),
                      (x-w/2,y+length/2-chamfer),(x-w/2,y-length/2+chamfer)],
                [(z,.86),(z+bevel,1),(z+height-bevel,1),(z+height,.88)], mat)


def ring(name, cy, ro, ri, z, h, mat):
    # A half annulus keeps the opening real and its center exactly on X=0.
    verts = []
    count = 33
    for zz, r in [(z,ro),(z,ri),(z+h,ro),(z+h,ri)]:
        for i in range(count):
            a = -math.pi/2+math.pi*i/(count-1)
            verts.append((max(0,r*math.cos(a)),cy+r*math.sin(a),zz))
    faces = []
    for i in range(count-1):
        a,b=i,i+1
        faces += [(a,b,2*count+b,2*count+a),(count+b,count+a,3*count+a,3*count+b),
                  (a,count+a,count+b,b),(2*count+b,3*count+b,3*count+a,2*count+a)]
    return mesh(name,verts,faces,mat)


# Spanwise loft stations: X, leading Y, trailing Y, belly Z, crown Z.
STATIONS = [(5.7,-8.8,12.6,2.9,6.65),(9,-10.2,12.6,2.8,6.55),
            (13,-9.0,12.1,2.95,6.1),(17,-5.9,11.2,3.15,5.65),
            (20.8,-2.8,9.8,3.4,5.1),(21.5,-2.25,9.5,3.48,4.9),
            (21.7,-.8,9.2,3.5,4.7),(24,1.2,7.5,3.7,4.38),
            (25,2.4,6.2,3.82,4.12)]
PANELS = []


def station(x):
    for a,b in zip(STATIONS,STATIONS[1:]):
        if a[0] <= x <= b[0]:
            t=(x-a[0])/(b[0]-a[0])
            return [a[i]+t*(b[i]-a[i]) for i in range(1,5)]
    return list(STATIONS[0 if x<STATIONS[0][0] else -1][1:])


def skinpoint(x,u,lower=False):
    front,rear,bottom,top=station(x)
    # The crown and belly are faceted aerofoils, not horizontal plates.
    chine=(max(0,.15-u)/.15*.55 + max(0,u-.84)/.16*.42)
    return Vector((x,front+(rear-front)*u,bottom+chine*.36 if lower else top-chine))


def slab(name, outline, mat, thickness=.06):
    n=len(outline)
    normal=(outline[1]-outline[0]).cross(outline[2]-outline[0]).normalized()
    verts=[tuple(p-normal*thickness) for p in outline]+[tuple(p) for p in outline]
    faces=[tuple(reversed(range(n))),tuple(range(n,n*2))]
    faces += [(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]
    return mesh(name,verts,faces,mat)


def rod(name, a, b, radius, mat):
    a,b=Vector(a),Vector(b)
    direction=(b-a).normalized()
    u=direction.cross(Vector((0,0,1)))
    if u.length<.01: u=direction.cross(Vector((0,1,0)))
    u.normalize(); v=direction.cross(u).normalized()
    verts=[tuple(p+radius*(u*math.cos(i*math.tau/6)+v*math.sin(i*math.tau/6)))
           for p in [a,b] for i in range(6)]
    return mesh(name,verts,[tuple(reversed(range(6))),tuple(range(6,12))]+
                [(i,(i+1)%6,(i+1)%6+6,i+6) for i in range(6)],mat)


def subtract(objects,cutter):
    for mod in list(cutter.modifiers): cutter.modifiers.remove(mod)
    low=[min(v.co[k] for v in cutter.data.vertices) for k in range(3)]
    high=[max(v.co[k] for v in cutter.data.vertices) for k in range(3)]
    for obj in objects:
        if not obj.data.vertices: continue
        if any(max(v.co[k] for v in obj.data.vertices)<low[k] or
               min(v.co[k] for v in obj.data.vertices)>high[k] for k in range(3)):
            continue
        mod=obj.modifiers.new('Machined_clearance','BOOLEAN')
        mod.operation='DIFFERENCE'; mod.object=cutter
        bpy.context.view_layer.objects.active=obj
        bpy.ops.object.modifier_move_to_index(modifier=mod.name,index=0)
        bpy.ops.object.modifier_apply(modifier=mod.name)
    bpy.data.objects.remove(cutter,do_unlink=True)


def wing():
    verts=[]
    for x,*unused in STATIONS:
        verts.extend(tuple(skinpoint(x,u,False)) for u in [0,.15,.84,1])
        verts.extend(tuple(skinpoint(x,u,True)) for u in [1,.84,.15,0])
    n=8
    faces=[tuple(reversed(range(n))),tuple(range(len(verts)-n,len(verts)))]
    faces += [(j*n+i,j*n+(i+1)%n,(j+1)*n+(i+1)%n,(j+1)*n+i)
              for j in range(len(STATIONS)-1) for i in range(n)]
    hull=mesh('Swept_loadbearing_monocoque',verts,faces,'dark')
    parts=[hull]
    xs=sorted(set([s[0] for s in STATIONS]+[7.2,10.4,11.7,14.4,15.7,18.3,19.6,22.8]))
    us=[0,.075,.15,.30,.46,.64,.84,.93,1]
    for lower in [False,True]:
        for i,(xa,xb) in enumerate(zip(xs,xs[1:])):
            for j,(ua,ub) in enumerate(zip(us,us[1:])):
                dx=min(.032,(xb-xa)*.10); du=.0020
                outline=[skinpoint(x,u,lower) for x,u in
                         [(xa+dx,ua+du),(xb-dx,ua+du),(xb-dx,ub-du),(xa+dx,ub-du)]]
                radiator=not lower and i in [1,4,7,10] and j==5
                offset=(-.035 if lower else .08) if not radiator else .009
                outline=[p+Vector((0,0,offset)) for p in outline]
                mat='dark' if radiator else ('panel' if (i*3+j)%7==0 else 'armor')
                obj=slab(('Ventral' if lower else 'Dorsal')+'_fitted_panel',outline,mat,.055)
                PANELS.append(obj); parts.append(obj)
                if radiator:
                    for k in range(13):
                        u=ua+.016+k*(ub-ua-.032)/12
                        a=skinpoint(xa+.13,u)+Vector((0,0,.065))
                        b=skinpoint(xb-.13,u)+Vector((0,0,.065))
                        rod('Inset_radiator_lamella',a,b,.025,'edge')
                elif not lower and j in [2,4,6] and xb-xa>.6:
                    p=skinpoint(xa+.16,ua+.012)+Vector((0,0,.102))
                    pod('Recessed_panel_lock',p.x,p.y,.13,.22,p.z,.025,'edge')
    # Fore-edge skin follows the sweep, with short windows establishing human scale.
    for xa,xb in zip(xs,xs[1:]):
        a,b=skinpoint(xa+.04,0),skinpoint(xb-.04,0)
        c,d=skinpoint(xb-.04,0,True),skinpoint(xa+.04,0,True)
        parts.append(slab('Leading_edge_armor',[a,b,c,d],'armor',.04))
        if xa<20:
            for t in [.22,.48,.74]:
                x=xa+(xb-xa)*t
                front,rear,bottom,top=station(x)
                pod('Scale_observation_window',x,front-.025,.23,.10,top-.90,.10,'light')
    return parts


def nozzle(x,y,z,width=2.0):
    def loop(yy,w,h):
        return [(x+dx,yy,z+dz) for dx,dz in [(-w/2+.15,-h/2),(w/2-.15,-h/2),
            (w/2,-h/2+.15),(w/2,h/2-.15),(w/2-.15,h/2),(-w/2+.15,h/2),
            (-w/2,h/2-.15),(-w/2,-h/2+.15)]]
    loops=[loop(y-1,width+1.0,1.9),loop(y+.8,width,1.45),
           loop(y+.8,width-.26,1.19),loop(y-.85,width-.35,1.0)]
    verts=[v for row in loops for v in row]
    faces=[(j*8+i,j*8+(i+1)%8,((j+1)%4)*8+(i+1)%8,((j+1)%4)*8+i)
           for j in range(4) for i in range(8)]
    mesh('Aft_vector_nozzle_open_duct',verts,faces,'edge')
    # The luminous throat is recessed, leaving a dark, visibly deep exhaust duct.
    pod('Thruster_recessed_throat',x,y-.72,width-.5,.10,z-.38,.76,'engine')
    for dx in [-.40,0,.40]:
        rod('Exhaust_vector_vane',(x+dx,y-.35,z-.42),(x+dx,y+.62,z+.42),.035,'dark')
    for dx in [-width*.42,width*.42]:
        rod('Nozzle_actuator',(x+dx,y-1,z+.90),(x+dx,y+.65,z+.65),.07,'copper')
    for dx in [-.45,-.15,.15,.45]:
        pod('Aft_maintenance_stencil',x+dx,y+.83,.09,.025,z+.85,.18,'mark')


def build():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.context.preferences.filepaths.save_version = 0
    PANELS.clear()
    obj=bpy.data.objects.new('Symmetry_X0',None)
    bpy.context.collection.objects.link(obj)
    for args in [('armor',(.052,.065,.069)),('panel',(.082,.093,.095)),
                 ('edge',(.15,.18,.18)),('dark',(.008,.013,.016)),
                 ('copper',(.27,.14,.065)),('mark',(.42,.44,.39),.2),
                 ('light',(.16,.57,.57),.25,.65),('engine',(.22,.59,.78),.2,2.5),
                 ('warm',(.8,.32,.06),.25,1),('beam',(.20,.90,.78),.1,4)]:
        material(*args)
    parts=wing()
    ring('Low_profile_open_structural_well',0,6.3,5.5,4.9,1.05,'armor')
    ring('Well_inset_inner_reveal',0,5.62,5.5,5.55,.12,'dark')
    # Radial armor sectors and tiny service lamps break the former smooth donut.
    for i in range(14):
        a=-math.pi/2+i*math.pi/14+.015; b=a+math.pi/14-.03
        outline=[Vector((max(0,r*math.cos(t)),r*math.sin(t),6.02))
                 for r,t in [(5.57,a),(6.25,a),(6.25,b),(5.57,b)]]
        slab('Well_radial_service_panel',outline,'panel',.055)
        r=5.66; t=(a+b)/2
        pod('Well_status_recess',r*math.cos(t),r*math.sin(t),.16,.20,5.72,.07,'warm')
    loft('Forward_swept_V_command_bridge',[(0,-16),(1.1,-15.2),(7.3,-8.3),(6.0,-6.6),(0,-12.8)],
         [(4.65,.95),(5.25,1),(6.03,.97)],'armor')
    for i in range(17):
        x=.35+i*.34; y=-15.25+x*1.03
        pod('Bridge_individual_glazing_recess',x,y,.28,.38,6.04,.035,'dark')
        pod('Bridge_individual_window',x,y,.19,.24,6.079,.016,'light')
    for x in [1.2,2.5,3.8,5.1]:
        y=-13.3+x
        pod('Bridge_service_hatch',x,y,.56,.76,6.05,.035,'panel')
        pod('Bridge_hatch_lock',x,y,.08,.22,6.09,.015,'mark')
    aft=loft('Tapered_aft_command_keel',[(0,6.0),(5.3,5.4),(7,10.5),(4.6,14.1),(2.8,15.0),(0,15.2)],
             [(3.1,.87),(4.1,1),(6.0,1),(6.5,.85)],'armor')
    for x in [.2,2.35]:
        for y in [7.1,8.9,10.7]:
            slab('Keel_flush_access_plate',[Vector((x,y,6.515)),Vector((x+1.95,y+.22,6.515)),
                 Vector((x+1.95,y+1.60,6.515)),Vector((x,y+1.53,6.515))],
                 'panel' if y==8.9 else 'armor',.018)
            pod('Keel_flush_plate_latch',x+.20,y+.25,.075,.15,6.535,.01,'edge')
    loft('Aft_avionics_streamlined_spine',[(0,6.4),(.65,6.9),(1.2,10.2),(.55,13.8),(0,14.1)],
         [(6.0,1),(6.65,.8),(6.95,.35)],'armor')
    for i in range(7):
        pod('Aft_control_window',.45+i*.47,13.85,.25,.12,6.04,.09,'light')
    # Recessed belly/fore launch pockets. No separate projecting box housings.
    for index,x in enumerate([10,15]):
        cutter=pod('TEMP_recessed_bay',x,-11.0,3.85,17.0,1.7,3.05,'dark')
        subtract(parts,cutter)
        pod('Bay_%d_internal_floor'%index,x,-4.8,3.6,4.4,2.15,.20,'dark')
        pod('Bay_%d_aft_service_bulkhead'%index,x,-2.72,3.5,.25,2.3,2.37,'dark')
        for dx in [-1.43,1.43]:
            pod('Bay_recessed_guidance',x+dx,-4.8,.065,3.0,2.36,.018,'warm')
            pod('Bay_docking_rail',x+dx,-4.0,.15,2.6,2.39,.18,'edge')
        for i in range(6):
            pod('Bay_threshold_service_mark',x-1.25+i*.5,-6.68,.25,.14,2.37,.016,'mark')
        pod('Bay_service_console',x+1.25,-2.95,.38,.20,3.0,.48,'panel')
    # Triangulated load paths sit below each lateral span, behind the bay approach.
    for y in [1.7,7.4]:
        nodes=[(x,y,station(x)[2]-.12) for x in [6.0,9,12,15,18,21,23]]
        lower=[(x,y,z-(.90 if x<20 else .52)) for x,y,z in nodes]
        for a,b in zip(nodes,nodes[1:]): rod('Span_upper_chord',a,b,.10,'edge')
        for a,b in zip(lower,lower[1:]): rod('Span_lower_chord',a,b,.11,'armor')
        for i in range(len(nodes)-1):
            rod('Triangulated_span_web',nodes[i],lower[i+1],.065,'edge')
            rod('Truss_vertical_tie',nodes[i],lower[i],.065,'edge')
    for x in [8,11.7,15.5,19]:
        z=station(x)[2]-.80
        pod('Recessed_ventral_service_cassette',x,5.0,1.4,3.4,z,.68,'panel')
        rod('Protected_power_line',(x,3.6,z-.10),(x,6.3,z-.10),.075,'copper')
        for y in [3.8,4.4,5.0,5.6,6.2]:
            pod('Cassette_cooling_fin',x,y,1.15,.07,z-.07,.06,'edge')
    ring('Compact_emitter_gimbal',0,3.8,2.5,.6,1.7,'armor')
    ring('Emitter_conductor',0,2.92,2.55,.12,.52,'copper')
    ring('Emitter_optical_surface',0,2.65,.10,-.1,.24,'light')
    for y in [-2.7,2.7]:
        rod('Emitter_diagonal_hanger',(5.5,y,4.95),(2.9,y,1.7),.20,'armor')
        rod('Emitter_hydraulic_ram',(4.85,y,4.4),(3.05,y,2),.07,'edge')
    for i in range(10):
        t=-math.pi/2+(i+.5)*math.pi/10
        x,y=3.64*math.cos(t),3.64*math.sin(t)
        pod('Emitter_service_segment',x,y,.22,.34,.8,.9,'edge')
    # Real aft-facing ducts, cut through the shell before nozzle assembly.
    for x in [8.2,12.6,17.2]:
        front,rear,bottom,top=station(x)
        z=(bottom+top)/2
        cutter=pod('TEMP_exhaust_channel',x,rear,1.68,4.0,z-.55,1.1,'dark')
        subtract(parts,cutter)
        nozzle(x,rear+.1,z,2.15 if x<15 else 1.85)
    cutter=pod('TEMP_keel_exhaust',2.1,14.1,1.75,4.4,3.98,1.2,'dark')
    subtract([aft],cutter)
    nozzle(2.1,15.15,4.58,2.0)
    for x in [19.3,22.6,24.1]:
        for i in range(3):
            p=skinpoint(x,.3+i*.05)+Vector((0,0,.10))
            pod('Wing_service_stencil',p.x,p.y,.11,.34,p.z,.014,'mark')
    for x in [7.6,11,15,19]:
        p=skinpoint(x,.80)
        rod('Flush_maintenance_handrail',(x,p.y,p.z+.10),(x+.65,p.y,p.z+.1),.025,'edge')
    marker=bpy.data.objects.new('beamOrigin',None)
    bpy.context.collection.objects.link(marker)
    marker.location=(0,0,-.1)
    marker['runtime_local_xyz']=[0,-.1,0]
    marker['runtime_mapping']='Blender (x,y,z) -> game (x,z,-y)'


def points():
    dep=bpy.context.evaluated_depsgraph_get()
    return [obj.matrix_world@v.co for obj in bpy.context.scene.objects if obj.type=='MESH'
            for v in obj.evaluated_get(dep).data.vertices]


def validate():
    ps=points()
    tree=kdtree.KDTree(len(ps))
    for i,p in enumerate(ps): tree.insert(p,i)
    tree.balance()
    error=max(tree.find(Vector((-p.x,p.y,p.z)))[2] for p in ps)
    bounds=[[min(p[k] for p in ps),max(p[k] for p in ps)] for k in range(3)]
    dep=bpy.context.evaluated_depsgraph_get()
    verts,faces=[],[]
    nonmanifold={}
    for obj in bpy.context.scene.objects:
        if obj.type!='MESH': continue
        data=obj.evaluated_get(dep).data
        off=len(verts)
        verts.extend(obj.matrix_world@v.co for v in data.vertices)
        faces.extend(tuple(off+i for i in f.vertices) for f in data.polygons)
        bm=bmesh.new(); bm.from_mesh(data)
        bad=sum(not e.is_manifold for e in bm.edges)
        if bad: nonmanifold[obj.name]=bad
        bm.free()
    bvh=BVHTree.FromPolygons(verts,faces)
    clearance=[]
    for x in [-15,-10,10,15]:
        hits=[]
        for dx in [-1.3,0,1.3]:
            for z in [2.9,3.6,4.3]:
                hit=bvh.ray_cast(Vector((x+dx,-18,z)),Vector((0,1,0)),14.0)
                hits.append(hit[0] is None)
        clearance.append({'centerX':x,'approachSamplesClear':all(hits),'samples':len(hits)})
    exhaust=[]
    for x in [8.2,12.6,17.2,2.1]:
        if x==2.1:
            y,z=15.15,4.58
        else:
            front,rear,bottom,top=station(x)
            y,z=rear+.1,(bottom+top)/2
        for sign in [-1,1]:
            clear=all(bvh.ray_cast(Vector((sign*(x+dx),y+1.5,z)),Vector((0,-1,0)),1.9)[0] is None
                      for dx in [-.65,.65])
            exhaust.append({'centerX':sign*x,'ductSamplesClear':clear,'samples':2})
    report={'proposalOnly':True,'axes':{'front':'-Y','up':'+Z','mirror':'X=0'},
            'bounds':bounds,'dimensions':[b-a for a,b in bounds],
            'symmetryMaxError':error,'evaluatedVertices':len(verts),'polygons':len(faces),
            'nonManifoldEdgesByObject':nonmanifold,'bayApproachChecks':clearance,
            'beamOrigin':[0,0,-.1],'bayClearEnvelope':[3.5,4.0,2.3],
            'revision':'V29_REFINED_SWEEP_02','surfacePanelCount':sum(bool(o.data.polygons) for o in PANELS)*2,
            'rearNozzleCount':8,'maximumWingRootThickness':3.85,
            'exhaustDuctChecks':exhaust,
            'passed':error<1e-5 and bounds[0][1]-bounds[0][0]<=50.001
                     and not nonmanifold and all(c['approachSamplesClear'] for c in clearance)
                     and all(c['ductSamplesClear'] for c in exhaust)}
    (OUT/'validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    return report


def studio():
    scene=bpy.context.scene
    scene.render.engine='CYCLES'
    scene.cycles.samples=24
    scene.cycles.use_denoising=True
    scene.view_settings.exposure=-.55
    scene.world=bpy.data.worlds.new('Neutral_studio')
    scene.world.use_nodes=True
    scene.world.node_tree.nodes['Background'].inputs[0].default_value=(.19,.22,.25,1)
    scene.world.node_tree.nodes['Background'].inputs[1].default_value=.65
    for pos,energy,size in [((5,-35,50),65000,35),((-35,-10,15),45000,28),((5,35,30),80000,25),((0,-15,-30),50000,25)]:
        bpy.ops.object.light_add(type='AREA',location=pos)
        obj=bpy.context.object; obj.data.energy=energy*.48; obj.data.shape='DISK'; obj.data.size=size
        obj.rotation_euler=(Vector((0,0,4))-obj.location).to_track_quat('-Z','Y').to_euler()
    bpy.ops.object.camera_add()
    scene.camera=bpy.context.object
    scene.camera.data.type='ORTHO'; scene.camera.data.clip_end=1000
    scene.render.image_settings.file_format='PNG'
    scene.render.image_settings.color_mode='RGBA'
    scene.render.film_transparent=True


def render(name,axis,target=(0,0,5),span=60,size=(1400,1000)):
    scene=bpy.context.scene; camera=scene.camera
    camera.location=Vector(target)+Vector(axis).normalized()*130
    camera.rotation_euler=(Vector(target)-camera.location).to_track_quat('-Z','Y').to_euler()
    camera.data.ortho_scale=span
    scene.render.resolution_x,scene.render.resolution_y=size
    scene.render.resolution_percentage=100
    scene.render.filepath=str(OUT/(name+'.png'))
    bpy.ops.render.render(write_still=True)


def loaded_points(objects):
    graph=bpy.context.evaluated_depsgraph_get()
    result=[]
    for obj in objects:
        if obj.type!='MESH' or obj.hide_render or obj.name.startswith('FX_'):
            continue
        evaluated=obj.evaluated_get(graph)
        result.extend(evaluated.matrix_world@v.co for v in evaluated.data.vertices)
    return result


def import_role(role, target):
    source=ROOT/'docs/design/detail-scale-v22'/role/'airframe.glb'
    existing=set(bpy.context.scene.objects)
    bpy.ops.import_scene.gltf(filepath=str(source))
    loaded=[obj for obj in bpy.context.scene.objects if obj not in existing]
    bpy.context.view_layer.update()
    ps=loaded_points(loaded)
    bounds=[min(p[k] for p in ps) for k in range(3)]+[max(p[k] for p in ps) for k in range(3)]
    center=Vector(((bounds[0]+bounds[3])/2,(bounds[1]+bounds[4])/2,bounds[2]))
    delta=Vector(target)-center
    roots=[obj for obj in loaded if obj.parent not in loaded]
    for obj in roots:
        obj.matrix_world.translation += delta
    for obj in loaded:
        obj['proposal_witness_only']=True
        obj['source_role']=role
    bpy.context.view_layer.update()
    dimensions=[bounds[3]-bounds[0],bounds[4]-bounds[1],bounds[5]-bounds[2]]
    return {'objects':loaded,'source':source,'bounds':bounds,'dimensions':dimensions,
            'clearance':[BAY_SIZE[i]-dimensions[i] for i in range(3)]}


def remove_loaded(objects):
    for obj in list(objects):
        if obj.name in bpy.data.objects:
            bpy.data.objects.remove(obj,do_unlink=True)


def role_witnesses(q):
    records={}
    # Each role is rendered in the same starboard inner bay at actual 1:1 scale.
    for role in ROLES:
        item=import_role(role,(10,-4.65,BAY_FLOOR_Z))
        records[role]={key:value for key,value in item.items() if key!='objects'}
        records[role]['source']=str(item['source'].relative_to(ROOT))
        records[role]['sourceSha256']=hashlib.sha256(item['source'].read_bytes()).hexdigest()
        records[role]['fits']=all(c>=-.0001 for c in item['clearance'])
        render('bay-fit-'+role,(1.2,-4,-1.05),target=(10,-4.4,3.15),span=8.0,size=(1000,760))
        remove_loaded(item['objects'])
    # Four simultaneously loaded bays prove the slot spacing and whole-ship scale.
    overview=[]
    for role,x in zip(('field','scout','cargo','engineer'),(-15,-10,10,15)):
        overview.append(import_role(role,(x,-4.65,BAY_FLOOR_Z)))
    render('bay-loaded-overview',(2.4,-6,-2.0),target=(0,-2.0,3.5),span=58,size=(1600,1000))
    for item in overview: remove_loaded(item['objects'])
    # The remaining roles share the same two inner bays in a second scale witness.
    reserve=[]
    for role,x in zip(('security','salvage'),(-10,10)):
        reserve.append(import_role(role,(x,-4.65,BAY_FLOOR_Z)))
    render('bay-loaded-security-salvage',(2.1,-5,-1.55),target=(0,-3.2,3.4),span=34,size=(1400,900))
    for item in reserve: remove_loaded(item['objects'])
    report={'proposalOnly':True,'sourcesAreCanonicalV22Glb':True,'pose':'gear-stowed export, frame 40',
            'scale':1.0,'bayClearEnvelope':list(BAY_SIZE),'bayFloorZ':BAY_FLOOR_Z,
            'roles':records,'passed':all(item['fits'] for item in records.values())}
    (OUT/'bay-role-fit.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    q.sheet_start(1800,1660)
    q.sheet_text('ACTUAL V22 3x3 ROLE MESHES / V29 BAY / 1:1 SCALE',30,18,30)
    q.sheet_text('BAY CLEAR 3.50 W x 4.00 D x 2.30 H / GEAR STOWED',30,58,22)
    for i,role in enumerate(ROLES):
        x=20+(i%2)*890; y=105+(i//2)*505
        dims=records[role]['dimensions']; clear=records[role]['clearance']
        q.sheet_text(role.upper()+'  %.3f W x %.3f D x %.3f H'%(dims[0],dims[1],dims[2]),x+8,y,22)
        q.sheet_text('CLEARANCE  %.3f / %.3f / %.3f'%(clear[0],clear[1],clear[2]),x+8,y+30,18)
        q.sheet_image(OUT/('bay-fit-'+role+'.png'),x,y+58,860,420)
    q.sheet_save(OUT/'bay-scale-comparison.png')
    if not report['passed']:
        raise RuntimeError('One or more canonical role meshes do not fit V29 bays')
    return report


def main():
    protected = list((ROOT/'src/main/resources').rglob('*'))
    protected += [ROOT/'docs/design/carrier-v28-intake-proposal'/('carrier-v28-intake-proposal'+ext)
                  for ext in ('.blend','.glb')]
    protected = [p for p in protected if p.is_file()]
    before = {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in protected}
    build()
    report=validate()
    print('GEOMETRY_VALIDATION',json.dumps(report),flush=True)
    studio()
    render('hero',(2.4,-5.5,2.65))
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'carrier-v29-proposal.blend'))
    bpy.ops.object.select_all(action='DESELECT')
    for obj in bpy.context.scene.objects:
        if obj.type in ('MESH','EMPTY'): obj.select_set(True)
    bpy.ops.export_scene.gltf(filepath=str(OUT/'carrier-v29-proposal.glb'),export_format='GLB',
        use_selection=True,export_apply=True,export_animations=False,export_cameras=False,export_lights=False)
    for name,axis in [('front',(0,-1,0)),('rear',(0,1,0)),('top',(0,0,1)),
                      ('bottom',(0,0,-1)),('left',(-1,0,0)),('right',(1,0,0)),
                      ('underside',(3,-5,-3)),('rear-underside',(3,5,-2))]:
        render(name,axis)
    # Beam is an optional presentation state, excluded from the saved carrier and GLB.
    beam=ring('Presentation_vertical_beam',0,2.5,.05,-30,29.9,'beam')
    render('beam-state',(3,-5,-1),target=(0,0,-8),span=68,size=(1400,1200))
    bpy.data.objects.remove(beam,do_unlink=True)
    spec=importlib.util.spec_from_file_location('sheet_helpers',ROOT/'tools/drone-design/blender/build_supply_carrier_v24.py')
    q=importlib.util.module_from_spec(spec); spec.loader.exec_module(q)
    role_witnesses(q)
    q.sheet_start(1800,1600)
    q.sheet_text('MORROWGEAR / CARRIER V29 / PROPOSAL ONLY',35,20,32)
    for i,name in enumerate(['hero','front','rear','top','bottom','left','right','underside','rear-underside']):
        x=20+(i%3)*595; y=90+(i//3)*495
        q.sheet_text(name.upper(),x+10,y,23)
        q.sheet_image(OUT/(name+'.png'),x,y+35,575,440)
    q.sheet_save(OUT/'contact-sheet.png')
    q.sheet_start(1800,1100)
    q.sheet_text('V28 APPROVED / V29 PROPOSAL - NOT TO A COMMON SCALE',30,15,26)
    for i,name in enumerate(['hero','bottom']):
        y=65+i*510
        q.sheet_image(ROOT/'docs/design/carrier-v28-intake-proposal'/(name+'.png'),20,y,850,480)
        q.sheet_image(OUT/(name+'.png'),920,y,850,480)
    q.sheet_save(OUT/'before-after.png')
    q.sheet_start(1800,850)
    q.sheet_text('V29 BLOCKOUT / V29 REFINED SWEEP - PROPOSAL ONLY',30,15,28)
    q.sheet_image(OUT/'previous-blockout-hero.png',20,70,850,740)
    q.sheet_image(OUT/'hero.png',920,70,850,740)
    q.sheet_save(OUT/'refinement-comparison.png')
    files=[p for p in OUT.iterdir() if p.suffix in ('.blend','.glb','.png')]
    (OUT/'artifacts.json').write_text(json.dumps({p.name:{'bytes':p.stat().st_size,
        'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in files},indent=2),encoding='utf-8')
    import numpy as np
    images = {}
    checked_images=(['hero','front','rear','top','bottom','left','right','underside',
                     'rear-underside','beam-state','bay-loaded-overview',
                     'bay-loaded-security-salvage']+
                    ['bay-fit-'+role for role in ROLES])
    for name in checked_images:
        im=bpy.data.images.load(str(OUT/(name+'.png')),check_existing=True)
        pix=np.asarray(im.pixels[:],dtype=np.float32).reshape(im.size[1],im.size[0],4)
        yy,xx=np.where(pix[:,:,3]>.1)
        images[name]={'size':list(im.size),'visiblePixels':len(xx),
            'notClipped':bool(len(xx) and xx.min()>0 and yy.min()>0 and xx.max()<im.size[0]-1 and yy.max()<im.size[1]-1)}
    after={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in protected}
    (OUT/'output-checks.json').write_text(json.dumps({'images':images,
        'protectedFileCount':len(before),'protectedUnchanged':before==after,
        'changedProtectedFiles':[p for p in before if before[p]!=after[p]]},indent=2),encoding='utf-8')
    assert all(v['notClipped'] for v in images.values()), 'Image blank or clipped'
    if not report['passed']: raise RuntimeError('Proposal geometry checks failed; inspect validation.json')


def verify_export():
    bpy.ops.wm.open_mainfile(filepath=str(OUT/'carrier-v29-proposal.blend'))
    PANELS[:]=[o for o in bpy.context.scene.objects if o.type=='MESH' and
               o.name.startswith(('Dorsal_fitted_panel','Ventral_fitted_panel'))]
    report=validate()
    original=points()
    tree=kdtree.KDTree(len(original))
    for i,p in enumerate(original): tree.insert(p,i)
    tree.balance()
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(OUT/'carrier-v29-proposal.glb'))
    imported=points()
    bounds=[[min(p[k] for p in imported),max(p[k] for p in imported)] for k in range(3)]
    delta=max(tree.find(p)[2] for p in imported)
    bound_error=max(abs(bounds[k][j]-report['bounds'][k][j]) for k in range(3) for j in range(2))
    result={'nativeGeometryPassed':report['passed'],'glbImported':True,
            'importedVertices':len(imported),'maxNearestNativeVertexError':delta,
            'boundsMaxError':bound_error,'passed':report['passed'] and delta<1e-4 and bound_error<1e-4}
    (OUT/'glb-readback.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print('GLB_READBACK',json.dumps(result),flush=True)
    assert result['passed']


if __name__=='__main__':
    if '--verify' in sys.argv:
        verify_export()
    else:
        main()
