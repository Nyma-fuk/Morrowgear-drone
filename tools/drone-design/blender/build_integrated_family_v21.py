"""Role-specific continuous pressure skins and recessed equipment, staging only."""
import argparse
import importlib.util
import json
import math
import sys
from pathlib import Path

import bpy
import bmesh
from mathutils import Vector
from mathutils.bvhtree import BVHTree

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
OUT=ROOT/'docs/design/integrated-family-v21'
spec=importlib.util.spec_from_file_location('family20',HERE/'build_role_family_v20.py')
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
DEPTH={'field':0.,'scout':.24,'cargo':.64,'engineer':.48,'security':.36,'salvage':.58}
PREFORM=None;BASE_POINTS=[];BODY_CHANGE={};CLOSED=[];POCKETS=[]


def sync_surface():
    bpy.context.view_layer.update()
    v.SURFACE=BVHTree.FromObject(v.HULL,bpy.context.evaluated_depsgraph_get())


def shell_shape(point):
    p=point.copy();x,y,z=p
    # A continuous monotone height field avoids discontinuities at ray-hit edges.
    # Upper skin above Z=.05 stays fixed; the belly stretches without folding.
    lower=1-v.v19.v18.smooth((z+.19)/.24)
    width=1-v.v19.v18.smooth((abs(x)-(.64 if v.ROLE=='cargo' else .46))/.65)
    fore=v.v19.v18.smooth((y+2.15)/1.10) if v.ROLE=='salvage' else v.v19.v18.smooth((y+2.94)/.70)
    aft=1-v.v19.v18.smooth((y-(.25 if v.ROLE=='scout' else .70))/1.10)
    radius=math.hypot(abs(x)-1.91,y-.01826677)
    fan=v.v19.v18.smooth((radius-1.055)/.14)
    p.z-=DEPTH[v.ROLE]*width*fore*aft*fan*lower**1.25
    return p


def continuous_body():
    global PREFORM,BASE_POINTS,BODY_CHANGE
    PREFORM=v.SURFACE
    # Add support edges before reshaping the existing lower skin. No second pod.
    bm=bmesh.new();bm.from_mesh(v.HULL.data)
    bmesh.ops.remove_doubles(bm,verts=list(bm.verts),dist=1e-5)
    for _ in range(3):
        edges=[e for e in bm.edges if e.calc_length()>.18 and
               abs((e.verts[0].co.x+e.verts[1].co.x)/2)<1.5 and
               -2.95<(e.verts[0].co.y+e.verts[1].co.y)/2<1.85]
        if edges:bmesh.ops.subdivide_edges(bm,edges=edges,cuts=1,use_grid_fill=True)
    bm.to_mesh(v.HULL.data);bm.free();v.HULL.data.update()
    BASE_POINTS=[p.co.copy() for p in v.HULL.data.vertices]
    for vert in v.HULL.data.vertices:vert.co=shell_shape(vert.co)
    for obj in list(bpy.context.scene.objects):
        if obj.name.startswith('B01_') or (v.ROLE!='field' and obj.name.startswith(('H06_','H07_','S03_','S04_','S05_','S06_','S07_','S08_','S09_'))):
            bpy.data.objects.remove(obj,do_unlink=True)
        elif obj!=v.HULL and obj.type=='MESH' and obj.name.startswith(('H06_','H07_')):
            for vert in obj.data.vertices:vert.co=shell_shape(vert.co)
    delta=[(a-b.co).length for a,b in zip(BASE_POINTS,v.HULL.data.vertices)]
    BODY_CHANGE={'lowerSkinMaxDisplacement':max(delta),'modifiedHullVertices':sum(d>1e-5 for d in delta)}
    mirror_hull();v.refresh_hull_normals();sync_surface()


def mirror_hull():
    bm=bmesh.new();bm.from_mesh(v.HULL.data)
    bmesh.ops.remove_doubles(bm,verts=list(bm.verts),dist=1e-5)
    bmesh.ops.bisect_plane(bm,geom=list(bm.verts)+list(bm.edges)+list(bm.faces),
                          plane_co=(0,0,0),plane_no=(1,0,0),dist=1e-6,clear_inner=True)
    for p in bm.verts:
        if abs(p.co.x)<1e-5:p.co.x=0
    bm.to_mesh(v.HULL.data);bm.free()
    mod=v.HULL.modifiers.new('CanonicalBodySymmetry','MIRROR');mod.use_clip=True;mod.merge_threshold=1e-5
    bpy.context.view_layer.objects.active=v.HULL;bpy.ops.object.modifier_apply(modifier=mod.name)


