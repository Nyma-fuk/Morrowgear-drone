"""Validate staged files independently of Blender's export success messages."""
import collections
import json
import math
import struct
from pathlib import Path

from PIL import Image

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/design/unified-family-v8'
checks=[]


def check(name, passed, detail=''):
    checks.append({'name':name,'pass':bool(passed),'detail':detail})


def glb(path):
    data=path.read_bytes()
    magic,version,length=struct.unpack_from('<4sII',data)
    check(path.parent.name+'/glb_header',magic==b'glTF' and version==2 and length==len(data))
    pos=12;js=None;blob=None
    while pos<len(data):
        size,kind=struct.unpack_from('<I4s',data,pos);pos+=8
        chunk=data[pos:pos+size];pos+=size
        if kind==b'JSON':js=json.loads(chunk)
        elif kind==b'BIN\0':blob=chunk
    check(path.parent.name+'/embedded_geometry',js is not None and blob is not None)
    if js is None or blob is None:return
    for index,view in enumerate(js.get('bufferViews',[])):
        check(path.parent.name+'/buffer_'+str(index),view.get('byteOffset',0)+view['byteLength']<=len(blob))
    vertices=0
    for mesh in js.get('meshes',[]):
        for primitive in mesh['primitives']:
            acc=js['accessors'][primitive['attributes']['POSITION']]
            vertices+=acc['count']
            view=js['bufferViews'][acc['bufferView']]
            offset=view.get('byteOffset',0)+acc.get('byteOffset',0)
            stride=view.get('byteStride',12)
            finite=all(all(math.isfinite(v) for v in struct.unpack_from('<fff',blob,offset+i*stride)) for i in range(acc['count']))
            check(path.parent.name+'/finite_positions_'+str(vertices),finite)
    check(path.parent.name+'/nonempty_mesh',vertices>0)
    if path.parent.name in ('field','scout','cargo','engineer','security','salvage'):
        check(path.parent.name+'/animation_present',bool(js.get('animations')))


def main():
    manifest=json.loads((OUT/'manifest.json').read_text())
    log=(ROOT/'work/unified-v8-all.log').read_text(encoding='utf-8',errors='replace')
    check('build_has_no_traceback','Traceback' not in log)
    exported=[json.loads(line.split('V8_ASSET ',1)[1])['asset'] for line in log.splitlines() if 'V8_ASSET ' in line]
    check('all_expected_assets_exported',set(exported)==set(manifest['assets']))
    for name in manifest['assets']:
        folder=OUT/'models'/name
        for file in (name+'.blend',name+'.glb','hero.png','icon.png','validation.json'):
            check(name+'/'+file,(folder/file).is_file())
        glb(folder/(name+'.glb'))
        report=json.loads((folder/'validation.json').read_text())
        check(name+'/symmetry',report['symmetryPass'])
        if 'groundClearancePass' in report:check(name+'/landing_clearance',report['groundClearancePass'])
    registered={p.stem for p in (ROOT/'src/main/resources/assets/morrowgear_drone/items').glob('*.json')}
    check('registered_item_coverage',registered==set(manifest['itemIds']))
    seen=collections.defaultdict(list)
    for name in registered:
        path=OUT/'items'/(name+'.png');im=Image.open(path)
        check(name+'/item_image',im.size==(128,128) and im.mode=='RGBA' and im.getbbox() is not None)
        seen[im.tobytes()].append(name)
    duplicates=[names for names in seen.values() if len(names)>1]
    check('no_identical_item_icons',not duplicates,str(duplicates))
    atlas=json.loads((OUT/'hud/atlas.json').read_text())
    for name,rect in atlas['sprites'].items():
        check(name+'/atlas_bounds',rect['x']>=0 and rect['y']>=0 and rect['x']+rect['width']<=atlas['width'] and rect['y']+rect['height']<=atlas['height'])
        for size in (16,24,32,64):
            im=Image.open(OUT/'hud'/str(size)/(name+'.png'))
            check(name+'/hud_'+str(size),im.size==(size,size) and im.getbbox() is not None)
    semantic=json.loads((OUT/'hud/semantic-map.json').read_text())
    for group,mapping in semantic.items():
        for state,icon in mapping.items():check(group+'/'+state,icon in atlas['sprites'])
    failed=[c for c in checks if not c['pass']]
    result={'checks':len(checks),'passed':len(checks)-len(failed),'failed':failed,'gameTested':False,'referenceVisualFidelityApproved':False,'scope':'artifact integrity, stationary symmetry, ground clearance, nonblank coverage; not gameplay or complete mechanical collision certification'}
    (OUT/'verification.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result,indent=2))
    raise SystemExit(1 if failed else 0)


if __name__=='__main__':main()
