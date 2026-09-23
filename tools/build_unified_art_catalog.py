"""Build code-native HUD glyphs and QA contact sheets from staged canonical models."""
import hashlib
import json
import math
import shutil
from pathlib import Path
from xml.etree.ElementTree import Element, SubElement, tostring

from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/design/unified-family-v8'
HUD=OUT/'hud';HUD.mkdir(parents=True,exist_ok=True)
MODELS=OUT/'models'
FONT=ImageFont.truetype('C:/Windows/Fonts/consola.ttf',15)
SMALL=ImageFont.truetype('C:/Windows/Fonts/consola.ttf',12)


def line(*points):return ('line',points)
def circle(x,y,r):return ('circle',(x,y,r))
def rect(x,y,w,h):return ('rect',(x,y,w,h))
WING=line((8,39),(32,15),(56,39),(40,36),(32,45),(24,36),(8,39))
GLYPHS={
 'field':[WING,line((28,30),(36,30))],
 'scout':[WING,circle(32,32,5),line((6,20),(11,16)),line((53,16),(58,20))],
 'cargo':[WING,rect(24,26,16,12),line((24,30),(40,30))],
 'engineer':[WING,line((24,34),(18,47),(24,54)),line((40,34),(46,47),(40,54))],
 'security':[WING,line((26,34),(26,48)),line((38,34),(38,48))],
 'salvage':[WING,line((32,35),(32,52),(25,55),(21,51))],
 'unit':[WING], 'wing':[line((8,29),(18,19),(28,29)),line((36,29),(46,19),(56,29)),line((22,47),(32,37),(42,47))],
 'all':[rect(10,10,16,16),rect(38,10,16,16),rect(10,38,16,16),rect(38,38,16,16)],
 'leader':[line((16,44),(12,22),(25,30),(32,16),(39,30),(52,22),(48,44),(16,44))],
 'dock':[line((10,46),(16,21),(48,21),(54,46),(10,46)),line((23,27),(41,40)),line((41,27),(23,40))],
 'solar_service':[circle(32,32,9),rect(5,24,13,16),rect(46,24,13,16),line((32,9),(32,16)),line((32,48),(32,55))],
 'charging_relay':[circle(32,22,9),line((24,37),(40,37),(28,54),(31,43),(24,37))],
 'follow':[circle(25,21,6),line((13,45),(16,33),(34,33),(37,45)),line((42,18),(53,28),(43,37))],
 'standby':[line((24,17),(24,47)),line((40,17),(40,47))],
 'return':[line((49,48),(49,24),(15,24)),line((24,14),(14,24),(24,34))],
 'orbit':[circle(32,32,18),line((40,11),(50,16),(52,6))],
 'waypoint':[line((10,46),(23,18),(47,43)),circle(10,46,4),circle(23,18,4),circle(47,43,4)],
 'track':[circle(32,32,11),line((32,7),(32,16)),line((32,48),(32,57)),line((7,32),(16,32)),line((48,32),(57,32))],
 'scan':[circle(32,32,21),circle(32,32,11),line((32,32),(48,15)),circle(43,34,3)],
 'excavate':[line((10,19),(54,19)),line((16,28),(24,45),(40,45),(48,28)),line((32,24),(32,37)),line((27,32),(32,37),(37,32))],
 'forestry':[line((32,8),(16,29),(24,29),(12,44),(52,44),(40,29),(48,29),(32,8)),line((32,44),(32,56))],
 'plant':[line((32,55),(32,30)),line((32,36),(14,28),(12,15),(27,19),(32,29),(40,17),(54,15),(49,30),(32,36))],
 'transport':[rect(10,27,17,18),rect(38,27,17,18),line((21,16),(46,16)),line((39,10),(46,16),(39,22))],
 'guard':[line((32,8),(51,17),(48,38),(32,55),(16,38),(13,17),(32,8))],
 'intercept':[line((10,50),(46,14)),line((28,14),(46,14),(46,32)),circle(46,14,7)],
 'gun':[line((12,23),(45,23),(45,36),(25,36),(20,47),(12,47),(16,34),(12,23)),line((45,27),(56,27))],
 'laser':[line((18,20),(46,20)),line(*[(32+14*math.cos(i*math.pi/16),20+12*math.sin(i*math.pi/16)) for i in range(17)]),line((32,32),(32,55))],
 'missile':[line((32,7),(39,23),(39,43),(25,43),(25,23),(32,7)),line((25,34),(17,47),(25,43)),line((39,34),(47,47),(39,43)),line((29,50),(29,57)),line((35,50),(35,57))],
 'unarmed':[circle(32,32,21),line((17,47),(47,17))],
 'auto':[line((18,25),(24,16),(44,16),(51,25)),line((43,25),(51,25),(51,17)),line((46,39),(40,48),(20,48),(13,39)),line((21,39),(13,39),(13,47))],
 'battery':[rect(10,20,40,25),line((54,28),(54,37)),line((19,27),(19,38)),line((27,27),(27,38)),line((35,27),(35,38))],
 'weapon_energy':[rect(10,20,40,25),line((33,12),(24,32),(37,32),(29,52))],
 'ammo':[line((14,46),(14,24),(19,14),(24,24),(24,46),(14,46)),line((36,46),(36,24),(41,14),(46,24),(46,46),(36,46))],
 'heat':[line((18,51),(15,39),(22,26),(18,13)),line((32,51),(29,39),(36,26),(32,13)),line((46,51),(43,39),(50,26),(46,13))],
 'health':[line((25,10),(39,10),(39,25),(54,25),(54,39),(39,39),(39,54),(25,54),(25,39),(10,39),(10,25),(25,25),(25,10))],
 'link':[circle(17,32,7),circle(47,32,7),line((24,32),(40,32))],
 'link_lost':[circle(15,32,6),circle(49,32,6),line((26,24),(38,40)),line((38,24),(26,40))],
 'power_lost':[line((32,8),(32,31)),line((20,17),(12,28),(14,44),(26,54),(41,51),(51,38),(49,25),(43,17)),line((11,9),(53,55))],
 'recovery':[line((14,32),(14,19),(26,10),(42,13),(51,26),(49,42),(38,51)),line((6,25),(14,33),(22,25)),line((32,28),(32,49),(26,54),(21,49))],
 'warning':[line((32,8),(57,53),(7,53),(32,8)),line((32,24),(32,36)),circle(32,44,1)],
 'critical':[line((22,8),(42,8),(56,22),(56,42),(42,56),(22,56),(8,42),(8,22),(22,8)),line((32,19),(32,35)),circle(32,45,1)],
 'hostile':[line((32,8),(56,32),(32,56),(8,32),(32,8)),line((24,24),(40,40)),line((40,24),(24,40))],
 'friendly':[line((32,8),(54,48),(10,48),(32,8)),circle(32,33,4)],
 'operation':[circle(17,18,5),circle(47,18,5),circle(32,48,5),line((22,18),(42,18)),line((20,23),(29,43)),line((44,23),(35,43))],
 'charge':[line((36,7),(16,36),(30,36),(25,57),(49,25),(35,25),(36,7))],
 'rearm':[rect(12,34,40,18),line((32,9),(32,29)),line((23,21),(32,30),(41,21))],
 'store':[rect(10,21,44,32),line((10,30),(54,30)),line((25,40),(39,40))],
 'chest':[rect(10,17,44,35),line((10,31),(54,31)),rect(28,27,8,10)],
 'input':[line((10,32),(41,32)),line((32,23),(41,32),(32,41)),line((43,12),(54,12),(54,52),(43,52))],
 'output':[line((22,32),(54,32)),line((45,23),(54,32),(45,41)),line((21,12),(10,12),(10,52),(21,52))],
 'selected':[rect(9,9,46,46),line((18,32),(28,42),(46,22))],
 'select_all':[rect(16,16,39,39),line((8,42),(8,8),(42,8)),line((24,34),(32,43),(47,26))],
 'pin':[line((22,9),(42,9),(39,29),(49,39),(15,39),(25,29),(22,9)),line((32,39),(32,57))],
 'lock':[rect(15,28,34,27),line((22,28),(22,17),(28,10),(36,10),(42,17),(42,28))],
 'zoom_in':[circle(27,27,17),line((39,39),(55,55)),line((18,27),(36,27)),line((27,18),(27,36))],
 'zoom_out':[circle(27,27,17),line((39,39),(55,55)),line((18,27),(36,27))],
 'center':[circle(32,32,13),line((8,21),(8,8),(21,8)),line((43,8),(56,8),(56,21)),line((56,43),(56,56),(43,56)),line((21,56),(8,56),(8,43))],
 'layers':[line((8,24),(32,10),(56,24),(32,38),(8,24)),line((10,35),(32,48),(54,35)),line((10,45),(32,58),(54,45))],
 'map':[line((8,16),(24,10),(40,16),(56,10),(56,48),(40,54),(24,48),(8,54),(8,16)),line((24,10),(24,48)),line((40,16),(40,54))],
 'filter':[line((9,12),(55,12),(38,32),(38,49),(26,55),(26,32),(9,12))],
 'sort':[line((16,12),(16,52)),line((9,44),(16,52),(23,44)),line((31,15),(54,15)),line((31,31),(48,31)),line((31,47),(41,47))],
 'previous':[line((40,12),(20,32),(40,52))], 'next':[line((24,12),(44,32),(24,52))],
 'up':[line((12,40),(32,20),(52,40))], 'down':[line((12,24),(32,44),(52,24))],
 'add':[line((32,12),(32,52)),line((12,32),(52,32))],
 'remove':[line((12,32),(52,32))], 'close':[line((14,14),(50,50)),line((50,14),(14,50))],
 'confirm':[line((10,32),(26,48),(54,16))],
 'pause':[line((23,12),(23,52)),line((41,12),(41,52))],
 'play':[line((20,10),(53,32),(20,54),(20,10))],
 'settings':[circle(32,32,10)]+[line((32+19*math.cos(a),32+19*math.sin(a)),(32+26*math.cos(a),32+26*math.sin(a))) for a in [i*math.pi/4 for i in range(8)]],
 'join':[line((10,16),(28,16),(38,32),(54,32)),line((10,48),(28,48),(38,32)),line((46,24),(54,32),(46,40))],
 'leave':[line((10,32),(28,32),(40,16),(54,16)),line((28,32),(40,48),(54,48)),line((48,10),(54,16),(48,22)),line((48,42),(54,48),(48,54))],
 'help':[circle(32,32,23),line((24,22),(28,17),(36,17),(41,23),(39,29),(32,33),(32,37)),circle(32,46,1)],
}


