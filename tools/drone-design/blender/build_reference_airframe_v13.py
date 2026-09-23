"""Render the rounded-edge/tapered-section candidate with the approved planform."""
import argparse
import importlib.util
import json
import math
import sys
from pathlib import Path

import bpy
import bmesh
from mathutils import Vector, geometry
from bpy_extras.object_utils import world_to_camera_view

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
sys.path.insert(0,str(ROOT/'tools'))
import reference_airframe_sections as sections

spec=importlib.util.spec_from_file_location('airframe_v11',HERE/'build_reference_airframe_v11.py')
a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
v,b=a.v,a.b
OUT=ROOT/'docs/design/reference-airframe-v13'
OUTLINE=[q[:2] for q in sections.BOUNDARY]
CAMERA=json.loads((ROOT/'docs/design/reference-airframe-v11/camera-fit.json').read_text())['cameraParameters']


def hull():
    coords=[];edges=[]
    def path(points,closed=False):
        points=list(points);dense=[]
        for p,q in zip(points,points[1:]+(points[:1] if closed else [])):
            p,q=Vector(p),Vector(q);steps=max(1,math.ceil((q-p).length/.035))
            dense.extend(p.lerp(q,i/steps) for i in range(steps))
        if not closed:dense.append(Vector(points[-1]))
        start=len(coords);coords.extend(dense)
        edges.extend((start+i,start+i+1) for i in range(len(dense)-1))
        if closed:edges.append((len(coords)-1,start))
    circle=[(a.CX+a.RR*math.cos(i*math.tau/160),a.CY+a.RR*math.sin(i*math.tau/160)) for i in range(160)]
    path(OUTLINE,True);path(circle,True)
    path([OUTLINE[4],OUTLINE[6]]);path([OUTLINE[4],OUTLINE[7]])
    for x in (.58,1.18,1.26,2.81,3.58,3.98):path([(x,-3),(x,2.3)])
    for y,z in sections.CROWN[1:-1]:path([(0,y),(4.8,y)])
    for x in [i*4.74/95 for i in range(96)]:
        leading,trailing=sections.chord_bounds(x)
        for i in range(97):
            t=(1-math.cos(math.pi*i/96))/2;y=leading+(trailing-leading)*t
            q=(x,y)
            if a.inside(q,OUTLINE) and not a.inside(q,circle):coords.append(Vector(q))
    xy,_,triangles,*_=geometry.delaunay_2d_cdt(coords,edges,[],0,1e-6)
    faces=[]
    for f in triangles:
        center=sum((xy[i] for i in f),Vector((0,0)))/len(f)
        if a.inside(center,OUTLINE) and not a.inside(center,circle):faces.append(tuple(f))
    used=sorted({i for f in faces for i in f});remap={old:new for new,old in enumerate(used)}
    xy=[xy[i] for i in used];faces=[tuple(remap[i] for i in f) for f in faces]
    count=len(xy);vertices=[(max(0,x),y,sections.skin(x,y,top)) for top in (False,True) for x,y in xy]
    surface=[f[::-1] for f in faces]+[tuple(i+count for i in f) for f in faces];uses={}
    for f in faces:
        for p,q in zip(f,f[1:]+f[:1]):uses[tuple(sorted((p,q)))]=uses.get(tuple(sorted((p,q))),0)+1
    for (p,q),n in uses.items():
        if n==1 and not (abs(xy[p].x)<1e-5 and abs(xy[q].x)<1e-5):surface.append((p,q,q+count,p+count))
    body=v.rawmesh('H01_ContinuousLiftingBody',vertices,surface,v.A)
    b.mirror(body);bpy.context.view_layer.objects.active=body;bpy.ops.object.modifier_apply(modifier='Exact_Centerline_Mirror')
    return body


def render(camera,name,location):
    scene=bpy.context.scene
    camera.data.type='ORTHO';camera.data.ortho_scale=9.9;camera.data.shift_x=0;camera.data.shift_y=0
    target=Vector((0,-.3,.08))
    direction={'front':(0,-14,0),'rear':(0,14,0),'top':(0,0,14),'bottom':(0,0,-14),
               'left':(-14,0,0),'right':(14,0,0)}.get(name,location)
    camera.location=target+Vector(direction);b.look(camera,target)
    if name=='hero':v.fit_camera(camera,scene)
    scene.render.filepath=str(OUT/(name+'.png'));bpy.ops.render.render(write_still=True)


