#!/usr/bin/env python3
"""Generate the visual Morrowgear recipe guide from the shipped recipe JSON."""

from __future__ import annotations

import html
import json
import shutil
import zipfile
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RECIPE_DIR = ROOT / "src/main/resources/data/morrowgear_drone/recipe"
MOD_TEXTURE_DIR = ROOT / "src/main/resources/assets/morrowgear_drone/textures/item"
MOD_TEXTURE_FALLBACK = {"operation_marker": "assets/minecraft/textures/item/ender_eye.png"}
LANG_PATH = ROOT / "src/main/resources/assets/morrowgear_drone/lang/ja_jp.json"
OUTPUT_DIR = ROOT / "docs/guides/recipe-guide"
ASSET_DIR = OUTPUT_DIR / "assets"
MINECRAFT_JAR = Path.home() / ".gradle/caches/fabric-loom/26.2/minecraft-client.jar"


VANILLA_NAMES = {
    "iron_ingot": "鉄インゴット",
    "copper_ingot": "銅インゴット",
    "redstone": "レッドストーンダスト",
    "charcoal": "木炭",
    "iron_nugget": "鉄塊",
    "gold_nugget": "金塊",
    "glass_pane": "ガラス板",
    "piston": "ピストン",
    "redstone_block": "レッドストーンブロック",
    "chest": "チェスト",
    "hopper": "ホッパー",
    "iron_pickaxe": "鉄のツルハシ",
    "diamond": "ダイヤモンド",
    "breeze_rod": "ブリーズロッド",
    "quartz": "ネザークォーツ",
    "glowstone_dust": "グロウストーンダスト",
    "amethyst_shard": "アメジストの欠片",
    "blaze_rod": "ブレイズロッド",
    "blaze_powder": "ブレイズパウダー",
    "gunpowder": "火薬",
    "ender_eye": "エンダーアイ",
    "dispenser": "ディスペンサー",
    "crossbow": "クロスボウ",
    "tnt": "TNT",
    "firework_rocket": "ロケット花火",
    "observer": "オブザーバー",
    "tripwire_hook": "トリップワイヤーフック",
    "lead": "リード",
    "compass": "コンパス",
    "spyglass": "望遠鏡",
    "shield": "盾",
    "redstone_torch": "レッドストーントーチ",
    "daylight_detector": "日照センサー",
    "gold_ingot": "金インゴット",
    "netherite_scrap": "ネザライトの欠片",
    "echo_shard": "残響の欠片",
    "tinted_glass": "遮光ガラス",
    "nether_star": "ネザースター",
}

VANILLA_TEXTURES = {
    "glass_pane": "assets/minecraft/textures/block/glass.png",
    "piston": "assets/minecraft/textures/block/piston_top.png",
    "redstone_block": "assets/minecraft/textures/block/redstone_block.png",
    "chest": "assets/minecraft/textures/block/oak_planks.png",
    "dispenser": "assets/minecraft/textures/block/dispenser_front.png",
    "crossbow": "assets/minecraft/textures/item/crossbow_standby.png",
    "tnt": "assets/minecraft/textures/block/tnt_side.png",
    "observer": "assets/minecraft/textures/block/observer_front.png",
    "tripwire_hook": "assets/minecraft/textures/block/tripwire_hook.png",
    "compass": "assets/minecraft/textures/item/compass_16.png",
    "shield": "assets/minecraft/textures/entity/shield/base.png",
    "daylight_detector": "assets/minecraft/textures/block/daylight_detector_top.png",
    "tinted_glass": "assets/minecraft/textures/block/tinted_glass.png",
}

