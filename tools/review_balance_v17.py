"""Review sheets, same-camera comparisons, and framing checks for V17."""
import hashlib
import json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
from review_propulsion_v16 import tile

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/design/reference-balance-v17'
BASE=ROOT/'docs/design/reference-propulsion-v16'
ORIGINAL=ROOT/'docs/design/reference-nose-v15'
FONT='C:/Windows/Fonts/meiryo.ttc'


def main():
    views=(('hero','前方俯瞰'),('rear-oblique','後方俯瞰'),('top','上面'),
           ('bottom','底面'),('front','正面'),('rear','背面'),('left','左側面'),
           ('right','右側面'),('underside','下面斜視'))
    sheet=Image.new('RGB',(2100,1590))
    for i,(name,label) in enumerate(views):
        sheet.paste(tile(OUT/(name+'.png'),label,(700,530)),((i%3)*700,(i//3)*530))
    sheet.save(OUT/'all-views.png')
    for name in ('hero','rear-oblique'):
        board=Image.new('RGB',(1440,1300))
        for i,(folder,label) in enumerate(((BASE,'V16 / 後部だけを大型化した案'),(OUT,'V17 / 全体の比率を再配分'))):
            board.paste(tile(folder/(name+'.png'),label,(1440,650),(0,40,1681,870)),(0,i*650))
        board.save(OUT/('comparison-'+name+'.png'))
    proportion=Image.new('RGB',(1200,1020),(25,29,32))
    proportion.paste(tile(OUT/'top.png','V17 / 主翼・浮上ファン・推進口を一体で再配分',(1200,770)),(0,0))
    d=ImageDraw.Draw(proportion)
    m=json.loads((OUT/'validation.json').read_text())['measurements']
    for i,text in enumerate(('翼幅 9.67 → 8.90   /   ローター径 1.80 → 2.04',
                             'ローター径 / 翼幅 18.6% → 22.9%',
                             '排気口 幅 2.07 → 1.66   /   高さ 0.314 → 0.239',
                             '数値は造形用の相対寸法。実機の推力・揚力を示すものではありません。')):
        d.text((30,793+52*i),text,font=ImageFont.truetype(FONT,25 if i<3 else 19),fill=(210,225,230))
    proportion.save(OUT/'proportions.png')
    # A frame touching alpha bounds is a failed overview even if it is nonblank.
    bounds={name:Image.open(OUT/(name+'.png')).getchannel('A').getbbox() for name,_ in views}
    report=json.loads((OUT/'validation.json').read_text())
    checks={'geometryPass':report['passed'],
            'allNineOverviewsFramed':all(b is not None and b[0]>2 and b[1]>2 and b[2]<1679 and b[3]<934 for b in bounds.values()),
            'allNineOverviewsFresh':all((OUT/(n+'.png')).stat().st_mtime>(ROOT/'tools/drone-design/blender/build_balanced_airframe_v17.py').stat().st_mtime for n,_ in views),
            'sourceUnmodified':report['sourceSha256']==hashlib.sha256((BASE/'airframe.blend').read_bytes()).hexdigest(),
            'exportsExist':all((OUT/n).stat().st_size>1000 for n in ('airframe.blend','airframe.glb'))}
    (OUT/'output-checks.json').write_text(json.dumps({'checks':checks,'passed':all(checks.values()),'overviewPixelBounds':bounds},indent=2))
    print(json.dumps(checks,indent=2))
    if not all(checks.values()):raise RuntimeError('Review output failed')


if __name__=='__main__':main()
