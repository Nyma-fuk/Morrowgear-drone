"""Compare actual rendered geometry, source silhouette and longitudinal sections."""
import hashlib
import json
from pathlib import Path

from fit_reference_projection import np,cv2,silhouette_metrics,difference
from PIL import Image,ImageDraw,ImageFont
from review_reference_airframe_v11 import tile

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/design/reference-airframe-v13'
PREVIOUS=ROOT/'docs/design/reference-airframe-v11'
SOURCE=ROOT/'docs/design/blended-wing-v5/concept.png'
FONT='C:/Windows/Fonts/meiryo.ttc'


def section_segments(mesh,x):
    vertices=np.asarray(mesh['vertices']);lines=[]
    for face in mesh['faces']:
        pts=vertices[face];hits=[]
        for a,b in zip(pts,np.roll(pts,-1,axis=0)):
            if (a[0]<=x<b[0]) or (b[0]<=x<a[0]):
                t=(x-a[0])/(b[0]-a[0]);hits.append(a[1:]*(1-t)+b[1:]*t)
        if len(hits)==2:lines.append(hits)
    return lines


def section_board():
    board=Image.new('RGB',(1560,1340),(243,246,248));d=ImageDraw.Draw(board)
    font=lambda n:ImageFont.truetype(FONT,n)
    ink=(34,47,55);blue=(0,118,159);amber=(159,85,12);gray=(159,167,173)
    d.text((35,23),'MORROWGEAR / 機体断面の比較',font=font(31),fill=ink)
    d.text((35,75),'実メッシュを切断した線: 灰 V11 / 青 V13   |   横・縦は等縮尺',font=font(21),fill=ink)
    d.text((35,112),'形状検討用。気流線・CFD・揚力測定の結果ではありません。',font=font(20),fill=amber)
    before=json.loads((PREVIOUS/'hull-mesh.json').read_text());after=json.loads((OUT/'hull-mesh.json').read_text())
    for index,(x,title,note) in enumerate(((.02,'中心胴体','後端の厚みを抑える。前部のセンサー部はなお鈍い形状。'),
                                          (1.91,'ダクトを通る断面','中央の途切れは開口。ローター等の内部機器はこの線に含まれない。'),
                                          (3.30,'外翼の断面','前縁を丸め、後縁へ厚みを絞る。薄さだけで飛行性能は判定できない。'))):
        cy=300+index*270;scale=205;ox=820
        d.text((35,cy-80),f'{index+1:02d} / {title}',font=font(23),fill=ink)
        d.text((35,cy-43),f'X = {x:.2f}',font=font(18),fill=gray)
        d.line((310,cy,1440,cy),fill=(210,218,222),width=1)
        for mesh,color,width in ((before,gray,5),(after,blue,3)):
            for a,b in section_segments(mesh,x):
                d.line((ox+a[0]*scale,cy-a[1]*scale,ox+b[0]*scale,cy-b[1]*scale),fill=color,width=width)
        d.text((310,cy+106),'前方 ←    '+note,font=font(19),fill=ink)
    d.line((35,1070,1525,1070),fill=(194,206,211),width=2)
    notes=['修正済み: 左右共通形状 / 前後方向の厚み変化 / 後縁の絞り / 曲面の面分割',
           '未確定: ローター開口と前進時の流れ / ホバリング時の前後・ヨー制御',
           '未検証: 実質量・重心・推力・失速・安定性・騒音。実機としての飛行保証はしない。',
           '元画像への忠実度は別判定。下面と内部は画像にない設計仮説。']
    for i,text in enumerate(notes):d.text((35,1100+i*48),text,font=font(21),fill=ink if i==0 else amber)
    board.save(OUT/'section-comparison.png')


def main():
    mask_path=ROOT/'docs/design/reference-family-v10/analysis/reference-silhouette.png'
    reference=np.asarray(Image.open(mask_path))>127
    alpha=np.asarray(Image.open(OUT/'hero.png'))[:,:,3]
    contours,_=cv2.findContours((alpha>127).astype(np.uint8),cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
    mask=np.zeros_like(alpha);cv2.drawContours(mask,contours,-1,255,-1)
    stats=silhouette_metrics(reference,mask>0)
    stats.update(scope='External silhouette at the unchanged V11 comparison camera; not aerodynamic performance or complete visual fidelity.',
                 sourceAnnotationReviewed=False,visualApproval=False,gameVerified=False,aerodynamicSimulationPerformed=False)
    stats['inputSha256']={str(q.relative_to(ROOT)):hashlib.sha256(q.read_bytes()).hexdigest() for q in (SOURCE,OUT/'hero.png',OUT/'hull-mesh.json',mask_path)}
    Image.fromarray(difference(reference,mask>0)).save(OUT/'silhouette-difference.png')
    (OUT/'comparison.json').write_text(json.dumps(stats,indent=2))
    board=Image.new('RGB',(1680,1100),(23,27,29))
    for i,(path,title) in enumerate(((SOURCE,'承認された参照画像'),(OUT/'hero.png','V13 / 断面修正候補・実3Dモデル'),
                                   (PREVIOUS/'hero.png','V11 / 修正前・同じカメラ'),(OUT/'silhouette-difference.png','外形差分 / 赤: 不足　青: はみ出し'))):
        board.paste(tile(path,title),((i%2)*840,(i//2)*510))
    ImageDraw.Draw(board).text((18,1034),f"輪郭IoU {stats['silhouetteIoU']:.3f} / 平均誤差 {stats['meanContourErrorPx']:.1f}px / 形状一致・飛行成立は未承認",font=ImageFont.truetype(FONT,19),fill=(225,200,133))
    board.save(OUT/'comparison-board.png')
    views=Image.new('RGB',(1680,1260),(23,27,29))
    for i,(name,title) in enumerate((('hero','斜め前'),('front','正面'),('rear','背面'),('top','上面'),('bottom','底面'),('left','左側面'),('right','右側面'),('underside','下面斜視'),('clay','無彩色・形状確認'))):
        views.paste(tile(OUT/(name+'.png'),title,560,420),((i%3)*560,(i//3)*420))
    views.save(OUT/'geometry-views.png')
    section_board()
    print(json.dumps(stats,indent=2))


if __name__=='__main__':main()