GROUPS = [
    (
        "01",
        "基礎製造",
        "序盤から量産基盤まで。まず合金・フレーム・制御基板を整えます。",
        [
            "raw_morrow_composite",
            "morrow_alloy",
            "basic_control_board",
            "lightweight_frame",
            "flight_actuator",
            "power_cell",
            "standard_battery_pack",
            "reinforced_battery_pack",
            "high_density_battery_pack",
        ],
    ),
    (
        "02",
        "管制・機体・基地設備",
        "機体を展開し、指揮・帰還・充電・回収を成立させる中核装備です。",
        [
            "controller",
            "field_drone_unit",
            "dock_item",
            "solar_service_station",
            "tactical_visor",
            "strike_beacon_launcher",
            "strike_beacon_cartridge",
            "recovery_tool",
            "operation_marker",
        ],
    ),
    (
        "03",
        "任務モジュール",
        "機体の役割を決める5系統。用途に応じて必要数を用意します。",
        ["scout_module", "cargo_module", "engineer_module", "security_module", "salvage_module"],
    ),
    (
        "04",
        "兵装・補給品",
        "兵装本体とDockへ投入する専用補給品。弾種ごとに在庫を分けて管理します。",
        [
            "autocannon_module",
            "autocannon_magazine",
            "laser_module",
            "laser_cell",
            "missile_module",
            "micro_missile_pack",
        ],
    ),
    (
        "05",
        "チャンクバスター母艦",
        "終盤の大型設備。展開装置と遠隔管制端末を別々に製造します。",
        ["carrier_console", "carrier_unit"],
    ),
]

STAGE = {
    "raw_morrow_composite": ("START", "オーバーワールド"),
    "morrow_alloy": ("START", "かまど / 溶鉱炉"),
    "basic_control_board": ("START", "序盤"),
    "lightweight_frame": ("START", "序盤"),
    "flight_actuator": ("START", "序盤"),
    "power_cell": ("START", "序盤"),
    "standard_battery_pack": ("START", "序盤"),
    "controller": ("START", "序盤"),
    "field_drone_unit": ("START", "序盤"),
    "dock_item": ("EXPAND", "拠点拡張"),
    "cargo_module": ("EXPAND", "物流"),
    "engineer_module": ("EXPAND", "作業"),
    "scout_module": ("EXPAND", "索敵"),
    "salvage_module": ("EXPAND", "回収"),
    "recovery_tool": ("EXPAND", "回収"),
    "operation_marker": ("EXPAND", "採掘・伐採"),
    "solar_service_station": ("NETHER", "ネザー到達後"),
    "reinforced_battery_pack": ("NETHER", "ネザー到達後"),
    "security_module": ("NETHER", "ネザー到達後"),
    "autocannon_module": ("NETHER", "ネザー到達後"),
    "autocannon_magazine": ("NETHER", "ネザー到達後"),
    "laser_cell": ("ADVANCED", "中盤以降"),
    "laser_module": ("ADVANCED", "試練の間"),
    "micro_missile_pack": ("ADVANCED", "中盤以降"),
    "missile_module": ("ADVANCED", "中盤以降"),
    "high_density_battery_pack": ("ADVANCED", "試練の間"),
    "tactical_visor": ("ENDGAME", "古代都市 / ネザー"),
    "strike_beacon_launcher": ("NETHER", "警備運用"),
    "strike_beacon_cartridge": ("NETHER", "警備運用"),
    "carrier_console": ("ENDGAME", "母艦管制"),
    "carrier_unit": ("ENDGAME", "ウィザー討伐後"),
}

