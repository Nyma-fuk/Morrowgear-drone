# 運用通信・イベント履歴の接続仕様

2026-09-22。[通信契約](DESIGN_RADIO_CARRIER_V2.md)に対応する新規ヘルパー。
既存のエントリーポイント、機体、HUD、戦術画面、言語資産、音声資産は変更していない。
UIと音声の試聴・承認・接続は親タスクが担当する。実ゲーム確認は未実施。

統合追記: 親タスクで共通・クライアント初期化からのregister呼び出しと26種類の日本語・英語メッセージを接続した。音声再生器、HUD字幕、履歴・設定画面は承認待ちで未接続。下記の「親側」は必要な残作業とAPI契約を示す。

## 必須の接続

共通エントリーポイントの初期化から一度だけ呼ぶ。

```java
jp.morrowgear.drone.OperationCommunicationsServer.register();
```

クライアント初期化から一度だけ呼ぶ。

```java
jp.morrowgear.drone.client.OperationCommunicationsClient.register();
```

両ヘルパーは重複登録を防ぐ。Payload登録、Entityロード／アンロード、5 tick監視、
接続／切断、次元変更、クライアント更新は内部登録する。DroneEntityへのフックは不要。

## 音声コールバック

以下のAPIは固定。親タスクの専用ネイティブ音声インスタンスを接続できる。
ゲームのアクセシビリティNarratorはこの実装から参照しない。

```java
OperationCommunicationsClient.setVoiceSink(new OperationCommunicationsClient.VoiceSink() {
    public boolean play(OperationCommunicationsClient.VoiceCue cue) {
        // cue.eventId(), cue.cueId(), cue.voiceNumber(), cue.count(), cue.volume()
        // 親側の承認済み短文を専用の所有者チャンネルで再生する。
        return false; // 音声が利用不能ならfalse。字幕と履歴は維持される。
    }
    public void stop() {
        // 割込、切断、設定OFF、最大時間到達時に停止する。
    }
});
```

- `VoiceCue(UUID eventId, String cueId, String voiceNumber, int count, float volume)`。
- 音声は最大3秒。完了時にクライアントスレッドで `voiceFinished(eventId)` を呼べる。
- `play`/`stop` はクライアントスレッドで実行。非同期音声側からの完了通知はMinecraftの実行キューへ戻す。
- 同時発話は1件。音声はWingまたは機体の短い安定番号と短文に限定し、任意名や全文字幕は読まない。
- 数字のないWing名は名前から安定した短い番号を得る。番号は識別用UUIDではない。
- コールバック例外／未設定／falseでも履歴・字幕は機能する。音声バイナリ、音源、クラウドTTSは追加しない。
- 再接続、次元変更時の履歴snapshotは音声・字幕を開始しない。

## HUD・履歴・設定API

| API | 用途 |
| --- | --- |
| `OperationCommunicationsClient.history()` | 新しい順の不変リスト。画面の再開で消去しない |
| `OperationCommunicationsClient.subtitle()` | 現在の字幕。最大3秒、設定OFFなら空 |
| `OperationCommunicationsClient.queuedCount()` | 認証待ちを含む待機件数、最大8 |
| `OperationCommunicationsClient.setSelectedContext(Predicate<OperationEvent>)` | 選択Wing／注視機体による開始・復帰の優先化 |
| `OperationCommunicationsConfig.current()` | 現在の設定 |
| `OperationCommunicationsConfig.save(OperationRadioSettings)` | 設定の保存と反映 |

`OperationEvent` は `sourceUnit()`、`wing()`、`sourceName()`、`voiceNumber()`、`count()`、
`dimension()`、`x()/y()/z()`、`occurredAtMillis()`、`kind()`、`messageKey()`、`messageArgs()` を提供する。
任意名は字幕用。`source()` はWingがあればWing、なければ機体名。集合報告の `sourceUnit()` と位置は代表機。
選択Wingは `event.wing().equals(selectedWing)` で判定できる。特定機体は `sourceUnit()` を利用する。
`count()` はその変化を確認した機数であり、Wing全機の完了を意味しない。

設定ファイルは `config/morrowgear-communications.properties`。
`voice=OFF|IMPORTANT|STANDARD`、`volume=0..1`、`subtitles=true|false`。
初期値はIMPORTANT、0.7、字幕ON。音声OFFでも字幕を独立して利用できる。
設定画面は追加していない。

## メッセージとCue一覧

