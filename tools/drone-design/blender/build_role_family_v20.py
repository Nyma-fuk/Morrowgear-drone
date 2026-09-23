"""V19-derived role equipment and retractable four-point landing gear. Staging only."""
import argparse
import hashlib
import importlib.util
import json
import math
import sys
from pathlib import Path

import bpy
import bmesh
from mathutils import Vector, Matrix, kdtree
from mathutils.bvhtree import BVHTree

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
BASE=ROOT/'docs/design/reference-low-profile-v19'
OUT=ROOT/'docs/design/role-family-v20'
ROLES=('field','scout','cargo','engineer','security','salvage')
SIZES={'field':(.92,.84),'scout':(1.,1.22),'cargo':(1.06,.96),
       'engineer':(1.,.90),'security':(1.,1.),'salvage':(1.12,1.08)}
M={};MIRROR=None;HULL=None;SURFACE=None;ROLE='';ROOT_OBJ=None
ADDED=[];GEAR_FEET=[];GEAR_PARTS=[];ROLE_MOVERS=[];HINGES=[]


def load_module(name,file):
    spec=importlib.util.spec_from_file_location(name,HERE/file)
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value)
    return value


v19=load_module('low_profile_v19','build_low_profile_v19.py')


def mat(name,color,metal=.5,rough=.36,emission=None):
    value=bpy.data.materials.new('V20_'+name);value.use_nodes=True
    bs=value.node_tree.nodes.get('Principled BSDF')
    bs.inputs['Base Color'].default_value=(*color,1)
    bs.inputs['Metallic'].default_value=metal;bs.inputs['Roughness'].default_value=rough
    if emission:
        bs.inputs['Emission Color'].default_value=(*emission,1);bs.inputs['Emission Strength'].default_value=2.2
    return value


def materials():
    M.clear()
    M.update(armor=mat('EquipmentGraphite',(.047,.054,.060),.65,.43),
             edge=mat('StructuralTitanium',(.17,.21,.23),.8,.30),
             dark=mat('Recess',(.008,.012,.015),.30,.40),
             rubber=mat('ContactSole',(.012,.016,.018),.05,.75),
             glass=mat('SmokedOptic',(.004,.008,.012),.42,.105),
             amber=mat('SafetyAmber',(.7,.30,.025),.65,.36),
             cyan=mat('DataCyan',(.018,.32,.43),.25,.27,(.03,.68,.8)),
             hot=mat('EnergyCore',(.55,.12,.035),.20,.2,(1.,.20,.045)))


def record(obj,pair=False):
    ADDED.append(obj)
    if pair:
        mod=obj.modifiers.new('Bilateral_X0','MIRROR');mod.mirror_object=MIRROR
        mod.use_axis[0]=True;mod.use_axis[1]=False;mod.use_axis[2]=False
    return obj


def mesh(name,verts,faces,material='armor',pair=False,smooth=False):
    data=bpy.data.meshes.new(name);data.from_pydata(verts,[],faces);data.update()
    obj=bpy.data.objects.new(name,data);bpy.context.collection.objects.link(obj)
    data.materials.append(M[material]);bm=bmesh.new();bm.from_mesh(data)
    bmesh.ops.recalc_face_normals(bm,faces=list(bm.faces));bm.to_mesh(data);bm.free()
    for face in data.polygons:face.use_smooth=smooth
    return record(obj,pair)


def box(name,loc,size,material='armor',pair=False,bevel=.025):
    bpy.ops.mesh.primitive_cube_add(size=1,location=loc);obj=bpy.context.object;obj.name=name
    obj.scale=size;bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
    obj.data.materials.append(M[material])
    if bevel:
        mod=obj.modifiers.new('ManufacturedEdge','BEVEL');mod.width=bevel;mod.segments=3
        mod=obj.modifiers.new('WeightedNormals','WEIGHTED_NORMAL');mod.keep_sharp=True
    return record(obj,pair)


def cylinder(name,loc,radius,depth,material='edge',pair=False,axis=(0,0,1)):
    bpy.ops.mesh.primitive_cylinder_add(vertices=40,radius=radius,depth=depth,location=loc)
    obj=bpy.context.object;obj.name=name;obj.rotation_mode='QUATERNION'
    obj.rotation_quaternion=Vector(axis).to_track_quat('Z','Y');obj.data.materials.append(M[material])
    for p in obj.data.polygons:p.use_smooth=len(p.vertices)==4
    bevel=obj.modifiers.new('RimBevel','BEVEL');bevel.width=.006;bevel.segments=2
    return record(obj,pair)