def surface_point(uv,side='bottom'):
    a,b=uv
    if side=='front':
        p=v.SURFACE.ray_cast(Vector((a,-5,b)),Vector((0,1,0)))[0]
        if p is None:raise ValueError(('front surface missed',a,b))
        return p
    return Vector((a,b,v.skin(a,b,side=='top')))


def direction(side):
    return Vector((0,-1,0) if side=='front' else (0,0,1 if side=='top' else -1))


def sample_polygon(polygon):
    rim=[]
    for a,b in zip(polygon,polygon[1:]+polygon[:1]):
        a,b=Vector(a),Vector(b);steps=max(1,math.ceil((b-a).length/.045))
        rim.extend(a.lerp(b,i/steps) for i in range(steps))
    return rim


def surface_grid(polygon,side,offset):
    center=sum((Vector(p) for p in polygon),Vector((0,0)))/len(polygon)
    rim=sample_polygon(polygon);normal=direction(side);n=len(rim)
    rings=max(2,math.ceil(max((p-center).length for p in rim)/.06))
    verts=[surface_point(center,side)+normal*offset]
    for j in range(1,rings+1):
        verts.extend(surface_point(center.lerp(p,j/rings),side)+normal*offset for p in rim)
    faces=[(0,i+1,(i+1)%n+1) for i in range(n)]
    for j in range(rings-1):
        for i in range(n):
            a=1+j*n+i;b=1+j*n+(i+1)%n;faces.append((a,b,b+n,a+n))
    return verts,faces,n


def patch(name,polygon,side='bottom',material='armor',offset=.004,pair=True):
    verts,faces,_=surface_grid(polygon,side,offset)
    return v.mesh(name,verts,faces,material,pair,True)


def boolean(cutter):
    bpy.context.view_layer.objects.active=v.HULL
    mod=v.HULL.modifiers.new('IntegralPocket','BOOLEAN');mod.operation='DIFFERENCE';mod.solver='EXACT';mod.object=cutter
    bpy.ops.object.modifier_apply(modifier=mod.name)
    if cutter in v.ADDED:v.ADDED.remove(cutter)
    bpy.data.objects.remove(cutter,do_unlink=True)


def recess(name,polygon,side='bottom',depth=.23,pair=True):
    # Both caps follow the whole curved skin. An ngon spanning the rim leaves
    # an uncut island when a convex belly projects beyond that rim plane.
    front,cap,n=surface_grid(polygon,side,.06)
    back,_,_=surface_grid(polygon,side,-depth);count=len(front);first=count-n
    faces=[tuple(reversed(face)) for face in cap]+[tuple(i+count for i in face) for face in cap]
    for i in range(n):
        a=first+i;b=first+(i+1)%n;faces.append((a,b,b+count,a+count))
    boolean(v.mesh('TMP_'+name,front+back,faces,'dark',pair))
    center=sum((Vector(p) for p in polygon),Vector((0,0)))/len(polygon)
    samples=[center]+[center.lerp(Vector(p),.6) for p in polygon]
    POCKETS.append((name,[surface_point(p,side) for p in samples],direction(side),depth))
    return patch(name+'_Interior',polygon,side,'dark',-depth+.006,pair)


def check_pockets():
    bpy.context.view_layer.update();tree=BVHTree.FromObject(v.HULL,bpy.context.evaluated_depsgraph_get())
    probes=[]
    for name,points,normal,depth in POCKETS:
        for p in points:
            hit,_,_,distance=tree.ray_cast(p+normal*.005,-normal)
            probes.append({'bay':name,'depthRequired':depth-.015,'hitDepth':distance,
                           'passed':hit is not None and distance>=depth-.015})
    return {'passed':all(p['passed'] for p in probes),'samples':probes}


