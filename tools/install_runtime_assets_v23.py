"""Copy approved generated assets to stable game resource identifiers."""
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/morrowgear_drone'
DESIGN = ROOT / 'docs/design/equipment-hmi-v23'


def copy(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


for source in (ROOT / 'build/runtime-v23').glob('*.mgm'):
    copy(source, ASSETS / 'models/runtime' / source.name)
    copy(source.with_suffix('.png'), ASSETS / 'textures/runtime' / source.with_suffix('.png').name)
for item in json.loads((DESIGN / 'item-manifest.json').read_text(encoding='utf-8'))['items']:
    name = item['id']
    copy(DESIGN / f'items/{name}-128.png', ASSETS / f'textures/item/{name}.png')
    write(ASSETS / f'models/item/{name}.json', {'parent': 'minecraft:item/generated',
          'textures': {'layer0': f'morrowgear_drone:item/{name}'}})
    write(ASSETS / f'items/{name}.json', {'model': {'type': 'minecraft:model',
          'model': f'morrowgear_drone:item/{name}'}})
for source in (DESIGN / 'hmi/symbols').glob('*-32.png'):
    copy(source, ASSETS / 'textures/gui/symbols' / source.name.replace('-32', ''))
copy(DESIGN / 'hmi/vendor/LUCIDE-LICENSE.txt', ROOT / 'src/main/resources/licenses/LUCIDE-LICENSE.txt')
for role in ('field', 'scout', 'cargo', 'engineer', 'security', 'salvage'):
    source = ROOT / f'docs/design/detail-scale-v22/{role}/icon-master.png'
    if not source.exists():
        source = ROOT / f'docs/design/detail-scale-v22/{role}/icon.png'
    if source.exists():
        copy(source, ASSETS / f'textures/gui/roles/{role}.png')
write(ASSETS / 'models/block/dock_wide_empty.json', {'textures': {
      'particle': 'morrowgear_drone:item/dock_item'}, 'elements': []})
for index in range(25):
    write(ASSETS / f'blockstates/dock_wide_part_{index}.json', {'variants': {
          '': {'model': 'morrowgear_drone:block/dock_wide_empty'}}})
report = {'version': 23, 'items': 23, 'meshes': [p.stem for p in (ASSETS / 'models/runtime').glob('*.mgm')],
          'sha256': {str(p.relative_to(ASSETS)): hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in (ASSETS / 'models/runtime').glob('*.mgm')}}
write(ASSETS / 'runtime-v23-manifest.json', report)
print(json.dumps({'items': report['items'], 'meshes': len(report['meshes'])}))
