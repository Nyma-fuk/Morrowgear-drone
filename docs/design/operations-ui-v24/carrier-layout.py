"""Independent carrier diagrams from menu coordinates, never from HTML or a browser."""
import importlib.util
from pathlib import Path
from PIL import Image, ImageFont

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("layout_art", HERE / "make-layout-diagrams.py")
art = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(art)
C = art.C
CARGO_SLOTS = 54
PLAYER_START = 54
HOTBAR_START = 81
SLOT_END = 90
BAY_CAPACITY = 4
NATIVE_SIZE = (176, 222)
COMPACT_SIZE = (320, 240)
COMPACT_STOP = (182, 216, 312, 237)
COMPACT_CANCEL = (276, 138, 312, 158)
COMPACT_FONT_SIZE = 9
for size in (24, 27):
    art.FONTS[size] = ImageFont.truetype(art.FONT, size)


def native_slots():
    cargo = [(i, 8 + i % 9 * 18, 18 + i // 9 * 18) for i in range(54)]
    player = [(54 + i, 8 + i % 9 * 18, 140 + i // 9 * 18) for i in range(27)]
    hotbar = [(81 + i, 8 + i * 18, 198) for i in range(9)]
    return cargo + player + hotbar


def inventory(im, d, origin, scale):
    sample = {0:("morrow_alloy",24), 1:("raw_morrow_composite",16), 9:("power_cell",8),
              10:("reinforced_battery_pack",1), 54:("power_cell",4), 55:("recovery_tool",1)}
    for index, nx, ny in native_slots():
        x, y = origin[0] + nx * scale, origin[1] + ny * scale
        size = 16 * scale
        art.box(d,(x-1,y-1,x+size,y+size),"bg","line")
        d.line((x-1,y-1,x+size,y-1),fill=C["muted"])
        if index in sample:
            name, count = sample[index]
            art.asset(im,"item",name,(x,y),size)
            if scale >= 2:
                font_size = 8 * scale
                count_width = d.textlength(str(count), font=art.FONTS[font_size])
                art.text(d,x+size-count_width,y+size-10*scale,count,size=font_size)


def line(d,x,y,label,value,width=360,color="text"):
    art.text(d,x,y,label,"muted",14)
    art.text(d,x+width,y,value,color,14)


def desktop():
    im,d=art.canvas("母艦コンソール / 値は表示例",height=1020)
    art.header(im,d,"基地・補給")
    art.box(d,(0,101,1280,151),"panel")
    art.text(d,16,113,"基地・補給 / 母艦 24CF",size=18)
    art.text(d,336,116,"所有者 / 船内から接続", "cyan",14)
    art.button(im,d,988,107,120,"乗艦","log-in",color="muted")
    art.button(im,d,1120,107,144,"安全下船","log-out")
    for x,label in [(16,"運用"),(112,"貨物"),(208,"ベイ"),(304,"乗艦権限")]:
        art.text(d,x,162,label,"cyan" if x==16 else "muted",16)
    art.text(d,580,163,"外装 Overworld / X 48 Y 100 Z -32", "muted",14)
    d.line((0,190,1280,190),fill=C["line"])
    line(d,16,206,"飛行電力","80,000 / 100,000",160)
    art.button(im,d,376,199,130,"燃料補充","battery-charging")
    line(d,568,206,"兵装電力","12,000 / 100,000",158)
    art.button(im,d,990,199,184,"兵装電力を充填","zap")
    d.line((0,248,1280,248),fill=C["line"])
    d.line((398,263,398,926),fill=C["line"])
    art.text(d,24,268,"貨物 54枠",size=18)
    art.text(d,215,271,"所有者のみ操作","muted",14)
    inventory(im,d,(16,284),2)
    art.text(d,32,537,"所持品 27枠 + ホットバー9枠",size=14)
    art.text(d,24,743,"貨物 0-53 / 所持品 54-80", "muted",14)
    art.text(d,24,771,"ホットバー 81-89", "muted",14)
    art.text(d,24,821,"航行",size=18)
    art.text(d,24,856,"現在 停止 / 行先 未指定", "muted",14)
    art.button(im,d,24,889,230,"航行点を指定","navigation")
    art.text(d,420,267,"作業コンソール",size=18)
    art.button(im,d,420,307,202,"採掘 16 × 16","pickaxe",True)
    art.button(im,d,633,307,276,"対生物 / 地形破壊なし","target")
    art.text(d,962,315,"未稼働 / プレビュー","amber",14)
    art.text(d,420,363,"対象範囲",size=16)
    terrain=Image.open(art.TERRAIN).convert("RGB").resize((218,169),Image.Resampling.NEAREST)
    im.paste(terrain,(420,395))
    d.rectangle((464,420,592,548),outline=C["amber"],width=3)
    for i in range(1,16):
        p=464+i*8; q=420+i*8
        d.line((p,420,p,548),fill=C["line"])
        d.line((464,q,592,q),fill=C["line"])
    art.box(d,(481,468,577,495),"panel")
    art.text(d,493,472,"16 × 16","amber",14)
    line(d,660,394,"チャンク","3 / -2",210)
    line(d,660,427,"X / Z","48..63 / -32..-17",210)
    line(d,660,460,"高さ Y","-64..64 / 129層",210)
    line(d,660,493,"世代","7B2E / 同じ世代で確定",210,"cyan")
    line(d,660,526,"有効時間","残り 8.0秒",210,"amber")
    art.text(d,420,589,"採掘: ブロック破壊あり / 1チャンクを上から処理", "amber",16)
    art.text(d,420,618,"範囲内の友軍・保護対象・満杯・電力不足で停止", "muted",14)
    art.button(im,d,420,652,160,"再プレビュー","refresh-cw")
    art.button(im,d,591,652,188,"保存地点を再確認","rotate-ccw")
    art.button(im,d,790,652,310,"範囲を確認して採掘開始","check",True)
    art.button(im,d,1110,652,132,"取消","x")
    d.line((420,710,1264,710),fill=C["line"])
    art.text(d,420,730,"一時サービス予約 2 / 4",size=18)
    art.text(d,965,734,"所属Dock・Wingを維持", "muted",14)
    for i in range(BAY_CAPACITY):
        y=774+i*37
        art.text(d,420,y,f"予約 {i+1}","muted",14)
        if i<2:
            art.asset(im,"gui/roles","security" if i==0 else "cargo",(503,y-4),30)
            art.text(d,544,y,"MG-001" if i==0 else "MG-004",size=14)
            art.text(d,689,y,"予約あり / 個別進捗未取得", "muted",14)
            art.button(im,d,1110,y-5,132,"予約解除","x")
        else:
            art.text(d,544,y,"空き", "muted",14)
            art.button(im,d,1110,y-5,132,"機体を予約","plus")
    art.box(d,(0,943,1280,994),"panel")
    art.button(im,d,16,951,226,"緊急停止","square",color="red")
    art.text(d,266,958,"未稼働 / 採掘の確定待ち", "amber",16)
    art.text(d,766,960,"船ID 24CF / 更新済み / 所有者", "muted",14)
    art.text(d,14,997,"独立設計図 / 貨物座標は2倍表示 / 値はサンプル / ブラウザー・Minecraft描画ではありません", "muted",12)
    im.save(HERE/"carrier-layout.png")


def compact():
    # Draw native slot positions at a deliberate 3x diagram scale, not a screen capture.
    im,d=art.canvas("母艦 / 小画面の寸法設計",width=1280,height=896)
    ox,oy,s=26,89,3
    art.text(d,26,52,"320 × 240 論理GUI / 3倍の座標図",size=16)
    art.box(d,(ox,oy,ox+320*s,oy+240*s),"panel","line")
    def text(x,y,v,c="text",size=COMPACT_FONT_SIZE):art.text(d,ox+x*s,oy+y*s,v,c,size*s)
    def rect(r,c="bg",border="line"):
        art.box(d,tuple(ox+r[i]*s if i%2==0 else oy+r[i]*s for i in range(4)),c,border)
    text(8,3,"貨物 54枠")
    inventory(im,d,(ox,oy),s)
    text(8,128,"所持品")
    d.line((ox+176*s,oy,ox+176*s,oy+240*s),fill=C["line"])
    text(182,5,"母艦 24CF / 所有者")
    rect((182,22,312,42),"raised");text(186,27,"運用 ▾")
    text(182,48,"飛行 80,000 / 100,000")
    text(182,62,"兵装 12,000 / 100,000")
    text(182,82,"採掘 16 × 16", "cyan")
    text(182,100,"プレビュー / 未稼働", "amber")
    text(182,116,"X48..63 Z-32..-17")
    text(182,130,"Y-64..64 / 世代7B2E")
    text(182,145,"残り8.0秒", "amber")
    rect(COMPACT_CANCEL,"raised");text(282,143,"取消")
    rect((182,161,312,181),"raised");text(186,166,"範囲を確認して開始")
    rect((182,188,312,208),"raised");text(186,193,"安全下船")
    rect(COMPACT_STOP,"raised","red");text(186,221,"緊急停止", "red")
    art.text(d,1010,99,"設計上の境界",size=18)
    for i,ln in enumerate(["貨物54 + 所持品36", "Slot 0-89を保持", "18pxピッチ", "全枠を同時表示", "", "主ナビは増やさない", "基地・補給 → 母艦", "専用Menu内の文脈", "", "緊急停止は固定", "ダイアログにも配置", "", "訪問者は状態閲覧", "乗下船のみ", "", "320×180は高さ不足", "全90枠の表示不可"]):
        art.text(d,1010,136+i*31,ln,"amber" if i>=15 else "muted",14)
    art.text(d,26,829,"矩形の座標確認用。Minecraftフォント・文字倍率・GUI倍率・カーソル・クリック領域の実検証は未実施。", "muted",14)
    art.text(d,26,859,"訪問者にも停止状態を表示するが、STOP送信は所有者のみ。確認ダイアログからも即時停止へ到達させる。", "muted",14)
    im.save(HERE/"carrier-compact-layout.png")


if __name__ == "__main__":
    desktop()
    compact()
    print("Created independent carrier layout diagrams; no HTML execution or runtime rendering.")
