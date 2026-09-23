"""Canonical accessory designs for the approved blended-wing family; no game sync."""
import argparse
import hashlib
import importlib.util
import json
import math
import sys
from pathlib import Path

import bpy
from mathutils import Vector, kdtree

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = ROOT / 'docs/design/equipment-hmi-v23'
spec = importlib.util.spec_from_file_location('detail22', HERE / 'build_detail_scale_v22.py')
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)
v = d.v
ITEMS = (
    'controller', 'tactical_visor', 'recovery_tool', 'scout_module', 'cargo_module',
    'engineer_module', 'security_module', 'salvage_module', 'power_cell',
    'standard_battery_pack', 'reinforced_battery_pack', 'high_density_battery_pack',
    'raw_morrow_composite', 'morrow_alloy', 'lightweight_frame', 'basic_control_board',
    'flight_actuator', 'autocannon_module', 'laser_module', 'missile_module',
    'solar_service_station', 'charging_relay', 'service_light',
)


def setup():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    v.ADDED.clear()
    v.materials()
    v.MIRROR = v.empty('Symmetry_X0')
    v.M['copper'] = v.mat('CopperContacts', (.42, .20, .065), .8, .33)
    v.M['pcb'] = v.mat('CircuitSubstrate', (.025, .13, .10), .18, .53)
    v.M['solar'] = v.mat('SolarSilicon', (.02, .045, .07), .60, .24)
    v.M['screen'] = v.mat('Screen', (.012, .035, .04), .15, .30)
    v.M['label'] = v.mat('CeramicMarkings', (.55, .67, .68), .2, .45)
    v.M['armor'].node_tree.nodes['Principled BSDF'].inputs['Base Color'].default_value=(.018,.024,.029,1)
    v.M['edge'].node_tree.nodes['Principled BSDF'].inputs['Base Color'].default_value=(.10,.14,.16,1)
    # Microfinish is material detail, never a substitute for ports, fins or recesses.
    for key in ('armor', 'edge', 'copper'):
        mat = v.M[key]
        tree = mat.node_tree
        tex = tree.nodes.new('ShaderNodeTexNoise')
        tex.inputs['Scale'].default_value = 170
        bump = tree.nodes.new('ShaderNodeBump')
        bump.inputs['Strength'].default_value = .13
        bump.inputs['Distance'].default_value = .007
        tree.links.new(tex.outputs['Fac'], bump.inputs['Height'])
        tree.links.new(bump.outputs['Normal'], tree.nodes['Principled BSDF'].inputs['Normal'])


def box(name, x, y, z, sx, sy, sz, mat='armor', bevel=.025):
    return v.box(name, (x, y, z), (sx, sy, sz), mat, bevel=bevel)


def shell(name, width, depth, height, z=0, mat='armor', chamfer=.12):
    return d.chamfer_box(name, width, depth, height, min(chamfer, width/3, depth/3), z, mat)


def cyl(name, x, y, z, r, length, mat='edge', axis=(0, 0, 1)):
    return v.cylinder(name, (x, y, z), r, length, mat, axis=axis)


def ring(name, loc, outer, inner, depth, mat='edge', axis=(0, 0, 1)):
    obj = v.sleeve(name, loc, outer, inner, depth, mat, axis)
    for mod in list(obj.modifiers):
        if mod.type == 'MIRROR':
            obj.modifiers.remove(mod)
    return obj


def line(name, points, mat='dark', r=.007):
    return v.line(name, points, mat, r)


def bolts(width, depth, z):
    for x in (-width/2, width/2):
        for y in (-depth/2, depth/2):
            cyl('CaptiveFastener', x, y, z, .022, .012)
            line('FastenerSlot', [(x-.012,y,z+.008),(x+.012,y,z+.008)], r=.003)


def contacts(width, depth, z):
    box('ConnectorRecess', 0, depth/2+.009, z, width*.65, .03, .11, 'dark', .008)
    for i in range(8):
        box('GoldContact', (i-3.5)*width*.065, depth/2+.028, z, width*.035, .011, .065, 'copper', .002)