def render_glyph(name, shapes):
    root=Element('svg',xmlns='http://www.w3.org/2000/svg',viewBox='0 0 64 64',fill='none',stroke='#ffffff',attrib={'stroke-width':'3','stroke-linecap':'round','stroke-linejoin':'round'})
    master=Image.new('RGBA',(256,256));draw=ImageDraw.Draw(master)
    for typ,data in shapes:
        if typ=='line':
            SubElement(root,'polyline',points=' '.join('%g,%g'%p for p in data))
            draw.line([(x*4,y*4) for x,y in data],fill='white',width=12,joint='curve')
        elif typ=='circle':
            x,y,r=data;SubElement(root,'circle',cx=str(x),cy=str(y),r=str(r))
            draw.ellipse(((x-r)*4,(y-r)*4,(x+r)*4,(y+r)*4),outline='white',width=12)
        else:
            x,y,w,h=data;SubElement(root,'rect',x=str(x),y=str(y),width=str(w),height=str(h))
            draw.rectangle((x*4,y*4,(x+w)*4,(y+h)*4),outline='white',width=12)
    (HUD/(name+'.svg')).write_bytes(tostring(root,encoding='utf-8'))
    for size in (16,24,32,64):
        dest=HUD/str(size);dest.mkdir(exist_ok=True)
        master.resize((size,size),Image.Resampling.LANCZOS).save(dest/(name+'.png'))


