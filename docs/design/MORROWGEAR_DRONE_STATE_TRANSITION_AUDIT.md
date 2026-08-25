# Morrowgear Drone 状態遷移・優先度監査書

更新日: 2026-08-23  
対象: Java Mod 現行実装  
主対象: GUARD、Wing航行、戦闘、サービス帰還、Recovery、フィールド任務

## 1. 目的

現行のドローン制御は、1機に対して複数の状態機械が並行して動く構造になっている。

- 基本飛行モード
- Wing / 地点移動ミッション
- 役割別任務
- GUARD警戒
- 緊急迎撃
- 戦闘
- 充電・修理帰還
- 障害物回避・Recovery
- 表示・軌跡

これらは個別には成立しているが、同時成立した際の排他条件と復帰先が統一されていない。そのため、GUARDを含む複合任務で、目標位置、速度、リーダー権限、表示状態が一致しないケースが発生する。

本書は以下を目的とする。

1. 現在実装されている状態を漏れなく分類する。
2. 1 tick内で実際にどの処理が優先されるかを明文化する。
3. 状態名、表示、飛行制御、任務所有権の矛盾を特定する。
4. 修正時に守るべき状態遷移モデルと不変条件を定義する。

## 2. 結論

監査時点の不安定化の主因は、状態数の多さそのものではなく、**現在の飛行をどの状態が所有しているかを決める単一の調停器がないこと**であった。

特に重大な問題は次の5点である。

1. GUARDが敵を検知すると、戦闘投入対象に選ばれていない機体まで警戒円を離れて敵へ寄る。
2. `REJOIN`は名前に反して再合流を制御せず、24 tickの表示状態に近い。
3. 複数機命令が機体別パケットで届き、編隊規模を即時縮小するため、ALL/Wing命令が非原子的である。
4. `STANDBY`が「停止」と「GUARD稼働中」の両方を意味している。
5. Mode変更、戦闘復帰、サービス復帰で異なる更新方法を使い、任務を消す場合と保持する場合が暗黙に分かれている。

したがって、個別の条件分岐を追加するだけでは再発しやすい。最優先で「飛行意図の単一決定」「GUARDの明示的な迎撃状態」「命令の一括適用」を導入すべきである。

### 2.1 0.15.1で採用した解決

0.15.1では、全状態を次の優先順位で解決する`DroneOperationalState`を導入した。

```text
POWER_LOSS
  > DOCKED
  > SERVICE_RTB
  > COMBAT
  > EMERGENCY_INTERCEPT
  > REJOIN
  > FIELD / SECURITY / ENGINEER / CARGO
  > WAYPOINT / FOLLOW / RETURN / ORBIT
  > STANDBY
```

同時に成立した下位状態は保存される場合があるが、そのtickの飛行を所有しない。状態変化時には、機体、Wing、任務、Combat、資源、Recoveryをログへ記録する。

### 2.2 資源別サービス遷移

| 条件 | 専用兵装 | AUTO | 遷移 |
|---|---|---|---|
| 飛行電力がDock距離別予備量以下 | 全機 | 全機 | 即時`SERVICE_RTB` |
| 体力25%以下 | 全機 | 全機 | 即時`SERVICE_RTB` |
| 武器電力15%未満 | SECURITY | SECURITY | 即時`SERVICE_RTB` |
| 機関砲弾0 | AUTOCANNON | 他兵装があれば継続 | 専用機のみ再装填帰還 |
| ミサイル0 | MISSILE | 他兵装があれば継続 | 専用機のみ再装填帰還 |
| HEAT 900以上 | LASER | 他兵装があれば切替 | レーザー停止・空中冷却。Dock帰還しない |
| AUTOの全兵装使用不能 | - | AUTO | 再武装帰還 |

帰還中に複数理由が成立した場合は、`DAMAGE > FLIGHT_POWER > WEAPON`の順で理由を昇格する。途中でUIから新しい命令を受けても帰還は中止せず、命令をサービス完了後の復帰先として保存する。

Dockでは飛行電力、武器電力、弾薬、ミサイル、HEAT、損傷を並行して回復する。通常は90%以上まで整備してから復帰する。高脅威が継続中の場合は、65%以上かつ使用可能兵装がある時点で緊急再出撃し、帰還機の穴は他の使用可能機が一時的に補う。

### 2.3 GUN RUN安定化

GUN RUNは次の3要因で速度が揺れていた。

1. 参加機の増減でスロット番号と共通開始時刻が変わる。
2. 敵の移動に合わせて攻撃軸が毎tick回転する。
3. 八の字の媒介変数を等速で進めるため、曲率によって実空間速度が変わる。

