"""One continuous bilateral proportion field for wing, lift fans and aft propulsion."""
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
BASE=ROOT/'docs/design/reference-propulsion-v16'
OUT=ROOT/'docs/design/reference-balance-v17'
FAN_SCALE=1.1333333333333333
OLD_SPAN=9.67327334
NEW_SPAN=8.90


def smooth(t):
    t=max(0.,min(1.,t));return t*t*(3-2*t)


def shape(p):
    x,y,z=p
    sign=-1 if x<0 else 1
    dx,dy=abs(x)-1.91,y-.01826677
    radius=math.hypot(dx,dy)
    fan_weight=1-smooth((radius-.955)/.50)
    fan_scale=1+(FAN_SCALE-1)*fan_weight
    xx=1.91+dx*fan_scale;yy=.01826677+dy*fan_scale
    if xx>2.99:
        xx=2.99+(xx-2.99)*(NEW_SPAN/2-2.99)/(OLD_SPAN/2-2.99)
    rear=smooth((y-1.05)/.38)*(1-smooth((abs(x)-1.25)/1.10))
    return Vector((sign*xx*(1-.20*rear),yy,z*(1-.24*rear)))


def geometry(obj):
    graph=bpy.context.evaluated_depsgraph_get();ev=obj.evaluated_get(graph);m=ev.to_mesh()
    points=[ev.matrix_world@v.co for v in m.vertices];faces=[tuple(p.vertices) for p in m.polygons]
    ev.to_mesh_clear();return points,faces


def build():
    oldnose=[];changes={}
    for obj in list(bpy.context.scene.objects):
        if obj.type not in ('MESH','CURVE'):continue
        graph=bpy.context.evaluated_depsgraph_get();ev=obj.evaluated_get(graph)
        m=bpy.data.meshes.new_from_object(ev,preserve_all_data_layers=True,depsgraph=graph)
        old=[ev.matrix_world@v.co for v in m.vertices];new=[shape(p) for p in old]
        if obj.name.startswith(('H01','S0')):oldnose.extend(p for p in old if p.y< -1.6)
        for v,p in zip(m.vertices,new):v.co=p
        m.update();name=obj.name
        replacement=bpy.data.objects.new(name+'_v17',m);bpy.context.collection.objects.link(replacement)
        bpy.data.objects.remove(obj,do_unlink=True);replacement.name=name
        changes[name]={'vertices':len(new),'maxDisplacement':max((a-b).length for a,b in zip(old,new))}
    bpy.context.view_layer.update()
    return oldnose,changes


