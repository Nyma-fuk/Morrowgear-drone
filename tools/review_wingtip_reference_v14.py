"""Reference crops and measured comparisons for the V14 wingtip review."""
from pathlib import Path
import json
import hashlib
from PIL import Image,ImageDraw,ImageFont
from fit_reference_projection import np,cv2,silhouette_metrics,difference

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/design/reference-wingtip-v14'
FONT='C:/Windows/Fonts/meiryo.ttc'
REGIONS={'left-tip':(20,35,310,300),'right-tip':(1310,345,1665,605),
         'aft-fairings':(710,175,1080,355)}


def crops():
    OUT.mkdir(parents=True,exist_ok=True)
    source=Image.open(ROOT/'docs/design/blended-wing-v5/concept.png').convert('RGB')
    candidate=Image.open(ROOT/'docs/design/reference-airframe-v13/hero.png').convert('RGBA')
    bg=Image.new('RGBA',candidate.size,(55,58,62,255));bg.alpha_composite(candidate);candidate=bg.convert('RGB')
    for name,box in REGIONS.items():
        width,height=box[2]-box[0],box[3]-box[1]
        board=Image.new('RGB',(width*4,height*2+70),(28,32,35));d=ImageDraw.Draw(board)
        for i,(im,title) in enumerate(((source,'REFERENCE'),(candidate,'V13 / CURRENT MODEL'))):
            board.paste(im.crop(box).resize((width*2,height*2)),(i*width*2,70))
            d.text((i*width*2+18,18),title,font=ImageFont.truetype(FONT,23),fill=(228,236,241))
        board.save(OUT/(name+'-before.png'))


def tile(path,title,width,height):
    im=Image.open(path).convert('RGBA')
    bg=Image.new('RGBA',im.size,(47,51,55,255));bg.alpha_composite(im)
    bg=bg.convert('RGB');bg.thumbnail((width-28,height-62))
    tile=Image.new('RGB',(width,height),(27,31,34));d=ImageDraw.Draw(tile)
    d.text((16,10),title,font=ImageFont.truetype(FONT,22),fill=(221,231,235))
    tile.paste(bg,((width-bg.width)//2,48+(height-48-bg.height)//2))
    return tile


def review():
    source=ROOT/'docs/design/blended-wing-v5/concept.png'
    before=ROOT/'docs/design/reference-airframe-v13'
    after=Image.open(OUT/'hero.png').convert('RGBA')
    mask_path=ROOT/'docs/design/reference-family-v10/analysis/reference-silhouette.png'
    reference=np.asarray(Image.open(mask_path))>127
    def mask(path):
        alpha=np.asarray(Image.open(path))[:,:,3]
        contours,_=cv2.findContours((alpha>127).astype(np.uint8),cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
        result=np.zeros_like(alpha);cv2.drawContours(result,contours,-1,255,-1)
        return result>0
    current=mask(OUT/'hero.png');previous=mask(before/'hero.png')
    stats={'scope':'Fixed-camera silhouettes only; no aerodynamic or full likeness proof.',
           'sourceAnnotationReviewed':False,'visualApproval':False,
           'before':silhouette_metrics(reference,previous),'after':silhouette_metrics(reference,current)}
    stats['regions']={}
    for name,(x0,y0,x1,y1) in REGIONS.items():
        sl=np.s_[y0:y1,x0:x1]
        stats['regions'][name]={label:float(np.logical_and(reference[sl],m[sl]).sum()/max(1,np.logical_or(reference[sl],m[sl]).sum())) for label,m in [('beforeIoU',previous),('afterIoU',current)]}
    stats['sha256']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (OUT/'airframe.blend',OUT/'hero.png',OUT/'hull-mesh.json')}
    (OUT/'comparison.json').write_text(json.dumps(stats,indent=2))
    Image.fromarray(difference(reference,current)).save(OUT/'silhouette-difference.png')
    board=Image.new('RGB',(1680,1100),(27,31,34))
    for i,(path,label) in enumerate(((source,'元画像 / 承認されたデザイン'),(OUT/'hero.png','V14 / 修正候補・実3Dモデル'),
                                    (before/'hero.png','V13 / 修正前・同じカメラ'),(OUT/'silhouette-difference.png','元画像との差 / 赤: 不足　青: はみ出し'))):
        board.paste(tile(path,label,840,510),((i%2)*840,(i//2)*510))
    ImageDraw.Draw(board).text((20,1040),'上下・左右・後方は同じモデルの投影。元画像にない面は推定設計。ゲーム未反映。',font=ImageFont.truetype(FONT,23),fill=(224,200,135))
    board.save(OUT/'comparison-board.png')
    for name,box in REGIONS.items():
        width,height=box[2]-box[0],box[3]-box[1]
        board=Image.new('RGB',(width*6,height*2+65),(27,31,34));d=ImageDraw.Draw(board)
        for i,(path,label) in enumerate(((source,'REFERENCE'),(before/'hero.png','V13'),(OUT/'hero.png','V14'))):
            im=Image.open(path).convert('RGBA');bg=Image.new('RGBA',im.size,(47,51,55,255));bg.alpha_composite(im)
            board.paste(bg.crop(box).resize((width*2,height*2)),(i*width*2,65))
            d.text((i*width*2+15,16),label,font=ImageFont.truetype(FONT,24),fill=(220,231,235))
        board.save(OUT/(name+'-comparison.png'))
    views=Image.new('RGB',(1800,1320),(27,31,34))
    for i,(name,label) in enumerate((('top','上面'),('bottom','底面 / 隠れている面は推定'),
                                    ('front','正面'),('rear','後方 / 隠れている面は推定'),
                                    ('left','左側面'),('right','右側面'))):
        views.paste(tile(OUT/(name+'.png'),label,900,440),((i%2)*900,(i//2)*440))
    views.save(OUT/'six-views.png')
    views=Image.new('RGB',(1680,1080),(27,31,34))
    for i,(name,label) in enumerate((('hero','前方俯瞰 / 元画像比較カメラ'),('rear-oblique','後方俯瞰'),('underside','下面斜視'),('clay','無彩色・形状確認'))):
        views.paste(tile(OUT/(name+'.png'),label,840,540),((i%2)*840,(i//2)*540))
    views.save(OUT/'oblique-views.png')
    print(json.dumps(stats,indent=2))


if __name__=='__main__':
    crops()
    if (OUT/'rear-oblique.png').exists():review()