def sleeve(name,loc,outer,inner,depth,material='edge',axis=(0,0,1)):
    verts=[];faces=[];n=40
    for z,r in ((-depth/2,outer),(depth/2,outer),(-depth/2,inner),(depth/2,inner)):
        verts.extend((r*math.cos(i*math.tau/n),r*math.sin(i*math.tau/n),z) for i in range(n))
    for i in range(n):
        j=(i+1)%n
        faces.extend(((i,j,n+j,n+i),(2*n+i,3*n+i,3*n+j,2*n+j),
                      (i,2*n+i,2*n+j,j),(n+i,n+j,3*n+j,3*n+i)))
    obj=mesh(name,verts,faces,material,True,True);obj.location=loc
    for i,face in enumerate(obj.data.polygons):face.use_smooth=i%4<2
    obj.rotation_mode='QUATERNION';obj.rotation_quaternion=Vector(axis).to_track_quat('Z','Y')
    return obj


def line(name,points,material='dark',radius=.006,pair=False,closed=False):
    data=bpy.data.curves.new(name,'CURVE');data.dimensions='3D';data.resolution_u=1
    data.bevel_depth=radius;data.bevel_resolution=2
    spl=data.splines.new('POLY');spl.points.add(len(points)-1)
    for p,co in zip(spl.points,points):p.co=(*co,1)
    spl.use_cyclic_u=closed;data.materials.append(M[material])
    obj=bpy.data.objects.new(name,data);bpy.context.collection.objects.link(obj)
    return record(obj,pair)


def empty(name,loc=(0,0,0)):
    obj=bpy.data.objects.new(name,None);bpy.context.collection.objects.link(obj);obj.location=loc
    return obj


def skin(x,y,top=True):
    hit,_,_,_=SURFACE.ray_cast(Vector((x,y,5 if top else -5)),Vector((0,0,-1 if top else 1)))
    if hit is None:raise ValueError(('Surface point outside airframe',x,y,top))
    return hit.z


def panel(name,polygon,material='armor',height=.012,pair=True):
    # Triangles share the actual underlying body height, not a floating flat plaque.
    center=Vector((sum(x for x,y in polygon)/len(polygon),sum(y for x,y in polygon)/len(polygon)))
    verts=[(center.x,center.y,skin(center.x,center.y)+height)];rim=[]
    for a,b in zip(polygon,polygon[1:]+polygon[:1]):
        steps=max(1,math.ceil(math.dist(a,b)/.055))
        for i in range(steps):
            x=a[0]+(b[0]-a[0])*i/steps;y=a[1]+(b[1]-a[1])*i/steps
            rim.append(Vector((x,y)))
    rings=max(2,math.ceil(max((p-center).length for p in rim)/.05));n=len(rim)
    for j in range(1,rings+1):
        for p in rim:
            q=center.lerp(p,j/rings);verts.append((q.x,q.y,skin(q.x,q.y)+height))
    faces=[(0,i+1,(i+1)%n+1) for i in range(n)]
    for j in range(rings-1):
        for i in range(n):
            a=1+j*n+i;b=1+j*n+(i+1)%n;faces.append((a,b,b+n,a+n))
    return mesh(name,verts,faces,material,pair,True)


def cut_hull(name,loc,size):
    cutter=box(name,loc,size,'dark',True,0)
    bpy.context.view_layer.objects.active=HULL
    mod=HULL.modifiers.new(name,'BOOLEAN');mod.operation='DIFFERENCE';mod.solver='EXACT';mod.object=cutter
    bpy.ops.object.modifier_apply(modifier=mod.name)
    ADDED.remove(cutter);bpy.data.objects.remove(cutter,do_unlink=True)


def keyed_location(obj,frame,position):
    obj.location=position;obj.keyframe_insert(data_path='location',frame=frame)


def hinge(obj,loc,axis,opened):
    pivot=empty(obj.name+'_Pivot',loc);bpy.context.view_layer.update()
    world=obj.matrix_world.copy();obj.parent=pivot;obj.matrix_world=world
    for frame,amount in ((1,0),(40,0),(60,opened),(100,opened),(120,0)):
        pivot.rotation_euler[axis]=amount;pivot.keyframe_insert(data_path='rotation_euler',frame=frame)
    HINGES.append((obj,world.copy(),pivot,axis,opened))
    ROLE_MOVERS.append(pivot);return pivot


