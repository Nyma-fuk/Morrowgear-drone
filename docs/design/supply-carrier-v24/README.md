# V24：補給品・チャンクバスターの造形提案

**更新：V24母艦外観はユーザーに却下された。詳細は [REJECTED.md](REJECTED.md)。正本と画像は履歴として保存し、新母艦案は `../carrier-v25/` に分離する。補給品3種は変更せず、個別承認待ちを維持する。**

**提案・未承認。補給品と母艦は別々に画像を提示して承認を得る。このフォルダーの成果物だけを根拠にゲームへ導入しない。**

補給品3種と母艦1種のネイティブ3Dモデルを提案する。既存のV22/V23正本、ゲーム用リソース、描画処理、アイテム登録、Launcher、既存ワールドは変更しない。追加した生成コードは `tools/drone-design/blender/build_supply_carrier_v24.py` のみ。

## 承認用画像

- [モバイル用まとめ](proposal-mobile.png)
- [参照画像との比較](reference-comparison.png)
- [補給品アイコンの実寸](native-icons.png)
- [補給品だけの承認用画像](supplies-approval.png)：母艦とは別に承認を取得
- [母艦の状態・接点](states-contact.png)
- [母艦の7方向図](models/chunkbuster/contact-sheet.png)
- [4ベイの配置と座標](bay-layout.png)

PNGはすべて正本メッシュから描画したもの。画像だけで3Dを代用しない。比較シートはBlenderの画像付き平面を描画して組み立て、縦横比を保持する。Pythonによるラスター画像編集は行わない。

## 正本ファイル

| 提案ID | 用途 | 正本・交換形式 | 識別形状 |
| --- | --- | --- | --- |
| `autocannon_magazine` | 120発補給 | [Blend](models/autocannon_magazine/autocannon_magazine.blend), [GLB](models/autocannon_magazine/autocannon_magazine.glb) | 中央が抜けた湾曲ベルトカセット、露出した連結弾、アンバーの解除部 |
| `laser_cell` | 兵装電力1000 | [Blend](models/laser_cell/laser_cell.blend), [GLB](models/laser_cell/laser_cell.glb) | 縦長の銀色蓄電器、軸方向の放熱板、シアンの充電窓、2端子 |
| `micro_missile_pack` | ミサイル5発 | [Blend](models/micro_missile_pack/micro_missile_pack.blend), [GLB](models/micro_missile_pack/micro_missile_pack.glb) | 実形状の5筒を3+2に配置、暗い封止面、小さいアンバー表示 |
| `chunkbuster` | 大型補給母艦 | [Blend](models/chunkbuster/chunkbuster.blend), [GLB](models/chunkbuster/chunkbuster.glb) | 幅48の一体翼、機腹中央レンズ、埋込ファン2基、実際に開いた4ベイ |

各モデルのフォルダーに `hero`、`front`、`rear`、`top`、`bottom`、`left`、`right` のPNG、一覧画像、検証JSONを格納する。補給品は16・32・64・1024pxをそれぞれ描画する。母艦には機腹俯瞰、閉鎖、サービス、稼働確認の画像と、閉鎖状態の別GLBも含む。

`laser_cell` の表示名は **兵装電力セル / Weapon Energy Cell**。レーザーだけでなく、ガン駆動・ミサイル射出にも使う兵装電力を表す。既存識別子は変更しない。

これらは設計確認用であり、ゲーム性能を確認済みのメッシュではない。主GLBはサービス状態、別GLBは閉鎖状態を保持する。Blenderにはシャッターの状態タイムラインを残すが、GLBには意図的にアニメーションを含めない。移動部品へワールド中央のMirrorを適用するだけでは、正しい左右連動のglTFアニメーション階層にならないため。

## 参照画像の分析

`blended-wing-v5/family-equipment.png` を原寸で開き、右下の旧チャンクバスター案を直接確認した。V22 FieldとV23の電力セル・機関砲モジュール・ミサイルモジュールは実際の `.blend` を開いて測定した。参照先、ハッシュ、材質、寸法は [reference-audit.json](reference-audit.json) に記録する。

判断の優先順位は、今回の明示指示、現行V22/V23正本の共通設計、旧案から繰り返し読み取れる機能、単一の透視図にしかない曖昧な細部とする。

