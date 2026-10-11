# Morrowgear FIELD動的データリンク網 設計・検証仕様

## 0. 文書情報

| 項目 | 内容 |
|---|---|
| 対象 | Morrowgear: Drone Command Java Mod |
| 基準 | Minecraft Java Edition 26.2 / Fabric |
| 作成日 | 2026-10-11 |
| 状態 | v1中核実装済み。高度制御・HUD・物理接続判定を含む |
| 目的 | FIELDを移動通信インフラとして成立させ、距離無制限の現行通信を段階的に置き換える |

本書は実装を始める前に、通信モデル、FIELDの自律展開、通信断時の自律動作、Scout、Strike Beacon、Solar Service Station、UI、保存、性能、および試験の合否条件を固定する。

本書で「必須」とした挙動は、実装時に都合よく変更してはならない。数値は境界値試験の対象とし、変更する場合は本書と試験を同時に更新する。

### 0.1 適用範囲と文書の優先順位

本書は、通信node、接続距離、経路選択、FIELDのrelay任務、通信断時の扱い、通信状態のUI表示について正本とする。これらの項目が既存資料と競合する場合は本書を優先する。

一方、次の既存契約は本書で置き換えない。

- [ドローン制御アーキテクチャ](../architecture/MORROWGEAR_DRONE_CONTROL_ARCHITECTURE.md): 飛行制御、任務ownership、server authorityの全体構造。
- [命令トランザクション・範囲作業仕様](MORROWGEAR_COMMAND_AND_FIELD_OPERATION_SPEC_JA.md): `commandId`と機体ごとの受付・保留・失敗結果。本書は通信待ち理由を追加するだけとする。
- [Cargo自律補給ネットワーク](SUPPLY_NETWORK_BACKEND.md): 在庫、予約、Cargo搬送、Dock補給。本書は遠隔命令とtelemetryの到達条件だけを制限する。
- [運用通信・イベント履歴](OPERATION_COMMUNICATIONS_IMPLEMENTATION.md): 字幕、履歴、音声event。本書の通信切断中も、局所で発生したeventは再接続時に重複なく同期する。

実装時に曖昧さを発見した場合、推測で補わない。該当する状態、入力、期待結果を本書へ追記し、失敗する試験を先に追加してから実装する。

### 0.2 v1実装対応

| 契約 | 実装 |
|---|---|
| 128 blockの物理接続、hop、品質、親node | `DataLinkRelayPolicy` |
| FIELDの基礎配置、予備、動的branch、安定slot割当 | `FieldRelayMeshPolicy` |
| owner・dimension単位の計画、任務Demand、圏外命令Demand | `FieldRelayRuntime` |
| 周辺地表上28 blockの固定運用面、共通障害物回避を使う進入、低速ロイター | `FieldRelayFlightPolicy`、`FieldRelayRuntime`、`DroneEntity.tickFieldRelayMesh` |
| server判定と同一snapshotのHUD転送 | `FleetLinkNetwork`、`FleetLinkPayload` |
| バイザーの状態帯、X/Z範囲、node、親link、128 block圏、3D空域図 | `VisorHudOverlay.drawFieldMesh`、`drawAirspaceMesh` |

ソーラーサービスDockは充電設備として残すが、データリンクnodeには登録しない。圏外地点への操作は、その地点を400 tick有効な動的Demandとして登録する。HUDでは確立済みlinkを実線、展開中のslotを破線、通信網全体を単一の上半球ドームで表示する。物理接続判定はXYZ距離であり、表示ドームは状況把握用の集約表現である。FIELDラベルには自機IDと親node IDを`自機>親`で併記する。

## 1. 現行仕様と変更理由

### 1.1 現行実装

現行の`DataLinkRelayPolicy`は、同一owner・同一dimensionであれば距離に関係なく接続済みと判定する。Solar Service Stationがない場合も通信品質100、存在する場合も距離に関係なく1 hopである。

この方式では次の問題がある。

1. Solar Service Stationを置かなくても遠隔操作でき、中継設備の意味がない。
2. FIELDの`FIELD_DATA_RELAY`は表示上の状態であり、実際の通信には寄与しない。
3. Scoutの遠隔索敵、Strike Beacon、遠隔Cargo、戦闘増援が通信経路を必要としない。
4. 通信網の破壊、再構築、FIELD交代というゲーム性が成立しない。
5. UIに表示されるrelay hopと実際の接続条件が一致しない。

### 1.2 変更後の責務

| 要素 | 責務 |
|---|---|
| コントローラー | ownerごとの通信網root。直接通信圏を持つ |
| FIELD | 通信圏を持つ移動relay。必要地点へ自律展開する |
| Scout | 索敵、経路観測、通信圏外でのstore-and-forward |
| その他の小型機 | 通信網のendpoint。relayにはならない |
| Solar Service Station | 充電専用。通信中継機能を持たない |
| Strike Beacon | 攻撃命令の終端局と局所射撃管制。relayにはならない |
| Dock | 補給・修理・格納。通信断中も局所サービスを継続する |
| 母艦 | v1ではendpoint。通信rootまたはrelayにはしない |

## 2. 設計原則

1. 通信断だけで機体を墜落、停止、`STANDBY`化させない。
2. 新規遠隔命令と、すでに受理した任務の継続を分離する。
3. 物理接続はmesh、使用中の経路はコントローラーを根とするtreeとする。
4. FIELDは個別ドローンを追い続けず、作戦ルートと任務clusterを覆う。
5. 古い接続を切る前に新しい接続を成立させる。これをmake-before-breakと呼ぶ。
6. 展開済みの基礎メッシュはプレイヤー移動へ追従させない。配置変更は明示命令または動的Demandに限定する。
7. owner、dimension、mission境界を越えて情報を共有しない。
8. 全機総当たりを禁止し、空間indexと有界な候補探索を使う。
9. 通信経路がなくても、Dock、自律帰還、局所防御、Power Lost処理は機能する。
10. UI表示とserver判定は同一snapshotを使用する。

## 3. 通信トポロジー

### 3.1 構造

```text
Controller root
  └─ FIELD-01
       ├─ FIELD-02 ─ Scout patrol
       │              └─ Security engagement
       └─ FIELD-03 ─ Engineer / Cargo operation
```

FIELD同士は複数の物理linkを持てる。通信処理ではrootから各endpointまでのprimary pathを一つ選び、重要任務だけsecondary pathを保持する。

### 3.2 固定値