def cassette(height=.40, width=1.06, depth=.86):
    shell('LowerIsolationSeal', width, depth, .065, 0, 'dark')
    shell('ConformalCassetteShell', width, depth, height-.065, .065)
    shell('ServiceLid', width-.075, depth-.075, .035, height)
    for sign in (-1, 1):
        box('RecessedCoupler', sign*(width/2-.045), .06, height*.50, .10, .43, .19, 'dark')
        box('CouplerRail', sign*(width/2+.006), .06, height*.50, .025, .32, .065, 'edge', .006)
        box('WitnessMark', sign*(width*.34), -.1, height+.039, .055, .21, .008, 'amber', .002)
    bolts(width-.26, depth-.23, height+.043)
    contacts(width, depth, height*.48)


def optic(name, x, y, z, radius, depth=.08, axis=(0,-1,0)):
    ring(name+'Rim', (x,y,z), radius, radius*.80, depth, 'edge', axis)
    cyl(name+'Glass', x, y+.013, z, radius*.77, depth*.7, 'glass', axis)
    ring(name+'Inner', (x,y-.031,z), radius*.52, radius*.48, .01, 'cyan', axis)
    cyl(name+'Pupil', x, y-.025, z, radius*.45, .012, 'glass', axis)


def controller():
    shell('ControllerStructuralCore', 1.55, .96, .12, 0, 'edge')
    shell('ControllerSeal', 1.50, .91, .12, .075, 'rubber')
    shell('ControllerFrontShell', 1.46, .88, .095, .15)
    box('ScreenRecess', 0, -.015, .245, 1.13, .73, .025, 'dark')
    box('ScreenGlass', 0, -.015, .259, 1.055, .655, .012, 'screen', .009)
    for side in (-1, 1):
        grip = shell('IntegratedGrip',.27,1.0,.24,0,'armor',.067)
        grip.location.x=side*.80;grip.location.y=-.055
        grip.rotation_euler.z = -side*.055
        box('GripInsert',side*.80,-.30,.241,.16,.30,.012,'rubber',.023)
        for j in range(5):
            box('GripTexture', side*.80, -.40+j*.045, .249, .13, .010, .005, 'dark', .003)
        cyl('GimbalRecess', side*.752, .13, .249, .093, .018, 'dark')
        cyl('StickCollar', side*.752, .13, .269, .072, .025)
        cyl('ControlStick', side*.752, .13, .302, .048, .035, 'rubber')
        box('GuardedKey', side*.733, -.14, .268, .10, .085, .026, 'amber', .015)
        box('AntennaHinge', side*.64, .48, .105, .17, .15, .18)
        box('Antenna', side*.64, .67, .115, .064, .30, .11, 'dark', .028)
        box('AntennaStatus', side*.64, .66, .173, .015, .19, .01, 'cyan', .005)
        box('RearGrip', side*.58, -.04, -.027, .23, .64, .065, 'rubber')
        for j in range(4):
            box('RearVent', side*(.30+j*.055), .15, -.065, .023, .28, .009, 'dark', .004)
    screen_path=OUT/'hmi/controller-screen.png'
    if screen_path.exists():
        screen=bpy.data.materials.new('V23_ControllerDisplay');screen.use_nodes=True
        tex=screen.node_tree.nodes.new('ShaderNodeTexImage');tex.image=bpy.data.images.load(str(screen_path));tex.image.pack()
        bs=screen.node_tree.nodes['Principled BSDF'];bs.inputs['Roughness'].default_value=.36
        bs.inputs['Emission Strength'].default_value=.45
        screen.node_tree.links.new(tex.outputs['Color'],bs.inputs['Base Color']);screen.node_tree.links.new(tex.outputs['Color'],bs.inputs['Emission Color'])
        v.M['lcd']=screen
        obj=v.mesh('LCDSurface',[(-.52,-.335,.272),(.52,-.335,.272),(.52,.305,.272),(-.52,.305,.272)],[(0,1,2,3)],'lcd')
        uv=obj.data.uv_layers.new(name='ScreenUV')
        for i,co in enumerate(((0,0),(1,0),(1,1),(0,1))):uv.data[i].uv=co
    box('RearBatteryCover', 0, -.045, -.018, .43, .58, .045, 'armor')
    contacts(1.1, .88, .05)
    bolts(1.25, .77, .255)


