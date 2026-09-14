# PL/I 資産のコンパイルおよび実行に向けた必要要件・方式検討レポート

| 項目 | 内容 |
| :--- | :--- |
| **作成日** | 2026-09-11 |
| **対象言語** | **PL/I (Programming Language One)**（Enterprise PL/I for z/OS 準拠） |
| **対象資材** | `reference/Bank-of-Z/src/base/batch/pli/BNKSTMT.pli`, `reference/Bank-of-Z/src/base/ims/pli/IBLOGIN.pli` |
| **関連文書** | [Bank-of-Z ビルド要件レポート](bank-of-z-build-and-execution-requirements.md), [設計 77 (Db2)](../design/77-spring-cics-db2.md), [設計 78 (IMS)](../design/78-ims-subsystem.md), [設計 90 (Job)](../design/90-job.md) |

---

## 1. はじめに

Bank-of-Z 資産の調査において、COBOL 資産群に加え、以下の **PL/I 言語資産** が存在することが確認されました：
1. **`src/base/batch/pli/BNKSTMT.pli`**:
   - 月次残高ステートメント出力バッチ（912行）。`IKJEFT01`（TSO バッチ）から起動され、Db2 カーソル（`ACCOUNT`, `CUSTOMER`, `PROCTRAN`）を走査して `SYSPRINT` へ月次明細帳票を出力する。
2. **`src/base/ims/pli/IBLOGIN.pli`**:
   - IMS TM/DB オンラインログインプログラム（348行）。`CALL PLITDLI` を使用し、I/O PCB からメッセージを取得（`GU`）、IMS 階層 DB（`CUSTOMER` セグメント）を照会・更新（`GU`, `REPL`）、応答を返送（`ISRT`）する。

本レポートでは、COBOL と同様にこれら PL/I 資産を JVM 上で直接コンパイル（バイトコード生成）し、実行可能にするために何が必要であるかをアーキテクチャ・文法・ランタイムの観点から詳細に検討し、報告します。

---

## 2. PL/I と COBOL の言語的差異および共通点

PL/I は IBM が 1960 年代に科学計算（FORTRAN）と事務処理（COBOL）の双方の長所を統合することを目指して開発した強力な手続き型・ブロック構造言語です。

| 観点 | COBOL | PL/I | 共通点（JVM 実行系での再利用性） |
| :--- | :--- | :--- | :--- |
| **構文構造** | 4つの部（DIVISION）による階層構造。予約語固定 | アルゴル風ブロック構造（`PROCEDURE ... END;`）。**予約語なし**（文脈依存キーワード） | 文末が終止符（COBOL: `.`）またはセミコロン（PL/I: `;`）で区切られる手続き型 |
| **メモリモデル** | 静的オフセット・連続バイト列配置（`CobolSession` メモリモデル） | 静的記憶域（`STATIC`）、自動記憶域（`AUTOMATIC`）、基底付き記憶域（`BASED`） | メインフレーム互換の**連続バイト列（EBCDIC 生バイト、ビッグエンディアン）**として統一可能 |
| **データ型** | `PIC X(n)`, `PIC 9(n) COMP-3`, `COMP`, `COMP-5` | `CHAR(n)`, `FIXED DEC(p,q)`, `FIXED BIN(p)`, `BIT(n)`, `POINTER` | **完全に 1:1 対応可能**（後述） |
| **サブルーチン呼出し** | `CALL 'pgm' USING ...` | `CALL proc(...)` または `CALL PLITDLI(...)` | 引数渡しは原則「ポインタ（参照）渡し」で共通 |
| **SQL 連携** | `EXEC SQL ... END-EXEC.` | `EXEC SQL ... ;` | 埋め込み SQL（ホスト変数バインディング、SQLCA）の構造は完全同一 |
| **IMS 連携** | `CALL 'CBLTDLI' USING ...` | `CALL PLITDLI (parmcount, func, pcb, buf)` | 第1引数（パラメタ数）の有無以外、DL/I 意味論は完全同一 |
| **例外処理** | `USE AFTER ERROR` / `DECLARATIVES` | `ON condition BEGIN; ... END;`（`ON ENDFILE`, `ON ERROR`） | ランタイムシグナル／状態遷移モデルとして抽象化可能 |

---

## 3. Bank-of-Z の PL/I 資産が使用している言語機能詳細

### 3.1 データ型宣言（`DCL` / `DECLARE`）
Bank-of-Z の PL/I コードで使用されている型は、COBOL の内部表現と極めて親和性が高いことがわかります：

