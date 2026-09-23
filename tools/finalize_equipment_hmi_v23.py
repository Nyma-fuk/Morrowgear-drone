"""Package design evidence without changing Minecraft assets or deployments."""
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/design/equipment-hmi-v23'
VERIFY = OUT / 'verification'
FONT = 'C:/Windows/Fonts/meiryo.ttc'


def label(image, point, text, size=20):
    ImageDraw.Draw(image).text(point, text, font=ImageFont.truetype(FONT, size), fill='#e8f1ef')


def paste_fit(board, path, x, y, width, height):
    image = Image.open(path).convert('RGBA')
    image.thumbnail((width, height), Image.Resampling.LANCZOS)
    board.paste(image, (x + (width-image.width)//2, y + (height-image.height)//2), image)


def main():
    manifest = json.loads((OUT / 'item-manifest.json').read_text(encoding='utf-8'))
    checked = []
    for item in manifest['items']:
        for view in ('hero', 'front', 'rear', 'top', 'bottom', 'left', 'right'):
            path = (OUT / 'hmi' / item['viewBase'] / (view+'.png')).resolve()
            image = Image.open(path).convert('RGBA')
            assert image.getchannel('A').getbbox(), (item['id'], view, 'blank')
            checked.append(str(path.relative_to(ROOT)))
        for size in (24, 32, 48, 64, 128, 256):
            image = Image.open(OUT / 'items' / f'{item["id"]}-{size}.png')
            assert image.size == (size, size) and image.getchannel('A').getbbox()
        for ext in ('blend', 'glb'):
            assert (OUT / 'hmi' / item[ext]).resolve().is_file(), item['id']
    symbols = json.loads((OUT/'hmi/symbols/manifest.json').read_text())
    board = Image.new('RGB', (1500, 1320), '#192024')
    label(board, (28, 18), 'HMI SYMBOLS / 共通線幅・役割・操作・状態', 26)
    for index, asset in enumerate(symbols['assets']):
        x, y = 24+(index % 8)*184, 78+(index//8)*101
        for size in (24, 32, 48, 64):
            path = OUT/'hmi/symbols'/f'{asset["id"]}-{size}.png'
            image = Image.open(path).convert('RGBA')
            assert image.size == (size, size) and image.getchannel('A').getbbox()
        paste_fit(board, OUT/'hmi/symbols'/f'{asset["id"]}-48.png', x+55, y, 48, 48)
        label(board, (x+4, y+54), asset['id'], 13)
    board.save(OUT/'hmi-symbols.png')

    for path in VERIFY.glob('*.jpg'):
        Image.open(path).convert('RGB').save(path.with_suffix('.png'))
    desktop = Image.new('RGB', (1640, 2260), '#151b1e')
    label(desktop, (26, 18), 'MORROWGEAR C2 / 全8画面・実操作プレビュー', 28)
    names = [('tactical','作戦地図'), ('wings','Wing管理'), ('missions','任務'),
             ('supply','Dock・補給'), ('salvage','救難・回収'), ('visor','バイザー'),
             ('items','機器・アイテム'), ('settings','HMI設定')]
    for i, (key, title) in enumerate(names):
        x, y = 20+(i % 2)*814, 80+(i//2)*540
        label(desktop, (x+7, y), title, 23)
        paste_fit(desktop, VERIFY/f'{key}-1440.png', x, y+40, 794, 496)
    desktop.save(OUT/'hmi-overview.png')
    mobile = Image.new('RGB', (1660, 1090), '#151b1e')
    label(mobile, (22, 15), 'SMALL VIEW / 表示縮小ではなく画面を切替', 28)
    for i, key in enumerate(('tactical','wings','items','visor')):
        label(mobile, (22+i*410, 69), dict(names)[key], 22)
        paste_fit(mobile, VERIFY/f'{key}-390.png', 18+i*410, 115, 390, 900)
    mobile.save(OUT/'hmi-mobile.png')
    held = Image.new('RGB', (1600, 1120), '#283136')
    label(held, (25, 16), 'HELD / 左右保持姿勢・装着方向の設計確認', 27)
    for i, key in enumerate(('controller-left','controller-right','recovery_tool-left','recovery_tool-right','visor-equipped')):
        x, y = 18+(i % 2)*790, 78+(i//2)*338
        label(held, (x+8, y), key, 17)
        paste_fit(held, OUT/'held'/f'{key}.png', x, y+30, 770, 295)
    held.save(OUT/'held-overview.png')

    browser = json.loads((VERIFY/'browser-results.json').read_text(encoding='utf-8'))
    policy = json.loads((VERIFY/'interaction-policy.json').read_text())
    exports = json.loads((VERIFY/'export-roundtrip.json').read_text())
    poses = [json.loads(p.read_text()) for p in (OUT/'models').glob('*/validation.json')]
    a = np.asarray(Image.open(VERIFY/'visor-final-a.png').convert('RGB'))
    b = np.asarray(Image.open(VERIFY/'visor-final-b.png').convert('RGB'))
    area = a[300:700, 300:1100]
    pixel_std = float(area.std())
    changed = float((np.abs(a.astype(float)-b.astype(float)).max(axis=2)>8).mean())
    report = {
        'scope':'Design artifacts and browser prototype, NOT Minecraft runtime',
        'registeredItems':len(manifest['items']), 'itemPngs':len(manifest['items'])*6,
        'registeredItemViews':len(checked), 'newCanonicalModels':len(poses),
        'hmiSymbols':symbols['count'], 'hmiSymbolPngs':symbols['count']*4,
        'geometryPoses':sum(len(p['checks']) for p in poses),
        'maximumMirrorError':max(c['mirrorError'] for p in poses for c in p['checks']),
        'geometryPassed':all(p['passed'] for p in poses), 'exportRoundtripPassed':exports['passed'],
        'policyCases':policy['total'], 'policyPassed':policy['passed'],
        'browserChecks':len(browser['tests']), 'browserPassed':sum(t['pass'] for t in browser['tests']),
        'canvasPixelStd':pixel_std, 'changedPixelFraction':changed,
        'canvasNonblankAndChanging':pixel_std>12 and changed>.0005,
        'gameInstalled':False, 'gameVerified':False,
        'notes':[
            'Browser screenshots are real prototype captures, not Minecraft screenshots.',
            '1920 CSS viewport was layout-tested; desktop evidence uses fully captured 1440 viewport.',
            'The 3D scene renders up to eight representative aircraft; 256-unit tests cover the interface data.',
            'Material/lighting fidelity in Minecraft and final item display transforms are untested.',
            'Finite tests do not cover all possible operation sequences or prove absence of bugs.'
        ]}
    report['passed'] = report['geometryPassed'] and report['exportRoundtripPassed'] and policy['passed']==policy['total'] and all(t['pass'] for t in browser['tests']) and report['canvasNonblankAndChanging']
    (VERIFY/'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    assert report['passed'], report


if __name__ == '__main__':
    main()