def visor():
    v.M['visor']=v.mat('OpticalVisor',(.013,.065,.081),.12,.22)
    v.M['visor'].node_tree.nodes['Principled BSDF'].inputs['Transmission Weight'].default_value=.35
    verts = []
    n = 48
    for z in (.05, .43):
        for i in range(n+1):
            t = -1.20 + 2.40*i/n
            notch=.055*max(0,1-abs(t)/.18) if z<.1 else 0
            verts.append((.61*math.sin(t), -.39*math.cos(t), z + .035*abs(math.sin(t))+notch))
    faces = [(i,i+1,n+2+i,n+1+i) for i in range(n)]
    obj = v.mesh('ContinuousSmokedVisor', verts, faces, 'visor', smooth=True)
    solid = obj.modifiers.new('OpticalGlassThickness','SOLIDIFY'); solid.thickness=.018
    for z in (.05,.43):
        pts=[(.615*math.sin(-1.2+2.4*i/n),-.397*math.cos(-1.2+2.4*i/n),z+.035*abs(math.sin(-1.2+2.4*i/n))) for i in range(n+1)]
        line('VisorBrowRim' if z>.1 else 'VisorLowerRim',pts,'edge',.021 if z>.1 else .008)
    for side in (-1,1):
        box('TempleHousing', side*.584, -.005, .278, .16,.35,.29, bevel=.055)
        line('TempleStatus', [(side*.671,-.10,.35),(side*.671,.10,.35)], 'cyan', .009)
        line('TempleArm', [(side*.597,.10,.30),(side*.607,.41,.30),(side*.53,.54,.25)], 'rubber', .055)
        cyl('TempleHinge',side*.671,.05,.26,.055,.024,'edge',(1,0,0))
        box('TempleRelease',side*.678,-.03,.19,.013,.06,.06,'amber',.005)
    line('NoseBridge', [(-.085,-.397,.08),(0,-.418,.14),(.085,-.397,.08)],'rubber',.025)


def recovery():
    cassette(.30, .84, .49)
    grip=box('RecoveryGrip',0,.15,-.24,.25,.26,.50,'rubber',.065)
    grip.rotation_euler.x=-.14
    box('GripHeel',0,.18,-.49,.32,.32,.10,bevel=.045)
    for i in range(6):
        box('GripTread',0,.002,-.05-i*.065,.23,.025,.014,'dark',.003)
    optic('RecoveryScanner',0,-.275,.15,.088)
    for side in (-1,1):
        pivot=v.empty('RecoveryJawHinge', (side*.36,-.15,.13))
        bpy.context.view_layer.update()
        before=set(bpy.context.scene.objects)
        outline=[(.33,-.12),(.48,-.18),(.72,-.43),(.68,-.61),(.60,-.77),(.48,-.78),(.55,-.46),(.37,-.29)]
        verts=[(side*x,y,z) for z in(.05,.23) for x,y in outline]
        faces=[tuple(reversed(range(8))),tuple(range(8,16))]+[(i,(i+1)%8,(i+1)%8+8,i+8) for i in range(8)]
        arm=v.mesh('JawMachinedArm',verts,faces,'armor');bev=arm.modifiers.new('ArmEdge','BEVEL');bev.width=.017;bev.segments=3
        line('JawRecessedCable',[(side*.40,-.22,.243),(side*.62,-.44,.243),(side*.55,-.66,.243)],'dark',.011)
        cyl('ElbowBearing',side*.60,-.43,.249,.066,.026,'edge')
        cyl('ElbowSeal',side*.60,-.43,.266,.041,.010,'dark')
        box('JawContact',side*.46,-.73,.13,.23,.12,.16,'rubber')
        for i in range(4):
            box('JawTooth',side*(.37+i*.06),-.787,.13,.021,.025,.13,'edge',.004)
        for obj in set(bpy.context.scene.objects)-before:
            obj.parent=pivot; obj.matrix_parent_inverse=pivot.matrix_world.inverted()
        cyl('JawPin',side*.37,-.16,.23,.085,.042)
        pivot.rotation_euler.z=0; pivot.keyframe_insert('rotation_euler',frame=1)
        pivot.rotation_euler.z=side*.32; pivot.keyframe_insert('rotation_euler',frame=40)
        pivot.rotation_euler.z=0; pivot.keyframe_insert('rotation_euler',frame=80)


