# ADR-0011: 手続き型 CALL と OO COBOL の Java 連携を別の契約として設計する

| 項目 | 内容 |
| --- | --- |
| 状態 | 採用 |
| 日付 | 2026-09-09 |
| 関連要件 | FR-086, FR-170〜172 |
| 関連設計 | [設計 75](../design/75-java-interop.md), [敵対的レビュー AR-01](../reviews/2026-09-09-interop-adversarial-review.md#ar-01) |

## 文脈

設計 75 の `ProgramCatalog` / `JavaCallable` は、既存 COBOL の通常の `CALL` を登録済み Java 実装へ
接続する手続き型 SPI である。これは HLASM サブルーチンの Java 置換には適するが、FR-171 が要求する
`INVOKE`、`CLASS-ID`、`METHOD-ID`、`REPOSITORY`、object reference、overload resolution の意味論を
提供しない。両者を同じ名前解決と引数 ABI で扱うと、Java object の寿命、null、例外、継承、static / instance
method の区別が曖昧になる。

## 決定

登録 alias、IBM 非 OO Java static call、OO 連携を別の公開契約にする。

- `ProgramCatalog` / `JavaCallable` は HLASM 等を置換する登録済み COBOL program alias、`CALL USING`、
  COBOL storage を扱う。この経路では Java exception を通常の program execution failure とする。
- IBM 非 OO 形式の `CALL 'Java.java-class-name.java-static-method-name'` は別の
  `JavaStaticMethodCatalog`、IBM 型変換表、RETURNING、`ON EXCEPTION`、Java exception special register
  の契約を持つ。任意 reflection ではなく、翻訳時または起動時に許可した descriptor だけを実行する。
- OO 連携は別の `JavaClassCatalog` / `JavaMethodPort` を持ち、COBOL class 名から明示登録した Java class
  descriptor だけを解決する。要求値から任意の完全修飾 class 名や method 名を反射実行しない。
- Java object は生の JVM reference や identity hash を COBOL storage へ格納せず、`CobolSession` が所有する
  generation 付き `JavaObjectHandle` で参照する。session close、`CANCEL` 相当の所有境界、classloader
  revision の変更で handle を無効化する。
- method descriptor は static / instance、引数順、BY VALUE / REFERENCE、nullable、return type、例外規則、
  overload key を持つ。解決は翻訳時を基本とし、runtime では descriptor hash を照合する。
- IBM 互換のデータ型対応、Java exception と `IGY-JAVAIOP-CALL-EXCEPTION` 相当、`CLASS-ID` / `METHOD-ID`
  で定義した COBOL method を Java から呼ぶ経路は、別の詳細設計と実機適合性試験を完了するまで未対応とする。

設計 75 の完了は登録 alias の連携だけを意味し、IBM 非 OO static call または FR-171 の完了とはみなさない。

## 影響

- HLASM 置換に不要な object model を `cobol-runtime` の program ABI へ混ぜずに済む。
- FR-171 には追加の parser、semantic model、code generation、object lifecycle、型変換が必要になる。
- Java 連携 API が二系統になるため、文書と診断で `CALL` と `INVOKE` を明確に区別する必要がある。

## 却下した案

### `JavaCallable` に method 名と `Object...` を追加する

COBOL storage ABI と Java object ABI が混在し、静的検査と寿命管理を失うため採用しない。

### 完全修飾 class 名をそのまま reflection で実行する

overload 解決が実行環境依存になり、許可していないコードの呼び出しと classloader leak を招くため採用しない。
