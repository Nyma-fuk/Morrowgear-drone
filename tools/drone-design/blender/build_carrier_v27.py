"""Approved V27 carrier: canonical polygons, native previews and MGM4 resources."""
import argparse
import importlib.util
import json
import math
import os
import shutil
import struct
import sys
from pathlib import Path

sys.dont_write_bytecode=True
import bpy
from mathutils import Vector, Matrix, kdtree
from mathutils.bvhtree import BVHTree

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
OUT=ROOT/'docs/design/carrier-v27'
BEFORE=OUT/'before-functional-detail'
REVISION='V27_FUNCTIONAL_DETAIL_01'
TOP_BVH=None
ASSETS=ROOT/'src/main/resources/assets/morrowgear_drone'
STAGED=OUT/'staged-assets'
REFERENCE=Path('C:/Users/fukud/.codex/generated_images/019ff8b6-65ab-7b61-9327-249478654bda/exec-0f667897-253c-40df-8bf5-f0e636268628.png')
spec=importlib.util.spec_from_file_location('review_helpers',HERE/'build_supply_carrier_v24.py')
q=importlib.util.module_from_spec(spec)
spec.loader.exec_module(q)
e,d,v=q.e,q.d,q.v
spec=importlib.util.spec_from_file_location('mgm4_helpers',HERE/'export_runtime_v23.py')
mgm=importlib.util.module_from_spec(spec)
spec.loader.exec_module(mgm)
SECTIONS=[(-25,.08,2.8,2),(-22,3.8,4.3,1.5),(-17,9.8,5.8,1.4),
          (-12,12.2,6.3,1.5),(-4,13,6.4,1.4),(7,12.8,7.2,1.5),
          (13,11.2,8.3,2.0),(21,9.7,7.6,2.5),(25,9,6.8,3.1)]


def prism(name,outline,bottom,top,mat='armor',paired=False,lod=True):
    obj=q.prism(name,outline,bottom,top,mat,paired)
    obj['lod_keep']=lod
    return obj


def box(name,xyz,size,mat='armor',paired=False,chamfer=.08,lod=True):
    x,y,z=xyz
    w,l,h=size
    c=min(chamfer,w*.2,l*.2)
    xy=[(x-w/2+c,y-l/2),(x+w/2-c,y-l/2),(x+w/2,y-l/2+c),(x+w/2,y+l/2-c),
        (x+w/2-c,y+l/2),(x-w/2+c,y+l/2),(x-w/2,y+l/2-c),(x-w/2,y-l/2+c)]
    return prism(name,xy,z-h/2,z+h/2,mat,paired,lod)


def cylinder(name,xyz,radius,depth,mat='edge',paired=False,n=20,lod=True,axis='Z'):
    x,y,z=xyz
    if axis=='Z':
        return prism(name,[(x+radius*math.cos(i*math.tau/n),y+radius*math.sin(i*math.tau/n)) for i in range(n)],z-depth/2,z+depth/2,mat,paired,lod)
    verts=[(x+radius*math.cos(i*math.tau/n),y+t,z+radius*math.sin(i*math.tau/n)) for t in (-depth/2,depth/2) for i in range(n)]
    faces=[tuple(reversed(range(n))),tuple(range(n,2*n))]+[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]
    obj=v.mesh(name,verts,faces,mat,paired)
    obj['lod_keep']=lod
    return obj


def ring(name,xyz,outer,inner,depth,mat='edge',n=32):
    x,y,z=xyz
    verts=[(x+r*math.cos(i*math.tau/n),y+r*math.sin(i*math.tau/n),z+t) for t in (-depth/2,depth/2) for r in (outer,inner) for i in range(n)]
    faces=[]
    for i in range(n):
        j=(i+1)%n
        faces.extend(((i,j,n+j,n+i),(2*n+i,3*n+i,3*n+j,2*n+j),
                      (i,2*n+i,2*n+j,j),(n+i,n+j,3*n+j,3*n+i)))
    return v.mesh(name,verts,faces,mat)


def loft(name,sections,mat='armor',paired=False,xoffset=0):
    verts=[]
    for y,w,top,bottom in sections:
        h=top-bottom
        half=[(0,top),(.67*w,top),(.87*w,top-.15*h),(w,top-.35*h),
              (w,bottom+.24*h),(.75*w,bottom),(0,bottom)]
        cross=half+[(-x,z) for x,z in reversed(half[1:-1])]
        verts.extend((x+xoffset,y,z) for x,z in cross)
    n=12
    faces=[tuple(reversed(range(n))),tuple(range((len(sections)-1)*n,len(sections)*n))]
    for row in range(len(sections)-1):
        for i in range(n):
            a=row*n+i
            b=row*n+(i+1)%n
            faces.append((a,b,b+n,a+n))
    return v.mesh(name,verts,faces,mat,paired)


def skin(x,y):
    if TOP_BVH is not None:
        hit,_,_,_=TOP_BVH.ray_cast(Vector((abs(x),y,30)),Vector((0,0,-1)))
        if hit is not None:
            return hit.z
    for a,b in zip(SECTIONS,SECTIONS[1:]):
        if y<=b[0]:
            t=(y-a[0])/(b[0]-a[0])
            w,top,bottom=[a[k]+t*(b[k]-a[k]) for k in (1,2,3)]
            u=abs(x)/w
            return top if u<=.67 else top-(top-bottom)*(.15*(u-.67)/.20 if u<=.87 else .15+.20*(u-.87)/.13)
    return SECTIONS[-1][2]


def panel(name,outline,mat='panel',offset=.035,lod=False):
    n=len(outline)
    vertices=[(x,y,skin(x,y)+h) for h in (offset-.055,offset) for x,y in outline]
    faces=[tuple(reversed(range(n))),tuple(range(n,2*n))]+[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]
    obj=v.mesh(name,vertices,faces,mat,True)
    obj['lod_keep']=lod
    return obj


def underside(x,y):
    for a,b in zip(SECTIONS,SECTIONS[1:]):
        if y<=b[0]:
            t=(y-a[0])/(b[0]-a[0])
            w,top,bottom=[a[k]+t*(b[k]-a[k]) for k in (1,2,3)]
            return bottom+max(0,abs(x)/w-.75)/.25*.24*(top-bottom)
    return SECTIONS[-1][3]


def belly_panel(name,outline,mat='panel',offset=.045,lod=False):
    n=len(outline)
    vertices=[(x,y,underside(x,y)+h) for h in (-offset,.025) for x,y in outline]
    faces=[tuple(reversed(range(n))),tuple(range(n,2*n))]+[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)]
    obj=v.mesh(name,vertices,faces,mat,True)
    obj['lod_keep']=lod
    return obj


def recessed_hull_panel(body,name,outline,depth=.14):
    def patch(name,outline,low,high,material):
        p=[Vector(point) for point in outline]
        xy=[]
        for row in range(3):
            for column in range(3):
                u,t=column/2,row/2
                xy.append(p[0]*(1-u)*(1-t)+p[1]*u*(1-t)+p[2]*u*t+p[3]*(1-u)*t)
        vertices=[(point.x,point.y,skin(point.x,point.y)+height) for height in (low,high) for point in xy]
        faces=[]
        for row in range(2):
            for column in range(2):
                a=row*3+column
                face=(a,a+1,a+4,a+3)
                faces.extend((tuple(reversed(face)),tuple(i+9 for i in face)))
        boundary=(0,1,2,5,8,7,6,3)
        for a,b in zip(boundary,boundary[1:]+boundary[:1]):
            faces.append((a,b,b+9,a+9))
        return v.mesh(name,vertices,faces,material,True)
    cutter=patch(name+'Void',outline,-depth,.7,'dark')
    q.subtract(body,cutter)
    cx=sum(x for x,_ in outline)/len(outline)
    cy=sum(y for _,y in outline)/len(outline)
    inset=[(cx+(x-cx)*.94,cy+(y-cy)*.94) for x,y in outline]
    patch(name,inset,-depth-.015,-depth+.035,'panel')


