# Chunkbuster V27 正本・ゲーム資産

ユーザーが承認した V27 デザイン画像を基に、全長 50 ブロックで再構成した母艦です。却下された V24 / V25 / V26 の船体を流用せず、中央の細長い装甲区画、左右に埋め込まれた飛行甲板、奥行きのある後部格納庫、双発機関、機腹照射口を正本のポリゴンで構成しています。画像の 70 m 表記をそのまま縮小せず、既存小型機と補給口の実寸を優先しています。

最新 revision は `V27_FUNCTIONAL_DETAIL_01`。承認シルエットと寸法・4 補給口の空間契約を維持し、補給・照射・搭乗・推進の役割が外観から読めるように構造を更新しました。

## 正本と確認画像

- `carrier-v27.blend` / `carrier-v27.glb`: 母艦本体のみの正本。全メッシュは共通の X=0 を使う編集可能な Mirror 構造です。
- `hero.png` / `front.png` / `rear.png` / `top.png` / `bottom.png` / `left.png` / `right.png`: 同じ正本の 7 方向。
- `contact-sheet.png`: 7 方向の一覧。
- `reference-comparison.png`: 承認画像と 50 ブロック正本の比較。画像内の 70 は承認元画像の原表記です。
- `scale-comparison.png` / `deck-detail.png`: V22 の既存小型機を無倍率で 4 機配置した確認用画像。
- `carrier-v27-review-only.blend`: 小型機を含む確認専用ファイル。ゲーム資産の入力には使用しません。
- `belly-hero.png`: 中央照射口と 4 補給口の確認。
- `carrier-mgm-readback.png` / `carrier_lod-mgm-readback.png`: 実際の MGM4 と atlas を読み戻して描画した画像。ゲーム画面ではありません。
- `mobile-summary.png`: モバイル表示用の縦長まとめ。
- `before-after.png`: 同一カメラでの変更前後。旧正本・出荷資産は `before-functional-detail/` に保存。
- `functional-details.png`: 補給口、中央照射口、後部搭乗受入口、推進器の接写一覧。個別画像は `service-bay-detail.png` / `emitter-detail.png` / `boarding-detail.png` / `propulsion-detail.png`。

中央装甲は艦首まで伸びる chine と段階的なセンサー区画を持ち、飛行甲板には整備パネル、排水溝、レーン表示、側壁配管と照明を配置しました。後部の機関区画は前方装甲へ斜面で接続し、機腹にも整備パネル、補強材、排熱部を追加しています。レーンの幾何学的な数字も鏡像化されるため、左右で並びが反転します。

今回、肩装甲の整備面は船体に実際の凹部を設けて埋め込み、機腹の整備面も船体の断面に沿わせました。塗膜の色・roughness に小さな差を持たせています。roughness 自体は Blender 材質であり、ゲーム用 atlas は色成分を bake したものです。

## 実機能との対応

| 外観の構造 | 対応する機能・制約 |
| --- | --- |
| アンバー枠の 4 補給口、庫内ガイド、電力端子・弾薬側ポート、配管 | 既存の電力・弾薬補給と整備。垂直進入域を塞がない位置に配置 |
| 中央機腹の多段照射口、光学固定環、冷却配管、支持部 | 採掘と攻撃に共通の中央照射口。別の砲塔武装は追加しない |
| 後部 `Z=18` のシアン枠受入口と案内矢印 | 地表から船内への既存転送式搭乗。動くリフトやタラップではない |
| 双発矩形排気口、整流部、上面熱交換器 | 飛行機関と放熱を表す外観。新しい推力計算は追加しない |

用途のない砲身形状は削除し、同じ低い台座を航法・監視光学部として整理しました。地表の搭乗マーカーと安全 pad 同期は親担当です。

## 寸法・座標契約

ゲーム座標は `+X=右、+Y=上、-Z=前、+Z=後`。1 単位が 1 ブロックで、追加の `0.2` 倍率や 180 度補正は不要です。原点は機腹下端です。

