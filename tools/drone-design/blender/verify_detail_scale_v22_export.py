"""Round-trip actual V22 part geometry at eleven mechanical poses."""
import importlib.util
import json
import sys
from pathlib import Path

import bpy

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('role_export_check', HERE / 'verify_role_family_v20_export.py')
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)
check.OUT = HERE.parents[2] / 'docs/design/detail-scale-v22'

def dock():
    folder = check.OUT / 'dock'
    bpy.ops.wm.open_mainfile(filepath=str(folder / 'dock.blend'))
    source = check.pose_bounds()
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(folder / 'dock.glb'))
    actual = check.pose_bounds()
    missing = sorted(set(source)-set(actual))
    maximum = max(max(abs(a-b) for a,b in zip(source[name],actual[name])) for name in source.keys()&actual.keys())
    report = {'passed': not missing and maximum < 5e-4, 'missing': missing,
              'maxBoundsError': maximum, 'sourceParts': len(source)}
    (folder / 'export-roundtrip.json').write_text(json.dumps(report,indent=2))
    print('DOCK_EXPORT_ROUNDTRIP',json.dumps(report),flush=True)
    if not report['passed']:
        raise RuntimeError(report)


if __name__ == '__main__':
    if '--dock-only' not in sys.argv:
        check.main()
    dock()