def rectangular_ring(name,xyz,outer,inner,depth,mat='edge',paired=False,lod=True):
    cx,cy,z=xyz
    def outline(width,length):
        c=.15
        return [(-width/2+c,-length/2),(width/2-c,-length/2),(width/2,-length/2+c),
                (width/2,length/2-c),(width/2-c,length/2),(-width/2+c,length/2),
                (-width/2,length/2-c),(-width/2,-length/2+c)]
    vertices=[(cx+x,cy+y,z+h) for h in (-depth/2,depth/2) for size in (outer,inner) for x,y in outline(*size)]
    faces=[]
    for i in range(8):
        j=(i+1)%8
        faces.extend(((i,j,8+j,8+i),(16+i,24+i,24+j,16+j),
                      (i,16+i,16+j,j),(8+i,8+j,24+j,24+i)))
    obj=v.mesh(name,vertices,faces,mat,paired)
    obj['lod_keep']=lod
    return obj


def pipe(name,points,radius=.07,mat='edge',paired=True,lod=False):
    points=[Vector(p) for p in points]
    vertices=[]
    n=8
    for i,p in enumerate(points):
        tangent=(points[min(i+1,len(points)-1)]-points[max(0,i-1)]).normalized()
        helper=Vector((0,0,1)) if abs(tangent.z)<.9 else Vector((0,1,0))
        normal=tangent.cross(helper).normalized()
        binormal=tangent.cross(normal).normalized()
        vertices.extend(p+radius*(math.cos(j*math.tau/n)*normal+math.sin(j*math.tau/n)*binormal) for j in range(n))
    faces=[tuple(reversed(range(n))),tuple(range(len(vertices)-n,len(vertices)))]
    faces.extend((row*n+i,row*n+(i+1)%n,(row+1)*n+(i+1)%n,(row+1)*n+i)
                 for row in range(len(points)-1) for i in range(n))
    obj=v.mesh(name,vertices,faces,mat,paired)
    obj['lod_keep']=lod
    return obj


def bay(row,cx,cy,width,length):
    lip=underside(cx,cy)
    rectangular_ring('ServiceMouthArmor'+row,(cx,cy,lip+.04),(width+.42,length+.42),(width-.05,length-.05),.22,'edge',True)
    rectangular_ring('ServiceMouthSeal'+row,(cx,cy,lip+.18),(width-.03,length-.03),(width-.20,length-.20),.16,'dark',True)
    box('ServiceCeiling'+row,(cx,cy,3.7),(width-.10,length-.10,.12),'dark',True)
    box('ServiceOverheadUtilityTrunk'+row,(cx,cy,3.54),(.60,length-.4,.16),'panel',True)
    for x in (cx-width/2+.16,cx+width/2-.16):
        box('ServiceCaptureRail'+row,(x,cy,2.6),(.24,length-.3,1.9),'edge',True)
        for y in (cy-length/2+.55,cy+length/2-.55):
            box('ServiceContact'+row,(x,y,2.62),(.30,.42,.14),'amber',True)
            box('ServiceGuideLamp'+row,(x,y,lip-.095),(.20,.65,.025),'warm',True,.02)
        box('ServiceWallUtilityCabinet'+row,(x,cy,2.75),(.30,1.3,.66),'panel',True)
        inner=x+(.17 if x<cx else -.17)
        box('ServicePowerSocket'+row,(inner,cy-.34,2.90),(.055,.31,.22),'cyan',True,.01)
        box('ServiceAmmoTransferPort'+row,(inner,cy+.34,2.70),(.07,.38,.28),'dark',True,.02)
        box('ServiceAmmoTransferLatch'+row,(inner,cy+.35,2.71),(.09,.22,.06),'amber',True,.01)
        pipe('ServicePowerCoolantLine'+row,[(x,cy-.6,2.84),(x,cy-1.0,3.52),
             (cx+(-.4 if x<cx else .4),cy-1.0,3.52)],.065,'edge',True)
        for y in (cy-length/2+.75,cy+length/2-.75):
            box('ServiceCaptureStop'+row,(x,y,2.15),(.36,.32,.23),'dark',True,.04)
    for y in (cy-length/2-.10,cy+length/2+.10):
        box('ServiceThresholdHazard'+row,(cx,y,lip-.081),(width-.75,.16,.035),'amber',True,.03)
        for dx in (-1.3,-.7,0,.7,1.3):
            box('ServiceThresholdNotch'+row,(cx+dx,y,lip-.106),(.20,.18,.025),'dark',True,.0,False)
    box('ServiceCeilingLamp'+row,(cx,cy-length/2+.20,3.56),(width-.8,.12,.08),'cyan',True)


def flight_deck():
    box('RecessedFlightDeck',(6.35,-3.45,4.63),(6.0,22.1,.08),'deck',True,.20)
    box('HangarFloor',(6.35,10.15,4.63),(6.0,5.0,.08),'deck',True)
    box('HangarRearWall',(6.35,12.55,6.0),(6.0,.17,2.7),'dark',True)
    for x in (3.45,9.25):
        box('DeepHangarJamb',(x,7.78,6.0),(.32,.45,2.8),'edge',True)
        for y in (-11.0,-5,1.0,7.5,11.7):
            box('DeckWallBrace',(x,y,5.30),(.24,.38,1.22),'edge',True,.04)
            box('DeckWorklight',(x,y-.18,5.65),(.10,.09,.20),'warm',True,.01)
    box('HangarArmoredLintel',(6.35,7.82,7.32),(6.45,.55,.30),'panel',True,.10)
    box('HangarEntryLight',(6.35,7.50,7.15),(5.25,.05,.08),'warm',True,.02)
    for y in (8.7,10.4,12.0):
        box('HangarRoofRib',(6.35,y,7.3),(5.8,.25,.18),'edge',True,.03)
    for x in (4.35,8.35):
        box('LaunchLane',(x,-3.5,4.69),(.075,20.4,.025),'mark',True,.01,False)
    for y in (-13.2,6.0):
        box('DeckThreshold',(6.35,y,4.70),(3.9,.10,.025),'amber',True,.01,False)
    for i,y in enumerate((-11.9,-5.0,1.9)):
        box('DeckExpansionJoint',(6.35,y,4.685),(5.80,.04,.02),'dark',True,.0,False)
        for x in (4.52,8.18):
            box('TieDown',(x,y+.65,4.705),(.14,.24,.03),'edge',True,.02,False)
    for x,y in ((6.2,-11),(7.0,4.2)):
        box('DeckMaintenanceSeal',(x,y,4.682),(1.4,1.65,.024),'dark',True,.10,False)
        box('DeckMaintenanceLid',(x,y,4.694),(1.18,1.42,.018),'panel',True,.08,False)


def citadel():
    loft('LongArmoredCentralCitadel',[(-24.75,.035,3.25,2.7),(-21,.58,5.3,3.2),
         (-17,1.45,6.8,4.6),(-11,2.18,7.75,5.7),(-2,2.48,8.85,5.9),
         (6,2.35,9.30,6.0),(11,1.95,9.10,6.0),(17,.8,8.3,6.0)],'panel')
    loft('UncrewedSensorCrown',[(3.8,.8,8.6,8.15),(6,1.2,10.2,8.3),(10,1.05,10.3,8.5),(12,.55,9.8,8.5)],'armor')
    box('SensorRecess',(0,4.00,8.83),(1.45,.17,.50),'dark')
    box('SensorGlass',(0,3.90,8.82),(1.1,.045,.23),'glass')
    box('SensorCyanDatum',(0,3.875,8.72),(.72,.02,.05),'cyan',False,.01)
    box('CitadelNoseRecess',(0,-16.5,6.0),(1.2,.35,.30),'dark')
    box('CitadelNoseLight',(0,-16.70,6.0),(.8,.04,.09),'cyan',False,.01)
    for x in (.62,):
        box('SensorMastBase',(x,9.0,10.26),(.35,.80,.18),'edge',True)
        box('SensorMast',(x,9.0,10.69),(.10,.18,.62),'armor',True,.02)
    for x in (2.55,):
        for y in (-7,-2,3):
            box('CitadelButtress',(x,y,6.25),(.55,.75,1.3),'edge',True,.13)


