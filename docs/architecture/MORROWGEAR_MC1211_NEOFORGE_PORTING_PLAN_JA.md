# Morrowgear Minecraft 1.21.1 NeoForge 移植計画

## 0. 文書情報

| 項目 | 内容 |
|---|---|
| 対象 | Morrowgear: Drone Command Java Mod |
| 移植元 | Minecraft 26.2 / Fabric Loader / Java 25 |
| 第1移植先 | Minecraft 1.21.1 / NeoForge / Java 21 |
| 第2移植先 | Minecraft 1.21.1 / Forge。NeoForge版の同等性確認後に判断 |
| 現行基準 | v0.20.5 / commit `6b8c0e0` |
| 基準日 | 2026-10-10 |
| 文書状態 | 実装開始前の承認対象計画 |

## 1. 目的

現行Fabric 26.2版の機能、ゲーム性、保存状態、マルチプレイヤー所有権、性能特性を壊さず、Minecraft 1.21.1のNeoForge環境へ移植する。

本計画は次を同時に扱う。

1. Minecraft本体APIの26.2から1.21.1への後方移植
2. Fabric APIからNeoForge APIへのローダー移植
3. Java 25からJava 21への実行環境移行
4. 描画、通信、保存、チャンク維持、UI、音響の互換化
5. 現行Fabric版とNeoForge版を同一リポジトリで保守できる構造化
6. 計算量、Tick時間、FPS、ネットワーク量を悪化させない検証
7. 専用サーバーと複数プレイヤーでの配布可能な成果物作成

## 2. 結論と採用判断

### 2.1 移植可否

移植は可能である。ただし、単純なimport置換や一括変換ではなく、大規模な後方移植として扱う。

現行実装はMixinへほぼ依存しておらず、戦闘、編隊、補給、物流、状態遷移の多くが純粋なJavaロジックとして分離可能である。このため中核機能の再利用性は高い。一方、描画、通信、エンティティ、保存、チャンクチケットはMinecraftバージョン差の影響を強く受ける。

### 2.2 第1移植先

第1移植先はNeoForge 1.21.1とする。

理由:

- 1.21.1向け公式ドキュメントが登録、通信、画面、エンティティ同期まで揃っている
- Java 21を前提とした開発環境を構成できる
- `DeferredRegister`、イベントバス、Payload Handlerによってローダー境界を明確にできる
- 1.21.1以降の継続保守を考えた場合に、先にNeoForgeアダプターを確立する価値が高い

### 2.3 Forge対応

Forge 1.21.1も技術的には対応可能である。ただし、NeoForgeとForgeを同時に実装してはならない。先にNeoForge版で全機能同等性を達成し、その共通層とPlatform Contractを使ってForgeアダプターの追加工数を再見積もりする。

Forge版を追加する条件:

- 導入先ModパックまたはサーバーがForgeを必須とする
- NeoForge版の全回帰、性能、マルチプレイヤー試験が完了している
- ローダー固有コードがPlatform Adapterへ隔離されている
- Forge版を継続して回帰試験するCI資源を確保できる

### 2.4 採用しない方式

- Fabric/NeoForge/Forgeを一つのJARで動かすUniversal JAR
- 各クラスに`if (isFabric)`のような分岐を埋め込む方式
- 現行Fabric版を直接NeoForge版へ変形して履歴を失う方式
- Minecraft 26.2ワールドを1.21.1で直接開く方式
- UI、描画、音響を省略した状態を「移植完了」とみなす方式

## 3. 現行資産の棚卸し

2026-10-10時点の静的調査結果を基準値とする。

| 対象 | 数量 | 移植上の意味 |
|---|---:|---|
| 製品Javaファイル | 371 | `src/main`と`src/client`の合計 |
| 製品Java行数 | 約53,000 | テストを除く |
| Fabric API直接依存ファイル | 47 | Platform Adapterへの置換対象 |
| Minecraft API依存ファイル | 222 | 1.21.1コンパイル監査対象 |
| Minecraft非依存候補 | 143 | `core-domain`への移動候補 |
| テストファイル | 268 | 現行の回帰資産 |
| Minecraft非依存テスト | 193 | 両ローダーで共有する候補 |
| リソース | 429 | PNG、JSON、MGM、OGG等 |
| PNG | 173 | 原則再利用可能 |
| JSON | 169 | バージョン別形式監査が必要 |
| 独自MGMモデル | 41 | データ再利用、Renderer再実装 |
| OGG | 40 | 原則再利用可能 |
| 現行自動テスト | 2,835件 | 移植前の最低回帰基準 |

