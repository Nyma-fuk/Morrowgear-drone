"""Low-profile, unfolded-wing proposal. Visual aircraft design, not flight validation."""
import hashlib
import importlib.util
import json
import math
import sys
from pathlib import Path

import bpy
import bmesh
from mathutils import Vector, kdtree
from mathutils.bvhtree import BVHTree

ROOT=Path(__file__).resolve().parents[3]
BASE=ROOT/'docs/design/reference-swept-tail-v18'
OUT=ROOT/'docs/design/reference-low-profile-v19'
spec=importlib.util.spec_from_file_location('swept_v18',Path(__file__).with_name('build_swept_tail_v18.py'))
v18=importlib.util.module_from_spec(spec);spec.loader.exec_module(v18)
BODY_HEIGHT_SCALE=.66
TIP_HEIGHT_SCALE=.30
TIP_POINTS=[v18.shape(p) for p in v18.TIP]


def plane_height(p,a,b,c):
    x,y=p;det=(b.x-a.x)*(c.y-a.y)-(c.x-a.x)*(b.y-a.y)
    u=((x-a.x)*(c.y-a.y)-(c.x-a.x)*(y-a.y))/det
    v=((b.x-a.x)*(y-a.y)-(x-a.x)*(b.y-a.y))/det
    return a.z+u*(b.z-a.z)+v*(c.z-a.z)


def tip_reference_height(x,y):
    a,b,c,d=TIP_POINTS
    side=(c.x-a.x)*(y-a.y)-(c.y-a.y)*(x-a.x)
    side_b=(c.x-a.x)*(b.y-a.y)-(c.y-a.y)*(b.x-a.x)
    return plane_height((x,y),a,b,c) if side*side_b>=0 else plane_height((x,y),a,c,d)


def shape(p):
    x,y,z=p
    outer=v18.smooth((abs(x)-2.96)/.72)
    body_z=z*BODY_HEIGHT_SCALE
    tip_z=.04+(z-tip_reference_height(abs(x),y))*TIP_HEIGHT_SCALE
    return Vector((x,y,body_z*(1-outer)+tip_z*outer))


def deform():
    before={};changes={}
    for obj in list(bpy.context.scene.objects):
        if obj.type not in ('MESH','CURVE'):continue
        graph=bpy.context.evaluated_depsgraph_get();ev=obj.evaluated_get(graph)
        mesh=bpy.data.meshes.new_from_object(ev,preserve_all_data_layers=True,depsgraph=graph)
        old=[ev.matrix_world@p.co for p in mesh.vertices];new=[shape(p) for p in old]
        before[obj.name]=old
        for p,q in zip(mesh.vertices,new):p.co=q
        # Recompute shading normals after deformation instead of retaining the old surface frame.
        if mesh.has_custom_normals:mesh.normals_split_custom_set([(0.,0.,0.)]*len(mesh.loops))
        mesh.update();name=obj.name
        replacement=bpy.data.objects.new(name+'_v19',mesh);bpy.context.collection.objects.link(replacement)
        bpy.data.objects.remove(obj,do_unlink=True);replacement.name=name
        changes[name]={'vertices':len(new),'maxDisplacement':max((p-q).length for p,q in zip(old,new))}
    bpy.context.view_layer.update()
    return before,changes


def clean_edge_trim():
    # The old raised trim crossed the triangulated skin. Remove the redundant
    # strip and keep the actual continuous hull edge as the visible silhouette.
    ribbons=[o for o in bpy.context.scene.objects if o.name.startswith('W02_MachinedEdge')]
    removed=[o.name for o in ribbons]
    body=next(o for o in bpy.context.scene.objects if o.name.startswith('H01_'))
    for obj in ribbons:bpy.data.objects.remove(obj,do_unlink=True)
    bm=bmesh.new();bm.from_mesh(body.data)
    bmesh.ops.remove_doubles(bm,verts=list(bm.verts),dist=1e-5)
    bmesh.ops.recalc_face_normals(bm,faces=list(bm.faces))
    sharp=[e for e in bm.edges if e.is_manifold and e.calc_face_angle()>math.radians(38)]
    bmesh.ops.split_edges(bm,edges=sharp)
    for f in bm.faces:f.smooth=True;f.material_index=0
    bm.to_mesh(body.data);bm.free();body.data.update()
    weighted=body.modifiers.new('AreaWeightedSurfaceNormals','WEIGHTED_NORMAL')
    weighted.mode='FACE_AREA_WITH_ANGLE';weighted.weight=40;weighted.keep_sharp=True
    bpy.context.view_layer.objects.active=body
    bpy.ops.object.modifier_apply(modifier=weighted.name)
    return removed


def extent(points,axis):
    return max(p[axis] for p in points)-min(p[axis] for p in points)


