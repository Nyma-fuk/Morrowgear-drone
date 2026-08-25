# Morrowgear Drone デザインパイプライン

> **現在の正規方式:** `blender/build_family_a_scout.py`から生成するBlenderポリゴンメッシュ。  
> Three.js版`model.mjs`は方式比較用の旧ブロックアウトであり、ゲーム実装へ使用しない。

画像生成を全周デザインの正として使わず、単一の数値ポリゴンメッシュからMinecraft向け外観を確定するためのツールである。

## Blender正規モデル

- 中央胴体は複数断面のロフトメッシュ。
- ローターダクトは円形開口、外周、内周、上面、底面を持つ厚み付きメッシュ。
- 左右部品はBlenderのMirror modifierで生成する。
- 垂直尾翼は側面ポリゴンで定義し、先端が後方`+Y`へ傾くことを検査する。
- 金属、機構、発光、整備ラッチを別マテリアルとして保持する。
- `.blend`を編集正本、`.glb`をゲーム変換用データとする。

出力先は`docs/design/drone-family-a/blender-v1/`。

## 方針

- 16 model unitをMinecraftの1ブロックとして扱う。
- 流線形胴体と翼状ダクトは数値輪郭から生成し、箱、円柱、押し出し輪郭、発光円弧を組み合わせる。
- Java版では単純な`CubeListBuilder`へ形を崩して押し込まず、必要な部分をカスタムメッシュ描画へ変換する。
- 左右部品は片側から`mirrorPair`で生成する。
- 役割差は共通Family Aモデルへの追加部品として管理する。
- 7方向の画像は同一モデルを正投影カメラで表示する。
- 画像生成AIは雰囲気案にのみ使用し、全周形状の確定には使用しない。

## 実行

```powershell
npm install
npm run validate
$env:MORROWGEAR_PLAYWRIGHT='C:\Users\fukud\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\node_modules\playwright'
npm run render
```

`render.cjs`はPCにインストール済みのChromeまたはEdgeを自動検出する。

## 出力

- `docs/design/drone-family-a/validated/family-a-*-model.json`
- `docs/design/drone-family-a/validated/family-a-*-validated.png`
- `docs/design/drone-family-a/validated/model-validation.json`
- `docs/design/drone-family-a/validated/render-validation.json`

## 合格条件

- 部品IDが重複しない。
- 全寸法が正であり、対応形状以外を含まない。
- 左右ペアの中心、寸法、回転、材質が鏡像一致する。
- Family Aのローターが左右2基である。
- 中央胴体から機首へ向けて幅が段階的に細くなる。
- ローターガードが円形開口を持つ翼状部分ダクトである。
- 役割ごとの外形部品が存在する。
- 全幅が3x3 Dockの48 unit内に収まる。
- 上面、正面、後面の反転輪郭差と左右側面差が2%未満である。
- 全7方向のCanvasが空画像ではない。
