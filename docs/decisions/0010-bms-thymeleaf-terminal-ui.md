# ADR-0010: BMS 画面を中立モデルから Thymeleaf、JavaScript、CSS で再現する

| 項目 | 内容 |
| --- | --- |
| 状態 | 採用 |
| 日付 | 2026-09-09 |
| 関連要件 | FR-162, FR-166, FR-167, NFR-034〜036 |
| 関連設計 | [設計 77](../design/77-spring-cics-db2.md) |

## 文脈

BMS マップを単純な JSON form に変換するだけでは、行・桁位置、protected field、autoskip、
初期 cursor、modified data tag、AID key 等を前提とする既存業務の操作感が失われる。一方、3270
データストリームと端末プロトコルをブラウザ内に再実装すると、Web UI の保守性、アクセシビリティ、
セキュリティと将来のフレームワーク交換を損なう。

また、JavaScript の検査だけで入力可否や field length を保証すると、HTTP 要求の改変によって
protected field や BMS input map を偽装できる。

## 決定

BMS parser が作るフレームワーク非依存の `BmsScreenModel` を唯一の意味論上の入力とし、Spring Boot 4
用の任意 adapter `cobol-spring-boot-4-bms-thymeleaf` を設ける。この adapter は共通 Thymeleaf template
から semantic HTML をサーバレンダリングし、外部 JavaScript module と CSS で 3270 に近い表示・操作を
提供する。map ごとの Thymeleaf ファイルを生成せず、必要な場合だけ利用者が mapset / map 単位の
fragment または theme を上書きできる。

実装方式は通常の HTML input だけで成立すると仮定しない。代表的な難画面で native input overlay、
per-cell DOM + 隠れた native input、visual canvas + semantic form の hybrid を time-boxed prototype し、
cell cursor、overwrite、field wrap、DBCS / IME、screen reader、性能を比較する。semantic form と入力の
正本は DOM に残しつつ、合格した場合は canvas を visual layer に限定して使用できる。

CSS は BMS の画面サイズを固定セル grid として表し、responsive reflow をしない。位置、長さ、色、
輝度、非表示、反転、下線等は列挙済み class / CSS custom property へ安全に写像する。BMS 由来文字列を
生の style、class、HTML として挿入しない。

JavaScript は terminal input state machine として、cursor 移動、protected / unprotected、ASKIP、NUM、
IC、FSET / MDT、field length、Tab / Backtab、Insert / Overwrite、Enter、Clear、PA / PF key、keyboard
lock、二重送信防止を扱う。logical field と DOM fragment を分離し、画面端で折り返す field も一つの
BMS field として送信する。

送信値は field ID、値、MDT、AID、cursor position、map version、single-use screen nonce、
conversation version とする。疑似会話には HTML ではなく、map ID / 版、terminal profile、動的属性、
cursor、MDT の中立 `BmsScreenSnapshot` を保存する。サーバは `BmsInputDecoder` で protected field、
桁数、NUM、文字コード、byte length、cursor、MDT を再検証し、BMS input map と EIB を構築する。
JavaScript は操作補助であり、信頼境界にはしない。

Thymeleaf は値を escape する通常の `th:text` / `th:value` を使い、動的な非 escape HTML と inline
script を使わない。JavaScript は version 付き静的 resource とし、CSP と CSRF を維持する。

視覚互換モードに加えて、on-screen PF / PA key、ARIA label、focus 表示、高コントラスト、reduced motion
を提供する。アクセシビリティ対応で BMS の業務データや AID が変わらないよう、同じ input state machine
へ合流させる。

## 影響

- 元画面に近い配置と keyboard workflow を保ちつつ、Spring MVC / Thymeleaf の標準 view として運用できる。
- BMS 意味論は renderer から独立し、将来別 template engine や desktop UI へ交換できる。
- browser、font、IME の差があるため、pixel 単位の完全同一ではなく cell placement と操作意味論を
  適合性の中心とする。
- JavaScript state machine とサーバ validator の規則を共有仕様・共通 test vector で同期する必要がある。
- 画面端をまたぐ field、DBCS、combining character は cell 数と encoded byte 数を別々に検証する必要がある。

## 却下した案

### BMS map ごとに専用 Thymeleaf template を生成する

レイアウト修正には便利だが、生成物の手修正、属性実装のばらつき、map 数に比例した移行作業を生む。
共通 renderer と任意 override を採る。

### Canvas だけで画面と入力を実装する

座標再現は容易でも、form、focus、IME、screen reader、テスト、CSS theme の利点を失うため採用しない。

### JavaScript が変更済み field をそのまま COBOL storage へ反映する

protected field 改変や byte length 違反を防げない。サーバ側で同じ BMS 規則を必ず再検証する。

### 画面幅に合わせて field を並べ替える responsive form

既存利用者の位置記憶と BMS 座標を壊す。小画面では縮小・スクロールし、配置を変えない。