def outline(name,polygon,side='bottom',material='dark',radius=.007,offset=.007,pair=True):
    return v.line(name,[surface_point(p,side)+direction(side)*offset for p in sample_polygon(polygon)],
                  material,radius,pair,True)


def closed_door(name,polygon,side='bottom',depth=.3,axis=1,angle=-1.25):
    recess(name,polygon,side,depth)
    obj=patch(name,polygon,side,'armor',.007)
    thickness=obj.modifiers.new('DoorThickness','SOLIDIFY');thickness.thickness=.018;thickness.offset=-1
    uv=max(polygon,key=lambda p:p[0]);pivot=surface_point(uv,side)
    v.hinge(obj,pivot,axis,angle);CLOSED.append(obj)
    return obj


def landing_gear():
    heavy=v.ROLE in ('cargo','salvage');drop=.16 if heavy else 0
    for fore in (-1,1):
        x=1.13;y=fore*1.65;top=min(.17,v.skin(x,y)-.04);floor=v.skin(x,y,False)
        v.cut_hull('TMP_GearCavity',(x,y,(floor-.08+top)/2),(.43,.64,top-floor+.08))
        v.box('GEAR_BayLiner',(x,y,top-.018),(.40,.60,.022),'dark',True,.015)
        axis=Vector((.16,0,-1)).normalized();home=Vector((x,y,.10))
        v.sleeve('GEAR_FixedSocket_'+str(fore),home+axis*((.34+drop)/2-.04),.143,.122,.34+drop,'armor',axis)
        for i,(r,inner) in enumerate(((.11,.092),(.083,.067),(.058,0))):
            name='GEAR_TelescopicStage_%s_%s'%(fore,i)
            part=v.sleeve(name,(0,0,0),r,inner,.38,'edge',axis) if inner else v.cylinder(name,(0,0,0),r,.38,'edge',True,axis)
            for frame,extended in ((1,1),(20,1),(40,0),(100,0),(120,1)):
                v.keyed_location(part,frame,home+axis*((.18+drop if extended else 0)+(.32*i if extended else .026*i)+.19))
        foot=v.box('GEAR_ContactPad_'+str(fore),(0,0,0),(.34 if heavy else .30,.56 if heavy else .48,.11),'rubber',True,.032)
        cap=v.box('GEAR_FootArmour_'+str(fore),(0,0,0),(.27,.44 if heavy else .38,.06),'edge',True,.022)
        treads=[v.box('GEAR_SoleTread_%s_%s'%(fore,i),(0,0,0),(.28,.045,.018),'dark',True,.006) for i in (-1,1)]
        for frame,extended in ((1,1),(20,1),(40,0),(100,0),(120,1)):
            end=home+axis*((.18+drop+1.02) if extended else .46)
            v.keyed_location(foot,frame,end);v.keyed_location(cap,frame,end+Vector((0,0,.065)))
            for i,obj in zip((-1,1),treads):v.keyed_location(obj,frame,end+Vector((0,i*.15,-.054)))


def front_optic(name,x,z,radius,material='glass',iris='cyan'):
    front=surface_point((x,z),'front');axis=Vector((0,1,0))
    rim=[surface_point((x+(radius+.029)*math.cos(i*math.tau/48),z+(radius+.029)*math.sin(i*math.tau/48)),'front') for i in range(48)]
    entrance=min(p.y for p in rim)-.05;glass_y=max(p.y for p in rim)+.035;back=glass_y+.055
    boolean(v.cylinder('TMP_'+name,(x,(entrance+back)/2,z),radius+.028,back-entrance,'dark',x>0,axis))
    v.cylinder(name+'_Recess',(x,glass_y+.027,z),radius+.018,.026,'dark',x>0,axis)
    v.cylinder(name+'_Optic',(x,glass_y,z),radius,.027,material,x>0,axis)
    v.cylinder(name+'_Iris',(x,glass_y-.018,z),radius*(.32 if iris=='amber' else .57),.010,iris,x>0,axis)
    return front


def field():
    v.field()


