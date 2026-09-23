# Dock専用補給・コンテナ仕様

## 実装範囲

専用兵装補給品、Dockの消費・残量保存、27枠のコンテナ制約、サーバー同期データ、物流向けAPIを実装する。新規アイコン、テクスチャ、画面、翻訳、音声はこの変更に含めない。新規の視覚資産は提示案の承認後に別途統合する。

## 補給品と暫定バランス

| 安定ID | 1個の供給量 | 1回の製作材料 | 最大スタック |
|---|---:|---|---:|
| `morrowgear_drone:autocannon_magazine` | 120発 | 鉄4、火薬1、ブレイズパウダー1、銅1 | 16 |
| `morrowgear_drone:laser_cell` (兵装電力セル / Weapon Energy Cell) | 共通兵装電力1,000 | クォーツ2、グロウストーンダスト1、アメジスト2、レッドストーン1、銅1 | 16 |
| `morrowgear_drone:micro_missile_pack` | ミサイル5発 | 鉄2、火薬3、エンダーアイ1、銅2、レッドストーン1 | 16 |

各レシピはバニラ材料だけを使う作業台レシピで、完成数は1個。機関砲はNether素材を継続的に要求する。兵装電力セルはクォーツ・グロウストーンとOverworldの鉱物を組み合わせる。レーザーの本体モジュールに既存の後期探索条件があるため、消耗品にBreeze Rodを毎回要求しない。ミサイルは誘導部品としてエンダーアイを消費し、強い単発兵装に継続コストを持たせる。これは調整案であり、実戦での燃費・火力検証は未実施。

鉄塊や花火ロケットは弾薬として受け付けず、自動変換もしない。既存ワールドの該当スタックはそのまま取り出せる。石炭・木炭・既存Power Cell・3種類のバッテリーパックは飛行電力専用であり、兵装電力には変換しない。

`laser_cell`と`SupplyKind.LASER`は安定識別子であり、レーザー専用という意味ではない。現行の機関砲・ミサイルも`WEAPON_POWER`を消費するため、兵装電力セルはこれらを含む共通の兵装電力を補給する。機関砲マガジンとミサイルパックは弾数だけを補給し、兵装電力を無償生成しない。

## 供給量と保存

- `provideAutocannonRounds(int)`、`provideWeaponCharge(int)`、`provideMissiles(int)`は要求以下の実供給量を返す。負数・0では消費しない。
- 開封済みクレジットを先に使う。空のときだけ1個開封する。1回の呼出しで複数パックを開けないため、大きな要求には複数回の呼出しが必要になる。
- 例: 1発だけ不足している機体にマガジンを使うと、1発を渡し119発をDockへ保存する。他の機体もこの119発を利用できる。
- 共通兵装電力は`WeaponCredit`、機関砲弾は`AutocannonCredit`、ミサイル弾は`MissileCredit`へ保存する。通常値の範囲はそれぞれ0..999、0..119、0..4。新規保存キーがない旧データは0として読み込む。
- 正常なサービス操作では「消費したパック数 × 内容量 = 機体に渡した総量 + 保存クレジット」が成立する。表示・在庫照会だけではアイテムを開封しない。
- `StoredPower`と`FuelCredit`は既存キーを維持する。燃料は石炭1,000、木炭800、Power Cell 1,200、バッテリー1,000/1,800/3,000。
- 蓄電容量は既存の4,000/6,000/10,000/18,000。容量を下げた際の超過分は`FuelCredit`へ戻し、削除しない。読み込み時の異常値対策としてタンクは最大18,000、タンクと燃料クレジットの合計は最大118,000とする。これは旧100,000クレジットと最大タンク量を保持できる上限。
- 新設Dockの電力は0。`StoredPower`を持たない旧保存形式だけは従来の2,000を移行値とする。通常の設置し直しで電力を獲得できない。
- インベントリの`clearContent()`は開封済みクレジットを消さない。ブロック破壊・撤去時の内部電力のアイテム化は実装しない。
- `provideFlightCharge(int)`は最大で現在のタンク容量まで供給する。`provideCharge(int)`は全量を供給できる場合だけtrueを返し、容量超過要求は燃料を消費せずfalseとする。
- 修理は既存の銅・鉄・Morrow Alloyの処理を維持する。修理材の端数保存は今回の弾薬・電力クレジットとは別の範囲。

## 27枠の互換性

保存・メニューともに同じインデックスを使用し、移動や詰め直しを行わない。