def sheet(names, path, image_for, cols=6, cell=(230,260)):
    rows=math.ceil(len(names)/cols)
    canvas=Image.new('RGB',(cols*cell[0],rows*cell[1]),'#20282e');draw=ImageDraw.Draw(canvas)
    for i,name in enumerate(names):
        x=(i%cols)*cell[0];y=(i//cols)*cell[1]
        img=Image.open(image_for(name)).convert('RGBA')
        img.thumbnail((cell[0]-16,cell[1]-44),Image.Resampling.LANCZOS)
        canvas.paste(img,(x+(cell[0]-img.width)//2,y+8),img)
        draw.text((x+9,y+cell[1]-31),name,fill='#e5edf1',font=SMALL if len(name)>22 else FONT)
    canvas.save(path)


def main():
    for name,shapes in GLYPHS.items():render_glyph(name,shapes)
    manifest=json.loads((OUT/'manifest.json').read_text())
    roles=['field','scout','cargo','engineer','security','salvage']
    sheet(roles,OUT/'drone-models.png',lambda n:MODELS/n/'hero.png',3,(460,430))
    sheet(roles,OUT/'drone-deployed.png',lambda n:MODELS/n/'deployed.png',3,(460,430))
    equipment=['dock_item','solar_service_station','charging_relay','controller','tactical_visor','recovery_tool']
    sheet(equipment,OUT/'equipment-models.png',lambda n:MODELS/n/'hero.png',3,(460,430))
    items=OUT/'items';items.mkdir(exist_ok=True)
    for item in manifest['itemIds']:
        model=manifest['aliases'].get(item,item)
        shutil.copyfile(MODELS/model/'icon.png',items/(item+'.png'))
    sheet(manifest['itemIds'],OUT/'item-catalog.png',lambda n:items/(n+'.png'),6,(250,200))
    sheet(list(GLYPHS),OUT/'hud-catalog.png',lambda n:HUD/'64'/(n+'.png'),8,(170,112))
    # Review native small sizes without enlarging or hiding rasterization defects.
    sample=Image.new('RGB',(1120,480),'#152027');d=ImageDraw.Draw(sample)
    for row,size in enumerate((16,24,32,64)):
        d.text((12,18+row*115),str(size)+'px',fill='white',font=FONT)
        for col,name in enumerate(('scout','engineer','salvage','battery','laser','power_lost','warning','wing','return','waypoint')):
            im=Image.open(HUD/str(size)/(name+'.png'));sample.paste(im,(100+col*100,10+row*115),im)
    sample.save(OUT/'hud-native-sizes.png')
    atlas=Image.new('RGBA',(720,math.ceil(len(GLYPHS)/10)*72))
    positions={}
    for i,name in enumerate(GLYPHS):
        x=i%10*72+4;y=i//10*72+4
        atlas.paste(Image.open(HUD/'64'/(name+'.png')),(x,y))
        positions[name]={'x':x,'y':y,'width':64,'height':64}
    atlas.save(HUD/'atlas.png')
    (HUD/'atlas.json').write_text(json.dumps({'width':atlas.width,'height':atlas.height,'padding':4,'sprites':positions},indent=2),encoding='utf-8')
    mapping={'DroneRole':dict(zip([r.upper() for r in roles],roles)),
      'DroneMode':{'STANDBY':'standby','FOLLOW':'follow','RETURN':'return','ORBIT':'orbit','DOCK':'dock','WAYPOINT':'waypoint'},
      'CombatState':{'IDLE':'standby','FLARE_ENTRY':'intercept','GUN_RUN':'gun','LASER_CHARGE':'charge','LASER_FIRE':'laser','MISSILE_APPROACH':'missile','MISSILE_EGRESS':'return','RAM_APPROACH':'intercept','REJOIN':'join'}}
    (HUD/'semantic-map.json').write_text(json.dumps(mapping,indent=2),encoding='utf-8')
    checks=[]
    for path in MODELS.glob('*/validation.json'):checks.append(json.loads(path.read_text()))
    issues=[]
    for check in checks:
        if not check['symmetryPass']:issues.append(check['asset']+': symmetry')
        if check.get('groundClearancePass') is False:issues.append(check['asset']+': ground clearance')
    for item in manifest['itemIds']:
        im=Image.open(items/(item+'.png'))
        if not im.getbbox():issues.append(item+': blank')
    hashes={p.stem:hashlib.sha256(p.read_bytes()).hexdigest() for p in items.glob('*.png')}
    report={'modelCount':len(checks),'itemCount':len(manifest['itemIds']),'hudGlyphCount':len(GLYPHS),'issues':issues,'structuralChecksPass':not issues,'modelChecks':checks,'itemHashes':hashes,'gameVerified':False,'referenceFidelityApproved':False}
    (OUT/'catalog-validation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in ('modelChecks','itemHashes')},indent=2))


if __name__=='__main__':main()
