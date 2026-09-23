"""Fixed-camera comparisons of the local nose proposal, without image warping."""
import hashlib
import json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'docs/design/reference-wingtip-v14'
OUT=ROOT/'docs/design/reference-nose-v15'
FONT='C:/Windows/Fonts/meiryo.ttc'


def tile(path,title,size,crop=None):
    im=Image.open(path).convert('RGBA')
    bg=Image.new('RGBA',im.size,(47,51,55,255));bg.alpha_composite(im)
    bg=bg.convert('RGB')
    if crop:bg=bg.crop(crop)
    bg.thumbnail((size[0]-20,size[1]-55))
    canvas=Image.new('RGB',size,(27,31,34));d=ImageDraw.Draw(canvas)
    d.text((14,12),title,font=ImageFont.truetype(FONT,23),fill=(230,238,239))
    canvas.paste(bg,((size[0]-bg.width)//2,52+(size[1]-52-bg.height)//2))
    return canvas


def main():
    board=Image.new('RGB',(1600,1270),(27,31,34))
    rows=[('hero','俯瞰',None,480),('top','上面・ノーズ拡大',(550,590,1130,936),440),
          ('left','側面・ノーズ拡大',(960,385,1340,595),350)]
    y=0
    for name,label,crop,height in rows:
        for i,(folder,version) in enumerate(((BASE,'V14 / 変更前'),(OUT,'V15 / 尖らせた案'))):
            board.paste(tile(folder/(name+'.png'),version+' / '+label,(800,height),crop),(800*i,y))
        y+=height
    board.save(OUT/'comparison.png')
    views=Image.new('RGB',(1800,1350),(27,31,34))
    for i,(name,label) in enumerate((('hero','前方俯瞰'),('top','上面'),('bottom','底面'),
                                    ('front','正面'),('rear','後方'),('left','左側面'),
                                    ('right','右側面'),('rear-oblique','後方俯瞰'),('underside','下面斜視'))):
        views.paste(tile(OUT/(name+'.png'),label,(600,450)),((i%3)*600,(i//3)*450))
    views.save(OUT/'all-views.png')
    report=json.loads((OUT/'validation.json').read_text())
    render_names=('hero','top','bottom','front','rear','left','right','rear-oblique','underside')
    output_checks={
        'sourcePreserved':report['sourceSha256']==hashlib.sha256((BASE/'airframe.blend').read_bytes()).hexdigest(),
        'allNineViewsNonblank':all(Image.open(OUT/(n+'.png')).getchannel('A').getbbox() for n in render_names),
        'allNineViewsSameResolution':all(Image.open(OUT/(n+'.png')).size==(1681,936) for n in render_names),
        'allNineViewsFresh':all((OUT/(n+'.png')).stat().st_mtime>(ROOT/'tools/drone-design/blender/build_pointed_nose_v15.py').stat().st_mtime for n in render_names),
        'exportsExist':all((OUT/n).stat().st_size>1000 for n in ('airframe.blend','airframe.glb')),
    }
    (OUT/'output-checks.json').write_text(json.dumps({'checks':output_checks,'passed':all(output_checks.values())},indent=2))
    print(json.dumps(output_checks,indent=2))
    if not all(output_checks.values()):raise RuntimeError('Output verification failed')


if __name__=='__main__':main()
