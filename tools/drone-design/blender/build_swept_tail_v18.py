"""Canonical V17-derived longer aft body and coordinated, broader swept tips."""
import hashlib
import importlib.util
import json
import math
from pathlib import Path

import bpy
import bmesh
from mathutils import Vector,kdtree
from mathutils.bvhtree import BVHTree

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
BASE=ROOT/'docs/design/reference-balance-v17'
OUT=ROOT/'docs/design/reference-swept-tail-v18'


def module(name,file):
    spec=importlib.util.spec_from_file_location(name,HERE/file)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result)
    return result


v16=module('propulsion_v16','build_propulsion_v16.py')
v17=module('balance_v17','build_balanced_airframe_v17.py')
TIP=[v17.shape(v16.aft_shape(Vector(p))) for p in v16.sections.BOUNDARY[4:8]]
TARGET_TIP=[p+Vector(delta) for p,delta in zip(TIP,((0,.12,0),(0,.20,0),(.18,.65,-.035),(0,1.02,-.035)))]
TAIL=[v17.shape(v16.aft_shape(Vector(v16.sections.BOUNDARY[i]))) for i in (10,9,8,7)]
OLD_OUTLINE=[v17.shape(v16.aft_shape(Vector(p))) for p in v16.sections.BOUNDARY]
NEW_OUTLINE=[p.copy() for p in OLD_OUTLINE]
for i,target in zip(range(4,8),TARGET_TIP):
    NEW_OUTLINE[i].y=target.y
for i,dy in ((8,.50),(9,.60),(10,.60)):NEW_OUTLINE[i].y+=dy


def lerp(t,points):
    for a,b in zip(points,points[1:]):
        if t<=b[0]:
            u=max(0.,min(1.,(t-a[0])/(b[0]-a[0])))
            return a[1]*(1-u)+b[1]*u
    return points[-1][1]


def smooth(t):
    t=max(0.,min(1.,t));return t*t*(3-2*t)


def chord(x,outline):
    x=min(max(x,1e-7),max(p.x for p in outline)-1e-6);hits=[]
    for a,b in zip(outline,outline[1:]+outline[:1]):
        if min(a.x,b.x)<=x<max(a.x,b.x):
            t=(x-a.x)/(b.x-a.x);hits.append(a.y+(b.y-a.y)*t)
    return min(hits),max(hits)


def shape(point):
    x,y,z=point;sign=-1 if x<0 else 1;x=abs(x)
    radius=math.hypot(x-1.91,y-.01826677)
    fan_mask=smooth((radius-1.045)/.40)
    if not fan_mask:return Vector((sign*x,y,z))
    rear_y=lerp(x,[(p.x,p.y) for p in TAIL])
    start=1.40-.34*smooth((x-.90)/.50)
    extension=lerp(x,[(0,.60),(.90,.60),(2.61,.50),(4.45,.50)])
    dy=extension*smooth((y-start)/max(.03,rear_y-start));dz=dx=0.
    if x>2.65:
        oldlead,oldtrail=chord(x,OLD_OUTLINE);newlead,newtrail=chord(x,NEW_OUTLINE)
        u=max(0.,min(1.,(y-oldlead)/max(.001,oldtrail-oldlead)))
        blend=smooth((x-2.65)/.85)
        wing_dy=(newlead-oldlead)*(1-u)+(newtrail-oldtrail)*u
        dy=dy*(1-blend)+wing_dy*blend
        # At the outermost corner the chord collapses; preserve its forward height.
        dz=-.035*u*blend*smooth((TIP[1].x-x)/.08)
        r,s=TIP[2],TIP[3]
        dx=.18*smooth((y-s.y)/(r.y-s.y))*smooth((x-s.x)/(r.x-s.x))
    return Vector((sign*(x+dx*fan_mask),y+dy*fan_mask,z+dz*fan_mask))