| 定数 | 初期値 | 意味 |
|---|---:|---|
| `CONTROLLER_RANGE` | 128 block | コントローラーrootの最大直接通信距離 |
| `FIELD_RANGE` | 128 block | FIELDとFIELD、FIELDとendpointの最大距離 |
| `LINK_ACQUIRE_RANGE` | 120 block | 新規linkを成立させられる距離 |
| `LINK_DROP_RANGE` | 128 block | 既存linkを切断する距離 |
| `RELAY_TARGET_SPACING` | 88 block | 自動配置の基準間隔 |
| `BASELINE_MIN_SEPARATION` | 44 block | handoff以外でFIELD同士を近接させない下限 |
| `LOCAL_BEACON_RANGE` | 128 block | Strike Beaconの局所管制距離 |
| `TOPOLOGY_TICK_INTERVAL` | 10 tick | 位置とlink品質の更新周期 |
| `PLAN_TICK_INTERVAL` | 20 tick | 配置計画の通常再評価周期 |
| `REPLAN_STABILITY_TICKS` | 40 tick | 軽微な変化を無視する安定化時間 |
| `BASELINE_REBALANCE_COOLDOWN` | 200 tick | 基礎メッシュの連続再配置を防ぐ時間 |
| `RELEASE_GRACE_TICKS` | 200 tick | 任務終了後に枝を保持する時間 |
| `MAX_ACTIVE_FIELD_RELAYS_PER_OWNER` | 16 | owner・dimensionごとの稼働上限 |
| `MAX_ACTIVE_FIELD_RELAYS_SERVER` | 64 | server全体の稼働上限 |

実際の距離判定は3次元Euclidean距離を使う。地図では理解しやすさのため水平円として表示する。

地形遮蔽による電波減衰はv1に導入しない。洞窟、地下、Netherで通信成立条件が予測不能になるためである。

### 3.3 Link品質

単一linkの品質は距離帯で決める。

| 距離 | 品質 |
|---:|---:|
| 0～64 | 100 |
| 64超～96 | 90 |
| 96超～112 | 75 |
| 112超～120 | 55 |
| 120超～128 | 30。既存linkだけ維持可能 |
| 128超 | 0。切断 |

path品質は`path上で最も低いlink品質 - 3 × (hop数 - 1)`を0～100へ正規化する。品質は表示、経路選択、冗長化判断に使う。v1では意図的なpacket遅延や確率的packet lossを追加しない。

### 3.4 接続条件

endpointは次をすべて満たしたときだけ接続済みである。

1. 同一ownerである。
2. 同一dimensionである。
3. Controllerまたは接続済みFIELDと距離条件を満たす。
4. path上のFIELDが生存し、Power Lostでなく、relay dutyを維持している。
5. 対象をserverが有効なnodeとして認識している。

別ownerのFIELDを自動利用しない。将来team共有を追加する場合も、明示的な許可設定と別の試験契約を必要とする。

## 4. 通信状態

各機体は次のいずれかを持つ。

| 状態 | 意味 |
|---|---|
| `DIRECT` | Controllerへ直接接続 |
| `RELAYED` | 1機以上のFIELDを経由 |
| `DEGRADED` | 接続中だが品質40未満、またはsecondary pathなし |
| `LOCAL_CONTROL` | Strike Beacon等の局所管制だけ有効 |
| `OFFLINE_CACHED` | 通信断。受理済み任務を保存情報で継続 |
| `RECONNECTING` | 接続候補を検出し、未送信情報を同期中 |

通信状態は飛行modeや任務状態を上書きしない。たとえば`WAYPOINT + OFFLINE_CACHED`、`COMBAT + LOCAL_CONTROL`を許容する。

## 5. FIELDの状態と優先度

FIELD relay制御は独立した任務ownershipとして実装し、基本modeの文字列だけで表現しない。

```text
IDLE
  -> RESERVED
  -> DEPLOYING
  -> RELAY_HOLD
  -> HANDOFF_SOURCE / HANDOFF_TARGET
  -> RELEASE_PENDING
  -> REJOINまたはSERVICE_RTB
```

`RELAY_HOLD`には状態を増やさず、別の`RelayDuty`を持たせる。

| `RelayDuty` | 意味 |
|---|---|
| `BASELINE` | 平常時の均等配置メッシュを構成する |
| `DYNAMIC` | route、任務、Beacon等の不足範囲を補う |
| `MESH_RESERVE` | 基礎メッシュへ参加しつつ、単独で通信を支えない増援候補 |

FIELDロールであることだけでは通信網へ参加しない。ユーザーがコントローラーから`Mesh展開`を明示した機体だけが、保存される`FieldMeshAuthorized`を持ち、`BASELINE`または`MESH_RESERVE`の候補になる。通常配置した機体はまず所有者の空きDockを予約して自動帰還し、空きがなければDock待機空域を周回する。勝手にメッシュへ出撃しない。Dock内で整備中の許可済み機体は、出撃可能条件を満たした時点でplannerへ追加する。`Mesh展開`はFIELD機に残る旧Waypoint、作業、整備復帰先より新しい正本命令として扱い、旧任務を後から再開しない。`Mesh撤収`または通常のDock帰還命令は許可を解除し、Dockへ帰還させる。stateの`RESERVED`は配置先を予約済みで移動開始前という意味であり、dutyの`MESH_RESERVE`とは別である。

FIELDの運用高度はプレイヤー高度へ追従させない。meshを開始するときだけ、Controller直下と周囲8地点の地表最高点に28 blockを加えた固定飛行面を決め、そのmeshが存続する間は全slotで同じYを再利用する。プレイヤーの上下移動、地下移動、slot直下の山谷を理由に飛行面を再計算しない。露天掘りや地下まで開いた基地では周辺rimの地表が初期基準になる。完全地下型meshは通常meshから推測せず、将来の明示的な地下relay任務として別途設計する。

固定飛行面は目的slotの高度であり、障害物回避を禁止する高度拘束ではない。FIELD機の展開とロイター進入は、一般飛行と同じ経路探索、通過断面判定、停滞検出、3段階の脱出経路を毎tick通す。見通しが確保された区間だけ直進し、壁、天井、樹木、狭い開口または30 tick以上の停滞を検出した場合は一時的に上下・側方へ迂回してから元のslotへ戻る。回避中もslot予約は保持するが、回避完了までは確立済み通信nodeとして数えない。これにより割当の再配分と飛行制御の取り合いを防ぎ、HUDには`AVOID 1..3`を表示する。

