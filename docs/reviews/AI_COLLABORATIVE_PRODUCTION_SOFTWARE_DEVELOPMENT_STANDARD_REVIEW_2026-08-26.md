# AI協働型・量産ソフトウェア開発標準プロセス 5回独立レビュー記録

## 1. 対象

- 対象文書: `docs/AI_COLLABORATIVE_PRODUCTION_SOFTWARE_DEVELOPMENT_STANDARD.md`
- 実施日: 2026-08-26
- 対象状態: REVIEWED DRAFT
- 目的: AIが推測で仕様を補わず、量産開発でバグを減らすための実行可能な標準として使えるかを確認する

本レビューは文章を5回読み直した記録ではない。異なる欠陥class、判定基準、期待成果を持つ5つの独立passとして実施した。

## 2. なぜ5回なのか

レビュー回数は、多ければ安全になるという経験則では決めていない。本書の主な失敗原因を次の5classに分解し、各classに一つの独立passを割り当てた。

1. 規範の曖昧さにより、AIが未承認判断を補完する。
2. 状態・データ・並行処理の設計不足により、局所的に正しい実装が全体不整合を生む。
3. 試験・証拠・release・運用統制の不足により、未検証をPASSまたは出荷可能と誤認する。
4. 抽象的な規則が実際の依頼へ適用できず、AIの進行・停止判断が揺れる。
5. 長文の構成、参照、文書状態が不明瞭で、正しい規則へ到達できない。

同じ観点を反復するより、failure modeが独立した観点を一度ずつ深く確認する方が重複を減らし、未確認領域を説明できる。5回で十分とする終了条件は次のとおりとした。

- 未解決のCriticalまたはMajor findingがない。
- MUST、MUST NOT、停止条件、Evidence LevelをAIが観測可能な条件へ展開できる。
- 設計、検証、運用の各契約にowner、入力、結果、失敗時挙動、証拠がある。
- 4種類の机上例で、進行、停止、再分類、必要承認を一意に説明できる。
- 見出し、code fence、local link、参照先に機械的な不整合がない。
- 最終passで、新しい重大な欠陥classが検出されず、指摘が文書管理と可読性へ収束する。

したがって「5」は永久に十分な固定値ではない。今回定義した5classと終了条件に対して十分である。後述の再レビュー条件が発生すれば、回数を追加または該当passを再実施する。

## 3. 判定尺度

| Level | 文書上の欠陥 |
|---|---|
| Critical | AIが未承認の外部挙動、data、権限、安全性を実装できる、または未検証をCOMPLETEと判定できる |
| Major | バグを防ぐ契約、owner、失敗処理、証拠が不足し、実装者ごとに異なる判断になる |
| Minor | 正しい規則は存在するが、発見性、参照、表現、保守性が低い |

## 4. Pass 1: 規範・曖昧さ・推測余地

**判定質問**: AIが数値、条件、Role、証拠のない語を、自分の常識で埋められないか。

主なfinding:

- 「高risk」「重大」「安全側」「長時間」「最大負荷」「十分な証拠」の境界が一部未定義だった。
- Evidence Levelの適用条件、Security試験、mutation testの発動条件が裁量的だった。
- 固定件数のscenario作成が、要求・故障modeの漏れを隠す可能性があった。

対応:

- R0～R3、S0～S3、Safe state、長時間試験、最大負荷を判定可能な条件へ展開した。
- R2/R3のE3～E6、Security試験、欠陥注入の発動条件を明示した。
- 固定件数を廃止し、Critical要求とS2/S3故障mode台帳から試験を導出する規則へ変更した。

結果: Critical/Major findingは解消。

## 5. Pass 2: 状態・データ・並行処理・互換性

**判定質問**: 複数処理、途中停止、version共存があっても、正本と最終決定者を一意に説明できるか。

主なfinding:

- transaction境界、複数writer、eventと状態更新の原子性がtemplateへ落ちていなかった。
- lost update、event順序、lease解放、遅延callbackの扱いが製品判断として明示されていなかった。
- rollback不能なschema変更と旧新reader/writer共存の条件が不足していた。

対応:

- 整合性境界、transaction、outbox相当、補償、削除・tombstone規則を追加した。
- 競合制御、version/sequence、event順序、resource・取消・停止の寿命を契約化した。
- expand-contract、migration再開、rollback/roll-forward、ID寿命を追加した。
- data/transaction契約と並行処理/resource契約のtemplateを追加した。

結果: Critical/Major findingは解消。

## 6. Pass 3: 試験・証拠・release・運用

**判定質問**: テスト件数や再実行PASSに頼らず、検証済みartifactを安全に配布し、失敗時に復旧できるか。