| PL/I 記述 | 意味 | COBOL 対応表現 | `cobol-runtime` での内部実装 |
| :--- | :--- | :--- | :--- |
| `CHAR(n)` | n バイト固定長文字列 | `PIC X(n)` | `AlphanumericItem`（EBCDIC バイト列） |
| `FIXED BIN(31)` | 4 バイト符号付き 2 進整数 | `PIC S9(9) COMP`（または `COMP-5`） | 32-bit ビッグエンディアン整数（`BinaryItem`） |
| `FIXED BIN(15)` | 2 バイト符号付き 2 進整数 | `PIC S9(4) COMP`（標識変数等） | 16-bit ビッグエンディアン整数（`BinaryItem`） |
| `FIXED DEC(10,2)` | 10 桁・小数部 2 桁のパック 10 進数 | `PIC S9(8)V9(2) COMP-3` | `PackedDecimalItem`（IBM COMP-3 形式） |
| `POINTER` | 参照メモリアドレス | `USAGE POINTER` | オフセットポインタ／JVM オブジェクト参照 |
| `BIT(1)` (`'0'B`, `'1'B`) | 1 ビット論理値 | `PIC X VALUE '0'`（条件名 88） | ビット演算またはバイトフラグ |
| `BASED (ptr)` | ポインタ修飾の動的構造体 | `LINKAGE SECTION` 項目 | 基底バッファ上の動的オフセットビュー |
| `INIT(...)` | 初期値設定 | `VALUE ...` | クラス初期化時のバイト列書き込み |

### 3.2 制御構造・組み込み機能
- **ブロックと手続き**: `BNKSTMT: PROCEDURE OPTIONS(MAIN); ... END;`, 内部副手続き `FETCH_ACCOUNT: PROCEDURE; ... END;`
- **ループ・条件分岐**:
  - `DO WHILE (^END_OF_DATA); ... END;`
  - `DO UNTIL (TERM_IO = 1); ... END;`
  - `IF ... THEN DO; ... END; ELSE DO; ... END;`
- **文字列操作・組み込み関数**:
  - `SUBSTR(str, pos, len)`（COBOL の部分参照 `str(pos:len)` と等価）
  - `TRIM(str)`（前後の空白除去）
  - `DATETIME()`（現在日時の取得）
  - `||`（文字列結合。COBOL の `STRING` 文に相当）
- **ストリーム I/O**:
  - `PUT SKIP LIST(...)`: 改行付き標準出力（COBOL の `DISPLAY` に相当）
  - `OPEN FILE(DATECARD)`, `READ FILE(DATECARD) INTO(...)`, `CLOSE FILE(...)`（COBOL の順編成 `READ` に相当）
- **条件処理（例外ハンドリング）**:
  - `ON ENDFILE(SORTCODE) BEGIN; ... END;`: ファイル終端時の割り込み処理（COBOL の `AT END` に相当）

### 3.3 サブシステム連携
- **Db2 埋め込み SQL**:
  - `EXEC SQL INCLUDE SQLCA;`
  - `EXEC SQL DECLARE ACCT_CURSOR CURSOR FOR SELECT ...;`
  - `EXEC SQL OPEN / FETCH / CLOSE ACCT_CURSOR;`
  - `EXEC SQL COMMIT WORK;`
- **IMS DL/I 呼出し**:
  - `CALL PLITDLI (parmcount, func, pcb, buf)`（COBOL の `CBLTDLI` と同等。先頭に引数個数を表す `FIXED BIN(31)` 定数が付く）

---

## 4. PL/I コンパイル・実行を実現するための具体的要件

PL/I を COBOL と同様に JVM バイトコードへコンパイルし実行可能にするためには、以下のコンポーネントの実装が必要です。

```
[ PL/I ソースコード (.pli) ]
             |
             v
+-------------------------------------------------------------+
| `pli-compiler` (新規モジュール)                              |
|   1. プリプロセッサ (*PROCESS, %INCLUDE)                     |
|   2. 構文解析器 (ANTLR4: PliParser.g4)                       |
|   3. 意味解析器 (型検査, 記憶域レイアウト, スコープ解決)     |
|   4. バイトコード生成 (ASM: CobolSession / JVM クラス生成)   |
+-------------------------------------------------------------+
             |
             +--------------------+--------------------+
             |                    |                    |
             v                    v                    v
+------------------------+ +------------------+ +------------------+
| `cobol-runtime`        | | `cobol-db2`      | | `cobol-ims`      |
| (メモリ/文字/算術共通) | | (SQL/UOW共通)    | | (DL/I 共通)      |
+------------------------+ +------------------+ +------------------+
```

