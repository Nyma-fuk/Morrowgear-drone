"""Independent layout drawings. Does not read or execute HTML/CSS/JavaScript."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
ASSETS = ROOT / "src/main/resources/assets/morrowgear_drone/textures"
TERRAIN = ROOT / "docs/design/equipment-hmi-v23/hmi/terrain.png"
FONT = "C:/Windows/Fonts/meiryo.ttc"
C = {"bg":"#111416", "panel":"#1a1e21", "raised":"#23282b", "line":"#394347",
     "text":"#edf3f2", "muted":"#a6b5b8", "cyan":"#62d8df", "amber":"#ffbf69",
     "red":"#ff7b75", "green":"#8cddb4"}
FONTS = {n: ImageFont.truetype(FONT, n) for n in [12, 14, 16, 18, 20, 22]}

def canvas(title, width=1280, height=800):
    im = Image.new("RGB", (width, height), C["bg"])
    d = ImageDraw.Draw(im)
    box(d, (0,0,width,42), "raised")
    text(d, 14, 8, "V24 | レイアウト設計図・実画面未検証", "amber", 16)
    text(d, width-330, 8, title, "text", 16)
    return im, d

def box(d, rect, color="panel", outline=None):
    d.rectangle(rect, fill=C[color], outline=C[outline] if outline else None)

def text(d, x, y, value, color="text", size=16):
    d.text((x,y), str(value), font=FONTS[size], fill=C[color])

def asset(im, category, name, xy, size):
    p = ASSETS / category / (name+".png")
    icon = Image.open(p).convert("RGBA")
    icon.thumbnail((size,size), Image.Resampling.LANCZOS)
    im.paste(icon, (xy[0]+(size-icon.width)//2, xy[1]+(size-icon.height)//2), icon)

def symbol(im, name, x, y, size=20):
    asset(im,"gui/symbols",name,(x,y),size)

def button(im,d,x,y,w,label,sym=None,active=False,color="text"):
    box(d,(x,y,x+w,y+34),"raised","cyan" if active else "line")
    if sym: symbol(im,sym,x+8,y+7)
    text(d,x+(36 if sym else 10),y+5,label,color,14)

def header(im,d,active="作戦"):
    box(d,(0,43,1280,100),"panel")
    text(d,18,58,"MORROWGEAR",size=18)
    text(d,172,62,"C2","cyan",14)
    for i,(label,icon) in enumerate([("作戦","map"),("部隊","layers"),("基地・補給","dock"),("履歴","clock"),("設定","sliders-horizontal")]):
        x=230+i*164
        if label==active:
            box(d,(x,43,x+155,100),"raised")
            box(d,(x,97,x+155,100),"cyan")
        symbol(im,icon,x+10,60)
        text(d,x+40,58,label,"cyan" if label==active else "muted",16)
    text(d,1090,63,"LINK ONLINE","green",14)
    d.line((0,100,1280,100),fill=C["line"])
    box(d,(0,101,1280,143),"panel")
    text(d,16,111,"8機 選択中","cyan",16)
    text(d,160,111,"完全Wing 1  /  部分Wing 0  /  未所属 0  /  フィルター外 0","muted",16)
    button(im,d,1110,105,104,"詳細","list-checks")
    symbol(im,"x",1240,113)
    d.line((0,143,1280,143),fill=C["line"])

def operation():
    im,d=canvas("作戦端末 / 100機",height=820)
    header(im,d)
    for i,label in enumerate(["地図","任務","戦線"]):
        text(d,18+i*76,153,label,"cyan" if i==0 else "muted",16)
    d.line((0,183,1280,183),fill=C["line"])
    box(d,(0,184,250,784),"panel")
    text(d,12,195,"部隊",size=18); text(d,171,198,"100機","muted",14)
    button(im,d,10,230,111,"全ロール")
    button(im,d,129,230,111,"ID順")
    for i,(label,active) in enumerate([("機体",False),("Wing",True),("全機",False)]):
        button(im,d,10+i*78,272,73,label,active=active)
    roles=["security","scout","engineer","cargo","field","salvage","security","scout"]
    names=["警備","偵察","作業","輸送","汎用","回収","警備","偵察"]
    for i,r in enumerate(roles):
        y=318+i*50
        box(d,(4,y,246,y+47),"raised")
        box(d,(4,y,7,y+47),"cyan")
        asset(im,"gui/roles",r,(12,y+2),42)
        text(d,61,y+3,f"MG-{i+1:03}  W01",size=14)
        text(d,61,y+25,f"{names[i]} / "+("補給帰還" if i==2 else "任務中"),"muted",12)
        text(d,198,y+13,"14%" if i==2 else "86%","amber" if i==2 else "green",14)
    for x,sym in [(14,"chevrons-left"),(49,"chevron-left"),(178,"chevron-right"),(214,"chevrons-right")]:symbol(im,sym,x,742)
    text(d,83,740,"1-8 / 100","muted",12)
    terrain=Image.open(TERRAIN).convert("RGB").resize((748,551),Image.Resampling.NEAREST)
    im.paste(terrain,(251,184))
    box(d,(251,184,999,227),"panel")
    text(d,265,195,"作戦地図",size=18);text(d,379,199,"Overworld","muted",14)
    for x,sym in [(902,"minus"),(934,"plus"),(966,"locate-fixed")]:symbol(im,sym,x,196)
    for i,r in enumerate(roles[:6]):
        x=325+i%3*220; y=300+i//3*200
        box(d,(x,y,x+106,y+38),"panel","cyan")
        asset(im,"gui/roles",r,(x+4,y+1),36)
        text(d,x+46,y+8,f"W{i+1:02}",size=16)
    box(d,(798,629,932,665),"panel","red");symbol(im,"target",807,637);text(d,838,634,"接触 3","red",16)
    box(d,(301,641,432,679),"panel","green");asset(im,"item","dock_item",(306,643),34);text(d,345,649,"Dock 03",size=14)
    box(d,(251,736,999,784),"panel")
    box(d,(251,736,999,738),"amber")
    text(d,264,745,"地点移動 / 対象指定","amber",16)
    text(d,506,746,"8機 / X 48 Z -24","muted",14)
    button(im,d,782,743,97,"取消","x")
    button(im,d,890,743,97,"確定","check",True)
    box(d,(1000,184,1279,784),"panel")
    text(d,1016,196,"対象への命令",size=18);text(d,1215,199,"8機","cyan",14)
    text(d,1016,240,"移動・待機",size=16)
    cmds=[("追従","navigation"),("待機","pause"),("自分へ帰還","corner-down-left"),("Dock帰投","dock"),("周回","rotate-ccw"),("地点移動","map-pin")]
    for i,(label,sym) in enumerate(cmds):button(im,d,1013+i%2*130,271+i//2*42,122,label,sym,active=i==5)
    d.line((1013,404,1267,404),fill=C["line"])
    text(d,1016,417,"作戦",size=16);text(d,1133,419,"□ 自動編成","muted",14)
    for i,(label,sym) in enumerate([("鉱脈処理","pickaxe"),("掘削","mountain"),("樹体処理","tree-pine"),("警戒","shield"),("巡回路","route"),("追跡","target")]):button(im,d,1013+i%2*130,451+i//2*42,122,label,sym)
    text(d,1016,586,"輸送",size=16)
    button(im,d,1013,620,122,"搬出元","package-minus")
    button(im,d,1143,620,122,"搬入先","package-plus")
    button(im,d,1013,714,252,"機体を格納","package",color="red")
    text(d,14,793,"承認待ち | 描画・文字幅・操作判定・GUI倍率はMinecraft実画面での確認が必要", "muted",14)
    im.save(HERE/"operations-layout.png")

def draw_slot(im,d,x,y,size,index,it=None,label=None):
    if label:text(d,x,y-22,label,"muted",14)
    box(d,(x,y,x+size-3,y+size-3),"bg","line")
    d.line((x,y,x+size-3,y),fill=C["muted"])
    d.line((x,y,x,y+size-3),fill=C["muted"])
    if it:
        asset(im,"item",it[0],(x+2,y+2),size-8)
        text(d,x+size-20,y+size-21,it[1],"text",12)

def dock():
    im,d=canvas("Dock / 27枠の配置契約",height=820)
    box(d,(0,43,1280,106),"panel")
    asset(im,"item","dock_item",(14,46),56)
    text(d,85,52,"MORROWGEAR / Dock 03",size=20)
    text(d,85,81,"MG-008 / 着艦 / W01", "muted",14)
    text(d,972,62,"弾薬待ち / 出撃保留","amber",18)
    text(d,24,127,"装備・サービス入力",size=18)
    text(d,362,132,"枠番号 0-8 / 位置とIDを維持","muted",14)
    labels=["機体","電池","役割","兵装","燃料","整備","弾薬","出力","蓄電"]
    items={0:("field_drone_unit",1),1:("reinforced_battery_pack",1),2:("security_module",1),3:("autocannon_module",1),4:("power_cell",8),5:("morrow_alloy",12),8:("high_density_battery_pack",1),9:("field_drone_unit",1),18:("power_cell",32),19:("morrow_alloy",24)}
    for i,label in enumerate(labels):draw_slot(im,d,24+i*60,189,56,i,items.get(i),label)
    text(d,24,259,"回収出力",size=18);text(d,420,264,"枠番号 9-17","muted",14)
    for i in range(9):draw_slot(im,d,24+i*60,293,56,9+i,items.get(9+i))
    text(d,24,365,"共通補給",size=18);text(d,420,370,"枠番号 18-26","muted",14)
    for i in range(9):draw_slot(im,d,24+i*60,399,56,18+i,items.get(18+i))
    text(d,24,478,"所持品",size=18);text(d,350,483,"27枠 + ホットバー9枠","muted",14)
    for i in range(27):draw_slot(im,d,24+i%9*60,515+i//9*54,52,27+i,("power_cell",16) if i==0 else None)
    for i in range(9):draw_slot(im,d,24+i*60,692,52,54+i)
    d.line((596,122,596,760),fill=C["line"])
    text(d,622,128,"同期された整備状態",size=18)
    rows=[("蓄電 / 容量","6,200 / 10,000","text"),("飛行電力","100%","green"),("兵装電力","100%","green"),("機関砲弾薬","0発","amber"),("燃料在庫","40個","text"),("回収出力の使用","1 / 10枠","text")]
    for i,(label,value,color) in enumerate(rows):
        y=180+i*43;text(d,623,y,label,"muted",16);text(d,1040,y,value,color,16);d.line((622,y+32,1235,y+32),fill=C["line"])
    text(d,622,462,"停止理由","amber",18)
    text(d,622,501,"機関砲弾薬不足 / 補給元に在庫なし",size=16)
    text(d,622,544,"機関砲マガジン 120発 / レーザーセル 1,000電力","muted",14)
    text(d,622,572,"小型ミサイルパック 5発","muted",14)
    button(im,d,622,625,156,"補給網へ","package")
    button(im,d,803,625,206,"出撃不可: 弾薬不足","send",color="muted")
    text(d,622,700,"Slot操作・個数・投入可否はDockMenuが判定", "muted",14)
    text(d,14,786,"設計図は拡大配置例。実装はDockMenuの18pxピッチと0-62の実Slotを使用。実画面未検証。", "muted",14)
    im.save(HERE/"dock-layout.png")

def hud():
    im,d=canvas("HUD / 戦線集約・注目機・単一通知",height=800)
    im.paste(Image.open(TERRAIN).convert("RGB").resize((1280,716),Image.Resampling.NEAREST),(0,42))
    box(d,(20,63,1260,108),"panel")
    box(d,(20,63,1260,65),"cyan")
    text(d,34,75,"戦線 2",size=18)
    text(d,150,76,"交戦 12機",size=18)
    text(d,323,76,"要対応 3件","amber",18)
    text(d,977,79,"MORROWGEAR / 100機","muted",14)
    box(d,(977,132,1260,308),"panel")
    box(d,(977,132,979,308),"cyan")
    text(d,993,145,"MG-001 / W01",size=18);symbol(im,"pin",1227,150)
    text(d,993,188,"飛行 / 兵装","muted",14);text(d,1140,188,"86% / 82%",size=14)
    text(d,993,229,"状態","muted",14);text(d,1140,229,"任務中",size=14)
    text(d,993,269,"リンク / 系統","muted",14);text(d,1140,269,"正常 / 100%",size=14)
    asset(im,"gui/roles","security",(490,345),80)
    symbol(im,"focus",479,344,94)
    text(d,491,437,"W01 / 8機",size=14)
    d.line((635,400,645,400),fill=C["text"],width=2)
    d.line((640,395,640,405),fill=C["text"],width=2)
    box(d,(20,676,882,740),"panel")
    box(d,(20,676,23,740),"red")
    text(d,37,685,"北東戦線 / 2機 電力喪失・回収待ち",size=18)
    text(d,37,715,"W03 / X 128 Z -64 / 救難信号を保持", "muted",14)
    symbol(im,"clock",836,695,25)
    text(d,14,771,"HUD配置のみ。既存地形を背景に使用 / ゲームの視点・遮蔽・投影を再現した画像ではありません。", "muted",14)
    im.save(HERE/"hud-layout.png")

if __name__ == "__main__":
    operation()
    dock()
    hud()
    print("Created three independent layout diagrams; browser and Minecraft rendering not performed.")
