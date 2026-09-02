# Morrowgear 自律運用・Task Force仕様

## 1. 文書情報

| 項目 | 内容 |
|---|---|
| 対象 | Morrowgear: Drone Command v0.19.1 |
| 機能 | `AUTO OPS`による目的ベースの一時編成 |
| 基準日 | 2026-09-02 |
| 正本 | 現行Java実装と本書 |

本書は、プレイヤーが個々の機体を選ばず「何を、どこで行うか」を指定した場合に、serverがどの機体を選び、既存任務とどう共存させるかを定義する。

## 2. 目的

従来の手動方式では、プレイヤーがScout、Engineer、Cargo、Security等を選択し、同じ作業指令を送る必要があった。これは精密な指揮には必要だが、繰り返し作業では操作量が多い。

`AUTO OPS`は手動方式を置き換えない。次の二方式を併存させる。

| 方式 | 入力 | 用途 |
|---|---|---|
| 手動 | 選択機、作業種別、地点 | Wingや個体を明示して指揮する |
| AUTO OPS | 作業種別、地点 | 利用可能戦力から一時Task Forceを自動編成する |

## 3. Command contract

Clientは一機ごとの`DroneCommandPayload`ではなく、次の単一payloadを送る。

```text
FleetOperationPayload
  operationType
  x
  z
  radius
```

Serverは作業種別、半径、playerからの距離を検証してから編成する。Client表示だけを受理証拠にしない。

単一payloadにする理由は、機体ごとのpacketが途中まで受理される状態を避け、同じserver tickの同じfleet snapshotから編成するためである。

## 4. 編成対象

候補はownerが同じ、同dimension内の機体である。次をすべて満たす場合のみ編成できる。

- `STANDBY`または着艦中。
- Power Lostでない。
- service帰還中でない。
- 戦闘、緊急迎撃中でない。
- Recovery中でない。
- Field、Guard、Salvage任務中でない。
- Cargoの独立コンテナ輸送中でない。
- 空中待機機は飛行電力35%以上。
- 着艦機は通常出撃基準の90%以上。
- 体力30%以上。

これらは「空いているように見える」だけで判定せず、server上の排他状態を確認する。任務中の機体を一度呼び戻して再編成する機能ではない。

## 5. 標準Task Force

最大機数は恒久Wingと同じ8機とする。

| 作業 | Engineer | Scout | Cargo | Security | Field |
|---|---:|---:|---:|---:|---:|
| Ore | 2 | 1 | 1 | 1 | 1 |
| Forestry | 2 | 1 | 1 | 1 | 1 |
| Excavate | 2 | 1 | 2 | 1 | 1 |
| 大範囲 | 上記 | 上記 | 上記 | Securityを2機へ増員 | 上記 |

Engineerは作業対象を処理する必須能力である。利用可能なEngineerが0機の場合、Scoutだけが走査して永久待機する任務を作らないため、任務開始を拒否する。

Scout、Cargo、Security、Fieldが不足する場合は縮退編成を許可する。

- Scout不足: Engineerは局所走査へfallbackし、作業範囲と効率が低下する。Engineerの局所作業完了をField支援の走査完了として扱い、Fieldに不要な広域走査を継続させない。
- Cargo不足: dropは現場へ残り、自動搬送されない。
- Security不足: 作業は続くが専属護衛を持たない。
- Field不足: 電力融通と複合任務支援を持たない。

不足能力は`DEGRADED / MISSING`としてplayerへ通知する。

## 6. 候補順位

同じRoleに複数候補がある場合は次の順で選ぶ。

1. すでに空中待機している機体。
2. 飛行電力が高い機体。
3. 体力が高い機体。
4. 作業地点へ近い機体。
5. `unitId`順。

最後に`unitId`を使うことで、同じsnapshotからは常に同じ結果を得る。乱数で編成を変えない。

## 7. Wingとの境界

AUTO OPSは`groupId`を変更しない。恒久Wingはplayerが定義した組織であり、一時任務の都合で書き換えると、任務終了後の復帰先とUI表示が壊れるためである。

Task Forceの共有単位は`fieldOrderId`である。異なるWingから選ばれた機体でも同じ作業台帳を参照するが、恒久Wingの所属は維持する。

## 8. 任務ライフサイクル

```text
REQUESTED
  -> VALIDATED
  -> TASK FORCE ALLOCATED
  -> Scout survey / Engineer local survey
  -> Engineer work
  -> Cargo collect and deliver
  -> Guard escort
  -> COMPLETE
  -> assigned Dock、またはownerへRETURN
```

各Roleの処理は既存`FieldOperationRegistry`と`FieldMissionStatusPolicy`を利用する。AUTO OPS専用の別作業実装を作らない。これにより手動任務と自動任務でdrop、植林、Cargo、護衛の結果が分岐しない。

## 9. 中断とfallback

| 状況 | 動作 |
|---|---|
| 電力reserve到達 | 現在taskを保存し、DockまたはSolar Serviceへ移る |
| 戦闘への一時離脱 | 恒久WingとField orderを保持し、終了後に再評価する |
| 対象block消失 | 完了扱いにして次対象へ進む |
| 未読込chunk | 対象を捨てず、読込後の再試行へ回す |
| Cargo搬入先満杯 | Cargoを保持し、搬入可能先または待機へ移る |
| Role機消失 | 残存Roleで縮退継続する。Engineerが全滅した場合は作業進行を停止する |
| 全工程完了 | Dock割当済みならDock、未割当ならownerへRETURNする |

AUTO OPSは進行中任務から別の機体を自動的に引き抜いて補充しない。無制限な再配分は任務間churnを再発させるためである。将来増援を追加する場合は、operation epoch、交代lease、引継ぎ対象の有効性を別契約として実装する。

## 10. Dock運用表示

戦術画面のDock一覧は、IDに加えて蓄電率と次の状態を表示する。

| 表示 | 意味 |
|---|---|
| `READY` | 電力があり、空きBayとして利用可能 |
| `BAY ONLINE` | 着艦機が通常出撃基準を満たす |
| `CHARGE n%` | 出撃基準まで飛行電力を充電中 |
| `WAIT POWER` | Dockの蓄電・入力資源不足 |
| `WAIT AMMO` | 現在機が必要とする弾薬不足 |
| `WAIT REPAIR` | 現在機が必要とする修理材不足 |
| `LIMITED` | 現在のservice要求に対する一部能力不足 |

弾薬や修理材が空であっても、空きDockを常に異常扱いしない。必要資源は着艦機のservice理由と組み合わせて判定する。

## 11. テスト契約

- 標準Ore編成が6機になる。
- ExcavateがCargoを2機要求する。
- 8機を超えない。
- Engineer不在では開始しない。
- 各Role不足をすべて表示する。
- Power Lost、service、combat、intercept、Recovery、Field、Guard、Salvage、Cargo中の機体を選ばない。
- 着艦中は90%、空中待機中は35%の電力境界を守る。
- 同条件では候補選択順が決定的である。
- DockのREADY、充電、資源不足表示がservice状態と一致する。

## 12. 今後の拡張

次段階では、operation単位の増援lease、任務優先度、playerが編集可能なTask Force template、農業工程を追加できる。ただし既存任務からの機体引抜きは、任務所有権と復帰先を明示するまで実装しない。