def engine_pods():
    body=loft('MassiveAftEnginePod',[(8.8,1.7,7.65,4.1),(12.0,2.5,8.3,4.1),(16,3.2,10.0,4.0),
               (22.7,3.0,9.8,3.3),(25,2.75,8.7,3.1)],'armor',True,7.0)
    q.subtract(body,box('EngineTopVentVoid',(7.0,18.9,10),(4.5,6.2,2.0),'dark',False,.12))
    q.subtract(body,box('EngineExhaustVoid',(7.0,24.7,6.7),(4.50,2.1,3.1),'dark',False,.14))
    box('EngineTopVentBed',(7.0,18.9,9.07),(4.4,6.0,.08),'dark',True,.05)
    for j in range(11):
        box('EngineIntakeLouver',(7,16.25+j*.52,9.39),(4.20,.20,.28),'edge',True,.03)
    box('EngineExhaustBack',(7,23.70,6.7),(4.4,.10,3.0),'dark',True,.10)
    for z in (5.9,6.7,7.5):
        box('AmberExhaustCore',(7,23.78,z),(3.7,.08,.16),'warm',True,.02)
    for x in (5.2,8.8):
        box('ExhaustVerticalFrame',(x,24.45,6.7),(.22,.45,2.9),'edge',True,.05)
    box('EngineVentAmberHeader',(7,15.91,9.70),(3.5,.10,.06),'amber',True,.01)
    for y,w,h in ((24.00,4.18,2.88),(24.78,4.38,3.04)):
        for x in (7-w/2,7+w/2):
            box('PropulsionNozzleJamb',(x,y,6.7),(.11,.18,h),'panel',True,.025)
        for z in (6.7-h/2,6.7+h/2):
            box('PropulsionNozzleLip',(7,y,z),(w,.18,.10),'edge',True,.025)
    for x in (6.0,8.0):
        box('PropulsionExhaustStator',(x,24.08,6.7),(.09,.43,2.50),'panel',True,.02)
    for x in (4.80,9.20):
        pipe('EngineHeatExchangerManifold',[(x,15.95,9.10),(x,17.0,9.45),
             (x,21.8,9.38),(x,23.1,8.60)],.095,'panel',True)
    for y in (16.7,21.9):
        box('HeatExchangerHeader',(7,y,9.32),(4.10,.18,.30),'panel',True,.025)


def finish_surfaces():
    # Native relief establishes scale; coating is baked from these source materials.
    loft('CitadelSensorTerrace',[(-8,.9,8.02,7.6),(-3,1.32,9.03,8.4),
         (1.5,1.23,9.7,8.75),(5,.8,9.72,9.05)],'armor')
    loft('ForwardChineInlay',[(-24.45,.025,3.38,3.30),(-21,.30,5.35,5.29),
         (-17,.74,6.85,6.76),(-12,1.1,7.65,7.55)],'armor')
    for y,z,w in ((-15,7.2,1.4),(-10,8.08,1.8),(-4,9.02,2.0),(0,9.75,1.7)):
        box('CitadelTerraceServiceSeal',(0,y,z),(w,1.15,.10),'dark',False,.13,False)
        box('CitadelTerraceServicePlate',(0,y,z+.055),(w-.16,.93,.06),'panel',False,.11,False)
    for y,z in ((5.1,9.82),(8.9,10.34)):
        cylinder('SensorRadome',(0,y,z),.38,.31,'edge',False,16)
        box('SensorRadomeCap',(0,y,z+.23),(.41,.50,.20),'dark',False,.09)
    for x,y,z in ((.72,5.6,10.18),(.80,10.2,10.40)):
        cylinder('TelemetryPillar',(x,y,z),.052,.80,'edge',True,8)
        box('TelemetryCrossbar',(x,y,z+.21),(.45,.08,.08),'dark',True,.01)
    box('SideSensorFace',(1.13,7.3,9.77),(.09,1.5,.44),'dark',True,.02)
    for y in (6.8,7.25,7.7):
        box('SideSensorAperture',(1.182,y,9.80),(.018,.23,.19),'glass',True,.01)
    for x in (3.28,9.42):
        for z in (5.13,5.36):
            cylinder('FlightDeckServicePipe',(x,-2.8,z),.055,21.5,'edge',True,8,False,'Y')
        for y in (-12,-8,-4,0,4,7):
            box('PipeSupport',(x,y,5.28),(.17,.11,.53),'dark',True,.02,False)
    for y in (-10.2,-6.8,-3.4,0,3.4):
        box('DeckCenterDrain',(6.35,y,4.688),(.16,2.55,.022),'dark',True,.02,False)
        for x in (4.0,8.72):
            box('DeckPanelSeal',(x,y,4.70),(.78,2.40,.045),'dark',True,.06,False)
            box('DeckPanelPlate',(x,y,4.73),(.65,2.25,.025),'panel',True,.05,False)
        box('LaunchCenterDash',(6.35,y,4.715),(.06,1.45,.035),'mark',True,.01,False)
    for y in (-12.9,5.8):
        for j in range(6):
            box('LaunchThresholdStripe',(4.58+j*.7,y,4.73),(.31,.42,.024),'mark',True,.01,False)
    # Mirrored geometric stencil strokes preserve the exact X0 contract.
    for dx in (-.25,.25):
        box('LaneZeroStroke',(5.87+dx,-12.0,4.74),(.08,.85,.02),'mark',True,.0,False)
    for dy in (-.41,.41):
        box('LaneZeroStroke',(5.87,-12+dy,4.74),(.5,.08,.02),'mark',True,.0,False)
    box('LaneOneStroke',(6.75,-12.0,4.74),(.10,.85,.02),'mark',True,.0,False)
    panel('ForwardShoulderRecess',[(3.1,-17.6),(4.35,-17.8),(6.0,-15.55),(3.5,-14.8)],'dark',.045)
    panel('ForwardShoulderArmor',[(3.25,-17.45),(4.26,-17.64),(5.7,-15.6),(3.65,-15.02)],'panel',.10)
    for y in (11.7,14.3,17.0,20.0):
        panel('RearSpineSeal',[(1.6,y-1),(3.8,y-.8),(3.7,y+.8),(1.6,y+1)],'dark',.04)
        panel('RearSpinePanel',[(1.7,y-.88),(3.66,y-.7),(3.57,y+.68),(1.7,y+.86)],'panel',.09)
    for y,z in ((12.4,8.45),(14.1,9.18)):
        box('EngineRampAccessSeal',(7,y,z),(3.3,.82,.08),'dark',True,.12,False)
        box('EngineRampAccess',(7,y,z+.055),(3.05,.64,.06),'panel',True,.10,False)
    for y in (16.5,19.0,21.5):
        box('EngineOuterReinforcement',(10.12,y,7.50),(.17,.36,2.45),'panel',True,.04)
    for x in (4.65,9.35):
        box('EngineLongArmorRail',(x,19.5,9.58),(.12,6.0,.17),'panel',True,.03)
    for y in (-10,-4,2,7):
        x=12.25 if y<0 else 12.15
        box('FlankArmoredFrame',(x,y,3.50),(.35,.37,2.65),'panel',True,.06)
        box('FlankServiceRecess',(x-.05,y+1.1,3.02),(.31,1.4,.45),'dark',True,.05)
    for y,length in ((-15,4.0),(-10,2.0),(13.4,2.1),(22.6,3.0)):
        outline=[(.08,y-length/2),(1.42,y-length/2+.15),(1.55,y+length/2-.15),(.08,y+length/2)]
        belly_panel('BellyEquipmentBusSeal',outline,'dark',.025)
        inset=[(.17,y-length/2+.12),(1.32,y-length/2+.24),(1.42,y+length/2-.25),(.17,y+length/2-.1)]
        belly_panel('BellyEquipmentBusCover',inset,'panel',.085)
    route=[(7.15,-2.02),(7.15,2.7),(5.9,5.65)]
    pipe('InterBayPowerServiceTrunk',[(x,y,underside(x,y)-.05) for x,y in route],.10,'edge',True,True)
    for y in (-.6,1.3):
        box('InterBayTrunkClamp',(7.15,y,underside(7.15,y)-.075),(.30,.20,.15),'panel',True,.025)
        box('InterBayPowerDatum',(7.15,y,underside(7.15,y)-.159),(.17,.08,.018),'cyan',True,.01)