0.15.1では、戦闘単位ごとに参加スロット、攻撃軸、軌道開始時刻をリース中固定し、八の字を曲線長に沿って進む方式へ変更した。軌道速度は加速完了後おおむね0.90 block/tickで一定となる。近距離離脱は機体と敵の3次元距離が3 block以内の場合だけ発動し、高度差だけで不要な離脱へ入らない。

### 2.4 任務中断と復帰

- `REJOIN`は表示だけの24 tick状態ではなく、元任務の合流位置へ到着するか240 tickの安全上限に達するまで飛行を所有する。
- `REJOIN`中の機体はWing隊形人数とリーダー候補から除外する。
- Recovery中のリーダーは権限を委譲し、ルートの到達人数にも含めない。
- GUARDで接触を検知しても、選抜されていない機体は警戒スロットを維持する。
- Field、Security、Follow、Waypoint、Trackingの新規割当は、競合する旧任務を共通手順で解除する。
- 複数機命令の逐次受信中は40 tickの確定猶予を設け、最初の受信機がWing規模を即時縮小しない。

### 2.5 0.15.1検証範囲

- JUnit: 1,479件、失敗0、エラー0、スキップ0。
- 全Operational Stateの優先順位。
- 飛行電力、損傷、武器電力、弾薬、ミサイル、AUTO全兵装枯渇。HEAT単独は帰還理由にしない。
- 戦闘対象消失、再合流、Recovery、リーダー委譲、任務上書き。
- GUN RUN曲線速度、射撃方向、隊間隔、攻撃軸と開始時刻の安定性。
- ゲーム内検証には、レーザーHEAT空中冷却・任務維持・再参戦と、GUN RUN連続tick速度差および連射速度の計測を追加した。

## 3. 現在の状態レイヤー

### 3.1 機体ロール

ロールは能力と任務適格性を表す。飛行状態ではない。

| ロール | 主機能 | フィールド任務 | GUARD任務 |
|---|---|---:|---:|
| FIELD | 汎用機 | 不可 | 不可 |
| SCOUT | 広域走査、脅威共有 | 可 | 不可 |
| CARGO | 回収、輸送 | 可 | 不可 |
| ENGINEER | 採掘、伐採、植林、修理 | 可 | 不可 |
| SECURITY | 警戒、迎撃、戦闘 | 可 | 可 |

ロール変更時には一部任務が解除されるが、全状態を一括正規化する処理ではない。

### 3.2 基本飛行モード `DroneMode`

| 状態 | 現在の意味 | 問題 |
|---|---|---|
| STANDBY | 通常停止。またはGUARD/フィールド任務の土台 | 「待機」と「任務中」が同じ値 |
| FOLLOW | プレイヤー追従 | Wing追従情報を別フィールドにも保持 |
| RETURN | プレイヤー付近へ帰還 | Dock帰還とは別 |
| ORBIT | プレイヤー周辺旋回 | 地点哨戒の旋回とは別 |
| DOCK | Dockへ帰還・着艦 | 通常帰還とサービス帰還を兼用 |
| WAYPOINT | 地点移動、追跡、ルート巡回 | Wing状態機と密結合 |

`setMode()`は`WAYPOINT`以外を指定すると地点ミッションを消去する。この副作用は、単なるモード変更に見える呼び出しが任務キャンセルにもなることを意味する。

### 3.3 Wing / 地点ミッション状態

| 状態 | 意味 | 主な遷移先 |
|---|---|---|
| MISSION_MUSTER | 発進待ち・集合 | MOVING |
| MISSION_MOVING | 編隊航行 | CONVERGING / ORBIT_ENTRY |
| MISSION_CONVERGING | リーダー・隊形へ合流 | MOVING |
| MISSION_ORBIT_ENTRY | 円弧へ進入 | ORBIT |
| MISSION_ORBIT | 地点旋回 | 次ルートのMOVING |

現行の地点・追跡・ルート命令は、新規割当時に原則`MOVING`から開始する。一方で`MUSTER`前提の待機・縮小ロジックが残っている。

関連データ:

- `MISSION_ID`: 任務共有ID
- `MISSION_EXPECTED`: 期待機数
- `MISSION_INDEX`: 任務内インデックス
- `COHORT_ID`: 収束済み集団
- `COHORT_LEADER`: リーダーID
- `COHORT_RANK`: 編隊内順位
- `GROUP`: 手動Wing名

`GROUP`と`COHORT`と`MISSION`は別概念だが、UI上はすべてWingとして見える場面があり、制御上の所属と表示上の所属が一致しない。

### 3.4 Cargo状態

