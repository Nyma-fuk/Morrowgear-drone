# Morrowgear: Drone Command マルチプレイ導入手順

## 1. 対応環境

この配布物は、次の組み合わせ専用です。

| 項目 | 必須バージョン |
| --- | --- |
| Minecraft: Java Edition | 26.2 |
| Java | 25以上（64 bit） |
| Fabric Loader | 0.19.3以上 |
| Fabric API | 0.156.0+26.2 |
| Morrowgear: Drone Command | 0.20.5 |

Minecraft、Fabric API、Morrowgearのバージョンは、サーバーと参加者全員で一致させてください。Morrowgearはサーバーだけでなく、画面表示、機体描画、サウンドを担当する各参加者のクライアントにも必要です。

## 2. 配布ZIPの内容

- `SERVER/`: サーバー管理者が使う一式
- `CLIENT/mods/`: 各参加者が自分の`mods`フォルダーへ入れる2ファイル
- `DOCUMENTS/`: 本手順と運用・トラブル対応手順
- `SHA256SUMS.txt`: 配布ファイルが破損していないか確認するためのハッシュ
- `VERIFY_PACKAGE.ps1`: Windows用の簡易整合確認

## 3. サーバー管理者の手順

### 3.1 Java 25を確認する

PowerShellまたはコマンドプロンプトで次を実行します。

```text
java -version
```

先頭に`25`以上が表示されることを確認してください。Minecraft Launcherに内蔵されたJavaとは別に、専用サーバーから利用できるJavaが必要です。

### 3.2 サーバーを初期化する

1. ZIPを任意の新しいフォルダーへ展開します。
2. `SERVER`フォルダーを開きます。
3. Windowsでは`start-server.bat`を実行します。Linuxでは最初に`chmod +x start-server.sh`を実行し、その後`./start-server.sh`を実行します。
4. 初回は必要なMinecraftファイルを取得した後、EULA未同意のため停止します。
5. [Minecraft EULA](https://www.minecraft.net/eula)を確認します。
6. 同意する場合だけ、`SERVER/eula.txt`を`eula=true`へ変更します。
7. 起動ファイルをもう一度実行します。
8. `Done`が表示されたら起動完了です。

起動メモリは既定で最小2 GB、最大6 GBです。16機程度のドローンと母艦を使う想定では、サーバーPCに12 GB以上の物理メモリがある構成を推奨します。割当量を変える場合は`start-server.bat`または`start-server.sh`の`-Xms2G -Xmx6G`を変更してください。

### 3.3 参加者を許可する

既定ではホワイトリストが有効です。サーバーコンソールで、参加者の正確なMinecraftユーザー名を登録します。

```text
whitelist add PlayerName
whitelist list
```

管理コマンドを使う担当者だけ、必要に応じてOPへ登録します。

```text
op PlayerName
```

通常プレイだけを行う参加者へOPを付与する必要はありません。

### 3.4 接続先を伝える

- 同じ家庭内LAN: サーバーPCのローカルIPアドレスとポート`25565`
- インターネット経由: ルーターでTCP `25565`をサーバーPCへ転送し、Windows FirewallでもJavaの受信を許可
- レンタルサーバー: 管理画面でJava 25、Fabric 26.2、Loader 0.19.3を選び、`SERVER/mods`の2ファイルを配置

`online-mode=true`は変更しないでください。認証を無効化すると、第三者が別人のユーザー名を使って接続できる危険があります。

## 4. 参加者の手順

1. Minecraft 26.2用のFabric環境を用意します。
2. Fabric Loaderは0.19.3以上を選択します。
3. MinecraftとLauncherを終了します。
4. 使用するFabricプロファイルの`mods`フォルダーを開きます。
5. 古いMorrowgearや26.2以外のFabric APIがあれば、別フォルダーへ退避します。
6. `CLIENT/mods`内の2ファイルを`mods`フォルダーへコピーします。
7. FabricプロファイルでMinecraftを起動します。
8. `マルチプレイ`から、管理者に教えられたアドレスへ接続します。

クライアント側に必要なファイルは次の2つだけです。

```text
fabric-api-0.156.0+26.2.jar
morrowgear-drone-0.20.5.jar
```

## 5. マルチプレイ上の動作

- ドローンAI、戦闘、Dock、補給、任務、保存データはサーバー側が正とします。
- 機体モデル、HUD、戦術画面、サウンドは各参加者のクライアントで表示します。
- 原則としてドローンを配置・所有するプレイヤーが、その機体へ命令します。
- サーバー停止後も、機体、Dock、任務、母艦内部、積載物はワールドへ保存されます。
- 大量の機体を同時運用する場合は、サーバーの`simulation-distance`と割当メモリだけでなく、各参加者の描画距離も負荷へ影響します。
- Vanilla 26.2の既定では、参加者が0人になって60秒後にサーバーtickが一時停止します。その間はドローン任務も進みません。無人中も処理を継続したい運用だけ、`server.properties`へ`pause-when-empty-seconds=-1`を追加してください。無人運転ではCPU負荷とワールド変化が続く点に注意してください。

## 6. 安全な停止とバックアップ

サーバーのウィンドウを閉じる前に、必ずコンソールで次を実行します。

```text
save-all flush
stop
```

停止後、最低限次をバックアップしてください。

- `world/`
- `server.properties`
- `whitelist.json`
- `ops.json`
- `mods/`

Morrowgearを更新する場合は、サーバーと参加者全員のJARを同じ版へ交換してください。ワールドを更新版で開く前にバックアップを作成します。

## 7. よくある問題

### `Incompatible mod set`または接続時のバージョン不一致

サーバーとクライアントのMinecraft、Fabric Loader、Fabric API、Morrowgearの版を照合します。`mods`フォルダーにMorrowgearやFabric APIが複数入っていないことも確認してください。

### Morrowgearの機体は見えるがUIや音が出ない

参加者側の`mods`にMorrowgear本体が入っていないか、別のFabricプロファイルを起動しています。起動構成のゲームディレクトリと`mods`フォルダーを確認してください。

### サーバーが見つからない

サーバーコンソールに`Done`が出ているか、接続先IP、ポート`25565`、Windows Firewall、ルーターのポート転送、ホワイトリストを順に確認します。

### サーバーが重い

まずログ内の`Can't keep up`を確認します。機体数を減らす前に、Java 25の使用、6 GB程度の割当、SSD、`simulation-distance=10`以下を確認してください。問題調査時は`logs/latest.log`と`crash-reports`内の最新ファイルを保管します。

## 8. 参照先

- [Fabric公式サーバーダウンロード](https://fabricmc.net/use/server/)
- [Fabric公式プレイヤー・サーバーガイド](https://docs.fabricmc.net/players/)
- [Fabric API 0.156.0+26.2公式Maven](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.156.0%2B26.2/)
- [Minecraft EULA](https://www.minecraft.net/eula)

## 9. 配布範囲

このZIPは、指定メンバーによるマルチプレイ試験用です。リポジトリの`fabric.mod.json`と`LICENSE`でライセンス表記が一致していないため、公開配布や第三者への再配布を始める前に、権利者が正式なライセンスを確定してください。

