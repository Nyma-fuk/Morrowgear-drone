"""Import exported GLBs and compare animated mechanical poses with source blends."""
import json
from pathlib import Path

import bpy

ROOT=Path(__file__).resolve().parents[3]
OUT=ROOT/'docs/design/role-family-v20'
ROLES=('field','scout','cargo','engineer','security','salvage')
FRAMES=(1,20,30,40,50,60,70,80,100,110,120)


def pose_bounds():
    result={};graph=bpy.context.evaluated_depsgraph_get()
    for obj in bpy.context.scene.objects:
        if obj.type not in ('MESH','CURVE'):continue
        ev=obj.evaluated_get(graph);mesh=ev.to_mesh()
        points=[ev.matrix_world@v.co for v in mesh.vertices]
        if points:
            result[obj.name]=[f(p[i] for p in points) for f in (min,max) for i in range(3)]
        ev.to_mesh_clear()
    return result


def main():
    reports={}
    for role in ROLES:
        expected={};errors=[];maximum=0
        bpy.ops.wm.open_mainfile(filepath=str(OUT/role/'airframe.blend'))
        for frame in FRAMES:
            bpy.context.scene.frame_set(frame);expected[frame]=pose_bounds()
        bpy.ops.wm.read_factory_settings(use_empty=True)
        bpy.ops.import_scene.gltf(filepath=str(OUT/role/'airframe.glb'))
        for frame in FRAMES:
            bpy.context.scene.frame_set(frame);actual=pose_bounds();source=expected[frame]
            missing=set(source)-set(actual)
            if missing:errors.append({'frame':frame,'missing':sorted(missing)})
            for name in source.keys()&actual.keys():
                delta=max(abs(a-b) for a,b in zip(source[name],actual[name]));maximum=max(maximum,delta)
                if delta>5e-4:errors.append({'frame':frame,'part':name,'boundsError':delta})
        reports[role]={'passed':not errors,'errors':errors,'maxBoundsError':maximum,
                       'sampledFrames':list(FRAMES),'sourceParts':len(expected[1])}
        print('EXPORT_ROUNDTRIP',role,json.dumps(reports[role]),flush=True)
    passed=all(r['passed'] for r in reports.values())
    (OUT/'export-roundtrip.json').write_text(json.dumps({'passed':passed,'roles':reports},indent=2))
    if not passed:raise RuntimeError('Export roundtrip mismatch')


if __name__=='__main__':main()
