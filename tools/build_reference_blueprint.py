"""Reference annotations, an explicitly inferred six-view drawing, and model overlay."""
import base64
import hashlib
import io
import json
import math
from functools import lru_cache
from pathlib import Path
from xml.etree import ElementTree as ET

from fit_reference_projection import np,cv2
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/design/reference-blueprint-v12'
DATA=json.loads((OUT/'constraints.json').read_text(encoding='utf-8'))
FONT='C:/Windows/Fonts/meiryo.ttc'
INK=(33,51,62);BLUE=(0,117,159);AMBER=(172,91,18);GREY=(181,189,193);BG=(245,247,248)
W,H=2400,1980
SPAN=2*max(x for x,y in DATA['halfOutline'])


class Sheet:
    def __init__(self):
        self.image=Image.new('RGB',(W,H),BG);self.draw=ImageDraw.Draw(self.image)
        self.svg=ET.Element('svg',xmlns='http://www.w3.org/2000/svg',width=str(W),height=str(H),viewBox=f'0 0 {W} {H}')
        ET.SubElement(self.svg,'rect',x='0',y='0',width=str(W),height=str(H),fill='#f5f7f8')
    def line(self,pts,color=BLUE,width=2,dash=False,closed=False):
        if closed:pts=list(pts)+[pts[0]]
        if dash:
            for a,z in zip(pts,pts[1:]):
                dx,dy=z[0]-a[0],z[1]-a[1];length=math.hypot(dx,dy)
                for start in range(0,int(length),13):
                    lo=start/max(length,1);hi=min(start+7,length)/max(length,1)
                    self.draw.line((a[0]+dx*lo,a[1]+dy*lo,a[0]+dx*hi,a[1]+dy*hi),fill=color,width=width)
        else:self.draw.line(pts,fill=color,width=width,joint='curve')
        attrs={'points':' '.join(f'{x:.2f},{y:.2f}' for x,y in pts),'fill':'none','stroke':'#%02x%02x%02x'%color,'stroke-width':str(width)}
        if dash:attrs['stroke-dasharray']='7 6'
        ET.SubElement(self.svg,'polyline',attrs)
    def text(self,xy,value,size=20,color=INK):
        self.draw.text(xy,value,font=ImageFont.truetype(FONT,size),fill=color)
        el=ET.SubElement(self.svg,'text',x=str(xy[0]),y=str(xy[1]+size),fill='#%02x%02x%02x'%color,style=f'font-family:Meiryo,sans-serif;font-size:{size}px');el.text=value
    def ellipse(self,box,color=BLUE,width=2):
        self.draw.ellipse(box,outline=color,width=width)
        x,y,z,t=box;ET.SubElement(self.svg,'ellipse',cx=str((x+z)/2),cy=str((y+t)/2),rx=str((z-x)/2),ry=str((t-y)/2),fill='none',stroke='#%02x%02x%02x'%color,**{'stroke-width':str(width)})
    def save(self,name):
        self.image.save(OUT/(name+'.png'));ET.indent(self.svg);ET.ElementTree(self.svg).write(OUT/(name+'.svg'),encoding='utf-8',xml_declaration=True)


def full_outline():
    half=DATA['halfOutline'];return half+[[-x,y] for x,y in half[-2:0:-1]]


def profile(points, value):
    return float(np.interp(value, [p[0] for p in points], [p[1] for p in points]))


def skin_z(x, y, upper=True):
    """One provisional surface law shared by every envelope and section."""
    crown=profile(DATA['crownProfile' if upper else 'bellyProfile'],y)
    wing=profile(DATA['wingTopProfile' if upper else 'wingBottomProfile'],abs(x))
    weight=min(1.0,max(0.0,(abs(x)-DATA['crownHalfWidth'])/
                        (DATA['fuselageShoulderHalfWidth']-DATA['crownHalfWidth'])))
    return crown*(1-weight)+wing*weight


