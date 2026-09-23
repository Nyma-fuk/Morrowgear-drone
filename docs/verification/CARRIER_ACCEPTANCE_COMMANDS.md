# 母艦の実ワールド検証コマンド

## 適用範囲

2026-09-22の実機引き継ぎ。実施結果の正本は親担当の `CARRIER_END_TO_END_ACCEPTANCE.md` とする。この文書自体は実ゲームの成功証明ではない。

- 対象は `getWorldData().getLevelName()` が `MG Carrier V27 Verification` と完全一致する、単独プレイヤーのクリエイティブ・シングルプレイのみ。保存フォルダ名は判定に使わない。
- 既存 `CarrierRuntimeVerification` の READY/PASS、母艦UUID、元4機のUUIDを再利用する。母艦・小型機を生成、交換、削除しない。
- 4機の任務、役割、武装、HomeDock、Wingを検証器から書き換えない。既存戦闘の終了と待機への移行は通常UIで行う。
- 既存の `CarrierRuntimeVerification.register()` から登録済み。追加のmain登録は不要。
- 新コマンドのルートは `/morrowgear_carrier_accept`。記録は別SavedData `morrowgear_drone:carrier_acceptance` に保存し、旧READY/PASSを上書きしない。
- この担当はGradle、起動、ゲーム操作、インストールを実行していない。

## 開始前

通常操作で所有母艦に乗り、船内入口付近に立つ。カーソルでアイテムを持たず、主所持品に空き1枠を確保する。船内の相対座標 X=4..8、Z=4..5、Y=65 の10箇所が空気である必要がある。入口保全領域と旧検証チェスト X=6、Z=6 を避けている。

母艦が稼働中、航路・プレビュー・保留ドロップ・ベイ予約がある状態、4機が既知の戦闘等に従事している状態は拒否する。自然スポーンや難易度の準備は親が通常操作で行い、検証器は未知の敵を削除しない。

```text
/morrowgear_carrier_accept status
/morrowgear_carrier_accept deep_start
```

旧18ブロック試験の未完カーソルが残る場合、通常の `deep_start` は拒否する。`status` の世代UUIDと旧READYのarena/chunk由来を親が確認した場合のみ、次を使う。

```text
/morrowgear_carrier_accept deep_start_legacy <確認した世代UUID>
```

同一chunkという理由だけで未知の任務を置換するためのコマンドではない。旧Progressは新記録の `legacy` に保存し、通常PREVIEW→ACTIVATEで新試験へ移る。

## 採掘と満杯

16×16×64層の実採掘ブロック16,384個と、底面の岩盤256個を空気だけに生成する。母艦は通常MOVEで上空へ移動し、生成後に通常採掘を開始する。岩盤より下には空気と既存superflat地層を残し、採掘は各列の最初の岩盤でsealされる。既存地下を掘り切ったとの表現はしない。

材質は石35層、深層岩24層、石炭・鉄・金・ダイヤ・エメラルド各1層。さらに3ブロックを赤石・ラピス・銅鉱石へ置換する。期待数量を別乱数で再計算せず、実際の `Block.getDrops` 結果がcargoへcommitされた直後のAFTERイベントから、`lastCaptureDrops()` の実品種・実数量を記録する。

空きcargo枠だけに今回のrun識別付き充填材を通常メニューで投入する。元の貨物を消したり置換したりしない。実FULLと未破壊の保留ブロックを確認すると `FULL_WAIT` で自動処理を止める。

```text
/morrowgear_carrier_accept status
/morrowgear_carrier_accept deep_resume
```

再開では充填材1スタックを通常メニュー→実プレイヤー所持品へ取り出し、今回生成したその充填材だけを処分する。ページ変更の操作間隔を待ち、同一世代のRESUME_PREVIEW→ACTIVATEを実行する。進行後に再度FULLとなることを確認し、残りの充填材も同様に回収する。

この16x16x64領域は受入試験時間を制限するための専用fixtureであり、製品の採掘範囲ではない。製品は母艦下面からディメンション最下層までを対象にする。試験中はlaser cellを追加せず、蓄電が減っても炉直結の持続採掘が同一世代・同一カーソルで継続することを観測する。完了は16,384回の実破壊に加えて、16,384座標を表す永続ビット台帳が全て1回ずつ記録されたこと、256列のseal、全体Progress.complete、残存岩盤、空気となった全採掘位置、外周5,068ブロックの一致で判定する。同じ座標の重複通知で回数だけを満たすことはできず、18個を掘れたことでも代替しない。

## 手動サンプルと全量搬出

実UI撮影は自動 `extract` より先に行う。

```text
/morrowgear_carrier_accept manual_begin
```

後続ページの例示されたpage/slot/item/countと、空のプレイヤー枠を確認する。通常UIで合計1..64個を元々空だった主所持品枠へ取り出す。元貨物が入っていたslot0などから元の18個まで持ち出さない。カーソルを空にし、取り出し後画像を撮ってから監査する。

```text
/morrowgear_carrier_accept manual_audit
```