def weapons_and_panels(body):
    for x,y,z in ((5.7,-17.2,5.52),(3.25,9.0,8.02)):
        cylinder('NavigationSensorSeat',(x,y,z),.85,.30,'edge',True,16)
        box('NavigationSensorHousing',(x,y-.12,z+.42),(1.2,1.65,.70),'armor',True,.22)
        box('NavigationSensorRecess',(x,y-.96,z+.40),(.92,.06,.34),'dark',True,.03)
        for dx in (-.24,.24):
            cylinder('NavigationSensorOptic',(x+dx,y-1.0,z+.40),.105,.045,'glass',True,12,True,'Y')
    for y in (-11.0,-5.4,.2,5.7):
        outer=min(12.5,12.15+(y+11)*.016)
        outline=[(9.83,y-1.9),(outer-.35,y-1.25),(outer,y+.75),(10.0,y+1.5)]
        recessed_hull_panel(body,'OuterRecessedServiceArmor',outline)
    for x,y in ((8.8,-15.5),(10.8,8.8),(4.0,-20.0)):
        z=skin(x,y)
        box('RecessedVentHousing',(x,y,z+.08),(1.05,2.5,.15),'dark',True,.12)
        for j in range(6):
            box('SideCoolingFin',(x,y-.95+j*.38,z+.18),(.88,.12,.13),'edge',True,.015,False)
    for y in (-9,-2,5):
        x=12.7 if y>-7 else 12.4
        box('FlankSensorBed',(x,y,3.62),(.12,1.65,.38),'dark',True,.02)
        box('FlankCyanLight',(x+.06,y,3.62),(.025,1.05,.10),'cyan',True,.01)


def boarding_receiver(body):
    q.subtract(body,box('BoardingReceiverVoid',(0,18,2.0),(3.5,3.6,5.4),'dark',False,.18))
    lip=underside(0,18)
    rectangular_ring('BoardingReceiverFrame',(0,18,lip+.04),(3.92,4.02),(3.47,3.57),.27,'edge')
    rectangular_ring('BoardingReceiverSeal',(0,18,lip+.24),(3.49,3.59),(3.23,3.33),.19,'dark')
    box('BoardingReceiverCeiling',(0,18,4.70),(3.35,3.45,.16),'dark')
    for x in (1.64,):
        box('BoardingVerticalGuide',(x,18,3.42),(.13,3.34,2.20),'panel',True,.035)
        for y in (16.52,19.48):
            box('BoardingReceiverBeacon',(x,y,lip-.103),(.16,.33,.028),'boarding',True,.025)
    for y in (16.15,19.85):
        box('BoardingLateralBeacon',(0,y,lip-.113),(2.56,.09,.022),'boarding',False,.01)
    box('BoardingCeilingLight',(0,18,4.585),(.15,2.85,.05),'boarding',False,.015)
    for x in (-.7,.7):
        box('BoardingCeilingHandrail',(x,18,4.55),(.09,2.50,.11),'edge',False,.02)
    # This receiver marks the existing ground-to-cabin transfer, not an animated lift.
    arrow=[(-.12,21.80),(.12,21.80),(.12,21.06),(.48,21.06),(0,20.54),(-.48,21.06),(-.12,21.06)]
    prism('BoardingAftDirection',arrow,underside(0,21.2)-.075,underside(0,21.2)-.040,'boarding')
    for x in (2.02,):
        pipe('BoardingPowerConduit',[(x,19.7,lip+.3),(x,21.1,underside(x,21.1)),
             (x,22.2,underside(x,22.2)+.12)],.075,'edge',True)
    panel('BoardingRoofServiceSeal',[(.06,16.15),(1.62,16.3),(1.62,19.70),(.06,19.85)],'dark',.06)
    panel('BoardingRoofServicePanel',[(.14,16.30),(1.47,16.44),(1.47,19.54),(.14,19.70)],'panel',.10)


def emitter_structure():
    cylinder('IntegratedEmitterHousing',(0,0,1.2),3.00,1.5,'armor',False,24)
    ring('EmitterArmoredRing',(0,0,.52),2.86,2.18,.35)
    ring('EmitterFocusSeat',(0,0,.26),2.46,2.10,.19,'dark')
    cylinder('IntegratedEmitterLens',(0,0,.15),2.10,.09,'glass',False,48)
    ring('EmitterOpticalRetainer',(0,0,.18),2.16,2.02,.055,'edge')
    ring('EmitterBellyDatum',(0,0,.08),1.35,1.23,.16,'dark')
    ring('EmitterCoolantManifold',(0,0,1.04),3.46,3.27,.20,'panel',24)
    for y in (-1.10,1.10):
        box('EmitterLoadBearingSaddle',(3.0,y,1.12),(.55,.48,.91),'edge',True,.10)
        pipe('EmitterCoolantReturn',[(3.10,y,.75),(3.72,y*1.8,1.0),
             (4.05,y*2.7,underside(4.05,y*2.7)+.03)],.095,'edge',True)
    for i in range(12):
        a=i*math.tau/12
        x,y=2.65*math.cos(a),2.65*math.sin(a)
        box('EmitterFocusClamp',(x,y,.41),(.20,.19,.23),'panel',False,.025,False)
        x,y=2.29*math.cos(a),2.29*math.sin(a)
        box('EmitterCyanSegment',(x,y,.149),(.15,.12,.033),'cyan',False,.02)
    for y in (-3.35,3.35):
        box('EmitterPowerCoupler',(0,y,.89),(1.12,.34,.61),'dark',False,.08)
        for x in (.29,):
            box('EmitterPowerBus',(x,y,.60),(.17,.38,.10),'amber',True,.015)


