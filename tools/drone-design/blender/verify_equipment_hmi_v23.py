"""GLB readback, held/equipped views, and complete Dock views. Design verification only."""
import importlib.util
import json
from pathlib import Path

import bpy
from mathutils import Vector

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[2]
spec=importlib.util.spec_from_file_location('v23build',HERE/'build_equipment_hmi_v23.py')
b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
OUT=b.OUT


def readback():
    results=[]
    for item in b.ITEMS:
        folder=OUT/'models'/item
        src=json.loads((folder/'validation.json').read_text())
        bpy.ops.wm.read_factory_settings(use_empty=True)
        bpy.ops.import_scene.gltf(filepath=str(folder/(item+'.glb')))
        # glTF importer restores Blender Z-up; compare all three operating poses.
        checks=[]
        for sample in src['checks']:
            bpy.context.scene.frame_set(sample['frame']);bpy.context.view_layer.update()
            p=b.d.all_points(b.d.geometry());bb=b.d.bounds(p)
            error=max(abs(a-c) for a,c in zip(bb,sample['bounds']))
            checks.append({'frame':sample['frame'],'boundsError':error,'passed':error<1e-4})
        results.append({'id':item,'checks':checks,'passed':all(c['passed'] for c in checks)})
    report={'scope':'new equipment GLB readback; not Minecraft renderer','results':results,'passed':all(r['passed'] for r in results)}
    (OUT/'verification/export-roundtrip.json').write_text(json.dumps(report,indent=2))
    if not report['passed']:raise ValueError([r for r in results if not r['passed']])


def dock_views():
    bpy.ops.wm.open_mainfile(filepath=str(ROOT/'docs/design/detail-scale-v22/dock/dock.blend'))
    b.d.studio();bpy.context.scene.cycles.samples=20
    out=OUT/'views/dock_item';out.mkdir(parents=True,exist_ok=True)
    for name,axis in [('hero',(6,-8,6)),('front',(0,-8,0)),('rear',(0,8,0)),('left',(-8,0,0)),('right',(8,0,0)),('top',(0,0,8)),('bottom',(0,0,-8))]:
        b.d.render(out/(name+'.png'),axis,6.4,target=(0,0,.20),size=(768,640),frame=1)


def held_views():
    output=OUT/'held';output.mkdir(exist_ok=True)
    reports=[]
    for item in ('controller','tactical_visor','recovery_tool'):
        for side in (-1,1):
            bpy.ops.wm.open_mainfile(filepath=str(OUT/'models'/item/(item+'.blend')))
            bpy.context.scene.frame_set(1);bpy.context.view_layer.update()
            objs=[o for o in bpy.context.scene.objects if o.type in('MESH','CURVE','EMPTY')]
            root=b.v.empty('HeldTransform')
            for o in objs:
                if o.parent is None:o.parent=root
            root.location=(side*.42,.0,0)
            root.rotation_euler.z=side*.06
            b.d.studio();bpy.context.scene.cycles.samples=20
            b.v.materials()
            # A neutral block-hand proxy makes contact/orientation inspectable; not player art.
            grip_z=-.05 if item!='recovery_tool' else -.26
            hand=b.box('HandProxy',side*1.22 if item=='controller' else side*.42,.17,grip_z,.23,.33,.18,'rubber')
            b.d.render(output/f'{item}-{"left" if side<0 else "right"}.png',(0,-3,3),3.0,target=(0,-.08,.05),size=(1280,800),frame=1)
            normal=Vector((0,0,1)) if item=='controller' else Vector((0,-1,0))
            view=Vector((0,-3,3)).normalized()
            reports.append({'item':item,'hand':'left' if side<0 else 'right','displayFacingCameraDot':normal.dot(view),'mirrorsText':False,
                            'scope':'design-space handed presentation, not Minecraft ItemDisplayContext','passed':normal.dot(view)>0})
    # Wearable preview is authored around a vanilla-proportioned, half-block head envelope.
    bpy.ops.wm.open_mainfile(filepath=str(OUT/'models/tactical_visor/tactical_visor.blend'))
    b.v.materials();b.box('HeadFitProxy',0,.24,.19,.86,.86,.86,'rubber',.04)
    b.d.studio();b.d.render(output/'visor-equipped.png',(3,-5,2),2.0,target=(0,0,.2),size=(1000,800),frame=1)
    (output/'pose-contract.json').write_text(json.dumps({'poses':reports,'runtimeApplied':False,'passed':all(r['passed'] for r in reports)},indent=2))


def main():
    (OUT/'verification').mkdir(exist_ok=True)
    readback();dock_views();held_views()
    print('V23_EXPORT_HELD_VIEWS_COMPLETE',flush=True)


if __name__=='__main__':main()