充電またはDock整備はrelay任務を終了させる命令ではない。離脱開始時に旧割当を即時無効化して代替機を計画し、整備完了後は汎用の`STANDBY`、旧Waypoint、Dock帰還へfallbackせず、`FIELD MESH REJOIN QUEUED`から現在のmeshへ再割当する。割当待ち中の機体は現在位置で安定待機し、所有者追従や旧目的地への移動を行わない。ただし、整備中または帰還中にユーザーが`Mesh撤収`か`Dock帰還`を明示した場合、その命令は終端命令となる。整備は接点上で継続しても、保存済みmeshや旧任務は自動再開しない。

Mesh認可済み機体がDock接点で出撃電力を待つ間、HMI状態は`FIELD MESH / DOCK SERVICE`を正本とする。汎用の`DOCK / ADD FUEL`と`FIELD MESH / SERVICE PENDING`を交互に表示してはならない。電力不足、充電中、出撃可能の差は同じ状態内の理由として表示する。

### 5.1 飛行ownershipの優先順位

```text
POWER_LOSS
  > DOCKED
  > SERVICE_RTB
  > EMERGENCY_SELF_RECOVERY
  > RELAY_HANDOFF
  > RELAY_DEPLOY / RELAY_HOLD
  > FIELD_OPERATION_SUPPORT
  > WAYPOINT / FOLLOW / RETURN / ORBIT
  > STANDBY
```

通信経路を支えているFIELDへ通常移動命令が来た場合、即座に離脱させない。命令を保留し、代替FIELDが接続した後に実行する。ユーザーが強制解除を選んだ場合は、切断される配下機数と任務を確認表示してから離脱させる。

### 5.2 FIELD候補条件

次のFIELDは新規relay dutyへ割り当てない。

- ユーザーによる`Mesh展開`許可を持たない。
- Power Lost、Recovery、service帰還、Dock最終進入中。
- 重大損傷または必要帰還電力を満たさない。
- Container牽引、Salvage、ユーザーが固定した任務を実行中。
- 別のcritical pathで代替不能なrelayになっている。

候補順位は、既存経路から配置点までの距離、電力、損傷、現在任務への影響、unitIdの順で決定する。結果は同じ入力に対して決定的でなければならない。

## 6. 基礎メッシュと動的配置計画

### 6.1 二層構造

FIELD通信網は次の二層で構成する。

```text
Controller
  ├─ Baseline mesh: 平常時の均等配置、基地と主要設備を常時接続
  └─ Dynamic branch: route、戦闘、救難、物流等へ必要な間だけ延伸
```

基礎メッシュは、FIELDが余っているときにも通信網と即応力へ寄与させる。動的branchは基礎メッシュの最寄りnodeから伸ばし、任務終了後は解放したFIELDを基礎メッシュの空きslotへ戻す。動的任務の終了を`STANDBY`への遷移条件にしない。

### 6.2 戦略通信領域

均等配置の対象はworld全体ではなく、owner・dimensionごとの有限な`StrategicCoverageRegion`とする。領域は次の和集合で構成する。

| anchor | 自動登録条件 | 基礎領域 |
|---|---|---|
| Controller | ownerがそのdimensionで最初の`Mesh展開`を実行した地点 | 水平半径128 block |
| Dock | 稼働中、機体収容中、または補給・帰還予約あり | 水平半径128 block |
| 登録基地・補給拠点 | ownerが地図から明示登録 | 水平半径64～512 block。初期値128 |
| 母艦待機地点 | ownerが母艦の戦略anchor登録を有効化 | 水平半径128 block |
| 通信corridor | 接続対象anchor間 | 中心線の左右64 block。中心線は88 block以下でsample |

Controller由来anchorは最初の`Mesh展開`命令地点で固定する。プレイヤーの水平・上下移動、充電離脱、通常の再計画では更新しない。全FIELD機を`Mesh撤収`またはDock帰還させた後、改めて`Mesh展開`した場合だけ新しい命令地点へ更新する。anchorは認可済みFIELD機へ保存し、サーバー再起動後も維持する。旧保存データにanchorがない場合は、認可済みFIELD機群の重心を一度だけ移行anchorとして採用し、現在のプレイヤー位置へ引き寄せない。Dock破壊、dimension変更、明示登録解除はslotと経路を再計画するが、この固定anchor自体は変更しない。

未登録chest、単に通過した地点、現在位置が変化し続ける一般ドローン、敵contactだけを理由に基礎領域を拡張しない。これらは動的Demandで扱う。遠隔anchorを全て囲う巨大な凸包は作らず、anchor周辺の領域とそれらを結ぶcorridorだけを対象にする。

FIELD数が領域全体を覆うには不足する場合、優先度はController、有人地点、稼働Dock、登録基地、補給拠点、空白領域の順とする。覆えない範囲はUIへ明示し、接続済みとして偽装しない。

### 6.3 基礎メッシュ生成

1. 戦略通信領域を88 block間隔の三角格子候補へ離散化する。隣接行は44 blockずらし、円形通信圏を無駄なく重ねる。
2. Controller直接圏の候補から始め、既存接続へ連結できる候補だけを外側へ展開する。
3. anchor優先度、未被覆面積、既存nodeからの距離を使ってslotを選ぶ。
4. 現在有効なassignmentは、距離128以内かつ安全条件を満たす限り維持する。
5. 新規FIELDは未被覆量が最大のslotへ割り当てる。既存全機を並べ直さない。
6. FIELD減少時は最も重要度の低い外周slotから縮退する。
7. 通常配置ではFIELD間を44 block未満にしない。handoff中だけ一時的な近接を許す。

割当は「全体を毎回最適化」せず、既存slot固定後に空きslotを最近傍の適格FIELDで埋める。これにより、機体追加や軽微なanchor移動のたびに全FIELDが交差移動することを防ぐ。同距離時はunitIdで決定し、同じ入力から同じ結果を得る。

格子slotは水平`x/z`の計画点である。実際の`y`はmesh開始時に確定した固定飛行面を使う。建造物や山は通常の障害物回避で迂回し、個々のslotの地形やプレイヤー高度を理由にmesh全体の高度を変更しない。高度差を含むlink距離が上限を超える場合は中間slotの追加または未被覆表示へ切り替える。