def rear_details():
    polygon=[(.93,1.60),(1.25,1.35),(1.83,1.53),(1.62,2.03),(1.04,2.25)]
    panel('AFT_ServiceCassette',polygon)
    line('AFT_CassetteGasket',[(x,y,skin(x,y)+.019) for x,y in polygon],radius=.009,pair=True,closed=True)
    for i in range(6):
        y=1.50+i*.087;x=1.26-i*.020
        slot=[(x,y),(x+.29,y+.11),(x+.27,y+.15),(x-.02,y+.04)]
        panel('AFT_HeatExchangerThroat_%02d'%i,slot,'dark',.018)
        line('AFT_LouverEdge_%02d'%i,[(x,y,skin(x,y)+.033),(x+.29,y+.11,skin(x+.29,y+.11)+.033)],'edge',.008,True)
    for x,y in ((1.03,1.64),(1.54,1.57),(1.13,2.12)):
        cylinder('AFT_CaptiveFastener',(x,y,skin(x,y)+.025),.027,.015,'edge',True)
        line('AFT_FastenerSlot',[(x-.012,y,skin(x,y)+.034),(x+.012,y,skin(x,y)+.034)],radius=.003,pair=True)
    cylinder('AFT_ServiceCoupler',(1.53,2.02,skin(1.53,2.02)+.036),.075,.04,'dark',True)
    cylinder('AFT_CouplerCap',(1.53,2.02,skin(1.53,2.02)+.059),.047,.014,'edge',True)
    panel('AFT_ReleaseLatch',[(1.09,2.10),(1.20,2.05),(1.26,2.11),(1.14,2.17)],'amber',.031)
    for y in (2.15,2.23):
        line('AFT_StatusLens',[(.94,y,skin(.94,y)+.025),(1.02,y-.015,skin(1.02,y-.015)+.025)],'cyan',.007,True)


def landing_gear():
    heavy=ROLE in ('cargo','salvage');drop=.16 if heavy else 0
    for fore in (-1,1):
        y=fore*1.65;x=.70
        # Four blind cavities stop short of the upper skin and sit inboard of fan flow.
        top=min(.17,skin(x,y)-.045)
        cut_hull('TMP_GearCavity',(x,y,(-.45+top)/2),(.46,.66,top+.45))
        box('GEAR_BayLiner',(x,y,top-.015),(.41,.60,.025),'dark',True,.015)
        box('GEAR_Hardpoint',(x,y,top-.032),(.28,.37,.08),'edge',True,.022)
        axis=Vector((.16,0,-1)).normalized()
        home=Vector((x,y,.125))
        barrel_length=.34+drop
        sleeve('GEAR_FixedSocket_%s'%fore,home+axis*(barrel_length/2-.04),
               .143,.122,barrel_length,'armor',axis)
        parts=[]
        for i,(radius,length) in enumerate(((.11,.38),(.083,.38),(.058,.38))):
            name='GEAR_TelescopicStage_%s_%d'%(fore,i)
            obj=(sleeve(name,(0,0,0),radius,(.092,.067)[i],length,'edge',axis) if i<2 else
                 cylinder(name,(0,0,0),radius,length,'edge',True,axis))
            parts.append(obj);GEAR_PARTS.append(obj)
            for frame,extended in ((1,1),(20,1),(40,0),(100,0),(120,1)):
                base=home+axis*((.18+drop) if extended else 0)
                offset=(.026*i if not extended else .32*i)+length/2
                keyed_location(obj,frame,base+axis*offset)
        foot=box('GEAR_ContactPad_%s'%fore,(0,0,0),(.34 if heavy else .30,.56 if heavy else .48,.11),'rubber',True,.035)
        cap=box('GEAR_FootArmour_%s'%fore,(0,0,0),(.27 if heavy else .24,.44 if heavy else .38,.06),'edge',True,.023)
        GEAR_FEET.append(foot);GEAR_PARTS.extend((foot,cap))
        for frame,extended in ((1,1),(20,1),(40,0),(100,0),(120,1)):
            base=home+axis*((.18+drop) if extended else 0)
            endpoint=base+axis*(.46 if not extended else 1.02)
            keyed_location(foot,frame,endpoint);keyed_location(cap,frame,endpoint+Vector((0,0,.065)))
        for j in (-1,1):
            grip=box('GEAR_SoleTread_%s_%d'%(fore,j),(0,0,0),(.28,.045,.018),'dark',True,.006)
            GEAR_PARTS.append(grip)
            for frame,extended in ((1,1),(20,1),(40,0),(100,0),(120,1)):
                base=home+axis*((.18+drop) if extended else 0);end=base+axis*(.46 if not extended else 1.02)
                keyed_location(grip,frame,end+Vector((0,j*.15,-.054)))


def underside_plate(name,x,y,width,length,depth=.09,material='armor'):
    top=skin(x,y,False)+.015
    return box(name,(x,y,top-depth/2),(width,length,depth),material,x>0,.025)


def field():
    underside_plate('FIELD_ServiceKeel',0,-.15,.82,1.12,.11)
    for x in (.15,.29):box('FIELD_ServiceContact',(x,-.15,-.38),(.04,.66,.018),'amber',True,.008)
    box('FIELD_DataPort',(0,.32,-.38),(.34,.20,.035),'dark',False,.012)
    for i in range(3):box('FIELD_PortPin',(0,.28+i*.05,-.402),(.22,.013,.012),'edge',False,.003)
    panel('FIELD_LinkRadome',[(.20,-.12),(.42,-.04),(.43,.48),(.22,.64)],'glass',.012)


