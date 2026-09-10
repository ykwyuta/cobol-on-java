# 設計文書 00: 全体アーキテクチャ

| 項目 | 内容 |
| --- | --- |
| 対応要件 | ARC-1〜ARC-9 |
| ステータス | P0-a 第 1 増分 実装済。V2 検証が稼働中 |

Java 連携とJUnitの第1増分は実装済みである。Db2とCICSは中立coreの第1増分へ着手し、
Spring adapter、BMSは設計済み・未実装である。
[敵対的設計レビュー](../reviews/2026-09-09-interop-adversarial-review.md)の P0 gate を満たすまでは
experimental とする。特に IBM 非 OO Java static call と OO COBOL / Java 連携、Db2 `WITH HOLD`、
SQLCA fidelity、BMS の「忠実」互換性は確定済みとは扱わない。

Db2 は通常 `SPRING_MANAGED` profile で Spring の connection / transaction 管理を使う。ただし
`WITH HOLD` 必須 task は [ADR-0012](../decisions/0012-db2-driver-managed-uow-for-required-hold-cursors.md)に従い、
task 全体を `DB2_DRIVER_MANAGED_HOLD` profile として Db2 adapter が専用 connection lease を管理する。

## モジュール構成

要件 ARC-4 (ランタイムをコンパイラから独立させる) と ARC-7 (ランタイム先行) に従い、
Maven のマルチモジュール構成とする。

| モジュール | 責務 | 状態 |
| --- | --- | --- |
| `cobol-runtime` | データ表現・10 進演算・編集移送・文字コード変換・データセットの意味論。**コード生成に一切依存しない** | 第 1 増分 実装済 ([設計 10](10-runtime-p0a.md))。データセットは[設計 80](80-file-io.md) |
| `cobol-oracle` | Hercules 用テストの生成 (`.tst`) と期待値の採取・照合 | 第 1 増分 実装済 ([設計 20](20-oracle.md))。**V2 検証が稼働中** |
| `cobol-compiler` | プリプロセッサ、構文解析、意味解析、ASM によるコード生成 | P0-b 着手。固定形式の読み取りと継続処理、初期`EXEC CICS`変換を実装済 ([設計 30](30-compiler-preprocessor.md), [設計 77](77-spring-cics-db2.md)) |
| `cobol-job` | 内部ジョブモデルと実行機構、記述形式のフロントエンド | 内部モデルと宣言的形式を実装済 ([設計 90](90-job.md)) |
| `cobol-verify` | 外の基準で測る。NIST CCVS85 と OSS コーパスを処理系へ流し、合格率と未対応構文を数える | 第 1 増分 実装済 ([設計 25](25-verification.md))。コーパスは同梱せず取得スクリプトで持ってくる |
| `cobol-junit` | JUnit 5 からの COBOL 実行、fixture、プログラム・SECTION の Mock / spy | program Mock、SECTION Mock/spy、class埋込みmetadata検査、単一deploy catalog読込み、制限付き直接SECTION実行を実装 ([設計 76](76-junit-testing.md)) |
| `cobol-cics` | CICS コマンド、EIB、BMS、疑似会話、資源ポートのフレームワーク非依存モデル | experimentalなTRANSID registry、入力上限、command/control、同一sessionのLINK / XCTL / RETURN実行、版・lease付き疑似会話storeを実装 ([設計 77](77-spring-cics-db2.md)) |
| `cobol-db2` | SQL 計画、ホスト変数、SQLCA、カーソル、UOW ポートのフレームワーク非依存モデル | experimentalなprofile固定、遅延UOW、WITH HOLD方針、SQLCA fidelity行列を実装 ([設計 77](77-spring-cics-db2.md)) |
| `cobol-spring-boot-4-autoconfigure` / `starter` | Spring Boot 4.1 の MVC、Session、JDBC、transaction、Actuator を中立ポートへ接続 | 設計済み、未実装 ([設計 77](77-spring-cics-db2.md)) |
| `cobol-spring-boot-4-bms-thymeleaf` | BMS 中立画面を Thymeleaf、terminal JavaScript、固定セル CSS で 3270 互換表示する任意 UI adapter | 設計済み、未実装 ([設計 77](77-spring-cics-db2.md)) |
| `cobol-cli` | コンパイラ・ジョブ実行のコマンドライン入口 | 各モジュールの `Main` が暫定の入口である |