USAGE = {
    "raw_morrow_composite": ("中間素材", "かまど・溶鉱炉", "燃料と一緒に精錬し、すべてのMorrowgear製造の基礎となるモロウ合金へ加工します。"),
    "morrow_alloy": ("構造材・整備材", "クラフト / Dock整備スロット", "機体・設備の構造材です。Dockでは1個につき機体耐久を20回復し、Security機の搭載量強化にも消費します。"),
    "basic_control_board": ("電子部品", "各種クラフト", "機体、役割モジュール、管制装置、サービスDockなどの制御中枢として使う共通基板です。"),
    "lightweight_frame": ("機体部品", "各種クラフト", "ドローン本体、Dock、役割・兵装モジュールの骨格に使う共通構造部品です。"),
    "flight_actuator": ("飛行部品", "各種クラフト", "姿勢制御と浮上機構を構成します。機体、浮遊サービス充電Dock、チャンクバスターの製造に使います。"),
    "power_cell": ("飛行用補給品", "Dockの電力入力 / 自動補給バッファ", "Dockへ投入すると1個につき飛行電力1200を供給します。浮遊DockやSalvageモジュールの製造にも使います。"),
    "standard_battery_pack": ("容量部品", "Dockの機体・蓄電スロット / 着艦機へ使用", "機体へ装着すると飛行電力容量1000。Dockへ装着すると蓄電上限を6000にします。機体の交換作業は着艦中のみ可能です。"),
    "reinforced_battery_pack": ("容量部品", "Dockの機体・蓄電スロット / 着艦機へ使用", "機体へ装着すると飛行電力容量1800。Dockへ装着すると蓄電上限を10000にします。機体の交換作業は着艦中のみ可能です。"),
    "high_density_battery_pack": ("容量部品", "Dockの機体・蓄電スロット / 着艦機へ使用", "機体へ装着すると飛行電力容量3000。Dockへ装着すると蓄電上限を18000にします。機体の交換作業は着艦中のみ可能です。"),
    "controller": ("指揮装備", "メインハンドで右クリック", "統合戦術画面を開き、機体・Wing・任務・目的地・Dock・補給網・回収・母艦を操作します。電力喪失機の直接回収にも使えます。"),
    "field_drone_unit": ("機体", "右クリック展開 / Dockの機体スロット", "単体ならFIELD機として展開します。Dockでバッテリー・役割・兵装を組み合わせれば構成済み機として就役します。回収済み機体はDock整備後に再出撃します。"),
    "dock_item": ("基地設備", "5×5の空き地へ設置", "機体の就役、動的な帰還予約、充電、再武装、修理、モジュール交換、回収機の復旧、Cargo補給網の拠点になります。"),
    "solar_service_station": ("空中充電設備", "ブロックへ使用して上空に展開", "最大3機へ充電子機を派遣し、周回中の機体へ飛行電力を供給します。弾薬・レーザー電力・修理材は補給しないため通常Dockと使い分けます。"),
    "tactical_visor": ("装着装備", "頭スロットへ装備", "周辺機・Wing・作戦・脅威・交戦相手を状況に応じてHUD表示します。電力喪失ビーコンは距離にかかわらず回収対象として表示します。"),
    "strike_beacon_launcher": ("攻撃指示装備", "左クリックで射出 / 右クリックで再装填", "最大3発を装填できます。ビーコンは同時に3本まで展開でき、所有者のデータリンク網へ距離上限なしで招集を発信します。複数地点では使用可能なSECURITY機を均等配分し、各地点が独立して攻撃を開始します。"),
    "strike_beacon_cartridge": ("専用弾薬", "強襲ビーコンランチャーの再装填", "1個でビーコン1発を再装填します。ランチャーを手に持って右クリックすると、インベントリから最大3発まで装填します。"),
    "recovery_tool": ("回収工具", "所有する電力喪失機へ右クリック", "墜落した機体を状態保持したアイテムへ変換します。耐久値は192で、回収後はDockへ投入して飛行可能になるまで整備します。"),
    "operation_marker": ("作業範囲指定装備", "範囲の境界4点へ順番に使用", "4点を含むX/Y/Z直方体を採掘範囲として登録します。Engineerが掘削し、ドロップは現地へ残り、Cargoが設定済み搬入先へ回収します。スニーク使用で入力途中の点を取り消せます。"),
    "scout_module": ("役割モジュール", "Dockの役割スロット / 着艦中の交換", "SCOUT機へ変更します。周期的な全周スキャン、地形・Mob調査、脅威発見、Wingや全機へのデータリンク通報を担当します。"),
    "cargo_module": ("役割モジュール", "Dockの役割スロット / 着艦中の交換", "CARGO機へ変更します。指定コンテナ間の輸送と、補給不足のDockへ物資を連続配送する物流網を担当します。"),
    "engineer_module": ("役割モジュール", "Dockの役割スロット / 着艦中の交換", "ENGINEER機へ変更します。採掘・作業・修復支援に加え、埋没した電力喪失機の上部を除去してSalvage経路を確保します。"),
    "security_module": ("役割モジュール", "Dockの役割スロット / 着艦中の交換", "SECURITY機へ変更し、警戒・迎撃・護衛・脅威度に応じた戦力配分を有効にします。実戦には兵装モジュールも必要です。"),
    "salvage_module": ("役割モジュール", "Dockの役割スロット / 着艦中の交換", "SALVAGE機へ変更します。電力喪失機を捜索・吊り上げ・牽引し、空きDockへ運んで復旧工程へ引き渡します。"),
    "autocannon_module": ("兵装モジュール", "SECURITY機の兵装スロット / 着艦中の交換", "機関砲によるCAS掃射を有効にします。射撃可能な進入路を選び、弾薬は機関砲弾倉からDockで補充します。"),
    "autocannon_magazine": ("兵装補給品", "Dockの弾薬スロット / 自動補給バッファ", "1個で機関砲弾120発を補給します。Dockに在庫があれば帰還したSecurity機へ必要分だけ移送されます。"),
    "laser_module": ("兵装モジュール", "SECURITY機の兵装スロット / 着艦中の交換", "高出力レーザーを有効にします。射線を確保して照射し、複数機では同一目標へ同期攻撃します。消費電力は兵装電力セルで補充します。"),
    "laser_cell": ("兵装補給品", "Dockの弾薬スロット / 自動補給バッファ", "1個でレーザー兵装電力1000を補給します。飛行電力とは別系統で、照射に使用した分だけDockから移送されます。"),
    "missile_module": ("兵装モジュール", "SECURITY機の兵装スロット / 着艦中の交換", "マイクロミサイルを有効にします。脅威度に応じて複数目標へ最大5発を割り当て、垂直射出から誘導・遅延起爆へ移行します。"),
    "micro_missile_pack": ("兵装補給品", "Dockの弾薬スロット / 自動補給バッファ", "1個でマイクロミサイル5発を補給します。通常素材では代替できない専用補給品です。"),
    "carrier_console": ("母艦管制装備", "右クリック", "船内、または権限を持つ128ブロック以内のチャンクバスターを開き、移動・採掘範囲・攻撃・格納庫・物資・船内機能を操作します。"),
    "carrier_unit": ("大型母艦", "地面へ使用して上空32ブロックに展開", "チャンクバスターを展開します。広域移動、チャンク掘削、範囲攻撃、回収物保管、専用船内、小型ドローンの補給母艦として使用します。"),
}


