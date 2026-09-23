# チャンクバスターのバックエンドと接続契約

## 承認と担当境界

V27外装の確定寸法は幅26X、長さ50Z、高さ11Y。外装Entityと船UUID別の専用船内次元を使い、設置ブロックを物理移動させない。4ベイの位置は維持する。速度、消費量、船内サイズは現在の実装値であり、全資産や全画面の完成承認を意味しない。

共通初期化は親側の `CarrierModule.register()`、クライアント接続は `CarrierClientApi.register()`、画面は `CarrierModule.MENU`、描画は `CarrierModule.ENTITY`。共有補給品の `SupplyItems.register()` と `CarrierDroneServiceAdapter.install()` も必要。初期化・描画・画面・共通言語・通常ドローン任務は別担当。

保存キーは `morrowgear_drone:carrier_cabins`、船内次元は `morrowgear_drone:carrier_interior`。既存アイテムID、HomeDock、Wing保存形式を変更しない。carrierレシピの `fabric:registry_contains` は、モジュール未登録時だけ未登録アイテム参照を読み飛ばすための条件。

## 座標と描画API

- 腹部中央が原点。+Xは右舷、+Zは後方、前方は-Z。yaw=0固定で外装旋回は未実装。
- `CarrierEntity.exteriorBounds(Vec3)` の包囲箱は原点から `(-13,0,-25)..(13,11,25)`。宣言幅だけでなく実際の矩形AABBを航行判定に使用する。
- `CarrierBeamPath` を照射座標の唯一の契約とする。`origin` と `target` はともにworld-space Vec3で、`beamOrigin()` は外装の腹部光学口（local 0,-0.1,0）、`beamAim()` は実照射終点である。対Mobは遮蔽判定に使った目の位置、採掘はブロック中心。EntityDataでは原点相対Vector3fcを転送するが、復元後の判定・カリング・描画・音源は必ず同じworld-space pathを使う。描画時だけ両端へ同じrender originを減算し、片端だけを機体座標へ追従させない。
- `beamTarget()` は整数位置の互換getter。`beamActive()` がtrueのときだけ実照射表示を行う。
- モード・状態・進捗は `workMode()/workStatus()/workCursor()/workTotal()`、範囲は `operationMin()/operationMax()/operationGeneration()`。通常のEntityData追跡で同期する。
- `workPhase()/phaseTick()/phaseDuration()/sampleTick()` はサーバー確定の充填・射撃・冷却。採掘はFIREでduration=0。`beamAims(): List<Vec3>` は最大16本の実成功命中先であり、探索円柱全体を照射したことにはしない。`combatRadius()` は戦闘または戦闘previewのみ96、それ以外0。
- `captureSequence()/minedBlocks()/capturedItems()/lastCapturePosition()` はブロック除去と収納commitの両方が成功した後だけ更新する。Entity session累計で、操作ごとには消さず再読込で0。複数回収が1つの同期にまとまることがある。サーバー専用 `lastCaptureDrops()` は最後の確定ドロップの深いコピーで、AFTER breakイベントより前に更新する。

### 乗艦マーカー

`boardingProjection()` は外装位置+(0,0,18)。水平帯はX±1.5、Z16.5..19.5で、原点を含む16x16採掘チャンクの外側にある。

`visualBoardingPad(): Optional<BlockPos>` は所有者向けにサーバーが確認した安全地表の足元を同期する。空なら非表示にする。`isOwnedBy(UUID)` も同期情報から利用でき、rendererはローカルプレイヤーとの一致で表示を絞れる。

返却条件は、オンライン所有者が同じ外界にいて、生存・非spectator・非乗物状態、転送cooldown終了、外装から128以内であること。さらに読込済みの帯内9候補から、地面の支持・頭上空間・危険物・他Entityを確認する。高さ差は4超96以下。これは「所有者がそのパッドへ接触すれば通常の乗艦を試みられる」という表示情報であり、他プレイヤーへの権限付与ではない。