主なfinding:

- flaky testを計測する規則はあったが、Gateでの扱いと隔離期限が未定義だった。
- 変更影響から必須試験を導く手続と、test dataのSecurity/Privacy統制が不足していた。
- CIで検証したartifactと配布artifactの同一性、保護branch、rollback不能時のroll-forward条件が不足していた。
- telemetryの個人情報、sampling、cardinality、alert ownerが未定義だった。

対応:

- flakyの定義、初回FAIL保全、再実行PASSの非上書き、隔離条件、release停止条件を追加した。
- 要求・risk・故障mode・test IDの影響表とtest data統制を追加した。
- immutable artifact昇格、必須CI check、段階公開、復旧演習を追加した。
- telemetryとalertのowner、保存、sampling、cardinality、初動を追加した。

結果: Critical/Major findingは解消。

## 7. Pass 4: AI机上シミュレーション

**判定質問**: 実際の依頼を受けたAIが、実装開始、DECISION_PENDING、停止、Evidence要求を同じ規則から導けるか。

実施scenario:

| Scenario | 期待判定 | 結果 |
|---|---|---|
| private関数を既存helperへ統合 | 条件付きR1、同値性検証 | 一意に判定可能 |
| 保存schemaへfield追加 | R2、Data owner決定と移行契約待ち | 一意に判定可能 |
| 認可条件変更 | R3、Security owner承認と独立証拠 | 一意に判定可能 |
| UI配置・通知音変更 | R2、実装前UX決定と実装後E5 | 一意に判定可能 |

発見したgap:

- 「既存契約への適合修正」と「新しい契約の追加」が明示的に分類されていなかった。
- 小規模開発で依頼者が複数Roleを兼ねる場合、会話上の同意をどこまでDECISIONにできるか不明瞭だった。

対応:

- 依頼直後の5分類と次actionを追加した。
- 選択対象、観測可能な挙動、trade-off、承認Roleを記録して初めてDECISIONになる規則を追加した。
- 4scenarioを本文へ基準例として追加した。

結果: 4scenarioすべてで進行・停止判断が一意。Critical/Major findingは解消。

## 8. Pass 5: 構成・参照・可読性・収束

**判定質問**: 1300行規模でも、AIと人間が正しい規則を発見し、草案と承認済み正本を区別できるか。

主なfinding:

- 文書のStatus、owner、承認前の扱いが先頭になかった。
- 用途別の読み順がなく、templateやGateへの到達に全文探索が必要だった。
- 文脈だけでは判定しにくい「重大」「重要な組合せ」等が一部残っていた。

対応:

- REVIEWED DRAFT、Document owner、承認条件、review記録を文書管理表へ追加した。
- AI実装、設計、review、release、導入の用途別navigationを追加した。
- 残存語をR3分類、承認済みSLI、要求・risk・故障mode台帳へ結び付けた。

機械確認:

- 見出し重複: 0
- code fence: 28本で開閉整合
- local link: 6件すべて存在
- 外部一次資料URL: 15件すべてHTTP 200を確認
- DORA現行指標: 公式ページで5指標と2026-01-05の更新日を再確認
- Markdown差分検査: whitespace errorなし
- 最終passで新規のCritical/Major欠陥class: 0

結果: Minor findingを解消し、レビューは収束。

## 9. 残存事項

- 本文のStatusはREVIEWED DRAFTであり、組織の正式標準にするにはDocument owner、適用範囲、承認者、承認日、次回見直し条件が必要である。
- 医療、車載、金融、個人情報等のR3製品では、本書だけで適合を主張できない。適用法、規格、契約、独立検証をPhase 0で追加する。
- 外部一次資料は本レビュー時点の参照先である。規格versionまたは組織方針が変わった場合は12、13節を再確認する。

## 10. 再レビューを起動する条件

次のいずれかが起きた場合、固定日を待たず該当passを再実施する。

- MUST、MUST NOT、risk分類、Severity、Evidence Level、Gateを変更する。
- 新しいdata store、message基盤、並行処理方式、AI model、外部公開契約を導入する。
- S2/S3障害、Security/Privacy incident、移行失敗、rollback失敗が発生する。
- AIが本文を参照しても誤った進行・停止判断をした事例が見つかる。
- 適用法、規格、一次資料、対象domainが変わる。
- 文書が大きくなり、local link、見出し、template、Context Packageとの不整合が生じる。

各再レビューは「何回読むか」ではなく、発生したfailure mode、判定質問、終了条件を先に定める。新しい独立欠陥classが見つかった場合は6回目以降を追加する。
