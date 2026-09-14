# Bank-of-Z 資産のビルドおよび実行に向けた必要要件・課題分析レポート

| 項目 | 内容 |
| :--- | :--- |
| **作成日** | 2026-09-11 |
| **対象資材** | `reference/Bank-of-Z/src/base/` (CICS, IMS, Batch) |
| **関連設計** | [設計 77 (Spring CICS/Db2)](../design/77-spring-cics-db2.md), [設計 78 (IMS サブシステム)](../design/78-ims-subsystem.md), [利用ガイド](../guide.md) |

---

## 1. はじめに

本書は、`reference/Bank-of-Z/src/base/` 配下の各種資産（CICS / IMS / Batch）を、本プロジェクト（`cobol-on-java`）のコンパイラ（`cobol-compiler`）および実行系（`cobol-runtime` 等）を用いてビルド（JVMクラスファイル生成）し、JVM上で実行可能にするために何が必要であるかを分析・整理した調査レポートです。

---

## 2. 対象資材の内訳と現況

`reference/Bank-of-Z/src/base/` には、IBM メインフレーム（z/OS）上で稼働するハイブリッドバンキングデモのコア資産群が格納されています。

| サブシステム | 言語・種別 | 数量 | 主なファイル・役割 | 現行コンパイラでのビルド結果 |
| :--- | :--- | :--- | :--- | :--- |
| **CICS** | COBOL (`.cbl`) | 32本 | 口座開設(`BNK1CAC`)、照会(`INQACC`)、振替(`XFRFUN`)、アベンド処理(`ABNDPROC`)、共通部品(`GETCOMPY`) 等 | **2本成功 / 30本失敗**<br>（`GETCOMPY.cbl`, `GETSCODE.cbl` のみ成功） |
| **CICS** | コピー句 (`.cpy`) | 42本 | データ構造、Db2 テーブル定義(`ACCDB2`, `CUSTDB2`)、レスポンス定義 | コンパイル時のインクルード対象 |
| **CICS** | BMS マップ (`.bms`) | 10本 | 3270 画面レイアウトアセンブラ定義（`BNK1ACC.bms` 等） | アセンブラ未処理（DSECT未生成） |
| **IMS** | COBOL (`.cbl`) | 11本 | オンラインMPP (`IBLOGIN1`, `IBACSUM` 等 6本)、DB初期ロード (`LOADCUST`, `LOADACCT` 等 5本) | **0本成功 / 11本失敗** |
| **IMS** | コピー句 (`.cpy`) | 4本 | トランザクション電文レイアウト、JNI 連携用（`JNI.cpy`） | コンパイル時のインクルード対象 |
| **IMS** | DBD (`.asm`) | 9本 | 階層データベース物理定義（`CUSTOMER.asm`, `ACCOUNT.asm` 等） | アセンブラ定義 |
| **IMS** | PSB (`.asm`) | 8本 | プログラム仕様ブロック定義（`IB.asm`, `IBLOGIN.asm` 等） | アセンブラ定義 |
| **IMS** | データ (`.data`/`.csv`) | 12本 | 初期ロード用データ（CSV / TXT） | `LOADxxxx` バッチの入力データ |
| **IMS** | Java (Gradle) | 1式 | JMP/JBP 用 Java ロジック（`InsertHist.java`） | Java コード（Gradle 管理） |
| **IMS** | PL/I (`.pli`) | 1本 | オンライン PL/I 版ログイン（`IBLOGIN.pli`） | PL/I 言語 |
| **Batch** | JCL (`.jcl`) | 1本 | 月次残高ステートメント出力ジョブ（`BNKSTMT.jcl`） | JCL |
| **Batch** | PL/I (`.pli`) | 1本 | 月次残高ステートメント出力バッチ（`BNKSTMT.pli`） | PL/I 言語 |

---

## 3. 現行コンパイラでのコンパイル失敗要因の分析

全 COBOL ファイル（43本）に対して `dev.cobolonjava.compiler.Main` を実行したところ、以下の具体的な障害・文法制約が判明しました。

### 3.1 CICS COBOL における失敗要因

1. **BMS マップコピー句（`.cpy`）の不存在 (10本で発生)**:
   - 例: `BNK1CAC.cbl:65:8: copybook not found: BNK1CAM`
   - 原因: CICS の画面入出力プログラムは、BMS マクロ定義（`.bms`）から生成された DSECT コピー句（`...I`, `...O` 等の画面シンボル項目構造）を `COPY` します。リポジトリ内には `.bms` のみがあり、COBOL コピー句が事前に生成されていないためインクルード解決で失敗します。