def edge_ribbons():
    for obj in list(bpy.context.scene.objects):
        if obj.name.startswith('W02_MachinedEdge'):bpy.data.objects.remove(obj,do_unlink=True)
    for q,r in zip(OUTLINE[3:9],OUTLINE[4:10]):
        vertices=[];steps=max(80,math.ceil(math.dist(q,r)/.015))
        for i in range(steps+1):
            t=i/steps;x=q[0]*(1-t)+r[0]*t;y=q[1]*(1-t)+r[1]*t
            for px,py in ((x,y),(a.CX+(x-a.CX)*.980,a.CY+(y-a.CY)*.980)):
                vertices.append((px,py,sections.skin(px,py)+.014))
        obj=v.rawmesh('W02_MachinedEdge',vertices,[(i*2,i*2+1,i*2+3,i*2+2) for i in range(steps)],v.E,True)
        b.mirror(obj)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--quick',action='store_true')
    opt=parser.parse_args(sys.argv[sys.argv.index('--')+1:] if '--' in sys.argv else [])
    OUT.mkdir(parents=True,exist_ok=True)
    for key,value in {'skin':sections.skin,'CROWN':sections.CROWN,'BELLY':sections.BELLY,
                      'BOUNDARY':sections.BOUNDARY,'OUTLINE':OUTLINE,'DECK':sections.DECK}.items():setattr(a,key,value)
    v.shared.reset();a.configure_materials()
    for material in (v.A,v.P,*v.PAL):
        for node in material.node_tree.nodes:
            if node.type=='VALTORGB':
                for element in node.color_ramp.elements:element.color=tuple(c*.78 for c in element.color[:3])+(1,)
    v.skin=sections.skin;v.CX=a.CX;v.CY=a.CY;v.FAN_R=a.RR;v.OUTLINE=OUTLINE
    body=hull();a.detail(body);a.fan();a.nose();a.belly()
    bm=bmesh.new();bm.from_mesh(body.data)
    bmesh.ops.remove_doubles(bm,verts=list(bm.verts),dist=1e-6)
    bmesh.ops.recalc_face_normals(bm,faces=list(bm.faces))
    sharp=[edge for edge in bm.edges if edge.is_manifold and edge.calc_face_angle()>math.radians(40)]
    bmesh.ops.split_edges(bm,edges=sharp)
    for face in bm.faces:face.smooth=True
    bm.to_mesh(body.data);bm.free();body.data.update()
    a.panel_materials(body);edge_ribbons()
    # Flush hinge lines reserve control surfaces without claiming a working
    # actuation or flight-control system from visual geometry alone.
    for x0,x1 in ((1.33,2.57),(2.92,3.42)):
        points=[]
        for i in range(25):
            x=x0+(x1-x0)*i/24;lo,hi=sections.chord_bounds(x)
            points.append((x,hi-.20*(hi-lo)))
        a.seam('W06_ProposedElevonHinge',points,width=.004)
    camera=v.setup();scene=bpy.context.scene
    scene.view_settings.look='AgX - Medium High Contrast';scene.view_settings.exposure=-.25
    scene.render.resolution_x=1681;scene.render.resolution_y=936
    v.FITTED={'cameraParameters':CAMERA}
    views={'hero':(6,-12,8),'front':(0,-14,.1),'rear':(0,14,.1),'top':(0,0,14),'bottom':(0,0,-14),'left':(-14,0,.1),'right':(14,0,.1),'underside':(6,-12,-7)}
    for name,loc in views.items():
        if not opt.quick or name=='hero':render(camera,name,loc)
    v.fit_camera(camera,scene);bpy.context.view_layer.update()
    points={'fan_left':(-a.CX,a.CY,.3275),'fan_right':(a.CX,a.CY,.3275),
            'nose_visor':(0,-2.670,-.075),'chin_sensor':(0,-2.747,-.319),'nose_right_corner':(.57,-2.637,-.056)}
    landmarks={}
    for key,xyz in points.items():
        uv=world_to_camera_view(scene,camera,Vector(xyz));landmarks[key]={'xyz':xyz,'xy':[uv.x*1681,(1-uv.y)*936]}
    (OUT/'landmarks.json').write_text(json.dumps(landmarks,indent=2))
    (OUT/'hull-mesh.json').write_text(json.dumps({'vertices':[list(body.matrix_world@q.co) for q in body.data.vertices],'faces':[list(q.vertices) for q in body.data.polygons]}))
    report=v.shared.validation('reference-airframe-v13');report.update(referenceFidelityApproved=False,gameVerified=False,glbTextureParityVerified=False,aerodynamicSimulationPerformed=False)
    mechanics=a.mechanics(body)
    mechanics['checks']['symmetricProfile']=all(abs(sections.skin(x,y,u)-sections.skin(-x,y,u))<1e-9 for x in (.2,.8,1.5,2.5,3.5) for y in (-.5,.1,.7) for u in (True,False))
    mechanics['passed']=all(mechanics['checks'].values())
    (OUT/'validation.json').write_text(json.dumps(report,indent=2))
    (OUT/'mechanics.json').write_text(json.dumps(mechanics,indent=2))
    params=sections.parameters();params['cameraParameters']=CAMERA
    (OUT/'design-parameters.json').write_text(json.dumps(params,indent=2))
    if not opt.quick:
        clay=b.mat('V13_DiagnosticClay',(.25,.25,.25),0,.65);scene.view_layers[0].material_override=clay
        scene.render.filepath=str(OUT/'clay.png');bpy.ops.render.render(write_still=True);scene.view_layers[0].material_override=None
    scene['asset_status']='UNAPPROVED_GEOMETRY_HYPOTHESIS_NOT_FLIGHT_VALIDATED'
    bpy.ops.wm.save_as_mainfile(filepath=str(OUT/'airframe.blend'))
    if not opt.quick:
        bpy.ops.object.select_all(action='DESELECT')
        for obj in scene.objects:
            if obj.type in ('MESH','CURVE','EMPTY'):obj.select_set(True)
        bpy.ops.export_scene.gltf(filepath=str(OUT/'airframe.glb'),export_format='GLB',use_selection=True)
    print('MODEL_REPORT',json.dumps(report),flush=True)
    print('GEOMETRY_REPORT',json.dumps(mechanics),flush=True)
    if not mechanics['passed']:raise RuntimeError('Geometry checks failed')


if __name__=='__main__':main()
