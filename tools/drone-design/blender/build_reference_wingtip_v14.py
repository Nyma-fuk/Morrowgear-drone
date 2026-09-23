"""V14 staging candidate: broad tips and integrated aft fairings."""
import importlib.util
import json
import math
import sys
from pathlib import Path

import bpy
from mathutils import Vector
from bpy_extras.object_utils import world_to_camera_view

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
sys.path.insert(0,str(ROOT/'tools'))
import reference_wingtip_sections_v14 as sections

spec=importlib.util.spec_from_file_location('v13',HERE/'build_reference_airframe_v13.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
a,v,b=m.a,m.v,m.b
OUT=ROOT/'docs/design/reference-wingtip-v14'
m.OUT=OUT;m.sections=sections;m.OUTLINE=[p[:2] for p in sections.BOUNDARY]
old_detail=a.detail
old_render=m.render
old_hull=m.hull


def hull():
    body=old_hull()
    vertices=[tuple(v.co) for v in body.data.vertices]
    faces=[tuple(p.vertices) for p in body.data.polygons]
    # Refine inside faces only, preserving all shared edges and watertightness.
    # A fixed XY grid alone undersamples the steep rounded leading edge.
    for _ in range(4):
        refined=[]
        for face in faces:
            pts=[vertices[i] for i in face];split=False
            if len(face)==3:
                center=[sum(p[k] for p in pts)/3 for k in range(3)]
                for top in (True,False):
                    if all(abs(z-sections.skin(x,y,top))<.001 for x,y,z in pts):
                        z=sections.skin(center[0],center[1],top)
                        if abs(z-center[2])>.008:
                            index=len(vertices);vertices.append((center[0],center[1],z))
                            refined.extend((face[i],face[(i+1)%3],index) for i in range(3))
                            split=True
                        break
            if not split:refined.append(face)
        faces=refined
    body.data.clear_geometry();body.data.from_pydata(vertices,[],faces);body.data.update()
    return body


def delete_prefixes(prefixes):
    for obj in list(bpy.context.scene.objects):
        if obj.name.startswith(prefixes):bpy.data.objects.remove(obj,do_unlink=True)


def detail(body):
    old_detail(body)
    delete_prefixes(('W04_TipMark','H09_AftFairing','H10_AftOpening','H11_AftMark',
                     'H12_RearServiceOpening','H13_RearLouver'))
    p,q,r,s=[Vector(p[:2]) for p in sections.BOUNDARY[4:8]]
    # Amber paint is along the forward panel edge, not its outer/aft ridge.
    points=[tuple(p.lerp(q,t).lerp(s.lerp(r,t),.12)) for t in (.10,.35,.60,.90)]
    a.seam('W04_TipMark',points,v.O,.010)
    panel=[tuple(p.lerp(q,u).lerp(s.lerp(r,u),t)) for u,t in ((.06,.06),(.91,.06),(.91,.91),(.06,.91),(.06,.06))]
    a.seam('W07_TipPanelJoint',panel,width=.0035)
    a.seam('W08_TipFold',[tuple(p),tuple(s)],width=.005)
    # The reference shows a low, broad shroud with a forward-facing dark
    # throat. Its hidden aft closure is an explicit modeling inference.
    x=.55
    part=b.loft('H09_AftFairing',[(1.78,.20,.05,.35),
                  (2.25,.19,-.025,.30),(2.38,.15,-.045,.19)],v.A,.009)
    part.location.x=x;b.mirror(part)
    v.box('H10_AftOpening',(x,1.769,.22),(.26,.014,.155),v.D,True,.008)
    for z in (.186,.226):v.box('H10_AftInternalLouver',(x,1.758,z),(.21,.027,.012),v.P,True,.002)
    stripe=[]
    for y in (1.83,2.22):
        z=.35+(y-1.78)/(.47)*(-.05)+.002
        stripe.extend([(x+.057,y,z),(x+.079,y,z)])
    b.mirror(v.rawmesh('H11_AftMark',stripe,[(0,1,3,2)],v.O))
    for sign in (-1,1):
        verts=[]
        for y,width,wall in ((1.52,.18,.275),(1.78,.20,.295),(2.25,.19,.245),(2.34,.16,.17)):
            inner=x+sign*width;outer=x+sign*(width+.14)
            verts.extend([(inner,y,max(sections.skin(inner,y),wall)),
                          (outer,y,sections.skin(outer,y)+.004)])
        b.mirror(v.rawmesh('H14_AftBlendedCheek',verts,[(i*2,i*2+1,i*2+3,i*2+2) for i in range(3)],v.A))
    for x in (.24,.53):
        y=2.38-.08*x/1.12
        v.box('H12_RearServiceOpening',(x,y+.005,.045),(.19,.025,.10),v.D,True,.005)
        for z in (.017,.05,.083):v.box('H13_RearLouver',(x,y+.020,z),(.15,.016,.009),v.P,True,.002)


def edge_ribbons():
    delete_prefixes(('W02_MachinedEdge',))
    outline=[Vector(p) for p in m.OUTLINE]
    normals=[]
    for p,q in zip(outline,outline[1:]+outline[:1]):
        delta=(q-p).normalized();normals.append(Vector((-delta.y,delta.x)))
    inset=[]
    for i,p in enumerate(outline):
        n0,n1=normals[i-1],normals[i]
        inset.append(p+(n0+n1)*(.065/max(.1,1+n0.dot(n1))))
    for index in range(3,9):
        p,q=outline[index:index+2];vertices=[]
        steps=max(80,math.ceil((q-p).length/.015))
        for i in range(steps+1):
            t=i/steps
            for xy in (p.lerp(q,t),inset[index].lerp(inset[index+1],t)):
                vertices.append((*xy,sections.skin(*xy)+.009))
        b.mirror(v.rawmesh('W02_MachinedEdge',vertices,
                          [(i*2,i*2+1,i*2+3,i*2+2) for i in range(steps)],v.E))


def render(camera,name,location):
    # Diagnostic underside lighting reveals geometry that V13 hid in shadow.
    if name in ('bottom','underside','rear','left','right'):
        bpy.ops.object.light_add(type='AREA',location=(0,-2,-6))
        li=bpy.context.object;li.name='DiagnosticUndersideLight'
        li.data.energy=1050;li.data.size=7;b.look(li)
    old_render(camera,name,location)
    delete_prefixes(('DiagnosticUndersideLight',))
    if name=='underside':old_render(camera,'rear-oblique',(6,12,7))


a.detail=detail;m.render=render;m.edge_ribbons=edge_ribbons;m.hull=hull
m.main()
report=json.loads((OUT/'validation.json').read_text())
report['asset']='reference-wingtip-v14'
report['referenceFidelityApproved']=False
(OUT/'validation.json').write_text(json.dumps(report,indent=2))
points={}
for side,sign in (('left',-1),('right',1)):
    for name,index in (('root_front',4),('outer_front',5),('outer_rear',6),('root_rear',7)):
        x,y,_=sections.BOUNDARY[index];p=Vector((sign*x,y,sections.skin(x,y)))
        uv=world_to_camera_view(bpy.context.scene,bpy.context.scene.camera,p)
        points[f'{side}_{name}']={'xyz':list(p),'xy':[uv.x*1681,(1-uv.y)*936]}
(OUT/'tip-landmarks.json').write_text(json.dumps(points,indent=2))