| 状態群 | 状態 |
|---|---|
| 未割当 | UNASSIGNED |
| 移動 | TO_SOURCE / TO_TARGET |
| 待機 | WAIT_SOURCE / WAIT_TARGET |
| 調停 | QUEUE_SOURCE / QUEUE_TARGET |
| 搬送 | LOADING_SOURCE / UNLOADING_TARGET |

フィールド任務中はCargo専用ルート処理を止め、`FieldOperationState`側の回収・搬送状態を使う。

### 3.5 Engineer状態

| 状態 | 意味 |
|---|---|
| IDLE | 修理任務なし |
| APPROACH | 修理対象へ移動 |
| REPAIRING | 修理中 |
| MATERIAL_LOW | 資材不足 |

フィールド作業時はこの状態機ではなく`FieldOperationState`を使う。

### 3.6 フィールド任務状態

共通状態:

- IDLE
- SCANNING
- WAITING_DATA
- TRANSIT
- WORKING
- COLLECTING
- DELIVERING
- PLANTING
- COMPLETE
- BLOCKED

ロール別状態:

- SCOUT_SURVEY
- SCOUT_OVERWATCH
- SCOUT_CARGO_ESCORT
- ENGINEER_WAITING_INTEL
- ENGINEER_LOCAL_SURVEY
- ENGINEER_RECOVERY_OVERWATCH
- CARGO_HOLD
- GUARD_WORK_ESCORT
- GUARD_CARGO_ESCORT

フィールド任務はSCOUT、ENGINEER、CARGO、SECURITYが同じ`FIELD_ORDER`を共有する。共有台帳はセッション内メモリであり、ワールド再読込時にはフィールド任務だけ明示的に解除される。

### 3.7 GUARD警戒状態

GUARDには専用enumが存在せず、以下の組合せで表現される。

- Role = SECURITY
- Mode = STANDBY
- SECURITY_ORDERが存在
- SECURITY_ANCHOR / RADIUSが存在
- SECURITY_CONTACTに検知対象を保持

表示上は、接触なしなら「地点警戒」、接触ありなら即座に「迎撃」となる。しかし、実際に戦闘要員へ選ばれたかはこの表示に反映されない。

### 3.8 緊急迎撃状態

専用enumではなく、通常フィールドで表現される。

- `emergencyTargetId`
- `emergencyTargetPosition`
- `emergencyInterceptSlot`
- `emergencyInterceptCount`
- `emergencyInterceptUntil`
- `emergencyLaunchedFromDock`

プレイヤー脅威またはSCOUT報告を受け、`GuardDispatchPolicy`が必要数を選抜する。

### 3.9 戦闘状態 `CombatState`

| 状態 | 意味 | `combatActive()` |
|---|---|---:|
| IDLE | 非戦闘 | false |
| FLARE_ENTRY | 戦闘突入演出 | true |
| GUN_RUN | 機関砲攻撃 | true |
| LASER_CHARGE | レーザー充填 | true |
| LASER_FIRE | レーザー照射 | true |
| MISSILE_APPROACH | ミサイル接近 | true |
| MISSILE_EGRESS | ミサイル離脱 | true |
| RAM_APPROACH | 体当たり | true |
| REJOIN | 復帰表示 | **false** |

`REJOIN`が非戦闘扱いであるため、戦闘用目標位置ではなく、その直後から通常任務、警戒、緊急迎撃の目標位置が選ばれる。

### 3.10 サービス帰還状態

`serviceReturn`フラグと`serviceReason`で表現する。

| 理由 | 条件 |
|---|---|
| FLIGHT_POWER | Dock往復距離を考慮した飛行電力予備を下回る |
| WEAPON_POWER | ロードアウトに必要な武器資源を下回る |
| DAMAGE | 体力が危険域 |
| NONE | サービス不要 |

サービス帰還中はModeをDOCKにし、フィールド・Cargo・Engineer・警戒・緊急迎撃の更新を停止する。元のModeだけを`serviceResumeMode`に記録する。

### 3.11 Navigation / Recovery状態

Recoveryは任務状態とは独立している。

- 通常航法
- 局所回避
- 戦略迂回点
- スタックRecovery
- 液体・衝突Recovery

Recoveryは要求された目標位置を一時的な脱出点に置き換えるが、任務完了判定、リーダー権限、ルート進行を一律停止しない。

### 3.12 表示・軌跡状態

軌跡は上記状態から後段で推測される派生表示である。

- NAVIGATION
- CATCH_UP
- SERVICE_RETURN
- REJOIN
- COMBAT_ENTRY
- NONE

飛行制御の最終決定結果ではなく、複数の生状態を再判定しているため、実際の動きと色・表示が一致しない余地がある。

## 4. 1 tick内の現行処理順

`DroneEntity.customServerAiStep()`の実効順は次のとおり。

