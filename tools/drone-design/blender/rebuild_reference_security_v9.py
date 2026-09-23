"""Reference-led single-airframe rebuild, separate from the rejected V8 batch."""
import importlib.util
import json
import math
from pathlib import Path

import bpy
import bmesh
from mathutils import Vector

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('v9_shared',HERE/'build_unified_family_v8.py')
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
b,p=v.b,v.p
OUT=v.ROOT/'docs/design/reference-rebuild-v9';OUT.mkdir(parents=True,exist_ok=True)
ARMOR=b.mat('V9_GraphiteSkin',(.11,.125,.14),.55,.43,weather=True)
PLATE=b.mat('V9_PanelTitanium',(.17,.19,.21),.65,.38,weather=True)
EDGE=b.mat('V9_CutEdge',(.25,.28,.30),.82,.32)
DARK=b.mat('V9_DuctBlack',(.012,.017,.022),.30,.42)
GLASS=b.mat('V9_SmokedGlass',(.004,.007,.011),.18,.11)
CYAN=b.mat('V9_RecessedOptics',(.04,.55,.66),.18,.18,(.08,.8,1))
AMBER=b.mat('V9_Service',(.85,.32,.035),.50,.38)
v.reset()


def box(name,loc,size,mat=ARMOR,pair=False,bevel=.025):
    return b.cube(name,loc,size,mat,bevel,mirrored=pair)


def symmetry_half(obj):
    bm=bmesh.new();bm.from_mesh(obj.data)
    bmesh.ops.bisect_plane(bm,geom=list(bm.verts)+list(bm.edges)+list(bm.faces),plane_co=(0,0,0),plane_no=(1,0,0),dist=1e-7,clear_inner=True)
    bm.to_mesh(obj.data);bm.free();b.mirror(obj)


def ray_edge(poly,cx,cy,a):
    dx,dy=math.cos(a),math.sin(a)
    hits=[]
    for q,r in zip(poly,poly[1:]+poly[:1]):
        sx,sy=r[0]-q[0],r[1]-q[1]
        det=dx*sy-dy*sx
        if abs(det)<1e-8:continue
        px,py=q[0]-cx,q[1]-cy
        t=(px*sy-py*sx)/det;u=(px*dy-py*dx)/det
        if t>0 and -.00001<=u<=1.00001:hits.append(t)
    if not hits:raise RuntimeError('Wing ray does not intersect boundary')
    t=min(hits)
    return cx+t*dx,cy+t*dy


def wing_height(x,y):
    root=.30*max(0,1-(x-.85)/.72)
    tip=.35*max(0,(x-3.72)/.48)
    return .27+root+tip