地表走査は10tick周期。船が0.5ブロック以上動いた場合、投影位置のBlockPosが変わった場合、時計が戻った場合も更新する。不在・搭乗後・権限条件不成立・Entity消失では明示的にクリアし、読込や距離の失効は周期を待たず表示から外す。ブロックや他Entityの変化は次の定期走査まで反映が遅れる場合があるため、実転送は必ず再検証する。

`CarrierInterior.boardingPad(CarrierEntity, ServerPlayer)` はサーバー専用の実検証API。招待者の実乗艦もこちらで権限を確認する。表示用の地表取得でチャンクを生成しない。

## 専用メニューと補給

`CarrierMenu.open(ServerPlayer, UUID)` は所有者/招待者、距離、船内割当を検証する。外装右クリック、専用console、船内パッド使用から開く。破壊船の所有者は復旧メニューから貨物・船内へアクセスできる。

| 範囲 | index | 座標 |
| --- | --- | --- |
| 貨物54枠 | 0..53 | x8+18列、y18+18行 |
| 専用補給5枠 | 54..58 | x8/44/80/116/152、y126 |
| 所持品36枠 | 59..94 | x8、y150開始、hotbar y208 |

専用補給は `CarrierSupplies.FLIGHT/WEAPON/GUN/MISSILES/REPAIR` の順。power_cell、laser_cell、機関砲マガジン、ミサイルパック、修理材をそれぞれ受け付ける。貨物表示54枠は削減せず、内部は64ページ・3456stackまで。全アイテムが64stackなら221184個だが、非stack品・複雑なlootにはこの個数を保証しない。操作は所有者のみ、招待者は状態閲覧・乗下船のみ。通常のコンテナ同期を使用し、独自アイテム移動packetはない。

ページ操作は `CARGO_PAGE` のx=requested page、y=expected menu stateId、z=current page。本人・アクセス・現menu・stateId・現page・carried空をサーバーで確認し、新containerIdのメニューを開く。古いクリックやドラッグを別ページへ誤適用しない。取出し/quickMoveは表示中の54枠に対する通常操作。採掘は全ページに対する差分収納計画で、全量が入る場合のみ実行する。旧54枠セーブは先頭ページに保持し、末尾空枠は保存を省略する。ページ収納・メニュー本体は別担当との共同実装。

所有者が外界近傍または自船内にいる間、20tickごとに飛行・兵装タンクそれぞれ最大1セルを充電する。1000全量が入る場合のみ消費し、タンク上限は各100000。専用枠を優先し、無ければ旧貨物の同種補給品を使用する。プレイヤー所持品の自動消費はない。手動REFUEL/CHARGE_WEAPONは専用枠、貨物、所持品の順。破壊船では充電しない。

suppliesは任意保存フィールドで、旧セーブでは空の5枠になる。貨物、ItemStack成分、余剰弾薬クレジットを保存する。型の違う保存アイテムを勝手に削除・供給しない。

## パケット契約

画面は `menu.view()` を読む。初期データと10tickごとの更新に、所有権、破壊状態、mode/stop、generation、次元・chunkX/Z・min/maxY、cursor/total、両タンク、previewTicks/previewMode、所有者向け招待一覧、予約ドローン一覧が含まれる。

`navigation().exterior()` は読込済み実機の現在座標優先、`destination()` は航行点、`combatTicks()` は残り時間。`bays()` は `Bay(UUID drone, int slot, boolean aligned)` の一覧で、空きベイを詰めて番号付けしない。`aligned` は進入段階完了、available、同一性、最終位置・相対速度の実判定。`gunCredit()/missileCredit()` は開封済みパックの余り。従来Navigationの3引数constructorは互換用に保持する。

`navigation().storage()` は `Storage(page,pages,usedSlots,totalSlots,long items)`、`effects()` は `Effects(phase,phaseTick,phaseDuration,sampleTick,beamAims,captureSequence,minedBlocks,capturedItems,lastCapturePosition,combatRadius)`。`navigationPaused()/loadWaitTicks()` は航行の保留状態。preview直後のメニューから探索半径を明示し、Entity同期の次tickを待たせない。

