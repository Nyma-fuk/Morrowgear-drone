"""Canonical art build; writes a staging pack, never overwrites live game assets."""
import argparse
import importlib.util
import json
import math
import sys
from pathlib import Path

import bpy
import bmesh
from mathutils import Matrix, Vector, kdtree

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = ROOT / 'docs/design/unified-family-v8'


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, HERE / path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


p = module('v8_parts', 'build_family_a_variants_v3.py')
b = p.base
A = b.mat('V8_Graphite', (.022, .029, .038), .32, .44)
S = b.mat('V8_Titanium', (.16, .19, .21), .73, .30)
D = b.mat('V8_Recess', (.009, .014, .018), .45, .32)
G = b.mat('V8_Smoked_Dome', (.006, .009, .012), .32, .085)
C = b.mat('V8_Data_Cyan', (.02, .42, .54), .3, .25, (.02, .6, .8))
O = b.mat('V8_Service_Amber', (.85, .34, .045), .5, .3)
W = b.mat('V8_Optic_Core', (.7, .85, .9), .2, .2, (.7, .85, 1))
PANEL = b.mat('V8_Solar', (.025, .07, .10), .6, .18)
ROLES = ('field', 'scout', 'cargo', 'engineer', 'security', 'salvage')


def reset():
    b.clear()
    bpy.ops.object.empty_add()
    b.MIRROR_ORIGIN = bpy.context.object
    b.MIRROR_ORIGIN.name = 'MirrorOrigin_X0'
    bpy.context.scene.frame_set(1)


def box(name, pos, size, mat=A, pair=False, bevel=.04):
    return b.cube(name, pos, size, mat, bevel, mirrored=pair)


def cyl(name, pos, radius, depth, mat=S, pair=False):
    return b.cylinder(name, pos, radius, depth, mat, 48, mirrored=pair)


def beam(name, start, end, width=.10, mat=S, pair=False):
    return p.beam(name, start, end, width, width, mat, mirrored=pair)


def anim_move(obj, axis, states):
    for frame, value in states:
        obj.delta_location[axis] = value
        obj.keyframe_insert(data_path='delta_location', frame=frame)


def hinge(obj, origin, axis, states):
    bpy.ops.object.empty_add(location=origin)
    pivot = bpy.context.object
    pivot.name = obj.name + '_Hinge'
    matrix = obj.matrix_world.copy()
    obj.parent = pivot
    obj.matrix_world = matrix
    for frame, value in states:
        pivot.rotation_euler[axis] = value
        pivot.keyframe_insert(data_path='rotation_euler', frame=frame)
    return pivot


def surface_z(x, y, top=True):
    ridge = .46 * math.exp(-((x / .92) ** 2)) * max(.18, 1 - ((y - .15) / 3.1) ** 2)
    return (.14 + ridge) if top else (-.13 - .20 * math.exp(-((x / .9) ** 2)))