def wing():
    cx,cy=2.04,.12
    outline=[(.76,-1.74),(1.24,-1.86),(3.85,.20),(4.25,.70),(4.20,1.65),(3.7,1.55),(2.6,1.18),(1.13,1.62),(.78,.62)]
    segments=96
    verts=[]
    for top in (False,True):
        for ring in range(5):
            u=ring/4
            for i in range(segments):
                angle=i*math.tau/segments
                ix,iy=cx+.975*math.cos(angle),cy+.975*math.sin(angle)
                ox,oy=ray_edge(outline,cx,cy,angle)
                x,y=ix*(1-u)+ox*u,iy*(1-u)+oy*u
                z=wing_height(x,y)+(.06*math.sin(u*math.pi) if top else -.29+.14*u)
                verts.append((x,y,z))
    faces=[];layer=5*segments
    for side in (0,1):
        for ring in range(4):
            for i in range(segments):
                j=(i+1)%segments;a=side*layer+ring*segments
                f=(a+i,a+j,a+segments+j,a+segments+i)
                faces.append(f if side else f[::-1])
    for ring in (0,4):
        for i in range(segments):
            j=(i+1)%segments;a=ring*segments
            faces.append((a+i,a+j,a+layer+j,a+layer+i))
    obj=b.mesh('W01_LoftedWingAroundDuct',verts,faces,ARMOR,.015,True);b.mirror(obj)
    for offset in (.04,.14):
        points=[]
        for x,y in outline:
            xx=cx+(x-cx)*(1-offset);yy=cy+(y-cy)*(1-offset)
            points.append((xx,yy,wing_height(xx,yy)+.026))
        b.curve('W02_PerimeterSeam',points,DARK,.009,mirrored=True,cyclic=True)
    # Separate cut-edge strip, not a bright raised tube around the entire duct.
    for index in (0,1,2,3,4):
        x,y=outline[index];xx,yy=outline[index+1]
        strip=b.mesh('W03_LeadingEdge_'+str(index),[(x,y,wing_height(x,y)),(xx,yy,wing_height(xx,yy)),(xx,yy,wing_height(xx,yy)-.045),(x,y,wing_height(x,y)-.045)],[(0,1,2,3)],EDGE)
        b.mirror(strip)
    for a in (30,70,120,170,205,260,300,330):
        angle=math.radians(a);x,y=ray_edge(outline,cx,cy,angle)
        pts=[]
        for i in range(7):
            t=i/6;xx=(cx+1.04*math.cos(angle))*(1-t)+x*t;yy=(cy+1.04*math.sin(angle))*(1-t)+y*t
            pts.append((xx,yy,wing_height(xx,yy)+.02))
        b.curve('W04_RadialPanelSeam',pts,DARK,.008,mirrored=True)
    # Short upswept tip surface follows the outer wing, not an isolated vertical tail.
    tip=b.mesh('W05_UpsweptTip',[(3.85,.35,.38),(4.25,.7,.65),(4.20,1.65,.70),(3.78,1.43,.36)],[(0,1,2,3)],PLATE,.014)
    solid=tip.modifiers.new('TipThickness','SOLIDIFY');solid.thickness=.06;b.mirror(tip)
    b.curve('W06_TipAmber',[(4.08,.70,.67),(4.05,1.35,.69)],AMBER,.016,mirrored=True)
    p.annular_ring('R01_DeepDuct',cx,cy,.973,.92,-.22,.28,DARK,mirrored=True)
    p.annular_ring('R02_ThinDuctLip',cx,cy,1.006,.963,.275,.30,EDGE,mirrored=True)
    p.annular_ring('R03_LowerDuct',cx,cy,.97,.92,-.24,-.19,ARMOR,mirrored=True)
    b.cylinder('R04_Hub',(cx,cy,-.035),.22,.22,DARK,48,True)
    b.cylinder('R05_HubCap',(cx,cy,.1),.16,.07,PLATE,48,True)
    for index in range(12):
        angle=index*math.tau/12;verts=[]
        for depth in (-.023,.023):
            for radius,skew,height in ((.19,-.1,-.035),(.46,-.15,-.075),(.89,-.07,-.045),(.88,.08,.005),(.42,.24,.035),(.20,.27,.02)):
                verts.append((cx+radius*math.cos(angle+skew),cy+radius*math.sin(angle+skew),height+depth))
        faces=[tuple(range(5,-1,-1)),tuple(range(6,12))]
        faces += [(i,(i+1)%6,(i+1)%6+6,i+6) for i in range(6)]
        blade=b.mesh('R06_TwistedBlade_'+str(index),verts,faces,DARK,.008,True);b.mirror(blade)
    for a in range(0,360,45):
        aa=math.radians(a)
        b.cylinder('R07_DuctFastener',(cx+1.04*math.cos(aa),cy+1.04*math.sin(aa),.31),.022,.013,EDGE,12,True)