1. 所有者、光源、Dock所有権を更新する。
2. 飛行電力を消費する。0なら落下処理を行い、そのtickを終了する。
3. サービス必要性を判定し、必要ならサービス帰還を開始する。
4. Dockリンクを検証する。
5. 所有機一覧を取得する。
6. サービス帰還中でなければフィールド任務を更新する。
7. フィールド任務がなければCargo専用任務を更新する。
8. フィールド任務がなければEngineer修理任務を更新する。
9. GUARD警戒の敵検知を更新する。
10. FOLLOWリーダーを保守する。
11. 周囲環境とプレイヤー脅威を評価する。
12. ミッション・データリンクとSCOUT脅威を共有する。
13. 緊急迎撃要員を選抜する。
14. 戦闘状態を更新する。
15. Wingリーダーを保守する。
16. 旧プレイヤー防壁状態を毎tick解除する。
17. 現在動くべきかを複数フラグから決定する。
18. WAYPOINTならミッション段階を更新する。
19. 最終目標位置を優先順位で1つ選ぶ。
20. Navigation / Recoveryが必要なら目標位置を置換する。
21. 速度プロファイルを決める。
22. 戦闘専用または通常操舵を行う。
23. 到着、Dock進入、着艦を処理する。
24. 機首方向を更新する。

問題は、6から15までが状態を更新した後、19で別の優先順位によって飛行目標を決める点にある。状態更新側が想定した目標が、後段の別状態に上書きされる。

## 5. 現在の実効優先度

### 5.1 飛行目標の優先度

高い順に次のとおり。

1. Active Combat
2. 緊急迎撃
3. GUARD地点警戒
4. フィールド任務
5. Engineer修理接近
6. FOLLOW編隊
7. ORBIT
8. RETURN
9. WAYPOINT / 追跡 / ルート
10. プレイヤー追従位置へのフォールバック

サービス帰還はこの関数の外側でDock目標を先に設定する。ただし緊急・戦闘移動フラグが残るとDock目標を破棄する分岐がある。

### 5.2 速度の優先度

1. レーザー専用速度
2. 機関砲CAS速度
3. 緊急迎撃の最低速度
4. WAYPOINT / 編隊収束 / Scoutリーダー速度制御
5. 通常飛行速度

### 5.3 操舵の優先度

1. レーザー専用操舵
2. 機関砲CAS専用操舵
3. CONVERGING目標
4. ルート予測操舵
5. 通常ナビゲーション操舵

### 5.4 Wing参加判定

サービス帰還、緊急迎撃、Active Combatは一時離脱として扱われる。一方、`REJOIN`はActive Combatではないため、物理的な合流前から通常Wingメンバーに戻る。

## 6. GUARDの現行遷移

### 6.1 地点警戒

```text
GUARD命令
  -> フィールド任務解除
  -> 発艦
  -> Mode = STANDBY
  -> 地点警戒情報を保存
  -> 警戒円を周回
```

この時点で通常のWing地点ミッションは`setMode(STANDBY)`により消える。GUARD複数機は手動Wingではなく、同じ`SECURITY_ORDER`とUnit ID順で警戒配置を作る。

### 6.2 敵検知

```text
5 tickごとに警戒範囲を走査
  -> 最寄りEnemyをSECURITY_CONTACTへ設定
  -> 全同一警戒機の目標位置が敵寄りの迎撃位置になる
  -> updateCombatが必要攻撃機数を別途算出
```

ここに重大な二重制御がある。必要攻撃機に選ばれなかったGUARDも、`SECURITY_CONTACT`があるだけで敵寄りへ移動する。

### 6.3 プレイヤーまたはSCOUTからの緊急迎撃

```text
脅威報告
  -> 候補GUARDを順位付け
  -> 脅威度・プレイヤー危険度・損耗から必要数算出
  -> 選抜機だけemergencyInterceptActive
  -> 迎撃位置へ移動
  -> 戦闘状態へ移行
```

この経路では選抜が存在するが、地点警戒の敵検知経路には選抜前移動が存在するため、同じGUARDでも挙動が一致しない。

### 6.4 戦闘

```text
IDLE / REJOIN
  -> FLARE_ENTRY
  -> ロードアウトに応じた攻撃状態
  -> 次攻撃または武器変更
  -> 対象撃破・割当解除・資源枯渇
  -> REJOIN
```

戦闘要員数は戦闘中も再計算される。選抜外になった機体はREJOINへ移るが、REJOIN専用の合流地点、編隊、完了条件はない。

### 6.5 充電・再出撃

```text
飛行電力 / 武器資源 / 体力の閾値到達
  -> サービス帰還
  -> Dock着艦
  -> 飛行電力・武器資源・体力回復
  -> 出撃可能判定
  -> 元Modeを復元
  -> 脅威を再評価
```