def scout():
    v.scout()
    for obj in list(v.ADDED):
        if obj.name.startswith('SCOUT_Optic'):
            v.ADDED.remove(obj);bpy.data.objects.remove(obj,do_unlink=True)
    front_optic('SCOUT_EmbeddedCamera',.16,-.30,.090,iris='glass')
    polygon=[(.60,-.15),(.88,-.115),(.94,-.16),(.88,-.205),(.62,-.225)]
    recess('SCOUT_CheekSensor',polygon,'front',.09)
    patch('SCOUT_CheekGlass',polygon,'front','glass',-.018)
    outline('SCOUT_SensorSeal',polygon,'front',radius=.006)
    slit=[(.66,-.175),(.84,-.145),(.87,-.16),(.67,-.191)]
    patch('SCOUT_SideScanLens',slit,'front','cyan',-.006)


def cargo():
    bay=[(.028,-1.94),(.48,-1.94),(.59,-1.73),(.59,.56),(.42,.77),(.028,.70)]
    closed_door('CARGO_FloorDoor',bay,depth=.34,angle=-1.32)
    for y in (-1.43,-.75,-.08):
        z=v.skin(.28,y,False)+.17
        v.box('CARGO_InternalTray',(.29,y,z),(.46,.52,.20),'edge',True,.025)
        v.box('CARGO_InternalLatch',(.48,y-.23,z),(.055,.035,.10),'amber',True,.009)
    # Conformal service drawers are cut into the deepened front skin.
    for z in (-.47,-.69):
        poly=[(.04,z+.092),(.54,z+.079),(.58,z-.080),(.055,z-.095)]
        recess('CARGO_FrontDrawer',poly,'front',.085)
        patch('CARGO_FrontDoor',poly,'front','armor',-.010)
        outline('CARGO_DrawerSeal',poly,'front',radius=.006)
        patch('CARGO_DrawerLatch',[(.32,z+.004),(.44,z+.004),(.44,z-.015),(.32,z-.015)],'front','amber',-.004)
    for y in (-1.6,-.6,.45):
        points=[(.63,y),(.72,y+.05),(.81,y+.16)]
        v.line('CARGO_ShellJoint',[surface_point(p)+direction('bottom')*.005 for p in points],'dark',.007,True)


def engineer():
    bay=[(.45,-1.98),(.83,-1.97),(.91,-1.74),(.88,-.63),(.48,-.64)]
    recess('ENG_ArmCradle',bay,depth=.45)
    outline('ENG_CradleRim',bay,radius=.008)
    upper=v.box('ENG_UpperArm',(0,0,0),(1,1,1),'edge',True,0)
    lower=v.box('ENG_Forearm',(0,0,0),(1,1,1),'armor',True,0)
    joints=[v.cylinder('ENG_Joint_'+str(i),(0,0,0),.096,.11,'edge',True,(1,0,0)) for i in range(3)]
    joint_caps=[v.cylinder('ENG_JointCap_'+str(i),(0,0,0),.052,.018,'dark',True,(1,0,0)) for i in range(3)]
    ram=v.cylinder('ENG_LinearActuator',(0,0,0),1,1,'dark',True)
    rod=v.cylinder('ENG_ActuatorRod',(0,0,0),1,1,'edge',True)
    claw=v.box('ENG_GripperBody',(0,0,0),(.19,.21,.12),'armor',True,.025)
    fingers=[v.box('ENG_GripperFinger_'+str(i),(0,0,0),(.040,.24,.09),'edge',True,.012) for i in (-1,1)]
    root=Vector((.67,-1.45,-.25));a0=Vector((0,.58,-.08));b0=Vector((0,-.50,-.025))
    a1=Vector((.12,-.20,-.54));b1=Vector((.15,-.48,-.02))
    for frame in (1,20,40,50,60,70,80,90,100,110,120):
        t=v.v19.v18.smooth((frame-40)/40) if frame<=100 else 1-v.v19.v18.smooth((frame-100)/20)
        elbow=root+a0.lerp(a1,t).normalized()*a0.length;wrist=elbow+b0.lerp(b1,t).normalized()*b0.length
        v.pose_beam(upper,root,elbow,frame,.13);v.pose_beam(lower,elbow,wrist,frame,.11)
        for obj,p in zip(joints,(root,elbow,wrist)):v.keyed_location(obj,frame,p)
        for obj,p in zip(joint_caps,(root,elbow,wrist)):v.keyed_location(obj,frame,p+Vector((.065,0,0)))
        ram_start=root+Vector((.10,0,0));ram_end=elbow+Vector((.10,0,.03))
        v.pose_beam(ram,ram_start,ram_start.lerp(ram_end,.68),frame,.047)
        v.pose_beam(rod,ram_start.lerp(ram_end,.58),ram_end,frame,.026)
        v.keyed_location(claw,frame,wrist+Vector((0,-.08,0)))
        for sign,obj in zip((-1,1),fingers):v.keyed_location(obj,frame,wrist+Vector((sign*.071,-.26,0)))
    front_optic('ENG_IntegratedWorkEmitter',0,-.44,.145,'glass','amber')