def module(role):
    cassette(.46)
    if role=='scout':
        optic('ReconOptic',0,-.455,.25,.225,.12)
        for side in (-1,1):
            box('SideScanWindow',side*.538,-.015,.29,.024,.25,.10,'glass',.008)
            box('SideScanLight',side*.552,-.015,.29,.008,.18,.023,'cyan',.004)
    elif role=='cargo':
        box('CargoShutterRecess',0,-.438,.25,.83,.03,.37,'dark')
        for j in range(7):
            box('CargoShutterSlat',0,-.46,.085+j*.050,.76,.024,.027,'edge',.005)
        for side in(-1,1):
            box('CargoLatch',side*.43,-.468,.25,.05,.04,.24,'amber',.008)
    elif role=='engineer':
        ring('ToolSocket',(0,-.48,.27),.18,.125,.10,'edge',(0,-1,0))
        cyl('ToolInner',0,-.463,.27,.119,.022,'dark',(0,-1,0))
        ring('ToolTrace',(0,-.485,.27),.083,.067,.012,'cyan',(0,-1,0))
        for side in(-1,1):
            for j in range(4):
                box('HeatFin',side*(.25+j*.068),-.46,.26,.025,.14,.34,'edge',.006)
    elif role=='security':
        for side in(-1,1):
            line('SecurityRecess',[(side*.40,-.445,.36),(0,-.49,.13)],'dark',.022)
            line('SecuritySignature',[(side*.37,-.464,.35),(0,-.511,.15)],'cyan',.008)
        box('ArmoredCore',0,-.44,.32,.14,.025,.15,'armor')
    elif role=='salvage':
        cyl('WinchDrum',0,-.48,.27,.16,.64,'dark',(1,0,0))
        for i in range(23):
            ring('CableWinding',(-.286+i*.026,-.48,.27),.169,.152,.019,'edge',(1,0,0))
        for side in(-1,1):
            box('WinchBearing',side*.37,-.45,.26,.115,.30,.39)
            cyl('WinchAxle',side*.438,-.48,.27,.092,.034,'edge',(1,0,0))
        line('SalvageCable',[(0,-.635,.26),(0,-.64,-.08)],'dark',.020)
        for side in(-1,1):
            line('SymmetricRecoveryClamp',[(side*.10,-.64,-.06),(side*.16,-.64,-.21),(side*.06,-.64,-.28)],'edge',.035)


def inrange(count):
    return range(count)


def battery(tiers):
    for tier in range(tiers):
        z=tier*.18
        shell('BatteryIsolation',1.12,.78,.040,z,'dark')
        shell('BatteryLayer',1.12,.78,.125,z+.04)
        for side in (-1,1):
            box('CellEdgeRail',side*.48,0,z+.10,.060,.55,.105,'edge')
            box('ReleaseWitness',side*.43,-.18,z+.169,.028,.14,.009,'amber',.003)
        box('BatteryChargeGauge',0,-.395,z+.10,.30,.014,.021,'cyan',.006)
    shell('BatteryLid',1.10,.76,.028,tiers*.18-.015,'edge')
    contacts(1.0,.78,.080)
    bolts(.90,.57,tiers*.18+.019)