| 項目 | 確定値 |
| --- | --- |
| 幅・高さ・全長 | 26 × 11 × 50 |
| runtime AABB | `[-13,0,-25] .. [13,11,25]` |
| 正本の軸 | Blender `+Z=上、-Y=前` |
| 座標変換 | Blender `(x,y,z)` → runtime `(x,z,y)`、三角形の頂点順序を反転 |
| 照射基準 | `(0,-0.1,0)` |
| 搭乗受入口の地面投影 | `(0,0,18)`。3×3 の垂直進入範囲を `Y=4.4` まで確保。実際の地面高さ・安全判定は backend 側 |

4 補給口の位置は以前の契約を維持しています。進入点は同じ XZ の `Y=-2`、各口の必要空間は `3×3`、高さは `Y=2.4..3.35` です。

| slot | runtime XYZ |
| --- | --- |
| 0 | `(-7.15,2.4,-5.2)` |
| 1 | `(7.15,2.4,-5.2)` |
| 2 | `(-3.6,2.4,7.8)` |
| 3 | `(3.6,2.4,7.8)` |

甲板上の見本 4 機は大きさの確認専用です。補給 slot の追加や runtime 常設機を意味しません。上面の格納庫は船内空間を表す構造であり、補給判定の入口は機腹の 4 口です。

## ゲーム用出力

すべて `src/main/resources/assets/morrowgear_drone/` 以下です。

| 資産 | 内容 |
| --- | --- |
| `models/runtime/carrier.mgm` | MGM4、本体 23,288 三角形、予算 25,000 以下 |
| `textures/runtime/carrier.png` | 正本材質の diffuse-color bake、2048×2048 |
| `models/runtime/carrier_lod.mgm` | 小部品を除いた軽量版、12,384 三角形、予算 15,000 以下 |
| `textures/runtime/carrier_lod.png` | 軽量版専用 atlas、1024×1024 |
| `items/carrier_unit.json` / `models/item/carrier_unit.json` | 母艦ユニットの定義 |
| `textures/item/carrier_unit.png` | 同じ母艦正本からの 128×128 描画 |
| `items/carrier_console.json` / `models/item/carrier_console.json` | 母艦端末の定義 |
| `textures/item/carrier_console.png` | 既存 V23 controller 正本由来の 128×128 描画を再利用 |

MGM4 は既存 `RuntimeMesh` の形式です。ダミー rotor center は 2 個、全頂点 `group=0`、base / gear / action の位置・法線は同一です。発光 flag `1` は兵装に連動するシアン、flag `2` は atlas 色を維持するアンバーと搭乗用シアンです。搭乗灯は兵装色に変化しません。軽量化では鏡像を崩す decimate を使用せず、指定した小部品単位で除外しています。現在の `CarrierRenderer` の LOD 切替距離は 160 ブロック超で、距離判定は renderer 側の責務です。

コンソールの手持ち表示は親担当の `EquipmentItemModel` が既存 `controller` に対応付けます。補給品 3 種や既存 renderer、backend、既存小型モデルはこの作業で変更していません。

## 再生成

リポジトリ直下で実行します。ゲーム起動・インストールは行いません。

```powershell
& '../work/blender-portable/blender-5.2.0-windows-x64/blender.exe' --background --python-exit-code 1 --python tools/drone-design/blender/build_carrier_v27.py
```

`-- --only native` / `runtime` / `comparison` / `verify` で工程を指定できます。`native` は正本と画像、`runtime` は `staged-assets/` 内の候補資産、`comparison` は小型機比較、`verify` は最終読み戻し検査と詳細・変更前後画像を生成します。`verify` の全工程が成功した後だけ、検証済み MGM4 2 個・atlas 2 枚・ユニットアイコンを resources へ切り替えます。`published-assets.json` に出荷ファイルの SHA-256 を記録します。

このフォルダ内の `hero.png` と `validation.json` は最新の `V27_FUNCTIONAL_DETAIL_01` 正本を指します。初期ブロックアウトの承認済み記録を意味しません。

検査の範囲と未実施事項は [VERIFICATION.md](VERIFICATION.md) を参照してください。