def airframe(role):
    scale = {'field': .91, 'scout': 1.08, 'cargo': 1.06, 'engineer': 1., 'security': 1., 'salvage': 1.17}[role]
    profile = [(-2.15,.25),(-1.85,.60),(-1.35,1.30),(-.8,1.9),(-.2,2.65),(.45,3.30),(.8,3.55),(1.1,3.35),(1.38,2.25),(1.62,1.10),(1.85,.50)]
    if role == 'scout':
        profile = [(y, w * (1.10 if w > 1.5 else 1.04)) for y,w in profile]
    if role in ('cargo', 'salvage'):
        profile = [(y * 1.10, max(w, .55) if y < -1.8 else w) for y,w in profile]
    verts = []
    for top in (False, True):
        for y,width in profile:
            for i in range(33):
                x = width * (i / 16 - 1)
                verts.append((x, y, surface_z(x,y,top)))
    n = len(profile) * 33
    faces = []
    for layer in (0,1):
        for j in range(len(profile)-1):
            for i in range(32):
                k = layer*n+j*33+i
                f = (k,k+1,k+34,k+33)
                faces.append(f if layer else f[::-1])
    boundary = list(range(33)) + [j*33+32 for j in range(1,len(profile))] + list(range(n-2,n-34,-1)) + [j*33 for j in range(len(profile)-2,0,-1)]
    for a,c in zip(boundary, boundary[1:]+boundary[:1]):
        faces.append((a,c,c+n,a+n))
    shell = b.mesh('BlendedWing_StructuralShell',verts,faces,A,.035,True)
    # Real through-holes, not black disks hiding a solid wing.
    for sign in (-1,1):
        cutter = cyl('TemporaryDuct', (sign*1.64,.30,0),.73,3,D)
        bpy.context.view_layer.objects.active = shell
        mod = shell.modifiers.new('OpenLiftDuct', 'BOOLEAN')
        mod.operation = 'DIFFERENCE'
        mod.object = cutter
        bpy.ops.object.modifier_apply(modifier=mod.name)
        bpy.data.objects.remove(cutter,do_unlink=True)
    bm=bmesh.new();bm.from_mesh(shell.data)
    bmesh.ops.bisect_plane(bm,geom=list(bm.verts)+list(bm.edges)+list(bm.faces),dist=1e-7,plane_co=(0,0,0),plane_no=(1,0,0),clear_inner=True)
    bm.to_mesh(shell.data);bm.free();b.mirror(shell)
    b.loft('SculptedCentralArmor',[(-2.12,.23,-.09,.18),(-1.73,.48,.18,.40),(-.95,.60,.40,.67),(.05,.64,.50,.77),(1.05,.45,.45,.66),(1.72,.25,.23,.42)],A,.065)
    b.loft('SpineInset',[(-1.50,.13,.40,.46),(-.8,.19,.62,.70),(.2,.20,.72,.79),(1.1,.13,.61,.68)],S,.018)
    for y,z in ((-1.25,.51),(-.55,.74),(.45,.78),(1.22,.61)):
        box('DorsalPanelFastener',(.27,y,z),(.06,.095,.025),O,True,.006)
    for j in range(3):
        yy=-1.1+j*.20
        pts=[(.73,yy,surface_z(.73,yy)+.035),(1.06,yy+.12,surface_z(1.06,yy+.12)+.035),(1.08,yy+.22,surface_z(1.08,yy+.22)+.035),(.74,yy+.1,surface_z(.74,yy+.1)+.035)]
        vent=b.mesh('ShoulderCoolingInset',pts,[(0,1,2,3)],D);b.mirror(vent)
    for sign in (-1,1):
        verts=[(sign*2.55,.98,.16),(sign*3.36,.82,.16),(sign*3.43,.78,.43),(sign*2.62,.97,.29)]
        b.mesh('WingtipFence',verts,[(0,1,2,3)],A,.008)
    p.annular_ring('DuctWall',1.64,.30,.75,.69,-.14,.22,D,mirrored=True)
    p.annular_ring('MachinedDuctLip',1.64,.30,.78,.70,.20,.235,S,mirrored=True)
    cyl('TurbineHub',(1.64,.30,.07),.15,.20,S,True)
    rotors={}
    for sign in (-1,1):
        bpy.ops.object.empty_add(location=(sign*1.64,.30,0));pivot=bpy.context.object;pivot.name='RotorPivot_'+str(sign)
        for frame,angle in ((1,0),(25,0),(40,sign*math.tau),(80,sign*math.tau*9),(120,sign*math.tau*17)):
            pivot.rotation_euler.z=angle;pivot.keyframe_insert(data_path='rotation_euler',frame=frame)
        rotors[sign]=pivot
    bpy.context.scene.frame_set(1)
    for i in range(10):
        angle = i*math.tau/10
        verts=[]
        for z in (-.01,.035):
            for r,a in ((.14,-.18),(.64,-.06),(.65,.09),(.25,.30)):
                verts.append((1.64+r*math.cos(angle+a),.30+r*math.sin(angle+a),z))
        faces=[(0,3,2,1),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)]
        for sign in (-1,1):
            obj=b.mesh('RotorBlade_%s_%02d'%(sign,i),[(sign*x,y,z) for x,y,z in verts],faces if sign==1 else [f[::-1] for f in faces],D,.008)
            matrix=obj.matrix_world.copy();obj.parent=rotors[sign];obj.matrix_world=matrix
    for y in (-1.4,-.85,-.3,.3,.9,1.4):
        pts=[(x,y,surface_z(x,y)+.018) for x in (0,.3,.6,.84)]
        b.curve('PanelSeam',pts,D,.009,mirrored=True)
    for x in (.32,.60):
        b.curve('SpineSeam',[(x,y,surface_z(x,y)+.018) for y in (-1.55,-1,-.3,.5,1.35)],D,.009,mirrored=True)
    for y in (-.7,.95):
        box('ServiceLatch',(.22,y,surface_z(.22,y)+.025),(.08,.16,.025),O,True,.008)
    for y in (.9,1.06,1.22,1.38):
        box('CoolingLouver',(.8,y,surface_z(.8,y)+.012),(.17,.07,.018),D,True,.006)
    visor=[(-.56,-1.89,.12),(-.28,-2.15,.12),(0,-2.17,.12),(.28,-2.15,.12),(.56,-1.89,.12)]
    b.ribbon('VisorRecess',visor,.062,D)
    b.ribbon('Visor',[(x,y-.014,z) for x,y,z in visor],.019,C)
    # Dedicated cooling mouths on solid shoulder area; attachments stay below/inboard.
    box('IntakeRecess',(.91,-1.17,.04),(.34,.13,.18),D,True,.025)
    for x in (.80,.91,1.02):
        box('IntakeVane',(x,-1.25,.04),(.02,.02,.12),S,True,.003)
    for y in (-1.15,1.05):
        before=set(bpy.context.scene.objects)
        beam('Gear_Strut',(.62,y,-.23),(.92,y,-.91),.11,S,True)
        box('Gear_Foot',(.92,y,-.97),(.32,.53,.12),D,True)
        box('Gear_Inlay',(.92,y,-.9),(.18,.31,.035),S,True,.015)
        for obj in set(bpy.context.scene.objects)-before:
            anim_move(obj,2,[(1,0),(40,.67),(80,.67),(120,0)])
    box('GearBay',(.63,-.05,-.28),(.22,2.95,.12),D,True)
    if role == 'scout':
        for x in (-.20,.20):
            obj=cyl('ForwardCamera',(x,-2.08,-.05),.07,.08,G)
            obj.rotation_euler.x=math.pi/2
        perimeter=[(width,y,.035) for y,width in profile]
        perimeter+= [(-width,y,.035) for y,width in reversed(profile)]
        b.curve('ScannerTrack',perimeter,C,.012,cyclic=True)
        bpy.ops.mesh.primitive_uv_sphere_add(segments=12,ring_count=8,radius=.035)
        point=bpy.context.object;point.name='ScanPoint';point.data.materials.append(W)
        lengths=[0.]
        closed=perimeter+[perimeter[0]]
        for a,c in zip(closed,closed[1:]): lengths.append(lengths[-1]+(Vector(c)-Vector(a)).length)
        for idx,pos in enumerate(closed):
            point.location=pos;point.keyframe_insert(data_path='location',frame=1+48*lengths[idx]/lengths[-1])
        point['visual_only']=True
    elif role == 'cargo':
        b.loft('CargoBay',[(-1.15,.48,-.65,-.22),(1.0,.55,-.65,-.22)],D)
        for sign in (-1,1):
            door=box('CargoDoor',(sign*.27,-.05,-.68),(.53,2.1,.08),A)
            hinge(door,(sign*.55,-.05,-.64),1,[(1,0),(40,0),(80,sign*1.1),(120,0)])
        for y in (-.65,0,.65): box('CargoContainer',(0,y,-.48),(.80,.55,.30),S)
    elif role == 'engineer':
        box('ArmSupportKeel',(0,-.65,-.40),(1.3,.36,.20),S)
        for sign in (-1,1):
            before=set(bpy.context.scene.objects)
            beam('ArmUpper',(sign*.58,-.65,-.44),(sign*1.03,-.85,-.60),.13,S)
            beam('ArmLower',(sign*1.03,-.85,-.60),(sign*.90,-1.3,-.65),.11,A)
            for x,y,z in ((sign*.58,-.65,-.44),(sign*1.03,-.85,-.60),(sign*.90,-1.3,-.65)):
                cyl('ArmJoint',(x,y,z),.13,.10,O)
            for offset in (-.09,.09): beam('Gripper',(sign*.90+offset,-1.3,-.65),(sign*.90+offset,-1.57,-.65),.045,D)
            pieces=set(bpy.context.scene.objects)-before
            bpy.ops.object.empty_add(location=(sign*.58,-.65,-.44));pivot=bpy.context.object;pivot.name='ArmAssemblyPivot'
            for obj in pieces:
                matrix=obj.matrix_world.copy();obj.parent=pivot;obj.matrix_world=matrix
            for frame,angle in ((1,0),(40,0),(80,sign*.95),(120,0)):
                pivot.rotation_euler.y=angle;pivot.keyframe_insert(data_path='rotation_euler',frame=frame)
        cyl('WorkEmitter',(0,-.65,-.56),.12,.05,C)
    elif role == 'security':
        # A lower hemisphere, rim embedded in the ventral keel, not a hanging turret.
        verts=[(0,-.85,-.62)]
        for j in range(1,13):
            theta=(math.pi/2)*j/12
            for i in range(48):
                a=i*math.tau/48
                verts.append((.31*math.sin(theta)*math.cos(a),-.85+.31*math.sin(theta)*math.sin(a),-.31-.31*math.cos(theta)))
        faces=[(0,1+(i+1)%48,1+i) for i in range(48)]
        for j in range(11):
            for i in range(48):
                a=1+j*48+i;c=1+j*48+(i+1)%48;faces.append((a,c,c+48,a+48))
        b.mesh('SmokedEnergyDome',verts,faces,G,0,True)
        p.annular_ring('DomeRetainer',0,-.85,.33,.305,-.325,-.30,D,mirrored=False)
        bpy.ops.mesh.primitive_uv_sphere_add(segments=16,ring_count=8,radius=.018,location=(0,-.85,-.620))
        core=bpy.context.object;core.name='DomeEnergyPoint';core.data.materials.append(W)
        for frame,value in ((1,.001),(40,.001),(65,.3),(80,1),(100,1),(120,.001)):
            core.scale=(value,)*3;core.keyframe_insert(data_path='scale',frame=frame)
        for sign in (-1,1):
            muzzle=cyl('GunMuzzle',(sign*.52,-1.52,-.23),.055,.09,D);muzzle.rotation_euler.x=math.pi/2
            box('DorsalMissileBay',(sign*.34,.93,.52),(.43,.93,.08),D)
            for y in (.63,.89,1.15):
                cyl('LaunchCell',(sign*.34,y,.56),.083,.08,S)
                missile=cyl('MicroMissile',(sign*.34,y,.50),.05,.28,O)
                anim_move(missile,2,[(1,0),(65,0),(80,.50),(100,1.5),(120,0)])
            door=box('MissileHatch',(sign*.34,.93,.62),(.45,.97,.055),A)
            hinge(door,(sign*.58,.93,.62),1,[(1,0),(40,0),(65,-sign*1.3),(100,-sign*1.3),(120,0)])
    elif role == 'salvage':
        box('WinchSupport',(0,0,-.40),(.9,.75,.18),S)
        drum=cyl('WinchDrum',(0,0,-.53),.18,.68,D);drum.rotation_euler.y=math.pi/2
        for x in (-.34,.34):
            flange=cyl('DrumFlange',(x,0,-.53),.22,.06,S);flange.rotation_euler.y=math.pi/2
        for x in (-.24,-.16,-.08,0,.08,.16,.24):
            ring=cyl('CableWinding',(x,0,-.53),.183,.022,S);ring.rotation_euler.y=math.pi/2
        hook=box('RecoveryClamp',(0,-.07,-.78),(.28,.24,.16),O)
        anim_move(hook,2,[(1,0),(40,0),(80,-.60),(120,0)])
        cable=cyl('WinchCable',(0,-.07,-.69),.016,.10,D)
        for frame,extent in ((1,0),(40,0),(80,.60),(120,0)):
            cable.delta_location.z=-extent/2;cable.scale.z=1+extent/.1
            cable.keyframe_insert(data_path='delta_location',frame=frame);cable.keyframe_insert(data_path='scale',frame=frame)
    else:
        box('UtilityServicePanel',(0,-.05,-.35),(.65,1.1,.12),A)
        box('ServiceContact',(.17,-.05,-.42),(.05,.5,.025),O,True,.008)
    # All role size differences come from the same center, preserving bilateral construction.
    for obj in list(bpy.context.scene.objects):
        if obj.parent is None and obj != b.MIRROR_ORIGIN:
            obj.location *= scale
            obj.scale *= scale
    return scale


