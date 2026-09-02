# Morrowgear Drone 制御アーキテクチャ・実機化設計指針

## 0. 文書情報

| 項目 | 内容 |
|---|---|
| 対象 | Morrowgear: Drone Command Java Mod |
| 実装版 | v0.19.1 |
| Minecraft | Java Edition 26.2 |
| 基準日 | 2026-09-02 |
| 文書状態 | 現行コードから抽出した実装仕様 + 実機化する場合の推奨設計 |

本書は、Morrowgearのドローンが「どの情報を受け取り、どの処理を通り、どう移動するか」をコード単位で説明する。後半では、同じ製品概念を実ドローンへ移植する場合に必要な制御、通信、安全設計を分離して記載する。

表記は次のように区別する。

- **現行実装**: v0.19.1のJavaコードから確認できる事実。
- **推奨**: 実機化する場合の設計案。現行Modに実装済みであることを意味しない。
- **禁止**: Mod上の兵器・攻撃機能を実機へ移植することは本書の対象外とする。実機のSecurity roleは監視、通報、照明、退避支援等の非武装用途へ限定する。

関連文書:

- [現行機能仕様](../Morrowgear_Drone_現行仕様.md)
- [状態遷移・優先度監査](../design/MORROWGEAR_DRONE_STATE_TRANSITION_AUDIT.md)
- [複数戦域仕様](../design/MORROWGEAR_COMBAT_THEATER_SPEC.md)
- [サービス帰還ポリシー](../design/AUTO_COMBAT_SERVICE_POLICY.md)
- [ライフサイクル・物流仕様](../design/MORROWGEAR_LIFECYCLE_LOGISTICS_SPEC.md)
- [自律運用・Task Force仕様](../design/MORROWGEAR_AUTONOMOUS_OPERATIONS_SPEC.md)

### 目次