def cell():
    cyl('CellCore',0,0,.24,.235,.76,'dark',(1,0,0))
    for x in (-.36,-.29,.29,.36):
        ring('CellCage',(x,0,.24),.265,.213,.056,'edge',(1,0,0))
    for x in (-.19,.19):
        ring('CellEnergyRing',(x,0,.24),.243,.230,.027,'cyan',(1,0,0))
    for y,z in ((-.23,.24),(0,.47),(.23,.24),(0,.01)):
        box('CellArmorRail',0,y,z,.59,.065,.065)
    for x in (-.399,.399):
        cyl('CellTerminal',x,0,.24,.11,.025,'copper',(1,0,0))


def materials_item(kind):
    if kind=='morrow_alloy':
        sections=[(-.44,.64,.29),(.42,.64,.29)]
        verts=[(x,y,z) for y in (-.44,.44) for x,z in ((-.40,0),(.40,0),(.31,.30),(-.31,.30))]
        obj=v.mesh('CastAlloyBillet',verts,[(0,3,2,1),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)],'edge')
        bevel=obj.modifiers.new('CastingEdge','BEVEL');bevel.width=.028;bevel.segments=3
        box('AssayInset',0,0,.302,.17,.26,.006,'armor',.008)
    elif kind=='raw_morrow_composite':
        for i in range(5):
            shell('UnfinishedLaminate',.86-i*.035,.80-i*.035,.052,i*.052,'copper' if i%2 else 'armor',.13)
        for side in(-1,1):
            line('RawLamination',[(side*.31,-.29,.267),(side*.27,.12,.267),(side*.15,.27,.267)],'edge',.008)
    elif kind=='lightweight_frame':
        for z in(.03,.43):
            line('FramePerimeter',[(-.47,-.36,z),(.47,-.36,z),(.47,.36,z),(-.47,.36,z),(-.47,-.36,z)],'edge',.039)
        for x in(-.47,.47):
            for y in(-.36,.36):
                line('FrameUpright',[(x,y,.03),(x,y,.43)],'edge',.033)
                box('FrameCorner',x,y,.43,.14,.14,.11,bevel=.026)
        for y in(-.36,.36):
            line('FrameDiagonal',[(-.47,y,.03),(.47,y,.43)],'edge',.026)
            line('FrameDiagonal',[(.47,y,.03),(-.47,y,.43)],'edge',.026)
    elif kind=='basic_control_board':
        shell('ControlPCB',.94,.81,.036,0,'pcb',.075)
        box('ControlCore',0,0,.074,.34,.32,.07,'dark',.012)
        box('CoreCap',0,0,.112,.25,.24,.009,'edge',.012)
        for side in(-1,1):
            box('EdgeConnector',side*.395,.02,.059,.10,.63,.063,'dark',.007)
            for i in range(12):
                y=-.235+i*.043
                box('ConnectorPad',side*.398,y,.099,.069,.014,.012,'copper',.001)
                line('CircuitTrace',[(side*.19,y,.044),(side*.30,y,.044)],'copper',.003)
            for y in(-.31,.31):
                cyl('BoardMount',side*.28,y,.037,.031,.011,'edge')
                box('Capacitor',side*.19,y,.062,.10,.053,.05,'armor',.005)
    elif kind=='flight_actuator':
        ring('ActuatorHousing',(0,0,.28),.37,.275,.33,'armor',(0,-1,0))
        for y in(-.18,.18):
            ring('ActuatorRim',(0,y,.28),.385,.298,.037,'edge',(0,-1,0))
        cyl('ActuatorRotor',0,0,.28,.185,.30,'edge',(0,-1,0))
        ring('ShaftCoupling',(0,-.23,.28),.125,.067,.12,'edge',(0,-1,0))
        for i in range(12):
            a=i*math.tau/12
            x,z=.239*math.sin(a),.28+.239*math.cos(a)
            cyl('CopperWinding',x,0,z,.056,.26,'copper',(0,-1,0))


