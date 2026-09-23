"""Build the complete item/HMI design catalog from canonical render outputs."""
import hashlib
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/design/equipment-hmi-v23'
V22=ROOT/'docs/design/detail-scale-v22'
FONT='C:/Windows/Fonts/meiryo.ttc'
RECOGNITION={
    'controller':'埋込ディスプレイ・対称スティック・握り一体の薄型端末',
    'tactical_visor':'連続する曲面レンズと左右の薄型演算部',
    'recovery_tool':'中央グリップと左右対称の可動回収クランプ',
    'field_drone_unit':'承認済み3×3共通機。実機メッシュと同じ機影',
    'dock_item':'5×5の低背着艦面。支柱なし・四方進入',
    'solar_service_station':'3つの子機ベイ、環状太陽電池、下面電力バス',
    'scout_module':'中央光学レンズと埋込側面走査窓',
    'cargo_module':'横桟シャッターと左右の固定ラッチ',
    'engineer_module':'工具ソケットと対称放熱フィン',
    'security_module':'装甲化した制御カセットとV字識別帯',
    'salvage_module':'巻き線ドラムと対称回収クランプ',
    'power_cell':'円筒コアと金属保護環。電池パックと区別',
    'standard_battery_pack':'1層の薄型パックと共通接点',
    'reinforced_battery_pack':'2層パック。容量差は形状で識別',
    'high_density_battery_pack':'3層パック。加速性能のペナルティは追加しない',
    'raw_morrow_composite':'焼結前の銅・モロウ材の積層',
    'morrow_alloy':'明るい鋳造面を持つ完成合金ビレット',
    'lightweight_frame':'開放骨格と対角補強。箱形モジュールと区別',
    'basic_control_board':'緑の基板、中央チップ、対称接点列',
    'flight_actuator':'露出巻き線と中空回転軸',
    'autocannon_module':'互換アイテム: 双砲身と冷却カラー',
    'laser_module':'互換アイテム: 中央集光レンズ',
    'missile_module':'互換アイテム: 対になった密閉弾倉',
}


def text(im,xy,value,size=18,fill='#ebf2f1'):
    ImageDraw.Draw(im).text(xy,value,font=ImageFont.truetype(FONT,size),fill=fill)


def place(im,path,box):
    obj=Image.open(path).convert('RGBA')
    bounds=obj.getchannel('A').getbbox()
    if bounds:obj=obj.crop(bounds)
    obj.thumbnail((box[2],box[3]),Image.Resampling.LANCZOS)
    im.paste(obj,(int(box[0]+(box[2]-obj.width)/2),int(box[1]+(box[3]-obj.height)/2)),obj)