1. [現行アーキテクチャの結論](#1-結論-現在は中央指令自律飛行協調隊列)
2. [個体ドローンの制御](#4-個体ドローンの制御パイプライン)
3. [Waypoint航行](#6-waypoint航行)
4. [Wing・Cohort制御](#7-wingcohort制御)
5. [同期・データリンク](#8-同期とデータリンク)
6. [障害物回避・電力・役割](#9-障害物回避recovery)
7. [現行Modの限界](#12-現行modの設計上の限界)
8. [実ドローン向け推奨設計](#13-実ドローン向け推奨アーキテクチャ)
9. [段階的な実機検証](#15-実機化の段階的検証)
10. [コードと一次資料](#16-現行コード参照)

## 1. 結論: 現在は中央指令・自律飛行・協調隊列

現行方式を一文で表すと、**操作指令はサーバーへ集約し、各機の飛行は個別に計算し、Wingは共有された任務情報とリーダー状態を参照して協調するハイブリッド方式**である。

```text
Tactical Dashboard
  │ 選択機ごとのDroneCommandPayload
  ▼
Minecraft Server
  ├─ 機体ごとに所有権・入力を検証
  ├─ 各機へ同じmissionIdと個別indexを設定
  ├─ 任務・Scout・脅威の共有registryを保持
  └─ 各DroneEntityを毎tick更新
       ├─ 自機状態を評価
       ├─ 最終飛行目標を選ぶ
       ├─ Wing leaderと自分のslotを参照
       ├─ 地形回避と機体間分離を加える
       └─ 自機の次速度を計算する
```

親ドローンが指令を受けて子へ再送する構造ではない。Leaderは飛行隊形の基準機であり、通信中継器ではない。したがってLeaderを失っても、後継機を選べれば任務情報そのものは失われない。

## 2. 制御対象と座標・時間

### 2.1 制御周期

Minecraft Serverは通常20 tick/秒で動作する。主要な飛行目標と速度は各server tickで更新される。処理負荷を抑えるため、次の処理は周期を落としている。

| 処理 | 周期 |
|---|---:|
| 飛行目標・速度・姿勢方位 | 1 tick |
| Wing leader健全性・交代判定 | 10 tick |
| 航行進捗・stuck判定 | 10 tick |
| Scout報告生成 | 10 tick |
| 飛行電力消費 | 20 tick |
| Solar Service再評価 | 20 tick |

### 2.2 単位

- 位置: `Vec3`、単位はblock。
- 速度: block/tick。
- 時間: server game tick。
- 方位: Minecraft yaw degree。
- missionの水平入力: X/Z。Yは地形Heightmapからserverが決定する。

実機のm、m/s、rad/sへ値をそのまま転用してはならない。Minecraftには質量、推力、空気抵抗、ローター慣性、センサー雑音が存在せず、現行係数はゲーム内の見た目と操作感に対する値である。

## 3. 指令の配布

### 3.1 ClientからServer

統合戦術画面は選択された各ドローンへ、`entityId`と文字列`action`を持つ`DroneCommandPayload`を一件ずつ送信する。

地点移動では、各機へ次の情報が送られる。

```text
move:<worldX>:<worldZ>:<missionId>:<expected>:<index>
```

| 値 | 意味 |
|---|---|
| `worldX`, `worldZ` | 指定地点 |
| `missionId` | 同じ任務・同じWingを束ねる識別子 |
| `expected` | この編隊で予定する機数 |
| `index` | 機体の編隊内index |

追跡は`track`、巡回は`patrol`、追従は`follow`を使うが、いずれも同じmission identity、expected、indexの考え方を共有する。任務規模の入力上限は128機、恒久Wingの基本上限は8機である。

### 3.2 Server側の検証

Serverは次を確認してから機体状態を書き換える。

1. actionが空でなく256文字以下か。
2. entityが実在する`DroneEntity`か。
3. 指令者が機体ownerか。
4. missionId、expected、indexが許容範囲か。
5. 指定地点または対象がplayerから許容距離内か。
6. roleがその任務を実行できるか。

不正なpacketは破棄される。clientの表示だけでは指令受理の証拠にならない。

### 3.3 現行方式の整合性

複数機命令は一件の原子的な群commandではない。clientが機体数分のpacketを送るため、途中で一部packetだけ欠落・拒否される可能性がある。serverは40 tick後、実際に同一任務へ参加した全assigned rosterを基準に`expected`と`index`を縮小・再採番する。

この仕組みはゲーム内の部分受理を自己収束させるが、実機の指令保証としては不十分である。実機案は13.4節で定義する。

## 4. 個体ドローンの制御パイプライン

各`DroneEntity`はserver側で次の順に処理される。

```text
1. 浮力・電力状態
2. ownerとchunk稼働条件
3. service / role task / threat / combat更新
4. operational stateとleader健全性
5. flight intentから目標位置を選択
6. terrain navigationで安全な中間目標へ変換
7. 機体間separationと速度上限を計算
8. 現速度から次速度へsteering
9. yawを進行方向へ追従
10. clientへ位置・状態をMinecraft標準同期
```

### 4.1 入力

| 分類 | 主な入力 |
|---|---|
| 自機 | position、velocity、mode、battery、health、role、subsystem、recovery level |
| 任務 | missionId、expected、index、waypoint、route、stage、cohort、leader、rank |
| Wing | member位置、member速度、leader状態、slot、到着数 |
| 環境 | block collision、fluid、Heightmap、空間開放度、天井余裕、明暗 |
| 外部対象 | owner位置・速度、追跡Entity、Dock、作業対象、脅威 |
| 共有情報 | Scout report、threat contact、combat allocation、service reservation |

### 4.2 飛行意図の優先順位

表示上の最上位状態は次の順で解決する。

```text
POWER_LOSS
  > DOCKED
  > SERVICE_RTB
  > COMBAT
  > EMERGENCY_INTERCEPT
  > REJOIN
  > FIELD_OPERATION
  > SECURITY_PATROL
  > ENGINEER_SUPPORT
  > CARGO_ROUTE
  > WAYPOINT
  > FOLLOW
  > RETURN
  > ORBIT
  > STANDBY
```

実際の目標位置も、戦闘、緊急迎撃、Solar Service、警戒、Field、Engineer、基本modeの順で選ばれる。上位状態は下位任務を消すのではなく、一時中断し、復帰時にtask stackと対象の有効性を再評価する設計を採っている。

### 4.3 ホバリングと電力喪失

通常飛行中は毎tick `setNoGravity(true)`と`fallDistance = 0`を設定する。そのためowner不在や任務データリンク切断だけでは落下しない。`STANDBY`空中時は現在速度を22%へ減衰し、ほぼ停止する。

バッテリーが0になった場合は例外である。

1. 戦闘、迎撃、light、navigationを停止する。
2. 現在taskを再開用stackへ保存する。
3. modeを`STANDBY`へする。
4. salvageで吊り下げ中でなければ`setNoGravity(false)`にする。
5. 落下速度を適用して墜落させる。
6. ownerへPower Lost beaconを送る。

したがって現行仕様は「連携断では落ちないが、電力喪失では意図的に落ちる」である。

### 4.4 目標位置の生成

最終目標は単なるWaypointそのものとは限らない。

- FOLLOW: owner周囲の環境適応slot。
- WAYPOINT: leaderの巡航目標、またはleader基準の編隊slot。
- CONVERGING: leaderの移動slotへ追いつく位置。
- ORBIT: 時刻、index、半径、位相から計算した円軌道点。
- DOCK: 外周待機点、gate、着艦点からなる段階目標。
- COMBAT/FIELD/SALVAGE: 専用flight plannerが生成した目標。

### 4.5 速度制御

通常のsteeringは、位置誤差から希望速度を作り、現在速度へ一次遅れで追従させる簡易制御である。

```text
positionError = target - currentPosition
requestedSpeed = min(speedLimit, 0.03 + distance * 0.075)
desiredVelocity = normalize(positionError) * requestedSpeed + separation
nextVelocity = currentVelocity * (1 - response) + desiredVelocity * response
```

長い経路の残距離がある場合は、近い中間目標だけを見て過度に減速しないよう残距離項を加える。目標から0.35 block未満では速度を35%へ落とす。

主な速度上限:

| 状況 | 上限 block/tick |
|---|---:|
| 通常巡航の基準 | 0.44 |
| 距離がある通常移動・追いつき | 最大0.96 |
| Dock帰還巡航 | 最大1.08 |
| Dock approach | 0.28 |
| Dock final | 0.14 |
| 緊急移動 | 最低1.05を確保 |
| レーザー旋回 | 0.46 |
| 機関砲attack run | 0.92、離脱1.14 |

これはPIDによる機体力学制御ではない。Minecraft Entityの速度vectorを直接設定するゲーム用guidance lawである。

### 4.6 機体間分離

近傍owner機から離れる反発vectorを希望速度へ加える。

| 状態 | 検出半径 | 最大補正 |
|---|---:|---:|
| 通常 | 2.1 block | 0.18 |
| tight formation | 1.35 block | 0.07 |

ドローン同士はMinecraftの物理衝突対象からも外している。つまり、見た目上の重なりをseparationで減らしつつ、接触による押し合いで隊形が壊れないようにしている。

### 4.7 姿勢

server側yawは水平速度方向へ最大`6 + speed * 20`度/tick、上限18度/tickで追従する。pitchとrollはclient rendererが速度から生成する視覚表現であり、推力vectorや機体運動へはフィードバックされない。

## 5. 基本mode

| Mode | 目標 | 到達・維持方法 |
|---|---|---|
| `STANDBY` | 現位置 | 空中ではbrake、Dock上では完全停止 |
| `FOLLOW` | owner周囲の割当slot | 環境適応の円・傘・縦列を維持 |
| `ORBIT` | owner周囲の時変slot | owner移動方向と環境を反映して旋回 |
| `RETURN` | owner後方上空 | 近づいたらFOLLOWへ戻る |
| `WAYPOINT` | 指定地点またはroute | mission stageとWing制御を使う |
| `DOCK` | assigned Dock | 外周、gate、finalの段階進入 |

### 5.1 FOLLOWの環境適応

owner周囲32点と天井を観測し、次をprofileへ変換する。

- openness
- ceiling clearance
- sky visible
- fluid nearby
- darkness
- owner speedと進行方向

天井余裕3 block以下、またはopenness 0.42未満では縦列を選ぶ。開放空間では機数とowner速度に応じて旋回半径、高度、回転速度を変える。8機を超える場合は8機単位のlayerを作り、13機以上ではlayer間高度を大きくする。

## 6. Waypoint航行

### 6.1 単機

単機でもWaypointは直接一直線に向かうとは限らない。

1. 指定X/Zのsurface Yを取得する。
2. 目的地が地下か判定する。
3. 地上長距離なら現在地から目的地まで8 block間隔相当、4～20点で地表をsamplingする。
4. 通常地形では最大地表+7、経路の70%以上が水面なら最大地表+3を巡航高度にする。
5. 最大30 block先のlookahead targetを作る。
6. 直線corridorが塞がれていればpath、local detour、strategic detourへ切り替える。
7. 目的地水平15 block以内では目的地そのものへapproachする。

### 6.2 複数point route

routeは最大8点である。最初のpointは個体またはWing重心から最も近いpointを選ぶ。point到達前18 blockから次区間へsmoothstepでblendし、2点往復では横方向へ弧を加えて折返しを滑らかにする。

次pointへの進行条件は、各Wing leaderの60%以上がhandoff条件を満たすことである。単一の遅延Wingが全routeを永久停止させない一方、leader未到達のまま全機が先へ進まないようにしている。

### 6.3 Mission stage

```text
MISSION_MUSTER
  -> MISSION_MOVING
  -> MISSION_CONVERGING（必要な機体だけ）
  -> MISSION_MOVING（slot合流完了）
  -> MISSION_ORBIT_ENTRY
  -> MISSION_ORBIT
```

| Stage | 意味 |
|---|---|
| MUSTER | 発艦機を任務起点周辺へ集める |
| MOVING | leaderまたは割当slotとして目的地へ航行する |
| CONVERGING | 移動中leaderの隊形slotへ追いつく |
| ORBIT_ENTRY | 到着時の並びを保ちながら円軌道へ展開する |
| ORBIT | 等間隔の時変slotを維持する |

50 tickのORBIT_ENTRY中、後続は密な円弧列から本来の等間隔へ徐々に展開する。

## 7. Wing・Cohort制御

### 7.1 用語

| 用語 | 寿命 | 用途 |
|---|---|---|
| Fleet | ownerが保有する全機 | UI、脅威、サービスの全体管理 |
| Wing / `groupId` | 永続 | 手動編成。基本上限8機 |
| Mission | 指令単位 | 同じmissionIdとWing境界を持つ機体集合 |
| Cohort | 任務中の一時編隊 | leader、rank、収束、隊形移動の単位 |
| Temporary Engagement | 戦闘中のみ | 永続Wingを変更しない一時的な担当群 |

同じmissionIdだけではmemberにしない。`MissionWingPolicy`でgroup境界も一致した機体だけを同じ任務編隊として扱う。

### 7.2 大規模編成

- 1～8機: 一つのWingとして移動。
- 9～12機: 単一mission内の大編隊として扱い得る。
- 13機以上: 8機単位の階層Wingへ分割する。
- 各Wingは独自leaderとCohortを持ち、Wing間は横、前後、高度方向へoffsetする。

階層Wingは目的地を共有するが、全機を一つのleaderへ密集させない。空中でWing同士を物理的に結合せず、同じmissionを独立遂行する。

### 7.3 Leader選出

分散状態から任務を開始する場合、目的地へ最も近い機体をleaderとする。同距離ならunitIdで決定する。leaderは目的地へ進みながら、後続が追いつくまで速度を制限する。

Leaderの役割:

- leader自身のtransit targetを計算する。
- 進行方向と速度をfollowerの基準にする。
- route point進行判定の代表になる。
- Cohortの隊形slot座標の原点になる。

Leaderが行わないこと:

- client commandの中継。
- followerの低位飛行制御。
- followerの衝突回避。
- mission dataの唯一の保存。

### 7.4 隊形slot

Leader以外の目標は次で表せる。

```text
slotPosition = leaderPosition
             + movingOffset(rank, formationSize, leaderVelocityDirection)
```

| 機数 | 基本形 |
|---:|---|
| 1 | SOLO |
| 2 | COLUMN |
| 3～8 | DELTA |
| 9以上の非階層編成 | STAR |

DELTAは後方2.4 block/rowを基準に左右へ広がる。狭所・地下では横幅を抑えた縦列slotへ切り替える。

### 7.5 分散収束

機体spreadが、旋回直径と隊形直径から算出したthresholdを超える場合、最初から完成隊形とは扱わない。

1. 目的地に最も近い機体をleaderにする。
2. followerへ最終rankに対応する暫定slotを割り当てる。
3. leader速度に位置誤差のclosure速度を加えて追いつく。
4. slot距離が16 block以上なら最大1.08以上、8～16では0.78～1.08を確保する。
5. 全join対象がslotから2.8 block以内になったtickでMOVINGへ一斉昇格する。

後続は単にleader位置へ集合せず、CONVERGING中から隊形slotへ向かう。そのため合流完了直前まで一か所へ固まる挙動を避けている。

### 7.6 Leader交代

10 tickごとにleader availabilityを確認する。service帰還、戦闘への一時離脱、Recovery、消失はleader候補から外す。

後継順位:

1. 任務から一時離脱していない。
2. Recoveryでない。
3. 目的地に近い。
4. batteryが多い。
5. 現rankが小さい。
6. unitId順。

交代時は全memberの`COHORT_LEADER`とrankを更新する。FOLLOW leaderがRecoveryから回復しない場合にもlease expiry後に同様の移譲を行う。

### 7.7 到着と哨戒

到着した健全Cohortは、Recovery中の遅延機を待たず、60%以上のquorumでORBITへ移行できる。遅延機は後から同じshared phaseへ参加する。

旋回半径は`max(5.4, count * 3.4 / 2π)`。複数Wingでは高度layer、半径、角速度、位相をずらし、円錐状の多層哨戒を作る。

## 8. 同期とデータリンク

### 8.1 三種類の同期

| 経路 | 現行方式 | 用途 |
|---|---|---|
| Command | clientからserverへ機体別packet | 新しい任務・modeの設定 |
| Coordination | server memoryと各Entityの共有参照 | leader、member、Scout、脅威、予約 |
| Presentation | `SynchedEntityData`等のMinecraft同期 | client描画、HUD、UI表示 |

ドローン間で無線packetを実際に送受信しているわけではない。全機が同じserver world stateを参照できるため、server内共有registryが仮想的なdata linkになっている。

### 8.2 Mission Data Link

Scoutは`dimension + owner UUID + missionId`をkeyとして報告する。source unitごとに最新報告を一つ保持する。

共有値:

- route status: UNKNOWN / PROBING / CLEAR / HAZARDOUS / BLOCKED
- threat scoreとposition
- destination ready
- confidence
- observed tick
- source Wing

報告TTLは200 tick。航行判断へ使うにはconfidence 0.45以上かつ100 tick以内でなければならない。古い情報はLOCAL判断へfallbackする。

### 8.3 「同期」の意味

現行実装は全機を同一時刻・同一状態へlockstepさせない。次の契約だけを同期する。

- 同じmission identityとWing境界。
- leader identityとCohort rank。
- followerが参照するleader位置・速度。
- join対象が全員2.8 block以内になった時のstage昇格。
- ORBIT_ENTRY開始tickとshared phase。
- route進行に必要なWing leader quorum。

各機の位置、速度、回避行動は一致しなくてよい。局所障害物を避けるための差を許容しながら、共通slotへ収束させる。

### 8.4 連携断・欠落時

| 事象 | 現行挙動 |
|---|---|
| playerが別dimension・offline | no-gravityは維持される。通常AI更新はowner確認後に停止する |
| Scout報告が途絶 | 最大100 tick後に航行制約へ使わずLOCALへ戻る |
| 一部command packet欠落 | 受理機だけ開始し、40 tick後にassigned rosterへ縮小する |
| leader消失・離脱 | 10 tick周期で後継を選ぶ |
| followerがstuck | Recoveryに入り、健全Cohortはquorumで任務継続可能 |
| battery 0 | data linkに関係なくPOWER_LOSSとなり墜落する |
| mission中のchunk | ownerが同dimensionにいる間、operation ticketでentity tickingを維持する |

## 9. 障害物回避・Recovery

### 9.1 Corridor判定

現在位置から目標まで0.45 block間隔で機体bounding boxを移動させ、block collisionとwater/lavaを検査する。Wing memberは地形障害物として扱わず、機体間隔はseparationに任せる。

### 9.2 Navigationの階層

```text
Direct corridor
  -> cached strategic waypoint
  -> strategic detour
  -> Minecraft path
  -> stable local detour
  -> Recovery escape
```

長距離または巨大障害物では左右8～72 block、高度差-12～+68 blockの候補を評価する。候補から目標へ直接抜けられる経路を強く優先する。

### 9.3 stuck検出

10 tickで0.28 block未満しか進まず、それが30 tick続く、または衝突・fluidを検出するとRecoveryへ入る。Recovery levelは最大3で、横回避、後退、上昇、戦略迂回へ段階的に拡大する。10 tickで1.2 block以上進めた場合はRecoveryを解除する。

## 10. 電力・Dock・任務復帰

飛行電力は20 tickごとに速度で消費する。

| 速度 | 基本消費/秒 |
|---|---:|
| 0.45未満 | 2 |
| 0.45以上 | 4 |
| 0.8以上 | 6 |
| salvage牽引 | 上記+2、最大8 |

Dock距離から帰還reserveを計算し、必要時はtaskを中断して帰還する。帰還機はWing所属を失わず、active formationから一時除外される。充電・補給後は保存taskを再評価し、対象が生存し任務が有効なら復帰し、無効なら次のtaskまたはfallbackへ移る。

Solar Serviceは最大3 slotの空中給電であり、飛行電力だけを補う。弾薬、修理、role module等は通常Dockが担当するため、Solar Serviceだけを選び続けて本来のDock serviceを妨げない優先度が必要である。

## 11. Roleと群制御の関係

| Role | 群への主な寄与 |
|---|---|
| Scout | 航路状態、脅威、目的地readyをpublish |
| Engineer | 指定block作業、機体整備支援 |
| Cargo | 現場drop回収、containerへの搬送 |
| Field | 複合field taskの調停・緊急支援 |
| Security | 脅威追跡、非任務Wingからの一時離脱・復帰 |
| Salvage | Power Lost機の回収とservice Dockへの牽引 |

role taskは恒久Wingを書き換えない。戦闘、service、salvage等で一時離脱しても、`groupId`とmission identityを保持して復帰先を再構成する。

## 12. 現行Modの設計上の限界

実機化を考える際、次を「すでに解決済み」と誤認してはならない。

1. **物理flight controllerではない**: velocity vectorを直接書き換えており、thrust、motor、質量、風を制御していない。
2. **state estimatorがない**: Minecraftの真値position/velocityを直接読んでいる。
3. **通信品質を模擬していない**: server共有memoryには帯域、遅延、packet loss、clock driftがない。
4. **群commandが非原子的**: 機体別packetにack、transaction、epoch commitがない。
5. **leader情報は即時参照可能**: 実無線の古いtelemetry、範囲外、partitionを再現していない。
6. **衝突回避が簡略**: block corridorと反発vectorであり、動的障害物の将来軌道を最適化していない。
7. **pitch/rollは表示だけ**: attitude制御とflight pathが結合していない。
8. **single server authority**: server停止は全体の停止であり、実機のonboard autonomyに相当しない。
9. **security境界がゲーム用**: 実機に必要な暗号鍵、secure boot、署名firmware、anti-replayがない。

## 13. 実ドローン向け推奨アーキテクチャ

### 13.1 基本原則

**通信が切れても飛行安定を失わない**ことを最上位の不変条件にする。群制御やGCSは姿勢・motorを直接支配せず、各機内のflight controllerが単独でhover、brake、land、returnを完結できなければならない。

```text
Ground / Fleet Layer
  Mission Manager
  Fleet Allocator
  Operator UI
        │ mission intent / constraints
        ▼
Onboard Mission Layer（各機）
  Mission Executive
  Wing Membership + Leader Lease
  Trajectory Manager
  Local Collision Avoidance
  Safety Supervisor
        │ position / velocity / acceleration setpoint
        ▼
Flight Control Layer（各機、通信断でも継続）
  State Estimator
  Position Controller
  Velocity Controller
  Attitude Controller
  Angular Rate Controller
  Mixer / Actuator Output
        │
        ▼
Sensors + Motors
```

### 13.2 制御loop

PX4等の一般的なmulticopter構成に合わせ、cascade制御に分ける。

```text
position error
  -> velocity setpoint
  -> acceleration / thrust setpoint
  -> attitude setpoint
  -> angular-rate setpoint
  -> motor command
```

概念式:

```text
v_sp = Kp_pos * (p_sp - p_est)
a_sp = Kp_vel * (v_sp - v_est)
     + Ki_vel * integral(error)
     + Kd_vel * derivative(error)
attitude_sp, thrust_sp = accelerationToAttitude(a_sp, yaw_sp)
rate_sp = Kp_att * attitudeError(attitude_sp, attitude_est)
motor = rateController(rate_sp, gyro) + mixer
```

Morrowgearの`FlightDynamics`は、このうちpositionからvelocity setpointまでのgame guidanceに近い。実機では下位loopをautopilotへ任せ、群制御はposition/velocity/trajectory setpointより下へ入れない。

### 13.3 State estimation

各機はGNSS、IMU、barometer、magnetometer、必要に応じてoptical flow、VIO、LiDAR等をfusionし、position、velocity、attitude、bias、共分散を推定する。

群制御が参照する値には、値だけでなく次を含める。

- source timestamp
- estimator validity
- covarianceまたはquality
- coordinate frame
- frame origin/version
- sensor degradation状態

positionが取得できない時に古い値を真値として使い続けず、attitude stabilize、altitude hold、land等の承認済みfailsafeへ遷移する。

### 13.4 実機command contract

一機ずつ文字列commandを送る代わりに、型付きmission envelopeを使う。

```text
MissionEnvelope
  mission_id
  mission_epoch
  wing_id
  member_id
  formation_slot
  route_version
  coordinate_frame
  waypoints[]
  constraints
  issued_at
  valid_from
  expires_at
  required_capability
  policy_on_link_loss
  payload_hash
  signature
```

適用手順:

1. GCSが全対象へ`PREPARE(epoch, hash)`を配布する。
2. 各機はschema、capability、geofence、battery、時刻を検証してACK/NACKする。
3. quorumまたは全機条件を満たしたら`COMMIT(epoch)`する。
4. 未受理機は旧missionを維持するか、安全なholdへ移る。
5. 同じepochの再送は冪等に扱う。

必ずしも分散transactionを完全実装する必要はないが、少なくとも「誰が何を受理したか」「いつ有効になるか」「期限切れ時に何をするか」を明示する。

### 13.5 中央・Leader・分散の分担

推奨は現行Modを発展させたhybrid方式である。

| 判断 | 所有者 |
|---|---|
| mission目的、対象Wing、route、優先度 | Ground/Fleet Mission Manager |
| Wing membership、epoch、leader lease | Wing coordination service + 各機copy |
| formation基準軌道 | 現leader。ただしmemberもmissionから再構成可能 |
| 自機slot軌道 | 各機 |
| 局所衝突回避 | 各機。formation維持より優先 |
| 姿勢・推力・motor | 各機flight controller |
| link loss、sensor loss、battery emergency | 各機Safety Supervisor |

Leaderをcommand relayの唯一経路にしない。各機はmissionを直接受信・保存し、leader telemetryが途絶したらlease expiry後に決定的な規則で後継を選ぶ。新leaderが決まるまで各機は最後のtrajectoryを無期限に外挿せず、速度上限付きholdまたはmission-defined fallbackへ移る。

### 13.6 実機Waypoint航行

実機ではWaypointを点列のままposition controllerへ渡さず、制約付きtrajectoryへ変換する。

必要な制約:

- 最大速度、加速度、jerk
- 最大傾斜、上昇・下降率、yaw rate
- geofenceと最低・最高高度
- stopping distance
- GNSS精度、風、payload、battery reserve
- dynamic obstacleと通信coverage

route cornerは曲率連続なspline、Dubins path、jerk-limited trajectory等から機体特性に合う方式を選ぶ。Waypoint通過判定には単純距離だけでなく、along-track progress、cross-track error、速度、次区間のfeasibilityを使う。

### 13.7 実機Wing航行

各memberの目標を次の形にする。

```text
p_i_sp = p_leader_predicted + R(heading) * slot_i + avoidance_i
v_i_sp = v_leader_predicted + Kp_form * formation_error + avoidance_velocity_i
```

`p_leader_predicted`は受信時刻とleader velocity/accelerationから短時間予測する。telemetry ageが閾値を超えた場合は補間を止め、degraded formationへ遷移する。

実機では次の優先順位を推奨する。

```text
1. flight envelope / ground collision / geofence
2. inter-vehicle collision avoidance
3. emergency landing / battery reserve
4. mission safety constraint
5. formation maintenance
6. mission efficiency
```

隊形slotへ無理に戻ることで衝突する設計を避ける。回避後はrejoin corridorを予約し、slotへ横切って戻らない。

### 13.8 通信・QoS

全messageを同じreliabilityにしない。

| Channel | 推奨QoS | 理由 |
|---|---|---|
| emergency command | reliable、期限付き、ACK必須 | 欠落させない |
| mission/epoch | reliable、transient、version管理 | late joinと再接続に必要 |
| leader state | best effortまたは低遅延reliable、短lifespan | 古い値の再送より新しい値を優先 |
| high-rate telemetry | best effort、depth小 | backlogを作らない |
| health/failsafe | reliable、liveliness/deadline監視 | 機体喪失を検出する |
| log/map | reliable、帯域制御 | 飛行制御を圧迫しない |

ROS 2/DDSを使う場合はreliabilityだけでなくdeadline、lifespan、liveliness、history depthをtopicごとに指定する。通信断判定はTCP切断だけでなく、message deadlineとsource timestampで行う。

### 13.9 時刻同期

server tickの共通時計がないため、実機ではmonotonic clockと時刻同期が必要である。

- MAVLink TIMESYNC、PTP、GNSS time等から構成に合う方式を選ぶ。
- wall clock変更を制御loopの経過時間へ使わない。
- messageへsource timeとreceive timeを保持する。
- offsetだけでなくround-trip delayと同期qualityを監視する。
- 同期精度がformation要件を外れた場合は間隔を広げるかdegraded modeへ移る。

### 13.10 Link lossとfailsafe

Offboard/群制御信号にはheartbeatとtimeoutを設ける。timeout時の動作は機体・任務ごとに事前承認する。

| 喪失 | 推奨動作例 |
|---|---|
| GCS linkのみ喪失 | onboard mission継続、またはHold/RTL。任務policyに従う |
| Leader telemetry喪失 | formation拡大、hold、leader再選出 |
| 全peer link喪失 | 自機hover/loiter後、battery条件でRTLまたはland |
| GNSS喪失 | VIO等へ切替。位置quality不足なら姿勢・高度安定後land |
| battery reserve到達 | missionよりRTL/landを優先 |
| flight controllerとcompanion間喪失 | autopilot内failsafeへ移行。companion再接続を待ってhoverし続けない |

PX4 Offboard modeはproof-of-life喪失を`COM_OF_LOSS_T`で検出し、設定したfailsafe actionへ移る。同等の独立監視をMorrowgear実機版でもflight controller側に持たせる。

### 13.11 Security

- 機体・GCS・serviceの相互identityを検証する。
- missionへsequence/epoch、期限、署名を付け、replayを拒否する。
- secure boot、署名firmware、鍵rotation、失効を設計する。
- operator roleと機体ownerを分離し、最小権限にする。
- command、manual override、failsafe変更を監査logへ残す。
- debug interfaceとdefault credentialを本番機へ残さない。

### 13.12 実機の規制・運用

飛行場所、機体重量、目視外、夜間、第三者上空、自動飛行、remote ID等の条件は地域と運用で変わる。日本で運用する場合は国土交通省の最新制度と許可・承認条件を確認し、software試験だけで飛行可否を判断しない。

## 14. Modから実機への対応表

| Morrowgear Mod | 実機で置換するもの |
|---|---|
| `DroneCommandPayload` | 型付きmission protocol + ACK + epoch + signature |
| `SynchedEntityData` | telemetry topic + timestamp + quality + QoS |
| server `gameTime` | 同期済みmonotonic time |
| 真値`position()` | estimator output + covariance |
| `setNoGravity(true)` | onboard attitude/rate controllerによる推力維持 |
| `setDeltaMovement` | trajectory setpointをcascade controllerへ入力 |
| `separationVector` | relative localization + predictive collision avoidance |
| `cohortLeaderId` | leader lease + epoch + deterministic election |
| `MissionDataLink` Map | version付きpub/sub data product + TTL |
| chunk ticket | onboard autonomy。通信範囲外でも継続可能 |
| `POWER_LOSS`墜落 | reserveに基づくRTLまたはcontrolled landing |
| block collision scan | map、range sensor、detect-and-avoid、geofence |
| client pitch/roll | 実attitude controllerの結果 |

## 15. 実機化の段階的検証

実飛行から始めない。次のGateを順番に通す。

### Gate 1: Decision Core

- mission state、leader election、slot、timeout、fallbackを純粋logicとして試験する。
- packet loss、順序逆転、重複、clock offset、member消失を決定的に再生する。
- invariant: 一機一Wing、一epoch一mission、期限切れcommand拒否、leader単一性。

### Gate 2: Software-in-the-Loop

- autopilot SITLとphysics simulatorを使う。
- 風、GNSS noise、sensor dropout、link latency、packet loss、battery低下を注入する。
- 単機hover、Waypoint、RTLを群制御より先に合格させる。

### Gate 3: Multi-vehicle simulation

- 1、2、8、複数Wingで規模を上げる。
- leader喪失、network partition、late join、stuck、route変更を試す。
- 最小機間距離、cross-track error、formation error、timeout、CPU、networkを測る。

### Gate 4: Hardware-in-the-Loop

- 実flight controller、radio、companion computer、電源系を接続する。
- watchdog、brownout、reboot、thermal throttling、radio saturationを確認する。
- motorは原則外すか安全設備内で試験する。

### Gate 5: Tethered / protected flight

- 単機、低速、低高度、立入管理区域から始める。
- independent safety pilot、manual override、flight termination条件を用意する。
- 群機数は前段の全Evidenceが揃った場合だけ増やす。

### Gate 6: Operational trial

- 適用法、risk assessment、weather limit、maintenance、battery管理、incident responseを承認する。
- software version、airframe、parameter、payload、operatorをflight logへ紐付ける。

## 16. 現行コード参照

| 関心 | 正本コード |
|---|---|
| command受信・validation | [`MorrowgearDrone.java`](../../src/main/java/jp/morrowgear/drone/MorrowgearDrone.java) |
| client群command配布 | [`TacticalDashboard.java`](../../src/client/java/jp/morrowgear/drone/client/TacticalDashboard.java) |
| 個体tick・状態・任務 | [`DroneEntity.java`](../../src/main/java/jp/morrowgear/drone/DroneEntity.java) |
| 速度steering | [`FlightDynamics.java`](../../src/main/java/jp/morrowgear/drone/FlightDynamics.java) |
| 姿勢表示 | [`FlightAttitude.java`](../../src/main/java/jp/morrowgear/drone/FlightAttitude.java) |
| 巡航高度・追跡観測点 | [`MissionFlightPlan.java`](../../src/main/java/jp/morrowgear/drone/MissionFlightPlan.java) |
| 障害物回避 | [`DroneNavigator.java`](../../src/main/java/jp/morrowgear/drone/DroneNavigator.java) |
| Wing形状・階層 | [`SwarmFormation.java`](../../src/main/java/jp/morrowgear/drone/SwarmFormation.java) |
| Cohort合流 | [`CohortControl.java`](../../src/main/java/jp/morrowgear/drone/CohortControl.java) |
| Leader交代 | [`WingLeadershipPolicy.java`](../../src/main/java/jp/morrowgear/drone/WingLeadershipPolicy.java) |
| Scout共有 | [`MissionDataLink.java`](../../src/main/java/jp/morrowgear/drone/MissionDataLink.java) |
| Scout航行制約 | [`ScoutRoutePolicy.java`](../../src/main/java/jp/morrowgear/drone/ScoutRoutePolicy.java) |
| 巡回route | [`PatrolRoutePolicy.java`](../../src/main/java/jp/morrowgear/drone/PatrolRoutePolicy.java) |
| 電力消費 | [`FlightPowerPolicy.java`](../../src/main/java/jp/morrowgear/drone/FlightPowerPolicy.java) |
| 最上位状態 | [`DroneOperationalState.java`](../../src/main/java/jp/morrowgear/drone/DroneOperationalState.java) |

## 17. 実機設計の一次資料

- [PX4 Controller Diagrams](https://docs.px4.io/main/en/flight_stack/controller_diagrams)
- [PX4 Offboard Mode](https://docs.px4.io/main/en/flight_modes/offboard)
- [PX4 Using PX4's Navigation Filter / EKF2](https://docs.px4.io/main/en/advanced_config/tuning_the_ecl_ekf)
- [PX4 ROS 2 Multi-Vehicle Simulation](https://docs.px4.io/main/en/ros2/multi_vehicle)
- [PX4 ROS 2 Offboard Control Example](https://docs.px4.io/main/en/ros2/offboard_control)
- [MAVLink Time Synchronization Protocol](https://mavlink.io/en/services/timesync.html)
- [MAVLink Mission Protocol](https://mavlink.io/en/services/mission.html)
- [ROS 2 Quality of Service Settings](https://docs.ros.org/en/humble/Concepts/Intermediate/About-Quality-of-Service-Settings.html)
- [国土交通省 無人航空機ポータルサイト](https://www.mlit.go.jp/koku/drone/)

## 18. 設計上の最終原則

1. **群連携が切れても、一機として安全に飛べること。**
2. **Leaderは隊形の基準であり、唯一のcommand relayや正本にしないこと。**
3. **中央は目的を決め、各機は自機の安全と低位制御を所有すること。**
4. **古い共有情報を使い続けず、timestamp、TTL、quality、epochで拒否できること。**
5. **formation維持より衝突回避、battery reserve、flight envelopeを優先すること。**
6. **Modの係数を実機へ転用せず、physics、sensor、通信、規制を含むEvidenceから決めること。**