復元されるのはModeだけである。GUARD警戒情報やフィールド任務情報は別領域に残っているため動作は再開できるが、「何へ復帰するか」を示す統一Resume Tokenはない。

## 7. 矛盾・不整合

### P0: GUARD検知と戦闘要員選抜が競合する

`securityTargetPosition()`は接触があるだけで迎撃位置を返す。一方、`GuardDispatchPolicy`と`updateCombat()`は脅威度に応じた必要数だけを選抜する。

結果:

- 非選抜機も警戒スロットを離れる。
- 「何機を任務に残すか」というリソース管理が成立しない。
- 同じ敵に対して警戒配置、緊急迎撃配置、武器別戦闘配置が短時間に切り替わる。

修正原則:

- 敵検知はThreat Trackの生成だけを行う。
- 実際に警戒円を離れるのは`ASSIGNED_INTERCEPT`の機体だけとする。
- 非選抜GUARDは警戒スロットを維持する。

### P0: 複数機命令が非原子的である

クライアントは選択機ごとに`DroneCommandPayload(entityId, action)`を送る。サーバーは1機ずつ即時適用する。

同時に、現在は新規地点任務を`MOVING`から開始し、利用可能機が期待数より少ない場合に`MISSION_EXPECTED`を即時縮小できる。

結果:

- 最初に命令を受けた機体が「期待8、実在1」と判断し、期待数を1へ縮小できる。
- 後続機は同じMission IDでも異なる期待数・インデックス・Cohortを持ちうる。
- ALL、Wing、ルート巡回、追跡で開始直後の隊形が不安定になる。

修正原則:

- 選択機ID一覧を含む単一の`FleetCommandBatch`として送る。
- サーバーで全対象を検証後、同一tickに一括適用する。
- やむを得ず個別パケットを維持する場合、割当後の猶予期間中は期待数を縮小しない。

### P0: `REJOIN`が実際の再合流状態ではない

`REJOIN`は`combatActive() == false`であり、専用飛行目標を持たない。Modeを復元した瞬間から通常任務目標へ直接向かい、24 tick後にIDLEへ戻る。

結果:

- Wingから離れた位置でも通常メンバーに即復帰する。
- リーダー候補、隊形スロット、旋回人数に早期反映される。
- GUARD復帰、ルート復帰、フィールド護衛復帰の挙動を区別できない。

修正原則:

- `REJOIN`をActive Operational Stateとする。
- 復帰先種別、復帰スロット、対象Wing、完了距離を保持する。
- 物理合流完了後にのみ通常任務へ戻す。

### P1: `STANDBY`の意味が二重である

GUARD地点警戒はModeをSTANDBYにするが、警戒円を飛行し続ける。フィールド任務でも同様にModeは停止を意味しない。

結果:

- UI、ログ、デバッグ時に「待機」と「任務中」を区別できない。
- `mode == STANDBY`をアイドル判定に使う他処理と矛盾する。

修正原則:

- STANDBYは真の停止だけに限定する。
- PATROL、FIELD_OPERATIONなどを上位Operational Stateとして持つ。

### P1: Mode変更の副作用が統一されていない

通常の`setMode()`はWAYPOINT以外でミッションを消す。一方、戦闘・サービス復帰は`entityData.set(MODE, ...)`で直接書き換え、ミッションを保持する。

結果:

- 同じMode遷移でも呼び出し経路により任務が消える場合と残る場合がある。
- Dock帰還、緊急解除、戦闘復帰で意図しないミッション消失が起きうる。

修正原則:

- `transitionTo(state, reason, resumePolicy)`だけを状態変更入口にする。
- 任務キャンセル、休止、再開を明示的に分ける。

### P1: GUARD任務とWing任務が別物である

GUARD割当時にWAYPOINT/Cohort情報を消し、同じSECURITY_ORDERだけで独自配置する。手動WingはGUARDの隊形・リーダー・警戒スロット決定に使われない。

結果:

- 「WingへGUARDを指示した」というUI上の理解と実装が一致しない。
- Wingリーダーの交代、最大8機、複数Wingの高度分離がGUARDには適用されない。

修正原則:

- Wing membershipとMission membershipを分離して永続保持する。
- GUARD任務でもWing単位の警戒セクター、リーダー、最大機数を利用する。

### P1: Recoveryと任務進行が直交しすぎている

Recoveryは目標を一時置換するだけで、ルート到達判定、任務段階、リーダー権限を一律停止しない。

結果:

- Recovery中のリーダーを基準に後続が待つ。
- 迂回中に地点到達やルート更新が成立する可能性がある。
- FOLLOWではリーダー委譲規則があるが、地点ミッションでは扱いが異なる。

