"""Build review boards from unretouched Blender renders and verify artifacts."""
import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image
from review_propulsion_v16 import tile
from review_role_family_v20 import glb_document, mirrors_x, VIEWS, ROLES

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/design/integrated-family-v21'
LABELS={'field':'FIELD / 共通機体', 'scout':'SCOUT / 機首内蔵光学系',
        'cargo':'CARGO / 胴体一体型貨物庫', 'engineer':'ENGINEER / 胴体内アーム収納',
        'security':'SECURITY / 内蔵兵装庫・下面レンズ', 'salvage':'SALVAGE / 中央内部ウインチ'}


def boards():
    for view,name in (('hero','family-flight'),('active','family-equipment'),('landed','family-landed')):
        sheet=Image.new('RGB',(1920,1590))
        for i,role in enumerate(ROLES):
            sheet.paste(tile(OUT/role/(view+'.png'),LABELS[role],(960,530),(220,180,1570,850)),
                        ((i%2)*960,(i//2)*530))
        sheet.save(OUT/(name+'.png'))
    working=Image.new('RGB',(1920,1120))
    for i,(role,view,title) in enumerate((('cargo','active','CARGO / 貨物庫を展開'),
                                        ('engineer','active','ENGINEER / 収納室からアーム展開'),
                                        ('security','top-active','SECURITY / 背面ミサイル庫'),
                                        ('salvage','active','SALVAGE / 中央下面から吊り下げ'))):
        working.paste(tile(OUT/role/(view+'.png'),title,(960,560),(220,160,1570,865)),
                      ((i%2)*960,(i//2)*560))
    working.save(OUT/'mechanisms.png')
    if (OUT/'salvage/equipment-close.png').exists():
        details=Image.new('RGB',(1920,1280))
        for i,role in enumerate(('cargo','engineer','security','salvage')):
            details.paste(tile(OUT/role/'equipment-close.png',LABELS[role],(960,640)),((i%2)*960,(i//2)*640))
        details.save(OUT/'equipment-details.png')


def verify():
    checks={};measurements={}
    base_hash=hashlib.sha256((ROOT/'docs/design/reference-low-profile-v19/airframe.blend').read_bytes()).hexdigest()
    stamp=(ROOT/'tools/drone-design/blender/build_integrated_family_v21.py').stat().st_mtime
    for role in ROLES:
        folder=OUT/role;sheet=Image.new('RGB',(2100,2200),(25,29,32));bounds={}
        for i,(name,label) in enumerate(VIEWS):
            crop=(220,180,1570,865) if name in ('hero','landed','active','top-active','gear-stowed') else None
            if name in ('front','rear'):crop=(110,300,1580,700)
            if name in ('left','right','right-stowed'):crop=(270,290,1410,720)
            sheet.paste(tile(folder/(name+'.png'),label,(700,440),crop),((i%3)*700,(i//3)*440))
            bounds[name]=Image.open(folder/(name+'.png')).getchannel('A').getbbox()
        sheet.save(folder/'all-views.png')
        report=json.loads((folder/'validation.json').read_text())
        glb=glb_document(folder/'airframe.glb');nodes=glb['nodes'];animations=glb.get('animations',[])
        moving={nodes[c['target']['node']]['name'] for a in animations for c in a['channels']}
        checks[role]={
            'geometryChecksPass':report['passed'],
            'equipmentOpeningsHaveClearDepth':report['pocketDepthChecks']['passed'],
            'baseUnchanged':report['baseSha256']==base_hash,
            'thirteenViewsFramed':all(b and b[0]>2 and b[1]>2 and b[2]<1679 and b[3]<934 for b in bounds.values()),
            'thirteenViewsFresh':all((folder/(name+'.png')).stat().st_mtime>stamp for name,_ in VIEWS),
            'thirteenViewsSameResolution':all(Image.open(folder/(name+'.png')).size==(1681,936) for name,_ in VIEWS),
            'blendAndGlbPresent':all((folder/p).stat().st_size>1000 for p in ('airframe.blend','airframe.glb')),
            'detailRenderFresh':(folder/'equipment-close.png').stat().st_mtime>stamp,
            'singleCombinedAnimation':len(animations)==1,
            'fourFeetAnimatedInExport':len([n for n in moving if n.startswith('GEAR_ContactPad')])==4,
            'exportHasMirroredHierarchy':any(n.get('name')=='V20_PortMechanisms' and mirrors_x(n) for n in nodes),
        }
        if role!='field':checks[role]['bodyActuallyReshaped']=report['continuousBody']['modifiedHullVertices']>=100
        if role in ('cargo','security'):checks[role]['bothHatchesAnimated']=len([n for n in moving if 'Pivot' in n])==2
        measurements[role]={'bounds':bounds,'body':report['continuousBody'],'landed':report['samples'][0],
                            'animatedNodes':len(moving),'nodeCount':len(nodes)}
    result={'passed':all(all(v.values()) for v in checks.values()),'checks':checks,'measurements':measurements,
            'renderSource':'Real Blender mesh, no generated or retouched aircraft image',
            'gameVerified':False,'fullSweptCollisionVerified':False,'aerodynamicsSimulated':False}
    (OUT/'output-checks.json').write_text(json.dumps(result,indent=2))
    print(json.dumps({'passed':result['passed'],'checks':checks},indent=2))
    if not result['passed']:raise RuntimeError('Artifact verification failed')


def comparison():
    board=Image.new('RGB',(1920,1590))
    for row,role in enumerate(('cargo','engineer','salvage')):
        for col,(folder,label) in enumerate(((OUT/'comparison-v20','V20 / 後付け構造'),(OUT,'V21 / 胴体統合'))):
            board.paste(tile(folder/role/'hero.png',role.upper()+' / '+label,(960,530),(220,180,1570,850)),
                        (col*960,row*530))
    board.save(OUT/'before-after.png')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--preview',action='store_true')
    args=parser.parse_args();boards()
    if not args.preview:verify()
    if (OUT/'comparison-v20/cargo/hero.png').exists():comparison()