def scout():
    # Retain two primary optics. The perimeter track carries the sensor identity.
    for x in (.20,):
        cylinder('SCOUT_OpticBezel',(x,-2.61,-.15),.073,.052,'edge',True,(0,1,0))
        cylinder('SCOUT_OpticGlass',(x,-2.642,-.15),.056,.018,'glass',True,(0,1,0))
    raw=v19.v18.v16.sections.BOUNDARY;boundary=[]
    for a,b in zip(raw,raw[1:]):
        a,b=Vector(a),Vector(b);steps=max(1,math.ceil((b-a).length/.045))
        boundary.extend(a.lerp(b,i/steps) for i in range(steps))
    boundary.append(Vector(raw[-1]))
    points=[]
    for point in boundary:
        p=Vector(point)
        if p.y< -1.60:
            f=v19.v18.smooth((-1.60-p.y)/(2.66-1.60))*max(0,1-abs(p.x)/1.30)
            p.x*=1-.16*f;p.y-=.28*f
        p=v19.shape(v19.v18.shape(v19.v18.v17.shape(v19.v18.v16.aft_shape(p))))
        inside=(p.x*.995,p.y*.995)
        try:
            top=skin(*inside);p.z=(top+skin(*inside,False))/2
            aft=v19.v18.smooth((p.y-2.60)/.40)*(1-v19.v18.smooth((abs(p.x)-.82)/.24))
            p.z=p.z*(1-aft)+max(top+.025,.168)*aft
        except ValueError:p.z-=.025
        p.x*=1.002;p.y*=1.002
        if abs(p.x)>3.02:p.x=math.copysign(3.02+(abs(p.x)-3.02)*SIZES[ROLE][1],p.x)
        points.append(p)
    outline=points+[Vector((-p.x,p.y,p.z)) for p in reversed(points[1:-1])]
    line('SCOUT_PerimeterRecess',outline,'dark',.015,False,True)
    rim=outline
    outline=[p+Vector((rim[(i+1)%len(rim)].y-rim[i-1].y,
                      rim[i-1].x-rim[(i+1)%len(rim)].x,0)).normalized()*.017
             for i,p in enumerate(rim)]
    line('SCOUT_ScanTrack',outline,'cyan',.005,False,True)
    dot=cylinder('FX_SCOUT_ScanPoint',(0,0,0),.035,.024,'cyan')
    distances=[0.]
    closed=outline+[outline[0]]
    for a,b in zip(closed,closed[1:]):distances.append(distances[-1]+(b-a).length)
    for p,d in zip(closed,distances):keyed_location(dot,1+119*d/distances[-1],p)
    for layer in dot.animation_data.action.layers:
        for strip in layer.strips:
            for bag in strip.channelbags:
                for curve in bag.fcurves:
                    for key in curve.keyframe_points:key.interpolation='LINEAR'
    dot['structural_symmetry_exempt']='Moving scan phase, not a structural component'
    panel('SCOUT_DorsalReceiver',[(.19,-.15),(.41,-.03),(.42,.72),(.22,.93)],'glass',.015)


def cargo():
    # An actual open load well, with walls above two side-hinged floor doors.
    for x in (.58,):box('CARGO_BaySide',(x,.02,-.54),(.10,2.10,.50),'armor',True,.055)
    for y in (-1.03,1.07):box('CARGO_BayEnd',(0,y,-.51),(1.23,.11,.48),'edge',False,.03)
    box('CARGO_BayRoof',(0,.02,-.29),(1.13,2.08,.09),'dark',False,.025)
    for x in (.39,):box('CARGO_LoadRail',(x,.02,-.76),(.07,1.96,.07),'edge',True,.012)
    door=box('CARGO_FloorDoor',(.285,.02,-.815),(.57,2.04,.065),'armor',True,.022)
    hinge(door,(.59,.02,-.80),1,-1.35)
    for y in (-.64,.02,.68):
        box('CARGO_FreightContainer',(0,y,-.56),(.94,.54,.35),'edge',False,.038)
        box('CARGO_FreightLatch',(.30,y-.25,-.57),(.08,.02,.15),'amber',True,.008)
    for y in (-.68,0,.68):box('CARGO_OuterRib',(.65,y,-.51),(.07,.08,.38),'edge',True,.012)


def arm_elbow(root,target,l1=.61,l2=.59):
    d=target-root;distance=d.length;axis=d.normalized();pole=Vector((0,1,0))
    orth=(pole-axis*pole.dot(axis)).normalized()
    a=(l1*l1-l2*l2+distance*distance)/(2*distance)
    return root+axis*a+orth*math.sqrt(max(0,l1*l1-a*a))