| インデックス | 用途 | 新規挿入制約 |
|---|---|---|
| 0 | 機体 | `field_drone_unit`、最大1 |
| 1 | 機体用バッテリー | 3種類のバッテリーパック、最大1 |
| 2 | 役割モジュール | 既存5種類、最大1 |
| 3 | 兵装モジュール | 既存3種類、最大1 |
| 4 | 飛行電力入力 | `FUEL` |
| 5 | 修理材 | `REPAIR` |
| 6 | 兵装補給入力 | `GUN`、`LASER`、`MISSILE` |
| 7 | 回収出力 | 取り出し専用 |
| 8 | Dock容量拡張 | 3種類のバッテリーパック、最大1 |
| 9..17 | 回収バッファ | 取り出し専用 |
| 18..26 | 共通補給バッファ | 5種類の`SupplyKind` |

`ContainerHelper.saveAllItems/loadAllItems`を継続使用する。旧Chestメニューで用途外の枠に入ったアイテムも削除・変換しない。サービスは対応する入力枠と18..26だけを探索する。回収品、機体用バッテリー、装着モジュール、容量拡張用バッテリーは消費しない。旧誤配置の補給品は入力枠か補給バッファへ移す必要がある。

ドラッグ、通常クリック、右クリック分割、ホットバー交換、ダブルクリック収集はMinecraft標準処理へ委譲し、`Slot.mayPlace`で制限する。Shiftクリックは機体・モジュール、容量拡張、専用入力、共通バッファの順で適合枠に挿入し、バッファでは既存スタックへの合流を優先する。装着用バッテリー2枠が埋まった後の余剰バッテリーは燃料枠・補給バッファへ入る。補給対象外のアイテムはプレイヤー側のメイン枠とホットバー間だけを移動する。

権限はサーバー側で検証する。開く際と操作時に所有者UUID、同一ワールド、中心から8ブロック以内、元のBlockEntityの存続を確認する。従来の所有者名による移行APIは維持するが、所有者未設定でないDockを単なるsingleplayer条件だけで自動譲渡しない。物流APIにはプレイヤー引数がないため、ルートの所有者・アクセス権検証は呼出し側が担当する。

## 親統合API

### 登録

共通初期化でレジストリ凍結前に以下を呼ぶ。

```java
SupplyItems.register();
DockMenu.register();
```

公開アイテム定数は`SupplyItems.AUTOCANNON_MAGAZINE`、`LASER_CELL`、`MICRO_MISSILE_PACK`。MenuTypeは`DockMenu.TYPE`、安定IDは`morrowgear_drone:dock`。Creativeタブへの列挙、翻訳、承認済み画面の登録は親タスク側の統合箇所。

### サービス・物流

以下は`DockBlockEntity`の公開API。既存`provide*`の引数と戻り値は変更しない。

```java
boolean hasPowerSupply();              // 飛行のみ。互換エイリアス
boolean hasFlightPowerSupply();
boolean hasWeaponPowerSupply();        // LASERのクレジットまたは未開封在庫
boolean hasAutocannonAmmunition();      // GUNのクレジットまたは未開封在庫
boolean hasMissileAmmunition();         // MISSILEのクレジットまたは未開封在庫
boolean completionResourceExhausted(boolean flightRequired, boolean weaponRequired,
    boolean ammunitionRequired, boolean ammunitionAvailable);
int supplyCount(DockSupplyPolicy.SupplyKind kind);
int insertNetworkSupply(ItemStack stack);
int storedFlightPower();               // タンクのみ。storedPower()も維持
int flightFuelCredit();                // タンク外の既開封燃料
int storedWeaponPower();
int weaponPowerCapacity();             // 1000
int autocannonCredit();
int missileCredit();
DroneEntity dockedDrone();             // 所有者・割当Dock・距離を確認。なければnull
void setSupplyCreditsForVerification(int weapon, int gun, int missile);
```

`supplyCount`は対応入力枠・共通バッファの物理アイテム数。クレジット、回収枠、装備枠は数えない。`null`は0。`SupplyKind`は`FUEL,GUN,LASER,MISSILE,REPAIR`で、明示的な`index()`は0..4、`mask()`は1,2,4,8,16。名前と値を変更しない。

`insertNetworkSupply`はサーバースレッドから呼ぶ。挿入できた個数を返し、引数スタックを同数だけ縮める。呼出し側で重ねて縮めない。拒否・満杯では0で、引数は変化しない。18..26だけに入れ、既存スタックへの合流を優先する。アイテム名・コンポーネントが異なるスタックは合流しない。取り外し済みDock、クライアントワールド、同じDock内のスタック自身を渡す操作は拒否する。