移動命令のYは母艦bounding box下端と一致する腹部高度であり、ブロック中心の`+0.5`を加えない。X/Zだけは指定ブロック列の中心（`+0.5`）を使う。保存済み`CarrierAnchor`、到着判定、衝突包絡、UIの高度表示はこの契約に従う。

送信形式は `CarrierCommandPayload(menu.containerId, menu.shipId, action, generation, x, y, z, guest)`。初期viewのmenu=-1を送信IDに使わない。未使用UUIDはNONE、未使用座標は0。任意コマンド文字列やクライアント指定ItemStackを受け取る経路はない。

`CarrierCommands.handle(payload, ServerPlayer)` は通常メニューと同一のサーバースレッド入口。menu ID、船UUID、本人の所有権・現在のアクセス条件を検証する。

| action | 意味 |
| --- | --- |
| PREVIEW_MINING / PREVIEW_COMBAT | 外装の属するチャンクを新generationで表示。まだ稼働しない |
| ACTIVATE | 表示generation、200tick期限、所有者、読込、機体と範囲の位置関係を再検証 |
| RESUME_PREVIEW | 保存された採掘範囲とcursorを再表示。ACTIVATEが別途必要 |
| STOP | 所有者の緊急停止。5tick操作制限の対象外 |
| BOARD / EXIT | 接触帯/船内出口と同じ安全転送。退出失敗も短期cooldownで抑制 |
| MOVE | 同次元world border内の1地点へ航行。停止中の保存目的地を再指定すると明示再開 |
| REFUEL / CHARGE_WEAPON | 対応するセルを1個だけ消費し1000充電 |
| ALLOW_GUEST / REVOKE_GUEST | 最大32名。搭乗者を安全退出できなければ即時剥奪しない |
| RECOVER_CABIN | 所有者による破壊船内の明示復旧訪問 |
| RESERVE_BAY / RELEASE_BAY | guest欄のドローンUUIDを予約/解除 |
| CARGO_PAGE | x/y/zのページとメニュー状態を検証して表示ページを変更 |

停止理由はFRIENDLY_IN_AREA、PROTECTED、FULL、NO_POWER、LEASE_LIMIT、NO_SAFE_EXITなど。水・溶岩を含む純粋な流体ブロックは、保護・所有権確認後に無ドロップで封止除去して採掘を継続する。水没した通常ブロックは元ブロックの自然物・保護判定を維持する。満杯・再読込・緊急停止から自動再起動しない。

## DronePort

`CarrierModule.installDronePorts((CarrierEntity, UUID) -> DronePort)` は同じ外界で読込済みの実機のみ返す。不在時はnull。未接続時に予約成立・サービス完了を偽装しない。

Identityはdrone/owner/HomeDock/Wing/元missionを保持し、一時サービス状態を元missionに混ぜない。Needsはflight/weapon/gun/missiles/repairの実不足量。receiveEnergy、receiveWeaponEnergy、receiveAutocannonRounds、receiveMissilesは実受入量0..offeredを返す。repairWithは1個のコピーで実修理できた場合だけtrue。物理材料の消費は母艦側が行う。アダプターは信頼されたサーバー実装であり、不正な受入量は契約違反。

予約成功直後に `port.approach(carrier.bayApproachPosition(slot), carrier.getDeltaMovement())` を通知する。初回tick前の命令でも一時役割を解除できる。他船の予約を整理する前に所有者と機体identityを検証する。HomeDock/Wingは変更しない。

4ベイはslot0=(-7.15,2.4,-5.2)、slot1=(7.15,2.4,-5.2)、slot2=(-3.6,2.4,7.8)、slot3=(3.6,2.4,7.8)。各3x3、機体高さ0.95の契約。最初に同じXZ・Y=-2の進入点へ整列し、その後最終ベイへ誘導する。

20tickごと、距離0.75以下・相対速度0.08以下のときだけ移送する。飛行/兵装各40以下、機関砲40発以下、ミサイル2発以下、修理材1個以下。120発マガジン/5発パックの余りはbay_stockに保存する。弾薬・修理材は専用枠優先、旧貨物も互換供給する。

