"""Assemble unwarped diagnostic evidence and the actual rendered geometry."""
import json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/design/reference-family-v10'
A=OUT/'analysis'
FONT='C:/Windows/Fonts/meiryo.ttc'


def tile(path,title,size=(840,510),bg=(52,56,61)):
    canvas=Image.new('RGB',size,bg)
    image=Image.open(path).convert('RGBA');image.thumbnail((size[0]-16,size[1]-65))
    canvas.paste(image,((size[0]-image.width)//2,55+(size[1]-65-image.height)//2),image)
    draw=ImageDraw.Draw(canvas);draw.rectangle((0,0,size[0],48),fill=(23,28,31))
    draw.text((15,10),title,font=ImageFont.truetype(FONT,19),fill=(235,240,243))
    return canvas


def main():
    stats=json.loads((A/'latest-comparison.json').read_text(encoding='utf-8'))
    board=Image.new('RGB',(1680,1124),(23,28,31))
    items=[(ROOT/'docs/design/blended-wing-v5/concept.png','参照画像 / 2Dデザイン画像'),
           (OUT/'base/hero.png','実3Dモデルの描画 / 再現性は未合格'),
           (A/'latest-visible-mesh-overlay.png','実メッシュの可視面 / 隠面処理・カメラ固定'),
           (A/'latest-full-model-difference.png','全パーツの輪郭差分 / 赤:不足   青:はみ出し')]
    for i,(path,title) in enumerate(items):board.paste(tile(path,title),((i%2)*840,(i//2)*510))
    d=ImageDraw.Draw(board);font=ImageFont.truetype(FONT,19)
    whole=stats['fullModelSilhouette']
    d.text((18,1033),f"全体外形IoU {whole['silhouetteIoU']:.3f} / 輪郭平均誤差 {whole['meanContourErrorPx']:.1f}px / 主要点RMS {stats['landmarkRmsErrorPx']:.1f}px",font=font,fill=(245,193,101))
    d.text((18,1065),'単一視点の診断値です。3D全体の再現率ではありません。材質・機構・他方向は別の評価項目です。',font=font,fill=(220,228,231))
    board.save(A/'comparison-board.png')
    views=Image.new('RGB',(1680,1260),(23,28,31))
    for i,(name,title) in enumerate((('hero','立体'),('front','前面'),('rear','後面'),('top','上面'),('bottom','底面'),('left','左側面'),('right','右側面'),('underside','下面斜視'),('clay','材質を外した形状確認'))):
        views.paste(tile(OUT/'base'/f'{name}.png',title,(560,420)),((i%3)*560,(i//3)*420))
    views.save(A/'geometry-views.png')
    print(str(A/'comparison-board.png'))


if __name__=='__main__':main()
