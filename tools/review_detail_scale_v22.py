"""Compile same-mesh portraits, HUD glyphs and readable review sheets, without touching game assets."""
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/design/detail-scale-v22'
ROLES = ('field', 'scout', 'cargo', 'engineer', 'security', 'salvage')
LABELS = ('FIELD / 共通機体', 'SCOUT / 機首内蔵光学系', 'CARGO / 胴体一体型貨物庫',
          'ENGINEER / 格納式作業アーム', 'SECURITY / 内蔵兵装', 'SALVAGE / 内部ウィンチ')
FONT = 'C:/Windows/Fonts/meiryo.ttc'
BG = '#171d21'
WHITE = '#eaf2f5'
MUTED = '#9baeb7'
CYAN = '#56d7e3'


def font(size):
    return ImageFont.truetype(FONT, size)


def text(image, position, value, size=22, color=WHITE):
    ImageDraw.Draw(image).text(position, value, font=font(size), fill=color)


def place(image, path, box):
    item = Image.open(path).convert('RGBA')
    if item.width > 512:
        bb = item.getchannel('A').getbbox()
        if bb:
            padding = 12
            item = item.crop((max(0,bb[0]-padding),max(0,bb[1]-padding),
                              min(item.width,bb[2]+padding),min(item.height,bb[3]+padding)))
    item.thumbnail((box[2], box[3]), Image.Resampling.LANCZOS)
    x = box[0] + (box[2]-item.width)//2
    y = box[1] + (box[3]-item.height)//2
    image.paste(item, (x, y), item)