def outline_intervals(station):
    intersections=[];outline=full_outline()
    for (x1,y1),(x2,y2) in zip(outline,outline[1:]+outline[:1]):
        if min(y1,y2)<=station<max(y1,y2):
            intersections.append(x1+(x2-x1)*(station-y1)/(y2-y1))
    intersections.sort()
    return list(zip(intersections[::2],intersections[1::2]))


def fan_gaps(station):
    f=DATA['fan'];delta=station-f['centerY']
    if abs(delta)>=f['radius']:return []
    half=math.sqrt(f['radius']**2-delta**2)
    return [(s*f['centerX']-half,s*f['centerX']+half) for s in (-1,1)]


def section_intervals(station):
    pieces=outline_intervals(station)
    for lo,hi in fan_gaps(station):
        result=[]
        for a,b in pieces:
            if hi<=a or lo>=b:result.append((a,b))
            else:
                if a<lo:result.append((a,lo))
                if hi<b:result.append((hi,b))
        pieces=result
    return pieces


@lru_cache(maxsize=None)
def surface_grid():
    ys=np.unique(np.r_[np.linspace(-2.65,1.78-1e-8,650),
                      [p[0] for p in DATA['crownProfile']],
                      [p[0] for p in DATA['bellyProfile']],
                      [p['y'] for p in DATA['sectionStations']]])
    xs=np.unique(np.r_[np.linspace(-SPAN/2,SPAN/2,901),
                      [s*p[0] for p in DATA['wingTopProfile'] for s in (-1,1)],
                      [-DATA['crownHalfWidth'],DATA['crownHalfWidth']],
                      [-DATA['fuselageShoulderHalfWidth'],DATA['fuselageShoulderHalfWidth']]])
    xx,yy=np.meshgrid(xs,ys);inside=np.zeros(xx.shape,dtype=bool)
    for i,y in enumerate(ys):
        for a,b in section_intervals(float(y)):inside[i]|=(xs>=a)&(xs<=b)
    weight=np.clip((np.abs(xx)-DATA['crownHalfWidth'])/
                   (DATA['fuselageShoulderHalfWidth']-DATA['crownHalfWidth']),0,1)
    def grid(upper):
        cp=DATA['crownProfile' if upper else 'bellyProfile']
        wp=DATA['wingTopProfile' if upper else 'wingBottomProfile']
        crown=np.interp(yy,[p[0] for p in cp],[p[1] for p in cp])
        wing=np.interp(np.abs(xx),[p[0] for p in wp],[p[1] for p in wp])
        return crown*(1-weight)+wing*weight
    return xs,ys,inside,grid(True),grid(False)


def envelope(front=True):
    xs,ys,inside,upper,lower=surface_grid();axis=0 if front else 1
    valid=inside.any(axis=axis);coords=(xs if front else ys)[valid]
    top=np.max(np.where(inside,upper,-np.inf),axis=axis)[valid]
    bottom=np.min(np.where(inside,lower,np.inf),axis=axis)[valid]
    return list(zip(coords,top)),list(zip(coords,bottom))


def frame(sheet,title,subtitle,origin,scale):
    x,y=origin
    sheet.text((x-350,y-245),title,26)
    sheet.text((x-350,y-209),subtitle,17,AMBER)
    sheet.line([(x-360,y),(x+360,y)],GREY,1,True)
    sheet.line([(x,y-180),(x,y+205)],GREY,1,True)


def dimension(sheet,a,b,label):
    sheet.line([a,b],INK,1)
    for x,y in (a,b):sheet.line([(x-4,y-5),(x+4,y+5)],INK,1)
    sheet.text(((a[0]+b[0])/2-100,(a[1]+b[1])/2+6),label,16)


def fan_top(sheet,x,y,r,hidden=False):
    color=AMBER if hidden else BLUE
    sheet.ellipse((x-r,y-r,x+r,y+r),color)
    sheet.ellipse((x-r*.88,y-r*.88,x+r*.88,y+r*.88),color,1)
    sheet.ellipse((x-r*.15,y-r*.15,x+r*.15,y+r*.15),color,2)
    for i in range(12):
        a=i*math.tau/12
        sheet.line([(x+r*.2*math.cos(a),y+r*.2*math.sin(a)),(x+r*.82*math.cos(a+.13),y+r*.82*math.sin(a+.13))],color,1)