def legacy_weapon(kind):
    cassette(.39,1.0,.72)
    if kind=='autocannon':
        for side in (-1,1):
            ring('CannonServiceSleeve',(side*.21,-.53,.22),.117,.078,.49,'edge',(0,-1,0))
            ring('Muzzle',(side*.21,-.825,.22),.103,.062,.095,'dark',(0,-1,0))
            for i in range(5):
                ring('BarrelCoolingCollar',(side*.21,-.41-i*.067,.22),.12,.098,.018,'armor',(0,-1,0))
    elif kind=='laser':
        optic('LegacyLaserOptic',0,-.405,.22,.25,.13)
        for side in(-1,1):
            for j in range(4):
                box('LaserHeatFin',side*(.31+j*.05),-.24,.22,.023,.53,.37,'edge',.006)
    else:
        for side in(-1,1):
            box('SealedMicroMissileCanister',side*.23,-.05,.35,.37,.94,.43,bevel=.065)
            box('CanisterCap',side*.23,-.53,.35,.28,.025,.33,'dark',.05)
            box('CanisterWitness',side*.23,-.554,.40,.16,.01,.019,'cyan',.004)


def relay(light=False):
    radius=.16 if light else .25
    ring('RelayDuct',(0,0,.15),radius,radius*.68,.105,'armor')
    ring('RelayEdge',(0,0,.15),radius+.006,radius-.007,.025,'edge')
    cyl('RotorHub',0,0,.15,radius*.20,.065,'edge')
    for i in range(8):
        a=i*math.tau/8
        blade=box('RelayBlade',radius*.43*math.cos(a),radius*.43*math.sin(a),.15,radius*.58,radius*.17,.018,'edge',.005)
        blade.rotation_euler.z=a
    shell('RelayKeel',radius*1.5,radius*2.4,.08,.048,'armor',radius*.3)
    cyl('ChargeOptic',0,0,.025,radius*.42,.028,'cyan')
    for side in(-1,1):
        line('RelayContact',[(side*radius*.60,-radius*.40,.05),(side*radius*.60,radius*.4,.05)],'copper',.012)
    box('RelayCrown',0,.02,.228,radius*.47,radius*1.4,.038,bevel=.02)


def station():
    ring('StationStructuralDeck',(0,0,.24),1.44,.53,.21,'edge')
    ring('StationUpperSkin',(0,0,.36),1.44,.57,.055,'armor')
    ring('StationBellyRim',(0,0,.12),1.40,.53,.08,'armor')
    cyl('StationCore',0,0,.22,.53,.28,'armor')
    cyl('StationServiceCap',0,0,.40,.46,.055,'edge')
    for i in range(12):
        a=i*math.tau/12
        if i in (0,4,8):
            continue
        verts=[]
        for r in(.68,1.36):
            for j in range(7):
                b=a-math.tau/24+.045+(math.tau/12-.09)*j/6
                verts.append((r*math.sin(b),-r*math.cos(b),.403))
        v.mesh('SolarWafer',verts,[(j,j+1,j+8,j+7) for j in range(6)],'solar')
        for j in range(1,5):
            b=a-math.tau/24+.05+(math.tau/12-.10)*j/5
            line('SolarBus',[(.70*math.sin(b),-.70*math.cos(b),.407),(1.32*math.sin(b),-1.32*math.cos(b),.407)],'edge',.002)
    # Three service berths: one front and mirrored rear pair. They are not home docks.
    for a in(0,math.tau/3,-math.tau/3):
        x,y=.96*math.sin(a),-.96*math.cos(a)
        berth=box('RelayBerth',x,y,.413,.53,.63,.055,'dark',.07);berth.rotation_euler.z=a
        for side in(-1,1):
            dx,dy=side*.20*math.cos(a),side*.20*math.sin(a)
            box('BerthContact',x+dx,y+dy,.448,.034,.28,.015,'copper',.007).rotation_euler.z=a
    for i in range(12):
        a=(i+.5)*math.tau/12
        x,y=1.43*math.sin(a),-1.43*math.cos(a)
        ob=box('StationPerimeterCassette',x,y,.25,.40,.21,.23,bevel=.037);ob.rotation_euler.z=a
        ob=box('StationGuideRecess',x*1.078,y*1.078,.25,.31,.021,.058,'dark',.008);ob.rotation_euler.z=a
        ob=box('StationGuide',x*1.088,y*1.088,.25,.24,.018,.025,'cyan',.008);ob.rotation_euler.z=a
        cyl('StationServiceFastener',x*.94,y*.94,.393,.018,.012,'edge')
        line('BellyRadialConduit',[(.76*math.sin(a),-.76*math.cos(a),.049),(1.16*math.sin(a),-1.16*math.cos(a),.049)],'dark',.024)
        for j in(-1,0,1):
            xx,yy=x*.84+j*.055*math.cos(a),y*.84+j*.055*math.sin(a)
            ob=box('BellyHeatSink',xx,yy,.032,.02,.18,.09,'edge',.004);ob.rotation_euler.z=a
    ring('BellyPowerBus',(0,0,.075),.72,.64,.035,'copper')
    ring('BellyCoreLens',(0,0,.053),.39,.32,.026,'cyan')
    for x in(-.27,.27):
        for y in(-.27,.27):
            cyl('CoreFastener',x,y,.017,.034,.03,'edge')
    for side in(-1,1):
        for y in(-.15,-.05,.05,.15):
            box('StationCoreVent',side*.17,y,.433,.18,.024,.01,'dark',.005)
    for a in(0,math.tau/3,-math.tau/3):
        before=set(bpy.context.scene.objects);relay()
        pivot=v.empty('StationBerthRelay')
        for obj in set(bpy.context.scene.objects)-before:
            if obj!=pivot:obj.parent=pivot
        pivot.location=(.96*math.sin(a),-.96*math.cos(a),.45);pivot.rotation_euler.z=a