旧案は機腹から見た透視図1枚であり、寸法図ではない。正確なベイ数、機首方向、翼厚をすべて確定できる資料ではないため、完全な復元とは主張しない。背の高い旧翼端部より現行系列の低い外形を優先し、大型フィン、着陸脚、外付け収納箱は追加しない。

### 座標軸と比率

- 単位は1 Blender unit = 1ブロック。前方 `-Y`、上方 `+Z`、構造の対称面 `X=0`。
- 母艦は幅48・長さ32・高さ7。幅を1.00とすると比率は1.00 : 0.667 : 0.146。
- 原点は機腹基準面の水平中央。Blender外形は `X=-24..24, Y=-16..16, Z=0..7`。
- 中央レンズは水平座標 `(0,0)`、ベゼル直径10.0。翼幅の約0.208。
- ファン中心は水平座標 `(+-11,4)`、機体表面に沿う外周直径は約9.64。大型翼に合わせて新規作成。
- 前方2ベイは4.6×5.8、水平中心 `(+-7.15,-5.2)`。後方2ベイは4.2×4.6、中心 `(+-3.6,7.8)`。全4か所に3×3の受入領域を確保。
- 各ベイに原寸のV22 Fieldを1機ずつ配置し、実寸と収まりを確認する。frame 40の格納脚を含む実メッシュを使用し、高さは実測0.320866。母艦自体には着陸脚を追加しない。

母艦本体は断面から新規に構成した連続翼面で、実際の開口部を持つ。小型ドローンを幅48へ拡大したものではない。V22形状を再利用するのは原寸の格納見本だけ。

### バックエンドとの共通契約

[runtime-contract.json](runtime-contract.json) が共通座標の機械可読な記録。Blender `(x,y,z)` をゲーム座標 `(x,z,y)` へ変換する。ゲーム側は幅/X=48、長さ/Z=32、高さ/Y=7、外形 `X=-24..24, Y=0..7, Z=-16..16`。+Xが右舷、+Zが後方で、yawは中立・固定。中央光軸は原点に一致し、`BeamOrigin` はBlender `(0,0,-0.1)`、ゲーム側では `entity.position + (0,-0.1,0)`。

4ベイの最終受入点は以下のとおり。すべて機腹原点に対するゲーム座標XYZで、位置マーカー `ServiceBay_0`〜`ServiceBay_3` もGLBへ出力する。Hume担当のバックエンドも同じ値へ更新済みと確認した。

| slot | 位置 | 受入点XYZ | 受入領域 |
| --- | --- | --- | --- |
| 0 | 左舷前方 | `(-7.15,2.4,-5.2)` | 3×3 |
| 1 | 右舷前方 | `(7.15,2.4,-5.2)` | 3×3 |
| 2 | 左舷後方 | `(-3.6,2.4,7.8)` | 3×3 |
| 3 | 右舷後方 | `(3.6,2.4,7.8)` | 3×3 |

待機点は各受入点と同じX/Z、高さY=-2。位置を合わせてからY=2.4へ上昇する。開口部は4か所とも独立し、受入領域の高さはY=2.4..3.35。小型機の `ENTITY_TYPE.sized(3.0f,0.75f)` に合わせ、高さ0.75に上方余裕0.20を加えた有効高さ0.95を確保する。旧試作のX=±10・Z=±12は現行翼形に収まらないため採用しない。

実天井下面はY=3.436892、Entity上端はY=3.15。原寸・脚格納状態のV22メッシュはY=2.659656..2.980522に収まり、実天井までの余裕は0.456370。各ベイの3×3領域に169本のレイを通し、機体だけでなく天井・レール・接点・ガイド・開放時のシャッターも確認する。全天井頂点と上面外板の最小距離は0.642391で、天井が翼上面を突き抜けないことも検査する。

初期の高さ7.07から母艦本体だけを垂直方向に7へ調整した。V22格納見本は平行移動のみで、倍率を変えない。船内は別次元とし、動くブロック群としては作らない。座標軸の交換で座標系の向きが変わるため、将来のRuntimeDroneMesh出力では既存処理の頂点順・法線規約に従う。この提案タスクではバックエンドを変更しない。

### 構成部品と状態