def view(sheet,name,origin,scale):
    ox,oy=origin;f=DATA['fan'];top=name in ('top','bottom')
    def point(a,z):return ox+a*scale,oy+z*scale
    if top:
        sheet.line([point(x,y+.3) for x,y in full_outline()],BLUE,3,closed=True)
        for sign in (-1,1):
            fan_top(sheet,*point(sign*f['centerX'],f['centerY']+.3),f['radius']*scale,name=='bottom')
            sheet.line([point(sign*.51,-2.38+.3),point(sign*.64,-1.65+.3),point(sign*.64,1.43+.3)],BLUE,2)
            sheet.line([point(sign*3.95,.10+.3),point(sign*3.70,1.14+.3)],BLUE,3)
            if name=='top':
                sheet.line([point(sign*.76,-1.48+.3),point(sign*1.04,-.72+.3),point(sign*.86,-.50+.3)],BLUE,2,closed=True)
            else:
                for y in (-1.28,.91):
                    pts=[(.54,y-.25),(.88,y-.25),(.88,y+.25),(.54,y+.25)]
                    sheet.line([point(sign*x,z+.3) for x,z in pts],AMBER,2,True,True)
        if name=='top':
            for y in (-1.68,-.83,.15,.92):sheet.line([point(-.60,y+.3),point(.60,y+.3)],BLUE,1)
            for section_data in DATA['sectionStations']:
                y=section_data['y'];bounds=outline_intervals(y)
                left=min(a for a,b in bounds);right=max(b for a,b in bounds)
                sheet.line([point(left-.12,y+.3),point(right+.12,y+.3)],GREY,1,True)
                sheet.text(point(right+.16,y+.21),section_data['id'][0],16,INK)
            dimension(sheet,point(-SPAN/2,2.33),point(SPAN/2,2.33),'W / 翼幅 = 1.000')
        else:sheet.line([point(-.43,-.8+.3),point(.43,-.8+.3),point(.43,.8+.3),point(-.43,.8+.3)],AMBER,2,True,True)
    elif name in ('front','rear'):
        upper,lower=envelope(True)
        sheet.line([point(x,-z) for x,z in upper+lower[::-1]],BLUE,3,closed=True)
        for sign in (-1,1):
            for a in (f['centerX']-f['radius'],f['centerX']+f['radius']):
                sheet.line([point(sign*a,-f['deckZ']),point(sign*a,-f['lowerZ'])],AMBER,1,True)
            sheet.line([point(sign*(f['centerX']-f['radius']),-f['rotorZ']),point(sign*(f['centerX']+f['radius']),-f['rotorZ'])],AMBER,1,True)
        if name=='front':
            sheet.line([point(-DATA['noseVisorHalfWidth'],.045),point(0,.085),point(DATA['noseVisorHalfWidth'],.045)],BLUE,4)
            sheet.line([point(-.12,.22),point(.12,.22),point(.12,.37),point(-.12,.37)],AMBER,2,True,True)
        else:
            for x in (-.55,-.26,.26,.55):
                sheet.line([point(x-.075,.06),point(x+.075,.06),point(x+.075,.21),point(x-.075,.21)],AMBER,2,True,True)
    else:
        direction=1 if name=='left' else -1
        upper,lower=envelope(False)
        sheet.line([point(direction*(y+.4),-z) for y,z in upper+lower[::-1]],BLUE,3,closed=True)
        sheet.line([point(direction*(y+.4),-z) for y,z in DATA['crownProfile']],GREY,1,True)
        sheet.line([point(direction*(.52+.4),-.58),point(direction*(1.45+.4),-.58),point(direction*(1.14+.4),-.13)],AMBER,2,True)
        rect=[(f['centerY']-f['radius'],f['deckZ']),(f['centerY']+f['radius'],f['deckZ']),(f['centerY']+f['radius'],f['lowerZ']),(f['centerY']-f['radius'],f['lowerZ'])]
        sheet.line([point(direction*(y+.4),-z) for y,z in rect],AMBER,2,True,True)
        dimension(sheet,point(-2.25,.98),point(2.18,.98),f'L / W = {4.43/SPAN:.3f}')