def deform():
    protected={};oldhull=[];changes={}
    for obj in list(bpy.context.scene.objects):
        if obj.type not in ('MESH','CURVE'):continue
        graph=bpy.context.evaluated_depsgraph_get();ev=obj.evaluated_get(graph)
        data=bpy.data.meshes.new_from_object(ev,preserve_all_data_layers=True,depsgraph=graph)
        old=[ev.matrix_world@v.co for v in data.vertices]
        new=[shape(p) for p in old]
        if obj.name.startswith(('R0','S0')):protected[obj.name]=old
        if obj.name.startswith('H01_'):oldhull=old
        for v,p in zip(data.vertices,new):v.co=p
        data.update();name=obj.name
        replacement=bpy.data.objects.new(name+'_v18',data);bpy.context.collection.objects.link(replacement)
        bpy.data.objects.remove(obj,do_unlink=True);replacement.name=name
        changes[name]={'vertices':len(new),'maxDisplacement':max((a-b).length for a,b in zip(old,new))}
    bpy.context.view_layer.update()
    return protected,oldhull,changes


def validate(protected,oldhull,changes):
    vertices=[];faces=[];parts={};preserved={}
    for obj in bpy.context.scene.objects:
        if obj.type not in ('MESH','CURVE'):continue
        vs,fs=v17.geometry(obj);parts[obj.name]=(vs,fs)
        n=len(vertices);vertices.extend(vs);faces.extend([tuple(n+i for i in f) for f in fs])
        if obj.name in protected:
            original=protected[obj.name]
            preserved[obj.name]=len(original)==len(vs) and max((a-b).length for a,b in zip(original,vs))<1e-6
    kd=kdtree.KDTree(len(vertices))
    for i,p in enumerate(vertices):kd.insert(p,i)
    kd.balance();error=max(kd.find(Vector((-p.x,p.y,p.z)))[2] for p in vertices)
    body=next(o for o in bpy.context.scene.objects if o.name.startswith('H01_'))
    hull,_=parts[body.name]
    bm=bmesh.new();bm.from_mesh(body.data);bmesh.ops.remove_doubles(bm,verts=list(bm.verts),dist=1e-5)
    nonmanifold=sum(not e.is_manifold for e in bm.edges);volume=bm.calc_volume(signed=True);bm.free()
    tree=BVHTree.FromPolygons(vertices,faces);probes=[];path_blocks=[]
    for p in json.loads((BASE/'validation.json').read_text())['probes']:
        a,b=shape(Vector(p['start'])),shape(Vector(p['hit']))
        hit,normal,index,d=tree.ray_cast(a,(b-a).normalized())
        probes.append({'type':p['type'],'start':list(a),'hit':list(hit) if hit else None,
                       'depth':d,'expectedDepth':(b-a).length})
        original_start,original_end=Vector(p['start']),Vector(p['hit'])
        for j in range(24):
            q0=shape(original_start.lerp(original_end,.85*j/24))
            q1=shape(original_start.lerp(original_end,.85*(j+1)/24))
            direction=(q1-q0).normalized();segment=(q1-q0).length
            hp,_,_,hd=tree.ray_cast(q0+direction*.0001,direction,segment-.0002)
            if hp is not None:path_blocks.append({'type':p['type'],'segment':j,'point':list(hp)})
    jacobians=[]
    for p in oldhull[::23]:
        a=shape(p);dx=(shape(p+Vector((.0001,0,0)))-a)/.0001;dy=(shape(p+Vector((0,.0001,0)))-a)/.0001
        jacobians.append(dx.x*dy.y-dx.y*dy.x)
    targets=[shape(p) for p in TIP]
    old_notch=TIP[2].y-TIP[3].y;new_notch=targets[2].y-targets[3].y
    span=max(p.x for p in hull)-min(p.x for p in hull)
    length=max(p.y for p in vertices)-min(p.y for p in vertices)
    checks={'bilateralGeometry':error<1e-5,'finiteCoordinates':all(math.isfinite(c) for p in vertices for c in p),
            'rotorsAndSensorsUnchanged':all(preserved.values()),'watertightHull':nonmanifold==0,'positiveHullVolume':volume>0,
            'wingspanRetained':abs(span-8.90)<1e-5,
            'bodyTailExtended':abs(max(p.y for p in hull)-max(p.y for p in oldhull)-.60)<.001,
            'tipCornersReachTargets':max((p-q).length for p,q in zip(targets,TARGET_TIP))<1e-5,
            'trailingNotchShallower':0<new_notch<old_notch*.40,
            'tipAftEdgeBroader':targets[2].x-targets[3].x>TIP[2].x-TIP[3].x+.17,
            'noSampledPlanformFold':min(jacobians)>.15,
            'intakeOpen':all(p['depth'] is not None and p['depth']>.15 for p in probes if p['type']=='intake'),
            'exhaustOpen':all(p['depth'] is not None and p['depth']>.25 for p in probes if p['type']=='exhaust'),
            'curvedThroatContinuity':not path_blocks}
    return {'checks':checks,'passed':all(checks.values()),'mirrorError':error,'nonmanifoldHullEdges':nonmanifold,
            'minimumSampledJacobian':min(jacobians),'jacobianSamples':len(jacobians),
            'measurements':{'span':span,'length':length,'bodyTailExtension':.60,
                            'tipRearExtension':.65,'tipRootRearExtension':1.02,'tipRearWidening':.18,
                            'notchBefore':old_notch,'notchAfter':new_notch,'notchReductionPercent':100*(1-new_notch/old_notch)},
            'tipBefore':[list(p) for p in TIP],'tipAfter':[list(p) for p in targets],
            'probes':probes,'blockedThroatSegments':path_blocks,'componentChanges':changes,'protectedParts':preserved,
            'sourceSha256':hashlib.sha256((BASE/'airframe.blend').read_bytes()).hexdigest(),
            'gameVerified':False,'referenceFidelityApproved':False,'aerodynamicsSimulated':False}