HUD翻訳キーは `communication.morrowgear_drone.<cueId>`。
引数は常に `[source(), count()]`。例: `%1$s: 作業完了 (%2$s機)`。
親タスクは通常の `Component.translatable(event.messageKey(), event.messageArgs().toArray())` を利用できる。
以下は短文候補であり、試聴承認済みの音声ではない。Wing番号の付加を含め約3秒以内に収める。

| cueId | 短文候補 | 発生条件 |
| --- | --- | --- |
| `power_lost` | 飛行電力喪失 | サーバーが電力喪失への変化を検出。機体自身の発声ではない |
| `danger` | 危険状態 | 体力が既存の危険判定値以下へ変化 |
| `emergency_attack` | 緊急攻撃 | 実際のRAM_APPROACH、対象生存確認 |
| `intercepting` | 迎撃に移行 | 実際の戦闘／迎撃、対象生存確認 |
| `operation_blocked` | 作業停止 | BLOCKED。取得できない原因は推測しない |
| `material_low` | 整備資材不足 | MATERIAL_LOW |
| `cargo_stalled` | 輸送停滞 | 待機／順番待ちで積載数に変化がない状態が30秒継続 |
| `target_lost` | 目標喪失 | 追跡任務の対象が読み込み済み次元内に存在しない／生存しない |
| `follow_started` | 追従開始 | 新しい追従任務 |
| `route_started` | 巡航開始 | Waypoint／Orbit任務 |
| `patrol_started` | 巡回開始 | Security Patrol任務 |
| `survey_started` | 調査開始 | SCANNING／SCOUT_SURVEYの作業セッション |
| `work_started` | 作業開始 | WORKING／PLANTINGの作業セッション |
| `cargo_started` | 輸送開始 | 停止中でない輸送セッション |
| `repair_started` | 整備開始 | REPAIRING |
| `salvage_started` | 回収へ移行 | 回収対象が存在する回収任務 |
| `salvage_attached` | 回収機を確保 | 回収予約と実際の懸吊成立を台帳で確認 |
| `laser_started` | 照射開始 | LASER、LASER_FIREと現在状態開始以降のcombatShotTickを確認 |
| `gun_started` | 射撃開始 | AUTOCANNON、GUN_RUNと現在状態開始以降のcombatShotTickを確認 |
| `missile_started` | ミサイル発射 | MISSILE、APPROACH／EGRESSと実際の発射成功で更新されたcombatShotTickを確認 |
| `mission_resumed` | 任務復帰 | REJOIN終了後、同じ任務が実行中 |
| `service_return` | 補給へ帰還 | serviceReturnが有効で未着艦 |
| `return_started` | 帰還開始 | 通常のRETURN |
| `docked` | 着艦 | 着艦状態への変化。補給完了とは別 |
| `service_complete` | 補給完了 | 補給中の着艦から離陸し、通常出撃資源条件を満たす |
| `operation_complete` | 作業完了 | 明示的なFieldOperationState.COMPLETE |

## 優先度・履歴・認証

- 危険／電力喪失 > 迎撃／作業停止 > 選択部隊の開始／復帰 > 通常開始 > 完了。
- 同一所有者、Wing、任務、次元、意味、戦闘対象を1秒窓で集約。別戦線を混ぜない。
- 通常間隔4秒、同一意味抑制15秒、有効期限8秒、待機上限8、同時1発話。
- 重大警告は通常発話に割込可能。同じ警告は重複抑制対象。
- 履歴は所有者UUIDごとのSavedData `morrowgear_drone:operation_history/<UUID>` に最新128件。
  保存は通常のワールド保存に従い、クラッシュ直前の未保存分まで保証しない。
- 履歴と再生キューは別。過去の履歴は再接続で戻るが、音声の待機・再生・認証情報は戻さない。
- C2Sはsession UUIDとevent UUIDだけ。所有者は必ず `context.player()` から得る。
- 再生直前にサーバーへ照会し、現在の所有権、任務識別子・観測世代、状態、対象UUID／生存、期限を再検証。
  消えた機体は除外し、集合報告の現在有効機数を返す。全機無効なら再生しない。
- 所有者ごとに照会を20 tickあたり4件へ制限。未発行イベント、別session、他所有者の履歴からの再生要求は成立しない。
- 文字列、リスト、機数、UUID、enum、時刻をCodec境界で検証。snapshot／liveは最大128イベント。

## 監視範囲と制約

- Entityロード／アンロードで専用キャッシュを維持し、5 tickごとに読み取る。
  既存の `ownedDrones()` はWing正規化、`isOwnedBy(ServerPlayer)` は所有者移行を伴うため使わない。
- 全体4096読み込み済み機体、所有者ごと512機まで。超過分は監視対象外。ワールド全Entityの再走査、
  チャンクの強制読込、所有権・任務・制御状態の変更は行わない。別次元の読み込み済み所有機も対象。
