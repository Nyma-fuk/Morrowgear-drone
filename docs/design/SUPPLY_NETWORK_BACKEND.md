# Cargo 自律補給ネットワーク

## 実装範囲

明示設定した倉庫から、同じ所有者・同じディメンションの Dock へ Cargo が実際に飛行して補給する。台帳や設定処理ではアイテムを移さない。積荷は既存 `DroneEntity.cargo` だけに保存し、台帳には数量・識別子・割当世代だけを保存する。

サーバー側の経路選定、保存、予約、積み下ろし、`DroneEntity` 接続は実装済み。画面、状態送信、主初期化への登録は親タスクの担当。ゲームの起動・インストール・通常操作での飛行検証は実施していない。

統合追記: 親タスクで設定要求の登録、状態要求・応答、所有者と次元を確認するクライアントキャッシュを接続した。画面からの設定送信・状態表示はUI承認後の作業として残っている。

## API

| API | 用途 |
| --- | --- |
| `SupplyNetworkRuntime.configure(player, source, dock, rules, enabled)` | Dock ごとの供給元・最低在庫・優先度・有効状態を設定する |
| `SupplyNetworkRuntime.disable(player, dock)` | 端点が未読込・破壊済みでも、所有者の保存済み経路を無効化する |
| `SupplyNetworkPolicy.defaults()` | 燃料16、修理8、機関砲8、レーザー8、ミサイル4の既定設定。数量は資材アイテム数 |
| `SupplyNetworkRuntime.summary(level, ownerUUID)` | 所有者の設定、品目別在庫・搬入予定・不足、ジョブ、異常理由を返す |
| `SupplyNetworkRuntime.tick(level, candidates, hooks)` | 既存の読込済み機体集合を渡す追加の呼び出し口 |
| `SupplyNetworkRuntime.serviceEndpoint(level, drone, endpoint, loading, hooks)` | Cargo の到着・待機時間経過後に行う積み下ろし |
| `beforeLoad` / `afterLoad` / `beforeUnload` / `afterUnload` | 共通のリースを保持する間の数量確認・台帳更新。通常は `serviceEndpoint` がまとめて呼ぶ |
| `SupplyNetworkRuntime.cancel(level, drone, hooks, reason)` | 予約を解放して積荷を保持する。別任務へ積荷を自動転用しない |
| `SupplyNetworkRuntime.cargoSecuredOnRemoval(level, token)` | 通常の格納・ドロップ処理で積荷を確保した後の台帳解放 |

`rules` は `SupplyKind` の5種を重複なく含む。各最低在庫は0～576、優先度は0～100。0個の品目は配送対象にしない。高い優先度から試し、欠品中の品目がほかの配送可能な品目を永久に阻害しない。

## 親タスクの接続

### 主初期化

`PayloadTypeRegistry.serverboundPlay()` に `SupplyNetworkConfigPayload.TYPE` と `CODEC` を登録する。受信時はサーバースレッドで `payload.apply(context.player())` を呼ぶ。所有者 UUID はパケットに含まず、認証済み送信者から決定する。

有効化時はプレイヤーが倉庫を通常の距離で操作できること、Dock の所有権、両端の読込状態、距離256ブロック以内を確認する。最低在庫の変更も倉庫付近で行う。無効化は倉庫付近でなくてもよい。

`DroneEntity.customServerAiStep` が `tickFromDrone` を呼ぶため、主初期化への追加 tick 登録は不要。同一レベル・同一 tick の重複呼び出しは抑止する。機体が存在しない状態でも一覧の異常理由を毎秒更新する場合は、既存のレベル tick から `tick(level, loadedCandidates, DroneEntity.SUPPLY_CARGO_HOOKS)` を追加できる。独自の全エンティティ探索を増やす必要はない。

### クライアント

`SupplyNetworkConfigPayload(source.asLong(), dock.asLong(), enabled, rules)` を送信する。状態の要求・返送・表示は親タスクが所有する `SupplyNetworkStatusPayload`、`SupplyNetworkStatusRequest`、`SupplyNetworkTransport`、`SupplyNetworkClient` に接続する。バックエンドからそれらのファイルは変更しない。

`summary` の `stock == -1` / `missing == -1` は未読込などにより不明。0個として表示しない。`configuration` は保存済み設定、`jobs` は保留・返送を含むため、一覧の状態だけでなく各ジョブの `status` も表示する。

## DroneEntity 接続

`SUPPLY_CARGO_HOOKS` は以下を担当する。

- `token`: 保存する `SupplyNetworkRegistry.Token(UUID id, long generation)` を返す。
- `taskStackIdle`: 保留・待機タスク、太陽充電、予約済みサービスの有無を確認する。
- `dispatch`: 所有権と待機条件を再確認し、Cargo 端点とトークンを設定して既存航法を開始する。
- `redirectRetained`: トークン、既存供給任務の所有、保護状態を確認し、積荷を保ったまま `TO_TARGET` へ切り替える。
- `cargo`: 既存の実 `List<ItemStack>` を返す。別の保管場所は作らない。
- `acquireAccess` / `releaseAccess`: 既存 `CARGO_ACCESS` と `ContainerAccessKey` をそのまま利用する。
- `stop`: 同じトークンだけを停止し、その Cargo 世代のタスクだけを破棄する。サービス帰還・太陽充電・他の待機任務は消さない。