def fuselage():
    sections=[(-3.0,.44,-.47,.19),(-2.64,.68,-.55,.43),(-1.90,.84,-.58,.72),(-.8,.96,-.56,.98),(.45,.92,-.48,1.04),(1.50,.64,-.32,.86),(2.15,.40,-.20,.58)]
    body=b.loft('F01_MainFuselage',sections,ARMOR,.065)
    b.loft('F02_CrownPanel',[(-2.82,.30,.19,.29),(-2.4,.49,.41,.49),(-1.7,.61,.74,.80),(-.6,.67,.95,1.025),(.65,.61,1.0,1.075),(1.55,.39,.78,.86),(1.93,.25,.60,.67)],PLATE,.022)
    # Chamfered cheek transitions merge the central body into the lifting surface.
    for side in (-1,1):
        verts=[(side*.66,-2.38,-.43),(side*.98,-1.95,-.35),(side*1.18,-1.20,-.12),(side*1.18,.8,.15),(side*.77,1.35,.58),(side*.80,-1.10,.80),(side*.58,-2.38,.44)]
        obj=b.mesh('F03_BlendedCheek',verts,[tuple(range(len(verts)))],ARMOR,.03)
        mod=obj.modifiers.new('CheekThickness','SOLIDIFY');mod.thickness=.08
    # Front intake tunnels have four walls and an unobstructed mouth.
    for side in (-1,1):
        x=side*.91;y=-1.78
        box('I01_IntakeRoof',(x,y,.17),(.42,.82,.08),ARMOR)
        box('I02_IntakeFloor',(x,y,-.25),(.42,.82,.08),ARMOR)
        box('I03_IntakeInner',(x-side*.20,y,-.035),(.055,.82,.40),PLATE)
        box('I04_IntakeOuter',(x+side*.20,y,-.035),(.055,.82,.40),PLATE)
        box('I05_DarkTunnelBack',(x,y+.39,-.035),(.36,.035,.36),DARK)
    for i,(y,z,w) in enumerate(((-2.42,.47,.47),(-1.90,.73,.58),(-1.22,.91,.66),(-.45,1.04,.66),(.35,1.08,.62),(1.10,.98,.47))):
        b.curve('F04_CrownJoint_'+str(i),[(-w,y,z),(0,y+.04,z+.013),(w,y,z)],DARK,.008)
        for x in (-w*.80,w*.80):box('F05_FlushFastener',(x,y+.06,z+.018),(.038,.06,.012),EDGE,bevel=.004)
    for y,z in ((-1.25,.93),(.62,1.09)):
        box('F06_CenterLatch',(0,y,z),(.16,.23,.02),AMBER,bevel=.015)
        box('F07_LatchInset',(0,y,z+.013),(.074,.14,.012),DARK,bevel=.005)
    for sign in (-1,1):
        pts=[(sign*.79,-1.55,.59),(sign*1.01,-.82,.47),(sign*.91,-.50,.60),(sign*.79,-1.55,.59)]
        obj=b.mesh('I06_TopCoolingMouth',pts,[(0,1,2)],DARK,.013)
        b.curve('I07_CoolingRim',pts,EDGE,.012)
    nose=[(-.66,-2.65,.055),(-.44,-3.01,-.04),(0,-3.075,-.07),(.44,-3.01,-.04),(.66,-2.65,.055)]
    b.ribbon('S01_EmbeddedVisorRecess',nose,.057,DARK)
    b.ribbon('S02_EmbeddedVisor',[(x,y-.012,z) for x,y,z in nose],.020,CYAN)
    b.ribbon('S03_SideVisor',[(.66,-2.65,.055),(.73,-2.42,.12),(.79,-2.20,.18)],.018,CYAN);b.mirror(bpy.context.scene.objects.get('S03_SideVisor'))
    box('S04_ChinSensor',(0,-3.035,-.32),(.30,.17,.29),DARK,bevel=.045)
    box('S05_ChinSensorBezel',(0,-3.132,-.32),(.20,.02,.18),EDGE,bevel=.02)
    box('S06_ChinSensorGlass',(0,-3.145,-.32),(.125,.012,.115),CYAN,bevel=.014)
    for x in (-.45,.45):
        obj=b.cylinder('S07_CheekCamera',(x,-2.91,-.26),.045,.038,GLASS,24);obj.rotation_euler.x=math.pi/2
    # Closed dorsal magazine doors remain flush, rather than two boxes on top.
    for sign in (-1,1):
        hatch=box('M01_DorsalMagazine',(sign*.35,1.07,.88),(.42,.69,.06),ARMOR,bevel=.045)
        hatch.rotation_euler.x=math.radians(-13)
        stripe=box('M02_MagazineMark',(sign*.35,1.07,.92),(.033,.38,.014),AMBER,bevel=.004);stripe.rotation_euler.x=math.radians(-13)
    for y in (1.15,1.32,1.49,1.66):
        box('V01_RearCooling',(.55,y,.65-(y-1.15)*.3),(.22,.075,.05),DARK,True,.006)
    box('V02_RearServicePanel',(0,2.165,.13),(.61,.055,.42),DARK,bevel=.03)
    for z in (-.03,.07,.17):box('V03_RearLouver',(0,2.20,z),(.44,.018,.035),PLATE,bevel=.005)
    box('V04_RearLamp',(.25,2.205,.29),(.08,.018,.04),CYAN,True,.005)
    for y in (-1.5,-.65,.2,.9):box('B01_BellyPanel',(0,y,-.54),(.66,.55,.055),ARMOR,bevel=.055)
    return body