def validate(before,changes,removed):
    vertices=[];faces=[];parts={};xy_error=0.
    for obj in bpy.context.scene.objects:
        if obj.type not in ('MESH','CURVE'):continue
        ps,fs=v18.v17.geometry(obj);parts[obj.name]=ps;n=len(vertices)
        vertices.extend(ps);faces.extend(tuple(n+i for i in face) for face in fs)
        oldxy=kdtree.KDTree(len(before[obj.name]))
        for i,p in enumerate(before[obj.name]):oldxy.insert(Vector((p.x,p.y,0)),i)
        oldxy.balance()
        xy_error=max(xy_error,max(oldxy.find(Vector((p.x,p.y,0)))[2] for p in ps))
    kd=kdtree.KDTree(len(vertices))
    for i,p in enumerate(vertices):kd.insert(p,i)
    kd.balance();mirror=max(kd.find(Vector((-p.x,p.y,p.z)))[2] for p in vertices)
    body=next(o for o in bpy.context.scene.objects if o.name.startswith('H01_'))
    oldh=before[body.name];newh=parts[body.name]
    bm=bmesh.new();bm.from_mesh(body.data);bmesh.ops.remove_doubles(bm,verts=list(bm.verts),dist=1e-5)
    bad=sum(not e.is_manifold for e in bm.edges);volume=bm.calc_volume(signed=True);bm.free()
    base_report=json.loads((BASE/'validation.json').read_text())
    tips=[Vector(p) for p in base_report['tipAfter']];newtips=[shape(p) for p in tips]
    tree=BVHTree.FromPolygons(vertices,faces);probes=[]
    for source in base_report['probes']:
        a,b=shape(Vector(source['start'])),shape(Vector(source['hit']))
        hp,_,_,depth=tree.ray_cast(a,(b-a).normalized())
        probes.append({'type':source['type'],'depth':depth,'expectedDepth':(b-a).length,
                       'start':list(a),'hit':list(hp) if hp else None})
    # XY is unchanged; the vertical derivative is a positive convex combination.
    derivatives=[BODY_HEIGHT_SCALE*(1-v18.smooth((abs(p.x)-2.96)/.72))+
                 TIP_HEIGHT_SCALE*v18.smooth((abs(p.x)-2.96)/.72) for p in oldh]
    fans=[name for name in parts if name.startswith('R0')]
    blades=[name for name in fans if name.startswith('R06_Blade_')]
    checks={
        'bilateralGeometry':mirror<1e-5,
        'finiteCoordinates':all(math.isfinite(c) for p in vertices for c in p),
        'planformAndFanCirclesRetained':xy_error<1e-5,
        'allFunctionalComponentsRetained':set(before)-set(removed)==set(parts),
        'rotorPartsRetained':len(fans)>=7 and all(len(before[n])==len(parts[n]) for n in fans),
        'twelveMirroredBladePairsRetained':len(blades)==12,
        'watertightHull':bad==0,
        'positiveHullVolume':volume>0,
        'orientationPreservingField':min(derivatives)>0,
        'wingtipTopBelow010':max(p.z for p in newtips)<.10,
        'wingtipRiseRemoved':max(p.z for p in newtips)<max(p.z for p in tips)*.20,
        'tipLandmarksCoplanar':max(p.z for p in newtips)-min(p.z for p in newtips)<1e-6,
        'centralBodyThicknessReduced':extent([p for p in newh if abs(p.x)<.58],2)<
                                      extent([p for p in oldh if abs(p.x)<.58],2)*.67,
        'intakeOpen':all(p['depth'] is not None and p['depth']>.15 for p in probes if p['type']=='intake'),
        'exhaustOpen':all(p['depth'] is not None and p['depth']>.25 for p in probes if p['type']=='exhaust'),
        'throatsRemainUnblocked':all(p['depth'] is not None and p['depth']>p['expectedDepth']*.95 for p in probes),
    }
    return {'checks':checks,'passed':all(checks.values()),'mirrorError':mirror,'nonmanifoldHullEdges':bad,
            'measurements':{'span':extent(newh,0),'planformMaxDisplacement':xy_error,
                'centralBodyHeightBefore':extent([p for p in oldh if abs(p.x)<.58],2),
                'centralBodyHeightAfter':extent([p for p in newh if abs(p.x)<.58],2),
                'tipTopBefore':max(p.z for p in tips),'tipTopAfter':max(p.z for p in newtips),
                'bodyHeightScale':BODY_HEIGHT_SCALE,'tipSectionHeightScale':TIP_HEIGHT_SCALE},
            'tipBefore':[list(p) for p in tips],'tipAfter':[list(p) for p in newtips],
            'probes':probes,'componentChanges':changes,'redundantRaisedEdgeTrimRemoved':removed,
            'sourceSha256':hashlib.sha256((BASE/'airframe.blend').read_bytes()).hexdigest(),
            'gameVerified':False,'referenceFidelityApproved':False,'aerodynamicsSimulated':False}


def quick_render():
    scene=bpy.context.scene;camera=scene.camera;saved=camera.matrix_world.copy();data=camera.data.copy()
    for name,direction in (('hero',None),('front',(0,-14,0)),('right',(14,0,0))):
        if direction:
            camera.data.type='ORTHO';camera.data.ortho_scale=10.1
            camera.data.shift_x=camera.data.shift_y=0
            camera.location=Vector((0,.21,.08))+Vector(direction);v18.v16.look(camera,(0,.21,.08))
        else:camera.data=data.copy();camera.matrix_world=saved
        scene.render.filepath=str(OUT/(name+'.png'));bpy.ops.render.render(write_still=True)
    camera.data=data;camera.matrix_world=saved


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    bpy.ops.wm.open_mainfile(filepath=str(BASE/'airframe.blend'))
    before,changes=deform();removed=clean_edge_trim();report=validate(before,changes,removed)
    (OUT/'validation.json').write_text(json.dumps(report,indent=2))
    print('LOW_PROFILE_CHECKS',json.dumps(report['checks']),flush=True)
    if '--preview' in sys.argv:quick_render()
    else:v18.render(OUT)
    bpy.context.scene['asset_status']='UNAPPROVED_LOW_PROFILE_DESIGN_PROPOSAL'
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'airframe.blend'))
    if '--preview' not in sys.argv:
        bpy.ops.object.select_all(action='DESELECT')
        for obj in bpy.context.scene.objects:
            if obj.type in ('MESH','CURVE','EMPTY'):obj.select_set(True)
        bpy.ops.export_scene.gltf(filepath=str(OUT/'airframe.glb'),export_format='GLB',use_selection=True)
    if not report['passed']:raise RuntimeError(report['checks'])


if __name__=='__main__':main()