def build():
    global TOP_BVH
    TOP_BVH=None
    OUT.mkdir(parents=True,exist_ok=True)
    e.setup()
    bpy.context.preferences.filepaths.save_version=0
    v.M['panel']=v.mat('CarrierCastPanel',(.047,.057,.066),.52,.51)
    v.M['deck']=v.mat('CarrierFlightDeck',(.052,.060,.066),.25,.65)
    v.M['mark']=v.mat('CarrierPaleLane',(.33,.37,.38),.15,.6)
    v.M['warm']=v.mat('CarrierWarmIllumination',(.66,.28,.055),.1,.42)
    v.M['boarding']=v.mat('CarrierBoardingCyan',(.02,.35,.40),.1,.45,(.02,.65,.8))
    for key in ('armor','panel','deck'):
        material=v.M[key]
        nodes,links=material.node_tree.nodes,material.node_tree.links
        bsdf=nodes['Principled BSDF']
        base=tuple(bsdf.inputs['Base Color'].default_value)
        coordinates=nodes.new('ShaderNodeTexCoord')
        absolute=nodes.new('ShaderNodeVectorMath')
        absolute.operation='ABSOLUTE'
        links.new(coordinates.outputs['Object'],absolute.inputs[0])
        noise=nodes.new('ShaderNodeTexNoise')
        noise.inputs['Scale'].default_value=3.0
        links.new(absolute.outputs[0],noise.inputs['Vector'])
        ramp=nodes.new('ShaderNodeValToRGB')
        for element,factor in zip(ramp.color_ramp.elements,(.75,1.12)):
            element.color=(*(channel*factor for channel in base[:3]),1)
        links.new(noise.outputs['Fac'],ramp.inputs['Fac'])
        links.new(ramp.outputs['Color'],bsdf.inputs['Base Color'])
        roughness=nodes.new('ShaderNodeMapRange')
        roughness.inputs['From Min'].default_value=0
        roughness.inputs['From Max'].default_value=1
        roughness.inputs['To Min'].default_value=.43
        roughness.inputs['To Max'].default_value=.63
        links.new(noise.outputs['Fac'],roughness.inputs['Value'])
        links.new(roughness.outputs[0],bsdf.inputs['Roughness'])
    node=v.M['warm'].node_tree.nodes['Principled BSDF']
    node.inputs['Emission Color'].default_value=(1,.50,.13,1)
    node.inputs['Emission Strength'].default_value=1.2
    body=loft('CarrierContinuousHull',SECTIONS)
    for row,cx,cy,w,l in q.BAY_ROWS:
        for sign in (-1,1):
            q.subtract(body,box('ServicePocket',(sign*cx,cy,.2),(w,l,6.9),'dark',False,.16))
        bay(row,cx,cy,w,l)
    for sign in (-1,1):
        q.subtract(body,box('OpenFlightDeckWell',(sign*6.35,-3.35,11.6),(6.3,22.3,14),'dark',False,.24))
        q.subtract(body,box('DeepFlightHangar',(sign*6.35,10.20,6.0),(6.3,5.2,2.8),'dark',False,.15))
        q.subtract(body,box('PropulsionHullClearance',(sign*7,24.35,6.7),(4.5,3.0,3.1),'dark',False,.14))
    q.subtract(body,cylinder('EmitterPocket',(0,0,.6),3.05,4.8,'dark',False,32))
    boarding_receiver(body)
    TOP_BVH=BVHTree.FromObject(body,bpy.context.evaluated_depsgraph_get())
    flight_deck()
    citadel()
    engine_pods()
    weapons_and_panels(body)
    finish_surfaces()
    emitter_structure()
    q.canonical_halves()
    for obj in bpy.context.scene.objects:
        if obj.type=='MESH':
            obj['carrier_runtime']=True
    for row,cx,cy,_,_ in q.BAY_ROWS:
        for sign in (-1,1):
            slot=(0 if row=='Forward' else 2)+(0 if sign<0 else 1)
            v.empty('ServiceBay_'+str(slot),(cx*sign,cy,2.4))
    v.empty('BeamOrigin',(0,0,-.1))
    v.empty('BoardingProjection',(0,18,0))
    bpy.context.scene['front_axis']='-Y'
    bpy.context.scene['up_axis']='+Z'
    bpy.context.scene['status']='USER_APPROVED_V27_DESIGN_50_BLOCK_IMPLEMENTATION'
    bpy.context.scene['revision']=REVISION
    q.studio('chunkbuster')
    scene=bpy.context.scene
    scene.world.node_tree.nodes['Background'].inputs[0].default_value=(.28,.28,.28,1)
    scene.world.node_tree.nodes['Background'].inputs[1].default_value=.8
    scene.view_settings.exposure=.15
    for light in scene.objects:
        if light.type=='LIGHT' and ('Fill' in light.name or 'Belly' in light.name):
            light.data.energy*=1.5
    scene.frame_set(40)
    report=validate()
    q.dump(OUT/'validation.json',report)
    if not report['passed']:
        raise RuntimeError(report['errors'])
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'carrier-v27.blend'))
    q.export(OUT/'carrier-v27.glb')
    render_views()
    contract={'revision':REVISION,'runtimeBounds':[-13,0,-25,13,11,25],
              'runtimeAxes':{'up':'+Y','front':'-Z','right':'+X'},'blenderToRuntime':'(x,z,y), winding reversed',
              'beam':[0,-.1,0],'boardingProjection':[0,0,18],
              'bays':[{'slot':i,'xyz':[s*x,2.4,y],'approach':[s*x,-2,y],'clearSize':[3,.95,3]}
                      for i,(x,y,s) in enumerate(( (7.15,-5.2,-1),(7.15,-5.2,1),(3.6,7.8,-1),(3.6,7.8,1)))],
              'mesh':'models/runtime/carrier.mgm','atlas':'textures/runtime/carrier.png','scale':1,
              'format':'MGM4','rotorGroups':0,'poses':'all three identical','runtimeWitnesses':0}
    contract['boardingReceiver']={'center':[0,0,18],'visualClearSize':[3,4.4,3],
                                  'groundMarkerOwner':'server-safe-pad renderer; not this asset'}
    contract['triangleBudget']={'near':25000,'far':15000}
    q.dump(OUT/'runtime-contract.json',contract)


def validate():
    parts=d.geometry()
    points=d.all_points(parts)
    bb=d.bounds(points)
    tree=kdtree.KDTree(len(points))
    for i,p in enumerate(points):
        tree.insert(p,i)
    tree.balance()
    error=max(tree.find(Vector((-p.x,p.y,p.z)))[2] for p in points)
    errors=[]
    if error>.0001 or any(abs(a-b)>.001 for a,b in zip(bb,(-13,-25,0,13,25,11))):
        errors.append({'bounds':bb,'mirrorError':error})
    graph=bpy.context.evaluated_depsgraph_get()
    objects=[o for o in bpy.context.scene.objects if o.type=='MESH']
    structures=[(o,BVHTree.FromObject(o,graph)) for o in objects]
    bays=[]
    for row,cx,cy,_,_ in q.BAY_ROWS:
        for sign in (-1,1):
            hits=[]
            for i in range(13):
                for j in range(13):
                    origin=Vector((cx*sign-1.5+i*.25,cy-1.5+j*.25,-1))
                    for obj,bvh in structures:
                        inv=obj.matrix_world.inverted()
                        contact,_,_,_=bvh.ray_cast(inv@origin,inv.to_3x3()@Vector((0,0,1)))
                        if contact is not None and (obj.matrix_world@contact).z<3.35-.0001:
                            hits.append({'part':obj.name,'xy':list(origin)[:2]})
            bays.append({'slot':len(bays),'rays':169,'blocked':hits})
            if hits:
                errors.append(bays[-1])
    tris=0
    for obj in objects:
        ev=obj.evaluated_get(graph)
        mesh=ev.to_mesh()
        mesh.calc_loop_triangles()
        tris+=len(mesh.loop_triangles)
        ev.to_mesh_clear()
    if tris>25000:
        errors.append({'triangleBudget':tris})
    missing=[o.name for o in objects if not any(m.type=='MIRROR' and m.mirror_object==v.MIRROR for m in o.modifiers)]
    if missing:
        errors.append({'missingMirror':missing})
    boarding_hits=[]
    for i in range(13):
        for j in range(13):
            origin=Vector((-1.5+i*.25,16.5+j*.25,-1))
            for obj,bvh in structures:
                inv=obj.matrix_world.inverted()
                contact,_,_,_=bvh.ray_cast(inv@origin,inv.to_3x3()@Vector((0,0,1)))
                if contact is not None and (obj.matrix_world@contact).z<4.4-.0001:
                    boarding_hits.append({'part':obj.name,'xy':list(origin)[:2]})
    if boarding_hits:
        errors.append({'boardingReceiver':boarding_hits})
    if any('DefenseBarrel' in obj.name for obj in objects):
        errors.append('nonfunctional weapon barrels')
    return {'passed':not errors,'errors':errors,'boundsBlender':bb,'mirrorError':error,'triangles':tris,
            'meshObjects':len(objects),'bays':bays,'reference':str(REFERENCE),'referenceSha256':q.digest(REFERENCE),
            'boardingReceiver':{'rays':169,'blocked':boarding_hits,'clearTop':4.4},
            'revision':REVISION,'scope':'Native geometry, 676 service + 169 boarding entry rays; no moving mechanism claim'}