def render(folder,comparison=False):
    scene=bpy.context.scene;camera=scene.camera;saved=camera.matrix_world.copy();data=camera.data.copy()
    directions={'front':(0,-14,0),'rear':(0,14,0),'top':(0,0,14),'bottom':(0,0,-14),
                'left':(-14,0,0),'right':(14,0,0),'underside':(6,-12,-7),'rear-oblique':(6,12,7)}
    names=('hero','top','rear-oblique') if comparison else ('hero',*directions)
    for name in names:
        if name=='hero':camera.data=data.copy();camera.matrix_world=saved
        else:
            camera.data.type='ORTHO';camera.data.ortho_scale=12.0 if name in ('top','bottom') else 10.1
            camera.data.shift_x=0;camera.data.shift_y=0
            camera.location=Vector((0,.21,.08))+Vector(directions[name]);v16.look(camera,(0,.21,.08))
        light=None
        if name in ('bottom','underside','rear','left','right'):
            bpy.ops.object.light_add(type='AREA',location=(0,-2,-6));light=bpy.context.object
            light.data.energy=1050;light.data.size=7;v16.look(light,(0,0,0))
        scene.render.filepath=str(folder/(name+'.png'));bpy.ops.render.render(write_still=True)
        if light:bpy.data.objects.remove(light,do_unlink=True)
    camera.data=data;camera.matrix_world=saved


def main():
    OUT.mkdir(parents=True,exist_ok=True);baseline=OUT/'baseline-v17';baseline.mkdir(exist_ok=True)
    bpy.ops.wm.open_mainfile(filepath=str(BASE/'airframe.blend'))
    render(baseline,True)
    protected,oldhull,changes=deform();report=validate(protected,oldhull,changes)
    (OUT/'validation.json').write_text(json.dumps(report,indent=2))
    print('TAIL_CHECKS',json.dumps({k:v for k,v in report.items() if k not in ('probes','componentChanges','protectedParts')}),flush=True)
    render(OUT)
    bpy.context.scene['asset_status']='UNAPPROVED_SWEPT_TAIL_DESIGN_PROPOSAL'
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'airframe.blend'))
    bpy.ops.object.select_all(action='DESELECT')
    for obj in bpy.context.scene.objects:
        if obj.type in ('MESH','CURVE','EMPTY'):obj.select_set(True)
    bpy.ops.export_scene.gltf(filepath=str(OUT/'airframe.glb'),export_format='GLB',use_selection=True)
    if not report['passed']:raise RuntimeError(report['checks'])


if __name__=='__main__':main()