固定飛行面はmesh開始地点の周囲地表最高点から28 block上とする。保持中はslotで完全停止せず、半径7.5 blockを全機同方向へ600 tick、約30秒で一周する。高度は一定とし、水平12 block・垂直4 blockのロイター包絡内を通信成立位置として扱う。これにより見た目の微動と通信判定の点滅を分離する。世界上限付近では`maxY-8`、下限は`minY+12`へ制限する。

利用可能FIELD数に応じ、次の数を`MESH_RESERVE`として基礎メッシュのroot付近または複数linkが重なる位置へ置く。MESH_RESERVEも通信nodeだが、単独のbridgeにはしない。

| 利用可能FIELD数 | RESERVE目標 |
|---:|---:|
| 0～3 | 0 |
| 4～9 | 1 |
| 10～16 | 2 |

### 6.4 Demand

通信を必要とする対象は`LinkDemand`として登録する。

| 優先度 | Demand |
|---:|---|
| 500 | プレイヤー危機、Power Lost救難情報 |
| 450 | Strike Beacon攻撃 |
| 400 | 実行中の戦闘・迎撃 |
| 300 | Salvage回収・帰還 |
| 200 | 採掘・伐採・Engineer/Cargo複合任務 |
| 150 | 自動補給・Cargo物流 |
| 100 | Scout巡回・通常Waypoint |

同優先度では、すでに接続済みの任務、ownerへの危険が高い任務、少ないFIELDで成立する任務を先に処理する。

### 6.5 動的branch配置

厳密なSteiner tree最適化は使用しない。計算量と配置の揺れを抑えるため、次の決定的なgreedy方式を採用する。

1. Controller、基礎メッシュ、接続済みFIELD、既存branchをgraphへ登録する。
2. 作戦routeを88 block以下の間隔でsampleする。
3. 任務領域を一つのendpoint clusterとして登録する。
4. 既存通信圏に含まれるsampleを除外する。
5. 近接する複数任務のsampleを48 block以内で統合する。
6. 基礎メッシュの最寄り接続点から未接続sampleへFIELD候補を割り当てる。
7. critical demandでは余剰FIELDがある場合だけsecondary pathを作る。
8. FIELD不足時は優先度の低いbranchを`WAIT_RELAY`へする。

個々の移動中ドローンへFIELDを追従させず、route corridorまたは任務clusterを覆う。移動対象がcluster境界から48 block以上離れた状態を40 tick維持した場合にだけ再配置する。

### 6.6 FIELD不足時の供出元選定

動的branchに必要なFIELDがMESH_RESERVEだけでは足りない場合、基礎メッシュのFIELDを供出候補にできる。ただし「任務地点に最も近い機体」という理由だけでは選ばない。各候補を一時的に除いたgraphを仮計算し、次の辞書式順序で最小の候補を選ぶ。上位条件が異なる場合、下位条件で逆転させない。

1. **安全性**: critical endpoint、有人地点、実行中任務を切断する候補は除外する。
2. **構造維持**: 取り除くとgraphが分断されるarticulation nodeは、handoff先を先に用意できない限り除外する。
3. **被覆損失**: 新たに圏外となる戦略cellの優先度合計が最小。
4. **冗長性損失**: secondary pathを失うcritical anchor数が最小。
5. **品質低下**: 残存endpointのpath品質低下合計が最小。
6. **到着性**: 動的配置点までの到着時間と必要帰還電力が小さい。
7. **公平性**: 連続relay duty時間が長い機体を先に交代させる。
8. **決定性**: 最後はunitIdで決める。

候補の除去で生じる基礎メッシュの穴は、隣接FIELDを一斉に並べ直して埋めない。必要なら1機ずつmake-before-breakで移動し、200 tick以内の再移動を抑止する。

安全に供出できるFIELDがない場合は次のように処理する。

| Demand | FIELD不足時 |
|---|---|
| プレイヤー危機、Power Lost | 低優先度の外周cellを縮退し、接続を壊さない範囲で供出。なお不足する場合は部分経路と不足数を表示 |
| Strike Beacon、実行中戦闘 | 既存critical pathを壊さず供出。成立しなければ接続済み機だけで局所対応 |
| Salvage | 対象情報を保持して`WAIT_RELAY`。Power Lost対象の表示は維持 |
| 作業、物流、巡回 | 基礎メッシュを崩さず`WAIT_RELAY` |

優先度の高いDemandでも、別のcritical endpointを無通知で切断してはならない。UIは「FIELD不足」「供出すると失われる範囲」「必要追加数」を表示し、ユーザーが強制縮退を選んだ場合だけ確認済み範囲を切断する。

### 6.7 動的branchの終了

1. `RELEASE_GRACE_TICKS`中は再利用できるよう現在位置を維持する。
2. 同地域の次Demandがなければ、基礎メッシュの未充足slotを検索する。
3. slotがあれば`BASELINE`または`MESH_RESERVE`へ復帰する。
4. 整備が必要ならmake-before-break後にServiceへ移る。
5. 基礎メッシュが充足済みで保存任務もない場合だけDock待機を選ぶ。空中`STANDBY`にはしない。

### 6.8 再計画trigger

次の場合は通常周期を待たず再計画する。

- FIELD、Controller、重要endpointの消失またはdimension変更。
- FIELDのPower Lost。
- Strike Beaconの新規起動・消滅。
- プレイヤー危機の発生。
- active missionの開始・終了。
- linkが`LINK_DROP_RANGE`を超えた。
- FIELDの追加、回収、整備完了。
- 戦略anchorの登録、解除、破壊。
- 基礎メッシュの被覆穴が40 tick継続した。

軽微な位置変化、品質帯内の変動、短時間の対象速度変化では再計画しない。基礎メッシュの再配置は緊急切断を除き`BASELINE_REBALANCE_COOLDOWN`を守る。

### 6.9 FIELD不足時の具体例

利用可能FIELDが12機で遠隔Demandがない場合、10機を`BASELINE`、2機を`MESH_RESERVE`へ配置する。遠隔戦闘に3機のrelayが必要になった場合は、まず2機のMESH_RESERVEを使い、残り1機だけを基礎メッシュから供出する。

供出機は戦闘地点への近さだけで決めない。あるFIELDを抜くとDockと4機が切断され、別のFIELDを抜くと低優先度の空白cellだけが未被覆になるなら、後者を選ぶ。後者が遠くても通信網の損失を優先する。必要なら空いたslotの隣接機を1機だけhandoff移動させてから供出する。