`serviceEndpoint` は元の `loadCargo` / `unloadCargo` より先に実行する。`NOT_NETWORK` 以外では必ず元処理を迂回する。`TO_TARGET` は配送先へ移動、`COMPLETE` は単発配送の終了、`WAIT` は再試行、`HOLD` は荷物保持または返送経路への切替を表す。供給便完了後に通常 Cargo の無限往復へ戻してはならない。

手動地点指定、モード変更、グループ変更、Cargo 端点変更、役割変更などは先に古い供給予約を無効化する。Cargo tick の冒頭にも `missionId == unitId + "-cargo"` とトークンを確認する保護を置き、古い輸送が新しい地点指定を上書きしないようにした。

## 安全境界

- 倉庫は明示したチェスト・樽・シュルカーボックスのみ。施錠コンテナ、Dock、仮想在庫、遠隔在庫は供給元にしない。
- 大型チェストは選択した `ChestBlockEntity` 側の在庫だけを扱う。隣接側へ探索を拡張しない。
- 同一ディメンションの供給元・Dock を別所有者の設定で共有できない。UUID の一致を必須とする。
- `ALPHA` 以外のグループは、`WING-` 接頭辞の有無にかかわらず自動選出しない。
- Cargo の既存経路、積荷、Waypoint、任務、Field、Patrol、Combat、Recovery、Salvage、サービス、保留タスクがある機体は選出しない。
- 設定は最大64、ジョブは最大64、候補機キャッシュは最大128。判定は20 tickごと。端点は `hasChunkAt` 確認後に読む。地形・コンテナの周辺総当たりや強制読込は行わない。
- 一便は要求した1品目・最大1スタック・最大64個。実際の `ItemStack` を移し、コンポーネントを維持する。旧弾薬や役割モジュールなどは積まない。
- Dock の搬入先は専用補給バッファだけ。回収バッファや装着枠を汎用搬入先にしない。
- 元在庫の数量予約に加えて、共有補給バッファの競合を避けるため Dock ごとに一便だけ予約する。プレイヤー等による在庫変更は積み下ろし時に再確認する。
- 通常完了の通知は出さない。欠品・破損・在庫不整合などは変化時だけ通知し、再通知に間隔を設ける。

## 中断・返送・再読込

源在庫が消えても、積載済みで配送先が有効ならそのまま配送する。配送先が失われた場合は同じ所有者の設定済み・読込済み Dock を最大256ブロック以内で選び直す。受入余地のある代替 Dock がなければ元の倉庫へ返送する。返送も既存 Cargo 航法を使い、到着時に既存のコンテナリースを取得してから搬入する。

元倉庫へ返すときは source と target が同じ座標になるが、供給トークンのある返送便だけを通常の同一端点拒否から除外する。元倉庫への戻し入れは `canPlaceItem` を既存スタックへのマージにも適用する。帰路に容量がなくなった場合も余剰は Cargo に残す。

代替先と元倉庫の両方が無効・未読込・満杯なら積荷を保持して停止し、読込済みの候補を定期的に再確認する。手動命令で別任務を与えた後は返送でその命令を上書きしない。プレイヤーは機体格納・回収によって残荷を取り出せる。

設定変更は世代を更新し、古い積載許可・荷下ろし通知を拒否する。充電は同じジョブ・同じトークンで待機して再開する。Cargo タスクの識別子にも供給世代を含め、古いタスクが新しい割当へ復帰しないようにする。

保存先はディメンション別の `morrowgear_drone:supply_network` SavedData。機体 NBT は `SupplyNetworkToken` と既存 `Cargo`。再読込時に数量・所有者・トークンが合わない場合は荷物を保持して停止し、台帳からアイテムを作り直さない。消滅・格納では通常のドロップまたは格納アイテムへの引渡し後に予約を解放する。

通常の保存・再読込を対象とする。プロセス強制終了時の異なるチャンク・エンティティファイル間の原子的保存は Minecraft 本体の保証範囲を超える。台帳を第二のアイテム保管庫にして複製する方式は採用していない。

## 検証

Carrier cabin に所有者が滞在している間も、現状は `SupplyNetworkRuntime.eligible` と通常 Drone AI の同一 world 条件を維持する。供給の dispatch だけを許可しても既存 AI は停止するため、cabin 対応済みとは扱わない。Cargo 端点・途中充電・再開に限定した exterior AI 分岐が今後必要。Carrier サービス専用分岐は独立して実装しており、この制限を迂回しない。将来の権限判定には `CarrierDroneServiceAdapter.ownerSupportsExterior(...)` を利用できるが、owner の cabin 座標を exterior の位置として使ってはならない。

`gradlew.bat test --tests '*SupplyNetwork*' -x syncLauncherMod --console=plain` で台帳・ポリシー・実 ItemStack 操作・接続保護の46テストを実行し、成功した。

関連する Cargo、タスクスタック、Wing、コンテナ排他、Solar、DockService を含む選択実行は625テスト、失敗0件。その後の最終確認で供給便が戦闘・Recovery・Salvageの飛行を上書きしない早期リターンと停止時ガードを追加した。この最終差分は、並行ビルドを避けるため親タスクの直列フルビルドで再確認する。

未実施の通常操作検証: 倉庫設定からの飛行、途中充電と復帰、Dock 破壊後の返送、満杯待機、両端未読込、格納・撃墜、ワールド終了と再開、画面の設定・状態表示。自動テストの成功を実ゲーム検証の代わりにはしない。