### 4.1 コンパイラ側要件 (`pli-compiler` モジュール新設)

#### (1) プリプロセッサ
- `*PROCESS SYSTEM(IMS);` などのコンパイラ指示文の解釈。
- `%INCLUDE member;`（PL/I のコピー句インクルード）の解決。
- `EXEC SQL INCLUDE SQLCA;` を SQLCA 構造体宣言へ展開。

#### (2) ANTLR4 構文解析器（`PliParser.g4`）
- PL/I は **「文脈依存で予約語が存在しない」**（例: `IF IF = THEN THEN THEN = ELSE;` が構文上許される）という古典的難所があります。
- ただし、Bank-of-Z で実際に使われている構文サブセット（構造化手続き、`DCL`、`IF/DO WHILE/DO UNTIL`、`PUT LIST`、`OPEN/READ/CLOSE`、`CALL`、`EXEC SQL`）にスコープを絞ることで、標準的かつ堅牢な ANTLR4 文法で十分パース可能です。

#### (3) 意味解析とメモリレイアウト
- **`cobol-runtime` メモリモデルの完全共有**:
  - PL/I の `CHAR(n)`, `FIXED DEC(p,q)`, `FIXED BIN(15/31)` を、既存の `cobol-runtime` の `DataLayout`（バイト配列上のオフセット・長さ）へそのまま写像します。
  - `BASED (ptr)` は、引数や動的バッファから渡されたアドレス（`byte[]` + offset）を指すポインタ変数として解決します。

#### (4) バイトコード生成（ASM）
- 各 `PROCEDURE` を JVM メソッド（または独立クラス）として生成。
- 内部プロシージャ（`FETCH_ACCOUNT: PROCEDURE;` 等）は、同一クラス内の `private` メソッドとしてバイトコード生成。
- `PUT SKIP LIST(...)` は `System.out.println` または `SYSOUT` ストリームへ出力。
- `SUBSTR` や `||`（文字列結合）は `cobol-runtime` のバイト列操作命令を生成。

---

### 4.2 ランタイム・サブシステム連携要件

コンパイラが生成した JVM クラスを実行するランタイム側は、**COBOL 向けに作成した中立ポート基盤をほぼ 100% そのまま再利用** できます。

#### (1) IMS 連携ブリッジ（`CALL PLITDLI`）
- `cobol-ims`（[設計文書 78](../design/78-ims-subsystem.md)）の `CbltdliBridge` と並んで、`PlitdliBridge` を配置します。
  ```java
  public final class PlitdliBridge {
      // PLITDLI (parmCount, func, pcb, buffer, ssa...)
      public static void call(int parmCount, DataItem func, DataItem pcb, DataItem buffer, DataItem... ssas) {
          // 先頭の parmCount をスキップし、既存の DliDatabasePort / ImsQueuePort へディスパッチ
          CbltdliBridge.dispatch(func.asString().trim(), pcb, buffer, ssas);
      }
  }
  ```
- これにより、`IBLOGIN.pli` の `CALL PLITDLI (THREE, GU, LTERMPCB, INPUT_AREA);` は、COBOL の `CALL 'CBLTDLI'` と全く同一の RabbitMQ / RDB ストレージエンジンへ透過的に接続されます。

#### (2) Db2 連携（`BNKSTMT.pli` の SQL 実行）
- PL/I 内の `EXEC SQL OPEN / FETCH / CLOSE` は、COBOL と同じ `SqlPlan` / `UnitOfWorkPort`（`cobol-db2`）へバインドされます。
- ホスト変数（`HV_ACCT_NUMBER`, `HV_ACCT_AVAIL_BALANCE` 等）も、COBOL のホスト変数と同じバイト列コーデックで JDBC `PreparedStatement` / `ResultSet` と双方向バインドされます。

#### (3) バッチジョブ実行（`cobol-job` での `BNKSTMT.jcl` 実行）
- `BNKSTMT.jcl` は以下のように記述されています：
  ```jcl
  //STEP1    EXEC PGM=IKJEFT01
  //DATECARD DD *
  202606
  //SORTCODE DD *
  123456
  //SYSTSIN  DD *
   DSN SYSTEM(DBD1)
   RUN  PROGRAM(BNKSTMT) PLAN(BANKZPLN) LIB('BANKZ.V0R1M0.LOAD')
   END
  ```