def symmetry_report():
    points=d.all_points(d.geometry())
    tree=kdtree.KDTree(len(points))
    for i,p in enumerate(points): tree.insert(p,i)
    tree.balance()
    error=max(tree.find(Vector((-p.x,p.y,p.z)))[2] for p in points)
    return {'vertices':len(points),'bounds':d.bounds(points),'mirrorError':error,'passed':error<1e-5}


def canonical_mirror():
    bpy.context.scene.frame_set(1)
    bpy.context.view_layer.update()
    movers=[o for o in bpy.context.scene.objects if o.parent and o.parent.name.startswith('RecoveryJawHinge')]
    graph=bpy.context.evaluated_depsgraph_get()
    copied=[]
    for obj in movers:
        if obj.parent.location.x <= 0:
            continue
        ev=obj.evaluated_get(graph)
        data=bpy.data.meshes.new_from_object(ev,depsgraph=graph)
        data.transform(ev.matrix_world)
        copied.append((obj.name,data,obj.parent))
    for obj in movers:
        bpy.data.objects.remove(obj,do_unlink=True)
    displays=[o for o in bpy.context.scene.objects if o.name=='LCDSurface']
    for obj in displays:
        for col in list(obj.users_collection):col.objects.unlink(obj)
    root=v.empty('V23_EquipmentRoot')
    d.mirror_static_dock(root)
    for obj in displays:
        bpy.context.collection.objects.link(obj);obj.parent=root
    left=next((o for o in bpy.context.scene.objects if o.name.startswith('RecoveryJawHinge') and o.location.x<0),None)
    for name,data,parent in copied:
        for sign,hinge in ((1,parent),(-1,left)):
            newdata=data.copy()
            if sign<0:
                from mathutils import Matrix
                newdata.transform(Matrix.Diagonal((-1,1,1,1)))
                for polygon in newdata.polygons: polygon.flip()
            obj=bpy.data.objects.new(name+('_R' if sign>0 else '_L'),newdata)
            bpy.context.collection.objects.link(obj)
            obj.parent=hinge
            obj.matrix_parent_inverse=hinge.matrix_world.inverted()
    bpy.context.view_layer.update()