主なバージョン依存箇所:

- `net.minecraft.resources.Identifier`: 46ファイル
- `EntityRenderState`: 10ファイル
- `SubmitNodeCollector`: 16ファイル
- `StreamCodec`: 22ファイル
- `CustomPacketPayload`: 21ファイル
- `assets/morrowgear_drone/items`: 32定義
- Fabric networking import: 38箇所
- Fabric lifecycle import: 29箇所

## 4. 互換性の定義

### 4.1 完全一致が必要な契約

同じ初期状態、同じ乱数シード、同じプレイヤー命令に対して、次を一致させる。

1. 命令の受付、保留、拒否、実行、完了、失敗
2. 任務ID、目標ID、目的地、任務優先度
3. Wing、Cohort、攻撃群の構成とスロット
4. リーダー離脱、権限委譲、復帰判断
5. 敵接触、脅威度、必要機数、増援と交代要員
6. 使用兵器、ロック対象、発射数、資源消費
7. レーザー同期群、途中参加、冷却、遮蔽時の射撃判断
8. 機関砲進入、掃射、離脱の攻撃軸
9. ミサイルのマルチロック、弾道、着弾、地下目標処理
10. Dock選択、予約、待機順位、帰還、補給、再出撃
11. Cargo補給、Chest to Chest、連続配送、帰還条件
12. POWER LOST、Engineer開削、Salvage、復旧先Dock
13. 母艦の移動、攻撃、採掘、貨物、ミサイルコンテナ
14. 所有者、他ユーザー表示、操作権限、データリンク範囲
15. 保存、再読込、再接続、チャンク再読込後の継続状態

### 4.2 許容する差

- 同等に安全な経路のうち、経由点が数ブロック異なる
- パーティクル密度や光量が描画API差によりわずかに異なる
- 音声再生開始が1 Tick以内で異なる
- 重い通常処理を予算化した結果、1から4 Tick遅延する
- 内部クラス、Codec、Packet、Registry実装が異なる

### 4.3 許容しない差

- 処理予算不足を理由に任務を破棄または`STANDBY`へ落とす
- ローダー差により射程、威力、消費量、搭載数が変わる
- NeoForge版だけプレイヤー不在で遠隔任務が停止する
- 別プレイヤーの機体、Dock、貨物を操作できる
- UI表示とサーバー上の実状態が食い違う
- チャンクアンロードで任務や積荷が消える
- FPS確保のために索敵、命中、ダメージ判定を省略する
- 専用サーバーでクライアントクラスを読み込む

## 5. 目標アーキテクチャ

```text
root
├─ core-domain
│  ├─ Java 21
│  ├─ Minecraft/Fabric/NeoForge import禁止
│  ├─ 状態機、任務、戦闘、脅威、編隊、補給、物流
│  └─ 決定結果と不変条件のテスト
├─ platform-contract
│  ├─ 時刻、乱数、世界照会、保存、通信、チャンク、音響の境界
│  └─ Minecraft型を公開APIへ漏らさない
├─ fabric-26.2
│  ├─ Java 25
│  ├─ 現行Loom/Fabric実装
│  └─ Fabric固有イベント、通信、描画、保存
├─ neoforge-1.21.1
│  ├─ Java 21
│  ├─ ModDevGradle
│  └─ NeoForge固有イベント、通信、描画、保存
└─ assets
   ├─ common: PNG、OGG、lang、共有データ
   ├─ fabric-26.2: 26.2用item定義、shader
   └─ neoforge-1.21.1: 1.21.1用model、shader、metadata
```

### 5.1 共通層へ移すもの

- 状態、命令トランザクション、状態スタック
- Threat AssessmentとCombat Theaterの決定
- Wing、Cohort、Task Force、Formation Generation
- Dock割当、予約、待機列、再割当規則
- 武器選択、複合兵装、攻撃ウェーブ
- 電力、弾薬、熱、補給、再出撃判断
- Cargo、Salvage、Field Operationの計画
- 母艦の作業計画、カーゴ容量、ミサイル割当
- UIに渡す表示モデルと理由コード

### 5.2 Platform Adapterへ残すもの

