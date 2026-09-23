"""Local symmetric nose proposal derived from the canonical V14 mesh."""
import hashlib
import json
import math
from pathlib import Path

import bpy
import bmesh
from mathutils import Matrix, Vector, kdtree

ROOT=Path(__file__).resolve().parents[3]
BASE=ROOT/'docs/design/reference-wingtip-v14'
OUT=ROOT/'docs/design/reference-nose-v15'
EXTENSION=.28
ANCHOR_Y=-1.60
TIP_Y=-2.50
WIDTH=1.15


def smooth(t):
    t=max(0.,min(1.,t));return t*t*(3-2*t)


def shape(p):
    x,y,z=p
    weight=smooth((ANCHOR_Y-y)/(ANCHOR_Y-TIP_Y))
    lateral=max(0.,1-abs(x)/WIDTH)
    f=weight*lateral
    return Vector((x*(1-.16*f),y-EXTENSION*f,-.12+(z+.12)*(1-.08*f)))


def deform():
    graph=bpy.context.evaluated_depsgraph_get();changes={};source_hull=[];new_hull=[]
    for obj in list(bpy.context.scene.objects):
        if obj.type not in ('MESH','CURVE'):continue
        evaluated=obj.evaluated_get(graph)
        mesh=bpy.data.meshes.new_from_object(evaluated,preserve_all_data_layers=True,depsgraph=graph)
        points=[evaluated.matrix_world@v.co for v in mesh.vertices]
        changed=0;newpoints=[]
        for point in points:
            if obj.name.startswith(('S03_Chin','S04_Chin','S05_Chin')):
                # Keep the optical assembly rigid and slightly behind the new tip.
                new=point+Vector((0,-.20,.015))
            elif obj.name.startswith(('S06_Cheek','S07_Cheek')):
                anchor=Vector((math.copysign(.67,point.x),-2.53,-.235))
                new=point+(shape(anchor)-anchor)
            else:new=shape(point)
            changed+=int((new-point).length>1e-7);newpoints.append(new)
        changes[obj.name]={'vertices':len(points),'changed':changed}
        if obj.name.startswith('H01_'):
            source_hull=points;new_hull=newpoints
        if not changed:
            bpy.data.meshes.remove(mesh);continue
        name=obj.name
        replacement=bpy.data.objects.new(name+'_v15',mesh)
        bpy.context.collection.objects.link(replacement)
        for vertex,point in zip(mesh.vertices,newpoints):vertex.co=point
        mesh.update();bpy.data.objects.remove(obj,do_unlink=True);replacement.name=name
    bpy.context.view_layer.update()
    return changes,source_hull,new_hull