def security():
    for obj in list(bpy.context.scene.objects):
        if obj.name.startswith(('H03_CrownJoint','H04_CenterLatch','H05_LatchInset')):
            bpy.data.objects.remove(obj,do_unlink=True)
    bay=[(.10,-1.15),(.49,-1.07),(.58,-.70),(.58,.40),(.44,.56),(.10,.51)]
    closed_door('SEC_DorsalMissileHatch',bay,'top',.22,angle=1.20)
    for y in (-.83,-.43,-.03,.32):
        top=v.skin(.34,y)
        v.cylinder('SEC_LaunchTube',(.34,y,top-.12),.080,.16,'dark',True)
        v.cylinder('SEC_MissileCap',(.34,y,top-.036),.052,.018,'amber',True)
    for x in (.72,):
        z=-.27;front=surface_point((x,z),'front');axis=Vector((0,1,0))
        boolean(v.cylinder('TMP_GunChamber',front+axis*.085,.135,.30,'dark',True,axis))
        v.cylinder('SEC_CannonChamber',front+axis*.115,.125,.07,'dark',True,axis)
        for dx,dz in ((-.035,-.024),(.035,-.024),(0,.039)):
            v.cylinder('SEC_CannonBarrel',front+Vector((dx,.042,dz)),.024,.05,'edge',True,axis)
            v.cylinder('SEC_CannonBore',front+Vector((dx,.014,dz)),.016,.012,'dark',True,axis)
    y=-1.00;floor=v.skin(0,y,False)
    boolean(v.cylinder('TMP_EnergyWell',(0,y,floor+.035),.29,.31,'dark'))
    v.hemisphere('SEC_SmokedEnergyDome',(0,y,floor+.095),.30)
    focus=v.cylinder('FX_SEC_EnergyFocus',(0,y,floor-.205),.030,.007,'hot')
    for frame,s in ((1,.001),(40,.001),(60,.15),(80,1),(100,1),(120,.001)):
        focus.scale=(s,)*3;focus.keyframe_insert(data_path='scale',frame=frame)
    # Ring follows the lower fuselage, not a plate bolted below the dome.
    ring=[(.34*math.cos(i*math.tau/64),y+.34*math.sin(i*math.tau/64)) for i in range(64)]
    outline('SEC_EnergyWellSeal',ring,radius=.012,pair=False)