遠隔戦闘が終了したFIELDは200 tickの猶予後、基礎メッシュの空きslotへ戻る。同時に別の高優先度Demandが発生していれば、そのbranchへ直接再割当する。安全に抜ける機体がない場合は既存網を壊さず、接続済み戦力だけで対処し、UIへ`SHORT 1`と理由を表示する。

## 7. Make-before-breakと充電交代

FIELDが充電、修理、別任務へ移る場合は次の順序を必須とする。

1. 代替FIELDを予約する。
2. 代替FIELDを新しい配置点へ移動する。
3. 上流と下流の両方へ接続できたことを確認する。
4. primary pathを代替FIELDへ切り替える。
5. 最低10 tick連続で接続が維持されたことを確認する。
6. 旧FIELDを解放する。
7. 旧FIELDは保存済み任務、Solar Service、Dock帰還の順で復帰先を選ぶ。

代替機がない場合、旧FIELDは安全帰還に必要な電力を下回るまで待機してはならない。配下を`DEGRADED`へ移し、帰還を優先する。通信を維持するためにFIELDをPower Lostさせることは禁止する。

Solar Service Stationは通信relayではないが、FIELDの充電先候補になる。充電へ離脱する前に上記handoffを完了する。

## 8. 通信断時の共通契約

| 機能 | 通信断時 |
|---|---|
| 飛行・浮力 | 継続。通信断を理由に墜落させない |
| 受理済みWaypoint/route | 保存済みrouteで継続 |
| 新規遠隔命令 | `PENDING_LINK`として保留。対象機へ適用しない |
| ローカル敵対Mob検知 | 継続 |
| 自己防護・局所迎撃 | 継続 |
| 遠隔増援要求 | 機内queueへ保存し、再接続後に送信 |
| Dock帰還 | 保存済みDock、動的Dock予約、owner退避の順で継続 |
| Dock補給・修理 | 通信に依存せず継続 |
| Cargo既存輸送 | 保存済みsource/targetで継続 |
| 新規Cargo経路 | 保留 |
| 任務履歴・telemetry | 機内bufferへ保存し、再接続時に同期 |
| Power Lost beacon | 独立緊急周波数として全距離表示を維持 |

`OFFLINE_CACHED`中に任務対象が消失した場合は、既存のtask validationとsafe fallbackを使う。通信復旧まで無効な対象を追い続けてはならない。

## 9. Scoutの圏外偵察

Scout routeには次の二つの運用形態を持たせる。

| 方式 | 挙動 |
|---|---|
| `NETWORKED_RECON` | FIELD展開完了後に出発し、リアルタイム報告する |
| `DEEP_RECON` | routeを機内保存して圏外巡行する。報告は遅延する |

### 9.1 圏外で高脅威を発見した場合

1. 敵性判定と既存Threat評価を実行する。
2. Scoutは攻撃せず、距離確保と回避を優先する。
3. entity、位置、速度、進行方向、脅威、観測時刻を保存する。
4. 1機運用では最後の通信地点へ戻り、store-and-forwardする。
5. 同一Scout群に2機以上いて局所通信可能なら、1機を追跡、1機をcourierへする。
6. 再接続時に報告を送信し、経過時間を含む`LAST_KNOWN` contactとして登録する。
7. controllerはFIELD展開要求を生成する。
8. FIELD branch成立後、Scoutが再捕捉し、最新情報で戦力配分する。

FIELD不足時はSecurityを自動で盲目的に遠隔投入しない。routeを危険区域として保留し、必要FIELD数、最終観測位置、情報経過時間を表示する。ユーザーの強制出撃は許可するが、通信断と情報鮮度を命令確認へ明示する。

## 10. Strike Beacon

Strike Beaconはrelayではなく、owner情報を持つ攻撃命令終端局とする。

### 10.1 通信

- 戦略通信はController/FIELD網を使用する。
- ビーコン周辺128 blockでは局所射撃管制を使用する。
- FIELDなしでもController直接圏またはビーコン局所圏のSecurityは参加できる。
- 遠隔Securityの募集には接続済みFIELD pathが必要である。

### 10.2 切断時

| 時点 | 挙動 |
|---|---|
| 命令承認前 | 攻撃せず`WAIT_LINK` |
| 機体割当後・攻撃前 | 割当済み機は保存された座標へ移動。新規増援なし |
| 局所管制圏到達後 | `LOCAL_CONTROL`で攻撃継続 |
| 攻撃中 | sealed salvoを完遂。遠隔兵装変更・中止は再接続まで不可 |
| 終了後 | 元任務またはDockへ自律復帰し、再接続時に戦果を送信 |

最大3 beacon、beaconごとの独立開始、安定した均等配分、salvo clockは現行契約を維持する。新しい高優先度beaconは、すでにsealedされた攻撃群を奪わない。

## 11. Chunk稼働契約

通信網が遠隔地へ届いていても、Entityが非稼働chunk内で停止しては意味がない。active relayは有界なchunk leaseを持つ。

1. `RELAY_HOLD`中のFIELDは現在chunk一つだけをentity-ticking対象としてleaseする。
2. `DEPLOYING`中は進行先chunkを先にleaseし、通過後に旧chunkを解放する。
3. active mission clusterは既存の任務稼働機構を使用し、通信機能だけで無制限に周辺chunkを読むことは禁止する。
4. owner・dimension上限16、server全体上限64を超えたrelayは`WAIT_CAPACITY`にする。
5. owner offlineまたは別dimension移動後200 tickで不要なrelay leaseを解放する。
6. lease枯渇時は低優先度branchから停止する。

chunk lease APIの実装差で上記を安全に満たせない場合、通信接続を偽装せず`UNLOADED`と表示する。

## 12. データモデル

### 12.1 保存する情報

- FIELDのrelay assignment ID。
- relay state、`RelayDuty`、配置anchor、基礎mesh slot ID。
- 担当するdemand ID。
- handoff相手と開始tick。
- ownerが登録した戦略anchorと有効・無効状態。
- endpointが最後に受理したcommandと任務snapshot。
- Scoutの未送信contact buffer。
- Strike Beaconのsealed rosterとoperation state。

### 12.2 保存しない情報

- graphのedge一覧。
- primary/secondary pathの計算結果。
- link品質。
- 自動生成した基礎mesh slot一覧。
- UI用coverage polygon。

派生graphはworld load後に再構築する。壊れたnode ID、消失した任務、別dimensionの参照は破棄し、安全fallbackへ移る。

## 13. UI/HMI契約

### 13.1 コントローラー

作戦地図へ次を追加する。