修正原則:

- Recoveryを局所飛行制御として扱いつつ、進行判定は`recoveryLevel == 0`の機体だけで行う。
- リーダーRecoveryに期限を設け、代理リーダーへ委譲する。

### P1: ルート第1区間の起点が不正確である

第1ポイントへの進捗判定でも「前のポイント」としてルート末尾を使う。実際の発進位置ではない。

結果:

- 配置場所とルート形状によっては第1ポイントへ未到達でも進捗率が高く判定される。
- 1番地点で停止、または早すぎる2番地点への更新につながりうる。

修正原則:

- 初回だけ`routeEntryOrigin`を区間起点に使う。
- 第1ポイント通過後から前ルート点を使う。

### P1: ミッション所属判定がプレイヤー中心512ブロックに依存する

任務メンバー探索は所有者から512ブロック以内を基準にする。

結果:

- 遠距離任務中の機体が集合計算から消える。
- 期待数、リーダー、Cohortが揺れる。
- チャンク未読込時の任務継続規則と一致しない。

修正原則:

- 所有者周辺検索ではなく、サーバー側Mission Registryでメンバーを管理する。
- 未読込機は`OFFLINE/UNLOADED`として残す。

### P1: 飛行電力0に専用状態がない

飛行電力0では戦闘と緊急迎撃だけを消し、重力落下してtickを終了する。任務表示は残りうる。

結果:

- UI上はGUARDや巡回中のまま落下する。
- Wing側は機体喪失なのか一時離脱なのか判断しにくい。

修正原則:

- `POWER_LOSS` / `DISABLED`を最上位状態として定義する。
- WingとMission Registryへ即時通知する。

### P2: フィールド任務とGUARD警戒の優先度が暗黙である

通常割当では相互に解除するが、保存データ、役割変更、復帰経路などで重複した場合、GUARD警戒がフィールド護衛より常に優先される。

修正原則:

- 同時保持を禁止する不変条件を導入するか、明示的なSuspended Missionとして保存する。

### P2: フィールド任務だけ再読込時に消える

共有走査台帳がセッション内のため、再読込時にフィールド任務は解除される。一方、GUARD、Wing、戦闘、サービス情報は保存される。

結果:

- 複合Wing内で役割ごとに再開状態が異なる。
- GUARDだけ任務を継続し、SCOUT/ENGINEER/CARGOが停止しうる。

修正原則:

- Field Operation Registryを永続化するか、全メンバーを一貫して安全状態へ移す。

### P2: 旧プレイヤー防壁コードが残存する

防壁割当、投射物迎撃、近接押し戻し用の処理が残る一方、メインtickで毎回状態を解除している。現行の攻撃中心設計とは整合しない。

修正原則:

- 使用しない旧防御コードを削除する。
- 将来再利用する場合も現行Combat Directiveの一種として再設計する。

### P2: UI状態は制御状態の真実ではない

- SECURITY_CONTACTがあるだけで「迎撃」と表示する。
- STANDBYでも実際は警戒飛行する。
- REJOIN表示中でも通常任務制御に戻っている。
- DATA_LINK_STATUSは複数機能が最後に書いた文字列で上書きされる。

修正原則:

- UIは最終的な`ResolvedOperationalState`と`FlightIntent`から描画する。
- 検知、割当、戦闘、復帰を別ラベルにする。

## 8. 目標状態モデル

### 8.1 最上位Operational State

同時に1つだけ成立させる。

| 優先度 | 状態 | 飛行所有者 |
|---:|---|---|
| 0 | POWER_LOSS / DISABLED | 落下・緊急着地制御 |
| 1 | DOCKED_SERVICE | Dock制御 |
| 2 | SERVICE_RTB | Dock帰還制御 |
| 3 | COMBAT | 武器別戦闘制御 |
| 4 | EMERGENCY_INTERCEPT | 迎撃接近制御 |
| 5 | REJOIN | 元任務への物理再合流制御 |
| 6 | MISSION | 任務別制御 |
| 7 | GENERAL_FLIGHT | Follow / Return / Orbit / Waypoint |
| 8 | IDLE | 停止 |

Recoveryは上位状態を変更せず、選ばれたFlight Intentの経路だけを補正する。ただし任務進行とリーダー可否にはRecovery情報を明示的に渡す。

### 8.2 Mission State

排他的な型として保持する。

```text
NONE
WAYPOINT
ROUTE_PATROL
TRACK_ENTITY
FIELD_OPERATION
SECURITY_PATROL
CARGO_ROUTE
ENGINEER_SUPPORT
FOLLOW_FORMATION
```

任務を一時離脱する際は消さず、`SuspendedMission`として保持する。

### 8.3 GUARD専用状態