def inspect(changes,old,new):
    graph=bpy.context.evaluated_depsgraph_get();points=[]
    for obj in bpy.context.scene.objects:
        if obj.type not in ('MESH','CURVE'):continue
        ev=obj.evaluated_get(graph);mesh=ev.to_mesh()
        points.extend(ev.matrix_world@p.co for p in mesh.vertices);ev.to_mesh_clear()
    tree=kdtree.KDTree(len(points))
    for i,p in enumerate(points):tree.insert(p,i)
    tree.balance()
    error=max(tree.find(Vector((-p.x,p.y,p.z)))[2] for p in points)
    body=next(o for o in bpy.context.scene.objects if o.name.startswith('H01_'))
    bm=bmesh.new();bm.from_mesh(body.data)
    bmesh.ops.remove_doubles(bm,verts=list(bm.verts),dist=1e-5)
    bmesh.ops.recalc_face_normals(bm,faces=list(bm.faces))
    nonmanifold=sum(not e.is_manifold for e in bm.edges);volume=bm.calc_volume(signed=True);bm.free()
    x=lambda points,axis,minmax:minmax(p[axis] for p in points)
    protected=[(o,n) for o,n in zip(old,new) if o.y>=ANCHOR_Y or abs(o.x)>=WIDTH]
    checks={
        'bilateralGeometry':error<1e-5,
        'watertightHull':nonmanifold==0,
        'positiveHullVolume':volume>0,
        'finiteCoordinates':all(math.isfinite(v) for p in points for v in p),
        'aftAndWingHullUnchanged':all((o-n).length<1e-7 for o,n in protected),
        'rotorsUnchanged':all(v['changed']==0 for k,v in changes.items() if k.startswith('R0')),
        'wingtipPartsUnchanged':all(v['changed']==0 for k,v in changes.items() if k.startswith(('W02','W04','W07','W08'))),
        'rearPartsUnchanged':all(v['changed']==0 for k,v in changes.items() if k.startswith(('H09','H10','H11','H12','H13','H14'))),
        'hullNoseExtended':abs(x(new,1,min)-x(old,1,min)+EXTENSION)<.005,
        'spanUnchanged':abs(x(old,0,max)-x(new,0,max))<1e-7,
    }
    return {'asset':'reference-nose-v15','checks':checks,'passed':all(checks.values()),
            'mirrorError':error,'nonmanifoldHullEdges':nonmanifold,'hullVolume':volume,
            'hullTipBefore':x(old,1,min),'hullTipAfter':x(new,1,min),
            'extensionFractionOfPreviousHullLength':EXTENSION/(x(old,1,max)-x(old,1,min)),
            'sourceSha256':hashlib.sha256((BASE/'airframe.blend').read_bytes()).hexdigest(),
            'componentChanges':changes,'referenceFidelityApproved':False,
            'gameVerified':False,'aerodynamicSimulationPerformed':False,
            'scope':'Local visual design proposal. Not a reference reconstruction improvement claim.'}


def look(obj,target):
    obj.rotation_euler=(Vector(target)-obj.location).to_track_quat('-Z','Y').to_euler()


def render_views():
    scene=bpy.context.scene;camera=scene.camera
    hero_matrix=camera.matrix_world.copy();hero_data=camera.data.copy()
    directions={'front':(0,-14,0),'rear':(0,14,0),'top':(0,0,14),'bottom':(0,0,-14),
                'left':(-14,0,0),'right':(14,0,0),'underside':(6,-12,-7),'rear-oblique':(6,12,7)}
    for name in ('hero',*directions):
        if name=='hero':camera.data=hero_data.copy();camera.matrix_world=hero_matrix
        else:
            camera.data.type='ORTHO';camera.data.ortho_scale=9.9
            camera.data.shift_x=0;camera.data.shift_y=0
            camera.location=Vector((0,-.3,.08))+Vector(directions[name]);look(camera,(0,-.3,.08))
        light=None
        if name in ('bottom','underside','rear','left','right'):
            bpy.ops.object.light_add(type='AREA',location=(0,-2,-6));light=bpy.context.object
            light.data.energy=1050;light.data.size=7;look(light,(0,0,0))
        scene.render.filepath=str(OUT/(name+'.png'));bpy.ops.render.render(write_still=True)
        if light:bpy.data.objects.remove(light,do_unlink=True)
    camera.data=hero_data;camera.matrix_world=hero_matrix


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    bpy.ops.wm.open_mainfile(filepath=str(BASE/'airframe.blend'))
    changes,old,new=deform();report=inspect(changes,old,new)
    (OUT/'validation.json').write_text(json.dumps(report,indent=2))
    if not report['passed']:raise RuntimeError(report['checks'])
    render_views()
    scene=bpy.context.scene;scene['asset_status']='UNAPPROVED_LOCAL_NOSE_DESIGN_PROPOSAL'
    scene['nose_extension']=EXTENSION;scene['nose_anchor_y']=ANCHOR_Y
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'airframe.blend'))
    bpy.ops.object.select_all(action='DESELECT')
    for obj in scene.objects:
        if obj.type in ('MESH','CURVE','EMPTY'):obj.select_set(True)
    bpy.ops.export_scene.gltf(filepath=str(OUT/'airframe.glb'),export_format='GLB',use_selection=True)
    print('NOSE_REPORT',json.dumps({k:v for k,v in report.items() if k!='componentChanges'}),flush=True)


if __name__=='__main__':main()