VIEWS=[('top','上面','画像の主要配置を基にした寸法仮説',(430,395),76),
       ('front','正面','共通断面から生成した外形 / 内部は推定',(1210,395),76),
       ('left','左側面','前方 ← / 六面とも同じ縮尺',(1980,395),76),
       ('bottom','底面','未観測面 / 橙色の取付・脚区画は仮配置',(430,980),76),
       ('rear','背面','未観測面 / 排気・点検口は位置のみ仮定',(1210,980),76),
       ('right','右側面','前方 → / 左側面と同じ部品・断面',(1980,980),76)]


def model_overlay(sheet):
    mesh=json.loads((ROOT/'docs/design/reference-airframe-v11/hull-mesh.json').read_text())
    vertices=np.asarray(mesh['vertices'])
    mask=np.zeros((H,W),np.uint8)
    for name,_,_,(ox,oy),scale in VIEWS:
        if name in ('top','bottom'):xy=np.c_[vertices[:,0],vertices[:,1]+.3]
        elif name in ('front','rear'):xy=np.c_[vertices[:,0],-vertices[:,2]]
        else:xy=np.c_[vertices[:,1]+.4,-vertices[:,2]]*np.array([1 if name=='left' else -1,1])
        uv=np.round(xy*scale+[ox,oy]).astype(np.int32)
        for face in mesh['faces']:cv2.fillPoly(mask,[uv[face]],255)
    array=np.array(sheet.image);array[mask>0]=(211,218,221);sheet.image=Image.fromarray(array);sheet.draw=ImageDraw.Draw(sheet.image)
    raster=Image.new('RGBA',(W,H),(0,0,0,0));raster.paste((211,218,221,255),(0,0,W,H),Image.fromarray(mask))
    buffer=io.BytesIO();raster.save(buffer,format='PNG')
    ET.SubElement(sheet.svg,'image',x='0',y='0',width=str(W),height=str(H),
                  href='data:image/png;base64,'+base64.b64encode(buffer.getvalue()).decode('ascii'))


def section(sheet,origin,station,title):
    ox,oy=origin;scale=76;f=DATA['fan']
    sheet.text((ox-340,oy-120),title,22)
    sheet.text((ox-340,oy-86),f'Y = {station:+.2f} / 厚み・収納容積の確認用',16,AMBER)
    for a,b in section_intervals(station):
        xs=np.unique(np.r_[np.linspace(a,b,160),[x for x in (-1.18,-.64,.64,1.18,-2.85,2.85,-3.7,3.7) if a<x<b]])
        points=[(ox+x*scale,oy-skin_z(x,station)*scale) for x in xs]
        points += [(ox+x*scale,oy-skin_z(x,station,False)*scale) for x in xs[::-1]]
        sheet.line(points,BLUE,3,closed=True)
    if fan_gaps(station):
        for a,b in fan_gaps(station):
            sheet.line([(ox+(a+.04)*scale,oy-f['rotorZ']*scale),(ox+(b-.04)*scale,oy-f['rotorZ']*scale)],AMBER,2)
        sheet.text((ox-145,oy+46),'ダクト縁を翼面より下へ収納',17)
    else:
        sheet.line([(ox-.43*scale,oy-.06*scale),(ox+.43*scale,oy-.06*scale),(ox+.43*scale,oy+.17*scale),(ox-.43*scale,oy+.17*scale)],AMBER,2,True,True)
        sheet.text((ox-145,oy+46),'内部機器の配置はまだ未確定',17,AMBER)