def validate(oldnose,changes):
    vs=[];fs=[];parts={}
    for obj in bpy.context.scene.objects:
        if obj.type not in ('MESH','CURVE'):continue
        points,faces=geometry(obj);parts[obj.name]=(points,faces)
        offset=len(vs);vs.extend(points);fs.extend([tuple(offset+i for i in f) for f in faces])
    kd=kdtree.KDTree(len(vs))
    for i,p in enumerate(vs):kd.insert(p,i)
    kd.balance();mirror=max(kd.find(Vector((-p.x,p.y,p.z)))[2] for p in vs)
    nose_error=max(kd.find(p)[2] for p in oldnose)
    body=next(o for o in bpy.context.scene.objects if o.name.startswith('H01_'))
    bm=bmesh.new();bm.from_mesh(body.data);bmesh.ops.remove_doubles(bm,verts=list(bm.verts),dist=1e-5)
    bad=sum(not e.is_manifold for e in bm.edges);vol=bm.calc_volume(signed=True);bm.free()
    tree=BVHTree.FromPolygons(vs,fs)
    probes=[]
    for p in json.loads((BASE/'validation.json').read_text())['probes']:
        start=shape(Vector(p['origin']))
        end=shape(Vector(p['hit']))
        direction=(end-start).normalized()
        hit,normal,index,d=tree.ray_cast(start,direction)
        probes.append({'type':p['type'],'start':list(start),'depth':d,
                       'hit':list(hit) if hit else None,'expectedDepth':(end-start).length})
    oldwidth=.64;mouth_left=shape(Vector((.57-.32,1.30,.585)));mouth_right=shape(Vector((.57+.32,1.30,.585)))
    bodyvs,_=parts[body.name]
    span=max(p.x for p in bodyvs)-min(p.x for p in bodyvs)
    fan_part=parts['R01_DuctWall'][0]
    fan_right=[p for p in fan_part if p.x>0]
    fan_diameter=max(p.x for p in fan_right)-min(p.x for p in fan_right)
    ppoints=[p for name,(points,_) in parts.items() if name.startswith('P') for p in points]
    rotor_gap=min(math.hypot(abs(p.x)-1.91,p.y-.01826677)-1.02 for p in ppoints)
    upper=shape(Vector((0,2.641,.258))).z;lower=shape(Vector((0,2.641,-.056))).z
    nozzle_width=2*shape(Vector((1.035,2.586,.10))).x
    checks={'bilateralGeometry':mirror<1e-5,'finiteCoordinates':all(math.isfinite(v) for p in vs for v in p),
            'noseUnchanged':nose_error<1e-5,'watertightHull':bad==0,'positiveHullVolume':vol>0,
            'targetWingspan':abs(span-NEW_SPAN)<1e-5,'fanDiameterEnlarged':abs(fan_diameter-1.81*FAN_SCALE)<.001,
            'newPartsClearOfFans':rotor_gap>.05,
            'intakeRemainsOpen':all(p['depth'] is not None and p['depth']>.15 for p in probes if p['type']=='intake'),
            'exhaustRemainsOpen':all(p['depth'] is not None and p['depth']>.25 for p in probes if p['type']=='exhaust'),
            'noSkinIntrusionInThroats':all(p['depth'] is not None and p['depth']>p['expectedDepth']*.80 for p in probes),
            'fullTwoFanBladeSetRetained':sum(name.startswith('R06_Blade') for name in parts)==12}
    return {'checks':checks,'passed':all(checks.values()),'mirrorError':mirror,'noseError':nose_error,
            'nonmanifoldHullEdges':bad,'fanClearance':rotor_gap,'probes':probes,
            'measurements':{'span':span,'fanNominalDiameter':2.04,'fanDiameterToSpan':2.04/span,
                'outerWingSpanChangePercent':(span/OLD_SPAN-1)*100,'fanDiameterChangePercent':(FAN_SCALE-1)*100,
                'intakeWidth':mouth_right.x-mouth_left.x,'nozzleWidth':nozzle_width,'nozzleHeight':upper-lower,
                'nozzleWidthToSpan':nozzle_width/span},
            'sourceSha256':hashlib.sha256((BASE/'airframe.blend').read_bytes()).hexdigest(),
            'gameVerified':False,'referenceFidelityApproved':False,'aerodynamicsSimulated':False,
            'scope':'Unified visual proportions; topology and cavity clearance only. Not thrust or flight validation.',
            'componentChanges':changes}


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    bpy.ops.wm.open_mainfile(filepath=str(BASE/'airframe.blend'))
    oldnose,changes=build();report=validate(oldnose,changes)
    (OUT/'validation.json').write_text(json.dumps(report,indent=2))
    print('BALANCE_CHECKS',json.dumps({k:v for k,v in report.items() if k not in ('componentChanges','probes')}),flush=True)
    spec=importlib.util.spec_from_file_location('propulsion',Path(__file__).with_name('build_propulsion_v16.py'))
    prior=importlib.util.module_from_spec(spec);spec.loader.exec_module(prior);prior.OUT=OUT;prior.renders(False)
    scene=bpy.context.scene;camera=scene.camera;saved=camera.matrix_world.copy();data=camera.data.copy()
    # The extended aft apron exceeds the old top/bottom camera's vertical field.
    for name,z in (('top',14),('bottom',-14)):
        camera.data.type='ORTHO';camera.data.ortho_scale=10.9;camera.data.shift_x=0;camera.data.shift_y=0
        camera.location=(0,-.09,z);prior.look(camera,(0,-.09,.08))
        light=None
        if name=='bottom':
            bpy.ops.object.light_add(type='AREA',location=(0,-2,-6));light=bpy.context.object
            light.data.energy=1050;light.data.size=7;prior.look(light,(0,0,0))
        scene.render.filepath=str(OUT/(name+'.png'));bpy.ops.render.render(write_still=True)
        if light:bpy.data.objects.remove(light,do_unlink=True)
    camera.data=data;camera.matrix_world=saved
    scene['asset_status']='UNAPPROVED_UNIFIED_PROPORTION_PROPOSAL'
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'airframe.blend'))
    bpy.ops.object.select_all(action='DESELECT')
    for obj in scene.objects:
        if obj.type in ('MESH','CURVE','EMPTY'):obj.select_set(True)
    bpy.ops.export_scene.gltf(filepath=str(OUT/'airframe.glb'),export_format='GLB',use_selection=True)
    if not report['passed']:raise RuntimeError(report['checks'])


if __name__=='__main__':main()