- Registry登録
- Server/Level/Client Tickイベント
- Minecraft Entity、Block Entity、Menuの実体
- Payload登録、送信、受信
- SavedData、NBT、Data Component
- Chunk Ticket
- Block、Entity、Heightmap、Collisionの照会
- Renderer、HUD、Screen、Shader、Sound
- コマンド登録とゲームテスト起動

### 5.3 禁止依存

- `core-domain`から`net.minecraft`をimportしない
- `core-domain`からFabric/NeoForge APIをimportしない
- Platform Adapterからコアのprivate状態を直接変更しない
- Rendererからサーバー状態を決定しない
- Client Packetを受信しただけで所有権検証なしに状態変更しない

## 6. API移植表

| 現行Fabric/26.2 | NeoForge/1.21.1方針 | 注意点 |
|---|---|---|
| `ModInitializer` | `@Mod`コンストラクタ | client classを共通初期化で参照しない |
| `ClientModInitializer` | client限定イベント登録 | dedicated server classloading試験必須 |
| Loom | ModDevGradle | Java Toolchainを21へ固定 |
| `fabric.mod.json` | `META-INF/neoforge.mods.toml` | mod IDは維持 |
| Fabric registry helper | `DeferredRegister` | 登録順とSupplier解決時期を監査 |
| `ServerTickEvents` | server/level tickのPostイベント | PRE/POST二重実行禁止 |
| Fabric lifecycle | NeoForge server lifecycle event | stop時のcache/ticket解放を維持 |
| `PayloadTypeRegistry` | `RegisterPayloadHandlersEvent` | protocol versionと方向を定義 |
| `ServerPlayNetworking` | NeoForge payload handler/distributor | owner、tracking、dimensionで送信先を制限 |
| `ClientPlayNetworking` | client payload handler | render threadへの引渡しを明示 |
| `ExtendedMenuProvider` | NeoForge menu extension | 追加データのCodecを共有しない |
| Fabric HUD layer | NeoForge GUI layer/render event | marker予算、深度、遮蔽を維持 |
| 26.2 RenderState | 1.21.1 Entity Renderer | 毎Frameメッシュ再生成禁止 |
| `Identifier` | `ResourceLocation` | ID文字列を変更しない |
| 26.2 SavedData | 1.21.1 SavedData/NBT | schema versionを明示 |
| 26.2 TicketType flags | 1.21.1 chunk ticket API | 期限、半径、解放の同等性試験 |
| `assets/.../items` | `models/item`中心の1.21.1定義 | missing model検査を自動化 |

## 7. 性能設計

### 7.1 基本原則

移植によってコアアルゴリズムのBig-Oを変更しない。Platform Adapterは呼出頻度と処理対象を増やしてはならない。

### 7.2 Tickの単一入口

NeoForge側に`MorrowgearServerScheduler`を一つ置く。

```text
ServerTick Post 1回
  1. lifecycle cleanup
  2. owner/contact snapshot
  3. command transaction
  4. dock/supply/logistics scheduler
  5. carrier scheduler
  6. bounded world work
  7. telemetry aggregation
```

禁止事項:

- 同じ処理をPREとPOSTの両方へ登録する
- Server Tick処理を各Level Tickで再実行する
- 各ドローンから全所有機、全敵、全Dockを再走査する
- 開発用Verifierを通常サーバーで常時起動する

### 7.3 計算量契約

| 処理 | 許容する形 | 禁止する形 |
|---|---|---|
| 脅威評価 | ownerごとに`O(E + D)` | 機体ごとに`O(D × E)` |
| Dock割当 | owner単位snapshot | 各機が全Dockを毎Tick走査 |
| 編隊分離 | 空間近傍のみ | 全機総当たり`O(D²)` |
| 通信 | 差分×購読者 | 全状態×全機×全Player×毎Tick |
| 経路探索 | 予算付き、cache、backoff | 到達不能時の無制限再探索 |
| チャンク | 任務単位共有lease | 機体ごとの重複ticket |
| Renderer | 共有mesh、transform更新 | 毎Frame頂点再構築 |
| HUD | marker/label予算 | 全機体の全情報を毎Frame整形 |

### 7.4 維持する既存最適化

- `ThreatAssessment`のowner/Tick cache
- `ThreatSensorRuntime`の共有snapshot
- `NavigationEnvironment`の環境snapshot
- `DataLinkRelayNetwork`のcoverage cache
- `OperationChunkLeaseRuntime`のticket統合
- `ScoutScanVisualPolicy`のraycast budget
- `VisorHudPolicy`のmarker/label budget
- 母艦、採掘、爆発のブロック処理budget
- 到達不能経路の再試行間隔とfallback