def equipment(kind):
    if kind == 'micro_missile':
        cyl('MissileBody',(0,0,0),.12,.85,A)
        bpy.ops.mesh.primitive_cone_add(vertices=32,radius1=.12,radius2=0,depth=.30,location=(0,0,.575))
        bpy.context.object.name='MissileNose';bpy.context.object.data.materials.append(D)
        for a in (0,math.pi/2,math.pi,3*math.pi/2):
            beam('TailFin',(.12*math.cos(a),.12*math.sin(a),-.15),(.26*math.cos(a),.26*math.sin(a),-.38),.035,S)
        cyl('Exhaust',(0,0,-.45),.095,.07,O)
    elif kind == 'dock_item':
        for w,z,h,mat in ((7.9,.12,.24,D),(7.6,.31,.22,A),(6.7,.45,.12,S),(5.8,.50,.13,D)):
            box('DockDeck',(0,0,z),(w,w,h),mat,bevel=.28)
        for x in (-2.8,2.8):
            box('ContactRail',(x,0,.60),(.12,3.3,.05),C)
            for y in (-2,2): box('LandingContact',(x,y,.59),(.45,.5,.08),O)
        for angle in (-.65,.65):
            obj=box('LandingCross',(0,0,.58),(2.0,.08,.025),C);obj.rotation_euler.z=angle
        for x in (-3.2,3.2):
            for y in (-2.6,-1.3,0,1.3,2.6): box('EdgeFastener',(x,y,.49),(.17,.11,.09),S)
        for x in (-2.5,2.5):
            for y in (-2.5,2.5):
                box('ServiceDeckPanel',(x,y,.57),(1.35,1.35,.09),A,bevel=.16)
                for off in (-.36,-.12,.12,.36):box('DeckCoolingSlot',(x+off,y,.62),(.065,.70,.025),D,bevel=.012)
                box('DeckServiceLatch',(x,y-.55,.63),(.28,.09,.04),O,bevel=.015)
    elif kind == 'solar_service_station':
        station=module('v8_station_parts','build_solar_service_station_v2.py')
        station.underside()
        for index,degrees in enumerate((150,270,30),1):station.solar_wing(index,math.radians(degrees))
        for index,degrees in enumerate((90,210,330),1):station.service_emitter(index,math.radians(degrees))
        station.central_reactor();station.dorsal_service_detail()
        # Preserve the detailed station mechanisms, enforce the common symmetry plane.
        for obj in list(bpy.context.scene.objects):
            if obj.type not in ('MESH','CURVE'):continue
            mesh=bpy.data.meshes.new_from_object(obj.evaluated_get(bpy.context.evaluated_depsgraph_get()))
            mesh.transform(obj.matrix_world)
            bm=bmesh.new();bm.from_mesh(mesh)
            bmesh.ops.bisect_plane(bm,geom=list(bm.verts)+list(bm.edges)+list(bm.faces),dist=1e-7,plane_co=(0,0,0),plane_no=(1,0,0),clear_inner=True)
            bm.to_mesh(mesh);bm.free()
            replacement=bpy.data.objects.new(obj.name+'_V8',mesh);bpy.context.collection.objects.link(replacement)
            bpy.data.objects.remove(obj,do_unlink=True);b.mirror(replacement)
    elif kind == 'charging_relay':
        b.loft('RelayMonocoque',[(-.8,.22,-.15,.15),(-.45,.55,-.25,.32),(.4,.55,-.23,.31),(.8,.25,-.1,.12)],A)
        b.loft('RelayDorsalSpine',[(-.6,.12,.15,.24),(0,.23,.30,.42),(.55,.15,.24,.32)],S)
        box('RelayVisor',(0,-.79,.04),(.37,.02,.05),C)
        for sign in (-1,1):
            box('RelayThruster',(sign*.46,.37,-.03),(.22,.46,.25),D)
            box('RelayThrusterExit',(sign*.46,.62,-.03),(.15,.03,.12),C)
            for j in range(4):box('RelayFin',(sign*.52,-.27+j*.12,.16),(.08,.05,.12),S)
        p.annular_ring('ChargingOpticRing',0,0,.23,.15,-.32,-.25,S,mirrored=False)
        cyl('ChargingOptic',(0,0,-.32),.14,.025,C)
    elif kind in ('controller','tactical_visor','recovery_tool'):
        if kind=='controller':
            box('ControllerShell',(0,0,0),(2.0,.40,1.25),A,bevel=.16)
            box('ScreenBezel',(0,-.225,.12),(1.32,.055,.80),D,bevel=.06)
            box('Display',(0,-.261,.12),(1.17,.012,.64),PANEL,bevel=.025)
            for z in (-.07,.10,.27):box('ScreenRow',(0,-.272,z),(.85,.01,.025),C,bevel=.005)
            for x in (-.81,.81):
                box('HandGrip',(x,0,-.45),(.32,.48,.85),D,bevel=.12)
                c=cyl('ThumbControl',(x,-.24,.1),.09,.08,S);c.rotation_euler.x=math.pi/2
        elif kind=='tactical_visor':
            b.loft('VisorFrame',[(-.35,.75,-.2,.25),(0,1.05,-.2,.25),(.35,.9,-.15,.2)],A)
            box('SmokedLens',(0,-.37,.015),(1.4,.1,.30),G,bevel=.12)
            box('Temple',(.90,.48,.05),(.16,.9,.22),D,True,.06)
            box('LinkMark',(.68,-.425,.03),(.035,.018,.13),C,True,.008)
        else:
            box('ToolBody',(0,0,0),(.5,.50,1.5),A,bevel=.12)
            box('RecoveryJaw',(.29,0,.72),(.16,.4,.65),S,True)
            box('Grip',(0,0,-.73),(.36,.38,.7),D,bevel=.07)
            box('ToolStatus',(0,-.26,.15),(.12,.025,.4),C)
    else:
        accent=C
        if 'cargo' in kind or 'battery' in kind:accent=O
        if kind == 'raw_morrow_composite':
            for x,z,r in ((-.35,0,.36),(0,.07,.43),(.35,0,.36)):
                bpy.ops.mesh.primitive_uv_sphere_add(segments=8,ring_count=4,radius=r,location=(x,0,z))
                obj=bpy.context.object;obj.name='UnrefinedComposite';obj.data.materials.append(D)
            box('OreSeam',(0,-.31,.10),(.85,.08,.10),O)
        elif kind=='morrow_alloy':
            box('MaterialIngot',(0,0,0),(1.35,.65,.42),S,bevel=.12)
            for x in (-.38,0,.38):box('MaterialBand',(x,-.34,0),(.10,.03,.28),O)
        elif kind=='lightweight_frame':
            for x in (-.5,.5):box('FrameRail',(x,0,0),(.14,.9,.16),S)
            for y in (-.4,.4):box('FrameCross',(0,y,0),(1.1,.14,.16),S)
        elif kind=='basic_control_board':
            box('CircuitBoard',(0,0,0),(1.2,.85,.1),PANEL)
            for x in (-.3,0,.3):box('Processor',(x,0,.12),(.2,.3,.12),D)
            for x in (-.48,.48):box('Bus',(x,0,.09),(.025,.7,.025),O)
        elif kind=='flight_actuator':
            cyl('ActuatorMotor',(0,0,0),.30,.8,S)
            cyl('ActuatorShaft',(0,0,.65),.09,.6,D)
        else:
            count=3 if 'high_density' in kind else 2 if 'reinforced' in kind else 1
            width=.65+.20*(count-1)
            box('Cartridge',(0,0,0),(width,.42,1.12),A,bevel=.09)
            box('CartridgeFace',(0,-.23,0),(width*.8,.04,.9),D)
            for i in range(count):box('CapacityStripe',((i-(count-1)/2)*.16,-.26,.25),(.07,.025,.35),accent,bevel=.01)
            symbol={'scout_module':'lens','engineer_module':'tool','cargo_module':'bay','salvage_module':'hook','security_module':'shield','laser_module':'lens','autocannon_module':'gun','missile_module':'missile'}.get(kind)
            if symbol=='lens':
                xs=(-.10,.10) if kind=='scout_module' else (0,)
                for x in xs:
                    obj=cyl('RoleLens',(x,-.29,-.10),.075 if kind=='scout_module' else .15,.05,G);obj.rotation_euler.x=math.pi/2
            elif symbol=='tool':
                for sign in (-1,1):
                    beam('ToolGlyph',(sign*.09,-.30,.03),(sign*.20,-.30,-.25),.06,S)
                    beam('ToolJawGlyph',(sign*.20,-.30,-.25),(sign*.09,-.30,-.37),.05,O)
            elif symbol=='hook':
                b.curve('HookGlyph',[(0,-.30,.03),(0,-.30,-.29),(-.11,-.30,-.37),(-.21,-.30,-.29)],O,.035)
            elif symbol=='shield':
                b.mesh('ShieldGlyph',[(-.18,-.30,0),(.18,-.30,0),(.16,-.30,-.26),(0,-.30,-.40),(-.16,-.30,-.26)],[(0,1,2,3,4)],S)
            elif symbol=='missile':
                beam('MissileGlyph',(0,-.30,-.38),(0,-.30,-.02),.09,S)
                b.mesh('MissileTip',[(-.09,-.30,-.02),(.09,-.30,-.02),(0,-.30,.12)],[(0,1,2)],O)
            elif symbol=='gun':
                for x in (-.11,0,.11):beam('GunGlyph',(x,-.30,-.36),(x,-.30,.02),.055,S)
            elif symbol=='bay':
                for z in (-.05,-.18,-.31):box('BayGlyph',(0,-.30,z),(.36,.04,.07),O,bevel=.01)
            box('LockTab',(0,0,-.61),(.30,.32,.13),S)