- `cobol-job` の `IKJEFT01` ユーティリティエミュレータはすでに実装されているため、生成された `BNKSTMT.class` をロード対象として指定することで、DD 割当て（`DATECARD`, `SORTCODE`, `SYSPRINT`）を行ってそのままジョブステップとして実行可能です。

---

## 5. 実現に向けたアプローチの比較（方針選択）

PL/I 資産を JVM 上で実行可能にするには、以下の 2 つのアプローチが考えられます。

| 観点 | 【方式 A】PL/I 直接コンパイラ新設 (`pli-compiler`) | 【方式 B】PL/I → COBOL トランスパイラ / 等価 COBOL 化 |
| :--- | :--- | :--- |
| **概要** | `cobol-compiler` と同様に、PL/I 用の ANTLR 文法と ASM コード生成モジュールを開発する。 | PL/I ソースを等価な COBOL ソース（または Java）へ機械変換・手動移植し、既存の `cobol-compiler` でビルドする。 |
| **ソース互換性** | **無修正（100%）**。メインフレーム資産の `.pli` をそのままビルド可能。 | ソース変更を伴う（`.pli` と `.cbl` の二重管理が発生）。 |
| **開発工数** | **大**（PL/I 文法パーサー、意味解析、コード生成の新規開発が必要）。 | **小〜中**（Bank-of-Z の対象は 2 本のみであり、変換ルールまたは実装は限定的）。 |
| **アーキテクチャ整合性** | `cobol-runtime`, `cobol-db2`, `cobol-ims` を多言語から共用する「メインフレーム共通 JVM 基盤」に発展。 | COBOL 処理系としてのスコープ内に閉じる。 |
| **推奨スコープ** | 将来的に PL/I 資産を多く含む案件を移行する場合の戦略的選択肢。 | Bank-of-Z の稼働を最短で達成する場合の現実的選択肢。 |

---

## 6. 実装タスク一覧と推奨ロードマップ（方式 A 基準）

PL/I 直接コンパイラを整備してビルド・実行可能にする場合のステップです：

### タスク 1: `pli-compiler` モジュールの新設
1. **`PliParser.g4` の作成**:
   - `DCL`（変数・構造体・ポインタ・初期値）、`PROCEDURE / END`、`IF/THEN/ELSE`、`DO WHILE/UNTIL`、`PUT SKIP LIST`、`OPEN/READ/CLOSE`、`CALL`、`EXEC SQL` を受理する構文規則を定義。
2. **メモリレイアウト・型変換**:
   - `CHAR`, `FIXED BIN(15/31)`, `FIXED DEC(p,q)`, `POINTER`, `BASED` を `cobol-runtime` のメモリ記述子へ変換。
3. **バイトコード生成**:
   - ASM を用いて JVM `.class` を出力。
4. **`EXEC SQL` / `CALL PLITDLI` のバインド**:
   - `cobol-db2` および `cobol-ims` のポート呼出しコードを生成。

### タスク 2: `cobol-ims` への `PlitdliBridge` 追加
- `PLITDLI` のシグネチャ（第1引数のパラメータカウント、第2引数のファンクションコード等）をアンパックし、既存の DL/I コアへ渡すブリッジを追加。

### タスク 3: バッチおよびオンラインでの検証
- `BNKSTMT.jcl` を `cobol-job` で起動し、`DATECARD` / `SORTCODE` の読込み、Db2 カーソル走査、`SYSPRINT` への明細出力を確認。
- `IBLOGIN.pli` を `cobol-ims-jms`（RabbitMQ）経由で起動し、IMS キュー電文の取得、`CUSTOMER` 階層 DB 照会、応答送信を確認。

---

## 7. 結論

Bank-of-Z に含まれる PL/I 資産（`BNKSTMT.pli`, `IBLOGIN.pli`）は、データ表現（EBCDIC、COMP-3、2進整数）およびサブシステム連携（Db2 SQL、IMS DL/I）において **COBOL と同一のメインフレーム共通基盤（Language Environment）を前提として設計** されています。

そのため、新規に開発が必要となるのは「フロントエンド（構文解析とバイトコード生成を行う `pli-compiler`）」のみであり、バックエンドである `cobol-runtime`、`cobol-db2`、`cobol-ims`、`cobol-job` は **既存の資産をそのまま共用・再利用できる設計** となっています。

まずは Bank-of-Z で使用されている文法サブセットを対象に `pli-compiler` を段階的に構築することで、COBOL と全く同様に PL/I 資産の JVM 上での直接ビルドおよび完全透過実行を達成することが可能です。