- ControllerとFIELDの通信圏。
- FIELD間のprimary linkとsecondary link。
- 基礎メッシュ、RESERVE、動的branchの表示区別。
- `DIRECT / RELAY n / DEGRADED / LOCAL / OFFLINE`。
- branchが支えるWing、任務、endpoint数。
- `FIELD 12 / BASE 8 / RESERVE 2 / DYNAMIC 2 / SHORT 1`形式の割当集計。
- 未被覆の戦略cell、基礎メッシュから供出予定のFIELD、供出後に低下する範囲。
- 任務発令前の必要FIELD数と不足数。
- `DEPLOYING / HANDOFF / WAIT_CAPACITY / WAIT_FIELD`。
- 通信断機の最終受信位置と経過時間。

通信圏表示は切替可能にし、通常時の地図を覆わない。通信不能を赤だけに依存せず、切れた線、状態文字、最終更新時刻を併記する。

自動通信網は初期値ONとする。ユーザーは戦略anchorの追加・解除と強制縮退を操作できるが、個々のFIELD位置を通常操作で管理しなくても成立させる。強制縮退の確認画面には、切断見込みのWing、任務、Dock、cell数を表示する。

### 13.2 Visor

Visorは世界内表示と下部3D空域図を切り替えて使う。通常戦闘情報を押しのけない範囲で、FIELDの親子関係を確認できる詳細graphを表示する。

- 通常: `LINK D`または`LINK R2`。
- 低品質: `LINK LOW`。
- 通信断: `LOCAL`と最終同期経過時間。
- Strike Beacon: `LOCAL CONTROL`。
- FIELD: 配下数、handoff、電力低下だけを優先表示。
- 世界内FIELDマーカー: `F-B/F-R/F-D 自機ID > 親ID`。Controller直結は親を`CTRL`とする。
- 3D空域図: Minecraftの地表高とmap colorから薄い立体地形板を生成し、その上へController、FIELD node、確立link、展開先、接続点、空域内ドローンを同一のXYZ座標系で斜投影する。各FIELDの物理通信圏は半径128 blockの球のままだが、表示では球をnode数だけ重ねず、確立済みメッシュ全体を包む単一の上半球ドームへ集約する。ドーム底面は全体平均高ではなく、ドーム中心直下の地表高へ接地させる。
- 地形は11x11点、最大121地点だけを読み込み済みchunkから2秒ごとに再取得する。地形板には外周の厚みと高度陰影を付けるが、未読み込みchunkをHUD目的で読み込まない。面塗りは4 pixel単位の走査線とし、全cellの罫線は描かない。高層建造物や深い縦穴の単一sampleで地形板が画面高の尖りにならないよう、標高差はdatumを維持した双曲線正接で±40 block以内へ滑らかに表示圧縮する。通信ドームの接地高にも同じ圧縮を適用する。
- ドローンは役割別シンボルで表示する。地表への垂線と通常機から接続nodeへ伸びる線は、機体数に比例して地形を覆い描画負荷も増やすため表示しない。展開中FIELDも長い移動線を描かず、展開先をamberの点で示す。線で表示するのは確立済みFIELD間linkだけとし、通信状態は上部集計と機体色で補完する。
- FIELD nodeと親子linkは最大64 node、空域内機体は優先度順最大48機まで描画する。通信圏ドームはnode数にかかわらず1個とし、描画線数をメッシュ規模へ比例させない。ワールド表示と3D空域図は同時描画せず、空域図を開いている間は空域図だけを使う。これを超える機体情報は件数へ集約する。
- 空域範囲は最小±160 block、最大±384 blockとし、通信網の広がりへ自動追従する。
- 下部空域図は警告rail、inspection、hotbarを避ける候補位置を選び、空きがなければ表示しない。重要警告を隠してはならない。

### 13.3 命令transaction

通信を理由に適用できない命令は無反応にしない。

- `ACCEPTED`: 接続済みで機体が受理。
- `PENDING_LINK`: 接続またはFIELD展開待ち。
- `PARTIAL`: 複数機の一部だけ受理。
- `REFUSED_NO_ROUTE`: FIELD不足または上限到達。
- `FAILED_STALE_TARGET`: 待機中に対象が無効化。
- `COMPLETED`: 実行完了。

## 14. 性能設計

### 14.1 禁止する実装

- 全ドローン同士の毎tick総当たり。
- 全blockまたは全chunk走査によるrelay配置。
- 毎tickの全tree再最適化。
- path品質変動だけを理由にしたFIELD再配置。
- UI表示のためだけのserver全Entity走査。

### 14.2 採用する実装

- cell size 128 blockの3次元spatial hash。
- nodeは自cellと隣接cellだけを探索。
- endpointは最寄りの接続候補を有界数だけ保持。
- link更新は10 tick、配置計画は20 tick。
- 通常はownerごとにdirtyなgraphだけ再計算。
- 1 tick内の計画処理へ時間budgetを設け、超過分は次tickへ継続。
- graph到達判定はBFS、primary pathは品質とhop数を使う決定的なshortest path。

目標計算量はgraph更新`O(V + E)`、配置候補探索はspatial hashにより平均`O(V + K)`とする。`K`は近傍候補数であり、全機数に比例させない。

## 15. 互換性と段階移行

### 15.1 保存互換性

- `DroneRole.FIELD`、item ID、entity ID、Solar Service Station IDを変更しない。
- 既存FIELDは通常状態で読み込み、新relay stateを持たなければ`IDLE`へ移行する。
- Solar Service Stationは既存の充電機能と保存電力を維持する。
- 既存mission、Wing、task stack、Dock assignmentを削除しない。

### 15.2 導入段階

1. **Characterization**: 現行の無制限通信を試験で固定する。
2. **Shadow graph**: 新graphを計算・表示するが、命令を制限しない。
3. **FIELD deployment**: FIELD配置とhandoffだけを有効化する。
4. **Telemetry gating**: 圏外telemetryを最終位置表示へ変更する。
5. **Command gating**: 新規遠隔命令を`PENDING_LINK`へする。
6. **Threat gating**: Scout/脅威報告を通信経路へ接続する。
7. **Strike Beacon integration**: 遠隔募集と局所管制を分離する。
8. **Solar relay removal**: Solarのrelay登録を削除する。

既存world初回load後600 tickは移行猶予とする。稼働中任務はcached commandとして継続するが、新規圏外命令には猶予を適用しない。猶予中にFIELD plannerが必要経路を提示・構築する。