```text
PATROL
DETECT
TRACK_ONLY
ASSIGNED_INTERCEPT
ENGAGE
EGRESS
REJOIN_PATROL
SERVICE_RTB
SERVICE_READY
```

推奨遷移:

```text
PATROL
  -> DETECT
  -> TRACK_ONLY                  非選抜。警戒配置を維持
  -> ASSIGNED_INTERCEPT          選抜。警戒配置を離脱
  -> ENGAGE
  -> EGRESS
  -> REJOIN_PATROL
  -> PATROL

任意状態
  -> SERVICE_RTB                資源閾値到達
  -> SERVICE_READY
  -> 脅威再評価
     -> ASSIGNED_INTERCEPT      増援が必要
     -> REJOIN_PATROL           増援不要
```

### 8.4 単一Flight Intent

各tickで最終的に次の一組だけを生成する。

```text
FlightIntent
  ownerState
  targetPosition
  speedProfile
  headingPolicy
  altitudePolicy
  collisionPolicy
  formationMembership
  leaderEligibility
  arrivalCondition
  trailStyle
  statusLabel
```

目標位置、速度、軌跡、UIを同じIntentから取得すれば、表示と挙動の不一致を防げる。

## 9. 必須不変条件

1. 1 tickに飛行目標を所有する上位状態は1つだけである。
2. Active Combatと通常任務操舵は同時に機体へ力を加えない。
3. REJOIN完了前の機体は通常Wingの隊形人数、旋回人数、リーダー候補に含めない。
4. GUARDの接触検知だけでは警戒スロットを離れない。
5. 戦闘投入機数は中央のDispatch結果と一致する。
6. 手動Wing所属は一時任務、戦闘、充電で消えない。
7. 複数機命令はサーバー上で一括適用される。
8. `MISSION_EXPECTED`は命令確定前に縮小しない。
9. Recovery中の機体はルート通過判定とリーダー判定から除外する。
10. 状態遷移は専用APIを通し、直接Data fieldを書き換えない。
11. 任務解除、一時停止、再開を別操作として扱う。
12. UI状態、軌跡、サウンドは最終Flight Intentから導出する。
13. 保存・再読込後もWing、任務、サービス、戦闘復帰の整合性を保つ。
14. 飛行電力0、Dock消失、対象消失、チャンク未読込に明示的な状態を持つ。

## 10. 推奨修正順序

### Phase 1: 不安定化の停止

1. GUARDの非選抜機が接触へ移動する処理を止める。
2. 複数機命令に割当猶予を設け、期待数の即時縮小を止める。
3. REJOIN中をWing一時離脱として扱う。
4. Recovery中のリーダー委譲とルート進行抑止を統一する。

### Phase 2: 状態所有権の統一

1. `ResolvedOperationalState`を導入する。
2. `FlightIntentResolver`へ目標・速度・表示を集約する。
3. Mode直接書換えを状態遷移APIへ置換する。
4. GUARD専用状態を導入する。

### Phase 3: 群命令と永続化

1. `FleetCommandBatch`を導入する。
2. Mission Registryをサーバー管理にする。
3. Wing membershipとMission membershipを分離する。
4. Field Operation Registryを永続化する。

### Phase 4: 整理と回帰試験

1. 旧防壁コードを削除する。
2. 状態遷移イベントログを追加する。
3. 単機、8機Wing、複数Wing、100機で同じ遷移試験を行う。
4. 通常、敵検知、増援、充電交代、対象消失、Dock消失、巨大障害物、再読込を組み合わせて検証する。

## 11. 必要な状態遷移ログ

各機体について、状態が変化した時だけ次を記録する。

```text
tick
unitId
wingId
missionId
previousOperationalState
nextOperationalState
transitionReason
targetEntity / targetPosition
flightIntentOwner
leaderId
cohortId / rank
battery / weaponPower / health
recoveryLevel
resumeToken
```

GUARDでは追加で次を記録する。

```text
threatTrackId
enemyThreat
playerDanger
requestedResponders
selectedResponders
combatChannel
patrolSlot
rejoinSlot
```

これにより「状態表示が揺れた」のか、「目標が上書きされた」のか、「選抜結果が変わった」のかを分離できる。

## 12. 監査判定

現行実装は、個別機能の状態は豊富に定義されているが、複数状態を統合する上位状態機械がない。そのため、条件分岐の追加を続けるほど優先度が実行順へ埋め込まれ、GUARD、Wing、Combat、Recoveryの相互作用が予測しにくくなる。

修正方針としては、既存機能を削る必要はない。必要なのは次の3点である。

1. **任務・戦闘・帰還のどれが現在の飛行を所有するかを1か所で決める。**
2. **一時離脱と復帰を明示的な状態として扱う。**
3. **群命令を機体別更新ではなく一括トランザクションとして適用する。**

