"""Independent Pillow diagrams; never loads HTML or drives a browser."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
ASSETS = ROOT / "src/main/resources/assets/morrowgear_drone/textures"
MAP_SOURCE = ROOT / "docs/design/equipment-hmi-v23/verification/in-game/wing-map-patrol.png"
MAP_CROP = (422, 352, 1030, 746)
C = {"bg": "#111416", "panel": "#1a1e21", "raised": "#23282b", "line": "#394347",
     "text": "#edf3f2", "muted": "#a6b5b8", "cyan": "#62d8df", "amber": "#ffbf69",
     "red": "#ff7b75", "green": "#8cddb4"}
FONTS = {n: ImageFont.truetype("C:/Windows/Fonts/meiryo.ttc", n) for n in (14,16,18,20,22,24,27,28,30,32)}
TABS = (("採掘", "pickaxe"), ("敵への攻撃", "target"), ("移動", "navigation"),
        ("貨物・補給", "package"), ("船内", "home"))
TEXT_BOUNDS = []


def text(d, x, y, value, size=20, color="text", max_width=None):
    width = d.textlength(value, font=FONTS[size])
    if max_width is not None:
        assert width <= max_width, (value, width, max_width)
    TEXT_BOUNDS.append((value, size, width))
    d.text((x, y), value, font=FONTS[size], fill=C[color])


def box(d, rect, fill="panel", outline=None, width=1):
    d.rectangle(rect, fill=C[fill], outline=C[outline] if outline else None, width=width)


def icon(im, name, x, y, size=24):
    with Image.open(ASSETS / "gui/symbols" / f"{name}.png") as raw:
        asset = raw.convert("RGBA").resize((size, size), Image.Resampling.LANCZOS)
    im.paste(asset, (x, y), asset)


def button(im, d, rect, label, symbol=None, primary=False, danger=False, disabled=False, size=20):
    x, y, right, bottom = rect
    color = "muted" if disabled else "red" if danger else "cyan" if primary else "text"
    box(d, rect, "raised", color if primary or danger else "line", 2 if primary or danger else 1)
    tx = x + 16
    if symbol:
        icon(im, symbol, x + 14, y + (bottom-y-24)//2)
        tx = x + 49
    text(d, tx, y + (bottom-y-size)//2 - 3, label, size, color, right-tx-12)


def base(active="採掘", state="待機中"):
    im = Image.new("RGB", (1280,720), C["bg"])
    d = ImageDraw.Draw(im)
    box(d, (0,0,1280,32), "raised")
    text(d, 18, 5, "V25  レイアウト設計図・実画面未検証", 16, "amber")
    text(d, 736, 5, "既存ゲーム記録の地図抜粋 / 選択範囲・数値は配置例", 16, "muted", 528)
    box(d, (0,32,1280,89))
    icon(im, "chevron-left", 20, 49)
    text(d, 52, 45, "戻る", 20)
    text(d, 150, 46, "基地・補給 /", 18, "muted")
    text(d, 290, 41, "チャンクバスター", 28)
    text(d, 942, 48, state, 20, "green" if state=="採掘中" else "muted")
    icon(im, "sliders-horizontal", 1224, 48)
    for i, (label, symbol) in enumerate(TABS):
        x = 24 + i*230
        icon(im, symbol, x+6, 106)
        text(d, x+42, 100, label, 22, "cyan" if label==active else "muted")
        if label == active: box(d, (x,139,x+211,142), "cyan")
    d.line((0,144,1280,144), fill=C["line"])
    box(d, (0,654,1280,720))
    button(im,d,(24,667,174,710),"停止","square",danger=True,disabled=state=="待機中")
    text(d,202,677,state,20,"green" if state=="採掘中" else "muted")
    text(d,690,677,"貨物 12 / 54枠    補給品あり",20,"muted",550)
    return im,d


def cursor(d,x,y):
    d.polygon([(x,y),(x,y+30),(x+8,y+23),(x+14,y+37),(x+20,y+34),
               (x+14,y+21),(x+27,y+21)],fill=C["text"],outline=C["bg"],width=2)


def map_view(im,d,rect,stage="choose",other=False):
    x,y,right,bottom=rect
    with Image.open(MAP_SOURCE) as source:
        terrain=source.crop(MAP_CROP).resize((right-x,bottom-y),Image.Resampling.NEAREST)
    im.paste(terrain,(x,y))
    cell=144
    gx=x+30; gy=y+42
    for px in range(gx,right,cell): d.line((px,y,px,bottom),fill=C["muted"],width=2)
    for py in range(gy,bottom,cell): d.line((x,py,right,py),fill=C["muted"],width=2)
    ship=(gx+cell+72,gy+72)
    sx,sy=(gx+cell,gy+cell) if other else (gx+cell,gy)
    tint=Image.new("RGBA", im.size, (0,0,0,0)); td=ImageDraw.Draw(tint)
    hovered = stage == "hover"
    td.rectangle((sx,sy,sx+cell,sy+cell),fill=(98,216,223,20) if hovered else (255,191,105,55),
                 outline=C["cyan"] if hovered else C["amber"],width=2 if hovered else 4)
    im.paste(tint,(0,0),tint)
    for i in range(1,1 if hovered else 16):
        p=i*cell//16
        d.line((sx+p,sy,sx+p,sy+cell),fill="#99875c",width=1)
        d.line((sx,sy+p,sx+cell,sy+p),fill="#99875c",width=1)
    d.rectangle((sx,sy,sx+cell,sy+cell),outline=C["cyan"] if hovered else C["amber"],width=2 if hovered else 4)
    if other:
        d.line((ship[0],ship[1],sx+72,sy+72),fill=C["cyan"],width=3)
    d.ellipse((ship[0]-14,ship[1]-14,ship[0]+14,ship[1]+14),fill=C["panel"],outline=C["cyan"],width=3)
    icon(im,"navigation",ship[0]-10,ship[1]-10,20)
    box(d,(ship[0]-62,sy-38 if not other else gy-38,ship[0]+62,sy-8 if not other else gy-8),"panel")
    text(d,ship[0]-51,sy-37 if not other else gy-37,"母艦の位置",18,"cyan")
    box(d,(sx+2,sy+cell+8,sx+211,sy+cell+41),"panel")
    text(d,sx+12,sy+cell+10,"未選択" if hovered else "選択範囲 16 × 16",18,"cyan" if hovered else "amber")
    if stage in ("choose", "hover"):cursor(d,sx+101,sy+95)
    box(d,(x+12,bottom-43,x+409,bottom-9),"panel")
    text(d,x+24,bottom-41,"区画 16 × 16ブロック",18,"text")
    icon(im,"plus",right-75,y+14,24);icon(im,"minus",right-36,y+14,24)
    return (sx,sy,sx+cell,sy+cell)


def steps(d,stage):
    for i,label in enumerate(("1  区画を選ぶ","2  範囲を確認","3  採掘開始")):
        text(d,24+i*295,160,label,20,"cyan" if i==stage else "muted")


def mining(stage=0,other=False):
    state="採掘中" if stage==2 else "待機中"
    im,d=base(state=state);steps(d,stage)
    map_view(im,d,(24,210,860,636),"choose" if stage==0 else "confirm",other)
    x=900
    title="採掘する区画" if stage==0 else "選択範囲の確認" if stage==1 else "採掘中"
    text(d,x,211,title,28,"green" if stage==2 else "text",356)
    text(d,x,273,"選んだ区画" if other else "母艦の真下",24,"amber",356)
    text(d,x,318,"16 × 16 ブロック",24,"text",356)
    if stage==0:
        if other: text(d,x,376,"到着後に採掘範囲を確認",20,"muted",356)
        button(im,d,(x,546,1256,603),"母艦をここへ移動" if other else "採掘範囲を確認","navigation" if other else "chevron-right",True)
    elif stage==1:
        text(d,x,365,"地表から下へ / 岩盤は残す",20,"text",356)
        text(d,x,394,"高さ Y -64 ～ 94",16,"muted",356)
        icon(im,"triangle-alert",x,426)
        text(d,x+36,423,"ブロックが壊れます",20,"amber",320)
        box(d,(x,477,x+22,499),"raised","cyan")
        icon(im,"check",x+1,478,20)
        text(d,x+35,473,"範囲を確認しました",20,"text",321)
        button(im,d,(x,537,1256,594),"この範囲の採掘を開始","play",True)
        button(im,d,(x,605,1256,645),"区画を選び直す","chevron-left",size=18)
    else:
        text(d,x,379,"進み具合",20,"muted")
        text(d,x+245,365,"38%",32,"green",110)
        box(d,(x,417,1256,429),"raised")
        box(d,(x,417,x+135,429),"green")
        text(d,x,470,"貨物 12 / 54枠",20,"muted")
        button(im,d,(x,546,1256,603),"貨物・補給","package")
    name="04-move-to-area.png" if other else ("01-select-area.png","02-confirm-area.png","03-mining.png")[stage]
    im.save(HERE/name)


def unselected():
    im,d=base();steps(d,0)
    map_view(im,d,(24,210,860,636),"hover")
    text(d,900,211,"採掘する区画",28,max_width=356)
    text(d,900,273,"未選択",24,"muted",356)
    button(im,d,(900,546,1256,603),"採掘範囲を確認","chevron-right",True,disabled=True)
    im.save(HERE/"00-unselected.png")


def native_slots():
    return ([(i,8+i%9*18,18+i//9*18) for i in range(54)]
            + [(54+i,8+i%9*18,140+i//9*18) for i in range(27)]
            + [(81+i,8+i*18,198) for i in range(9)])


def cargo():
    im,d=base("貨物・補給")
    ox,oy,scale=40,182,2
    text(d,56,168,"貨物",24)
    text(d,226,175,"12 / 54枠",18,"muted")
    for i,x,y in native_slots():
        px,py=ox+x*scale,oy+y*scale
        box(d,(px-1,py-1,px+16*scale,py+16*scale),"raised","line")
        if i in (0,1,2,9,10,54,55):
            name={0:"morrow_alloy",1:"raw_morrow_composite",2:"power_cell",9:"reinforced_battery_pack",
                  10:"power_cell",54:"power_cell",55:"recovery_tool"}[i]
            with Image.open(ASSETS/"item"/f"{name}.png") as raw:
                asset=raw.convert("RGBA").resize((32,32),Image.Resampling.LANCZOS)
            im.paste(asset,(px,py),asset)
    text(d,56,438,"持ち物",18,"muted")
    d.line((423,172,423,634),fill=C["line"])
    text(d,458,170,"母艦に補給",24)
    text(d,458,220,"飛行用の電力",20)
    text(d,878,217,"80%",24,"cyan")
    button(im,d,(994,211,1256,258),"燃料を補給","battery-charging")
    text(d,458,293,"採掘・攻撃用の電力",20)
    text(d,878,290,"12%",24,"amber")
    button(im,d,(994,285,1256,332),"電力を補給","zap")
    d.line((458,357,1256,357),fill=C["line"])
    text(d,458,380,"小型機に補給",24)
    text(d,776,387,"2 / 4機",20,"muted")
    button(im,d,(994,373,1256,419),"機体を選ぶ","plus")
    for n,role in enumerate(("security","cargo")):
        y=458+n*66
        with Image.open(ASSETS/"gui/roles"/f"{role}.png") as raw:
            asset=raw.convert("RGBA").resize((46,46),Image.Resampling.LANCZOS)
        im.paste(asset,(458,y),asset)
        text(d,520,y+8,"MG-001" if n==0 else "MG-004",22)
        button(im,d,(1077,y,1256,y+46),"取り消す","x",size=18)
    im.save(HERE/"05-cargo-supply.png")


def combat():
    im,d=base("敵への攻撃")
    text(d,24,161,"攻撃する範囲を確認",20,"cyan")
    text(d,478,161,"地形は壊しません",20,"green")
    map_view(im,d,(24,210,860,636),"confirm")
    text(d,900,211,"敵への攻撃",28)
    text(d,900,272,"母艦の真下",24,"amber")
    text(d,900,318,"16 × 16 ブロック",24)
    text(d,900,376,"地形は壊しません",22,"green")
    text(d,900,421,"攻撃時間  5秒",20,"muted")
    box(d,(900,477,922,499),"raised","cyan");icon(im,"check",901,478,20)
    text(d,935,473,"攻撃範囲を確認しました",20,max_width=321)
    button(im,d,(900,537,1256,594),"この範囲の敵を攻撃","target",True)
    button(im,d,(900,605,1256,645),"範囲を選び直す","chevron-left",size=18)
    im.save(HERE/"06-attack.png")


def compact(stage):
    # Native 320x180 geometry is independently drawn at 3x, including 9px text.
    s,oy=3,40
    im=Image.new("RGB",(960,580),C["bg"]);d=ImageDraw.Draw(im)
    text(d,12,7,"V25 設計図・実画面未検証 / 320×180論理GUIの3倍座標図",18,"amber",936)
    def rect(r,fill="panel",outline=None):
        box(d,(r[0]*s,oy+r[1]*s,r[2]*s,oy+r[3]*s),fill,outline,2)
    def label(x,y,value,color="text",size=9,width=308):
        text(d,x*s,oy+y*s,value,size*s,color,width*s)
    def sym(name,x,y,size=10):icon(im,name,x*s,oy+y*s,size*s)
    def btn(r,label_text,symbol=None,primary=False,danger=False,disabled=False):
        rect(r,"raised","line" if disabled else "red" if danger else "cyan" if primary else "line")
        if symbol:sym(symbol,r[0]+5,r[1]+7)
        label(r[0]+20 if symbol else r[0]+6,r[1]+6,label_text,
              "muted" if disabled else "red" if danger else "cyan" if primary else "text",width=r[2]-r[0]-(26 if symbol else 12))
    rect((0,0,320,22));sym("chevron-left",5,5)
    label(20,4,"戻る",width=26);label(58,4,"チャンクバスター",width=139)
    rect((232,2,314,20),"raised","line");label(238,4,"採掘",width=60);sym("chevron-right",300,5)
    label(6,27,("1/3 区画を選ぶ","2/3 範囲を確認","3/3 採掘中")[stage],"cyan")
    map_rect=(6,44,144,119) if stage==1 else (6,44,314,128)
    mx,my,mr,mb=map_rect
    with Image.open(MAP_SOURCE) as source:
        terrain=source.crop(MAP_CROP).resize(((mr-mx)*s,(mb-my)*s),Image.Resampling.NEAREST)
    im.paste(terrain,(mx*s,oy+my*s))
    cell=48; sx=mx+46;sy=my+8
    for px in range(mx+46,mr,cell):d.line((px*s,oy+my*s,px*s,oy+mb*s),fill=C["muted"],width=2)
    for py in range(my+8,mb,cell):d.line((mx*s,oy+py*s,mr*s,oy+py*s),fill=C["muted"],width=2)
    d.rectangle((sx*s,oy+sy*s,(sx+cell)*s,oy+(sy+cell)*s),outline=C["amber"],width=6)
    for i in range(1,16):
        d.line(((sx+i*3)*s,oy+sy*s,(sx+i*3)*s,oy+(sy+cell)*s),fill="#99875c")
        d.line((sx*s,oy+(sy+i*3)*s,(sx+cell)*s,oy+(sy+i*3)*s),fill="#99875c")
    center=((sx+24)*s,oy+(sy+24)*s)
    d.ellipse((center[0]-16,center[1]-16,center[0]+16,center[1]+16),fill=C["panel"],outline=C["cyan"],width=3)
    icon(im,"navigation",center[0]-10,center[1]-10,20)
    if stage==0:
        cursor(d,(sx+34)*s,oy+(sy+26)*s)
        rect((159,69,269,84));label(164,70,"選択範囲 16×16", "amber",width=100)
        rect((159,88,244,102));label(164,89,"母艦の真下","cyan",width=76)
    elif stage==1:
        label(156,47,"16×16 ブロック",width=158)
        label(156,63,"下へ採掘 / 岩盤は残す",width=158)
        label(156,80,"ブロックが壊れます","amber",width=158)
        rect((156,102,166,112),"raised","cyan");sym("check",156,102)
        label(171,100,"範囲を確認しました",width=143)
    else:
        rect((159,69,294,86));label(164,70,"採掘中  38%","green",width=120)
    label(6,133,"貨物 12/54枠   補給品あり", "muted")
    btn((6,151,76,176),"停止","square",danger=True,disabled=stage<2)
    btn((87,151,314,176),("採掘範囲を確認","この範囲の採掘を開始","貨物・補給")[stage],
        "chevron-right" if stage==0 else "play" if stage==1 else "package",primary=stage<2)
    im.save(HERE/("10-small-select.png","11-small-confirm.png","12-small-mining.png")[stage])


if __name__ == "__main__":
    unselected()
    for stage in range(3): mining(stage)
    mining(0,True)
    cargo()
    combat()
    for stage in range(3): compact(stage)
    print("Created ten independent carrier layout diagrams; no runtime or HTML rendering.")
