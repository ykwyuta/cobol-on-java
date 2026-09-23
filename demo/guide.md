# cobol-on-java デモシナリオガイド

本書は、`cobol-on-java` の多彩な機能を体感するためのデモシナリオ一覧と実行ガイドです。
メインフレーム互換機能（バッチ、ファイル入出力、JCL、システムユーティリティ、CICS）から、オープン化・近代化を支える Java 連携（相互運用、JUnit 5 単体テスト、Spring Boot & Db2 SQL 統合）まで、全 10 のデモシナリオを提供しています。

---

## 1. デモシナリオ一覧

| No | シナリオ名 | 概要・検証ポイント | 状態 |
|---|---|---|---|
| **001** | [複数プログラム CALL 連携](file:///d:/workspace/cobol-on-java/demo/001/README.md) | 主プログラムから副プログラムへの `CALL ... USING`、`LINKAGE SECTION` による引数受け渡し、`ProgramContext` による状態管理 | **提供中** |
| **002** | [順編成ファイル入出力と EBCDIC 格納](file:///d:/workspace/cobol-on-java/demo/002/README.md) | `SELECT ... ASSIGN` によるデータセット割当、EBCDIC（IBM-1047）生バイト書き出し、サイドカーメタファイル (`.meta`) の検証と読込 | **提供中** |
| **003** | [JCL バッチジョブ実行制御](file:///d:/workspace/cobol-on-java/demo/003/README.md) | `cobol-job` による JCL 解析・実行、`IEFBR14`、`PARM` 渡し、動的 DD 割当、SYSOUT スプール、`IF-THEN` / `COND` 条件分岐 | **提供中** |
| **004** | [JCL ユーティリティ連携 (SORT / IEBGENER)](file:///d:/workspace/cobol-on-java/demo/004/README.md) | メインフレーム標準ユーティリティ (`SORT`/`DFSORT` でのフィールド並べ替え・`OUTFIL` 抽出、`IEBGENER` でのデータセット複製) の JCL 実行 | **提供中** |
| **005** | [Java ↔ COBOL 双方向相互運用](file:///d:/workspace/cobol-on-java/demo/005/README.md) | Java アプリケーションから `CobolSession` / `ProgramCatalog` を通じた COBOL 呼出、および COBOL `CALL` から Java 登録クラス (`JavaCallable`) へのコールバック | **提供中** |
| **006** | [JUnit 5 による COBOL 単体テスト & Mocking](file:///d:/workspace/cobol-on-java/demo/006/README.md) | `cobol-junit` (`@RegisterExtension CobolExtension`) を用いた単体テスト、外部 COBOL サブルーチンの Java Mock 差替、SECTION 単位の Spy 検証 | **提供中** |
| **007** | [CICS トランザクション & 疑似会話制御](file:///d:/workspace/cobol-on-java/demo/007/README.md) | `cobol-cics` による `EXEC CICS LINK` / `XCTL` / `RETURN TRANSID(...) COMMAREA(...)` 制御、EIB 状態管理、疑似会話トランザクション境界 | **提供中** |
| **008** | [Spring Boot & Db2 SQL 連携](file:///d:/workspace/cobol-on-java/demo/008/README.md) | Spring Boot 4.x / Spring Framework 7 との統合、COBOL ホスト変数による SQL 実行、Spring 管理トランザクション（コミット/ロールバック）との同期 | **提供中** |
| **009** | [BMS + COBOL + H2 による Todo アプリ](file:///d:/workspace/cobol-on-java/demo/009/README.md) | BMS マップ (`DFHMSD`/`DFHMDI`/`DFHMDF`) からの記号マップ自動生成、`SEND MAP`/`RECEIVE MAP` と `RETURN TRANSID COMMAREA` による疑似会話、COBOL の `EXEC SQL`（カーソルを含む）による H2 アクセス。同じ素材を Spring Boot + Thymeleaf のブラウザ画面でも動かせる (`run_web`) | **提供中** |
| **010** | [Maven の標準ディレクトリ体系でのビルド](file:///d:/workspace/cobol-on-java/demo/010/README.md) | `cobol-maven-plugin` による COBOL (写し句つき)・JCL (目録手続きつき)・PL/I (`%INCLUDE` つき) のビルド。`src/main/cobol` / `copybook` / `jcl` / `proclib` / `pli` / `pli-include` の標準の置き場、ビルド時の JCL 検査、ビルド結果からのジョブ実行 | **提供中** |

---

## 2. 各デモシナリオの実行方法

すべてのデモは、Windows 環境向けにワンクリックで実行可能なバッチファイル (`run_demo.bat`) を同梱しています。デモ #009 には Linux / macOS 向けの `run_demo.sh` と、ブラウザ版の `run_web.bat` / `run_web.sh` も付いています。

### 事前準備
1. **JDK 21 以上**、**Maven 3.9 以上** が PATH に通っていることを確認します。
2. プロジェクトルートで全体ビルドが済んでいない場合は実行してください:
   ```cmd
   mvn clean package -DskipTests
   ```

---

### デモ #001: 複数プログラム CALL 連携
* **概要**: 主プログラム `MAIN-JOB.cbl` から副プログラム `CALC-TAX.cbl` を `CALL` し、引数領域のゼロコピー参照（`DataView`）によって消費税計算を行います。
* **実行コマンド**:
  ```cmd
  demo\001\run_demo.bat
  ```
* **詳細説明**: [`demo/001/README.md`](file:///d:/workspace/cobol-on-java/demo/001/README.md)

---

### デモ #002: ファイル入出力（順編成データセット）
* **概要**: `WRITE-DATA.cbl` で顧客データファイル (`CUSTFILE`) を EBCDIC 生バイト列で作成し、属性サイドカーファイル (`CUSTFILE.meta`) の検証を経て `READ-DATA.cbl` で集計・印字します。
* **実行コマンド**:
  ```cmd
  demo\002\run_demo.bat
  ```
* **詳細説明**: [`demo/002/README.md`](file:///d:/workspace/cobol-on-java/demo/002/README.md)

---

### デモ #003: JCL バッチジョブ実行
* **概要**: メインフレーム互換 JCL (`SALESJOB.jcl`) を `cobol-job` の実行エントリポイント（`dev.cobolonjava.job.Main`）で実行し、ダミーステップ `IEFBR14`、`PARM` 引数渡し、動的 DD 割り当て、`IF-THEN` 条件分岐を体験します。
* **実行コマンド**:
  ```cmd
  demo\003\run_demo.bat
  ```
* **詳細説明**: [`demo/003/README.md`](file:///d:/workspace/cobol-on-java/demo/003/README.md)

---

### デモ #004: JCL ユーティリティ連携 (SORT / IEBGENER)
* **概要**: メインフレームのバッチジョブで必須となる標準ユーティリティプログラムの動作を体験します。
  - `STEP 1 (GEN)`: COBOL プログラム `GEN_TRANS` で未ソートの取引データセット (`RAWTRANS.DAT`) を出力。
  - `STEP 2 (BACKUP)`: ユーティリティ `IEBGENER` を実行し、データセットの完全複製 (`BAKTRANS.DAT`) を作成。
  - `STEP 3 (SORTSTEP)`: ソートユーティリティ `SORT` (`DFSORT`) を実行し、`SYSIN` 制御文（`SORT FIELDS=(7,5,ZD,D)`, `OUTFIL INCLUDE=...`）で特定条件の抽出と金額降順ソートを実行。
  - `STEP 4 (REPORT)`: 後続 COBOL プログラム `PRT_REPORT` で抽出結果を印字。
* **実行コマンド**:
  ```cmd
  demo\004\run_demo.bat
  ```
* **詳細説明**: [`demo/004/README.md`](file:///d:/workspace/cobol-on-java/demo/004/README.md)

---

### デモ #005: Java ↔ COBOL 双方向相互運用 (Java Interop)
* **概要**: Java アプリケーションと COBOL プログラムが、同一 JVM 内で透過的にデータを共有・相互呼出しする機構を体験します。
  - Java 側アプリケーション (`Demo005Main.java`) から `CobolSession` を通じて COBOL プログラム (`ORDER-PROCESS.cbl`) を呼び出し。
  - COBOL 内部の `CALL 'FXSERVICE' USING ...` から Java 登録サービス (`JavaCallable`) へのコールバックを駆動し、最新レート（150.00）を返却。
  - COBOL 側でドル換算計算を行い、Java 側が更新された結果ストレージから値を取得。
* **実行コマンド**:
  ```cmd
  demo\005\run_demo.bat
  ```
* **詳細説明**: [`demo/005/README.md`](file:///d:/workspace/cobol-on-java/demo/005/README.md)

---

### デモ #006: JUnit 5 による COBOL 単体テスト & Mocking
* **概要**: レガシー COBOL プログラムのテスト自動化を Java エコシステム (JUnit 5) で実現します。
  - `LoanAppTest.java` で `@RegisterExtension CobolExtension` を宣言。
  - 未実装の外部信用照会サブルーチン `SCOREAPI` を `cobol.expectProgram("SCOREAPI").thenAnswer(...)` で Java 実装へモック化。
  - 内部セクション `EVALUATE-LIMIT SECTION` の実行を `spySection` で監視。
  - 採否フラグや融資限度額の結果を JUnit 5 の `assertEquals` でアサート。
* **実行コマンド**:
  ```cmd
  demo\006\run_demo.bat
  ```
* **詳細説明**: [`demo/006/README.md`](file:///d:/workspace/cobol-on-java/demo/006/README.md)

---

### デモ #007: CICS オンライントランザクション & 疑似会話制御
* **概要**: メインフレームのオンライントランザクション処理 (CICS) を模したトランザクション制御を体験します。
  - `CICSMENU.cbl` から `EXEC CICS LINK PROGRAM('INQPROG')` で子プログラムを呼び出し、COMMAREA の受け渡しを実施。
  - `EXEC CICS XCTL PROGRAM('FINPROG')` によりコールスタックを解放して次プログラムへ遷移。
  - `FINPROG.cbl` 内で `EXEC CICS RETURN TRANSID('NEXT') COMMAREA(...)` を発行し、次回トランザクションの予約（疑似会話）を行ってタスクを終了。
* **実行コマンド**:
  ```cmd
  demo\007\run_demo.bat
  ```
* **詳細説明**: [`demo/007/README.md`](file:///d:/workspace/cobol-on-java/demo/007/README.md)

---

### デモ #008: Spring Boot & Db2 SQL 連携
* **概要**: Spring Framework 7 / Spring Boot 4.x のトランザクション管理下で、COBOL ホスト変数を用いた Db2 互換 SQL 実行とビジネスロジックを協調動作させます。
  - `SpringDb2DemoMain.java` でインメモリ H2 DB および `SpringManagedUnitOfWorkPort` をセットアップ。
  - トランザクション 1 (預金): SQL `SELECT` で現在残高を取得し、COBOL `ACC-PROCESS` で新残高を計算後、SQL `UPDATE` して正常コミット。
  - トランザクション 2 (引出・残高不足): COBOL 側で残高不足ステータスを検知し、Spring 管理トランザクションのロールバックが実行され DB が保護されることを確認。
* **実行コマンド**:
  ```cmd
  demo\008\run_demo.bat
  ```
* **詳細説明**: [`demo/008/README.md`](file:///d:/workspace/cobol-on-java/demo/008/README.md)

---

### デモ #009: BMS + COBOL + H2 による Todo アプリ（サンプルアプリケーション）
* **概要**: 画面・業務処理・データベースを 1 本のオンライントランザクションにまとめた、動くサンプルアプリケーションです。COBOL の外に業務ロジックはありません。
  - `TODOSET.bms`: 24x80 の BMS マップ。一覧の 8 行は `OCCURS=8` の欄。記号マップ写し句は翻訳時に原文から生成されます（`-I demo/009`）。
  - `TODOAPP.cbl`: `EXEC CICS RECEIVE MAP` でコマンド行を読み、`EXEC SQL`（`SELECT` / `INSERT` / `UPDATE` / `DELETE` とカーソル）で H2 の `TODO` 表を操作し、`EXEC CICS SEND MAP` で一覧を描き直して `RETURN TRANSID('TODO') COMMAREA(...)` で疑似会話を継続します。
  - `TodoTerminal.java`: `BmsScreenSnapshot` を 24x80 の文字格子として端末に描き、打ち込んだ 1 行を 3270 の入力（AID + 変更された欄）に見立てます。
* **実行コマンド**:
  ```cmd
  demo\009\run_demo.bat
  ```
  ```sh
  demo/009/run_demo.sh          # Linux / macOS
  demo/009/run_demo.sh --demo   # 打たずに一通り流す
  ```
* **ブラウザ版** (Spring Boot + Thymeleaf): 同じ `TODOAPP.cbl` と `TODOSET.bms` を書き換えずに、`cobol-spring-boot-4-bms-thymeleaf` の 3270 画面で動かします。`http://localhost:8080/` を開き、`demo` / `demo` でログインします。
  ```cmd
  demo\009\run_web.bat
  ```
  ```sh
  demo/009/run_web.sh
  ```
* **詳細説明**: [`demo/009/README.md`](file:///d:/workspace/cobol-on-java/demo/009/README.md)

---

### デモ #010: Maven の標準ディレクトリ体系でのビルド
* **概要**: 資産を標準の置き場に置き、`mvn package` 1 回で COBOL・PL/I の翻訳と JCL の検査を行います ([設計 91](file:///d:/workspace/cobol-on-java/docs/design/91-maven-build.md))。
  - `sales/`: COBOL 2 本が写し句 `SALESREC` を共有し、`SALESJOB.jcl` が PROCLIB の目録手続き `SALESRPT` を呼びます。
  - `greet/`: PL/I の `HELLO.pli` が `%INCLUDE GREETING` を取り込みます。
* **実行コマンド**:
  ```cmd
  demoun_demo.bat
  ```
  ```sh
  demo/010/run_demo.sh          # Linux / macOS
  ```
* **詳細説明**: [`demo/010/README.md`](file:///d:/workspace/cobol-on-java/demo/010/README.md)