def pose_beam(obj,a,b,frame,width):
    obj.location=(a+b)/2;obj.rotation_mode='QUATERNION';obj.rotation_quaternion=(b-a).to_track_quat('Z','Y')
    obj.scale=(width,width,(b-a).length)
    obj.keyframe_insert(data_path='location',frame=frame);obj.keyframe_insert(data_path='rotation_quaternion',frame=frame)
    obj.keyframe_insert(data_path='scale',frame=frame)


def engineer():
    underside_plate('ENG_LoadBearingKeel',0,-.38,1.24,.42,.12,'edge')
    upper=box('ENG_UpperArm',(0,0,0),(1,1,1),'edge',True,0)
    lower=box('ENG_Forearm',(0,0,0),(1,1,1),'armor',True,0)
    joints=[cylinder('ENG_Joint_'+str(i),(0,0,0),.105,.11,'amber',True,(1,0,0)) for i in range(3)]
    claw=box('ENG_GripperBody',(0,0,0),(.23,.30,.12),'edge',True,.025)
    fingers=[box('ENG_GripperFinger_'+str(i),(0,0,0),(.045,.26,.10),'dark',True,.012) for i in (-1,1)]
    root=Vector((.55,-.62,-.26));idle=Vector((.62,-.70,-.43));active=Vector((1.02,-1.35,-.95))
    for frame in (1,20,40,50,60,70,80,90,100,110,120):
        amount=v19.v18.smooth((frame-40)/40) if frame<=100 else 1-v19.v18.smooth((frame-100)/20)
        wrist=idle.lerp(active,amount);elbow=arm_elbow(root,wrist)
        pose_beam(upper,root,elbow,frame,.135);pose_beam(lower,elbow,wrist,frame,.11)
        for obj,p in zip(joints,(root,elbow,wrist)):keyed_location(obj,frame,p)
        keyed_location(claw,frame,wrist+Vector((0,-.09,0)))
        for i,obj in zip((-1,1),fingers):keyed_location(obj,frame,wrist+Vector((i*.08,-.31,0)))
    cylinder('ENG_WorkOptic',(0,-.69,-.37),.135,.075,'dark')
    cylinder('ENG_WorkEmitter',(0,-.69,-.412),.086,.018,'cyan')


def hemisphere(name,center,radius):
    x,y,z=center;verts=[(x,y,z-radius)]
    for j in range(1,17):
        t=j*math.pi/32
        for i in range(64):
            a=i*math.tau/64;verts.append((x+radius*math.sin(t)*math.cos(a),y+radius*math.sin(t)*math.sin(a),z-radius*math.cos(t)))
    faces=[(0,1+i,1+(i+1)%64) for i in range(64)]
    for j in range(15):
        for i in range(64):
            a=1+j*64+i;b=1+j*64+(i+1)%64;faces.append((a,b,b+64,a+64))
    return mesh(name,verts,faces,'glass',False,True)


def security():
    hemisphere('SEC_SmokedEnergyDome',(0,-.85,-.265),.335)
    cylinder('SEC_DomeEmbeddedRetainer',(0,-.85,-.268),.354,.055,'dark')
    core=cylinder('FX_SEC_EnergyFocus',(0,-.85,-.602),.035,.007,'hot')
    for frame,s in ((1,.001),(40,.001),(65,.15),(80,1),(100,1),(120,.001)):
        core.scale=(s,)*3;core.keyframe_insert(data_path='scale',frame=frame)
    for x in (.58,):
        box('SEC_EmbeddedGunHousing',(x,-2.17,-.19),(.21,.36,.15),'armor',True,.035)
        cylinder('SEC_GunMuzzle',(x,-2.375,-.185),.068,.05,'dark',True,(0,1,0))
        for dx,dz in ((-.023,-.018),(.023,-.018),(0,.026)):
            cylinder('SEC_GunBore',(x+dx,-2.406,-.185+dz),.014,.018,'edge',True,(0,1,0))
    top=skin(.34,.38)
    cut_hull('TMP_MissileWell',(.34,.38,top-.08),(.46,.96,.22))
    box('SEC_MissileWell',(.34,.38,top-.17),(.44,.94,.035),'dark',True,.012)
    for y in (.08,.38,.68):
        local_top=skin(.34,y)
        cylinder('SEC_LaunchTube',(.34,y,local_top-.104),.075,.12,'edge',True)
        cylinder('SEC_MicroMissile',(.34,y,local_top-.091),.048,.12,'dark',True)
        cylinder('SEC_MissileCap',(.34,y,local_top-.025),.044,.012,'amber',True)
    lid=panel('SEC_DorsalMissileHatch',[(.10,-.13),(.57,-.10),(.57,.87),(.10,.87)],'armor',.028)
    solid=lid.modifiers.new('HatchThickness','SOLIDIFY');solid.thickness=.015;solid.offset=-1
    hinge(lid,(.59,.38,skin(.59,.38)+.025),1,1.25)
    line('SEC_HatchCenterSeal',[(.08,y,skin(.08,y)+.020) for y in (-.13,.1,.4,.7,.87)],'dark',.006,True)


