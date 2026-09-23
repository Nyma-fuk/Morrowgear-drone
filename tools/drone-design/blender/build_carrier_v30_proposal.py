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
OUT = ROOT / 'docs/design/carrier-v30-proposal'
OUT.mkdir(parents=True, exist_ok=True)
M = {}
ROLES = ('field','scout','cargo','engineer','security','salvage')
BAY_SIZE = Vector((3.6,4.4,2.4))
BAY_FLOOR_Z = 2.25


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
STATIONS = [(0,-16,14.0,2.25,6.35),(3.8,-14.7,14.0,2.22,6.55),
            (7,-12.2,13.6,2.30,6.45),(10,-10.4,13.0,2.42,6.15),
            (14,-7.8,12.0,2.62,5.78),(18,-4.5,10.5,2.88,5.25),
            (21.5,-1.3,8.8,3.15,4.75),(23.6,.4,7.3,3.34,4.40),
            (25,2.0,6.2,3.54,4.05)]
PANELS = []
FUNCTIONAL_PANEL_PREFIXES = ('Command_spine_armor','Forward_sensor_cheek',
    'Primary_avionics_armor','Inboard_power_distribution','Midspan_load_armor',
    'Outer_sensor_armor','Wingtip_service_cap','Aft_thermal_armor')


def station(x):
    for a,b in zip(STATIONS,STATIONS[1:]):
        if a[0] <= x <= b[0]:
            t=(x-a[0])/(b[0]-a[0])
            return [a[i]+t*(b[i]-a[i]) for i in range(1,5)]
    return list(STATIONS[0 if x<STATIONS[0][0] else -1][1:])


def skinpoint(x,u,lower=False):
    front,rear,bottom,top=station(x)
    # The crown and belly are faceted aerofoils, not horizontal plates.
    chine=(max(0,.16-u)/.16*.50 + max(0,u-.84)/.16*.38)
    crown=(1-(2*u-1)**2)*(.12 if x<10 else .05)
    return Vector((x,front+(rear-front)*u,
                   bottom+chine*.28-crown*.15 if lower else top-chine+crown))


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
    hull=mesh('Continuous_low_armored_flying_hull',verts,faces,'armor')
    parts=[hull]
    # Leading edge armor follows the F-22-like sweep without making a tiled surface.
    for xa,xb in zip([s[0] for s in STATIONS],[s[0] for s in STATIONS][1:]):
        a,b=skinpoint(xa+.04,0),skinpoint(xb-.04,0)
        c,d=skinpoint(xb-.04,0,True),skinpoint(xa+.04,0,True)
        parts.append(slab('Faceted_leading_edge_armor',[a,b,c,d],'edge',.045))
        if 3<xa<19:
            for t in [.30,.62]:
                x=xa+(xb-xa)*t
                front,rear,bottom,top=station(x)
                pod('Scale_observation_window',x,front-.035,.20,.09,top-.82,.075,'light')
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


def lower_front_intake():
    def rect(y,width,z0,z1):
        return [(-width/2,y,z0),(width/2,y,z0),(width/2,y,z1),(-width/2,y,z1)]
    loops=[rect(-13.20,9.6,1.82,4.02),rect(-4.45,6.5,2.48,4.18),
           rect(-4.72,5.8,2.67,3.98),rect(-12.93,8.75,2.08,3.72)]
    verts=[point for loop in loops for point in loop]
    faces=[]
    for i in range(4):
        j=(i+1)%4
        faces += [(i,j,4+j,4+i),               # outer converging wall
                  (12+i,8+i,8+j,12+j),         # inner airflow wall
                  (i,12+i,12+j,j),             # armored mouth rim
                  (4+i,4+j,8+j,8+i)]           # rear service rim
    duct=mesh('LowerFrontIntake_armored_converging_duct',verts,faces,'edge',mirror=False)
    duct['functional_system']='propulsion_intake'
    for x in [.55,1.45,2.35]:
        vane=rod('LowerFrontIntake_flow_straightener',(x,-5.0,2.78),(x,-5.0,3.86),.055,'edge')
        vane['functional_system']='propulsion_intake'
    for x in [.8,1.8,2.8,3.8]:
        mark=pod('LowerFrontIntake_mouth_service_mark',x,-13.27,.11,.05,3.80,.10,'mark')
        mark['functional_system']='propulsion_intake'
    return duct


