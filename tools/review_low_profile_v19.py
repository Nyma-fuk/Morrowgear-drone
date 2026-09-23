"""Same-camera review sheets of the actual V18/V19 meshes."""
import hashlib
import json
from pathlib import Path
from PIL import Image
from review_propulsion_v16 import tile

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'docs/design/reference-swept-tail-v18'
OUT=ROOT/'docs/design/reference-low-profile-v19'


def main():
    views=(('hero','前方俯瞰'),('front','正面'),('right','右側面'),
           ('rear-oblique','後方俯瞰'),('top','上面'),('bottom','底面'),
           ('rear','背面'),('left','左側面'),('underside','下面斜視'))
    sheet=Image.new('RGB',(2100,1590))
    for i,(name,label) in enumerate(views):
        sheet.paste(tile(OUT/(name+'.png'),label,(700,530)),((i%3)*700,(i//3)*530))
    sheet.save(OUT/'all-views.png')
    for name,crop,height in (('hero',(0,15,1681,850),800),
                             ('front',(40,300,1640,640),390),
                             ('right',(210,305,1471,645),420),
                             ('rear-oblique',None,790),('top',None,790)):
        board=Image.new('RGB',(1440,height*2))
        for i,(folder,title) in enumerate(((BASE,'変更前 V18 / 厚い胴体・反り上がった外翼'),
                                            (OUT,'修正案 V19 / 低い胴体・薄く水平に伸びる外翼'))):
            board.paste(tile(folder/(name+'.png'),title,(1440,height),crop),(0,i*height))
        board.save(OUT/('comparison-'+name+'.png'))
    tile(OUT/'hero.png','V19 / LOW-PROFILE AIRFRAME',(1680,980)).save(OUT/'preview.png')
    report=json.loads((OUT/'validation.json').read_text())
    bounds={n:Image.open(OUT/(n+'.png')).getchannel('A').getbbox() for n,_ in views}
    checks={
        'geometryPass':report['passed'],
        'nineViewsFramed':all(b and b[0]>2 and b[1]>2 and b[2]<1679 and b[3]<934 for b in bounds.values()),
        'nineViewsSameResolution':all(Image.open(OUT/(n+'.png')).size==(1681,936) for n,_ in views),
        'nineViewsFresh':all((OUT/(n+'.png')).stat().st_mtime>
                            (ROOT/'tools/drone-design/blender/build_low_profile_v19.py').stat().st_mtime for n,_ in views),
        'baseAssetUnmodified':report['sourceSha256']==hashlib.sha256((BASE/'airframe.blend').read_bytes()).hexdigest(),
        'exportsPresent':all((OUT/n).stat().st_size>1000 for n in ('airframe.blend','airframe.glb')),
    }
    (OUT/'output-checks.json').write_text(json.dumps({'checks':checks,'passed':all(checks.values()),
                                                   'bounds':bounds,'gameVerified':False},indent=2))
    print(json.dumps(checks,indent=2))
    if not all(checks.values()):raise RuntimeError('Output check failed')


if __name__=='__main__':main()