def validation(name):
    scene=bpy.context.scene;scene.frame_set(1);deps=bpy.context.evaluated_depsgraph_get()
    coords=[];nonground=[];feet=[]
    for obj in scene.objects:
        if obj.type!='MESH' or obj.name=='ScanPoint':continue
        ev=obj.evaluated_get(deps);mesh=ev.to_mesh()
        points=[obj.matrix_world@v.co for v in mesh.vertices]
        coords.extend(points)
        (feet if obj.name.startswith('Gear_Foot') else nonground).extend(points)
        ev.to_mesh_clear()
    kd=kdtree.KDTree(len(coords))
    for i,v in enumerate(coords):kd.insert(v,i)
    kd.balance()
    error=max((kd.find(Vector((-v.x,v.y,v.z)))[2] for v in coords),default=0)
    low=min(v.z for v in coords);high=max(v.z for v in coords)
    report={'asset':name,'vertices':len(coords),'mirrorError':error,'symmetryPass':error<.0001,
            'boundsHeight':high-low,'gameVerified':False,'referenceFidelityApproved':False}
    if feet:
        foot=min(v.z for v in feet)
        # Exclude struts/inlays when checking payload ground clearance.
        payload=[]
        for obj in scene.objects:
            if obj.type=='MESH' and not obj.name.startswith('Gear_'):
                ev=obj.evaluated_get(deps);me=ev.to_mesh();payload += [obj.matrix_world@v.co for v in me.vertices];ev.to_mesh_clear()
        report['payloadGroundClearance']=min(v.z for v in payload)-foot
        report['groundClearancePass']=report['payloadGroundClearance']>.05
    return report


