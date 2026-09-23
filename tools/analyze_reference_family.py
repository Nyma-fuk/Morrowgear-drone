"""Image-space evidence, not a single-image depth reconstruction."""
import json
import sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'work/visual-analysis-libs'))
import cv2
import numpy as np
from PIL import Image,ImageDraw,ImageFont

OUT=ROOT/'docs/design/reference-family-v10/analysis'
OUT.mkdir(parents=True,exist_ok=True)
SOURCE=ROOT/'docs/design/blended-wing-v5/concept.png'
FONT=Path('C:/Windows/Fonts/meiryo.ttc')

# These are reviewable image annotations, not mechanically recovered 3D coordinates.
POLYGON=[(211,62),(198,115),(246,171),(485,214),(714,207),(817,199),
         (869,228),(934,241),(978,244),(1027,252),(1042,283),(1080,313),
         (1402,455),(1518,448),(1578,402),(1644,521),(1628,539),(1527,591),
         (1164,619),(833,666),(645,701),(556,720),(481,730),(373,710),(346,667),(330,612),
         (319,548),(41,231),(36,214),(43,141)]
LANDMARKS={
    'nose_visor': [413,634],
    'nose_right_corner': [480,650],
    'chin_sensor': [398,687],
    'fan_left': [400,293],
    'fan_right': [1034,451],
    'tip_left_front': [37,215],
    'tip_left_rear': [211,62],
    'tip_right_front': [1642,522],
    'tip_right_rear': [1577,402],
    'rear_crown': [882,246],
}


def pil(array):return Image.fromarray(cv2.cvtColor(array,cv2.COLOR_BGR2RGB))


def label(image,title):
    draw=ImageDraw.Draw(image)
    draw.rectangle((0,0,image.width,38),fill=(20,24,27))
    draw.text((12,7),title,font=ImageFont.truetype(str(FONT),16),fill=(236,241,243))
    return image


def main():
    src=cv2.imdecode(np.fromfile(SOURCE,dtype=np.uint8),cv2.IMREAD_COLOR)
    h,w=src.shape[:2]
    seed=np.zeros((h,w),np.uint8)
    cv2.fillPoly(seed,[np.array(POLYGON,np.int32)],255)
    inside=cv2.erode(seed,cv2.getStructuringElement(cv2.MORPH_ELLIPSE,(35,35)))
    outside=cv2.dilate(seed,cv2.getStructuringElement(cv2.MORPH_ELLIPSE,(25,25)))
    mask=np.full((h,w),cv2.GC_BGD,np.uint8)
    mask[outside>0]=cv2.GC_PR_BGD;mask[seed>0]=cv2.GC_PR_FGD;mask[inside>0]=cv2.GC_FGD
    cv2.line(mask,(822,641),(1515,573),cv2.GC_FGD,18)
    cv2.grabCut(src,mask,None,np.zeros((1,65),np.float64),np.zeros((1,65),np.float64),6,cv2.GC_INIT_WITH_MASK)
    foreground=np.where((mask==cv2.GC_FGD)|(mask==cv2.GC_PR_FGD),255,0).astype(np.uint8)
    Image.fromarray(foreground).save(OUT/'reference-silhouette.png')
    Image.fromarray(seed).save(OUT/'reference-annotated-polygon.png')
    gray=cv2.cvtColor(src,cv2.COLOR_BGR2GRAY)
    enhanced=cv2.createCLAHE(clipLimit=2.0,tileGridSize=(12,12)).apply(gray)
    threshold,_=cv2.threshold(gray[foreground>0],0,255,cv2.THRESH_BINARY+cv2.THRESH_OTSU)
    binary=np.zeros_like(gray);binary[(gray>threshold)&(foreground>0)]=255
    Image.fromarray(binary).save(OUT/'reference-light-dark-binary.png')
    levels=np.zeros_like(src)
    colors=((38,44,48),(82,93,103),(134,147,157),(204,213,220))
    cuts=np.quantile(gray[foreground>0],[.25,.50,.75])
    for i,color in enumerate(colors):levels[(np.digitize(gray,cuts)==i)&(foreground>0)]=color
    pil(levels).save(OUT/'reference-luminance-levels.png')
    edges=cv2.Canny(enhanced,55,125)
    edges[cv2.erode(foreground,np.ones((5,5),np.uint8))==0]=0
    Image.fromarray(edges).save(OUT/'reference-feature-edges.png')
    annotated=src.copy()
    cv2.polylines(annotated,[np.array(POLYGON,np.int32)],True,(0,180,255),1,cv2.LINE_AA)
    contours,_=cv2.findContours(foreground,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
    cv2.drawContours(annotated,contours,-1,(235,205,0),1,cv2.LINE_AA)
    for name,(x,y) in LANDMARKS.items():
        cv2.drawMarker(annotated,(x,y),(40,50,250),cv2.MARKER_CROSS,16,2)
        cv2.putText(annotated,name,(x+8,y-9),cv2.FONT_HERSHEY_SIMPLEX,.42,(20,20,240),1,cv2.LINE_AA)
    pil(annotated).save(OUT/'reference-annotations.png')
    crops=Image.new('RGB',(1440,540),(22,26,29))
    for index,(x,y) in enumerate(((400,300),(1036,470),(480,650))):
        crop=pil(src[max(0,y-90):y+90,x-120:x+120]).resize((480,360))
        draw=ImageDraw.Draw(crop)
        for a in range(0,240,20):
            draw.line((a*2,0,a*2,360),fill=(90,130,140),width=1)
            draw.text((a*2,0),str(x-120+a),font=ImageFont.truetype(str(FONT),11),fill=(255,225,120))
        for a in range(0,180,20):
            draw.line((0,a*2,480,a*2),fill=(90,130,140),width=1)
            draw.text((0,a*2),str(y-90+a),font=ImageFont.truetype(str(FONT),11),fill=(255,225,120))
        crops.paste(crop,(index*480,0))
    crops.save(OUT/'landmark-closeups.png')
    board=Image.new('RGB',(1680,1000),(22,26,29))
    images=[(pil(annotated),'参照画像 / 注釈点・抽出輪郭'),
            (Image.fromarray(foreground).convert('RGB'),'外形の二値化 / 陰影とは別に抽出'),
            (pil(levels),'明暗4階調 / 奥行きではなく輝度'),
            (Image.fromarray(edges).convert('RGB'),'面・開口・模様の境界候補 / 形状確定には使わない')]
    for index,(im,title) in enumerate(images):
        canvas=Image.new('RGB',(840,500),(22,26,29))
        im.thumbnail((830,455));canvas.paste(im,((840-im.width)//2,42+(455-im.height)//2));label(canvas,title)
        board.paste(canvas,((index%2)*840,(index//2)*500))
    board.save(OUT/'reference-analysis.png')
    (OUT/'reference-annotations.json').write_text(json.dumps({
        'source':str(SOURCE.relative_to(ROOT)),'size':[w,h],
        'method':'Manual contour seeds plus GrabCut; visible landmarks are human image annotations.',
        'manualPolygon':POLYGON,'landmarks':LANDMARKS,'shadingIsNotDepth':True,
        'otsuThreshold':float(threshold),'luminanceQuartiles':cuts.tolist(),
        'annotationReviewed':False,'silhouetteUncertaintyPixels':6,
    },indent=2),encoding='utf-8')
    print(json.dumps({'output':str(OUT),'foregroundPixels':int(np.count_nonzero(foreground)),'threshold':float(threshold)}))


if __name__=='__main__':main()
