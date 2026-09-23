"""Publish only numerically approved far MGM4 candidates and refresh the runtime manifest."""
from pathlib import Path
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parents[1]
CANDIDATES = ROOT / 'build/lod-candidates'
MODELS = ROOT / 'src/main/resources/assets/morrowgear_drone/models/runtime'
MANIFEST = ROOT / 'src/main/resources/assets/morrowgear_drone/runtime-v23-manifest.json'
NAMES = ('field', 'scout', 'cargo', 'engineer', 'security', 'salvage', 'carrier')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    for name in NAMES:
        source = CANDIDATES / f'{name}_far.mgm'
        report = json.loads(source.with_suffix('.json').read_text(encoding='utf-8'))
        limit = 5000 if name == 'carrier' else 4000
        if not report.get('numericChecksPassed') or report['triangles'] > limit or digest(source) != report['outputSha256']:
            raise ValueError(f'unapproved far LOD: {name}')
        shutil.copyfile(source, MODELS / source.name)
    manifest = json.loads(MANIFEST.read_text(encoding='utf-8'))
    for name in NAMES:
        asset = f'{name}_far'
        if asset not in manifest['meshes']:
            manifest['meshes'].append(asset)
        path = MODELS / f'{asset}.mgm'
        manifest['sha256'][f'models\\runtime\\{asset}.mgm'] = digest(path)
    manifest['meshes'].sort()
    manifest['sha256'] = dict(sorted(manifest['sha256'].items()))
    MANIFEST.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
