# Carrier と Drone のサービス接続

## 親タスクの接続箇所

- 共通初期化で `CarrierDroneServiceAdapter.install()` を呼ぶ。これは `CarrierModule.installDronePorts(...)` だけを呼び、Carrier の登録・描画登録・アイテム登録は行わない。
- `CarrierModule.reserveBay(owner, shipId, droneId)` の予約成功直後に、返された `lease` を使って `port.approach(carrier.bayApproachPosition(lease.slot()), carrier.getDeltaMovement())` を呼ぶ。予約と最初の機体 tick の間に届く手動命令でも、古い予約を直ちに解放できるようにするため。アダプター側から自動予約はしない。
- 継続接続は既存 `CarrierServiceBay.tick(...)` の `approach`、各 `receive...`、`repairWith`、`release`。座標は Carrier 側の staging / bay API をそのまま使い、寸法を複製しない。
- Drone 側 tick / 保存 / 読込 / 命令優先処理は `DroneEntity` に接続済み。追加の毎 tick イベント登録は不要。
- Client 側の追加接続はない。外部 HUD・通信の cabin 対応、Carrier UI の公開、Carrier 登録・描画は別作業。

## 所有者と割当

明示的な所有者の予約だけを受け付ける。Carrier と Drone は同じ exterior `ServerLevel` の実体 UUID で解決する。所有者が別ディメンションにいる場合は、`CarrierInterior.currentShip(owner)` がその exterior に存在し、本人所有の稼働 Carrier の cabin に滞在している場合だけ認める。通常の Nether 移動やゲスト cabin は例外にしない。

判定の再利用 API は `CarrierDroneServiceAdapter.ownerSupportsExterior(ServerLevel, ServerPlayer)`。これは権限・world 関係の判定だけで、owner の位置を exterior の位置に変換する API ではない。

HomeDock、任意 ID の手動 Wing、mission、cohort、Waypoint、保留・中断タスク、供給トークンを書き換えない。既存の Cargo 経路は停止中でも除外し、積荷、供給トークン、Combat、Salvage、Recovery、Field、Engineer 作業、Tracking / Patrol、Solar、Dock 帰還、保留タスクのある機体も除外する。単純な FOLLOW / ORBIT / Waypoint は明示予約に限り一時中断できる。

サービス中は `serviceReturnActive()` と供給自動選出の idle 判定からも保護する。新しい手動命令は古い Carrier lease を解放してから適用する。予約後の owner / Wing / mission / HomeDock / role / mode / dimension 変更も毎 tick 再検証する。

## 飛行と物資

専用の exterior 分岐を、通常 AI の owner-world ガードより前で実行する。owner の cabin 座標を `ThreatAssessment` や `targetPosition` に渡さない。既存 `DroneNavigator` の corridor / localDetour と `FlightDynamics` の steering / smoothing を利用し、位置の瞬間移動をしない。近傍25チャンクの読込確認と最大24ブロックの局所目標に制限し、遠隔探索・チャンク強制読込をしない。

移動 Carrier の速度に追従し、飛行中は重力を無効にする。給電・補充・修理は lease・所有者・割当を再検証し、bay との位置差と相対速度が `CarrierServiceBay.stable(...)` を満たす場合だけ受け入れる。受入量は offer と残容量以下。アイテム消費は Carrier 側の責務で、アダプターは実際の受入量だけを返す。

非戦闘役割は武装電池・弾薬を要求しない。SECURITY は共通武装電池に加え、AUTO は両弾薬、AUTOCANNON は機関砲弾薬だけ、MISSILE はミサイルだけ、LASER は弾薬なし。現在 RAM は `CombatWeapon` の動作であり選択可能な `SecurityLoadout` ではない。UNARMED は既存 `CombatPolicy.weaponServiceNeed` の共通武装電力条件に合わせ、武装電池だけを対象とする。

飛行電池は98%以上で需要を0にし、毎秒の飛行消費と給電の実行順による完了待ちを防ぐ。現行最大容量は飛行3000、武装2000。アプローチは2400 tick、接触後は不足量が600 tick 改善しなければ解放する。実際の充電・補充・修理が進む限り、サービス時間の絶対上限は設けない。既に到達した不足量を超える単なる再充電は、欠品待ちの期限を延長しない。

## 解放・再読込・回復

`CarrierService` NBT に Carrier UUID、割当 identity、元 mode / role、出発地点、開始 tick を保存する。読込後は保存済み lease の有効性を仮定せず、最初の tick で期限切れとして解放する。物資は Drone の既存電池・弾薬・耐久値だけに保存し、二重台帳を作らない。

所有者が同じ world に戻り、元の単純任務が有効ならそのまま再開する。そうでなければ読込済みの本人 HomeDock へ物理的に帰還し、無効・未読込なら出発地点付近へ戻る。帰還は1200 tick、追加の局所着地は合計2400 tick で打ち切る。任務が有効でも owner が cabin にいる間は通常 AI を動かさず、帰還先で元任務を保持して待つ。安全な帰還先や地面がない場合は `RECOVERY WAIT / HOME UNAVAILABLE` を表示し、手動回収を待つ。地面に回復した機体は重力を戻し、ホバー給電を継続しない。

## 未完了範囲と検証

供給ネットワークの owner-world 制限は変更していない。所有者が cabin にいる間の自動 Cargo 継続は、既存端点・再充電・再開を扱う専用 exterior 分岐が別途必要。dispatch の適格条件だけを変更すると、飛行 AI が停止する便を作るため行わない。

追加テストは `CarrierDroneServiceAdapterTest` 13件、`CarrierDroneServiceIntegrationTest` 11件の計24件。権限・保護状態、任意 Wing の identity、保存 codec、容量・修理、役割別需要、長時間充電、欠品・期限切れ、移動 bay 追従、物理接触条件、通常 AI の world ガードと接続保護を確認する。当初20件に、Java 構文木による比較・代入の判別、実際の書き込み検出、実コードへの代入挿入を検出する変異テスト、不正なソースの拒否を確認する4件を追加した。

2026-09-22、親タスクの最終全 Gradle ビルドは `BUILD SUCCESSFUL`。全130スイート・2119テストが成功し、失敗0件・エラー0件・スキップ0件。上記24件（AST ガード修正の回帰テスト4件を含む）もすべて成功した。本担当による Gradle の再実行は行っていない。

検証はソース側の自動検証のみ。インストール済みファイルのハッシュは変更されていない。`launcher_release_ready=false` を明示し、通常の `build` でも Launcher への自動同期・配布を許可しない状態を維持している。

通常ゲーム内の接触・移動 Carrier・地形障害・破壊・再読込・cabin 往復は未検証。インストール、起動、コミットは行っていない。