この3点を先に直すことで、GUARD挙動だけでなく、ALL地点移動、ルート巡回、Scout停止、充電復帰、編隊乱れの共通原因をまとめて解消できる。

## 13. 0.15.2 TARGET TRACK・移動目標交戦追補

### 13.1 追跡対象の区分

TARGET TRACKは対象の存在だけでなく、毎回次の区分を再評価する。

| 区分 | 観測 | 迎撃通報 | 攻撃 |
|---|---:|---:|---:|
| `INVALID` | 最終既知位置へフォールバック | しない | しない |
| `FRIENDLY` | 継続可能 | しない | しない |
| `NEUTRAL` | 継続 | しない | しない |
| `HOSTILE` | 継続 | Scout/Securityが通報 | 許可 |
| `DIRECT_THREAT` | 継続 | 最優先で通報 | 許可 |

所有者本人、Morrowgear機、同盟対象は攻撃対象にしない。Enemy系、所有者を攻撃対象にしたMob、直近の攻撃者は交戦可能とする。戦闘開始後も区分を再評価し、敵性が失われた場合は攻撃を中止してREJOINへ移る。

### 13.2 個体別射撃許可

レーザー群は全機の理想位置到着を待たない。各機が充填完了後、射程と視線条件を満たした時点で個別に照射へ移る。隊形スロットは継続して目標とするが、射撃可否の同期障壁にはしない。

- 理想スロット許容内: 命中精度100%。
- 射程内かつスロット誤差あり: 距離と誤差に応じて18%から99%。
- 射程外または遮蔽あり: 射撃しない。
- 低精度時は照準線も実際に偏差し、見た目と命中判定を一致させる。

同じ射撃解をGUN RUNへ適用し、移動目標によって経路中心がずれても射程内なら個体別に射撃できる。理想経路上では命中精度100%とし、位置誤差が大きいほど精度を落とす。ミサイルは誘導兵器のため命中精度低下ではなく、射程内への進入を個体別発射条件とする。

### 13.3 GUN RUN速度連続性

敵が攻撃機を追って急接近した場合はブレークアウェイへ移るが、1 tickあたりの速度ベクトル変化を`0.085`以下に制限する。通常の等弧長8の字経路、ブレークアウェイ、通常経路への復帰の間で急激な加減速を発生させない。

### 13.4 0.15.2検証記録

- 自動試験: 1,485件 PASS、失敗・エラー・スキップ0件。
- ビルド: PASS。
- Launcher反映: `morrowgear-drone-latest.jar`を更新し、起動ログで`morrowgear_drone 0.15.2`を確認。
- 実ゲーム適応戦闘試験: 6/6 PASS。
  - 隔離検証区画の生成。
  - 複数レーザー群の空域予約。
  - ルート巡回からの一時迎撃と任務復帰。
  - 武器充電時の代替機投入。
  - レーザーHEATサービスと戦線復帰。
  - GUN RUNの攻撃レーン、照準、速度連続性。
- 実ゲームログ: 試験中の`ERROR`、`Exception`、`FATAL`なし。

## 14. 0.15.3 HEAT運用・機関砲連射速度

- HEAT 900以上はレーザー使用停止条件であり、Dockサービス帰還条件ではない。
- 過熱したレーザー機は戦闘要素から一時離脱するが、Wing、哨戒、任務、Dock割当を保持する。
- 非照射時は飛行中も自然冷却し、HEAT 900未満で交戦候補へ自動復帰する。
- 他の使用可能機は、冷却中のレーザー機を投入可能数として数えず、必要戦力を補完する。
- 機関砲の発射間隔を3 tickから2 tickへ変更し、20 TPS時の連射速度を毎秒約6.7発から10発へ引き上げる。

## 15. 0.15.6 レーザー目標所有権・増援交代安定化

- `LASER_CHARGE` / `LASER_FIRE` は開始時の敵個体IDを排他的に保持し、対象死亡または敵性解除まで共有接触情報による再選択を禁止する。
- 別目標にロック中のレーザー機を、他目標の候補名簿へ混入させない。
- レスポンダー選抜は現在交戦中の機体を最優先し、帰還機の再出撃予約は欠員がある場合だけ使用する。
- 代替増援が必要数を維持している場合、サービス完了機は交戦群へ割り込まず、代替機が離脱した哨戒・ルート・地点・フィールド任務を引き継ぐ。これにより増援機と復帰機の反復交代、および元任務の空白を防ぐ。
- 敵1体への要求戦力は脅威度と損耗圧力に応じて1〜8機で変動する。8機は攻撃空域の過密を防ぐ敵単位の上限とする。
