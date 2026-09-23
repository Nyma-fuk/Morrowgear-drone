"""Review all rendered parts in the fixed camera, without warping either image."""
import hashlib
import json
from pathlib import Path

from fit_reference_projection import silhouette_metrics,difference,cv2,np
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/design/reference-airframe-v11'
REF=ROOT/'docs/design/reference-family-v10/analysis'
SOURCE=ROOT/'docs/design/blended-wing-v5/concept.png'
FONT='C:/Windows/Fonts/meiryo.ttc'


def tile(path,title,width=840,height=510):
    im=Image.open(path).convert('RGBA');im.thumbnail((width-20,height-68))
    canvas=Image.new('RGB',(width,height),(55,58,62))
    canvas.paste(im,((width-im.width)//2,54+(height-64-im.height)//2),im)
    d=ImageDraw.Draw(canvas);d.rectangle((0,0,width,46),fill=(23,27,29))
    d.text((16,10),title,font=ImageFont.truetype(FONT,18),fill=(232,238,240))
    return canvas


def main():
    reference=np.asarray(Image.open(REF/'reference-silhouette.png'))>127
    alpha=np.asarray(Image.open(OUT/'hero.png'))[:,:,3]
    if alpha.shape!=reference.shape:raise ValueError('Unmatched reference/render dimensions')
    contours,_=cv2.findContours((alpha>127).astype(np.uint8),cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
    mask=np.zeros_like(alpha);cv2.drawContours(mask,contours,-1,255,-1);candidate=mask>0
    stats=silhouette_metrics(reference,candidate)
    stats.update(scope='Fixed-camera full-model external silhouette. Not 3D fidelity or flight balance.',
                 referenceAnnotationReviewed=False,visualApproval=False)
    stats['inputSha256']={str(q.relative_to(ROOT)):hashlib.sha256(q.read_bytes()).hexdigest() for q in (SOURCE,OUT/'hero.png',OUT/'hull-mesh.json',REF/'reference-silhouette.png')}
    (OUT/'comparison.json').write_text(json.dumps(stats,indent=2),encoding='utf-8')
    Image.fromarray(difference(reference,candidate)).save(OUT/'silhouette-difference.png')
    board=Image.new('RGB',(1680,1100),(23,27,29))
    items=[(SOURCE,'参照画像'),(OUT/'hero.png','V11 / 断面から作り直した実3Dモデル'),
           (ROOT/'docs/design/reference-family-v10/base/hero.png','前回 V10 / 輪郭補正が内部の面にも影響'),
           (OUT/'silhouette-difference.png','V11 外形差分 / 赤:不足  青:はみ出し')]
    for i,(path,title) in enumerate(items):board.paste(tile(path,title),((i%2)*840,(i//2)*510))
    d=ImageDraw.Draw(board);d.text((18,1035),f"外形IoU {stats['silhouetteIoU']:.3f} / 平均輪郭誤差 {stats['meanContourErrorPx']:.1f}px / 外観承認・実ゲーム検証は未実施",font=ImageFont.truetype(FONT,18),fill=(225,200,133))
    board.save(OUT/'comparison-board.png')
    if (OUT/'clay.png').exists():
        views=Image.new('RGB',(1680,1260),(23,27,29))
        for i,(name,title) in enumerate((('hero','斜め前'),('front','正面 / 左右対称'),('rear','後面'),('top','上面 / 翼と胴体の面積'),('bottom','底面'),('left','左側面 / 厚み'),('right','右側面 / 厚み'),('underside','下面斜視'),('clay','無彩色 / 形状確認'))):
            views.paste(tile(OUT/(name+'.png'),title,560,420),((i%3)*560,(i//3)*420))
        views.save(OUT/'geometry-views.png')
    print(json.dumps(stats,indent=2))


if __name__=='__main__':main()