| 部品 | 形状・接続 | 可動・状態 | 対称性・表示 |
| --- | --- | --- | --- |
| 機体本体 | 連続した一体翼、薄い前縁 | 全状態で固定 | 編集可能な片側と共通Mirror原点 |
| ファンダクト | 翼面に沿う外周と内壁、実際の貫通穴 | 確認用の静止形状 | 左右2基、各12枚の羽根 |
| 機腹光学部 | 大型の暗い埋込レンズ、同心リング | 全状態に存在 | 中央配置、控えめなシアン表示 |
| 小型機ベイ | 4つの開口、暗い天井、内部受入レール | frame 1で閉鎖、40/80で開放 | 前後各1組の左右対称、アンバー接点16個 |
| シャッター | 翼面に沿う分割板を後方・上方へ格納 | frame 15〜40で移行 | 共通Mirrorで左右連動 |
| 格納見本 | 原寸のV22 Fieldを4機 | 寸法・収まり確認用、出撃動作なし | 各列で左右対称 |
| 後部推進部 | 内部羽根のある左右の埋込排気口 | 固定 | 細いシアンの後部灯 |
| 補給品の接続部 | 給弾口、蓄電器端子、ミサイル固定レール | 静止形状、装填動作は未実装 | 構造対称、機能別のアンバー・シアン |

`closed`、`service`、`active` は造形確認用の状態名であり、ゲーム側の実装完了を示さない。`active` と `service` は同じ開放形状で、確認方向のみ異なる。着陸状態や地上用の脚は持たず、接点の検証は内部の磁気受入部を対象とする。

## 材質と細部

V23の材質設定とV22の撮影・描画ヘルパーを読み取り専用で再利用する。グラファイト、銀色金属、暗い凹部と光学ガラス、控えめなシアン、小面積のアンバーを共通にする。蓄電器の銅端子は接点として残す。母艦にはV22に合わせた薄い補強パネル、点検ロック、安全表示を少量追加し、外付けの箱や過剰な発光で密度を補わない。

## 再生成と確認

既存のポータブルBlenderをバックグラウンドで実行する。依存関係のインストール、ゲームのビルド、Launcher起動、commitは不要。

```powershell
& '../work/blender-portable/blender-5.2.0-windows-x64/blender.exe' --background --python tools/drone-design/blender/build_supply_carrier_v24.py -- --audit
& '../work/blender-portable/blender-5.2.0-windows-x64/blender.exe' --background --python tools/drone-design/blender/build_supply_carrier_v24.py
& '../work/blender-portable/blender-5.2.0-windows-x64/blender.exe' --background --python tools/drone-design/blender/build_supply_carrier_v24.py -- --verify
& '../work/blender-portable/blender-5.2.0-windows-x64/blender.exe' --background --python tools/drone-design/blender/build_supply_carrier_v24.py -- --sheets
& '../work/blender-portable/blender-5.2.0-windows-x64/blender.exe' --background --python tools/drone-design/blender/build_supply_carrier_v24.py -- --validate
```

`--item <id>` で生成・GLB検査の対象を限定する。`--quick` は外形確認用で、承認用一式の生成ではない。最終ログは `build.log`、`carrier-build.log`、`verify.log`、`sheets.log`、`validate.log`。形状・容量の再集計は `--stats`、その結果は `model-budget.json` に出力する。初期の外形確認ログは別に保持する。

## 承認とゲーム導入の境界

親タスクが、4ベイ契約との一致を確認してからモバイル画像・比較図をユーザーへ提示する。自動検査のPASSをユーザー承認や参照画像への忠実さの証明として扱わない。現時点では未承認。

明示的な画像承認後に、親タスクが既存RuntimeDroneMeshへの変換を調整する。材質のベイク、左右可動部の階層、衝突・飛行外形、ID、補給量、保存互換をまとめて扱う。これらのゲーム用変更と実ゲーム検証は本提案に含めない。

## 検証とモデル容量

結果、目視確認での修正、モデル容量、未実施事項は [VERIFICATION.md](VERIFICATION.md) を参照。[output-checks.json](output-checks.json) では44描画、各モデル3状態の対称性、母艦80フレームの外形、参照正本のハッシュを検査する。4つの主GLBと閉鎖母艦GLBの部品位置、ビームおよびベイのマーカーも読み戻して照合する。

[model-budget.json](model-budget.json) に最新の頂点数、三角形数、ファイル容量を記録する。母艦には原寸のV22格納見本4機を含むため、そのまま導入可能なゲーム用メッシュとは扱わない。外形高さは試作の8ではなく、モデル・バックエンドとも正確に7。