### 7.5 性能合格値

| 指標 | 合格値 |
|---|---:|
| サーバー全体平均Tick | 50 ms未満 |
| Morrowgear通常時平均 | 5 ms/tick未満 |
| Morrowgear負荷時p95 | 10 ms/tick未満 |
| 単一処理最大占有 | 25 ms未満。超過対象は分割 |
| 無操作時パケット | 状態変化がない限り定期heartbeatのみ |
| 解放後のChunk Ticket | 期限内に0へ収束 |
| 長時間試験のheap | 安定状態へ収束し、単調増加しない |

低性能基準機として第3世代Core i3 U系サーバーを残す。高性能開発機だけで性能合格を判断しない。

## 8. 段階移植計画

### Phase 0: 基準固定

作業:

- `6b8c0e0`を移植元baselineとしてtag化
- 2,835テストの一覧、所要時間、結果を保存
- dedicated server smoke結果を保存
- 主要シナリオのDecision Traceを保存
- registry ID、Packet ID、NBT key、Data Component IDを一覧化
- 通常時と負荷時のTick、packet、chunk、heapを測定

合格条件:

- baselineを同じcommitから再現できる
- 失敗している既存試験が0件
- 既知の不具合は「移植起因」と混同しないよう記録されている

戻し先: `main`のbaseline。製品コード変更なし。

### Phase 1: 現行Fabric版に境界を作る

作業:

- Registry、Tick、Networking、Menu、SavedData、Chunk、RenderingをAdapter化
- 現行Fabric API呼出をadapter packageへ集約
- server authoritativeな状態変更口を一本化
- client-only型の共通側参照を除去

合格条件:

- Fabric 26.2の全テストと実ゲーム挙動が変更前と一致
- Fabric直接依存ファイルが定義済みadapterへ収束
- Decision Trace差分が0件

戻し先: Adapter導入前commit。新旧adapterを機能フラグで並走可能にする。

### Phase 2: `core-domain`抽出

作業:

- 143候補クラスを依存グラフ順に移動
- 時刻、乱数、UUID、世界照会をinterface化
- 193候補テストをJava 21で実行
- Minecraft型をvalue objectへ変換

合格条件:

- `core-domain`にMinecraft/loader importが0件
- Java 21で全core test成功
- Fabric版のDecision Trace差分が0件

戻し先: クラス単位で元package実装へ戻せる小さいcommitに分割する。

### Phase 3: NeoForge 1.21.1骨格

作業:

- ModDevGradle、Java 21、`neoforge.mods.toml`を構成
- `morrowgear_drone` namespaceを維持
- item、block、entity、block entity、menu、soundを`DeferredRegister`へ登録
- client/server runとdedicated serverを起動
- 共通PNG、OGG、lang、recipeを読み込む

合格条件:

- clean build成功
- client titleまで起動
- dedicated serverがclient classloadingなしで起動・正常停止
- registry missing、duplicate ID、missing modelが0件

戻し先: 空のNeoForge skeleton。Fabricモジュールへ影響させない。

### Phase 4: 最小の縦方向機能

対象: 1機のDrone、1基のDock、保存、帰還、充電、画面表示。

作業:

- Drone Entityと同期データ
- Dock Block EntityとMenu
- 所有権、配置、回収
- flight battery、service return、再出撃
- save/reload/reconnect

合格条件:

- 1機を配置、飛行、帰還、充電、再出撃できる
- サーバー再起動後にowner、状態、Dock予約が復元される
- 他プレイヤーが操作できない
- Fabric版と同じDecision Traceを出す

### Phase 5: 通信、命令、HMI

作業:

- 全Payloadを方向、権限、最大長、rate limit付きで登録
- 命令トランザクションと部分成功を移植
- Controller、Dock、Carrier、Visor画面を移植
- tracking/owner/data-link単位の差分配信

合格条件:

- 全命令が受付から完了/失敗まで追跡可能
- 押しても動かない命令には理由が表示される
- 2人マルチで所有機表示と操作が混線しない
- UIを閉じた状態で詳細snapshotを過剰配信しない

### Phase 6: 飛行、Wing、Cohort、索敵

作業:

- Navigation、Waypoint、Route、Follow、Orbit
- Wing/Cohort、leader委譲、離脱、復帰
- Scout scan、Data Link relay、Threat Network
- chunk leaseと遠隔任務