def render_views():
    scene=bpy.context.scene
    for name,axis in q.VIEWS:
        scene.render.film_transparent=name!='hero'
        q.render(OUT/(name+'.png'),axis,61,(0,0,5.5),(1280,1024))
    scene.render.film_transparent=True
    q.render(OUT/'belly-hero.png',(4,6,-4),61,(0,0,5.5),(1280,1024))
    for size in (16,32,64,128,1024):
        path=OUT/('carrier-unit-'+str(size)+'.png')
        q.render(path,(4,-6,5),58,(0,0,5.5),(size,size))
        if size==128:
            destination=STAGED/'textures/item/carrier_unit.png'
            destination.parent.mkdir(parents=True,exist_ok=True)
            bpy.data.images['Render Result'].save_render(str(destination))
    q.sheet_start(1440,1080)
    q.sheet_text('Morrowgear / CHUNKBUSTER V27 / 50 BLOCK IMPLEMENTATION',24,18,26)
    for i,(name,_) in enumerate(q.VIEWS):
        x,y=i%3*480,i//3*328+75
        q.sheet_text(name.upper(),x+20,y,18)
        q.sheet_image(OUT/(name+'.png'),x+8,y+26,464,280)
    q.sheet_text('26 width / 50 length / 11 height',510,790,23)
    q.sheet_text('Belly origin / exact X0 mirror',510,836,22)
    q.sheet_text('Approved image reconstruction',510,882,22)
    q.sheet_save(OUT/'contact-sheet.png')
    q.sheet_start(1500,1050)
    q.sheet_text('APPROVED V27 / CANONICAL 50-BLOCK MODEL',24,20,30)
    q.sheet_image(REFERENCE,12,85,720,870,uv=(0,0,1020/1536,1))
    q.sheet_image(OUT/'hero.png',756,95,730,585)
    q.sheet_image(OUT/'belly-hero.png',756,681,730,310)
    q.sheet_text('Reference 70 -> functional 50 / not uniform scaling',24,1002,23)
    q.sheet_save(OUT/'reference-comparison.png')


def runtime_geometry(lod=False):
    graph=bpy.context.evaluated_depsgraph_get()
    vertices,faces,indices,materials=[],[],[],[]
    material_map={}
    parts={}
    for obj in sorted(bpy.context.scene.objects,key=lambda o:o.name):
        if obj.type!='MESH' or not obj.get('carrier_runtime') or (lod and not obj.get('lod_keep',True)):
            continue
        if lod and obj.name.startswith(('EngineIntakeLouver','DeckWallBrace','ServiceCaptureStop',
                'ServiceMouthSeal','ServiceWallUtilityCabinet','ServiceAmmoTransferLatch',
                'BoardingCeilingHandrail','NavigationSensorOptic','EmitterOpticalRetainer')):
            continue
        evaluated=obj.evaluated_get(graph)
        mesh=evaluated.to_mesh()
        mesh.calc_loop_triangles()
        base=len(vertices)
        vertices.extend(evaluated.matrix_world@p.co for p in mesh.vertices)
        for triangle in mesh.loop_triangles:
            if triangle.area<1e-10:
                continue
            faces.append(tuple(base+i for i in triangle.vertices))
            material=mesh.materials[triangle.material_index]
            if material.name not in material_map:
                material_map[material.name]=len(materials)
                materials.append(material)
            indices.append(material_map[material.name])
        parts[obj.name]=len(mesh.loop_triangles)
        evaluated.to_mesh_clear()
    return vertices,faces,indices,materials,parts


def export_runtime(lod=False,source=None):
    source=source or OUT/'carrier-v27.blend'
    bpy.ops.wm.open_mainfile(filepath=str(source))
    scene=bpy.context.scene
    vertices,faces,indices,materials,parts=runtime_geometry(lod)
    mesh=bpy.data.meshes.new('CarrierMGM4Bake')
    mesh.from_pydata(vertices,[],faces)
    mesh.update()
    model=bpy.data.objects.new('CarrierMGM4Bake',mesh)
    scene.collection.objects.link(model)
    for mat in materials:
        mesh.materials.append(mat)
    for face,index in zip(mesh.polygons,indices):
        face.material_index=index
    for obj in scene.objects:
        if obj.type=='MESH' and obj!=model:
            obj.hide_render=True
    bpy.ops.object.select_all(action='DESELECT')
    model.select_set(True)
    bpy.context.view_layer.objects.active=model
    mesh.uv_layers.new(name='GameUV')
    bpy.ops.object.mode_set(mode='EDIT')
    bpy.ops.mesh.select_all(action='SELECT')
    bpy.ops.uv.smart_project(angle_limit=math.radians(66),island_margin=.003)
    bpy.ops.object.mode_set(mode='OBJECT')
    name='carrier_lod' if lod else 'carrier'
    print('CARRIER_TRIANGLE_CANDIDATE',name,len(faces),flush=True)
    atlas=bpy.data.images.new(name+'_albedo',width=1024 if lod else 2048,height=1024 if lod else 2048,alpha=False)
    signals=[]
    for mat in materials:
        color,signal=mgm.material_signal(mat)
        if signal and (color[1]>color[2] or 'CarrierBoarding' in mat.name):
            signal=2
        signals.append((color,signal))
        node=mat.node_tree.nodes.new('ShaderNodeTexImage')
        node.image=atlas
        mat.node_tree.nodes.active=node
    scene.render.engine='CYCLES'
    scene.cycles.samples=1
    scene.cycles.device='CPU'
    scene.render.bake.use_pass_direct=False
    scene.render.bake.use_pass_indirect=False
    scene.render.bake.use_pass_color=True
    scene.render.bake.margin=4
    bpy.ops.object.bake(type='DIFFUSE')
    atlas_path=STAGED/'textures/runtime'/(name+'.png')
    atlas_path.parent.mkdir(parents=True,exist_ok=True)
    atlas.filepath_raw=str(atlas_path)
    atlas.file_format='PNG'
    atlas.save()
    mesh_path=STAGED/'models/runtime'/(name+'.mgm')
    mesh_path.parent.mkdir(parents=True,exist_ok=True)
    mesh.calc_loop_triangles()
    count=len(mesh.loop_triangles)*3
    if count>(45000 if lod else 75000):
        raise ValueError(f'Carrier 25k / 15k triangle budget exceeded: {name} {count//3}')
    with mesh_path.open('wb') as output:
        output.write(struct.pack('>III6f',0x4D474D34,count,2,0,0,0,0,0,0))
        for tri in mesh.loop_triangles:
            color,signal=signals[tri.material_index]
            # Blender front -Y maps to runtime -Z through a handedness reversal.
            loops=[tri.loops[i] for i in (0,2,1)]
            points=[Vector((mesh.vertices[mesh.loops[i].vertex_index].co.x,
                            mesh.vertices[mesh.loops[i].vertex_index].co.z,
                            mesh.vertices[mesh.loops[i].vertex_index].co.y)) for i in loops]
            normal=(points[1]-points[0]).cross(points[2]-points[0]).normalized()
            for point,loop in zip(points,loops):
                pose=(*point,*normal)
                uv=mesh.uv_layers.active.data[loop].uv
                output.write(struct.pack('>20f6B',*(pose*3),uv.x,1-uv.y,*color,0,signal))
    report=read_runtime(mesh_path)
    report.update({'sourceSha256':q.digest(source),'atlasSha256':q.digest(atlas_path),
                   'atlasSize':list(atlas.size),'parts':parts,'excludedWitnesses':True,
                   'lodStrategy':'drop bounded detail parts' if lod else 'full evaluated canonical polygons'})
    q.dump(OUT/(name+'-runtime-validation.json'),report)
    if not report['passed']:
        raise RuntimeError(report)
    print('CARRIER_RUNTIME',name,report['triangles'],'triangles',flush=True)


