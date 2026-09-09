# ADR-0007: CICS / Db2 の中核をフレームワーク非依存ポートとして分離する

| 項目 | 内容 |
| --- | --- |
| 状態 | 採用 |
| 日付 | 2026-09-09 |
| 関連要件 | FR-150〜156, FR-160〜167, NFR-032, NFR-034〜036 |
| 関連設計 | [設計 75](../design/75-java-interop.md), [設計 77](../design/77-spring-cics-db2.md) |

## 文脈

CICS オンライン実行と Db2 アクセスは Spring Boot 4.1 の Web、Session、DataSource、
トランザクション、運用監視の機能を利用する。一方、生成した COBOL クラスが Spring、Servlet、
JDBC、コネクションプールの型を直接参照すると、Spring Boot の更新が生成コードの再翻訳を要求し、
別フレームワークへの移行も困難になる。

CICS の `LINK`、`XCTL`、`RETURN`、`SYNCPOINT` と SQL の `OPEN`、`FETCH`、`COMMIT` は、
HTTP や JDBC より長く維持すべき COBOL の意味論である。逆に、トランザクションの開始方法や
会話状態の保存先はホストフレームワークごとに変わる。

## 決定

次の依存方向を固定する。

```text
生成 COBOL / cobol-compiler ──▶ cobol-cics / cobol-db2 ──▶ cobol-runtime
                                      ▲
                                      │ 中立ポートを実装
                ┌─────────────────────┴────────────────────┐
cobol-spring-boot-4-autoconfigure    cobol-spring-boot-4-bms-thymeleaf
                ▲
cobol-spring-boot-4-starter
```

`cobol-cics` と `cobol-db2` は、CICS コマンド、EIB、BMS、SQL 計画、SQLCA、カーソル、
作業単位 (UOW) の中立モデルとポートを所有する。公開 API に `org.springframework.*`、
`jakarta.servlet.*`、`java.sql.*`、HikariCP、Thymeleaf、HTML / DOM 固有型を含めない。生成コードが参照するのも
この中立 API だけとする。

Spring Boot 4 系の実装は独立した `cobol-spring-boot-4-autoconfigure` に閉じ込め、利用者向け
依存関係を `cobol-spring-boot-4-starter` で提供する。Boot 4.1 を最初の基準とするが、成果物名は
minor 版ではなく Boot の major 版で分ける。次の major で非互換変更が必要なら別アダプタを追加し、
中立ポートは互換に保つ。

BMS browser UI は任意の `cobol-spring-boot-4-bms-thymeleaf` に置く。JSON-only 利用者へ template engine、
JavaScript、CSS を強制せず、別 UI framework は同じ `BmsScreenModel` から実装できるようにする。

自動構成は公開 API と条件付き Bean 登録だけを使い、全ポートに利用者定義 Bean を優先する
上書き点を設ける。Spring の構成プロパティは一度中立な設定値へ写像し、中核処理から
`Environment` を直接参照しない。

ポート契約テストをアダプタ非依存の test kit として提供する。同じ契約をインメモリ実装、
Spring Boot 4.1 実装、将来の別フレームワーク実装へ適用する。

ただし直接交換できる host は、同一 thread 上の同期 COBOL 実行と imperative transaction を提供するものに
限る。reactive / actor / remote worker は continuation、context propagation、cancellation の意味が異なるため、
port の別実装だけで対応可能とは主張せず、新しい task coordinator と ADR を要求する。

## 影響

- Spring Boot の機能は最大限利用できるが、Bean と中立モデル間の変換コードが必要になる。
- CICS / Db2 の意味論を単体テストでき、Spring の更新による影響をアダプタ層へ限定できる。
- Spring Boot 固有機能を中核へ即座に露出できない。必要な能力は中立な capability として定義する。
- モジュールが増えるが、将来の置換とバージョン共存の境界が明確になる。

## 却下した案

### 生成コードへ Spring Bean と `JdbcTemplate` を注入する

生成コードが Spring のライフサイクルとバージョンに固定され、COBOL の再翻訳なしに
ホストを交換できなくなるため採用しない。

### 全機能を `cobol-runtime` に置く

最小ランタイムが Web、JDBC、Session の依存を引き受け、バッチや組み込み用途にも不要な
依存が波及するため採用しない。

### Spring Boot の minor 版ごとに別成果物を作る

利用者の更新負荷が過大になる。4.1 系は互換性テストで吸収し、公開 API の非互換が生じる
major 境界でだけ成果物を分ける。