def save_asset(name, views=False):
    target=OUT/'models'/name;target.mkdir(parents=True,exist_ok=True)
    report=validation(name)
    scene=bpy.context.scene
    scene.render.engine='BLENDER_EEVEE';scene.render.film_transparent=True
    scene.render.image_settings.file_format='PNG';scene.render.image_settings.color_mode='RGBA'
    scene.render.resolution_percentage=100
    scene.world.use_nodes=True
    scene.world.node_tree.nodes['Background'].inputs[0].default_value=(.35,.38,.42,1)
    scene.world.node_tree.nodes['Background'].inputs[1].default_value=.32
    scene.view_settings.look='AgX - Medium High Contrast'
    scene.view_settings.exposure=-.15
    for loc,power,size in (((-4,-6,8),1700,7),((5,-1,5),1500,6),((0,5,5),1800,5),((0,-3,-4),500,4)):
        bpy.ops.object.light_add(type='AREA',location=loc);li=bpy.context.object;li.data.energy=power;li.data.shape='DISK';li.data.size=size;b.look(li)
    bpy.ops.object.camera_add();camera=bpy.context.object;camera.data.type='ORTHO';scene.camera=camera
    scene.frame_set(1)
    deps=bpy.context.evaluated_depsgraph_get();points=[]
    for obj in scene.objects:
        if obj.type=='MESH': points += [obj.matrix_world@Vector(v) for v in obj.evaluated_get(deps).bound_box]
    span=max(max(v[i] for v in points)-min(v[i] for v in points) for i in range(3))
    center=Vector(tuple((max(v[i] for v in points)+min(v[i] for v in points))/2 for i in range(3)))
    camera.data.ortho_scale=9.2 if name in ROLES else span*1.10
    poses={'hero':(5,-10,4),'front':(0,-12,0),'rear':(0,12,0),'top':(0,0,12),'bottom':(0,0,-12),'left':(-12,0,0),'right':(12,0,0)}
    for key,pos in poses.items():
        if not views and key!='hero':continue
        camera.location=center+Vector(pos);b.look(camera,center)
        scene.render.resolution_x=640;scene.render.resolution_y=640
        scene.render.filepath=str(target/(key+'.png'));bpy.ops.render.render(write_still=True)
    if name in ROLES:
        for frame,label,pos in ((40,'cruise',(5,-9,4)),(80,'deployed',(5,-10,1.5))):
            scene.frame_set(frame);camera.location=center+Vector(pos);b.look(camera,center)
            scene.render.filepath=str(target/(label+'.png'));bpy.ops.render.render(write_still=True)
    scene.frame_set(1)
    camera.location=center+Vector(poses['hero']);b.look(camera,center)
    scene.render.resolution_x=128;scene.render.resolution_y=128
    scene.render.filepath=str(target/'icon.png');bpy.ops.render.render(write_still=True)
    scene.render.resolution_x=640;scene.render.resolution_y=640
    scene.frame_end=120
    bpy.ops.wm.save_as_mainfile(filepath=str(target/(name+'.blend')))
    bpy.ops.object.select_all(action='DESELECT')
    for obj in scene.objects:
        if obj.type in ('MESH','CURVE','EMPTY'):obj.select_set(True)
    bpy.ops.export_scene.gltf(filepath=str(target/(name+'.glb')),export_format='GLB',use_selection=True,export_animations=True)
    (target/'validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print('V8_ASSET',json.dumps(report),flush=True)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--asset');parser.add_argument('--all',action='store_true')
    args=parser.parse_args(sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else [])
    items=sorted(path.stem for path in (ROOT/'src/main/resources/assets/morrowgear_drone/items').glob('*.json'))
    names=list(ROLES)+['charging_relay','micro_missile']+items
    names=[n for n in names if n!='field_drone_unit']
    if args.asset:names=[args.asset]
    for name in names:
        reset()
        if name in ROLES:airframe(name)
        else:equipment(name)
        save_asset(name,name in ROLES or name in ('dock_item','controller','solar_service_station','charging_relay','tactical_visor','recovery_tool'))
    manifest={'assets':names,'itemIds':items,'aliases':{'field_drone_unit':'field'},'legacyItems':['autocannon_module','laser_module','missile_module'],'stagingOnly':True}
    OUT.mkdir(parents=True,exist_ok=True)
    if not args.asset or not (OUT/'manifest.json').exists():
        (OUT/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')


if __name__=='__main__':main()