各段階はfeature flagで切り戻せるようにし、次段階へ進む前に全回帰試験を通す。

## 16. テスト方針

### 16.1 層構造

| 層 | 対象 | UI操作 |
|---|---|---|
| L0 Characterization | 現行挙動と互換性baseline | 不要 |
| L1 Pure policy | 距離、graph、planner、handoff、優先度 | 不要 |
| L2 Integration | registry、SavedData、payload、command transaction | 不要 |
| L3 Headless runtime | 実Entity、chunk、移動、再接続、戦闘 | 不要。commandとlogで実行 |
| L4 Client contract | 地図、Visor、表示文字、上限 | 原則不要。描画入力をfixture化 |
| L5 Minecraft visual | coverage、link、FIELD移動、Beacon表示 | 最終確認だけ必要 |
| L6 Multiplayer/soak | 複数owner、長時間、低性能server | UI不要 |

### 16.2 L1境界値試験

必須境界値:

- 距離`0 / 64 / 96 / 112 / 119.999 / 120 / 120.001 / 127.999 / 128 / 128.001`。
- 水平距離だけ同じで高度差が異なる場合。
- direct 0 hop、1 hop、16 relay、循環graph、孤立node。
- owner違い、dimension違い、死亡node、Power Lost node。
- 同品質・同距離時の決定的tie break。
- FIELD 0、必要数ぴったり、不足1、上限超過。
- 基礎メッシュのFIELD数`0 / 1 / 3 / 4 / 9 / 10 / 16`と`MESH_RESERVE`境界。
- 通常配置の距離`43.999 / 44 / 44.001`。
- mesh展開後にプレイヤーが`47.999 / 48 / 128 / 512 block`移動し、`39 / 40 / 200 tick`経過してもController anchorが不変であること。
- 全FIELD撤収前の追加展開では既存anchorを共有し、全FIELD撤収後の新規展開だけが現在の命令地点をanchorにすること。
- 保存・再読込後に同じanchorを復元し、旧保存では認可済みFIELD群の重心を一度だけ採用すること。
- 登録基地半径の`63 / 64 / 128 / 512 / 513 block`入力。範囲外入力は64～512へ正規化する。
- 40 tick未満の需要変動では再配置しない。
- 40 tick継続後は一度だけ再配置する。
- 基礎再配置cooldownの`199 / 200 / 201 tick`。
- release graceの199 / 200 / 201 tick。

### 16.3 Planner試験

1. FIELDを三角格子slotへ均等配置し、戦略領域外へ無駄に広げない。
2. FIELDが1機追加されたとき最大の被覆穴だけを埋め、既存全機を再配置しない。
3. FIELDが1機減ったとき低優先度の外周slotから縮退する。
4. FIELD数4と10で、それぞれ`MESH_RESERVE`を1機と2機確保する。
5. `MESH_RESERVE`を単独bridgeにしない。
6. 遠隔anchor間の巨大な凸包を埋めず、anchor周辺とcorridorだけを覆う。
7. 未登録chest、通過地点、敵contactが基礎領域を拡張しない。
8. 空Dockは基礎領域を恒久的に拡張せず、稼働・収容・予約条件が消えた後に解放猶予を経て除外する。
9. 1本の遠隔routeへ最小限のFIELDを配置する。
10. 共通幹線を持つ2任務が途中まで同じFIELDを共有する。
11. critical demandだけsecondary pathを得る。
12. FIELD不足時に低優先度branchを待機させる。
13. active task、service、Power Lost機を候補にしない。
14. 同じ入力から常に同じ配置結果を得る。
15. routeの小さな揺れで配置点を変更しない。
16. Controllerを持つプレイヤーが移動しても基礎slotを再配置せず、遠隔任務は必要な動的branchだけを追加する。
17. 任務終了後に動的FIELDを基礎メッシュへ戻す。
18. 基礎メッシュ充足時だけ不要機をDock待機へ移し、空中`STANDBY`にしない。

### 16.4 供出元選定試験

- 最近傍FIELDがarticulation nodeなら選ばず、遠くても安全な`MESH_RESERVE`を選ぶ。
- 候補除去を仮計算し、critical endpointを切断する候補を必ず棄却する。
- 被覆損失、冗長性損失、品質低下、到着性、公平性、unitIdの順で決定する。
- 低優先度の空白cellと稼働Dockが競合した場合、空白cellを縮退する。
- 低優先度Demandは基礎メッシュを崩さず`WAIT_RELAY`になる。
- プレイヤー危機では安全な外周cellを縮退して増援する。
- 安全な候補がない場合、FIELDを移動させず不足数と理由を返す。
- 強制縮退は確認されたcellだけを切断し、影響一覧と実結果が一致する。
- 同時Demandを優先度順に処理し、先に成立した高優先度branchを後発低優先度Demandが奪わない。
- 任務終了後、解放FIELDが元機体ではなく現在の最適な空きslotへ復帰してもassignment churnを起こさない。

### 16.5 Handoff試験

- 代替FIELD接続前に旧FIELDを解放しない。
- primary path切替後10 tick接続を確認する。
- handoff中に代替機が消失したら旧FIELDを維持する。
- 旧FIELDが安全帰還限界なら通信維持より帰還を優先する。
- 配下endpointから見て、正常handoff中に切断snapshotが一度も発生しない。
- 充電完了後、必要なら元relay dutyへ復帰し、不要なら保存済み任務へ戻る。

### 16.6 通信断・任務回帰試験

- Waypoint、route、Wing、Cargo、Engineer、Salvage、Combatが通信断だけで消えない。
- 通信断だけで`STANDBY`、Power Lost、Dock帰還へ遷移しない。
- 飛行電力低下は通信状態に関係なくDock帰還を開始する。
- 失われた対象はsafe fallbackし、無限追跡しない。
- 再接続時に古い命令を新しい命令より後から上書きしない。
- event bufferは重複送信せず、上限超過時は低優先度から集約する。

### 16.7 Scout試験

- `NETWORKED_RECON`はFIELD接続完了前に出発しない。
- `DEEP_RECON`は保存routeで圏外巡行できる。
- 高脅威検出時にScoutが攻撃せず回避する。
- 単機Scoutが最後の通信地点へ戻って報告する。
- 2機Scoutがtracker/courierへ決定的に分担する。
- 報告へ観測時刻、最終位置、速度、脅威を保持する。
- 古いcontactを現在位置として扱わない。
- FIELD不足時にSecurityを自動盲目的投入しない。