def compile_items():
    lang=json.loads((ROOT/'src/main/resources/assets/morrowgear_drone/lang/ja_jp.json').read_text(encoding='utf-8'))
    expected={p.stem for p in (ROOT/'src/main/resources/assets/morrowgear_drone/items').glob('*.json')}
    if expected!=set(RECOGNITION):raise ValueError(('Registered item mismatch',expected^set(RECOGNITION)))
    (OUT/'items').mkdir(exist_ok=True)
    items=[]
    for key,recognition in RECOGNITION.items():
        alias={'field_drone_unit':('field','airframe'),'dock_item':('dock','dock')}.get(key)
        folder=V22/alias[0] if alias else OUT/'models'/key
        basename=alias[1] if alias else key
        master=Image.open(folder/'icon-master.png').convert('RGBA')
        bb=master.getchannel('A').getbbox()
        if not bb:raise ValueError(('Blank',key))
        cropped=master.crop(bb)
        for size in (24,32,48,64,128,256):
            icon=Image.new('RGBA',(size,size));res=cropped.copy();res.thumbnail((int(size*.88),int(size*.88)),Image.Resampling.LANCZOS)
            icon.paste(res,((size-res.width)//2,(size-res.height)//2),res)
            icon.save(OUT/'items'/f'{key}-{size}.png')
        category='legacy' if key in ('autocannon_module','laser_module','missile_module') else 'module' if key.endswith('_module') else 'power' if key.endswith('battery_pack') or key=='power_cell' else 'material' if key in ('raw_morrow_composite','morrow_alloy','lightweight_frame','basic_control_board','flight_actuator') else 'equipment'
        base='../../detail-scale-v22/'+alias[0] if alias else '../models/'+key
        views='../views/dock_item' if key=='dock_item' else base
        items.append({'id':key,'name':lang['item.morrowgear_drone.'+key].replace('Morrowgear ',''),'category':category,
                      'recognition':recognition,'viewBase':views,'blend':f'{base}/{basename}.blend','glb':f'{base}/{basename}.glb',
                      'iconMasterSha256':hashlib.sha256((folder/'icon-master.png').read_bytes()).hexdigest()})
    (OUT/'hmi/catalog.js').write_text('window.MorrowCatalog = '+json.dumps(items,ensure_ascii=False,indent=2)+';\n',encoding='utf-8')
    (OUT/'item-manifest.json').write_text(json.dumps({'registeredCount':len(items),'status':'design / no game installation','items':items},ensure_ascii=False,indent=2),encoding='utf-8')
    board=Image.new('RGB',(1600,1530),'#191f22')
    text(board,(30,18),'MORROWGEAR / 全23アイテム',30)
    text(board,(30,64),'実メッシュの正本から作成。色だけでなく機能部・層数・外形で識別。',19,'#a5b7ba')
    for i,it in enumerate(items):
        x=20+i%5*316;y=110+i//5*278
        ImageDraw.Draw(board).line((x,y+265,x+292,y+265),fill='#3b494e')
        place(board,OUT/'items'/f'{it["id"]}-256.png',(x+24,y,245,175))
        text(board,(x+10,y+180),it['name'],17)
        text(board,(x+10,y+212),it['id'],11,'#b6c7ca')
        if it['category']=='legacy':text(board,(x+10,y+236),'既存ID互換用',13,'#e8b46d')
    board.save(OUT/'all-items.png')
    native=Image.new('RGB',(1550,1130),'#20292c')
    text(native,(25,20),'INVENTORY / 24・32・64 px 実寸',26)
    for i,it in enumerate(items):
        x=25+(i%3)*510;y=80+(i//3)*127
        text(native,(x,y),it['name'],16)
        for j,size in enumerate((24,32,64)):
            path=OUT/'items'/f'{it["id"]}-{size}.png';im=Image.open(path)
            native.paste(im,(x+j*125,y+34),im);text(native,(x+j*125+70,y+50),str(size),12,'#9eb6bb')
    native.save(OUT/'native-icons.png')
    return items


def model_sheets():
    for folder in (OUT/'models').iterdir():
        if not folder.is_dir():continue
        board=Image.new('RGB',(1600,1140),'#293236')
        views=('hero','top','bottom','front','rear','left','right')
        for i,view in enumerate(views):
            x=(i%3)*530;y=(i//3)*375
            text(board,(x+12,y+10),folder.name.upper()+' / '+view.upper(),14)
            place(board,folder/(view+'.png'),(x+16,y+50,495,292))
        report=json.loads((folder/'validation.json').read_text())
        text(board,(1080,815),'X=0 / 左右対称',22)
        text(board,(1080,855),f"最大誤差 {max(c['mirrorError'] for c in report['checks']):.8f}",16)
        text(board,(1080,890),'同一正本から7方向を描画',17)
        text(board,(1080,929),'BLEND + GLB / 未反映',15,'#a8bec4')
        board.save(folder/'all-views.png')
    board=Image.new('RGB',(1600,1260),'#293236')
    text(board,(30,18),'EQUIPMENT / 共通機体からの展開',30)
    for i,key in enumerate(('controller','tactical_visor','recovery_tool','solar_service_station','charging_relay','service_light')):
        x=20+(i%2)*790;y=80+(i//2)*390
        text(board,(x+15,y+6),key.upper(),22)
        place(board,OUT/'models'/key/'hero.png',(x+15,y+48,750,315))
    board.save(OUT/'equipment.png')


def terrain():
    size=768
    yy,xx=np.mgrid[0:size,0:size]
    coarse_x=xx//5;coarse_y=yy//5
    noise=(np.sin(coarse_x*.23+np.cos(coarse_y*.19)*2.5)+np.cos(coarse_y*.17)+np.sin((coarse_x+coarse_y)*.06))
    detail=(np.sin(coarse_x*8.123+coarse_y*4.784)*4312.55)%1
    a=np.zeros((size,size,3),dtype=np.uint8)
    for channel,base in enumerate((69,88,61)):a[:,:,channel]=np.clip(base+noise*11+detail*14,0,255)
    river=np.abs(xx-(size*.37+np.sin(yy/105)*64+np.cos(yy/50)*15))<22+np.sin(yy/170)*8
    a[river]=(62,102,120)
    mountains=(noise>1.85)&(xx>size*.54)
    for ch,base in enumerate((130,136,129)):a[:,:,ch][mountains]=np.clip(base+detail[mountains]*24,0,255)
    peak=(noise>2.65)&(xx>size*.54);a[peak]=(180,185,177)
    im=Image.fromarray(a);draw=ImageDraw.Draw(im)
    draw.line([(12,590),(250,590),(420,375),(755,375)],fill='#b3ad96',width=9)
    for x in range(6):
        for y in range(4):
            draw.rectangle((45+x*20,385+y*22,59+x*20,401+y*22),fill='#c2c6ba',outline='#555d57')
    im.save(OUT/'hmi/terrain.png')


def controller_screen():
    im=Image.new('RGB',(1040,640),'#10181c');draw=ImageDraw.Draw(im)
    text(im,(26,17),'MORROWGEAR  C2',29);text(im,(750,24),'24 ONLINE',19,'#75ddd8')
    draw.line((20,64,1020,64),fill='#45616a',width=2)
    for i,name in enumerate(('ALPHA','BRAVO','CHARLIE')):
        y=105+i*102
        draw.rectangle((24,y,247,y+82),fill='#1a343b' if i==0 else '#18262b',outline='#42626b')
        text(im,(37,y+12),name,22);text(im,(37,y+47),'8 UNITS / LINKED',14,'#9ab8bd')
    for x in range(282,781,43):draw.line((x,92,x,570),fill='#273b40')
    for y in range(92,572,43):draw.line((282,y,781,y),fill='#273b40')
    route=[(320,432),(506,174),(728,377),(320,432)]
    draw.line(route,fill='#68d5d7',width=4)
    for i,(x,y) in enumerate(route[:-1]):
        draw.ellipse((x-15,y-15,x+15,y+15),fill='#1b3238',outline='#a5edf0',width=2);text(im,(x-7,y-12),str(i+1),17)
    for i,(name,value,color) in enumerate((('FLT',82,'#7de0dc'),('WPN',71,'#e3b774'),('SYS',98,'#8eddbc'))):
        y=192+i*89;text(im,(807,y),name,21);text(im,(940,y),str(value)+'%',20)
        draw.rectangle((807,y+34,1001,y+40),fill='#34474c');draw.rectangle((807,y+34,807+1.94*value,y+40),fill=color)
    text(im,(28,584),'M1 / NORTH PATROL',17,'#97b9bd');text(im,(750,584),'LINK SECURE',17,'#77dccc')
    im.save(OUT/'hmi/controller-screen.png')


def main():
    OUT.mkdir(exist_ok=True);(OUT/'hmi').mkdir(exist_ok=True)
    items=compile_items();model_sheets();terrain();controller_screen()
    validations=[json.loads(p.read_text()) for p in (OUT/'models').glob('*/validation.json')]
    results={'registeredItems':len(items),'itemPngs':len(list((OUT/'items').glob('*.png'))),
             'newCanonicalModels':len(validations),'geometryPassed':all(r['passed'] for r in validations),
             'sevenViewSheets':len(list((OUT/'models').glob('*/all-views.png'))),'gameVerified':False}
    (OUT/'asset-checks.json').write_text(json.dumps(results,indent=2))
    print(json.dumps(results))


if __name__=='__main__':main()