def make_sheet(overlay=False):
    s=Sheet()
    if overlay:model_overlay(s)
    s.text((48,28),'MORROWGEAR / REFERENCE BLUEPRINT 01',34)
    s.text((48,78),'画像基準の六面設計図・断面図  |  寸法仮説 / 未承認 / 元画像の隠れた面は復元確定していません',23,AMBER)
    s.text((48,117),'青: 設計線  /  橙の破線: 未観測・仮配置  /  灰: '+('現行V11本体の投影（部品追加前の本体形状）' if overlay else '中心線')+'  /  左右は同じパラメータから生成',18)
    for name,title,sub,origin,scale in VIEWS:
        frame(s,title,sub,origin,scale);view(s,name,origin,scale)
    s.line([(45,1240),(2355,1240)],GREY,1)
    for station,x in zip(DATA['sectionStations'],(430,1210,1980)):
        section(s,(x,1420),station['y'],station['id']+' / '+station['title'])
    s.line([(45,1535),(2355,1535)],GREY,1)
    f=DATA['fan'];length=max(y for x,y in DATA['halfOutline'])-min(y for x,y in DATA['halfOutline'])
    rows=[('翼幅',1.0),('全長 / 翼幅',length/SPAN),('胴体肩幅 / 翼幅',DATA['fuselageShoulderHalfWidth']*2/SPAN),('タービン直径 / 翼幅',f['radius']*2/SPAN),('タービン中心間 / 翼幅',f['centerX']*2/SPAN),('全高の仮説 / 翼幅',(max(z for y,z in DATA['crownProfile'])-min(z for y,z in DATA['bellyProfile']))/SPAN)]
    s.text((48,1568),'比率の設計仮説（翼幅=1.000）',25)
    for i,(label,number) in enumerate(rows):
        s.text((48+(i%3)*750,1612+(i//3)*43),f'{label}: {number:.3f}',21)
    s.text((48,1720),'要確認: ノーズの絞り / 胴体と翼の量感 / 翼端の折れ角 / タービン前方の余裕 / 着陸脚と装備の格納余地',21)
    s.text((48,1765),'照合順序: 元画像の部位線 → 六面・断面の整合 → モデルとの重ね合わせ → 材質・細部 → 実ゲーム',21)
    s.text((48,1810),'注意: この図は製造図面でも、元画像に付属する真の正投影図でもありません。実重量・推力・空力は未評価です。',19,AMBER)
    s.text((48,1852),'底面の収納脚・取付区画、背面の開口は「仮配置」。決定済みディテールとしてモデルへ埋め込まないこと。',19,AMBER)
    s.text((48,1900),'平面に外装を開くUV展開・パネル展開は、立体形状の合意後に同じメッシュから作成します。',19)
    name='model-overlay' if overlay else 'six-view-blueprint';s.save(name)
    return rows


def source_evidence():
    source=Image.open(ROOT/DATA['source']).convert('RGB')
    canvas=Image.new('RGB',(1681,1200),BG);canvas.paste(source,(0,95));d=ImageDraw.Draw(canvas)
    font=lambda size:ImageFont.truetype(FONT,size)
    d.text((25,18),'元画像の観測図 / 部位の対応を先に固定する',font=font(28),fill=INK)
    d.text((25,58),'この斜視画像上の位置は観測値。六面図の奥行き・高さは別途推定。',font=font(19),fill=AMBER)
    points=[((399,295),'01 タービン軸'),((1034,451),'02 タービン軸'),((482,645),'03 バイザー折れ点'),((397,687),'04 顎センサー'),((882,248),'05 後部フェアリング間'),((211,63),'06 翼端'),((1577,402),'07 翼端')]
    for (x,y),label in points:
        py=y+95;d.ellipse((x-6,py-6,x+6,py+6),fill=(245,177,50),outline=INK,width=1)
        tx=max(8,min(1400,x+14));ty=py-31
        box=d.textbbox((tx,ty),label,font=font(17));d.rectangle((box[0]-4,box[1]-3,box[2]+4,box[3]+3),fill=BG);d.text((tx,ty),label,font=font(17),fill=INK)
    d.line((482,740,397,782),fill=(228,156,38),width=3)
    d.text((25,1050),'重要: 03の折れ点と04のセンサーを、どちらも機体中心と決めつけない。',font=font(22),fill=AMBER)
    d.text((25,1092),'06と07が同じ物理的な角かも要確認。対応点の誤りを、メッシュ変形で無理に吸収しない。',font=font(20),fill=INK)
    d.text((25,1135),'確認できない底面・後面・内部は、六面図で破線として明示する。',font=font(20),fill=INK)
    canvas.save(OUT/'reference-evidence.png')


def main():
    source_evidence();ratios=make_sheet();make_sheet(True)
    tests={'sourceExists':(ROOT/DATA['source']).exists(),'outlineSymmetry':True,
           'fanLipBelowDeck':DATA['fan']['lipZ']<DATA['fan']['deckZ'],
           'hiddenSurfacesFlagged':len(DATA['uncertainties'])>=4,'approvalNotAssumed':DATA['status']=='DESIGN_HYPOTHESIS_NOT_APPROVED',
           'hasSixViews':len(VIEWS)==6,'sameViewScale':len({v[4] for v in VIEWS})==1}
    outline=full_outline();tests['outlineSymmetry']=all(any(abs(x+u)<1e-9 and abs(y-z)<1e-9 for u,z in outline) for x,y in outline)
    xs,ys,inside,upper,lower=surface_grid()
    tests['positiveShellDepth']=bool(np.all(upper[inside]>lower[inside]))
    tests['surfaceSymmetry']=all(abs(skin_z(x,y,u)-skin_z(-x,y,u))<1e-9 for x in xs[::30] for y in ys[::30] for u in (True,False))
    tests['sectionsWithinOutline']=all(all(any(lo-1e-9<=a<b<=hi+1e-9 for lo,hi in outline_intervals(s['y'])) for a,b in section_intervals(s['y'])) for s in DATA['sectionStations'])
    tests['sectionsExcludeFanVoid']=all(all(b<=lo+1e-9 or a>=hi-1e-9 for lo,hi in fan_gaps(s['y'])) for s in DATA['sectionStations'] for a,b in section_intervals(s['y']))
    tests['fanCirclesInsideOutline']=all(any(lo<x<hi for lo,hi in outline_intervals(y)) for sign in (-1,1) for angle in np.linspace(0,math.tau,721) for x,y in [(sign*DATA['fan']['centerX']+DATA['fan']['radius']*math.cos(angle),DATA['fan']['centerY']+DATA['fan']['radius']*math.sin(angle))])
    tests['fanRotorBelowLip']=DATA['fan']['lowerZ']<DATA['fan']['rotorZ']<DATA['fan']['lipZ']
    tests['frontEnvelopeSymmetry']=all(abs(z-w)<1e-6 for points in envelope(True) for (_,z),(_,w) in zip(points,points[::-1]))
    tests['svgIncludesModelOverlay']=any(el.tag.endswith('image') and el.attrib.get('href','').startswith('data:image/png;base64,') for el in ET.parse(OUT/'model-overlay.svg').getroot())
    report={'checks':tests,'passed':all(tests.values()),'scope':'Drawing consistency only. Image fidelity and airworthiness are not certified.',
            'orthographicDimensionsAreHypotheses':True,'ratios':dict(ratios),'visualApproval':False,
            'modelOverlaySvgContainsRasterModel':True,
            'sourceSha256':hashlib.sha256((ROOT/DATA['source']).read_bytes()).hexdigest(),
            'constraintSha256':hashlib.sha256((OUT/'constraints.json').read_bytes()).hexdigest(),
            'candidateHullSha256':hashlib.sha256((ROOT/'docs/design/reference-airframe-v11/hull-mesh.json').read_bytes()).hexdigest(),
            'sectionIntervals':{s['id']:section_intervals(s['y']) for s in DATA['sectionStations']}}
    (OUT/'drawing-checks.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False,indent=2))
    if not report['passed']:raise SystemExit('Blueprint consistency checks failed')


if __name__=='__main__':main()