def salvage():
    bay=[(-.54,-.81),(.54,-.81),(.61,-.56),(.61,.47),(.44,.65),(-.44,.65),(-.61,.47),(-.61,-.56)]
    recess('SALVAGE_InternalWinchBay',bay,depth=.58,pair=False)
    outline('SALVAGE_BaySeal',bay,radius=.012,pair=False)
    center=Vector((0,-.23,-.32));radius=.20
    v.cylinder('SALVAGE_WinchDrum',center,radius,.80,'dark',False,(1,0,0))
    for x in (.43,):
        v.cylinder('SALVAGE_LoadBearing',(x,center.y,center.z),.255,.075,'edge',True,(1,0,0))
        v.box('SALVAGE_InternalSpar',(x,.09,-.18),(.15,1.02,.13),'armor',True,.027)
    coil=[(.35*i/360,center.y+.207*math.cos(i*math.tau/60),center.z+.207*math.sin(i*math.tau/60)) for i in range(361)]
    v.line('SALVAGE_CableWinding',coil,'edge',.012,True)
    anchor=Vector((0,-.45,-.65))
    v.line('SALVAGE_FairleadCable',[(0,-.44,-.32),(0,-.45,-.45),anchor],'edge',.016)
    v.box('SALVAGE_FairleadHousing',(0,-.45,-.61),(.23,.22,.10),'dark',False,.035)
    cable=v.cylinder('SALVAGE_MainCable',(0,0,0),.016,1,'edge')
    clamp=v.box('SALVAGE_RecoveryClamp',(0,0,0),(.37,.30,.15),'edge',False,.035)
    jaw=v.box('SALVAGE_ClampJaw',(.12,0,0),(.075,.25,.17),'amber',True,.018)
    pivot=v.cylinder('SALVAGE_ClampHinge',(0,0,0),.063,.40,'dark',False,(1,0,0))
    jaw_tip=v.box('SALVAGE_ClampToe',(0,0,0),(.11,.075,.07),'edge',True,.017)
    for frame,length in ((1,.045),(40,.045),(60,.8),(80,2.10),(100,2.10),(120,.045)):
        cable.location=anchor+Vector((0,0,-length/2));cable.scale.z=length
        cable.keyframe_insert(data_path='location',frame=frame);cable.keyframe_insert(data_path='scale',frame=frame)
        v.keyed_location(clamp,frame,anchor+Vector((0,0,-length)))
        v.keyed_location(pivot,frame,anchor+Vector((0,0,-length)))
        v.keyed_location(jaw,frame,anchor+Vector((.12,0,-length-.075)))
        v.keyed_location(jaw_tip,frame,anchor+Vector((.095,-.09,-length-.17)))
    for x in (.69,):
        for y in (-.68,.54):
            z=v.skin(x,y,False)
            v.box('SALVAGE_FrameWitness',(x,y,z-.006),(.065,.20,.022),'amber',True,.006)


def render(folder,preview=False):
    scene=bpy.context.scene;cam=scene.camera;target=Vector((0,.18,-.33))
    cam.data.type='ORTHO';cam.data.shift_x=cam.data.shift_y=0
    views=[('hero',40,(8,-12,5.8)),('active',80,(8,-12,-8)),('landed',1,(8,-12,5.8)),
           ('front',1,(0,-14,0)),('rear',1,(0,14,0)),('top',40,(0,0,14)),
           ('bottom',40,(0,0,-14)),('left',1,(-14,0,0)),('right',1,(14,0,0)),
           ('right-stowed',40,(14,0,0)),('rear-oblique',40,(8,12,6)),
           ('top-active',80,(8,-12,7)),('gear-stowed',40,(8,-12,-8))]
    if preview:views=[views[i] for i in (0,1,2,8,11,12)]
    bpy.ops.object.light_add(type='AREA',location=(2,-7,-4));fill=bpy.context.object;fill.name='Review_BellyFill'
    fill.data.energy=650;fill.data.size=7;v.v19.v18.v16.look(fill,(0,0,-.2))
    for name,frame,axis in views:
        scene.frame_set(frame);cam.data.ortho_scale=14.4 if name in ('top','bottom') else 12.8
        cam.location=target+Vector(axis);v.v19.v18.v16.look(cam,target)
        scene.render.filepath=str(folder/(name+'.png'));bpy.ops.render.render(write_still=True)
    if not preview:
        scene.frame_set(80)
        close_target=Vector((0,-.75,-.35));axis=Vector((4,-6,-11));cam.data.ortho_scale=5.2
        if v.ROLE=='scout':close_target=Vector((0,-2.10,-.18));axis=Vector((0,-10,.25));cam.data.ortho_scale=3.3
        if v.ROLE=='security':close_target=Vector((0,-.28,.10));axis=Vector((5,-7,10));cam.data.ortho_scale=4.9
        if v.ROLE=='salvage':close_target=Vector((0,-.23,-.35));axis=Vector((1.5,-4,-12));cam.data.ortho_scale=4.3
        cam.location=close_target+axis;v.v19.v18.v16.look(cam,close_target)
        scene.render.filepath=str(folder/'equipment-close.png');bpy.ops.render.render(write_still=True)
    bpy.data.objects.remove(fill,do_unlink=True)
    scene.frame_set(40);cam.data.ortho_scale=12.8;cam.location=target+Vector((8,-12,5.8));v.v19.v18.v16.look(cam,target)


