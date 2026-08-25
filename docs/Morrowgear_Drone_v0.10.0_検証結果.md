# Morrowgear Drone v0.10.0 検証結果

## 対象

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.3
- Fabric API 0.156.0+26.2
- Morrowgear Drone Command 0.10.0

## Field Operations

- 戦術地図から`ORE OPS`、`EXCAVATE`、`FORESTRY`を選び、地点のダブルクリックで選択機へ共通作業IDを割り当てる。
- Scoutの走査カーソルは同一作業内で共有し、複数Scoutが同じブロックを重複走査しない。
- `ORE OPS`は水平半径24、上下32ブロックを走査し、石炭、鉄、銅、金、レッドストーン、エメラルド、ラピス、ダイヤモンド、ネザー金、ネザークォーツ、古代の残骸と各深層岩鉱石を検出する。
- Engineerは走査完了まで待機し、共有された対象を複数機で排他的に処理する。
- `EXCAVATE`は9x9x6を対象とし、破壊不能ブロックとブロックEntityを保護する。
- `FORESTRY`は`minecraft:logs`と`minecraft:leaves`を対象とし、葉は素手相当、幹はダイヤモンド斧相当の通常ドロップテーブルを使う。
- 植林は元の幹基部へ対応樹種の苗木を1個消費する。現場ドロップを優先し、不足時は所有者インベントリを利用する。
- Cargoは現場のアイテムEntityを最大9スタックまで回収し、指定済み搬入先へ搬送する。搬入先が満杯の場合は積荷を保持する。

## 負荷・安全性

- 走査は読み込み済みチャンクだけを対象とし、1tickあたり384ブロックに制限する。
- 採掘物は通常のアイテムEntityとしてワールド保存され、再起動時にも消失しない。
- 既存Mod ID、Entity ID、Item ID、Block ID、Role IDは変更していない。
- 新規保存項目は既定値を持ち、v0.9.0以前の機体を読み込める。
- 通常の`STANDBY`、`RETURN`、`DOCK`、`ORBIT`指示で作業を中止できる。

## 自動検証

- 同一作業IDによるScout/Engineer共有、走査カーソルの排他性、対象処理数、範囲採掘の初期化をテストした。
- 既存の群制御、Cargo調停、Engineer修理、Dock、飛行制御を含む全114テストを実行した。