`setStoredPowerForVerification(0)`は飛行電力・燃料クレジットだけを消去する。空の検証fixtureでは続けて`setSupplyCreditsForVerification(0,0,0)`を呼ぶ。これは通常ゲーム操作のクレジット消去経路ではない。

### 機体側の待機判定

飛行の不足は`hasFlightPowerSupply()`、兵装電力の不足は`hasWeaponPowerSupply()`へ分ける。片方だけの在庫で、もう片方の充填が継続可能と判定してはいけない。上の`completionResourceExhausted`または親側の独立した判定を使用する。弾薬はロードアウトごとの必要条件を使い、AUTOで片方の弾薬だけ残っていることが他方の欠品を隠さないようにする。

親の`DroneEntity`では「鉄塊を追加」「ロケットを追加」表記、旧検証fixture、拠点生成時の配備補給品も置き換える。専用補給品を登録するだけでは、古い機体側の`hasPowerSupply()`併用判定は解消しない。

## メニューと同期契約

パッケージは`jp.morrowgear.drone`。コンストラクタは以下の通り。

```java
DockMenu(int syncId, Inventory inventory); // client: SimpleContainer(27), SimpleContainerData(40)
DockMenu(int syncId, Inventory inventory, DockBlockEntity dock);
DockMenu(int syncId, Inventory inventory, Container container, ContainerData data);
```

Dock枠0..26の座標は`x = 8 + (slot % 9) * 18`、`y = 18 + (slot / 9) * 18`。プレイヤー枠27..53は`(8,85)`から3行、ホットバー54..62は`y=143`。これはコンテナ座標の契約であり、新規画面の承認やデザイン実装ではない。

サーバーはメニュー同期時に近傍の着艦機だけを調べる。空きDockではID=-1、機体数値=0、不足=0へ戻す。読み取りは`menu.value(field)`、`menu.supplyCount(kind)`、`menu.available(kind)`、`menu.shortage(kind)`を使用する。

| 論理フィールド | 値 | 意味 |
|---|---:|---|
| `FLIGHT_POWER` / `FLIGHT_CAPACITY` / `FUEL_CREDIT` | 0 / 1 / 2 | タンク電力、容量、待機燃料クレジット |
| `WEAPON_POWER` / `WEAPON_CAPACITY` | 3 / 4 | 開封済み兵装電力、1セル量 |
| `GUN_CREDIT` / `MISSILE_CREDIT` | 5 / 6 | 開封済み残弾 |
| `STOCK_START + kind.index()` | 7..11 | 種類別の未開封アイテム数 |
| `AVAILABLE_MASK` / `SHORTAGE_MASK` | 12 / 13 | 利用可能資源、不足している必要資源 |
| `DRONE_ID` | 14 | ランタイムEntity ID。未着艦は-1 |
| `READINESS` | 15 | `EMPTY=0,SERVICING=1,READY=2,BLOCKED=3` |
| `DRONE_FLIGHT_PERCENT` / `DRONE_WEAPON_PERCENT` | 16 / 17 | 着艦機の残量率 |
| `DRONE_GUN_ROUNDS` / `DRONE_MISSILES` | 18 / 19 | 着艦機の残弾 |

`VALUE_COUNT=20`、`DATA_COUNT=40`。各論理値は下位16bit・上位16bitの2ワードへ分割し、vanillaのshort転送による118,000の燃料クレジットやEntity IDの桁落ちを防ぐ。画面側は生ワードではなく`value()`を使う。

`READINESS`は既存の通常出撃判定を再利用し、緊急出撃判断を独自に置き換えない。不足マスクは実際に必要な資源だけを示す。軽微な修理不足があっても既存ルールで出撃可能なら`READY`と修理不足ビットが共存する。

## 検証

### 実メニューの開発者検証コマンド

`DockMenuRuntimeVerification.register()`を親の共通初期化から任意で呼ぶと、`/morrowgear_dock_verify <x> <y> <z>`を登録する。座標は既存Dockの中心を指定する。直接呼出しは`DockMenuRuntimeVerification.run(ServerPlayer, DockBlockEntity)`で、全確認と復元に成功した場合だけ1を返す。登録やJUnitから自動実行しない。

この検証は未実行。Minecraftの起動・コマンド実行は今回行わない。`PASS`は実際に呼び出して処理が完了した時だけ表示する。