def read_runtime(path):
    raw=path.read_bytes()
    magic,count,rotors=struct.unpack_from('>III',raw)
    errors=[]
    if magic!=0x4D474D34 or rotors!=2 or len(raw)!=12+rotors*12+count*86:
        errors.append('header or byte length')
    rows=list(struct.iter_unpack('>20f6B',raw[12+rotors*12:]))
    points=[Vector(row[:3]) for row in rows]
    bounds=d.bounds(points)
    tree=kdtree.KDTree(count)
    for i,p in enumerate(points):
        tree.insert(p,i)
    tree.balance()
    mirror=max(tree.find(Vector((-p.x,p.y,p.z)))[2] for p in points)
    if mirror>.0001 or any(abs(a-b)>.001 for a,b in zip(bounds,(-13,0,-25,13,11,25))):
        errors.append('bounds or bilateral mirror')
    luminous={str(i):0 for i in range(3)}
    for row in rows:
        if not all(math.isfinite(v) for v in row[:20]) or row[:6]!=row[6:12] or row[:6]!=row[12:18]:
            errors.append('nonfinite or nonstatic pose')
            break
        if abs(Vector(row[3:6]).length-1)>.001 or not all(0<=uv<=1 for uv in row[18:20]) or row[24]!=0 or row[25] not in (0,1,2):
            errors.append('normal uv group or signal')
            break
        luminous[str(row[25])]+=1
    for i in range(0,count,3):
        a,b,c=points[i:i+3]
        normal=(b-a).cross(c-a).normalized()
        if normal.dot(Vector(rows[i][3:6]))<.999:
            errors.append('triangle winding')
            break
    if count%3 or count//3>25000 or not luminous['1'] or not luminous['2']:
        errors.append('budget triangle or emission channels')
    if path.stem.endswith('_lod') and count//3>15000:
        errors.append('LOD 15k budget')
    bvh=BVHTree.FromPolygons(points,[(i,i+1,i+2) for i in range(0,count,3)],all_triangles=True)
    apertures=[]
    for label,cx,cz,top in (('bay0',-7.15,-5.2,3.35),('bay1',7.15,-5.2,3.35),
                           ('bay2',-3.6,7.8,3.35),('bay3',3.6,7.8,3.35),('boarding',0,18,4.4)):
        blocked=0
        for i in range(13):
            for j in range(13):
                origin=Vector((cx-1.5+i*.25,-2,cz-1.5+j*.25))
                hit,_,_,_=bvh.ray_cast(origin,Vector((0,1,0)))
                blocked+=int(hit is not None and hit.y<top-.0001)
        apertures.append({'name':label,'rays':169,'blocked':blocked})
        if blocked:
            errors.append(label+' runtime aperture')
    return {'passed':not errors,'errors':errors,'format':'MGM4','vertices':count,'triangles':count//3,
            'byteLength':len(raw),'sha256':q.digest(path),'runtimeBounds':bounds,'mirrorError':mirror,
            'rotorCenters':2,'groups':[0],'posesIdentical':True,'luminousVertices':luminous,'apertures':apertures}


def comparison_witnesses():
    bpy.ops.wm.open_mainfile(filepath=str(OUT/'carrier-v27.blend'))
    source=ROOT/'docs/design/detail-scale-v22/field/airframe.glb'
    existing=set(bpy.context.scene.objects)
    # The V22 canonical GLB is exported at frame 40 with gear stowed. Its static
    # hierarchy avoids a Blender 5.2 dependency-graph crash when appending drivers.
    bpy.ops.import_scene.gltf(filepath=str(source))
    loaded=[o for o in bpy.context.scene.objects if o not in existing]
    print('WITNESS imported',len(loaded),flush=True)
    bpy.context.scene.frame_set(40)
    bpy.context.view_layer.update()
    graph=bpy.context.evaluated_depsgraph_get()
    vertices,faces,materials,indices=[],[],[],[]
    material_map={}
    for obj in loaded:
        if obj.type not in ('MESH','CURVE') or obj.hide_render or obj.name.startswith('FX_'):
            continue
        evaluated=obj.evaluated_get(graph)
        mesh=evaluated.to_mesh()
        base=len(vertices)
        vertices.extend(evaluated.matrix_world@p.co for p in mesh.vertices)
        for poly in mesh.polygons:
            faces.append(tuple(base+i for i in poly.vertices))
            mat=mesh.materials[poly.material_index].original
            if mat.name not in material_map:
                material_map[mat.name]=len(materials)
                materials.append(mat)
            indices.append(material_map[mat.name])
        evaluated.to_mesh_clear()
    bounds=d.bounds(vertices)
    print('WITNESS sampled',bounds,flush=True)
    height=bounds[5]-bounds[2]
    for slot,(x,y) in enumerate(((-6.35,-7),(6.35,-7),(-6.35,1),(6.35,1))):
        mesh=bpy.data.meshes.new('V22_actual_scale_'+str(slot))
        mesh.from_pydata(vertices,[],faces)
        for mat in materials:
            mesh.materials.append(mat)
        for face,index in zip(mesh.polygons,indices):
            face.material_index=index
        obj=bpy.data.objects.new('REVIEW_ONLY_V22_'+str(slot),mesh)
        bpy.context.scene.collection.objects.link(obj)
        obj.location=(x,y,4.82-bounds[2])
        obj['carrier_runtime']=False
        obj['scale_witness_only']=True
    for obj in loaded:
        bpy.data.objects.remove(obj,do_unlink=True)
    print('WITNESS placed',flush=True)
    report={'source':str(source.relative_to(ROOT)),'sourceSha256':q.digest(source),
            'sourceBoundsBlender':bounds,'gearStowedMeshHeight':height,'entityHeight':.75,
            'serviceClearHeight':.95,'actualMeshFitsHeight':height<=.95,
            'deckWitnessCount':4,'runtimeIncluded':False,'scale':1}
    q.dump(OUT/'small-drone-fit.json',report)
    if height>.95 or bounds[3]-bounds[0]>3.001 or bounds[4]-bounds[1]>3.001:
        raise RuntimeError('Actual V22 gear-stowed witness exceeds service envelope')
    bpy.context.scene.render.film_transparent=False
    q.render(OUT/'scale-comparison.png',(4,-6,5),61,(0,0,5.5),(1500,1200))
    q.render(OUT/'deck-detail.png',(1,-2,3),38,(0,-1,6.0),(1500,1100))
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'carrier-v27-review-only.blend'))


