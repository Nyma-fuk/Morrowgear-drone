"""Contact sheets of actual Blender renders. No image synthesis or shape warping."""
import hashlib
import json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/design/reference-propulsion-v16'
BASE=ROOT/'docs/design/reference-nose-v15'
FONT='C:/Windows/Fonts/meiryo.ttc'


def tile(path,title,size,crop=None):
    im=Image.open(path).convert('RGBA')
    bg=Image.new('RGBA',im.size,(43,47,51,255));bg.alpha_composite(im)
    bg=bg.convert('RGB')
    if crop:bg=bg.crop(crop)
    bg.thumbnail((size[0]-32,size[1]-64))
    canvas=Image.new('RGB',size,(25,29,32));d=ImageDraw.Draw(canvas)
    d.text((20,14),title,font=ImageFont.truetype(FONT,26),fill=(228,236,239))
    canvas.paste(bg,((size[0]-bg.width)//2,60+(size[1]-60-bg.height)//2))
    return canvas


def main():
    views=(('hero','前方俯瞰'),('rear-oblique','後方俯瞰'),('top','上面'),
           ('bottom','底面'),('front','正面'),('rear','背面'),('left','左側面'),
           ('right','右側面'),('underside','下面斜視'))
    sheet=Image.new('RGB',(2100,1590))
    for i,(name,label) in enumerate(views):
        sheet.paste(tile(OUT/(name+'.png'),label,(700,530)),((i%3)*700,(i//3)*530))
    sheet.save(OUT/'all-views.png')
    # Identical cameras and fixed crops preserve scale and evidence of silhouette changes.
    for view,crop,label in (('rear-oblique',(150,220,1681,820),'主翼から排気口へのつながり'),
                            ('hero',(0,0,1681,790),'吸気口と機体全体の比率'),
                            ('right',(330,285,1420,640),'側面の厚みと後端への絞り')):
        h=660 if view!='right' else 490
        board=Image.new('RGB',(1440,h*2),(25,29,32))
        for i,(folder,title) in enumerate(((BASE,'V15 / 変更前'),(OUT,'V16 / 後部再設計'))):
            board.paste(tile(folder/(view+'.png'),title+'  |  '+label,(1440,h),crop),(0,i*h))
        board.save(OUT/('comparison-'+view+'.png'))
    close=Image.new('RGB',(1440,1140))
    for i,(name,label) in enumerate((('intake-close','吸気口 / 開口と湾曲する奥行き'),
                                     ('propulsion-close','排気口 / 低く幅広い開口と翼の連続面'))):
        close.paste(tile(OUT/(name+'.png'),label,(1440,570)),(0,i*570))
    close.save(OUT/'details.png')
    report=json.loads((OUT/'validation.json').read_text())
    names=[v[0] for v in views]+['rear-quarter','propulsion-close','intake-close','rear-close']
    checks={
        'geometryChecksPass':report['passed'],
        'baseAssetUnchanged':report['sourceSha256']==hashlib.sha256((BASE/'airframe.blend').read_bytes()).hexdigest(),
        'all13RendersNonblank':all(Image.open(OUT/(n+'.png')).getchannel('A').getbbox() for n in names),
        'all13RendersSameResolution':all(Image.open(OUT/(n+'.png')).size==(1681,936) for n in names),
        'all13RendersFresh':all((OUT/(n+'.png')).stat().st_mtime>(ROOT/'tools/drone-design/blender/build_propulsion_v16.py').stat().st_mtime for n in names),
        'modelExportsExist':all((OUT/n).stat().st_size>1000 for n in ('airframe.blend','airframe.glb')),
    }
    result={'checks':checks,'passed':all(checks.values()),'gameAssetsModified':False,
            'imageGenerationUsed':False,'referenceFidelityApproved':False}
    (OUT/'output-checks.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result,indent=2))
    if not result['passed']:raise RuntimeError('Output verification failed')


if __name__=='__main__':main()