補助クラスのコンパイル、および`DockMenuBehaviorTest`、`DockSupplyPolicyTest`、`DockSupplyResourceTest`、`DockBackendContractTest`を指定したJUnit実行は正常終了した。これらの結果に実メニューの実行成功は含まれない。

- creativeかつgame-master権限を持つ接続中の生存プレイヤーだけを許可する。コンソール、実行スレッド違い、所有者違い、別ワールド、未読込・撤去済みDock、通常の操作距離外を拒否する。所有者の自動移行を利用して権限を取得しない。
- コマンドの実行元ワールドではなく、実プレイヤーの現在のワールドから既存Dockを解決する。機体・プレイヤー・アイテムEntity・ブロックを生成しない。プレイヤーの位置やゲームモードも変更しない。
- 別のコンテナUIが開いている場合、カーソルにアイテムがある場合、対象Dockを誰かが開いている場合は拒否する。コマンド実行前に通常インベントリ画面も閉じる。通常インベントリ画面の表示状態そのものはサーバーから検出できない。
- 現在の実プレイヤーの`Inventory`と既存Dockで実際の`DockMenu`を開く。27+36枠の対応、16個の補給品と1個の装着枠、通常クリック・右クリック、左/右ドラッグ、Shiftクリック、満杯時の余り、回収専用枠、ホットバー交換、全回収、所有者が無効になった後の操作拒否を実行する。左/右ドラッグだけを使い、creativeの複製操作は使わない。
- メイン枠だけでなく装備・オフハンド・追加装備枠、選択中ホットバー、個人クラフト枠と出力、カーソルをコピーして保持する。Dockはクレジットを含む全NBTを保持する。
- 全処理はサーバースレッドの同期呼出し内で完了し、tickをまたぐ試験用の状態を残さない。成功・失敗とも`finally`でDockとプレイヤーを復元する。試験メニューだけを閉じ、試験用カーソルを先に消すことで、閉じる時のアイテム落下を防ぐ。既存または外部で開かれた別メニューを閉じない。
- 復元後にDockのNBT、全プレイヤーアイテムのコンポーネントと個数、選択枠を比較する。復元失敗も`FAIL`とし、試験本体だけ成功した場合には`PASS`を出さない。

永久無効化した8本のJUnitケースは廃止し、`DockMenuBehaviorTest`を1本の明示的な登録・安全策のソース契約に置き換える。この契約の成功は実メニュー動作の成功ではない。クライアント描画、ネットワーク越しのドラッグ操作、閉じたメニューへの偽造パケット、距離・ディメンション変更後の通信試験は別途必要。

追加テストは`DockSupplyPolicyTest`、`DockSupplyResourceTest`、`DockBackendContractTest`。数値ポリシーはランダムな60,000回の供給で保存則を確認し、1,000通りの部分セルと連続再読み込み、旧保存形式、異常値、容量変更、二系統の欠品、枠別制約を検証する。レシピはJSONを構造として読み、材料個数と出力を確認する。静的契約テストは保存マッピングとメニュー接続を確認するもので、実際のプレイヤー操作試験を代替しない。

この担当の初回`build -x syncLauncherMod`は、同時作業中のcarrierおよびSupplyNetworkSavedDataのコンパイルエラーで停止した。親タスクが検証を実施中のため、競合するGradle実行は行わない。最終の全体ビルド結果は親タスクの結果を参照する。インストール・ゲーム起動・コミットは行わない。

親統合後、以下を実ゲームまたは適切な統合テストで確認する。

1. 所有者による開閉、左/右ドラッグ、Shiftクリック、ホットバー交換、ダブルクリック回収、満杯時の部分挿入。回収枠へ挿入できず、旧誤配置品は取り出せること。
2. 他人、距離超過、別ディメンション、開いたままDock撤去、権限変更後の操作拒否。
3. 1発・1電力だけ不足した機体へ補給し、チャンク再読込・再起動後も119発/999電力/4発が残ること。複数機で順に使っても増殖・消失しないこと。
4. 装備・回収枠の燃料や弾薬が消費されないこと。容量バッテリーを取り外した後も飛行電力の合計が保存されること。
5. 飛行燃料だけ残る場合、兵装電力セルだけ残る場合、弾薬クレジットだけ残る場合、完全欠品の場合の待機理由と資源制限出撃。生存条件を満たさない無理な出撃は許可しないこと。
6. 既存27枠と回収機体のコンポーネント保存、物流側の所有者検証、挿入個数だけ配送元が減ること。
7. 3レシピの通常クラフト、専用アイテム登録、クライアントのMenuType対応。見た目は承認済み資産が揃ってから別途検証すること。