def build():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.context.preferences.filepaths.save_version = 0
    PANELS.clear()
    obj=bpy.data.objects.new('Symmetry_X0',None)
    bpy.context.collection.objects.link(obj)
    for args in [('armor',(.040,.052,.056)),('panel',(.074,.087,.089)),
                 ('edge',(.12,.145,.145)),('dark',(.006,.011,.013)),
                 ('copper',(.25,.125,.055)),('mark',(.40,.42,.37),.2),
                 ('light',(.12,.48,.47),.25,.55),('engine',(.16,.48,.70),.2,2.2),
                 ('warm',(.8,.32,.06),.25,1),('beam',(.20,.90,.78),.1,4)]:
        material(*args)
    parts=wing()
    # A continuous low armored back replaces V29's open ring. Panels are sized by function.
    functional_panels=[
        ('Command_spine_armor',[(.12,.12),(3.2,.13),(5.2,.28),(4.2,.72),(.12,.84)],'panel'),
        ('Forward_sensor_cheek',[(1.0,.025),(6.8,.04),(8.8,.20),(6.0,.31)],'dark'),
        ('Primary_avionics_armor',[(5.5,.30),(10.8,.27),(12.5,.54),(7.2,.61)],'panel'),
        ('Inboard_power_distribution',[(2.2,.73),(7.0,.64),(8.0,.91),(2.0,.94)],'armor'),
        ('Midspan_load_armor',[(11.0,.18),(16.6,.16),(18.2,.53),(12.6,.55)],'panel'),
        ('Outer_sensor_armor',[(17.0,.15),(21.2,.19),(23.0,.48),(18.5,.55)],'armor'),
        ('Wingtip_service_cap',[(21.4,.50),(24.7,.28),(24.8,.83),(22.0,.80)],'panel'),
        ('Aft_thermal_armor',[(8.2,.63),(13.8,.60),(14.7,.91),(8.0,.92)],'dark')]
    for name,coords,mat in functional_panels:
        outline=[skinpoint(x,u)+Vector((0,0,.075 if mat!='dark' else .018)) for x,u in coords]
        PANELS.append(slab(name,outline,mat,.045))
    # Inset thermal banks use a few long serviceable cassettes, not a tile grid.
    for xa,xb,ua,ub in [(8.5,13.2,.66,.86),(15.0,19.4,.60,.78)]:
        for k in range(11):
            u=ua+(ub-ua)*(k+.5)/11
            rod('Inset_thermal_bank_lamella',skinpoint(xa,u)+Vector((0,0,.04)),
                skinpoint(xb,u)+Vector((0,0,.04)),.028,'edge')
    # Low faceted command blister grows from the same armored back.
    loft('Integrated_forward_command_blister',[(0,-14.8),(1.0,-14.5),(5.8,-10.2),(6.2,-7.4),(3.8,-5.9),(0,-6.3)],
         [(6.15,.96),(6.55,1),(7.15,.72)],'armor')
    for i in range(13):
        x=.35+i*.39; y=-14.25+x*.70
        pod('Command_window_recess',x,y,.25,.18,6.52,.035,'dark')
        pod('Command_window',x,y,.16,.10,6.56,.018,'light')
    for x,y in [(1.2,-8.0),(2.7,-7.6),(4.2,-8.3)]:
        pod('Command_service_hatch',x,y,1.0,1.4,6.54,.035,'panel')
        pod('Command_hatch_lock',x,y,.08,.45,6.58,.014,'mark')
    # Recessed fore/underside bays remain inside the monocoque silhouette.
    for index,x in enumerate([10,15]):
        cutter=pod('TEMP_recessed_bay',x,-10.3,3.95,15.4,1.55,3.25,'dark')
        subtract(parts,cutter)
        pod('Bay_%d_internal_floor'%index,x,-4.65,3.7,4.8,2.02,.22,'dark')
        pod('Bay_%d_aft_service_bulkhead'%index,x,-2.35,3.65,.28,2.10,2.55,'dark')
        for dx in [-1.68,1.68]:
            pod('Bay_recessed_guidance',x+dx,-4.7,.07,3.2,2.25,.018,'warm')
            pod('Bay_docking_rail',x+dx,-4.0,.16,2.8,2.28,.16,'edge')
        for i in range(6):
            pod('Bay_threshold_service_mark',x-1.28+i*.51,-7.00,.26,.15,2.25,.016,'mark')
        pod('Bay_service_console',x+1.35,-2.55,.34,.18,2.8,.42,'panel')
    # A distinct central propulsion intake coexists with, but does not share, the four bays.
    intake_cutter=loft('TEMP_lower_front_intake_clearance',
        [(0,-17.0),(5.15,-17.0),(5.15,-3.45),(0,-3.45)],[(1.42,1),(4.32,1)],'dark')
    subtract(parts,intake_cutter)
    lower_front_intake()
    # Deep structural trusses communicate the loaded span without thick outer walls.
    for y in [1.0,7.1]:
        nodes=[(x,y,station(x)[2]-.10) for x in [5.2,8,11,14,17,20,22.5]]
        lower=[(x,y,z-(.90 if x<20 else .52)) for x,y,z in nodes]
        for a,b in zip(nodes,nodes[1:]): rod('Span_upper_chord',a,b,.10,'edge')
        for a,b in zip(lower,lower[1:]): rod('Span_lower_chord',a,b,.11,'armor')
        for i in range(len(nodes)-1):
            rod('Triangulated_span_web',nodes[i],lower[i+1],.065,'edge')
            rod('Truss_vertical_tie',nodes[i],lower[i],.065,'edge')
    for x in [6.5,9,12.5,16,19.5]:
        z=station(x)[2]-.80
        pod('Recessed_ventral_service_cassette',x,4.6,1.35,3.2,z,.62,'panel')
        rod('Protected_power_line',(x,3.3,z-.10),(x,5.9,z-.10),.075,'copper')
        for y in [3.5,4.05,4.6,5.15,5.7]:
            pod('Cassette_cooling_fin',x,y,1.15,.07,z-.07,.06,'edge')
    # The giant emitter is entirely below the armored back and recessed into the belly.
    ring('Belly_emitter_armored_recess',-.3,5.0,3.45,.52,1.62,'armor')
    ring('Belly_emitter_conductor',-.3,4.0,3.50,.12,.55,'copper')
    ring('Belly_emitter_optical_surface',-.3,3.55,.08,-.1,.25,'light')
    for y in [-3.3,2.7]:
        rod('Emitter_buried_load_path',(5.5,y,4.7),(3.7,y,1.55),.18,'armor')
        rod('Emitter_hydraulic_ram',(4.9,y,4.25),(3.55,y,1.78),.07,'edge')
    for i in range(12):
        t=-math.pi/2+(i+.5)*math.pi/12
        x,y=4.55*math.cos(t),-.3+4.55*math.sin(t)
        pod('Emitter_service_segment',x,y,.20,.32,.72,.72,'edge')
    # Four large rear propulsion ducts per side, recessed through the trailing body.
    for x in [4.0,9.2,14.7,20.0]:
        front,rear,bottom,top=station(x)
        z=(bottom+top)/2
        width=3.2 if x<16 else 2.65
        cutter=pod('TEMP_exhaust_channel',x,rear,width-.45,4.4,z-.72,1.44,'dark')
        subtract(parts,cutter)
        nozzle(x,rear+.08,z,width)
    # Sparse markings identify service zones without turning the armor into a grid.
    for x,u in [(5.5,.33),(12.5,.42),(19.5,.36),(23.2,.62)]:
        p=skinpoint(x,u)+Vector((0,0,.10))
        for i in range(3):
            pod('Functional_service_stencil',p.x+i*.22,p.y,.10,.34,p.z,.014,'mark')
    for x in [6.8,12.0,17.5,22.0]:
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
        for dx in [-1.45,0,1.45]:
            for z in [2.75,3.55,4.35]:
                hit=bvh.ray_cast(Vector((x+dx,-18,z)),Vector((0,1,0)),14.0)
                hits.append(hit[0] is None)
        clearance.append({'centerX':x,'approachSamplesClear':all(hits),'samples':len(hits)})
    intake_objects=[o for o in bpy.context.scene.objects if o.type=='MESH' and
                    o.name.startswith('LowerFrontIntake_')]
    bay_objects=[o for o in bpy.context.scene.objects if o.type=='MESH' and o.name.startswith('Bay_')]
    intake_approach=[]
    for x in [-3.4,0,3.4]:
        for z in [2.45,3.25]:
            hit=bvh.ray_cast(Vector((x,-18,z)),Vector((0,1,0)),4.6)
            intake_approach.append(hit[0] is None)
    intake_bounds=None
    if intake_objects:
        intake_points=[o.matrix_world@v.co for o in intake_objects for v in o.data.vertices]
        intake_bounds=[[min(p[k] for p in intake_points),max(p[k] for p in intake_points)] for k in range(3)]
    intake_distinct=bool(intake_objects and bay_objects and
        all(o not in bay_objects for o in intake_objects) and intake_bounds and
        intake_bounds[0][0]>-5.1 and intake_bounds[0][1]<5.1)
    exhaust=[]
    for x in [4.0,9.2,14.7,20.0]:
        front,rear,bottom,top=station(x)
        y,z=rear+.08,(bottom+top)/2
        for sign in [-1,1]:
            clear=all(bvh.ray_cast(Vector((sign*(x+dx),y+1.5,z)),Vector((0,-1,0)),1.9)[0] is None
                      for dx in [-.65,.65])
            exhaust.append({'centerX':sign*x,'ductSamplesClear':clear,'samples':2})
    report={'proposalOnly':True,'axes':{'front':'-Y','up':'+Z','mirror':'X=0'},
            'bounds':bounds,'dimensions':[b-a for a,b in bounds],
            'symmetryMaxError':error,'evaluatedVertices':len(verts),'polygons':len(faces),
            'nonManifoldEdgesByObject':nonmanifold,'bayApproachChecks':clearance,
            'beamOrigin':[0,0,-.1],'bayClearEnvelope':list(BAY_SIZE),
            'revision':'V30_CONTINUOUS_ARMORED_SPAN_01',
            'functionalArmorPanelCount':sum(o.type=='MESH' and
                any(o.name.startswith(prefix) for prefix in FUNCTIONAL_PANEL_PREFIXES)
                for o in bpy.context.scene.objects)*2,
            'rearNozzleCount':8,'maximumCenterHullThickness':4.33,
            'topCenterClosed':any(abs(p.x)<1e-6 and p.z>6 for p in ps),
            'lowerFrontIntake':{'exists':bool(intake_objects),'objectCount':len(intake_objects),
                'bounds':intake_bounds,'distinctFromServiceBays':intake_distinct,
                'approachSamplesClear':all(intake_approach),'samples':len(intake_approach),
                'bayCentersX':[-15,-10,10,15]},
            'exhaustDuctChecks':exhaust,
            'passed':error<1e-5 and bounds[0][1]-bounds[0][0]<=50.001
                     and not nonmanifold and all(c['approachSamplesClear'] for c in clearance)
                     and all(c['ductSamplesClear'] for c in exhaust)
                     and any(abs(p.x)<1e-6 and p.z>6 for p in ps)
                     and intake_distinct and all(intake_approach)}
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
    scene.render.film_transparent=False