`MANUAL_WAIT` はactive=nullで、持ち上げ中の自動処理やタイムアウトは走らない。カーソル非空、元貨物の数量・成分減少、元所持品への干渉はstore開始前に拒否する。合格した実プレイヤー内のサンプルだけを通常barrelメニューへ移し、`manual_extracted` へ別計上する。人間がUIで操作したことと見栄えの証明は親の観察・画像で補う。

```text
/morrowgear_carrier_accept extract
```

残りは有限なサーバー側helperが `menu.clicked` を実行し、母艦→実プレイヤー所持品→実barrelの経路で全量搬出する。元の所持品と元貨物は最後に一致確認する。自動搬出数＋手動監査数＝実生成数を検査し、貨物＋プレイヤー＋倉庫の品種別収支を比較する。

barrelは27枠中末尾1枠を所有マーカーに使い、収納は10×26=260スタック。今回のcobble18のみの既存貨物を前提とする。採掘の変動ドロップを含め最大16,400個、通常の全量構成は最大259スタック相当。未知のloot tableで容量を超えれば実品を残して停止する。

## 保存・再読込

安定したFULL_WAIT、MINED、EXTRACTEDなどの停止段階で使う。通常充電中の補給品が残っている場合は、充電終了または通常取り出しを待つ。

```text
/morrowgear_carrier_accept restart_prepare
```

実ブロックの分割記録が終わり `RESTART_PREPARED` となってから、通常保存終了する。船内で再開して次を実行する。同一server bootでは合格しない。

```text
/morrowgear_carrier_accept restart_check
```

実インベントリ、保留ドロップ、Progress、全16,640 fixtureブロック、外周6面を含む全21,708検査ブロック、16,384座標の破壊台帳、品種別収支カウンタ、世代UUID、合格済み項目、4機の同一性を照合する。FULL時の再開は引き続き明示 `deep_resume` が必要。prepare前の任意の途中停止・クラッシュを自動復旧する機能ではない。

## 航行と隔離戦闘

```text
/morrowgear_carrier_accept travel_start
```

採掘完了位置から通常MOVEで544ブロック進み、通常MOVEで戻る。所有者は船内に留まり、実座標の連続変化、到着、燃料状態、flightMetricsを記録する。未ロード・障害・燃料不足で通常停止した場合にテレポートで突破しない。N03〜N05の全障害ケースをこの往復だけで合格にしない。

```text
/morrowgear_carrier_accept combat_start
```

通常航行で544ブロック隔離してから、元4機が未ロードまたは256ブロック以上遠方であり、近隣の別小型機もいないことを確認する。NoAIは敵判定から除外されないため、NoAIだけを隔離の根拠にしない。

生成するのは今回識別付きのHusk3体と味方Villager1体のみ。遮蔽と味方射線を置いた1回目、遮蔽を外し味方を別位置へ置く2回目を通常PREVIEW→ACTIVATEで実行する。充填・照射・冷却、実HP、照射aim数、武器電力、16,384個の空間ブロックと1,024列の実表面ブロックを観測する。

`COMBAT_DONE` ではactive=null、母艦と残り2体の標的を遠方に残して撮影を待つ。通常下船と128ブロック以内での通常UI操作で追加撮影できる。映像の合格は自動記録から推定しない。

```text
/morrowgear_carrier_accept status
/morrowgear_carrier_accept combat_return
```

通常再搭乗して攻撃を終えた後、明示 `combat_return` で今回生成した標的だけを片付け、通常MOVEで元位置へ戻り4機の同一性を再確認する。カメラや母艦のテレポートはない。

## 中断と既知の制限

- `pause` は所有する検証操作だけを停止し、実品とfixtureを残す。未知の新任務は停止しない。
- `cleanup` は今回の識別材・未変更fixture・識別付き戦闘標的のみを対象とする。プレイヤー再設置記録やBlockEntityがあるブロックは削除しない。元の母艦と4機は対象外。
- 採掘品の残るbarrelは削除せず `CLEANUP_WAIT` とする。通常メニューで空にしてから再試行する。遠方のfixtureは読み込まれた状態で扱う。
- 3種類以上の任意の既存端数貨物ではbarrel内の分断により260枠を使い切るケースがレビューで報告された。現在はcobble18のみの実証に限定する。次回まとめ修正時の候補は開始時の貨物構成guard、または全barrelの同種端数を空き枠より先に埋める方式。データを消して回避しない。
- A03の16体を超える公平割当、M09の液体・保護対象・味方侵入の全ケース、A07とN04/N05の全中断障害、クライアント演出は未実施として残す。
- 分離javacの型確認はエラー0。親から2226試験PASSの報告あり。ただし保存時刻照合では、充電待ち等の最終小修正はsource UTC10:39:00、既存class UTC10:37:56であった。最終小修正を含むビルド・実機成績は親の後続記録を正とする。
- 1tickのworld検査・配置上限は256、クリック上限は16。充填7スタック×2＋補給2、抽出は最大3＋barrel最大10。ページ切替は通常containerId/stateIdと5tick制限を維持する。