release理由はCOMPLETE/EXPIRED/SHIP_UNAVAILABLE/IDENTITY_CHANGED/CANCELLED。実機が応答する間は母艦側でrenewし、外部renewは不要。再読込後の予約は自動再開せず失効する。`serviceAvailable(server, shipId, droneId)` がfalseなら機体側も古い役割を解除する。通常ドローンの物理離脱・任務復帰・船内所有者対応はCarrierDroneServiceAdapter側の担当。

## 採掘と保存の安全境界

1チャンクの不変Operationを上から岩盤方向へ処理する。開始上端は `floor(beamOriginY)-1` で、腹部近傍の樹冠を範囲外へ取り残さない。外装の属するチャンクと世代のチャンクを厳密一致させる。最大512候補/tick、32破壊/tick、2msの協調予算。20TPSで640破壊/秒は個数上限からの理論値であり、実測速度ではない。Mob取得は129件で打ち切り、128超なら停止する。他Mod処理を時間で強制中断する保証ではない。

同じ層の手前のブロックが射線に当たる場合、その実命中点を先に処理する。承認チャンク内・同層・未封鎖列のみ。元の対象を処理するまでcursorを進めない。岩盤に達した列は封鎖し、その下を掘らない。別層・別チャンク・封鎖列の遮蔽を貫通しない。

自然タグには主要石/土/鉱石、草花、自然原木・葉、砂/赤砂/砂利、雪氷などを明示登録する。加工木材や農地は既定許可に含めない。砂・赤砂・砂利以外のFallingBlock、設備BlockEntity、岩盤、保護タグ、永続フラグの葉は拒否する。流体だけで構成されるブロックは除去できるが、水没建築は元ブロック側の判定を回避できない。任意の特殊Mod地形や岩盤による遮蔽まで無条件に突破する機能ではない。

mayBuild/mayInteract、world border、設置記録、Guard、BEFOREイベントをすべて確認する。新しいプレイヤーBlockItem使用付近を保守的に記録し、失敗した設置でも保護が残る場合がある。導入前の天然石/原木などの設置履歴はMinecraftにないため、古い建築物や未接続のMod移設を完全判別する保証はない。UNKNOWNは明示された範囲承認と天然タグを根拠に扱う。外部保護は `CarrierProtection.install(Guard)`、自動設置・移動の記録は `recordPlayerBuild(ServerLevel, BlockPos)` を接続する。

通常のBlock.getDropsと標準ネザライトピッケルでドロップを確定し、全量収納計画が成立してから除去する。エンチャント装着機能はない。満杯時は未破壊位置・BlockState・予定ドロップを保存する。UPDATE_KNOWN_SHAPEと自動ドロップ抑止を使い、二段植物・葉・砂の連鎖破壊をこの処理から発生させない。通常の経験値は現場に出す。

保護/lootフックの後と次の候補の前にmode・世代・進捗・所有者を再確認し、変更されたブロック/設備/新規設置記録を旧状態のまま削除しない。AFTERフック等でのSTOPを同じtickの後続破壊が無視しない。処理例外はEMERGENCY停止してサーバーログへ残す。

常用航行と通常採掘は核融合/アーク炉の基礎出力で維持する。飛行蓄電は急加速に使い、500未満では0.12 block/tickの低出力航行を継続しながら毎tick 5回復する。作業蓄電は毎tick 4回復し、採掘レーザー起動時に200、炉直結8ブロック/tickを超えて最大32ブロック/tickへ上げる各ブロックに10を使う。蓄電枯渇で世代やカーソルを破棄せず、通常採掘へ自動的に戻る。98,304ブロックの理論上限でも、起動回復を含む持続枠の上限は11分以内である。採掘柱内に生きた動物・プレイヤー・友軍がいれば停止する。所有者が船内でも、保護イベントは実際の外界次元で判定する。