def load_recipes() -> dict[str, dict]:
    recipes = {}
    for path in sorted(RECIPE_DIR.glob("*.json")):
        recipes[path.stem] = json.loads(path.read_text(encoding="utf-8"))
    return recipes


def load_names() -> dict[str, str]:
    lang = json.loads(LANG_PATH.read_text(encoding="utf-8"))
    names = {f"minecraft:{key}": value for key, value in VANILLA_NAMES.items()}
    for key, value in lang.items():
        if key.startswith("item.morrowgear_drone."):
            names[f"morrowgear_drone:{key.rsplit('.', 1)[-1]}"] = value
    return names


def item_name(item_id: str, names: dict[str, str]) -> str:
    return names.get(item_id, item_id.split(":", 1)[-1].replace("_", " ").title())


def texture_name(item_id: str) -> str:
    namespace, key = item_id.split(":", 1)
    return f"{namespace}__{key}.png"


def prepare_assets(item_ids: set[str]) -> None:
    if ASSET_DIR.exists():
        shutil.rmtree(ASSET_DIR)
    ASSET_DIR.mkdir(parents=True)

    with zipfile.ZipFile(MINECRAFT_JAR) as jar:
        entries = set(jar.namelist())
        for item_id in sorted(item_ids):
            namespace, key = item_id.split(":", 1)
            destination = ASSET_DIR / texture_name(item_id)
            if namespace == "morrowgear_drone":
                source = MOD_TEXTURE_DIR / f"{key}.png"
                if source.exists():
                    shutil.copy2(source, destination)
                elif key in MOD_TEXTURE_FALLBACK and MOD_TEXTURE_FALLBACK[key] in entries:
                    destination.write_bytes(jar.read(MOD_TEXTURE_FALLBACK[key]))
                continue

            candidates = [
                VANILLA_TEXTURES.get(key, ""),
                f"assets/minecraft/textures/item/{key}.png",
                f"assets/minecraft/textures/block/{key}.png",
            ]
            for candidate in candidates:
                if candidate and candidate in entries:
                    destination.write_bytes(jar.read(candidate))
                    break


def icon(item_id: str, names: dict[str, str], count: int = 1, size: str = "normal") -> str:
    name = html.escape(item_name(item_id, names))
    src = f"assets/{texture_name(item_id)}"
    count_html = f'<span class="stack-count">{count}</span>' if count > 1 else ""
    return (
        f'<span class="item-icon item-icon--{size}" title="{name}">'
        f'<img src="{src}" alt="{name}" onerror="this.parentElement.classList.add(\'missing\');this.remove()">'
        f'<span class="fallback">{html.escape(item_id.split(":", 1)[-1][:3].upper())}</span>{count_html}</span>'
    )