def verify_outputs():
    bpy.ops.wm.open_mainfile(filepath=str(OUT/'carrier-v27.blend'))
    v.MIRROR=bpy.data.objects.get('Symmetry_X0')
    native=validate()
    states=[]
    for frame in (1,40,80):
        bpy.context.scene.frame_set(frame)
        states.append({'frame':frame,'bounds':d.bounds(d.all_points(d.geometry()))})
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(OUT/'carrier-v27.glb'))
    parts=d.geometry()
    points=d.all_points(parts)
    bounds=d.bounds(points)
    tree=kdtree.KDTree(len(points))
    for i,p in enumerate(points):
        tree.insert(p,i)
    tree.balance()
    mirror=max(tree.find(Vector((-p.x,p.y,p.z)))[2] for p in points)
    errors=[]
    if not native['passed'] or mirror>.0001 or any(abs(a-b)>.001 for a,b in zip(bounds,native['boundsBlender'])):
        errors.append('native or GLB roundtrip')
    for state in states:
        if state['bounds']!=states[0]['bounds']:
            errors.append('static frame bounds')
    runtime=[]
    for name in ('carrier','carrier_lod'):
        path=STAGED/'models/runtime'/(name+'.mgm')
        report=read_runtime(path)
        runtime.append(report)
        if not report['passed']:
            errors.append(name)
        saved=json.loads((OUT/(name+'-runtime-validation.json')).read_text())
        if saved['sourceSha256']!=q.digest(OUT/'carrier-v27.blend') or saved['sha256']!=report['sha256']:
            errors.append(name+' stale source')
        bpy.ops.wm.read_factory_settings(use_empty=True)
        raw=path.read_bytes()
        _,count,rotors=struct.unpack_from('>III',raw)
        rows=list(struct.iter_unpack('>20f6B',raw[12+rotors*12:]))
        # Reverse the handedness conversion again for a native readback rendering.
        order=[base+j for base in range(0,count,3) for j in (0,2,1)]
        vertices=[(rows[i][0],rows[i][2],rows[i][1]) for i in order]
        mesh=bpy.data.meshes.new(name+'_readback')
        mesh.from_pydata(vertices,[],[(i,i+1,i+2) for i in range(0,count,3)])
        mesh.update()
        uv=mesh.uv_layers.new(name='GameUV')
        for loop,index in zip(uv.data,order):
            loop.uv=(rows[index][18],1-rows[index][19])
        obj=bpy.data.objects.new(name+'_readback',mesh)
        bpy.context.scene.collection.objects.link(obj)
        atlas=bpy.data.images.load(str(STAGED/'textures/runtime'/(name+'.png')))
        for signal in (0,1,2):
            mat=bpy.data.materials.new(name+'_signal_'+str(signal))
            mat.use_nodes=True
            nodes=mat.node_tree.nodes
            bsdf=nodes['Principled BSDF']
            bsdf.inputs['Metallic'].default_value=0
            bsdf.inputs['Roughness'].default_value=.7
            tex=nodes.new('ShaderNodeTexImage')
            tex.image=atlas
            mat.node_tree.links.new(tex.outputs['Color'],bsdf.inputs['Base Color'])
            if signal:
                bsdf.inputs['Emission Strength'].default_value=1.2
                if signal==1:
                    bsdf.inputs['Emission Color'].default_value=(.03,.68,.8,1)
                else:
                    mat.node_tree.links.new(tex.outputs['Color'],bsdf.inputs['Emission Color'])
            mesh.materials.append(mat)
        for face,i in zip(mesh.polygons,range(0,count,3)):
            face.material_index=rows[i][25]
        q.studio('chunkbuster')
        bpy.context.scene.render.film_transparent=False
        bpy.context.scene.world.node_tree.nodes['Background'].inputs[0].default_value=(.28,.28,.28,1)
        q.render(OUT/(name+'-mgm-readback.png'),(4,-6,4),61,(0,0,5.5),(1280,1024))
    images=[]
    for name in ('hero','front','rear','top','bottom','left','right','carrier-unit-16','carrier-unit-128','carrier-mgm-readback','carrier_lod-mgm-readback'):
        path=OUT/(name+'.png')
        picture=bpy.data.images.load(str(path),check_existing=False)
        pixels=list(picture.pixels)
        w,h=picture.size
        luminance=[sum(pixels[i:i+3]) for i in range(0,len(pixels),4)]
        nonblank=max(luminance)-min(luminance)>.08
        opaque=sum(pixels[i]>.05 for i in range(3,len(pixels),4))
        if not nonblank or not opaque:
            errors.append(name+' blank image')
        images.append({'path':path.name,'size':[w,h],'nonblank':nonblank,'occupiedPixels':opaque})
        bpy.data.images.remove(picture)
    report={'passed':not errors,'errors':errors,'native':native,'states':states,
            'glb':{'boundsBlender':bounds,'mirrorError':mirror,'sha256':q.digest(OUT/'carrier-v27.glb')},
            'runtime':runtime,'images':images,'sourceSha256':q.digest(OUT/'carrier-v27.blend'),
            'scope':'Static native and MGM geometry, atlas and image readback; no game launch or moving mechanism claim'}
    q.dump(OUT/'verification.json',report)
    if errors:
        raise RuntimeError(errors)
    q.sheet_start(840,1440)
    q.sheet_text('CHUNKBUSTER / V27',24,22,33)
    q.sheet_text('50 length / 26 width / 11 height',24,72,22)
    q.sheet_image(OUT/'scale-comparison.png',0,118,840,650)
    q.sheet_image(OUT/'belly-hero.png',0,744,840,446)
    q.sheet_text('4 service bays / original 3-block drones',24,1210,23)
    q.sheet_text('MGM4: '+str(runtime[0]['triangles'])+' tris / LOD: '+str(runtime[1]['triangles'])+' tris',24,1250,22)
    q.sheet_text('Native mirror + bounds + readback PASS',24,1290,22)
    q.sheet_text('Carrier unit',65,1350,19)
    q.sheet_image(OUT/'carrier-unit-128.png',260,1338,88,88)
    q.sheet_text('Console',456,1350,19)
    q.sheet_image(ASSETS/'textures/item/carrier_console.png',652,1338,88,88)
    q.sheet_save(OUT/'mobile-summary.png')
    functional_views()
    publish_validated_assets()


def functional_views():
    bpy.ops.wm.open_mainfile(filepath=str(OUT/'carrier-v27.blend'))
    scene=bpy.context.scene
    scene.render.film_transparent=False
    for name,target,axis,span in (
        ('service-bay-detail',(7.15,-5.2,2.0),(2,-3,-4),10.0),
        ('emitter-detail',(0,0,.80),(2,-3,-5),11.0),
        ('boarding-detail',(0,18,3.0),(2,4,-5),10.5),
        ('propulsion-detail',(7,23,7),(2,5,2),12.0),
    ):
        d.render(OUT/(name+'.png'),Vector(target)+Vector(axis).normalized()*span*3,span,
                 target=target,size=(1000,820),frame=40)
    q.sheet_start(1440,1120)
    q.sheet_text('V27 / FUNCTIONAL DETAILS / 50 x 26 x 11',24,18,28)
    for i,(name,label) in enumerate((('service-bay-detail','SERVICE / ENTRY + POWER + AMMUNITION'),
                                    ('emitter-detail','MINING + COMBAT / CENTRAL EMITTER'),
                                    ('boarding-detail','BOARDING / AFT Z=18 RECEIVER'),
                                    ('propulsion-detail','PROPULSION / TWIN EXHAUST + COOLING'))):
        x,y=(i%2)*720,80+(i//2)*510
        q.sheet_text(label,x+18,y,20)
        q.sheet_image(OUT/(name+'.png'),x+12,y+35,696,455)
    q.sheet_save(OUT/'functional-details.png')
    q.sheet_start(1440,1060)
    q.sheet_text('V27 / BEFORE AND AFTER / SAME CAMERA',24,18,29)
    q.sheet_text('BEFORE',24,72,23)
    q.sheet_text('FUNCTIONAL DETAIL 01',744,72,23)
    for row,name in enumerate(('hero','belly-hero')):
        q.sheet_image(BEFORE/(name+'.png'),10,115+row*445,700,420)
        q.sheet_image(OUT/(name+'.png'),730,115+row*445,700,420)
    q.sheet_text('Same silhouette / 4 service bays / origin + dimensions retained',24,1015,22)
    q.sheet_save(OUT/'before-after.png')


def publish_validated_assets(source=None):
    source=source or OUT/'carrier-v27.blend'
    verification=json.loads((OUT/'verification.json').read_text())
    if not verification['passed'] or verification['sourceSha256']!=q.digest(source):
        raise RuntimeError('Refusing unverified carrier publication')
    paths=('models/runtime/carrier.mgm','models/runtime/carrier_lod.mgm',
           'textures/runtime/carrier.png','textures/runtime/carrier_lod.png','textures/item/carrier_unit.png')
    for name in ('carrier','carrier_lod'):
        report=json.loads((OUT/(name+'-runtime-validation.json')).read_text())
        if (not report['passed'] or report['triangles']>(15000 if name.endswith('_lod') else 25000)
                or report['sha256']!=q.digest(STAGED/'models/runtime'/(name+'.mgm'))
                or report['atlasSha256']!=q.digest(STAGED/'textures/runtime'/(name+'.png'))):
            raise RuntimeError('Refusing stale runtime asset publication')
    if q.digest(STAGED/'textures/item/carrier_unit.png')!=q.digest(OUT/'carrier-unit-128.png'):
        raise RuntimeError('Carrier icon differs from canonical render')
    pending=[]
    for relative in paths:
        destination=ASSETS/relative
        temporary=destination.with_name(destination.name+'.verified-tmp')
        temporary.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(STAGED/relative,temporary)
        pending.append((temporary,destination))
    for temporary,destination in pending:
        os.replace(temporary,destination)
    q.dump(OUT/'published-assets.json',{'revision':REVISION,'passed':True,
           'sourceSha256':verification['sourceSha256'],
           'assets':[{'path':relative,'sha256':q.digest(ASSETS/relative)} for relative in paths]})


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--only',choices=('native','runtime','comparison','verify','all'),default='all')
    args=parser.parse_args(sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else [])
    if args.only in ('native','all'):
        build()
    if args.only in ('runtime','all'):
        export_runtime()
        export_runtime(True)
    if args.only in ('comparison','all'):
        comparison_witnesses()
    if args.only in ('verify','all'):
        verify_outputs()