合格条件:

- 急旋回、MUSTER拘束、REJOIN停止が発生しない
- player不在の承認済み任務が継続する
- Scout接触がowner fleetへ一度だけ統合される
- 64機規模で索敵計算が`O(D × E)`へ退行しない

### Phase 7: 戦闘、補給、物流、サルベージ

作業:

- laser、autocannon、micro missile
- 兵器切替、attack wave、cooling、reload
- Dock自動補給、Cargo route、Chest to Chest
- POWER LOST、Engineer clearance、Salvage towing
- Service Dock、Solar relay

合格条件:

- 既存の武器DPS、射程、資源消費が許容差内
- 1から8機レーザー群が射撃可能機から攻撃する
- Cargoが配送完了後に理由なく`STANDBY`へ落ちない
- Salvage対象が復旧可能なDockへ戻り任務復帰できる

### Phase 8: 母艦と世界処理

作業:

- Carrier移動、内部空間、Drone service
- 採掘、プラズマ球、地形破壊、回収
- cargo/missile container、winch、4機搬送
- heavy missile、地下目標、multi-lock
- bounded block workとchunk lease

合格条件:

- player不在でも認可された作業が継続する
- 採掘、攻撃、荷役が保存・再開可能
- cargo inventoryが積み下ろしで失われない
- block処理が1 Tickを長時間占有しない
- ticketが作業完了後に解放される

### Phase 9: 描画、HUD、音響

作業:

- MGM loaderと共有mesh cache
- Drone、Dock、Carrier、Container、Missile renderer
- laser、trail、scan、plasma、explosion、winch effect
- Visor marker、combat log、target relation
- sound attenuation、loop、one-shot、重複抑止

合格条件:

- 1、8、16、32機でFPS測定
- block越しのbeam/trail表示がない
- markerが3D位置、遮蔽、画面端を正しく扱う
- 機体数に比例してmeshを毎Frame再構築しない
- 音が各機体位置から再生され距離減衰する

### Phase 10: 統合、リリース、Forge判断

作業:

- 全自動試験、専用サーバー、マルチ試験
- update/reload/reconnect、異常終了復旧
- 配布ZIP、導入手順、既知制約、hash生成
- Forge版の需要と追加adapter量を再評価

合格条件:

- Definition of Doneをすべて満たす
- Fabric版のCIも継続して成功する
- NeoForge client/serverの同一release artifactを検証する

## 9. 試験計画

### 9.1 試験層

1. Pure unit test: `core-domain`
2. Platform contract test: Fabric/NeoForge共通契約
3. Serialization test: NBT、Codec、Packet、Data Component
4. Differential replay: 同一入力のDecision Trace比較
5. Headless runtime verification: GUI操作不要
6. Dedicated server smoke
7. Multiplayer integration
8. Visual/audio in-game verification
9. Long-running performance and leak test

### 9.2 規模マトリクス

| 軸 | 値 |
|---|---|
| Drone数 | 1、8、16、32、64 |
| Dock数 | 1、8、16、32 |
| 敵数 | 0、20、50、100、200 |
| Player数 | 1、2、4 |
| Dimension | Overworld、Nether、独自内部空間、同時稼働 |
| 距離 | 同一chunk、128m、512m、2,048m、player不在 |
| 稼働時間 | 5分、30分、2時間 |

全組合の直積は実行しない。境界値、pairwise、重大シナリオを組み合わせ、次だけは固定回帰として毎release実行する。

- 64機 + 200敵 + 2 Player
- 16 Dock + 自動補給 + Cargo連続配送
- player不在の遠隔Waypoint/Salvage/Carrier mining
- 8機laser + autocannon crossing + missile multi-lock
- carrier mining + cargo loading + heavy missile同時処理

### 9.3 永続化試験

各状態で保存・停止・再起動する。

- 飛行中
- Dock進入中
- 補給中
- laser charge/fire/cooling中
- missile lock/launch/impact待ち
- Cargo積載・配送中
- Salvage牽引中
- Carrier winch途中
- Mining cursor途中
- Chunk unload直前

復元後、同じ任務へ再開するか、対象消失時の定義済みfallbackへ収束することを確認する。

### 9.4 マルチプレイヤー試験

