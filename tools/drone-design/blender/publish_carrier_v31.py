"""Publish the validated V31 ring carrier through the production MGM4 pipeline."""
import hashlib
import importlib.util
import json
import shutil
import struct
import sys
from pathlib import Path

sys.dont_write_bytecode = True
import bpy

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SOURCE = ROOT / 'docs/design/carrier-v31-ring-proposal/carrier-v31-ring-proposal.blend'
OUT = ROOT / 'docs/design/carrier-v31-ring-proposal/runtime'
SANITIZED = OUT / 'carrier-v31-runtime-source.blend'
ASSETS = ROOT / 'src/main/resources/assets/morrowgear_drone'
STAGED = OUT / 'staged-assets'


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


carrier = module('carrier_runtime', HERE / 'build_carrier_v27.py')
proposal = module('carrier_v31', HERE / 'build_carrier_v31_ring_proposal.py')
carrier.OUT = OUT
carrier.STAGED = STAGED
carrier.REVISION = 'V31_INTEGRATED_MINING_RING_RUNTIME_01'

LOD_DROP = ('Command_window', 'Command_service_hatch', 'Command_hatch_lock',
            'Bay_threshold_service_mark', 'Bay_service_console', 'Functional_service_stencil',
            'Flush_maintenance_handrail', 'Cassette_cooling_fin', 'Protected_power_line',
            'MiningRing_upper_bezel', 'MiningRing_emitter_upper_window',
            'MiningRing_lower_service_truss', 'Exhaust_vector_vane')

# These markings are represented by the baked atlas at game scale. Keeping each
# shallow plate as geometry costs 3,360 triangles without changing the carrier
# silhouette or any functional opening.
RUNTIME_DECAL_ONLY = ('Aft_maintenance_stencil', 'Bay_threshold_service_mark')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare_source():
    verification = json.loads((SOURCE.parent / 'validation.json').read_text(encoding='utf-8'))
    if not verification.get('passed') or verification.get('symmetryMaxError') != 0:
        raise RuntimeError('V31 canonical validation is not approved')
    bpy.ops.wm.open_mainfile(filepath=str(SOURCE))
    for obj in list(bpy.context.scene.objects):
        if obj.name.startswith(('PRESENTATION_ONLY_', 'REVIEW_ONLY_')):
            bpy.data.objects.remove(obj, do_unlink=True)
            continue
        if obj.type == 'MESH' and not obj.hide_render:
            obj['carrier_runtime'] = not obj.name.startswith(RUNTIME_DECAL_ONLY)
            obj['lod_keep'] = not obj.name.startswith(LOD_DROP)
    OUT.mkdir(parents=True, exist_ok=True)
    bpy.ops.wm.save_as_mainfile(filepath=str(SANITIZED))


def inspect(path, budget):
    raw = path.read_bytes()
    magic, count, rotors = struct.unpack_from('>III', raw)
    errors = []
    if magic != 0x4D474D34 or rotors != 2 or len(raw) != 12 + rotors * 12 + count * 86:
        errors.append('header or byte length')
    rows = list(struct.iter_unpack('>20f6B', raw[12 + rotors * 12:]))
    if count % 3 or count // 3 > budget:
        errors.append('triangle budget')
    points = {(round(row[0], 3), round(row[1], 3), round(row[2], 3)) for row in rows}
    if any((-x, y, z) not in points for x, y, z in points):
        errors.append('bilateral symmetry')
    if not any(row[25] == 1 for row in rows):
        errors.append('missing weapon-linked emission')
    for row in rows:
        if row[:6] != row[6:12] or row[:6] != row[12:18] or row[24] != 0 or row[25] not in (0, 1, 2):
            errors.append('pose, rotor group or signal')
            break
    bounds = [min(row[i] for row in rows) for i in range(3)] + [max(row[i] for row in rows) for i in range(3)]
    # Runtime-only decal plates at the extreme aft edge are atlas-baked, so the
    # physical mesh ends 0.043 blocks ahead of the presentation source.
    expected = (-25.0, .2, -16.029, 25.0, 7.15, 14.855)
    if any(abs(a - b) > .012 for a, b in zip(bounds, expected)):
        errors.append('V31 bounds')
    return {'passed': not errors, 'errors': errors, 'format': 'MGM4', 'vertices': count,
            'triangles': count // 3, 'bounds': bounds, 'sha256': sha(path)}


def runtime_validator(path):
    return inspect(path, 15_000 if path.stem.endswith('_lod') else 25_000)


def render_icon():
    bpy.ops.wm.open_mainfile(filepath=str(SANITIZED))
    for obj in list(bpy.context.scene.objects):
        if obj.type in {'LIGHT', 'CAMERA'}:
            bpy.data.objects.remove(obj, do_unlink=True)
    proposal.base.OUT = OUT
    proposal.base.studio()
    bpy.context.scene.render.film_transparent = True
    proposal.base.render('carrier-unit-128', (4, -6, 5), (0, 0, 3.8), 58, (128, 128))
    destination = STAGED / 'textures/item/carrier_unit.png'
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(OUT / 'carrier-unit-128.png', destination)


def publish():
    destinations = ('models/runtime/carrier.mgm', 'models/runtime/carrier_lod.mgm',
                    'textures/runtime/carrier.png', 'textures/runtime/carrier_lod.png',
                    'textures/item/carrier_unit.png')
    for relative in destinations:
        source = STAGED / relative
        if not source.is_file() or source.stat().st_size == 0:
            raise RuntimeError('missing staged asset ' + relative)
    for relative in destinations:
        destination = ASSETS / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(STAGED / relative, destination)
    report = {'passed': True, 'revision': carrier.REVISION, 'canonicalSha256': sha(SOURCE),
              'near': inspect(ASSETS / destinations[0], 25_000),
              'far': inspect(ASSETS / destinations[1], 15_000),
              'assets': [{'path': relative, 'sha256': sha(ASSETS / relative)} for relative in destinations]}
    (OUT / 'published-assets.json').write_text(json.dumps(report, indent=2), encoding='utf-8')


def main():
    prepare_source()
    carrier.read_runtime = runtime_validator
    carrier.export_runtime(False, source=SANITIZED)
    carrier.export_runtime(True, source=SANITIZED)
    near = inspect(STAGED / 'models/runtime/carrier.mgm', 25_000)
    far = inspect(STAGED / 'models/runtime/carrier_lod.mgm', 15_000)
    if not near['passed'] or not far['passed'] or far['triangles'] >= near['triangles']:
        raise RuntimeError({'near': near, 'far': far})
    render_icon()
    publish()
    print('V31_RUNTIME_PUBLISHED', near['triangles'], far['triangles'], flush=True)


if __name__ == '__main__':
    main()
