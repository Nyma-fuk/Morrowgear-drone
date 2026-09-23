# 母艦 V30 新規デザイン提案

承認前の独立した Blender 提案です。不採用となった V29 は変更せず保存し、既存の正本、ランタイム、サーバー、ゲームには反映しません。

## 設計意図

中央の貫通リングを廃止し、機首から後部まで閉じた低い装甲上面を採用します。横幅50ブロックの段階的なスパン、鋭い前縁、機能ごとの大小装甲、下面埋込エミッター、4つの実機対応ベイ、左右4基ずつの大型後部推進器で構成します。

上面の区画は指揮、前方センサー、主電子機器、電力分配、荷重支持、外翼センサー、翼端整備、熱交換の用途別です。均等なタイル格子は使用しません。色と名称は本提案限りで、正式ブランド色や製品名を決定するものではありません。

## 座標と契約

Blender は前方 -Y、上方 +Z、左右 X。1単位を1ブロックとして設計し、構造部品は X=0 の共通 Mirror Modifier を使用します。`beamOrigin` は Blender `(0,0,-0.1)`、ゲームローカル `(0,-0.1,0)` を維持します。

実機比較には `docs/design/detail-scale-v22` の Field / Scout / Cargo / Engineer / Security / Salvage 正本GLBを1:1で使用します。これらは格納脚を畳んだフレーム40の静的書き出しです。

## 構成

| 部位 | 提案内容 |
|---|---|
| 主船体 | 最大幅50、全長30.93。中心から翼端まで連続した多角形モノコック |
| 上面 | 閉鎖した低い装甲面。16枚の機能別大区画と2系統の埋込熱交換帯 |
| 指揮部 | 機首寄りの低い多面体ブリスター。小窓と点検ハッチで実寸感を表現 |
| 前方下面インテーク | X=0の中央に独立配置した幅9.60の収束ダクト。整備ベイ、照射口とは別系統 |
| ベイ | X=±10、±15の4室。前下面開口、有効3.60幅 × 4.40奥行 × 2.40高さ |
| 下面 | スパントラス、整備カセット、保護配管、中心埋込エミッター |
| 推進器 | 左右4基ずつ。船体を貫くダクト、奥の発光面、偏向ベーン、アクチュエーター |

巨大照射口は上面へ貫通しません。`beam-state.png` のビームは提示専用で、保存した母艦 `.blend` / `.glb` には含みません。

## 実機適合

| ロール | 実機 幅 × 奥行 × 高さ | ベイとの差 幅 / 奥行 / 高さ | 判定 |
|---|---:|---:|---:|
| Field | 3.000 × 2.246 × 0.581 | 0.600 / 2.154 / 1.819 | 適合 |
| Scout | 3.000 × 1.979 × 0.511 | 0.600 / 2.421 / 1.889 | 適合 |
| Cargo | 3.000 × 2.158 × 0.612 | 0.600 / 2.242 / 1.788 | 適合 |
| Engineer | 3.000 × 2.201 × 0.569 | 0.600 / 2.199 / 1.831 | 適合 |
| Security | 3.000 × 2.131 × 0.549 | 0.600 / 2.269 / 1.851 | 適合 |
| Salvage | 3.000 × 2.077 × 0.589 | 0.600 / 2.323 / 1.811 | 適合 |

寸法差は両側・前後・上下を合わせた総余裕です。6機は同じ右内側ベイへ1:1で置いて個別レンダーし、Field / Scout / Cargo / Engineerは4ベイへ同時配置しました。機体の全姿勢、動的進入経路、ゲーム内当たり判定は承認後の実装工程で確認が必要です。

## 数値検証

- 実寸: 50.000 × 30.926 × 7.250
- 評価後形状: 13,715頂点、10,894ポリゴン
- 左右反転頂点の最大誤差: 0
- 非多様体エッジ: 0
- 上面中心: 閉鎖
- 4ベイ入口: 36 / 36サンプル通過
- 前方下面インテーク: 存在確認、ベイと分離、6 / 6進入サンプル通過
- 8推進ダクト: 16 / 16サンプル通過
- GLB再読込: 46,158頂点、元モデル最近傍頂点差0、外形差0
- 既存リソース、V28、V29の計379ファイル: 生成前後のSHA-256一致
- Blender生成と再読込: 終了コード0、Tracebackなし

GLB検査は位置と外形の検査であり、トポロジーや材質の完全同一性を証明するものではありません。

## 成果物

- `carrier-v30-proposal.blend` / `.glb`: 提案正本と交換モデル。ランタイム用ではありません。
- `hero.png`: ニュートラル背景の主提案画像。
- `front/rear/top/bottom/left/right.png`: 同一メッシュの6方向。ヒーローを含め7ビュー。
- `underside.png` / `rear-underside.png`: 下面構造と推進器の追加確認。
- `contact-sheet.png`: 全方向一覧。
- `beam-state.png`: 垂直照射の提示状態。
- `before-after.png` / `refinement-comparison.png`: 不採用V29との比較。
- `bay-scale-comparison.png`: 6ロールを同じベイへ置いた1:1比較。
- `bay-loaded-overview.png`: 4ベイ同時搭載。
- `bay-loaded-security-salvage.png`: 残る2ロールの搭載確認。
- `front-under-functional-closeup.png`: 前方下面インテークと4ベイの位置関係。
- `functional-comparison-ja.png`: インテーク、ベイ、照射口の日本語ラベル比較。
- `validation.json` / `bay-role-fit.json` / `glb-readback.json`: 数値検証。
- `output-checks.json` / `artifacts.json`: 画像検査、既存ファイル不変、出力ハッシュ。
- `build.log` / `verify.log`: Blender実行記録。

個別ベイ画像は拡大確認のため母艦全景を意図的に画面外へ切り取っています。全景画像11枚は欠けなし、比較画像を含む全18枚は非空です。

```powershell
& '../work/blender-portable/blender-5.2.0-windows-x64/blender.exe' --background --threads 12 --python-exit-code 1 --python tools/drone-design/blender/build_carrier_v30_proposal.py
& '../work/blender-portable/blender-5.2.0-windows-x64/blender.exe' --background --threads 12 --python-exit-code 1 --python tools/drone-design/blender/build_carrier_v30_proposal.py -- --verify
```