- 所有者A/BのDrone、Dock、Carrier、Containerを同時配置
- 表示可能範囲と操作可能範囲を別々に確認
- Data Link relayが所有者を越えないこと
- Packet偽装、古いentity ID、別dimension IDを拒否すること
- Player切断、再接続、死亡、dimension移動後に状態が混線しないこと

### 9.5 視覚試験

スクリーンショット比較だけでなく、次を実ゲームで観測する。

- 画面解像度とGUI scale別のHMI
- 機体、Dock、Carrierの全方位
- 昼、夜、洞窟、雪原、Nether
- block遮蔽されたbeam、trail、marker
- 近距離/遠距離LOD切替
- 1、8、16、32機でのFrame time

## 10. 保存データとワールド移行

### 10.1 ワールドの直接ダウングレード

Minecraft 26.2ワールドを1.21.1で直接開くことはサポートしない。Minecraft本体のワールド形式を後方変換する責任をModへ持たせない。

### 10.2 Morrowgear資産の移行

必要な場合は独立したExport/Import方式を実装する。

Export候補:

- owner UUID
- Drone role、name、health、battery、weapon resource
- Wing/Cohort定義
- Dock設定とinventory
- Cargo/Container inventory
- Carrier modules、cargo、missile count
- route、mission template

Import時は1.21.1の新規ワールドへ新規entity/blockとして生成する。元worldのentity ID、chunk ID、dimension IDをそのまま利用しない。

### 10.3 Schema version

すべての保存形式へ`schemaVersion`を持たせる。

```text
schemaVersion
sourceMinecraftVersion
sourceLoader
modVersion
payload
checksum
```

未知のversionは推測して読み込まず、安全に拒否して理由を表示する。

## 11. ブランチ、コミット、CI

### 11.1 ブランチ

```text
main                              Fabric 26.2安定版
port/neoforge-1.21.1              移植統合
port/core-domain                  共通層抽出
port/neoforge-1.21.1-rendering    描画作業
```

長期間の巨大差分を避ける。Phaseごとに統合可能な小さいcommitへ分割する。

### 11.2 CI matrix

| Job | Java | 内容 |
|---|---:|---|
| `core-test` | 21 | pure test、architecture rule |
| `fabric-26.2-build` | 25 | 現行版build/test |
| `neoforge-1.21.1-build` | 21 | build/test |
| `neoforge-dedicated-server` | 21 | server smoke、client classload検出 |
| `differential-replay` | 21/25 | Decision Trace比較 |
| `resource-validation` | 21 | JSON、model、sound、ID検査 |

### 11.3 成果物名

```text
morrowgear-drone-<modVersion>-mc26.2-fabric.jar
morrowgear-drone-<modVersion>-mc1.21.1-neoforge.jar
morrowgear-drone-<modVersion>-mc1.21.1-forge.jar
```

Minecraft版とloaderをファイル名から判別できるようにする。同じファイル名で上書きしない。

## 12. リスク登録簿

| ID | リスク | 重大度 | 検出 | 対策 |
|---|---|---:|---|---|
| R01 | Tick PRE/POST二重実行 | Critical | command/state trace | 単一scheduler、呼出回数assert |
| R02 | Level数だけserver処理が重複 | Critical | dimension別counter | server/level処理の契約分離 |
| R03 | 全機×全敵走査へ退行 | Critical | candidate count | owner snapshot、空間index |
| R04 | Chunk Ticket漏れ | Critical | active lease telemetry | RAII相当のlease lifecycle |
| R05 | client classでserver crash | Critical | dedicated server CI | client source/package隔離 |
| R06 | Packetで所有権突破 | Critical | 2 Player/偽装test | server側owner再検証 |
| R07 | 保存schema不一致 | Critical | restart test | versioned codec、旧読込fixture |
| R08 | Cargo/Container item消失 | Critical | inventory checksum | atomic transfer、rollback |
| R09 | 描画でFPS低下 | High | frame profiler | mesh cache、LOD、budget |
| R10 | HUDと実状態の不一致 | High | revision比較 | server snapshotを正本化 |
| R11 | Laser群のTickずれ | High | wave trace | server startTick固定 |
| R12 | Dock予約競合 | High | invariant test | owner単位直列allocator |
| R13 | 到達不能経路の再計算loop | High | retry counter | backoff、fallback、上限 |
| R14 | resource/model欠落 | Medium | build resource validator | version別生成、missing検出 |
| R15 | 音声二重再生 | Medium | sound instance counter | event identity、cooldown |
| R16 | Java 25機能の残存 | High | Java 21 compile | coreから順にtoolchain固定 |