def build(role,preview=False):
    v.ROLE=role;v.ADDED.clear();v.GEAR_FEET.clear();v.GEAR_PARTS.clear();v.HINGES.clear();v.ROLE_MOVERS.clear();CLOSED.clear();POCKETS.clear()
    bpy.ops.wm.open_mainfile(filepath=str(v.BASE/'airframe.blend'))
    v.materials();v.MIRROR=v.empty('V20_MirrorOrigin_X0')
    v.HULL=next(o for o in bpy.context.scene.objects if o.name.startswith('H01_'))
    sync_surface();continuous_body();v.rear_details();landing_gear();globals()[role]()
    factor=v.SIZES[role][1]
    for obj in list(bpy.context.scene.objects):
        if obj.type!='MESH' or obj in v.ADDED:continue
        for p in obj.data.vertices:
            if abs(p.co.x)>3.02:p.co.x=math.copysign(3.02+(abs(p.co.x)-3.02)*factor,p.co.x)
    mirror_hull();v.refresh_hull_normals()
    normal=v.HULL.modifiers.new('ContinuousSkinNormals','WEIGHTED_NORMAL')
    normal.mode='FACE_AREA_WITH_ANGLE';normal.weight=40;normal.keep_sharp=True
    bpy.context.view_layer.objects.active=v.HULL;bpy.ops.object.modifier_apply(modifier=normal.name)
    pockets=check_pockets()
    v.realize_mirrored_parts();v.ROOT_OBJ=v.empty('V21_AircraftRoot')
    for obj in list(bpy.context.scene.objects):
        if obj!=v.ROOT_OBJ and obj.type in ('MESH','CURVE','EMPTY') and obj.parent is None:obj.parent=v.ROOT_OBJ
    v.ROOT_OBJ.scale=(v.SIZES[role][0],)*3;bpy.context.scene.frame_end=120
    folder=OUT/role;folder.mkdir(parents=True,exist_ok=True)
    report=v.validate(role);report['continuousBody']=BODY_CHANGE
    report['pocketDepthChecks']=pockets
    if not pockets['passed']:
        report['errors'].append(('obstructedEquipmentBay',[p for p in pockets['samples'] if not p['passed']]))
        report['passed']=False
    report['sourceRoleLayoutReplaced']=True
    if role!='field' and BODY_CHANGE['modifiedHullVertices']<100:
        report['errors'].append(('noMeaningfulHullReshape',BODY_CHANGE));report['passed']=False
    (folder/'validation.json').write_text(json.dumps(report,indent=2))
    print('INTEGRATED_ROLE_CHECK',role,json.dumps({'passed':report['passed'],'errors':report['errors'],'body':BODY_CHANGE}),flush=True)
    render(folder,preview);bpy.context.scene['asset_status']='UNAPPROVED_V21_INTEGRATED_ROLE_PROPOSAL'
    bpy.ops.wm.save_as_mainfile(filepath=str(folder/'airframe.blend'))
    if not preview:
        bpy.ops.object.select_all(action='DESELECT')
        for obj in bpy.context.scene.objects:
            if obj.type in ('MESH','CURVE','EMPTY'):obj.select_set(True)
        bpy.ops.export_scene.gltf(filepath=str(folder/'airframe.glb'),export_format='GLB',use_selection=True,
                                 export_apply=True,export_animations=True,export_animation_mode='SCENE',
                                 export_anim_scene_split_object=False,export_frame_range=True,export_force_sampling=True)
    if not report['passed']:raise RuntimeError(report['errors'])


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--role',choices=v.ROLES);parser.add_argument('--preview',action='store_true')
    parser.add_argument('--compare-v20',action='store_true')
    opt=parser.parse_args(sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else [])
    if opt.compare_v20:
        for role in ('cargo','engineer','salvage'):
            bpy.ops.wm.open_mainfile(filepath=str(v.OUT/role/'airframe.blend'))
            folder=OUT/'comparison-v20'/role;folder.mkdir(parents=True,exist_ok=True)
            render(folder,True)
        return
    for role in ([opt.role] if opt.role else v.ROLES):build(role,opt.preview)


if __name__=='__main__':main()