### 16.8 Strike Beacon試験

- FIELDなし・Controller直接圏で近傍Securityだけが参加する。
- FIELDなし・遠隔Securityは参加しない。
- FIELD path成立後に遠隔Securityが参加する。
- 承認前切断では射撃しない。
- sealed rosterは切断後も局所管制でsalvoを完遂する。
- 切断後に新規増援を追加しない。
- 最大3 beaconは独立開始し、互いの到着を待たない。
- 後発高優先度beaconがsealed rosterを奪わない。
- 既存の均等配分、timeout、全兵装枯渇、音・軌跡event試験を維持する。

### 16.9 Solar、Dock、物流回帰試験

- Solar Service Stationをrelay一覧へ登録しない。
- Solarの最大3機充電、子機、充電beam、任務復帰を壊さない。
- Dock補給、修理、弾薬、動的割当は通信断中も継続する。
- 自動補給Cargoの既存routeは継続し、新規圏外routeだけ保留する。
- Cargoが補給後に不要な`STANDBY`へ落ちない。
- Salvage牽引中にrelay dutyへ奪わない。

### 16.10 保存・移行試験

- 旧worldのFIELD、Solar、Wing、route、Dockを読み込める。
- graphを保存せず再起動後に同等のpathを再構築できる。
- handoff途中、FIELD展開途中、Scout未送信report、sealed beaconを保存・復元できる。
- 戦略anchorとRelayDutyを復元し、派生slotとgraphは保存値へ依存せず再構築できる。
- 壊れたUUID、消失node、別dimension参照を安全に除去する。
- 600 tickの移行猶予が既存任務だけへ適用される。

### 16.11 Multiplayer試験

- 2～4 ownerのFIELD、contact、command、Beaconが混ざらない。
- 他ownerのFIELDをpathへ使わない。
- owner logout、dimension変更、再loginでleaseを正しく解放・再構築する。
- 同じ座標に異なるownerの通信網があっても情報漏洩しない。
- server operatorであっても通常のowner境界を暗黙に越えない。

### 16.12 性能・耐久試験

机上試験:

- 128 drones、16 FIELD、4 route、3 beacon、200 contacts。
- 4 owner、合計512 drones、64 FIELD。
- candidate比較回数とedge数へ上限assertionを置く。
- graph更新が全ドローン二乗比較へ退行していないことを検査する。
- 基礎slot生成を戦略領域cell数に比例させ、world面積や未登録blockを走査しない。
- 供出候補の除去simulationは最大16候補の有界処理とし、tickをまたぐ場合も同じsnapshotから決定する。

headless dedicated server:

- 30分soak。
- 16 FIELDの展開、交代、充電、再接続を反復。
- 4つの同時任務と3 beaconを反復。
- watchdog停止なし、例外なし、task queue無限増加なし。
- network subsystemの処理時間p95を2 ms未満、full server MSPT p95を45 ms未満とする。
- 一時的な再計画burstが50 msを超えた場合は失敗原因を記録し、分割実行へ修正する。

低性能CPUで時間値が安定しない単体試験では、実時間だけを合否条件にしない。候補比較回数、edge数、処理対象数、1 tick budget超過時の継続処理を併用する。

## 17. 実装順序とGate

### Gate 0: Baseline

- 現行全JUnitを成功させる。
- 現行の無制限通信、Scout、Beacon、Solar、Dockをcharacterization testへ固定する。
- 実装前のMSPTと計算対象数を記録する。

### Gate 1: Pure graph

- spatial index、link、quality、path、基礎mesh、供出simulation、動的branch plannerをMinecraft Entityから分離して実装する。
- L1、planner、性能机上試験を成功させる。
- ゲーム挙動はまだ変更しない。

### Gate 2: Shadow mode

- serverで新graphを計算し、管理画面だけに表示する。
- 現行通信は維持する。
- 実際の機体位置で誤接続、過剰FIELD数、揺れを検出する。

### Gate 3: FIELD deployment

- FIELDの基礎mesh均等配置、`MESH_RESERVE`、動的branch、予約、展開、保持、handoff、解放を有効化する。
- 命令とScout報告はまだ遮断しない。
- headless runtimeでroute treeと充電交代を確認する。

### Gate 4: Communication enforcement

- telemetry、command、Mission Data Link、Fleet Threat Networkを順番にgraphへ接続する。
- 各段階で全回帰試験を実施する。
- 一度にすべてを切り替えない。

### Gate 5: Beacon、Scout、Solar

- Deep Reconとstore-and-forward。
- Strike Beaconの戦略通信と局所管制分離。
- Solar relay削除と充電回帰。

### Gate 6: Minecraft確認

- 通常worldを変更しない専用fixtureで自動シナリオを実行する。
- logとcommandで機能試験を完了後、coverage円、link線、FIELD移動、Visorだけを目視確認する。
- 通常worldへの差し替えは全Gate成功後に行う。

## 18. 完了条件

次をすべて満たした場合のみ完成とする。

1. FIELDなしの近距離運用が従来どおり成立する。
2. 圏外の新規命令が理由表示なしに消えない。
3. FIELDがrouteと複数任務からtreeを自律構築する。
4. 平常時のFIELDが戦略通信領域へ均等配置され、余剰機が意味なく空中待機しない。
5. 動的branchへの供出で既存critical pathを無通知に切断しない。
6. FIELD不足時に、供出不能理由、失われる範囲、必要追加数を説明できる。
7. FIELD交代で正常時の通信断が発生しない。
8. 通信断中も受理済み任務、自律帰還、Dock、局所防御が壊れない。
9. ScoutのDeep Reconと高脅威報告が成立する。
10. Strike Beaconが近距離、遠隔、切断後の全ケースで契約どおり動く。
11. Solarの充電機能を維持し、通信relayだけを削除する。
12. owner、dimension、mission境界を越える情報漏洩がない。
13. 既存worldと保存Entityを移行できる。
14. 全既存試験と本仕様の新規試験が成功する。
15. 低性能serverのsoakでwatchdog停止と著しいMSPT悪化がない。

## 19. 今回の非対象

- block材質や天候による電波減衰。
- dimensionをまたぐ通信。
- 他ownerとの通信網共有。
- 母艦を通信rootまたはrelayにする機能。
- FIELD以外のドローンによる通常relay。
- packet latency、jitter、確率的packet lossのsimulation。
- FIELD専用外観、animation、soundの変更。

これらは基本通信網が安定した後に、別仕様として設計・試験する。
