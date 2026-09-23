# V24提案の確認記録

**母艦UIのみREJECTED。** `carrier-layout.png` / `carrier-compact-layout.png`は提示後に却下され、元の画像を保存する。[却下理由](CARRIER_REJECTED.md)と[改訂V25](../carrier-v25/SPEC_JA.md)を参照。以下の成功件数は過去の静的確認であり、視覚承認を意味しない。小型機controller・Dock・HUDの提案は変更しない。

2026-09-22。ユーザー視覚承認待ち。**runtimeへの実装完了報告ではない。**

## 実施済み

| 確認 | 結果 | 範囲 |
| --- | --- | --- |
| Morrowgear/UI/日本語仕様/Browserの手順確認 | 実施 | 承認境界、既存素材の継承、実画面とロジックの分離 |
| 既存HMI保護テスト | 8件成功 | `node --test tools/test_hmi_usability.cjs`。既存Javaの構文、文字幅、HUD幾何、対象集合、確認失効、Wing上限など。ゲームビルドではない |
| 提案の純粋Policy・静的制約テスト | 当担当で選択8件成功 | 下記の先行確認。全16件の結果は次行の親実行を参照 |
| 提案全件 + 既存HMIテスト | 親実行で24件成功、0失敗、0スキップ | 2026-09-22に親から結果を受領。提案16件（以前未実行だった模型8件を含む）+既存8件。純粋・静的・モデル試験であり実画面試験ではない |
| 提案JavaScriptの構文確認 | 成功 | `node --check docs/design/operations-ui-v24/app.js`。実行・描画なし |
| 独立PNG作成と目視 | 5枚確認 | 作戦1280×820、Dock1280×820、HUD1280×800、母艦1280×1020、小画面母艦1280×896。図自体の文字・区画・素材を確認 |
| 母艦の独立座標テスト | 11件成功 | 90枠のID・各行座標・境界・非重複、固定STOPと別の取消、小画面高さ不足、4予約上限、図の文字倍率。Minecraft・HTML・ブラウザーを実行しない |
| 親へのPNG共有 | 実施 | 作戦/Dock/HUDは親からユーザーへ提示済みとの連絡を受領。母艦2枚は親へ共有済み、モデル案と合わせた提示待ち。承認は未受領 |
| 実行時ファイル編集 | なし | UI担当の変更は`docs/design/operations-ui-v24/`のみ |

純粋Policy・静的制約だけを実行したコマンド:

```powershell
node --test --test-name-pattern='five primary|selection summary|wing capacity|target mode|empty, disconnected|pagination|one prioritized|palette' docs/design/operations-ui-v24/proposal.test.cjs
```

対象は5主ナビと旧機能の包含、完全/部分Wingと非表示対象、8機上限、対象スナップショット、無権限/通信断/空選択、100機を含む頁境界、単一通知の安定性と完了OFF、HmiArtパレット・固定文字寸法。

親が実行し、24件成功・0スキップ・0失敗と報告したコマンド:

```powershell
node --test docs/design/operations-ui-v24/proposal.test.cjs tools/test_hmi_usability.cjs
```

`proposal.test.cjs`の残り8件を「未実行」としていた先行記録は、この親報告により更新した。追加分はHTML文字列を生成する操作模型などの試験であり、ブラウザー画面の描画・入力・文字幅検証には数えない。当担当によるブラウザー再試行、HTMLの別経路での閲覧・レンダリングは行っていない。既存テストの削除、期待値緩和、PASS表示の上書きも行っていない。

親による`OperationLocalizationTest`と通信の26文字列の言語ファイル追加も受領済み。この24件にそのJavaテストの実行結果は含めない。当担当は言語ファイルを編集していない。

母艦の独立座標テストは、既存のPillow付きPython環境で実行した。11件成功、0失敗。上記24件とは別の実行結果であり、実Menuの挙動試験ではない。

```powershell
& 'C:/Users/fukud/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' docs/design/operations-ui-v24/carrier_layout_test.py
```

## ブラウザーの安全拒否

Browser skillに従ってCodex In-app Browserへ接続。1280×720の確認用viewportを設定し、次の遷移を1回だけ試みた。

```javascript
await tab.goto('file:///C:/Users/fukud/Documents/Codex/2026-08-13/referenced-chatgpt-conversation-this-is-an/morrowgear-drone-java/docs/design/operations-ui-v24/index.html')
```

ツールが返した全文:

```text
Browser Use rejected this action due to browser security policy. Reason: The browser URL policy blocks this action. Browser use cannot visit the requested page because its URL is blocked by the Browser use URL policy. The agent must not attempt to achieve the same outcome via workaround, indirect execution, raw CDP or browser commands, alternate browser surfaces, or policy circumvention. Proceed only with a materially safer alternative that does not require this blocked browser action; if none exists, stop and request user input.
```

URL形式非対応とは扱わず、明示的な安全拒否として停止した。別URL、localhostサーバー、別ブラウザー、CDP、別レンダラーを試していない。使用しなかったタブを閉じ、viewport上書きを解除した。

## PNGの意味

ユーザーが代案として明示的に許可した独立設計図。既存のHmiArt相当パレット、機体・機器・記号PNG、V23地形と、自分で定義した座標からPillowで生成した。生成処理はHTML/CSS/JavaScriptを読込・実行しない。依存の新規インストールなし。

全図に「レイアウト設計図・実画面未検証」を明記。Dockは実装時の18pxピッチを拡大した情報配置例、HUD背景は第一人称のゲーム画面ではない。PNGをブラウザーのスクリーンショット、Minecraft描画成功、3D投影成功、クリック成功、100機負荷成功として扱わない。

母艦の2図も独立した座標定義から生成した。`CarrierMenu`の54貨物+36所持品、18pxピッチ、y140の所持品、y198のホットバーを維持する。詳細は[母艦UI仕様](CARRIER_UI_JA.md)を参照。320×240は寸法案であり、320×180には固定90枠が収まらない制約を明記した。母艦モデル案と合わせた提示・ユーザー承認は親が取り扱う。

## 未実施・次段階

- ユーザーによる視覚承認。
- HTMLのブラウザー表示・実際のクリック・スクロール・小画面・キーボードQA。
- Minecraft実装、コンパイル、Dock実63Slot・母艦実90Slot確認、既存ワールド検証、Supply/communications/母艦の実結線。
- 320×180/320×240を含む解像度、GUI倍率ごとの原寸文字幅・重なり・操作領域。
- HUDの視点移動・遮蔽・ピン・通知、100機時のフレーム時間、キャッシュ維持。
- 独立Narratorの試聴、音量、割込、字幕との分離。UI提案は発声を実行しない。

承認後も上記をロジック試験から推定してPASSにしない。Minecraftを起動してよいかは親の指示に従う。
