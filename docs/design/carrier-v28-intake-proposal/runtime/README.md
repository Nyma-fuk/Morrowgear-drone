# 承認済み V28 ゲーム用出力

ユーザーの「OK読み込んで」に基づく `V28_APPROVED_RUNTIME_01` の出荷記録です。上位フォルダの提案時点の記録は残し、承認された `carrier-v28-intake-proposal.blend` をそのまま入力しています。V27 / V28 の `.blend` と `.glb` は変更していません。

## 出荷資産

`src/main/resources/assets/morrowgear_drone/` 以下の 5 ファイルを、全検査後に切り替えました。

| パス | 内容 |
| --- | --- |
| `models/runtime/carrier.mgm` | MGM4、23,680 三角形、予算 25,000 以下 |
| `models/runtime/carrier_lod.mgm` | MGM4、12,872 三角形、予算 15,000 以下 |
| `textures/runtime/carrier.png` | 正本材質の色成分を bake、2048×2048 |
| `textures/runtime/carrier_lod.png` | 遠距離専用 atlas、1024×1024 |
| `textures/item/carrier_unit.png` | V28 正本から直接描画した 128×128 RGBA |

原点は機腹 0、ゲーム座標は `+Y=上、-Z=前`。境界は `[-13,0,-25] .. [13,11,25]`、追加倍率なしです。4 補給口・中央照射基準・後部搭乗位置の契約は変更していません。

## 一致と検査

- 正本は 393 メッシュ・24,704 三角形。MGM4 は面積がほぼゼロの面を既存 pipeline と同じ基準で除外しています。
- 近距離は正本の全有効三角形、遠距離は既存 LOD 選別後の三角形集合と、位置精度 `0.00001` で一致。
- 追加した吸気部は提案時の出力除外フラグにかかわらず変換時に含め、近距離・遠距離とも全 12 部品を確認。正本ファイル自体のフラグは変更しません。
- 両 MGM4 は左右鏡像誤差 0、補給 676 本・搭乗 169 本の進入 ray は各メッシュとも遮蔽 0。
- ヘッダー、byte 数、法線、UV、winding、group 0、2 個のダミー rotor center、3 pose の同一性、発光 flag 1 / 2 を読み戻し検査。
- ユニットアイコンは 16 / 32 / 64 / 128 ピクセルで非空・非クリップ・RGBA を確認。出荷 128 ピクセル画像は正本描画と SHA-256 が一致。
- 検査終了まで旧出荷資産を維持し、出荷後も 5 ファイルの SHA-256 を再確認。旧資産は `previous-assets/` に保存。

`verification.json` が最終検査、`published-assets.json` が出荷した 5 資産の SHA-256 です。`carrier-runtime-validation.json` と `carrier_lod-runtime-validation.json` は atlas・部品別面数を含む個別記録です。

`runtime-readback-sheet.png` は実際の MGM4 と出荷用 atlas を Blender で読み戻した画像です。近遠とも大型インテーク・4 補給口・中央照射口・後部搭乗口を目視確認しています。ゲーム画面ではなく、実ゲームの描画・動作確認を代替するものではありません。

## 再生成

```powershell
& '../work/blender-portable/blender-5.2.0-windows-x64/blender.exe' --background --python-exit-code 1 --python tools/drone-design/blender/export_carrier_v28.py
```

既存 V27 エクスポーターに入力正本パスを明示する機能を加えて再利用しています。候補は `staged-assets/` に出力し、検査成功後だけ切り替えます。承認された形状の再造形は行っていません。Gradle、launcher の差し替え、ゲーム起動・操作は親担当で、この出力作業では実行していません。