採掘範囲は固定16x16x64ではない。母艦直下チャンクの16x16を、機体下面直下からディメンションの採掘可能最下層まで上から順に処理する。空気は走査のみ、岩盤列は封止して残し、ドロップは既存の有限貨物へトランザクション投入する。貨物満杯、保護ブロック、プレイヤー建築、友軍侵入では従来どおり停止する。

### 対Mob射撃

明示preview/ACTIVATEの1サイクルは充填60tick・射撃480tick・冷却60tick、20TPSで合計30秒。充填は毎tick2兵装energy、実成功hitは40、命中失敗ではhit分を消費しない。射撃中は10tick周期・1対象20damage、最大16対象。同じ対象に対する理論上限は40HP/秒で、通常の無敵時間・防具・耐性・他の攻撃と共存するため実ダメージは減る。独自に無敵時間を解除しない。

承認chunk中心から水平半径96、worldMin以上・腹部照射口より下を敵探索円柱とする。円柱全体にダメージを与えない。UUID整列した敵を巡回cursorで最大16体ずつ選び、17体目以降も順番が回る。上向き照射は不可。検索が129体に達した場合はTARGET_LIMITで停止し、探索の無制限化を避ける。

各敵への射線が読込済みで、権限・敵性・生存・LOSを満たす場合だけ判定する。友軍/動物/プレイヤーのAABBをビーム最大半径1.15ぶん膨張し、射線に交差する場合はその射撃だけを抑止する。他の安全な敵へは継続する。探索queryにも全周2block余白を付け、境界の友軍を取りこぼさない。実成功hitのVec3だけを最大16本同期する。地形変更・爆発経路はない。

FIRE中は直近の成功pulseの対象UUIDを最大10tick保持し、各tickの生存・敵性・権限・loaded/LOS・新しい友軍の射線交差を再確認して連続照射位置を更新する。この更新自体では追加ダメージ・課金・新対象獲得をしない。死亡・遮蔽・友好化・電力喪失・STOPで消灯する。次のpulseは成功したUUIDだけに置き換える。各射線の友軍queryも129件で打ち切り、あふれた射線は通さない。

チャンクとSavedDataは別々に保存される。通常保存のcodec整合性は試験対象だが、電源断時のディスク原子性やexactly-once保証は提供しない。他Modがブロック変更コールバック内で貨物自体を同時変更するような再入処理まで、取引ジャーナルで隔離しているわけではない。

## 船内・退出・チャンク読込

1船1チャンク、内寸14x14・高さ11。外装50x26とは別空間。cabin番号は最大4096、破壊船の建築や収納を再利用しない。未spawnかつ未使用の割当だけは、配置失敗時に取り消す。

入口はlocal(2,65,2)、出口は(13,65,13)。両パッド3x3と頭上を保護する。入口混雑時は9候補から安全位置を選び、入室転送に成功してから訪問を確定する。転送後40tick、退出失敗/復帰失敗後10tickの抑制がある。間接的にパッドへ伸びたブロックは通常ドロップを伴って除去し、内容を黙って消さない。

所有者/招待者のみ自室の建築、収納、作業台、かまど、ベッドを使える。実PlacementContext、ベッドの隣接部分、ドア/大型植物の上部分も検証する。床や壁を支持面にすることは許可する。バケツ、コーラス、パール、着火、卵、ピストン、ディスペンサー、ドロッパー、TNT、危険な転送類を拒否する。他ModのBlockItem/使用アイテムは初期互換対象外。任意Modの直接ワールド変更を隔離する万能なサンドボックスではない。

成功した退出ではactive visitを消し、船UUID・帰還点は永続assignmentsへ保持する。再ログイン/船内ベッドでの復活に利用する。`currentShip(player)` は生存・権限・実際の船室内座標も検証する。船破壊時も船内・貨物を削除せず通常搭乗者の退出を試み、所有者の明示RECOVER_CABINでは復旧作業中の自動退出を抑える。