## 13. AI開発時の必須規則

AIへ移植作業を任せる場合も、次を省略しない。

1. 作業前に対象Phase、契約、変更対象、非対象を宣言する
2. API名を記憶で置換せず、1.21.1公式資料またはworkspace sourceで確認する
3. コンパイル成功を機能成功と扱わない
4. 既存テストを削除、緩和、skipして通過させない
5. 差分が出た場合、NeoForge版へ合わせて仕様を変えず原因を特定する
6. 1タスクでversion移植とゲームバランス変更を同時に行わない
7. visual/audio変更は実ゲーム観測を完了条件とする
8. performance変更は処理回数、時間、候補数、packet量を前後比較する
9. 保存、所有権、inventory、damageは推測で補完しない
10. 未確認事項をPASSと報告しない

## 14. 停止条件

次の場合は次Phaseへ進まない。

- Fabric baselineに回帰が発生している
- Decision Trace差異の理由が説明できない
- dedicated serverがclient-only classを読み込む
- 保存再読込で任務、owner、inventoryが欠落する
- 性能計測なしにTick登録または探索範囲を変更した
- temporary workaroundがPlatform Adapter外へ漏れている
- 既知Critical/High不具合に所有者と修正計画がない

## 15. Definition of Done

NeoForge 1.21.1移植完了は、次をすべて満たした状態とする。

- Java 21 clean build成功
- core、platform、serialization、resource test成功
- Fabric 26.2版の全回帰成功
- NeoForge版の全機能シナリオ成功
- clientとdedicated serverの起動・正常停止成功
- 2人以上のマルチプレイヤー所有権試験成功
- save/reload/reconnect/chunk reload試験成功
- 64機、200敵の性能試験が合格値内
- Drone、Dock、Cargo、Salvage、Carrierの長時間試験成功
- Controller、Dock、Carrier、Visorの全画面操作確認
- model、effect、soundの実ゲーム確認
- 導入手順、運用手順、既知制約、移行手順を日本語で更新
- versioned JAR、server package、hash、試験記録を生成
- Critical/High未解決不具合が0件

## 16. 実装開始時の最初の作業単位

最初の実装ではNeoForgeコードを書かない。次の順で開始する。

1. `6b8c0e0`のbaseline tagと試験証跡を作る
2. Fabricイベント登録を一覧化し、1 Tickあたりの呼出回数testを追加する
3. `ThreatAssessment`、`Dock Allocation`、`Weapon Selection`のDecision Traceを追加する
4. `core-domain`候補143ファイルを依存グラフで分類する
5. 最初の10から20クラスだけJava 21共通層へ移動する
6. Fabric版で挙動差0を確認してから次の単位へ進む

この順序により、Minecraft版とloaderを変える前に、移植後の正しさを比較できる基準を作る。

## 17. 参考一次資料

- NeoForge 1.21.1 Getting Started: https://docs.neoforged.net/docs/1.21.1/gettingstarted/
- NeoForge 1.21.1 Registries: https://docs.neoforged.net/docs/1.21.1/concepts/registries/
- NeoForge 1.21.1 Networking: https://docs.neoforged.net/docs/1.21.1/networking/
- NeoForge 1.21.1 Screens: https://docs.neoforged.net/docs/1.21.1/gui/screens/
- NeoForge 1.21.1 Sides: https://docs.neoforged.net/docs/1.21.1/concepts/sides/
- NeoForge 1.21.1 Models: https://docs.neoforged.net/docs/1.21.1/resources/client/models/
- Forge 1.21.x Getting Started: https://docs.minecraftforge.net/en/1.21.x/gettingstarted/
- Forge 1.21.1 Downloads: https://files.minecraftforge.net/net/minecraftforge/forge/index_1.21.1.html

## 18. 関連文書

- [Morrowgear Drone 制御アーキテクチャ・実機化設計指針](MORROWGEAR_DRONE_CONTROL_ARCHITECTURE.md)
- [サーバー性能改善・挙動互換計画](../design/MORROWGEAR_SERVER_PERFORMANCE_COMPATIBILITY_PLAN_JA.md)
- [現行機能仕様](../Morrowgear_Drone_現行仕様.md)
- [AI協働型・量産ソフトウェア開発標準プロセス](../AI_COLLABORATIVE_PRODUCTION_SOFTWARE_DEVELOPMENT_STANDARD.md)
