# 母艦 V31 リング統合案

承認済みのV31外装正本と、その実ゲーム向け公開記録です。近距離・遠距離モデル、テクスチャ、アイコン、当たり判定、補給ベイ座標、航跡噴出口を同じ座標契約でランタイムへ反映しています。

## 設計方針

V30の50m級低翼面、埋込4ベイ、中央前方インテーク、左右4基ずつの後部推進器を基礎にしています。中央部は閉鎖甲板ではなく、上下へ実際に貫通する採掘リングへ再構成しました。

リング外周は低い面取り付き圧力フレームです。上面は16分割ベゼル、下面は16基の発光エミッター凹部を備え、前後左右の装甲フィーダーと荷重リブで主船体へ接続します。独立した円環を甲板へ載せた構造ではありません。

## 座標と主要寸法

- 前方: `-Y`
- 上方: `+Z`
- 厳密左右対称面: `X=0`
- 全体寸法: `50.000 × 30.926 × 6.950`
- 採掘リング: 外径12.7、内径9.1、中心 `(0, 0.8)`
- 整備ベイ: `X=±10, ±15`、各室の有効寸法 `3.60 × 4.40 × 2.40`
- 前方下面インテーク: ベイとは独立した中央収束ダクト
- 後部推進器: 8ダクト

## 構成と状態

詳細は `component-state-spec.json` に記録しています。

| 構成 | 内容 |
|---|---|
| 飛行船体 | 多角形ロフトによる50m連続モノコック |
| 採掘リング | 面取り付き貫通圧力フレーム、16分割上面ベゼル |
| 採掘エミッター | 下面環状部へ16基を埋込。上面窓と同一チャネルを表現 |
| 荷重系 | 四象限の装甲フィーダー、放射荷重リブ、下面トラス |
| 整備ベイ | 前下面へ4室を埋込。誘導灯、床、ドッキングレール付き |
| 吸気・推進 | 中央前方下面インテーク、後部8基ベクターダクト |

状態は `transit`、`miningReady`、`miningActivePresentation` の3種です。`mining-active-state.png` の垂直ビームは提示専用で、正本 `.blend` と `.glb` には含みません。

## 検証結果

- 左右反転頂点の最大誤差: `0`
- 評価後形状: `15,219`頂点、`12,194`ポリゴン
- 非多様体エッジ: `0`
- 中央開口: 上下方向 `5 / 5`サンプル通過
- エミッター: 論理数`16`、上面窓`16`、下面凹部`16`
- 4ベイ進入: `36 / 36`サンプル通過
- インテーク進入: `6 / 6`サンプル通過
- V22実機6ロール: 1:1格納姿勢で全機適合
- GLB再読込: `50,656`頂点、最近傍頂点差`0`、外形差`0`
- 既存保護対象381ファイル: SHA-256一致、変更なし
- Blender生成・再読込: 終了コード0、Tracebackなし
- 近距離ランタイム: `24,722`三角形（上限`25,000`）
- 遠距離ランタイム: `13,794`三角形（上限`15,000`）
- ランタイム左右対称性、寸法、発光信号、静的姿勢: 合格

## 成果物

- `carrier-v31-ring-proposal.blend`: 正本Blenderファイル
- `carrier-v31-ring-proposal.glb`: 提案交換モデル
- `hero.png`: 横長シルエットとリング統合状態
- `front.png` / `rear.png` / `top.png` / `bottom.png` / `left.png` / `right.png` / `underside.png`: 同一メッシュ七方向
- `bottom-functional-closeup.png`: 下面機能配置
- `ring-functional-closeup.png`: 貫通内壁と16基エミッター
- `bay-intake-functional-closeup.png`: 4ベイと中央インテーク
- `mining-active-state.png`: 提示専用採掘状態
- `contact-sheet.png`: ヒーローと七方向一覧
- `functional-sheet-ja.png`: 日本語機能比較
- `bay-scale-witness.png`: 実機4室同時搭載
- `component-state-spec.json`: 部品・接続・状態仕様
- `validation.json`: 幾何・機能検証
- `bay-role-fit.json`: 6ロール実機寸法適合
- `glb-readback.json`: GLB再読込検証
- `output-checks.json`: 画像と既存ファイル不変検査
- `build.log` / `verify.log`: Blender実行記録
- `runtime/carrier-v31-runtime-source.blend`: 公開用に提示専用物を除いた正本
- `runtime/published-assets.json`: 公開先資産のハッシュと近距離・遠距離検証結果
- `runtime/carrier-runtime-validation.json` / `carrier_lod-runtime-validation.json`: 変換後MGM4検査

`tools/drone-design/blender/publish_carrier_v31.py` が、この正本からゲーム資産を再現可能な形で生成・検証・公開します。整備ステンシルとベイ床表示は輪郭を変えない浅いプレートのため、ゲーム用メッシュではアトラス表現へ統合しています。