def recipe_result(recipe: dict) -> tuple[str, int]:
    result = recipe["result"]
    if isinstance(result, str):
        return result, 1
    return result["id"], int(result.get("count", 1))


def recipe_inputs(recipe: dict) -> list[str]:
    recipe_type = recipe["type"]
    if recipe_type == "minecraft:crafting_shaped":
        return [recipe["key"][symbol] for row in recipe["pattern"] for symbol in row if symbol != " "]
    if recipe_type == "minecraft:crafting_shapeless":
        return list(recipe["ingredients"])
    return [recipe["ingredient"]]


def crafting_grid(recipe: dict, names: dict[str, str]) -> str:
    recipe_type = recipe["type"]
    if recipe_type == "minecraft:crafting_shaped":
        cells = []
        for row in recipe["pattern"]:
            padded = row.ljust(3)
            for symbol in padded[:3]:
                cells.append(icon(recipe["key"][symbol], names) if symbol != " " else '<span class="grid-cell empty"></span>')
        return '<div class="craft-grid">' + "".join(cells) + "</div>"
    if recipe_type == "minecraft:crafting_shapeless":
        ingredients = list(recipe["ingredients"])
        cells = [icon(item_id, names) for item_id in ingredients]
        cells.extend(['<span class="grid-cell empty"></span>'] * (9 - len(cells)))
        return '<div class="craft-grid craft-grid--shapeless">' + "".join(cells[:9]) + "</div>"
    raise ValueError(f"Unsupported grid recipe: {recipe_type}")


def ingredients_summary(recipe: dict, names: dict[str, str]) -> str:
    counts = Counter(recipe_inputs(recipe))
    chips = []
    for item_id, count in counts.items():
        chips.append(
            '<span class="ingredient-chip">'
            + icon(item_id, names, count=count, size="tiny")
            + f'<span>{html.escape(item_name(item_id, names))}</span>'
            + "</span>"
        )
    return '<div class="ingredient-list">' + "".join(chips) + "</div>"


def usage_block(recipe_key: str) -> str:
    category, location, description = USAGE[recipe_key]
    return f"""
        <div class="usage-block">
          <div class="usage-meta"><span>{html.escape(category)}</span><strong>使う場所</strong><b>{html.escape(location)}</b></div>
          <p>{html.escape(description)}</p>
        </div>
    """


def card(recipe_key: str, recipe: dict, names: dict[str, str]) -> str:
    output_id, output_count = recipe_result(recipe)
    output_name = item_name(output_id, names)
    stage, unlock = STAGE[recipe_key]
    kind = "不定形" if recipe["type"] == "minecraft:crafting_shapeless" else "3 × 3"
    return f"""
      <article class="recipe-card" data-stage="{stage}" data-search="{html.escape(output_name)}">
        <header class="recipe-card__head">
          <div>
            <span class="eyebrow">{stage} · {html.escape(unlock)}</span>
            <h3>{html.escape(output_name)}</h3>
          </div>
          <span class="method">{kind}</span>
        </header>
        <div class="recipe-assembly">
          {crafting_grid(recipe, names)}
          <span class="assembly-arrow" aria-hidden="true">›</span>
          <div class="recipe-output">
            {icon(output_id, names, count=output_count, size="large")}
            <span>完成 × {output_count}</span>
          </div>
        </div>
        {ingredients_summary(recipe, names)}
        {usage_block(recipe_key)}
      </article>
    """


def furnace_card(recipes: dict[str, dict], names: dict[str, str]) -> str:
    smelting = recipes["morrow_alloy_smelting"]
    blasting = recipes["morrow_alloy_blasting"]
    input_id = smelting["ingredient"]
    output_id, _ = recipe_result(smelting)
    return f"""
      <article class="recipe-card recipe-card--furnace" data-stage="START" data-search="モロウ合金">
        <header class="recipe-card__head">
          <div>
            <span class="eyebrow">START · 熱処理</span>
            <h3>{html.escape(item_name(output_id, names))}</h3>
          </div>
          <span class="method">2経路</span>
        </header>
        <div class="heat-flow">
          <div class="heat-input">{icon(input_id, names, size="large")}<span>未焼結材</span></div>
          <div class="heat-methods">
            <div><span class="furnace-symbol">▦</span><strong>かまど</strong><small>{smelting['cookingtime'] // 20}秒</small></div>
            <div><span class="furnace-symbol">▣</span><strong>溶鉱炉</strong><small>{blasting['cookingtime'] // 20}秒</small></div>
          </div>
          <span class="assembly-arrow" aria-hidden="true">›</span>
          <div class="recipe-output">{icon(output_id, names, size="large")}<span>完成 × 1</span></div>
        </div>
        <p class="recipe-note">燃料は通常のかまど・溶鉱炉と同じ。溶鉱炉なら処理時間が半分になります。</p>
        {usage_block("morrow_alloy")}
      </article>
    """


