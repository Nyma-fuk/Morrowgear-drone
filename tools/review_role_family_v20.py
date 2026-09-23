"""Review sheets from real role meshes, fixed framing, and artifact verification."""
import hashlib
import json
import struct
from pathlib import Path

from PIL import Image
from review_propulsion_v16 import tile

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/design/role-family-v20'
BASE=ROOT/'docs/design/reference-low-profile-v19'
ROLES=('field','scout','cargo','engineer','security','salvage')
LABELS={'field':'FIELD / サービス接点・通信', 'scout':'SCOUT / 周囲走査帯・光学センサー',
        'cargo':'CARGO / 開閉式貨物庫', 'engineer':'ENGINEER / 折り畳み作業アーム',
        'security':'SECURITY / 内蔵兵装・黒色ドーム', 'salvage':'SALVAGE / 回収ウインチ'}
VIEWS=(('hero','飛行'),('landed','着艦'),('active','作業 / 下面'),('top-active','作業 / 上面'),
       ('top','上面'),('bottom','底面'),('front','正面'),('rear','背面'),
       ('left','左側面'),('right','右側面'),('rear-oblique','後方俯瞰'),
       ('gear-stowed','脚収納 / 下面'),('right-stowed','脚収納 / 側面'))


def glb_document(path):
    data=path.read_bytes()
    magic,version,length=struct.unpack_from('<4sII',data)
    assert magic==b'glTF' and version==2 and length==len(data)
    size,kind=struct.unpack_from('<II',data,12)
    assert kind==0x4E4F534A
    return json.loads(data[20:20+size])


def mirrors_x(node):
    x,y,z,w=node.get('rotation',(0,0,0,1));s=node.get('scale',(1,1,1))
    rotation=((1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w)),
              (2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w)),
              (2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)))
    expected=((-1,0,0),(0,1,0),(0,0,1))
    return all(abs(rotation[r][c]*s[c]-expected[r][c])<1e-5 for r in range(3) for c in range(3))


def main():
    for view,name in (('hero','family-flight'),('active','family-equipment'),('landed','family-landed')):
        sheet=Image.new('RGB',(1680,1500))
        for i,role in enumerate(ROLES):
            sheet.paste(tile(OUT/role/(view+'.png'),LABELS[role],(840,500),(290,70,1570,865)),((i%2)*840,(i//2)*500))
        sheet.save(OUT/(name+'.png'))
    gear=Image.new('RGB',(1680,1260))
    for row,role in enumerate(('security','cargo','salvage')):
        for col,view in enumerate(('right-stowed','right')):
            label=role.upper()+(' / 飛行・収納' if col==0 else ' / 着艦・4点支持')
            gear.paste(tile(OUT/role/(view+'.png'),label,(840,420),(360,295,1320,680)),(col*840,row*420))
    gear.save(OUT/'landing-gear.png')
    tile(OUT/'security/rear-detail.png','後部 / 冷却ルーバー・点検口・固定具',(1680,1000)).save(OUT/'rear-details.png')
    checks={};measurements={};base_hash=hashlib.sha256((BASE/'airframe.blend').read_bytes()).hexdigest()
    source_time=(ROOT/'tools/drone-design/blender/build_role_family_v20.py').stat().st_mtime
    for role in ROLES:
        folder=OUT/role;sheet=Image.new('RGB',(2100,2200),(25,29,32));bounds={}
        for i,(name,label) in enumerate(VIEWS):
            crop=None
            if name in ('hero','landed','active','top-active','gear-stowed'):crop=(290,70,1570,865)
            if name in ('front','rear'):crop=(130,330,1550,670)
            if name in ('left','right','right-stowed'):crop=(280,295,1400,690)
            sheet.paste(tile(folder/(name+'.png'),label,(700,440),crop),((i%3)*700,(i//3)*440))
            bounds[name]=Image.open(folder/(name+'.png')).getchannel('A').getbbox()
        sheet.save(folder/'all-views.png')
        report=json.loads((folder/'validation.json').read_text())
        glb=glb_document(folder/'airframe.glb');nodes=glb['nodes']
        animations=glb.get('animations',[])
        moving={nodes[c['target']['node']]['name'] for a in animations for c in a['channels']}
        feet=[n for n in moving if n.startswith('GEAR_ContactPad')]
        checks[role]={
            'geometryChecksPass':report['passed'],
            'baseUnchanged':report['baseSha256']==base_hash,
            'thirteenViewsFramed':all(b and b[0]>2 and b[1]>2 and b[2]<1679 and b[3]<934 for b in bounds.values()),
            'thirteenViewsFresh':all((folder/(v+'.png')).stat().st_mtime>source_time for v,_ in VIEWS),
            'thirteenViewsSameResolution':all(Image.open(folder/(v+'.png')).size==(1681,936) for v,_ in VIEWS),
            'blendAndGlbPresent':all((folder/p).stat().st_size>1000 for p in ('airframe.blend','airframe.glb')),
            'singleCombinedAnimation':len(animations)==1,
            'fourFeetAnimatedInExport':len(feet)==4,
            'exportHasMirroredHierarchy':any(n.get('name')=='V20_PortMechanisms' and mirrors_x(n) for n in nodes),
        }
        if role in ('cargo','security'):
            movers=[n for n in moving if 'Pivot' in n]
            checks[role]['bothHatchesAnimated']=len(movers)==2
        measurements[role]={'bounds':bounds,'landed':report['samples'][0],
                            'animatedNodeCount':len(moving),'nodeCount':len(nodes)}
    passed=all(all(v.values()) for v in checks.values())
    result={'checks':checks,'passed':passed,'measurements':measurements,
            'renderSource':'Blender model, not image generation','gameVerified':False,
            'fullSweptCollisionVerified':False,'aerodynamicsSimulated':False}
    (OUT/'output-checks.json').write_text(json.dumps(result,indent=2))
    print(json.dumps({'passed':passed,'checks':checks},indent=2))
    if not passed:raise RuntimeError('Role output verification failed')


if __name__=='__main__':main()
