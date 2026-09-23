"""Read-only inspection of the V15 intake-like fairings and aft surfaces."""
import hashlib
import json
from pathlib import Path
import bpy
import bmesh
from mathutils import Vector
from mathutils.bvhtree import BVHTree

ROOT=Path(__file__).resolve().parents[3]
SOURCE=ROOT/'docs/design/reference-nose-v15/airframe.blend'
OUT=ROOT/'docs/design/aft-audit-v15'


def geometry(obj,graph):
    ev=obj.evaluated_get(graph);mesh=ev.to_mesh()
    vertices=[ev.matrix_world@v.co for v in mesh.vertices]
    faces=[list(p.vertices) for p in mesh.polygons]
    ev.to_mesh_clear();return vertices,faces


def section(vertices,faces,x):
    segments=[]
    for f in faces:
        hits=[]
        for ai,bi in zip(f,f[1:]+f[:1]):
            a,b=vertices[ai],vertices[bi]
            if min(a.x,b.x)<=x<max(a.x,b.x):
                p=a.lerp(b,(x-a.x)/(b.x-a.x));hits.append([p.y,p.z])
        if len(hits)==2:segments.append(hits)
    return segments


def hit(tree,origin,direction):
    pos,normal,index,distance=tree.ray_cast(Vector(origin),Vector(direction))
    return None if pos is None else {'point':list(pos),'distance':distance,'normal':list(normal)}


def look(obj,target):
    obj.rotation_euler=(Vector(target)-obj.location).to_track_quat('-Z','Y').to_euler()


def main():
    OUT.mkdir(parents=True,exist_ok=True);before=hashlib.sha256(SOURCE.read_bytes()).hexdigest()
    bpy.ops.wm.open_mainfile(filepath=str(SOURCE));graph=bpy.context.evaluated_depsgraph_get()
    meshes={o.name:geometry(o,graph) for o in bpy.context.scene.objects if o.type=='MESH'}
    trees={k:BVHTree.FromPolygons(v,f) for k,(v,f) in meshes.items()}
    fairing=next(k for k in meshes if k.startswith('H09_'))
    body=next(k for k in meshes if k.startswith('H01_'))
    probes=[]
    for z in (.19,.23,.27):
        probes.append({'label':'intake_centerline','origin':[.55,1.70,z],
                       'hits':{k:hit(t,[.55,1.70,z],[0,1,0]) for k,t in trees.items() if k.startswith(('H09','H10'))}})
    rear_hit=hit(trees[fairing],[.55,2.8,.1],[0,-1,0])
    panel_gaps=[]
    for key in meshes:
        if not key.startswith('B01_'):continue
        vs,_=meshes[key];ys=[v.y for v in vs];gap_samples=[]
        for x in (-.25,0,.25):
            for i in range(1,12):
                y=min(ys)+(max(ys)-min(ys))*i/12
                panel_top=hit(trees[key],[x,y,2],[0,0,-1])
                hull_bottom=hit(trees[body],[x,y,-2],[0,0,1])
                if panel_top and hull_bottom:
                    gap_samples.append({'xy':[x,y],'gap':hull_bottom['point'][2]-panel_top['point'][2],
                                        'hullBottom':hull_bottom['point'][2],'panelTop':panel_top['point'][2]})
        panel_gaps.append({'object':key,'samples':len(gap_samples),'worst':max(gap_samples,key=lambda s:s['gap'])})
    selected=('H01','H09','H10','H11','H12','H13','H14','B01')
    sections={k:section(v,f,.55) for k,(v,f) in meshes.items() if k.startswith(selected)}
    # The center plane crosses the underside panels, unlike X=.55.
    bottom_sections={k:section(v,f,0) for k,(v,f) in meshes.items() if k.startswith(('H01','B01'))}
    report={'sourceSha256':before,'modelEdited':False,'frontProbes':probes,
            'rearFairingCapHit':rear_hit,'undersidePanelGaps':panel_gaps,
            'aftObjects':[k for k in meshes if k.startswith(('H09','H10','H11','H12','H13','H14'))],
            'explicitNozzleNamedObjects':[k for k in meshes if any(t in k.lower() for t in ('thruster','nozzle','exhaust'))],
            'interpretation':'Front apertures and aft outlets are assessed from geometry and builder code, not names alone.',
            'referenceRearViewAvailable':False,'aerodynamicsAssessed':False}
    (OUT/'inspection.json').write_text(json.dumps(report,indent=2))
    (OUT/'sections.json').write_text(json.dumps({'x055':sections,'x0':bottom_sections},indent=2))
    scene=bpy.context.scene;camera=scene.camera;scene.render.resolution_x=1440;scene.render.resolution_y=900
    bpy.ops.object.light_add(type='AREA',location=(0,5,-3));li=bpy.context.object
    li.data.energy=850;li.data.size=5;look(li,(0,1.8,0))
    for name,loc,target,scale in (
        ('rear-close',(0,7,.65),(0,1.85,.03),3.1),
        ('rear-quarter',(4,7,2.8),(0,1.9,.12),3.5),
        ('underside-close',(2,5,-2),(0,1.20,-.10),3.5)):
        camera.data.type='ORTHO';camera.data.ortho_scale=scale
        camera.data.shift_x=0;camera.data.shift_y=0;camera.location=loc;look(camera,target)
        scene.render.filepath=str(OUT/(name+'.png'));bpy.ops.render.render(write_still=True)
    report['sourceUnchanged']=before==hashlib.sha256(SOURCE.read_bytes()).hexdigest()
    (OUT/'inspection.json').write_text(json.dumps(report,indent=2))
    print('AFT_INSPECTION',json.dumps(report),flush=True)


if __name__=='__main__':main()