2. **`DATA DIVISION` 内の `EXEC SQL INCLUDE` 記述 (14本で発生)**:
   - 例: `BANKDATA.cbl:52:12: extraneous input 'EXEC SQL INCLUDE CUSTDB2 END-EXEC' expecting {<EOF>, IDENTIFICATION, ID}`
   - 原因: `CobolParser.g4` では `execStatement` が `PROCEDURE DIVISION` 内の文法規則にしか存在せず、`WORKING-STORAGE SECTION` に `EXEC SQL` が現れると構文解析エラーになります。また、`EXEC SQL INCLUDE <name>` はプリプロセスで対応コピー句を展開すべきものです。
3. **CICS 組み込み関数 `DFHRESP(...)` の未サポート (1本で顕在化、他多数に波及)**:
   - 例: `ABNDPROC.cbl:149:33: mismatched input 'DFHRESP' expecting ...`
   - 原因: `IF WS-CICS-RESP NOT= DFHRESP(NORMAL)` などの条件式で用いられる `DFHRESP(...)` は、IBM CICS プリプロセッサがコンパイル時に整数の定数リテラルへ変換するマクロ機能です。現状のコンパイラでは通常の COBOL 組み込み関数（FUNCTION）とも認識されず弾かれます。
4. **CICS アイランドパーサー（`CicsBlockParser`）の対応コマンド不足**:
   - 例: `ABNDPROC.cbl` の `EXEC CICS WRITE FILE(...)`、`CRDTAGY1.cbl` の `EXEC CICS DELAY FOR SECONDS(...)`、`BNK1CAC.cbl` の `SEND MAP`, `RECEIVE MAP`, `ASKTIME`, `FORMATTIME`, `BIF DEEDIT` 等。
   - 原因: 現行の `CicsBlockParser` は初期 subset（`LINK`, `XCTL`, `RETURN`, `SYNCPOINT`, `ABEND`, `HANDLE/IGNORE CONDITION`, `PUSH/POP HANDLE`, `HANDLE ABEND`, `ASSIGN ABCODE`）のみに厳格制限されており、その他のコマンドは fail-closed でコンパイル拒否されます。
5. **暗黙 EIB データ項目の不足 (5本で発生)**:
   - 例: `CRDTAGY1.cbl:108:17: undefined data item: EIBTASKN`
   - 原因: EIB の暗黙宣言に `EIBTASKN`（タスク番号、4バイト COMP-3）等の一部の特殊項目が含まれていません。
6. **LE 共通コピー句 `CEEIGZCT` の欠落 (1本で発生)**:
   - 例: `CRECUST.cbl:351:12: copybook not found: CEEIGZCT`
   - 原因: z/OS Language Environment の条件処理トークン構造を定義する標準ヘッダが存在しません。

### 3.2 IMS COBOL における失敗要因

1. **手続き部副エントリ宣言 `ENTRY "DLITCBL"` の未サポート (10本で発生)**:
   - 例: `IBLOGIN1.cbl:183:20: no viable alternative at input 'ENTRY"DLITCBL"'`
   - 原因: IMS プログラム（MPP および バッチ DL/I）は `PROCEDURE DIVISION.` の直後に `ENTRY "DLITCBL" USING PCB1, PCB2...` と記述して PCB 引数を受け取るのがホストの標準規約です。現行パーサーに `ENTRY` 文の文法規則が存在しません。
2. **Java 連携句 `REPOSITORY.` の未サポート (1本で発生)**:
   - 例: `IBTRAN.cbl:19:8: extraneous input 'REPOSITORY' expecting {<EOF>, IDENTIFICATION, ID}`
   - 原因: `IBTRAN.cbl` は Java クラス（`nazare.jmp.controller.InsertHist`）を呼び出すため OO-COBOL の `CONFIGURATION SECTION. REPOSITORY.` を使用しています。
3. **`PROCEDURE DIVISION` 直後の `DISPLAY` 等の構文順序制約 (5本で発生)**:
   - 例: `LOADACCT.cbl:126:16: mismatched input 'DISPLAY' expecting PROGRAM`
   - 原因: `ENTRY` 文で回復に失敗した後、セクション名や段落名を持たない無名段落（`sentence*`）の配置規則でエラーが連鎖しています。

---

## 4. ビルドおよび実行を可能にするために必要な具体的アクション