def build(item, quick=False):
    setup()
    if item=='controller':controller()
    elif item=='tactical_visor':visor()
    elif item=='recovery_tool':recovery()
    elif item=='power_cell':cell()
    elif item in ('standard_battery_pack','reinforced_battery_pack','high_density_battery_pack'):
        battery(('standard_battery_pack','reinforced_battery_pack','high_density_battery_pack').index(item)+1)
    elif item in ('raw_morrow_composite','morrow_alloy','lightweight_frame','basic_control_board','flight_actuator'):
        materials_item(item)
    elif item in ('autocannon_module','laser_module','missile_module'):legacy_weapon(item.removesuffix('_module'))
    elif item.endswith('_module'):module(item.removesuffix('_module'))
    elif item=='solar_service_station':station()
    else:relay(item=='service_light')
    canonical_mirror()
    bpy.context.view_layer.update()
    # Curved/radial meshes are authored bilaterally; verify, do not hide asymmetry with a render.
    checks=[]
    for frame in (1,40,80):
        bpy.context.scene.frame_set(frame);bpy.context.view_layer.update()
        checks.append({'frame':frame,**symmetry_report()})
    folder=OUT/'models'/item;folder.mkdir(parents=True,exist_ok=True)
    report={'id':item,'status':'design candidate / not installed','checks':checks,'passed':all(c['passed'] for c in checks),
            'axis':{'symmetry':'X=0','front':'-Y','up':'+Z'},'legacy':item in ('autocannon_module','laser_module','missile_module')}
    (folder/'validation.json').write_text(json.dumps(report,indent=2))
    if not report['passed']:raise ValueError((item,checks))
    d.studio();bpy.context.scene.cycles.samples=24
    bb=checks[0]['bounds'];target=tuple((bb[i]+bb[i+3])/2 for i in range(3))
    span=max(bb[i+3]-bb[i] for i in range(3))*1.52
    views=(('hero',(4,-6,4)),('front',(0,-8,0)),('rear',(0,8,0)),('left',(-8,0,0)),
           ('right',(8,0,0)),('top',(0,0,8)),('bottom',(0,0,-8)))
    for name,axis in views[:1] if quick else views:
        d.render(folder/(name+'.png'),axis,span,target=target,size=(768,640),frame=1)
    d.render(folder/'icon-master.png',(4,-6,4),span*.85,target=target,size=(512,512),frame=1)
    if item=='recovery_tool':d.render(folder/'active.png',(4,-6,4),span*1.1,target=target,size=(768,640),frame=40)
    bpy.context.scene.frame_set(1)
    bpy.context.scene['asset_status']='V23_DESIGN_CANDIDATE_NOT_INSTALLED'
    bpy.ops.wm.save_as_mainfile(filepath=str(folder/(item+'.blend')))
    bpy.ops.object.select_all(action='DESELECT')
    for obj in bpy.context.scene.objects:
        if obj.type in('MESH','CURVE','EMPTY'):obj.select_set(True)
    bpy.ops.export_scene.gltf(filepath=str(folder/(item+'.glb')),export_format='GLB',use_selection=True,export_apply=True,
                            export_animations=item=='recovery_tool',export_animation_mode='SCENE',export_frame_range=True,
                            export_anim_scene_split_object=False,export_force_sampling=True)
    report['blendSha256']=hashlib.sha256((folder/(item+'.blend')).read_bytes()).hexdigest()
    report['glbSha256']=hashlib.sha256((folder/(item+'.glb')).read_bytes()).hexdigest()
    (folder/'validation.json').write_text(json.dumps(report,indent=2))
    print('V23_ITEM_DONE',item,flush=True)


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--item',choices=ITEMS)
    parser.add_argument('--quick',action='store_true')
    parser.add_argument('--resume',action='store_true')
    args=parser.parse_args(sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else [])
    for item in ([args.item] if args.item else ITEMS):
        if args.resume and (OUT/'models'/item/(item+'.glb')).exists():continue
        build(item,args.quick)


if __name__=='__main__':main()