- 初回観測、ロード、再接続、所有者の次元変更は無音の基準化。参加前の状態から開始イベントを復元しない。
- 任務ID・目的地・ルート・Field/Patrol注文・Cargo端点等から観測世代を進める。
  5 tick間に完結して元へ戻る状態変化や、公開情報が同じ命令の再発行は識別できない。
- REJOIN入り口、各巡回点、毎ブロック、毎射、細かい護衛切替は読まない。
- 射撃系は状態だけで発話しない。過去のパス・別兵装・未来・8秒以上前のshot tickは不採用。
  認証時も同じ証拠を確認し、発射成功後の報告を1秒の集約窓中に失効させない。反復抑制は共通observer／queueが担当する。
- 回収のDELIVER入り口や対象消失だけから引渡し成功を推測しないため、回収搬入完了cueは未実装。
  独立Cargoの最終完了、任務消失の詳細理由も確実な読取情報がないため推測しない。
- ネットワーク遅延中に状態が変わる可能性は残る。再生許可は直前のサーバー確認であり、制御ロックではない。

## 検証手順

```powershell
.\gradlew.bat test --tests 'jp.morrowgear.drone.Operation*' compileClientJava -x syncLauncherMod
.\gradlew.bat build -x syncLauncherMod
```

他タスクの未完成テストがコンパイルを妨げる場合に限り、変更せずに対象試験を実行する。

```powershell
.\gradlew.bat -I tools/operation-communications-tests.init.gradle test --tests 'jp.morrowgear.drone.Operation*' compileClientJava -x syncLauncherMod
```

優先度、割込、期限、再接続、100機集約、所有者分離、任務A→B→A、対象死亡／消失、次元変更、
補給資源不足、状態往復、30秒停滞、保存Codec、Payload上限、長い字幕文字列、音声OFFを自動試験する。
実ゲーム表示、ネイティブ音声の音量・音質・3秒以内の明瞭性は親タスクの承認後の確認事項。

2026-09-22の実行結果: 対象試験50件成功、共通／クライアントコンパイル成功。
Launcher同期は全コマンドで除外し、インストール・起動・コミットは行っていない。
全体の `build -x syncLauncherMod` は2091件実行、6件失敗、8件skipで失敗。
失敗は並行追加中の `SupplyNetworkTransferTest` のNullPointerExceptionで、最後にtest runnerのEOFExceptionも出た。
当タスクではSupply側を変更していない。共有ツリーの全体合格や実ゲーム・実音声合格とは扱わない。

## 追加ファイル一覧

```text
src/main/java/jp/morrowgear/drone/OperationEvent.java
src/main/java/jp/morrowgear/drone/OperationObservation.java
src/main/java/jp/morrowgear/drone/OperationEventObserver.java
src/main/java/jp/morrowgear/drone/OperationEventCodecs.java
src/main/java/jp/morrowgear/drone/OperationHistory.java
src/main/java/jp/morrowgear/drone/OperationHistoryData.java
src/main/java/jp/morrowgear/drone/OperationRadioSettings.java
src/main/java/jp/morrowgear/drone/OperationRadioQueue.java
src/main/java/jp/morrowgear/drone/OperationClientStore.java
src/main/java/jp/morrowgear/drone/OperationCommunicationsServer.java
src/main/java/jp/morrowgear/drone/OperationShotPolicy.java
src/main/java/jp/morrowgear/drone/network/OperationWire.java
src/main/java/jp/morrowgear/drone/network/OperationEventsPayload.java
src/main/java/jp/morrowgear/drone/network/OperationCueRequestPayload.java
src/main/java/jp/morrowgear/drone/network/OperationCuePayload.java
src/client/java/jp/morrowgear/drone/client/OperationCommunicationsConfig.java
src/client/java/jp/morrowgear/drone/client/OperationCommunicationsClient.java
src/test/java/jp/morrowgear/drone/OperationTestFixtures.java
src/test/java/jp/morrowgear/drone/OperationEventObserverTest.java
src/test/java/jp/morrowgear/drone/OperationRadioQueueTest.java
src/test/java/jp/morrowgear/drone/OperationClientStoreTest.java
src/test/java/jp/morrowgear/drone/OperationCodecTest.java
src/test/java/jp/morrowgear/drone/OperationIntegrationContractTest.java
src/test/java/jp/morrowgear/drone/OperationShotPolicyTest.java
tools/operation-communications-tests.init.gradle
docs/design/OPERATION_COMMUNICATIONS_IMPLEMENTATION.md
```