### 4.1 【Phase 1】コンパイルを可能にするための改修（コンパイラ・ツール）

#### 1. BMS マップコンパイラ／ジェネレータの用意
- **タスク**: `.bms`（アセンブラマクロ）を解析し、COBOL コピー句（`.cpy`）を自動生成するツール（またはビルド前ステップ）を実装する。
  - 各フィールドの `POS=(line, col)`, `LENGTH=n`, `ATTRB=...` から、入出力用シンボル構造体（`xxxxI`, `xxxxO`）、フラグバイト、長さ項目（`xxxxL`）を生成する。
  - 生成されたコピー句を CICS コピー句ディレクトリ（またはビルド時の一時 include ディレクトリ）へ出力する。

#### 2. プリプロセッサの拡張 (`cobol-compiler`)
- **`EXEC SQL INCLUDE` の事前展開**:
  - `Preprocessor` または `CopyExpander` において、`EXEC SQL INCLUDE <name> END-EXEC` を `COPY <name>` と同等に扱い、コピー句を展開する。
- **`DFHRESP(...)` のマクロ展開**:
  - `DFHRESP(NORMAL)` -> `0`、`DFHRESP(NOTFND)` -> `13` 等、既知の CICS 応答コード定数への置換処理をプリプロセッサまたは構文解析前段に組み込む。
- **LE 標準コピー句 `CEEIGZCT.cpy` の配置**:
  - メインフレーム互換の 12 バイトフィードバックコード構造（`SEVERITY`, `MSG-NO`, `FACILITY-ID` 等）を持つダミー／標準コピー句を配置する。

#### 3. ANTLR 構文文法（`CobolParser.g4`）の拡張
- **`DATA DIVISION` 内の `execStatement` 許容**:
  - `dataDescriptionEntry` または `workingStorageSection` / `localStorageSection` / `linkageSection` に `execStatement`（`EXEC SQL ... END-EXEC`）を配置できるようにする。
- **`ENTRY` 文の追加**:
  - 手続き部に `entryStatement: ENTRY (LITERAL | IDENTIFIER) (USING procedureParameter+)? PERIOD;` を追加し、副エントリポイントのシグネチャを保持できるようにする。
- **`REPOSITORY` パラグラフの追加**:
  - `configurationSection` に `repositoryParagraph: REPOSITORY PERIOD (classSpecifier | functionSpecifier)*;` を追加する。

#### 4. CICS アイランドパーサー（`CicsBlockParser`）および意味解析の拡張
- Bank of Z で使われている以下のコマンド構文とコード生成を追加する：
  - `WRITE FILE(...) FROM(...) RIDFLD(...) [RESP(...)] [RESP2(...)]`
  - `READ FILE(...) INTO(...) RIDFLD(...) [RESP(...)]`
  - `SEND MAP(...) [MAPSET(...)] [FROM(...)] [DATAONLY | ERASE]`
  - `RECEIVE MAP(...) [MAPSET(...)] [INTO(...)]`
  - `SEND TEXT(...) [FROM(...)] [ERASE]` / `SEND CONTROL [ERASE | FREEKB]`
  - `DELAY FOR SECONDS(...)`
  - `ASKTIME [ABSTIME(...)]` / `FORMATTIME ABSTIME(...) [DATESEP(...)] ...`
  - `BIF DEEDIT FIELD(...)`
  - `ASSIGN APPLID(...) / PROGRAM(...)`
- 暗黙 EIB 構造体に `EIBTASKN`, `EIBTIME`, `EIBDATE` を追加する。

---

### 4.2 【Phase 2】実行を可能にするための改修（ランタイム・インフラ・周辺）

コンパイルによって `.class` が生成された後、実際に業務ロジックを稼働させるための実行基盤です。

#### 1. CICS オンラインおよび Db2 の実行基盤
- **VSAM KSDS ファイル制御の提供**:
  - `ABNDPROC.cbl` が `ABNDFILE` へ書き込むアベンドログを処理するため、`cobol-runtime` の `IndexedFile` を CICS ファイル制御ポートへ結びつける。
- **Db2 UOW / SQL Executor の接続**:
  - `cobol-spring-boot-4-autoconfigure` および `cobol-db2-jdbc` を用い、`ACCOUNT`, `CUSTOMER`, `PROCTRAN` 等のテーブルを PostgreSQL または Db2 上に構築し、SQL 実行を行えるようにする。