def render_html(recipes: dict[str, dict], names: dict[str, str]) -> str:
    recipe_count = len(recipes)
    output_count = len({recipe_result(recipe)[0] for recipe in recipes.values()})
    sections = []
    for number, title, description, keys in GROUPS:
        cards = []
        for key in keys:
            if key == "morrow_alloy":
                cards.append(furnace_card(recipes, names))
            else:
                cards.append(card(key, recipes[key], names))
        sections.append(
            f"""
            <section class="recipe-section" id="section-{number}">
              <div class="section-heading"><span>{number}</span><div><h2>{title}</h2><p>{description}</p></div></div>
              <div class="recipe-grid">{''.join(cards)}</div>
            </section>
            """
        )

    return f"""<!doctype html>
<html lang="ja">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Morrowgear Recipe Systems</title>
  <style>
    :root {{ --bg:#09100f; --panel:#111b19; --panel2:#162320; --line:#31463f; --text:#eef7f2; --muted:#91a79f; --cyan:#63e7d2; --mint:#baf7bd; --amber:#ffbd59; --red:#ff6d68; }}
    * {{ box-sizing:border-box; }}
    body {{ margin:0; color:var(--text); background:radial-gradient(circle at 72% 0%, #19302a 0, transparent 28rem), var(--bg); font-family:"Yu Gothic UI","Noto Sans JP",sans-serif; letter-spacing:0; }}
    img {{ image-rendering:pixelated; }}
    .topbar {{ position:sticky; top:0; z-index:20; height:64px; padding:0 3vw; display:flex; align-items:center; justify-content:space-between; background:rgba(7,13,12,.94); border-bottom:1px solid var(--line); backdrop-filter:blur(14px); }}
    .brand {{ display:flex; align-items:center; gap:14px; }}
    .brand-mark {{ width:7px; height:34px; background:var(--cyan); box-shadow:0 0 20px rgba(99,231,210,.45); }}
    .brand strong {{ font-size:15px; }} .brand small {{ display:block; color:var(--cyan); font-size:9px; margin-top:3px; }}
    .top-meta {{ color:var(--muted); font-size:12px; }} .top-meta b {{ color:var(--amber); font-weight:600; }}
    main {{ width:min(1760px, 95vw); margin:0 auto; padding:42px 0 80px; }}
    .hero {{ display:grid; grid-template-columns:minmax(0,1.35fr) minmax(360px,.65fr); gap:46px; align-items:end; padding:36px 0 44px; border-bottom:1px solid var(--line); }}
    .kicker,.eyebrow {{ color:var(--cyan); font-size:10px; font-weight:700; text-transform:uppercase; }}
    h1 {{ font-size:clamp(38px,5vw,78px); line-height:1; margin:14px 0 18px; font-weight:780; }}
    .hero p {{ color:var(--muted); max-width:760px; line-height:1.8; margin:0; }}
    .hero-stats {{ display:grid; grid-template-columns:repeat(3,1fr); border:1px solid var(--line); background:rgba(17,27,25,.74); }}
    .hero-stats div {{ padding:20px; border-right:1px solid var(--line); }} .hero-stats div:last-child{{border-right:0}}
    .hero-stats strong {{ display:block; font-size:27px; color:var(--amber); }} .hero-stats span {{ color:var(--muted); font-size:11px; }}
    .flow {{ margin:38px 0 58px; padding:24px; border:1px solid var(--line); background:linear-gradient(135deg,rgba(21,34,31,.94),rgba(10,17,16,.94)); }}
    .flow h2 {{ font-size:14px; margin:0 0 18px; color:var(--mint); }}
    .flow-line {{ display:grid; grid-template-columns:repeat(7,1fr); gap:8px; align-items:center; }}
    .flow-node {{ min-height:74px; padding:13px; border-left:3px solid var(--cyan); background:#0c1513; display:flex; flex-direction:column; justify-content:center; }}
    .flow-node strong {{ font-size:12px; }} .flow-node small {{ color:var(--muted); margin-top:6px; line-height:1.35; }}
    .flow-node.final {{ border-color:var(--amber); }}
    .flow-arrow {{ text-align:center; color:var(--amber); font-size:22px; }}
    .recipe-section {{ margin:62px 0 0; }}
    .section-heading {{ display:flex; gap:18px; align-items:flex-start; margin-bottom:20px; }}
    .section-heading>span {{ color:var(--amber); font-size:12px; border:1px solid #745b32; padding:7px 9px; }}
    .section-heading h2 {{ margin:0; font-size:24px; }} .section-heading p {{ margin:7px 0 0; color:var(--muted); font-size:13px; }}
    .recipe-grid {{ display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:14px; }}
    .recipe-card {{ min-width:0; border:1px solid var(--line); background:linear-gradient(160deg,rgba(22,35,32,.97),rgba(11,18,17,.98)); padding:18px; box-shadow:0 18px 50px rgba(0,0,0,.15); display:flex; flex-direction:column; }}
    .recipe-card__head {{ display:flex; justify-content:space-between; min-height:60px; gap:12px; border-bottom:1px solid #263b35; }}
    .recipe-card h3 {{ font-size:15px; margin:7px 0 15px; }} .method {{ color:var(--muted); font-size:10px; border:1px solid var(--line); height:24px; padding:5px 8px; }}
    .recipe-assembly {{ min-height:190px; display:grid; grid-template-columns:158px 28px 1fr; align-items:center; gap:8px; padding:16px 0; }}
    .craft-grid {{ width:158px; height:158px; display:grid; grid-template-columns:repeat(3,1fr); grid-template-rows:repeat(3,1fr); gap:4px; padding:7px; background:#07100e; border:1px solid #314a43; }}
    .grid-cell.empty,.item-icon {{ position:relative; display:flex; align-items:center; justify-content:center; background:#15211e; border:1px solid #2b3f39; min-width:0; }}
    .grid-cell.empty {{ opacity:.42; }} .item-icon img {{ width:78%; height:78%; object-fit:contain; filter:drop-shadow(0 3px 4px rgba(0,0,0,.55)); }}
    .item-icon .fallback {{ display:none; color:var(--cyan); font-size:10px; }} .item-icon.missing .fallback {{ display:block; }}
    .stack-count {{ position:absolute; right:3px; bottom:0; font-size:12px; font-weight:800; text-shadow:1px 1px #000,-1px -1px #000; }}
    .assembly-arrow {{ color:var(--amber); font-size:36px; text-align:center; }}
    .recipe-output {{ display:flex; flex-direction:column; align-items:center; gap:9px; color:var(--muted); font-size:10px; }}
    .item-icon--large {{ width:88px; height:88px; background:radial-gradient(circle,#274139,#101916 66%); border-color:#4d6a61; }}
    .item-icon--tiny {{ width:25px; height:25px; flex:0 0 25px; background:#0c1513; }} .item-icon--tiny .stack-count {{ font-size:9px; }}
    .ingredient-list {{ min-height:74px; display:flex; flex-wrap:wrap; align-content:flex-start; gap:6px; border-top:1px solid #263b35; padding-top:12px; }}
    .ingredient-chip {{ display:flex; align-items:center; gap:6px; padding:3px 7px 3px 3px; background:#0d1715; color:#afc0ba; font-size:9px; border:1px solid #253933; }}
    .usage-block {{ margin-top:14px; padding:13px 14px; border-left:3px solid var(--amber); background:#0a1311; }}
    .usage-meta {{ display:grid; grid-template-columns:auto auto 1fr; gap:8px; align-items:center; font-size:9px; }}
    .usage-meta>span {{ color:#08100e; background:var(--mint); padding:4px 6px; font-weight:800; }}
    .usage-meta>strong {{ color:var(--muted); font-weight:600; }} .usage-meta>b {{ color:var(--amber); font-weight:700; }}
    .usage-block p {{ margin:10px 0 0; color:#c8d6d1; font-size:11px; line-height:1.65; }}
    .heat-flow {{ min-height:190px; display:grid; grid-template-columns:1fr 1.2fr 24px 1fr; gap:12px; align-items:center; }}
    .heat-input {{ display:flex; flex-direction:column; align-items:center; gap:8px; color:var(--muted); font-size:10px; }}
    .heat-methods {{ display:grid; gap:8px; }} .heat-methods div {{ display:grid; grid-template-columns:30px 1fr auto; align-items:center; gap:7px; border:1px solid var(--line); padding:8px; background:#0c1513; }}
    .heat-methods strong {{ font-size:11px; }} .heat-methods small {{ color:var(--amber); }} .furnace-symbol {{ color:var(--cyan); font-size:20px; }}
    .recipe-note {{ color:var(--muted); font-size:10px; line-height:1.6; border-top:1px solid #263b35; padding-top:12px; margin:0; }}
    footer {{ margin-top:70px; padding-top:22px; border-top:1px solid var(--line); color:var(--muted); font-size:11px; display:flex; justify-content:space-between; }}
    @media (max-width:1200px) {{ .recipe-grid{{grid-template-columns:repeat(2,minmax(0,1fr))}} .flow-line{{grid-template-columns:1fr}} .flow-arrow{{transform:rotate(90deg)}} }}
    @media (max-width:720px) {{ main{{width:92vw;padding-top:18px}} .hero{{grid-template-columns:1fr;gap:24px}} .top-meta{{display:none}} .recipe-grid{{grid-template-columns:1fr}} .recipe-card{{padding:14px}} .recipe-assembly{{grid-template-columns:148px 22px 1fr}} .craft-grid{{width:148px;height:148px}} .hero-stats div{{padding:13px}} .hero-stats strong{{font-size:20px}} footer{{display:block;line-height:1.8}} }}
  </style>
</head>
<body>
  <header class="topbar"><div class="brand"><span class="brand-mark"></span><div><strong>MORROWGEAR</strong><small>CRAFTING SYSTEMS / MC 26.2</small></div></div><div class="top-meta">RECIPE DEFINITIONS <b>{recipe_count}</b> · CRAFTABLE OUTPUTS <b>{output_count}</b></div></header>
  <main>
    <section class="hero">
      <div><span class="kicker">FABRICATION REFERENCE / v0.20.6</span><h1>製造レシピ<br>ビジュアルガイド</h1><p>ゲームに収録されているレシピJSONから自動生成した一覧です。配置、必要数、完成数、熱処理時間を実データどおりに表示しています。</p></div>
      <div class="hero-stats"><div><strong>{recipe_count}</strong><span>レシピ定義</span></div><div><strong>{output_count}</strong><span>製造アイテム</span></div><div><strong>{len(GROUPS)}</strong><span>製造カテゴリ</span></div></div>
    </section>
    <section class="flow"><h2>推奨製造フロー</h2><div class="flow-line"><div class="flow-node"><strong>未焼結モロウ材</strong><small>鉄・銅・赤石・木炭</small></div><div class="flow-arrow">›</div><div class="flow-node"><strong>モロウ合金</strong><small>かまど / 溶鉱炉</small></div><div class="flow-arrow">›</div><div class="flow-node"><strong>基礎部品</strong><small>基板・フレーム・アクチュエーター</small></div><div class="flow-arrow">›</div><div class="flow-node final"><strong>機体と運用設備</strong><small>Drone・Dock・任務・兵装・母艦</small></div></div></section>
    {''.join(sections)}
    <footer><span>SOURCE: src/main/resources/data/morrowgear_drone/recipe</span><span>Generated from the shipped game data. No inferred substitutions.</span></footer>
  </main>
</body>
</html>
"""


def main() -> None:
    recipes = load_recipes()
    names = load_names()
    item_ids: set[str] = set()
    for recipe in recipes.values():
        item_ids.update(recipe_inputs(recipe))
        item_ids.add(recipe_result(recipe)[0])
    prepare_assets(item_ids)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    # Keep generated documentation stable across Windows and Unix checkouts.
    with (OUTPUT_DIR / "index.html").open("w", encoding="utf-8", newline="\n") as output:
        rendered = render_html(recipes, names)
        output.write("\n".join(line.rstrip() for line in rendered.splitlines()) + "\n")
    print(f"Generated {OUTPUT_DIR / 'index.html'} from {len(recipes)} recipe definitions")


if __name__ == "__main__":
    main()