Java と COBOL の双方向呼び出しは、`cobol-runtime` に置く共通の呼び出し境界を使う。
Java からはセッション API を介して翻訳済み COBOL を呼び、COBOL の `CALL` からは明示登録した
Java アダプタを COBOL プログラムと同じ名前空間で呼ぶ。詳細は
[設計 75](75-java-interop.md)を参照する。

COBOL の単体テストは、製品ランタイムを JUnit に依存させない独立した `cobol-junit` から
`CobolSession` を操作する。外部サブルーチンと明示的に `PERFORM` する SECTION をテスト単位で
差し替えられる。詳細は[設計 76](76-junit-testing.md)を参照する。

CICS / Db2 連携は `cobol-cics` と `cobol-db2` に中立ポートを置き、Spring Boot 4.1 固有の Web、
Session、DataSource、トランザクション、運用監視は major 版別アダプタへ閉じ込める。これにより
Spring Boot の更新と別フレームワークへの交換が、生成 COBOL の再翻訳へ波及しない。詳細は
[設計 77](77-spring-cics-db2.md)を参照する。
ホスト画面の Web 再現は任意の Thymeleaf adapter で行い、BMS の座標・属性・入力意味論は
`cobol-cics` の中立 `BmsScreenModel` に維持する。JavaScript は端末操作を補助するが、入力値は
同じ BMS 規則でサーバ側でも再検証する。

依存の向きは `cobol-compiler` → `cobol-runtime` / 中立subsystem APIと
`cobol-job` → `cobol-runtime` の一方向のみとする。`cobol-verify` は測る側なので `cobol-compiler` に依存するが、
<b>誰からも依存されない</b>葉である。`cobol-runtime` はコンパイラを知らず、`cobol-job` は翻訳系を知らない。
ジョブ実行が動かすのは<b>翻訳済みのクラス</b>であり、どう翻訳されたかは関わりがない。これは要件 ARC-7 の
「意味論のバグかコード生成のバグかを切り分けられること」を成立させるための構造的な制約であり、
`cobol-runtime` から他モジュールへの依存が生じた時点でこの性質は失われる。
`cobol-junit` はテスト側の葉として `cobol-compiler` と `cobol-runtime` に依存するが、製品モジュールは
`cobol-junit` および JUnit API に依存しない。
`cobol-cics` と `cobol-db2` は `cobol-runtime` だけへ依存し、コンパイラは生成コードの中立命令を
出すため該当subsystem APIへ依存する。Spring Boot アダプタがその外部portを実装する。
生成コードおよび中立モジュールは Spring、Servlet、JDBC、特定の接続プールの型へ依存しない。

## cobol-runtime のパッケージ構成

すべて `dev.cobolonjava.runtime` 配下。

| パッケージ | 責務 | 対応要件 |
| --- | --- | --- |
| `storage` | バイト列と、その上のオフセット・長さビュー。COBOL のメモリモデルの土台 | FR-020, FR-021, FR-026, ARC-2 |
| `codepage` | EBCDIC / ASCII の符号化とゾーンニブル、照合順序 | FR-050, FR-051, FR-053 |
| `decimal` | 10 進値の表現と演算、丸めモード | FR-040, FR-042 |
| `data` | ゾーン10進・パック10進・2 進の外部表現との相互変換 | FR-031, FR-033, FR-045 |
| `picture` | PICTURE 句の解析と数値編集 (機械語の `ED` / `EDMK` に相当) | FR-030 |
| `verb` | `MOVE` などの動詞レベルの意味論 | FR-060 |

## 中心となる設計判断

### バイト列 + ビュー (FR-020, ARC-2)

COBOL の意味論は「連続したバイト領域上のビュー」に依存している。`REDEFINES`、集団項目の移送、
`OCCURS DEPENDING ON` によるオフセット変化、部分参照はいずれも、
データ項目を Java のオブジェクトへ素朴に写像すると表現できない。

したがって、すべてのデータ項目は `Storage` (バイト配列) 上の `DataView` (オフセット + 長さ) として
表現する。`REDEFINES` は同一範囲に対する 2 つ目の `DataView` を作るだけで実現され、
一方への書き込みが他方から即座に観測されるという要件 FR-021 が構造的に満たされる。

### 純関数としての意味論 (ARC-7)

数値の符号化・復号・演算・編集は、いずれも「入力バイト列 → 出力バイト列」の純関数として実装する。
状態を持たないため、Hercules から採取した V2 期待値を直接ぶつけて検証できる。
この性質は P0-a の受け入れ基準そのものであり、実装上の利便のために崩さない。