退出は後部乗艦帯周辺の採掘範囲外地表、保存帰還点、world spawnの順。安全候補が皆無なら保持してNO_SAFE_EXITを返し、虚空や窒息位置へ強制転送しない。帰還点またはworld spawnの既知の1チャンクを明示読込する場合がある。

オンライン所有者が自船内にいる間だけ、サーバー全体最大4船、各船は現在地と1chunk先の最大2ticketを保持する。半径3・期限40tick、loading/simulation/keep-dimension-active。最大8ticketは8chunkという意味ではない。FULL影響範囲の重複を無視した保守上限は8×7×7=392chunk。さらに生成依存haloがあり、26.2の `ChunkLevel.RADIUS_AROUND_FULL_CHUNK` をHとすると `8×(2×(3+H)+1)^2` がticket影響の保守上限で、`CarrierNavigation.generationInfluenceBound()` から取得できる。実際の同時読込数は重複・他のticket・非同期unload/キャッシュに左右され、この値をサーバー総読込数の上限と主張しない。

枠不足はLEASE_LIMIT、紛失Entityの復元待ちは200tickまで。所有者退出・死亡・切断・船破壊で解除する。目的地までの距離制限はなく、同次元world borderと船体高度を確認する。遠方目的地は直接ロードせず、現在地と直近前方だけを更新する。外界からの操作権限128以内は維持し、長距離継続には所有者の自船内滞在を使う。

速度上限0.5block/tick、移動したtickだけ飛行energyを1消費する。最終短距離stepも1消費し、停留中は移動燃料を消費しない。全船体の始点・終点を包むswept AABBを1block拡大し、全footprintの地表高さと8block離隔、衝突、読込、borderを判定する。障害物・燃料切れで目的地を保持してpause。未読込は最大100tick待ち、解消すれば待機中は継続、期限超過でpauseして前方ticketを解放する。pause後はMOVEによる明示再開が必要。

保存 `navigation_intent` は任意フィールド。STOP・退出・再読込でも表示用目的地を保持し、load時は必ずRELOAD/paused。移動の自動復帰はしない。`flightMetrics()` は全sweep判定の回数・合計ns・最大ns・地表照会数を保持する。実ゲームの512block以上の移動で測定し、未測定の性能を完了扱いしない。地表キャッシュで変更を見落とす最適化は入れない。

## 検証器と残る確認

CarrierRuntimeVerificationは完全一致の専用ワールド名、singleplayer、Creative、生存プレイヤー1人だけを許可する。通常メニューのPREVIEW→ACTIVATE→STOPを通し、18個の試験石以外は追加BEFORE保護で拒否する。これは少量採掘/収納の実試験であり、チャンク全高の完走を主張しない。

異常終了時には、外装Entityやfixtureタグが失われていても、記録された船UUIDと所有者に一致するSavedDataの稼働を先に停止する。その後で保護を解除する。削除・fixture cleanupには引き続きタグ、所有者、位置、状態の検証が必要。再起動永続試験はbootの変化を要求し、インメモリcodecだけを再起動の証拠としない。

旧prototypeの24試験では実Minecraft BlockGetter.clipによる同層遮蔽・岩盤走査を確認した。その後、V27包囲箱、天然混合地形、補給/旧保存互換、複数ブロック配置、乗艦マーカーの周期と失効、callback停止・世代交換、検証器の保存状態停止を回帰対象へ追加した。今回さらに98304座標の全高走査と16384個の実voxel clip除去、256列岩盤保全、30秒の正確なフェーズ長、17～128敵の公平巡回、友軍ビーム境界、1000block超の有界stepと全船体sweep、目的地保存とpause、ページ/命中同期codecを追加した。地形モデルの試験は実ServerLevel・loot・プレイヤー取出し・実測性能を代替しない。

今回の担当内ではGradle、ゲーム起動、インストールを実行しない。親側の全体buildと、実ゲームでの乗下船・死亡/再ログイン・4ベイの移動/離脱・満杯再開・保護Mod・液体停止・対Mob地形非破壊の確認が必要。新しい試験を追加したことをPASSと取り違えない。現段階は `release_ready=false`。