- **BMS Web UI 端末エミュレーション**:
  - [設計 77](../design/77-spring-cics-db2.md) および [ADR-0010](../decisions/0010-bms-thymeleaf-terminal-ui.md) に基づき、BMS マップから Thymeleaf + CSS 画面をレンダリングし、HTTP 要求を CICS タスク（`CobolCicsTaskProgram`）へディスパッチする Web ゲートウェイを稼働させる。

#### 2. IMS DB / TM 実行基盤（[設計 78](../design/78-ims-subsystem.md)）
- **`CALL 'CBLTDLI'` ランタイムブリッジ (`cobol-ims`)**:
  - COBOL から呼び出される `CBLTDLI`（GU, GN, ISRT, REPL）を捕捉し、PCB 状態コードを設定する中立ポート。
- **階層 DB ストレージエンジン (`cobol-ims-rdb`)**:
  - 9 個の DBD（`CUSTOMER`, `ACCOUNT` 等）のスキーマを RDB（PostgreSQL / H2）上の `IMS_SEGMENT_STORE` へマッピング。
  - 初期ロードプログラム（`LOADACCT.cbl`, `LOADCUST.cbl` 等）を実行し、`LoadData/*.data` の CSV を DB へ投入する。
- **IMS TM メッセージキューイング (`cobol-ims-jms`)**:
  - RabbitMQ（Docker Compose）+ JMS 3.0 を介して、トランザクション電文（`IBLOGIN1`, `IBACSUM` 等）をキュー駆動で COBOL プログラムへ供給する。

#### 3. 非 COBOL 資産（PL/I, JCL）の取り扱い
- **`BNKSTMT.pli` (PL/I バッチ)**:
  - COBOL 処理系の対象外であるため、同等機能の COBOL 版（`BNKSTMT.cbl`）を作成するか、Java / Spring Batch による等価サービスとして提供する。
- **`BNKSTMT.jcl` (JCL)**:
  - `cobol-job` の JCL パーサーおよび `IKJEFT01` ユーティリティエミュレータを用いて実行する。

---

## 5. 実行までの推進ステップ案

```
[ステップ 1: ビルド成立フェーズ]
  1. BMS マップから COBOL コピー句を生成（ビルド前ツールまたは手動生成）
  2. プリプロセッサ改修 (EXEC SQL INCLUDE 展開、DFHRESP 置換、CEEIGZCT 配置)
  3. CobolParser.g4 改修 (DATA DIVISION の EXEC SQL、ENTRY 文、REPOSITORY 節)
  4. CicsBlockParser 拡張 (WRITE, DELAY, SEND/RECEIVE MAP 等の基本構文)
  ==> Bank-of-Z の COBOL 43 本がすべてクラスファイルへコンパイル可能になる

[ステップ 2: データ準備・バッチ実行フェーズ]
  1. cobol-ims-rdb エンジンの立ち上げ (PostgreSQL または H2)
  2. DBD 定義から階層ストレージメタデータを生成
  3. LOADCUST / LOADACCT バッチを cobol-job 経由で実行し、初期データをロード
  ==> データベースが利用可能な状態になる

[ステップ 3: CICS オンライン Web 実行フェーズ]
  1. Spring Boot 4.1 アプリケーションに cobol-cics / cobol-spring-boot-4-autoconfigure を組み込み
  2. BMS マップから Thymeleaf 画面を生成
  3. ブラウザから口座開設・照会・振替のトランザクションを実行・確認

[ステップ 4: IMS オンライン MPP 実行フェーズ]
  1. RabbitMQ コンテナを起動
  2. cobol-ims-jms により IBLOGIN1 / IBACSUM を起動
  3. テスト電文（REST / 外部クライアント）を投入し、応答電文を確認
```

---

## 6. まとめ

Bank-of-Z 資産は、CICS、Db2、IMS DB、IMS TM、BMS という IBM メインフレームの代表的なエンタープライズ機能が凝縮された非常に価値の高い検証資材です。

現行の `cobol-on-java` は中立 subsystem アーキテクチャ（設計 77, 78）がすでに策定されており、コンパイラ側の「プリプロセス展開（`EXEC SQL INCLUDE`, `DFHRESP`）」「BMS マップのコピー句化」「手続き部 `ENTRY` 文」という比較的局所的な機能拡張を行うことで、全 COBOL 資産のビルドを達成できる見通しが得られました。ビルド成立後、段階的にストレージ・キュー基盤を接続していくことで、完全な実行環境を構築することが可能です。