def render(name,axis,target=(0,0,5),span=60,size=(1400,1000)):
    scene=bpy.context.scene; camera=scene.camera
    camera.location=Vector(target)+Vector(axis).normalized()*130
    camera.rotation_euler=(Vector(target)-camera.location).to_track_quat('-Z','Y').to_euler()
    camera.data.ortho_scale=span
    scene.render.resolution_x,scene.render.resolution_y=size
    scene.render.resolution_percentage=100
    scene.render.filepath=str(OUT/(name+'.png'))
    bpy.ops.render.render(write_still=True)


def japanese_sheet_text(q,body,x,y,size=24,color=(.76,.86,.88,1)):
    before=set(bpy.context.scene.objects)
    q.sheet_text(body,x,y,size,color)
    created=[obj for obj in bpy.context.scene.objects if obj not in before and obj.type=='FONT']
    if created:
        created[0].data.font=bpy.data.fonts.load('C:/Windows/Fonts/meiryo.ttc',check_existing=True)


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
    bpy.ops.object.light_add(type='AREA',location=(0,-24,10))
    witness_light=bpy.context.object
    witness_light.name='REVIEW_ONLY_BAY_FILL'
    witness_light.data.energy=28000
    witness_light.data.shape='RECTANGLE'; witness_light.data.size=24; witness_light.data.size_y=10
    witness_light.rotation_euler=(Vector((0,-4.5,2.8))-witness_light.location).to_track_quat('-Z','Y').to_euler()
    # Each role is rendered in the same starboard inner bay at actual 1:1 scale.
    for role in ROLES:
        item=import_role(role,(10,-4.65,BAY_FLOOR_Z))
        records[role]={key:value for key,value in item.items() if key!='objects'}
        records[role]['source']=str(item['source'].relative_to(ROOT))
        records[role]['sourceSha256']=hashlib.sha256(item['source'].read_bytes()).hexdigest()
        records[role]['fits']=all(c>=-.0001 for c in item['clearance'])
        render('bay-fit-'+role,(.35,-4,.80),target=(10,-4.75,2.90),span=6.4,size=(1000,760))
        remove_loaded(item['objects'])
    # Four simultaneously loaded bays prove the slot spacing and whole-ship scale.
    overview=[]
    for role,x in zip(('field','scout','cargo','engineer'),(-15,-10,10,15)):
        overview.append(import_role(role,(x,-4.65,BAY_FLOOR_Z)))
    render('bay-loaded-overview',(2.3,-6,1.15),target=(0,-3.0,3.3),span=58,size=(1600,1000))
    for item in overview: remove_loaded(item['objects'])
    # The remaining roles share the same two inner bays in a second scale witness.
    reserve=[]
    for role,x in zip(('security','salvage'),(-10,10)):
        reserve.append(import_role(role,(x,-4.65,BAY_FLOOR_Z)))
    render('bay-loaded-security-salvage',(2.0,-5,.90),target=(0,-3.7,3.15),span=34,size=(1400,900))
    for item in reserve: remove_loaded(item['objects'])
    bpy.data.objects.remove(witness_light,do_unlink=True)
    report={'proposalOnly':True,'sourcesAreCanonicalV22Glb':True,'pose':'gear-stowed export, frame 40',
            'scale':1.0,'bayClearEnvelope':list(BAY_SIZE),'bayFloorZ':BAY_FLOOR_Z,
            'roles':records,'passed':all(item['fits'] for item in records.values())}
    (OUT/'bay-role-fit.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    q.sheet_start(1800,1660)
    q.sheet_text('ACTUAL V22 3x3 ROLE MESHES / V30 BAY / 1:1 SCALE',30,18,30)
    q.sheet_text('BAY CLEAR 3.60 W x 4.40 D x 2.40 H / GEAR STOWED',30,58,22)
    for i,role in enumerate(ROLES):
        x=20+(i%2)*890; y=105+(i//2)*505
        dims=records[role]['dimensions']; clear=records[role]['clearance']
        q.sheet_text(role.upper()+'  %.3f W x %.3f D x %.3f H'%(dims[0],dims[1],dims[2]),x+8,y,22)
        q.sheet_text('CLEARANCE  %.3f / %.3f / %.3f'%(clear[0],clear[1],clear[2]),x+8,y+30,18)
        q.sheet_image(OUT/('bay-fit-'+role+'.png'),x,y+58,860,420)
    q.sheet_save(OUT/'bay-scale-comparison.png')
    if not report['passed']:
        raise RuntimeError('One or more canonical role meshes do not fit V30 bays')
    return report


def main():
    protected = list((ROOT/'src/main/resources').rglob('*'))
    protected += [ROOT/'docs/design/carrier-v28-intake-proposal'/('carrier-v28-intake-proposal'+ext)
                  for ext in ('.blend','.glb')]
    protected += [ROOT/'docs/design/carrier-v29-proposal'/('carrier-v29-proposal'+ext)
                  for ext in ('.blend','.glb')]
    protected = [p for p in protected if p.is_file()]
    before = {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in protected}
    build()
    report=validate()
    print('GEOMETRY_VALIDATION',json.dumps(report),flush=True)
    studio()
    render('hero',(2.4,-5.5,2.65))
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'carrier-v30-proposal.blend'))
    bpy.ops.object.select_all(action='DESELECT')
    for obj in bpy.context.scene.objects:
        if obj.type in ('MESH','EMPTY'): obj.select_set(True)
    bpy.ops.export_scene.gltf(filepath=str(OUT/'carrier-v30-proposal.glb'),export_format='GLB',
        use_selection=True,export_apply=True,export_animations=False,export_cameras=False,export_lights=False)
    for name,axis in [('front',(0,-1,0)),('rear',(0,1,0)),('top',(0,0,1)),
                      ('bottom',(0,0,-1)),('left',(-1,0,0)),('right',(1,0,0)),
                      ('underside',(3,-5,-3)),('rear-underside',(3,5,-2))]:
        render(name,axis)
    # Beam is an optional presentation state, excluded from the saved carrier and GLB.
    beam=ring('Presentation_vertical_beam',0,2.5,.05,-30,29.9,'beam')
    render('beam-state',(3,-5,-1),target=(0,0,-8),span=68,size=(1400,1200))
    bpy.data.objects.remove(beam,do_unlink=True)
    render('front-under-functional-closeup',(1.4,-6,-1.45),target=(0,-7.2,2.65),span=36,size=(1500,1000))
    render('intake-functional-closeup',(0,-6,-1.05),target=(0,-8.2,2.85),span=16,size=(1200,900))
    render('bay-functional-closeup',(1.8,-6,-.70),target=(12.5,-5.0,2.95),span=14,size=(1200,900))
    render('emitter-functional-closeup',(2,-3,-5),target=(0,-.3,1.25),span=15,size=(1200,900))
    spec=importlib.util.spec_from_file_location('sheet_helpers',ROOT/'tools/drone-design/blender/build_supply_carrier_v24.py')
    q=importlib.util.module_from_spec(spec); spec.loader.exec_module(q)
    role_witnesses(q)
    q.sheet_start(1800,1600)
    q.sheet_text('MORROWGEAR / CARRIER V30 / PROPOSAL ONLY',35,20,32)
    for i,name in enumerate(['hero','front','rear','top','bottom','left','right','underside','rear-underside']):
        x=20+(i%3)*595; y=90+(i//3)*495
        q.sheet_text(name.upper(),x+10,y,23)
        q.sheet_image(OUT/(name+'.png'),x,y+35,575,440)
    q.sheet_save(OUT/'contact-sheet.png')
    q.sheet_start(1800,1100)
    q.sheet_text('V29 REJECTED / V30 NEW PROPOSAL - NOT TO A COMMON SCALE',30,15,26)
    for i,name in enumerate(['hero','bottom']):
        y=65+i*510
        q.sheet_image(ROOT/'docs/design/carrier-v29-proposal'/(name+'.png'),20,y,850,480)
        q.sheet_image(OUT/(name+'.png'),920,y,850,480)
    q.sheet_save(OUT/'before-after.png')
    q.sheet_start(1800,850)
    q.sheet_text('V29 REJECTED / V30 CONTINUOUS ARMORED SPAN',30,15,28)
    q.sheet_image(ROOT/'docs/design/carrier-v29-proposal/hero.png',20,70,850,740)
    q.sheet_image(OUT/'hero.png',920,70,850,740)
    q.sheet_save(OUT/'refinement-comparison.png')
    q.sheet_start(1800,1120)
    japanese_sheet_text(q,'V30 機能分離確認 / 提案のみ・ランタイム未反映',30,18,30)
    japanese_sheet_text(q,'中央前下面の大型インテークと、左右4室の整備ベイは独立した開口です。',30,58,22)
    panels=[('大型前方下面インテーク','推進系 / X=0 / 幅9.6m','intake-functional-closeup'),
            ('小型機整備ベイ','格納・整備系 / X=±10, ±15 / 4室','bay-functional-closeup'),
            ('巨大照射口','照射系 / 下面中央 / インテークではない','emitter-functional-closeup')]
    for i,(title,subtitle,name) in enumerate(panels):
        x=20+i*595
        japanese_sheet_text(q,title,x+8,112,25)
        japanese_sheet_text(q,subtitle,x+8,146,18)
        q.sheet_image(OUT/(name+'.png'),x,180,575,760)
    japanese_sheet_text(q,'識別: インテーク=収束ダクトと整流ベーン / ベイ=ドッキング床と誘導灯 / 照射口=円形光学面',30,1010,20)
    q.sheet_save(OUT/'functional-comparison-ja.png')
    files=[p for p in OUT.iterdir() if p.suffix in ('.blend','.glb','.png')]
    (OUT/'artifacts.json').write_text(json.dumps({p.name:{'bytes':p.stat().st_size,
        'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in files},indent=2),encoding='utf-8')
    import numpy as np
    images = {}
    checked_images=(['hero','front','rear','top','bottom','left','right','underside',
                     'rear-underside','beam-state','bay-loaded-overview',
                     'bay-loaded-security-salvage','front-under-functional-closeup',
                     'intake-functional-closeup','bay-functional-closeup',
                     'emitter-functional-closeup']+
                    ['bay-fit-'+role for role in ROLES])
    for name in checked_images:
        im=bpy.data.images.load(str(OUT/(name+'.png')),check_existing=True)
        pix=np.asarray(im.pixels[:],dtype=np.float32).reshape(im.size[1],im.size[0],4)
        border=np.concatenate((pix[:,:12,:3],pix[:,-12:,:3]),axis=1)
        background=np.median(border,axis=1)[:,None,:]
        yy,xx=np.where(np.max(np.abs(pix[:,:,:3]-background),axis=2)>.018)
        images[name]={'size':list(im.size),'visiblePixels':len(xx),
            'notClipped':bool(len(xx) and xx.min()>0 and yy.min()>0 and xx.max()<im.size[0]-1 and yy.max()<im.size[1]-1)}
    after={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in protected}
    (OUT/'output-checks.json').write_text(json.dumps({'images':images,
        'protectedFileCount':len(before),'protectedUnchanged':before==after,
        'changedProtectedFiles':[p for p in before if before[p]!=after[p]]},indent=2),encoding='utf-8')
    strict={'hero','front','rear','top','bottom','left','right','underside','rear-underside'}
    assert all(v['visiblePixels']>1000 for v in images.values()), 'Image blank'
    assert all(images[name]['notClipped'] for name in strict), 'Full-view image clipped'
    if not report['passed']: raise RuntimeError('Proposal geometry checks failed; inspect validation.json')


def verify_export():
    bpy.ops.wm.open_mainfile(filepath=str(OUT/'carrier-v30-proposal.blend'))
    PANELS[:]=[o for o in bpy.context.scene.objects if o.type=='MESH' and
               any(o.name.startswith(prefix) for prefix in FUNCTIONAL_PANEL_PREFIXES)]
    report=validate()
    original=points()
    tree=kdtree.KDTree(len(original))
    for i,p in enumerate(original): tree.insert(p,i)
    tree.balance()
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(OUT/'carrier-v30-proposal.glb'))
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


def check_saved_images():
    import numpy as np
    names=['hero','front','rear','top','bottom','left','right','underside','rear-underside',
           'beam-state','bay-loaded-overview','bay-loaded-security-salvage',
           'front-under-functional-closeup','intake-functional-closeup',
           'bay-functional-closeup','emitter-functional-closeup']+['bay-fit-'+r for r in ROLES]
    images={}
    for name in names:
        im=bpy.data.images.load(str(OUT/(name+'.png')),check_existing=False)
        pix=np.asarray(im.pixels[:],dtype=np.float32).reshape(im.size[1],im.size[0],4)
        border=np.concatenate((pix[:,:12,:3],pix[:,-12:,:3]),axis=1)
        background=np.median(border,axis=1)[:,None,:]
        yy,xx=np.where(np.max(np.abs(pix[:,:,:3]-background),axis=2)>.018)
        images[name]={'size':list(im.size),'visiblePixels':len(xx),
            'notClipped':bool(len(xx) and xx.min()>0 and yy.min()>0 and
                              xx.max()<im.size[0]-1 and yy.max()<im.size[1]-1)}
        bpy.data.images.remove(im)
    previous=json.loads((OUT/'output-checks.json').read_text(encoding='utf-8'))
    result={'images':images,'protectedFileCount':previous['protectedFileCount'],
            'protectedUnchanged':previous['protectedUnchanged'],
            'changedProtectedFiles':previous['changedProtectedFiles'],
            'backgroundDetection':'RGB distance from per-row median border background'}
    strict={'hero','front','rear','top','bottom','left','right','underside','rear-underside'}
    result['passed']=all(v['visiblePixels']>1000 for v in images.values()) and \
                     all(images[name]['notClipped'] for name in strict) and result['protectedUnchanged']
    (OUT/'output-checks.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print('IMAGE_CHECKS',json.dumps(result),flush=True)
    assert result['passed']


if __name__=='__main__':
    if '--check-images' in sys.argv:
        check_saved_images()
    elif '--verify' in sys.argv:
        verify_export()
    else:
        main()