def belly():
    center=Vector((0,-2.35,-.47));radius=.30
    verts=[tuple(center+Vector((0,0,-radius)))]
    for j in range(1,17):
        angle=math.pi*j/32
        for i in range(64):
            a=i*math.tau/64
            verts.append(tuple(center+Vector((radius*math.sin(angle)*math.cos(a),radius*math.sin(angle)*math.sin(a),-radius*math.cos(angle)))))
    faces=[(0,1+(i+1)%64,1+i) for i in range(64)]
    for j in range(15):
        for i in range(64):
            k=1+j*64+i;n=1+j*64+(i+1)%64;faces.append((k,n,n+64,k+64))
    b.mesh('L01_BlackHemisphericalCover',verts,faces,GLASS,0,True)
    p.annular_ring('L02_FlushRetainer',0,-2.35,.316,.298,-.49,-.46,DARK,mirrored=False)
    for sign in (-1,1):
        muzzle=b.cylinder('G01_EmbeddedGunMuzzle',(sign*.87,-2.145,-.35),.047,.055,DARK,24);muzzle.rotation_euler.x=math.pi/2
    # Mechanical legs fit wholly above the belly while stowed; root nodes are explicit.
    for y in (-1.63,1.17):
        box('LG01_ClosedGearBay',(.61,y,-.48),(.30,.65,.10),DARK,True,.035)
        box('LG02_GearDoor',(.61,y,-.535),(.29,.62,.035),ARMOR,True,.025)


def setup():
    scene=bpy.context.scene;scene.render.engine='BLENDER_EEVEE'
    scene.render.resolution_x=1400;scene.render.resolution_y=1050;scene.render.resolution_percentage=100
    scene.render.image_settings.file_format='PNG';scene.render.image_settings.color_mode='RGBA'
    scene.render.film_transparent=True
    scene.world.use_nodes=True;scene.world.node_tree.nodes['Background'].inputs[0].default_value=(.20,.22,.25,1);scene.world.node_tree.nodes['Background'].inputs[1].default_value=.32
    scene.view_settings.look='AgX - Medium High Contrast';scene.view_settings.exposure=0
    for loc,energy,size in (((-5,-6,9),1300,7),((6,-1,6),1100,6),((0,6,7),1700,5),((0,-4,-4),350,5)):
        bpy.ops.object.light_add(type='AREA',location=loc);obj=bpy.context.object;obj.data.energy=energy;obj.data.shape='DISK';obj.data.size=size;b.look(obj,(0,0,.1))
    bpy.ops.object.camera_add();camera=bpy.context.object;camera.data.type='ORTHO';camera.data.ortho_scale=10.2;scene.camera=camera
    return camera


fuselage();wing();belly()
report=v.validation('security_v9')
report['visualMatchApproved']=False
report['inferredRatios']={'wingSpan':8.5,'bodyLength':5.2,'ductDiameter':1.95,'centralBodyMaxWidth':1.92,'domeDiameter':.60}
(OUT/'geometry-check.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
camera=setup();scene=bpy.context.scene
views={'hero':(7,-11,9),'front':(0,-14,.4),'rear':(0,14,.4),'top':(0,0,14),'bottom':(0,0,-14),'left':(-14,0,.4),'right':(14,0,.4),'underside':(7,-11,-6)}
for name,loc in views.items():
    camera.location=loc;b.look(camera,(0,-.35,.10));scene.render.filepath=str(OUT/(name+'.png'));bpy.ops.render.render(write_still=True)
camera.location=views['hero'];b.look(camera,(0,-.35,.10))
bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'security-v9.blend'))
bpy.ops.object.select_all(action='DESELECT')
for obj in scene.objects:
    if obj.type in ('MESH','CURVE','EMPTY'):obj.select_set(True)
bpy.ops.export_scene.gltf(filepath=str(OUT/'security-v9.glb'),export_format='GLB',use_selection=True)
print('V9_REPORT',json.dumps(report),flush=True)