def salvage():
    box('SALVAGE_LoadFrame',(0,.02,-.37),(1.15,1.10,.17),'edge',False,.035)
    for x in (.49,):box('SALVAGE_DrumBearing',(x,.04,-.54),(.16,.63,.40),'armor',True,.04)
    cylinder('SALVAGE_WinchDrum',(0,.04,-.54),.20,.84,'dark',False,(1,0,0))
    for x in (.40,):cylinder('SALVAGE_DrumFlange',(x,.04,-.54),.25,.06,'edge',True,(1,0,0))
    coil=[]
    for i in range(361):
        t=i/360;a=math.tau*6*t;coil.append((.35*t,.04+.207*math.cos(a),-.54+.207*math.sin(a)))
    line('SALVAGE_CableWinding',coil,'edge',.012,True)
    cable=cylinder('SALVAGE_MainCable',(0,-.18,-.80),.016,1,'edge')
    clamp=box('SALVAGE_RecoveryClamp',(0,-.18,-.82),(.38,.33,.17),'amber',False,.035)
    hook=box('SALVAGE_ClampJaw',(.12,-.18,-.90),(.07,.27,.19),'edge',True,.022)
    for frame,length in ((1,.13),(40,.13),(60,.85),(80,2.15),(100,2.15),(120,.13)):
        cable.location=(0,-.18,-.68-length/2);cable.scale.z=length
        cable.keyframe_insert(data_path='location',frame=frame);cable.keyframe_insert(data_path='scale',frame=frame)
        keyed_location(clamp,frame,(0,-.18,-.68-length));keyed_location(hook,frame,(.12,-.18,-.75-length))
    for y in (-.38,.44):box('SALVAGE_SafetyStripe',(.61,y,-.43),(.045,.15,.13),'amber',True,.006)


def geometry(obj):
    graph=bpy.context.evaluated_depsgraph_get();ev=obj.evaluated_get(graph);data=ev.to_mesh()
    vs=[ev.matrix_world@v.co for v in data.vertices];fs=[tuple(f.vertices) for f in data.polygons]
    ev.to_mesh_clear();return vs,fs


def realize_mirrored_parts():
    # glTF cannot replay a world-space Mirror modifier on a moving object.
    # A mirrored transform hierarchy preserves doors, arms and legs in export.
    negative=empty('V20_PortMechanisms');negative.scale.x=-1
    pivots={}
    for obj in list(ADDED):
        modifier=obj.modifiers.get('Bilateral_X0')
        if not modifier:continue
        obj.modifiers.remove(modifier)
        twin=obj.copy();twin.name=obj.name+'_Port';bpy.context.collection.objects.link(twin)
        if obj.parent:
            if obj.parent not in pivots:
                pivot=obj.parent.copy();pivot.name=obj.parent.name+'_Port'
                bpy.context.collection.objects.link(pivot);pivot.parent=negative;pivots[obj.parent]=pivot
            twin.parent=pivots[obj.parent]
        else:twin.parent=negative


def refresh_hull_normals():
    if HULL.data.has_custom_normals:
        HULL.data.normals_split_custom_set([(0.,0.,0.)]*len(HULL.data.loops))
    bm=bmesh.new();bm.from_mesh(HULL.data)
    bmesh.ops.remove_doubles(bm,verts=list(bm.verts),dist=1e-5)
    bmesh.ops.recalc_face_normals(bm,faces=list(bm.faces))
    bmesh.ops.split_edges(bm,edges=[e for e in bm.edges if e.is_manifold and e.calc_face_angle()>math.radians(38)])
    for f in bm.faces:f.smooth=True
    bm.to_mesh(HULL.data);bm.free();HULL.data.update()


