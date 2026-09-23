# V27 検査・引き継ぎ

## モデル検査

対象 revision は `V27_FUNCTIONAL_DETAIL_01`。`validation.json` は正本の境界、全頂点の左右鏡像、共通 Mirror、4 補給口と後部搭乗受入口への進入検査を記録します。正本は 385 メッシュ、24,188 三角形。runtime は面積がほぼゼロの三角形を除き、近距離 23,288 / 遠距離 12,384 三角形です。

- 境界は幅 26、全長 50、高さ 11、機腹原点 0。
- 正本・runtime とも左右鏡像の最大位置誤差は 0。
- 補給口ごとに 13×13、合計 676 本の上向き ray を使用。3×3 の進入範囲に `Y=3.35` 未満の遮蔽なし。
- 後部搭乗受入口にも 13×13、169 本の ray を使用。`XZ=(0,18)` を中心とする 3×3 の進入範囲に `Y=4.4` 未満の遮蔽なし。補給と合計 845 本。
- 各補給口の footprint は分離。天井下面は `Y=3.64`。追加した配管・ガイドも必要空間上端 `Y=3.35` を侵さないことを ray で確認。
- `small-drone-fit.json` は V22 正本 frame 40 の GLB から実寸を測定。幅 3.0、前後長約 2.246、脚収納時メッシュ高約 0.321。ゲーム判定高 0.75 と上方余裕 0.20 の合計 0.95 を確保。
- 見本機は確認専用ファイルに分離し、正本母艦・GLB・MGM4 には含めない。

## 読み戻し

`carrier-runtime-validation.json` / `carrier_lod-runtime-validation.json` に MGM4 の独立したバイナリ読み戻し結果を保存します。

- magic、頂点数、2 個の rotor center、ファイル終端までの厳密な byte 数。
- 3 pose の位置・法線一致、有限値、単位法線、UV 範囲、group 0。
- 座標変換後の三角形 winding と法線の一致。
- 境界と鏡像、近距離 25,000 / 遠距離 15,000 三角形以内、発光 flag 1 / 2 の存在。
- 入力正本と atlas の SHA-256、部品ごとの面数。

`verification.json` は正本を再度開いた検査、frame 1 / 40 / 80 の静的境界、GLB 読み戻し、MGM4 再検査、画像の非空確認をまとめます。`carrier-mgm-readback.png` と `carrier_lod-mgm-readback.png` は、MGM4 の実際の頂点・UV・発光区分と出荷用 atlas から Blender で描画した確認です。ゲーム renderer によるスクリーンショットではありません。

最終の `verification.json` では近距離・遠距離 MGM4 に対しても各 845 本の進入 ray を通し、遮蔽 0 を確認しました。正本・GLB・両 MGM4 の境界は共通で、左右鏡像誤差はいずれも 0。光学部以外の架空砲身が残っていないことも検査しています。静的 3 frame の一致はアニメーション検証ではありません。

## 出荷とログ

生成中は `staged-assets/` に候補を保存し、出荷中の resources を保護します。最終検査、正本と atlas の SHA-256 照合、128 ピクセルアイコンの同一性確認、および確認画像の生成が成功してから、検証済みファイルを一時名経由で切り替えます。ファイルごとの置換は atomic ですが、5 ファイル全体を単一の atomic 操作として扱うものではありません。`published-assets.json` の `passed=true` と 5 件の SHA-256 が切り替え完了の記録です。

`build.log` 末尾の ACCESS_VIOLATION は以前の小型機比較用 `.blend` 読み込み失敗の記録です。今回の `functional-build.log` に残る遠距離予算超過も、出荷前の検査で検出した中間失敗です。小部品の LOD 除外を調整し、`functional-runtime.log`、`functional-comparison.log`、`functional-verification.log` の各工程を正常終了しています。最終結果は `verification.json` と `published-assets.json` を参照してください。

## 制約・親担当

- 正本は静的です。可動ハンガー扉、発艦アニメーション、飛行物理、任意姿勢の連続干渉を検証したとは扱いません。
- ray 検査は契約の垂直進入空間を対象とします。実ゲームの経路探索、移動中の他 entity、設置場所のブロック干渉は backend 側です。
- renderer 連携、LOD 距離切替、手持ち端末の controller 対応付け、Gradle 全体検査は親担当です。
- この作業ではゲーム起動、インストール、コミット、push を行っていません。
- 小型機の動的 `.blend` 取り込みでは Blender 5.2 の依存グラフが停止したため、同じ V22 正本の frame 40 静的 GLB を使用しています。評価済み材質を再利用する際は元の材質データを保持し、比較描画を完了しています。

## 入力

承認画像は `C:/Users/fukud/.codex/generated_images/019ff8b6-65ab-7b61-9327-249478654bda/exec-0f667897-253c-40df-8bf5-f0e636268628.png`。参照画像を目視し、中央の突出した装甲区画、埋込甲板、後部大型機関、左右対称を再構成しています。画像そのものをメッシュや atlas として貼り付けてはいません。

既存 `equipment-hmi-v23` の材質・描画 helpers と `export_runtime_v23.py` の MGM4 材質信号処理を再利用しています。表面の塗膜は正本材質から Blender で bake し、シートは Blender 内の画像平面と文字で組み立てています。Python による画像加工は行っていません。
