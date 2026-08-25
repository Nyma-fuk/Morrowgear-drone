# Morrowgear 複数戦域・侵略Mod互換仕様

更新日: 2026-08-24  
対象: Morrowgear Drone Java Mod

## 1. 目的

単一の強敵だけでなく、数十体以上の敵集団、複数方向からの同時侵攻、未知のMod Entityを検出し、既存のWing、攻撃機動、充電交代、任務復帰を壊さず迎撃する。

## 2. 制御階層

```text
Threat Contact Registry
  -> Threat Cluster
  -> Combat Theater Coordinator
  -> Temporary Engagement
  -> 既存のGun / Laser / Missile制御
```

- `Wing`: 最大8機の恒久的な所属。戦闘割当で変更しない。
- `Threat Cluster`: 同じ戦術的問題として扱う敵群。
- `Combat Theater`: 同時進行する全戦域を統合した作戦状況。
- `Temporary Engagement`: 1つの敵を担当する一時攻撃群。最大8機。

## 3. 敵群の判定

次の初期条件で接触を連結する。

| 項目 | 値 |
|---|---:|
| 水平距離 | 12ブロック以内 |
| 高度差 | 6ブロック以内 |
| 移動方向差 | 75度以内 |
| プレイヤー到達時間差 | 80tick以内 |

停止中または極低速の敵は、方向と到達時間が不確定なため距離と高度で判定する。戦闘開始済みの機体は現在目標への割当を保持し、群の再分類だけで攻撃パスを中断しない。

## 4. 戦力配分

要求戦力には次を使用する。

- 敵数
- 敵の現在HP
- 最大HP、攻撃力、防御力、移動速度
- プレイヤーとの距離と直接攻撃状態
- 特殊危険度
- 戦闘中のドローン損傷
- 飛行電力・武器電力の消費
- 破壊されたドローン数
- 使用可能なSecurity機数

1目標の上限は8機のまま維持する。多数の低～中脅威には複数目標へ戦力を分散し、高耐久・高損耗目標には最大8機を集中する。

複数接触時は原則20%を予備戦力として保持する。プレイヤーへの直接危険が高い場合は予備率を縮小する。充電、Recovery、武器使用不能状態の機体は配分から除外する。

## 5. 安定性

- CombatまたはIntercept開始後は、その目標が生存している限り割当を固定する。
- 作戦計画は10tick単位で更新する。
- 既存のGun Run、Laser Charge、Laser Fire、Missile Approachを再割当で中断しない。
- 戦闘割当はWing ID、Mission ID、Dock割当を変更しない。
- 目標消失後は既存の`REJOIN`を経由して元任務へ復帰する。
- 充電交代は目標単位の必要戦力を参照し、充足済みなら復帰機で交代要員を押し出さない。

## 6. 侵略Mod互換

### 自動対応

標準の`LivingEntity`、標準属性、プレイヤーをTargetにするMob、プレイヤーを実際に攻撃したEntityは自動判定する。標準属性が弱くても、実戦中の損耗テレメトリーによって増援数を引き上げる。

### Entity Typeタグ

他ModのEntityをデータパックから補正できる。

- `morrowgear_drone:compat_hostile`: 常に敵性として扱う。
- `morrowgear_drone:compat_friendly`: 攻撃対象から除外する。
- `morrowgear_drone:compat_high_value`: 特殊危険度を加算する。

例:

```json
{
  "replace": false,
  "values": [
    "example_invasion:siege_golem",
    "example_invasion:raider_commander"
  ]
}
```

独自の拠点攻撃AI、標準属性を使用しない攻撃、無敵フェーズなどは、タグと実戦テレメトリーを併用する。将来の個別Adapterはこのタグ判定より前に友好・敵対と特殊能力を供給する。

## 7. HUD

個別の`ENG-xx`表示とは別に、戦闘中だけ画面上部中央へ作戦概要を表示する。

```text
OPERATION AEGIS / ACTIVE / 3 FRONTS / 50 HOSTILES /
3W 19U ENGAGED / 5 RESERVE / 24 SEC

FRONT-01 22H/8U | FRONT-02 17H/7U | FRONT-03 11H/4U
```

- `FRONTS`: 現在の敵群数
- `HOSTILES`: 共有センサーネットワークが保持する敵数
- `W/U ENGAGED`: 参加Wing数とSecurity機数
- `RESERVE`: 使用可能だが未投入の予備機数
- `SEC`: 所有するSecurity機総数
- `H/U`: 各戦域の敵数と投入機数
- `!`: プレイヤーへの危険が高い戦域

戦域が5件以上ある場合、優先度上位4件を表示し、残りは`+N FRONTS`へ集約する。

## 8. 検証条件

- 従来の全自動試験を継続してPASSすること。
- ゾンビ50体と24機で複数目標へ分散し、1目標8機を超えないこと。
- 分離した接近経路を別戦域として分類すること。
- 高耐久・高損耗目標へ最大8機を集中できること。
- Combat中の目標を再配分で変更しないこと。
- 低電力、充電中、Recovery機を作戦投入しないこと。
- 同一Entityの複数Scout報告を1接触へ統合すること。