def validate(role):
    samples=[];errors=[];scale=SIZES[role][0]
    for frame in (1,20,30,40,50,60,70,80,100,110,120):
        bpy.context.scene.frame_set(frame);vs=[];feet=[];nongear=[];gear=[];fs=[];foot_heights=[]
        for obj in bpy.context.scene.objects:
            if obj.type not in ('MESH','CURVE') or obj.name.startswith('FX_'):continue
            points,faces=geometry(obj);n=len(vs);vs.extend(points);fs.extend(tuple(n+i for i in f) for f in faces)
            if obj.name.startswith('GEAR_SoleTread'):feet.extend(points)
            if obj.name.startswith('GEAR_ContactPad'):foot_heights.append(min(p.z for p in points))
            if not obj.name.startswith('GEAR_'):nongear.extend(points)
            if obj.name.startswith('GEAR_'):gear.extend(points)
        kd=kdtree.KDTree(len(vs))
        for i,p in enumerate(vs):kd.insert(p,i)
        kd.balance();mirror=max(kd.find(Vector((-p.x,p.y,p.z)))[2] for p in vs)
        ground=min(p.z for p in feet)
        clearance=min(p.z for p in nongear)-ground
        flow_hits=[p for p in gear if -.20*scale<p.z<.30*scale and
                   math.hypot(abs(p.x)/scale-1.91,p.y/scale-.01826677)<1.03]
        rec={'frame':frame,'mirrorError':mirror,'footContactZ':ground,
             'nonGearGroundClearance':clearance,'gearPointsInsideFanFlow':len(flow_hits),
             'contactCount':len(foot_heights),'contactPlaneError':max(foot_heights)-min(foot_heights)}
        samples.append(rec)
        if mirror>3e-5:errors.append(('symmetry',frame,mirror))
        if len(foot_heights)!=4 or max(foot_heights)-min(foot_heights)>1e-5:errors.append(('contactPlane',frame))
        if flow_hits:errors.append(('gearInFanFlow',frame,len(flow_hits)))
        if frame in (1,20,120) and clearance<.16*scale:errors.append(('groundClearance',frame,clearance))
        if frame in (1,40,80,120):
            for obj,closed,pivot,axis,opened in HINGES:
                expected=ROOT_OBJ.matrix_world@closed
                if frame in (1,40,120):
                    error=max(abs(obj.matrix_world[r][c]-expected[r][c]) for r in range(4) for c in range(4))
                    if error>1e-5:errors.append(('hatchClosedTransform',obj.name,frame,error))
                elif abs(pivot.rotation_euler[axis]-opened)>1e-5:errors.append(('hatchOpenPose',obj.name,frame))
    bpy.context.scene.frame_set(1)
    bm=bmesh.new();bm.from_mesh(HULL.data);bmesh.ops.remove_doubles(bm,verts=list(bm.verts),dist=1e-5)
    bad=sum(not e.is_manifold for e in bm.edges);volume=bm.calc_volume(signed=True);bm.free()
    if bad:errors.append(('hullNonmanifold',bad))
    if volume<=0:errors.append(('hullVolume',volume))
    required={'field':'FIELD_ServiceKeel','scout':'SCOUT_ScanTrack','cargo':'CARGO_FloorDoor',
              'engineer':'ENG_UpperArm','security':'SEC_SmokedEnergyDome','salvage':'SALVAGE_WinchDrum'}[role]
    if not bpy.data.objects.get(required):errors.append(('missingRoleComponent',required))
    bpy.context.scene.frame_set(40)
    if role=='security':
        for obj in bpy.context.scene.objects:
            if obj.name.startswith('SEC_MissileCap'):
                for p in geometry(obj)[0]:
                    q=p/scale
                    if q.z>skin(q.x,q.y)+.002:errors.append(('missileAboveClosedSkin',list(q)))
    all_points=[];all_faces=[]
    for obj in bpy.context.scene.objects:
        if obj.type not in ('MESH','CURVE') or obj.name.startswith('FX_'):continue
        points,faces=geometry(obj);n=len(all_points);all_points.extend(points)
        all_faces.extend(tuple(n+i for i in f) for f in faces)
    tree=BVHTree.FromPolygons(all_points,all_faces);probes=[]
    for source in json.loads((BASE/'validation.json').read_text())['probes']:
        start=Vector(source['start'])*scale;end=Vector(source['hit'])*scale
        hit,_,_,depth=tree.ray_cast(start,(end-start).normalized())
        expected=(end-start).length
        probes.append({'type':source['type'],'depth':depth,'expectedDepth':expected})
        if depth is None or depth<expected*.95:errors.append(('obstructedDuct',source['type'],depth,expected))
    report={'role':role,'passed':not errors,'errors':errors,'samples':samples,'hullNonmanifoldEdges':bad,
            'scale':scale,'outerWingFactor':SIZES[role][1],'baseSha256':hashlib.sha256((BASE/'airframe.blend').read_bytes()).hexdigest(),
            'gameVerified':False,'fullSweptCollisionVerified':False,'referenceFidelityApproved':False,
            'animatedFxExcludedFromStructuralSymmetry':True,'ductProbes':probes,
            'hingeTransformChecks':len(HINGES)*4,'mirroredAnimationUsesTransformHierarchy':True}
    return report


