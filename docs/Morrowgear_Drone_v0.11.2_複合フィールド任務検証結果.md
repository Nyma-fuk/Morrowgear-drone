# Morrowgear Drone v0.11.2 複合フィールド任務検証結果

## 変更概要

- Engineer単独時の調査範囲を局所範囲へ限定した。
- Scout参加時はScoutのみが任務領域全体を走査し、Engineerはデータリンク完了後に全対象を処理する。
- Scout、Cargo、GUARDへ作業フェーズに応じた旋回・随伴・護衛状態を追加した。
- Dock着艦機へフィールド任務を直接割り当てた際、着艦状態が残ってローターが停止する不具合を修正した。

## 役割遷移

1. Scoutは`SCOUT / AREA SURVEY`で先行走査する。
2. Engineerは`ENGINEER / DATA LINK`で走査完了を待つ。
3. Scoutは`SCOUT / WORK ORBIT`へ移り、Engineerは実作業を行う。
4. Cargoは回収物が出るまで`CARGO / HOLDING ORBIT`を維持する。
5. GUARDは`GUARD / WORK ESCORT`として作業隊を護衛する。
6. Engineer完了後、ScoutとGUARDはCargo随伴へ移る。
7. Cargoの回収・搬送完了後、全役割が`COMPLETE`へ移る。

## 検証結果

- Java自動試験: 1,390 / 1,390 PASS
- Minecraft Java 26.2実機ランタイム試験: 33 / 33 PASS
- 鉱石、範囲除去、森林伐採・植林の複合任務: PASS
- Cargoコンテナ調停・アイテム保存: PASS
- GUARDの複合任務参加と状態遷移: PASS
- Dock発艦時の着艦フラグ解除: PASS
- Dock帰投、屋根・閉塞進入、大規模障害物、作業中迂回: PASS

ゲーム速度は変更せず、Minecraftの通常tickで検証した。