def family():
    image = Image.new('RGB', (1680, 1470), BG)
    text(image, (30, 18), 'MORROWGEAR / V22', 28)
    text(image, (30, 60), '承認済みの一体型シルエットを継承 / 点検口・固定具・ファン整備リムを追加', 20, MUTED)
    for i, (role, label) in enumerate(zip(ROLES, LABELS)):
        x, y = 25+(i%2)*830, 117+(i//2)*447
        text(image, (x+5, y), label, 23)
        report = json.loads((OUT/role/'validation.json').read_text())
        bb = report['frameSamples'][0]['bounds']
        text(image, (x+5, y+36), f'{bb[3]-bb[0]:.2f} × {bb[4]-bb[1]:.2f} blocks / 着陸時高さ {bb[5]-bb[2]:.2f}', 18, MUTED)
        place(image, OUT/role/'hero.png', (x, y+52, 810, 364))
    image.save(OUT/'family-detail.png')
    close = Image.new('RGB', (1680, 1050), BG)
    text(close, (30, 18), 'DETAIL / 実メッシュからの拡大', 28)
    for i, role in enumerate(('field', 'security', 'engineer', 'salvage')):
        x, y = 20+(i%2)*830, 85+(i//2)*475
        text(close, (x+10, y), role.upper(), 22, CYAN)
        place(close, OUT/role/('detail.png' if i<2 else 'active.png'), (x, y+30, 810, 421))
    close.save(OUT/'detail-closeups.png')
    for role in ROLES:
        sheet = Image.new('RGB', (1500, 1050), BG)
        for i, view in enumerate(('hero', 'top', 'bottom', 'front', 'rear', 'active', 'left', 'right', 'landed')):
            x, y = (i%3)*500, (i//3)*350
            text(sheet, (x+12, y+6), role.upper()+' / '+view.upper(), 17)
            place(sheet, OUT/role/(view+'.png'), (x, y+30, 500, 305))
        sheet.save(OUT/role/'all-views.png')


def glyph_commands(role):
    # Symmetric, 24-unit mechanical signs supplement the actual aircraft silhouette.
    signs = {
        'field': [('line', [(4,9),(4,4),(9,4)]), ('line', [(15,4),(20,4),(20,9)]),
                  ('line', [(4,15),(4,20),(9,20)]), ('line', [(15,20),(20,20),(20,15)]),
                  ('line', [(8,12),(16,12)]), ('line', [(12,8),(12,16)])],
        'scout': [('line', [(2,12),(6,7),(18,7),(22,12),(18,17),(6,17),(2,12)]), ('ellipse', (9,9,15,15))],
        'cargo': [('line', [(4,4),(20,4),(20,20),(4,20),(4,4)]), ('line', [(4,12),(20,12)]),
                  ('line', [(10,8),(14,8)]), ('line', [(10,16),(14,16)])],
        'engineer': [('line', [(8,4),(4,8),(4,14),(8,18)]), ('line', [(16,4),(20,8),(20,14),(16,18)]),
                     ('line', [(9,10),(12,7),(15,10),(12,13),(9,10)]), ('line', [(12,13),(12,21)])],
        'security': [('line', [(4,4),(20,4),(20,12),(18,17),(12,21),(6,17),(4,12),(4,4)]),
                     ('line', [(8,10),(8,13)]), ('line', [(12,8),(12,15)]), ('line', [(16,10),(16,13)])],
        'salvage': [('line', [(5,3),(19,3),(19,8),(5,8),(5,3)]), ('line', [(12,8),(12,13)]),
                    ('line', [(7,20),(4,17),(7,13),(17,13),(20,17),(17,20)]),
                    ('line', [(9,17),(9,21),(15,21),(15,17)])],
        'dock': [('line', [(5,3),(19,3),(22,6),(22,18),(19,21),(5,21),(2,18),(2,6),(5,3)]),
                  ('line', [(8,8),(16,16)]), ('line', [(8,16),(16,8)])],
    }
    return signs[role]


def glyph(role, size):
    scale = size*4/24
    result = Image.new('RGBA', (size*4, size*4))
    draw = ImageDraw.Draw(result)
    for kind, coords in glyph_commands(role):
        if kind == 'ellipse':
            draw.ellipse(tuple(c*scale for c in coords), outline='white', width=round(2*scale))
        else:
            draw.line([(x*scale, y*scale) for x, y in coords], fill='white', width=round(2*scale), joint='curve')
    return result.resize((size, size), Image.Resampling.LANCZOS)


def svg(role):
    pieces = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2" stroke-linejoin="round">']
    for kind, coords in glyph_commands(role):
        if kind == 'ellipse':
            x0,y0,x1,y1 = coords
            pieces.append(f'<ellipse cx="{(x0+x1)/2}" cy="{(y0+y1)/2}" rx="{(x1-x0)/2}" ry="{(y1-y0)/2}"/>')
        else:
            pieces.append('<polyline points="'+' '.join(f'{x},{y}' for x,y in coords)+'"/>')
    return '\n'.join(pieces+['</svg>'])


def icons():
    folder = OUT/'icons'
    folder.mkdir(exist_ok=True)
    manifest = {'stage': 'not installed', 'portraits': 'Rendered from V22 canonical meshes',
                'mapMarkers': 'Orthographic mesh alpha, nose up',
                'roleGlyphs': 'Abstract mechanical signs, not a claim of mesh silhouettes', 'assets': []}
    for role in (*ROLES, 'dock'):
        master = Image.open(OUT/role/'icon-master.png').convert('RGBA')
        map_master = Image.open(OUT/role/('silhouette-master.png' if role!='dock' else 'top.png')).convert('RGBA')
        if role!='dock':
            map_master = map_master.rotate(180)
        alpha = map_master.getchannel('A')
        silhouette = Image.new('RGBA', map_master.size, 'white')
        silhouette.putalpha(alpha)
        (folder/(role+'-role.svg')).write_text(svg(role))
        for size in (24,32,48,64,128):
            portrait = master.resize((size,size), Image.Resampling.LANCZOS)
            portrait.save(folder/f'{role}-portrait-{size}.png')
            silhouette.resize((size,size), Image.Resampling.LANCZOS).save(folder/f'{role}-map-{size}.png')
            glyph(role,size).save(folder/f'{role}-role-{size}.png')
        manifest['assets'].append({'role': role, 'masterSha256': hashlib.sha256((OUT/role/'icon-master.png').read_bytes()).hexdigest(),
                                  'sizes': [24,32,48,64,128], 'masterSize': list(master.size)})
    (folder/'manifest.json').write_text(json.dumps(manifest,indent=2))
    board = Image.new('RGB', (1680, 1100), BG)
    text(board, (32,20), 'HUD / 機体アイコンと役割記号', 28)
    text(board, (32,65), '立体アイコン：機体詳細・リスト  /  白い機影：地図・方位  /  役割記号：近接HUD', 20, MUTED)
    for x, label in ((228,'3D'),(412,'機影'),(546,'役割'),(740,'実寸表示'),(1310,'明るい背景')):
        text(board,(x,108),label,17,MUTED)
    for i, role in enumerate((*ROLES, 'dock')):
        y = 135+i*128
        text(board, (36,y+36), role.upper(), 21)
        place(board, folder/f'{role}-portrait-128.png', (228,y,140,120))
        place(board, folder/f'{role}-map-64.png', (412,y+25,70,70))
        place(board, folder/f'{role}-role-64.png', (546,y+25,70,70))
        for j,size in enumerate((24,32,48)):
            x = 740+j*180
            place(board, folder/f'{role}-role-{size}.png', (x,y+20,60,60))
            text(board, (x,y+80), str(size)+' px', 15, MUTED)
            # Deliberately native-pixel samples, no preview upscaling.
        draw = ImageDraw.Draw(board)
        draw.rectangle((1310,y+18,1605,y+91), fill='#dce6eb')
        icon = Image.open(folder/f'{role}-role-32.png').convert('RGBA')
        dark = Image.new('RGBA',icon.size,'#24363e')
        dark.putalpha(icon.getchannel('A'))
        board.paste(dark,(1330,y+36),dark)
        text(board,(1380,y+39),role.upper(),18,'#24363e')
    board.save(OUT/'hud-icons.png')
    return manifest


def scale_board():
    canvas = Image.new('RGB', (1680,1160), BG)
    text(canvas,(30,20),'DOCK / 5 × 5 blocks',30)
    text(canvas,(30,65),'機体の水平最大外形 3 × 3以内 / 推奨設置間隔 7 blocks / 柱なし・上方／側方進入',20,MUTED)
    place(canvas,OUT/'dock/cargo-landed.png',(0,100,1000,610))
    place(canvas,OUT/'dock/hero.png',(980,110,670,400))
    # Top render has exactly seven blocks across, so this grid is a physical ruler.
    image = Image.open(OUT/'dock/scale-top.png').convert('RGBA').resize((580,580),Image.Resampling.LANCZOS)
    grid = Image.new('RGBA',(580,580))
    d=ImageDraw.Draw(grid)
    for i in range(8):
        p=round(i*580/7)
        d.line((p,0,p,580),fill=(126,162,175,95),width=1)
        d.line((0,p,580,p),fill=(126,162,175,95),width=1)
    inset=580/7
    d.rectangle((inset,inset,580-inset,580-inset),outline=(255,190,83,255),width=2)
    d.rectangle((2*inset,2*inset,580-2*inset,580-2*inset),outline=(86,215,227,255),width=2)
    image=Image.alpha_composite(image,grid)
    canvas.paste(image,(1060,545),image)
    place(canvas,OUT/'dock/scale-side.png',(20,685,1010,365))
    text(canvas,(40,1090),'CYAN 3×3機体範囲   /   AMBER 5×5Dock   /   GRID 7×7配置ピッチ',18,MUTED)
    canvas.save(OUT/'dock-scale.png')


def checks(manifest):
    failures=[]
    for role in ROLES:
        report=json.loads((OUT/role/'validation.json').read_text())
        if not report['passed']:
            failures.append((role,'geometry'))
        source=ROOT/'docs/design/integrated-family-v21'/role/'airframe.blend'
        if hashlib.sha256(source.read_bytes()).hexdigest()!=report['sourceSha256']:
            failures.append((role,'source changed after generation'))
        for name in ('hero','active','landed','front','rear','top','bottom','left','right','detail','icon-master','silhouette-master'):
            item=Image.open(OUT/role/(name+'.png')).convert('RGBA')
            bb=item.getchannel('A').getbbox()
            if bb is None or bb[0]<2 or bb[1]<2 or bb[2]>item.width-2 or bb[3]>item.height-2:
                # Closeups intentionally crop; whole-aircraft views must not.
                if name!='detail':failures.append((role,name,'clipping',bb))
    for path in (OUT/'icons').glob('*.png'):
        item=Image.open(path).convert('RGBA')
        if not item.getchannel('A').getbbox():failures.append((path.name,'empty'))
    dock=json.loads((OUT/'dock/fit-checks.json').read_text())
    if not dock['passed']:failures.append(('dock','fit'))
    for report_path in (OUT/'export-roundtrip.json',OUT/'dock/export-roundtrip.json'):
        if not json.loads(report_path.read_text())['passed']:
            failures.append((report_path.name,'export roundtrip'))
    report={'passed':not failures,'failures':failures,'roleModels':6,'orthographicAndObliqueViewsPerRole':9,
            'iconRasters':len(list((OUT/'icons').glob('*.png'))),'roleGlyphVectors':7,
            'sourceBlendsUnchanged':True,'aircraftAndDockExportRoundtrip':True,
            'notVerified':['In-game renderer','Runtime hitboxes/navigation','Player save migration','Human small-icon recognition study']}
    (OUT/'output-checks.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2))
    if failures:raise RuntimeError(failures)


def main():
    family()
    manifest=icons()
    scale_board()
    checks(manifest)


if __name__=='__main__':main()