def render(folder,preview=False):
    scene=bpy.context.scene;cam=scene.camera;cam.data.type='ORTHO';cam.data.shift_x=cam.data.shift_y=0
    target=Vector((0,.20,-.30));cam.data.ortho_scale=12.8
    views=[('hero',40,(8,-12,9)),('landed',1,(8,-12,6)),('active',80,(7,-12,-6)),
           ('front',1,(0,-14,0)),('rear',1,(0,14,0)),('top',40,(0,0,14)),
           ('bottom',40,(0,0,-14)),('left',1,(-14,0,0)),('right',1,(14,0,0)),
           ('rear-oblique',40,(7,12,8)),('gear-stowed',40,(7,-12,-6)),
           ('right-stowed',40,(14,0,0)),('top-active',80,(8,-12,9))]
    if preview:views=[views[i] for i in (0,1,2,8,9,10)]
    for name,frame,direction in views:
        scene.frame_set(frame);cam.data.ortho_scale=14.4 if name in ('top','bottom') else 12.8
        cam.location=target+Vector(direction);v19.v18.v16.look(cam,target)
        light=None
        if name in ('active','bottom','left','right','right-stowed','gear-stowed','rear'):
            bpy.ops.object.light_add(type='AREA',location=(0,-2,-6));light=bpy.context.object
            light.data.energy=900;light.data.size=7;v19.v18.v16.look(light,(0,0,0))
        scene.render.filepath=str(folder/(name+'.png'));bpy.ops.render.render(write_still=True)
        if light:bpy.data.objects.remove(light,do_unlink=True)
    if not preview and ROLE=='security':
        scene.frame_set(40);close_target=Vector((0,1.72,.15))
        cam.data.ortho_scale=5.8;cam.location=close_target+Vector((6,10,9));v19.v18.v16.look(cam,close_target)
        scene.render.filepath=str(folder/'rear-detail.png');bpy.ops.render.render(write_still=True)
    scene.frame_set(40);cam.location=target+Vector((8,-12,9));v19.v18.v16.look(cam,target)
    cam.data.ortho_scale=12.8


def build(role,preview=False):
    global MIRROR,HULL,SURFACE,ROLE,ROOT_OBJ
    ROLE=role;ADDED.clear();GEAR_FEET.clear();GEAR_PARTS.clear();ROLE_MOVERS.clear();HINGES.clear()
    bpy.ops.wm.open_mainfile(filepath=str(BASE/'airframe.blend'))
    materials();MIRROR=empty('V20_MirrorOrigin_X0')
    HULL=next(o for o in bpy.context.scene.objects if o.name.startswith('H01_'))
    SURFACE=BVHTree.FromObject(HULL,bpy.context.evaluated_depsgraph_get())
    rear_details();landing_gear();globals()[role]()
    # Keep the central body and fan spacing; alter only the outer wing planform.
    factor=SIZES[role][1]
    for obj in list(bpy.context.scene.objects):
        if obj.type!='MESH' or obj in ADDED:continue
        for p in obj.data.vertices:
            if abs(p.co.x)>3.02:p.co.x=math.copysign(3.02+(abs(p.co.x)-3.02)*factor,p.co.x)
        obj.data.update()
    refresh_hull_normals();realize_mirrored_parts()
    ROOT_OBJ=empty('V20_AircraftRoot')
    for obj in list(bpy.context.scene.objects):
        if obj!=ROOT_OBJ and obj.type in ('MESH','CURVE','EMPTY') and obj.parent is None:obj.parent=ROOT_OBJ
    ROOT_OBJ.scale=(SIZES[role][0],)*3
    bpy.context.scene.frame_end=120
    folder=OUT/role;folder.mkdir(parents=True,exist_ok=True)
    report=validate(role);(folder/'validation.json').write_text(json.dumps(report,indent=2))
    print('ROLE_CHECK',role,json.dumps({'passed':report['passed'],'errors':report['errors']}),flush=True)
    render(folder,preview)
    bpy.context.scene['asset_status']='UNAPPROVED_V20_ROLE_EQUIPMENT_PROPOSAL'
    bpy.ops.wm.save_as_mainfile(filepath=str(folder/'airframe.blend'))
    if not preview:
        bpy.ops.object.select_all(action='DESELECT')
        for obj in bpy.context.scene.objects:
            if obj.type in ('MESH','CURVE','EMPTY'):obj.select_set(True)
        bpy.ops.export_scene.gltf(filepath=str(folder/'airframe.glb'),export_format='GLB',use_selection=True,
                                 export_animations=True,export_frame_range=True,export_force_sampling=True,
                                 export_animation_mode='SCENE',export_anim_scene_split_object=False,export_apply=True)
    if not report['passed']:raise RuntimeError(report['errors'])


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--role',choices=ROLES);parser.add_argument('--preview',action='store_true')
    opt=parser.parse_args(sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else [])
    for role in ([opt.role] if opt.role else ROLES):build(role,opt.preview)


if __name__=='__main__':main()
