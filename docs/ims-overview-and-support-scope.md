# IBM ホストにおける IMS (Information Management System) と COBOL 実行系の対応検討

本書は、IBM メインフレーム（z/OS）上で稼働する **IMS（Information Management System）** の全体像と、本プロジェクト（`cobol-on-java`）において「IMS 対応」を行うことが何を意味し、どのようなアーキテクチャや機能実装が必要になるかを整理した入門・検討資料です。

---

## 1. IMS とは何か？

### 1.1 誕生と位置づけ
IMS（Information Management System）は、1966年にアポロ計画（サターンV型ロケットの部品・工程管理）を支援するために IBM、NAA（North American Aviation）、Caterpillar 社によって共同開発された、**世界最初期の商用階層型データベース管理システム（DBMS）兼オンライントランザクション処理（OLTP）システム**です。

誕生から半世紀以上が経過した現在でも、世界中のメガバンク、保険会社、航空会社、官公庁などのミッションクリティカルな超高スループット基幹システムで現役稼働しています。

### 1.2 IMS を構成する2つの柱
IMS は単一の機能ではなく、大きく分けて次の2つのサブシステムから構成されます。

```
+-------------------------------------------------------------------+
|                           IMS システム                            |
|                                                                   |
|  +-----------------------------+  +----------------------------+  |
|  |       IMS TM (旧 IMS/DC)     |  |          IMS DB            |  |
|  |  (Transaction Manager)      |  |  (Hierarchical Database)   |  |
|  |  - メッセージキューイング    |  |  - 階層型データモデル      |  |
|  |  - 端末・ネットワーク制御   |  |  - DL/I インタフェース     |  |
|  |  - トランザクションスケジューラ |  |  - 高速バッファ / 高性能    |  |
|  +-----------------------------+  +----------------------------+  |
+-------------------------------------------------------------------+
```

1. **IMS DB (Database Manager)**:
   - **階層型データモデル (Hierarchical Model)** を採用したデータベース。
   - 関係モデル（RDB / SQL）の「表と外部キー結合」とは異なり、ツリー構造（ルートセグメント親、子セグメント、孫セグメント...）としてデータを格納・探索します。
   - 検索・更新インタフェースには SQL ではなく **DL/I (Data Language/I)** という低水準 API を用います。

2. **IMS TM (Transaction Manager, 旧称 IMS/DC)**:
   - オンライントランザクション処理（OLTP）とメッセージキューイングを担うモニタ。
   - 3270端末、WebSphere MQ、OTMA (Open Transaction Manager Access) 経由のメッセージを受信し、キューへ蓄積。
   - スケジューラが従属領域（Dependent Region: MPP など）で COBOL などの業務プログラムを立ち上げ、メッセージを渡して処理させます。

※ 現場によっては **「IMS TM + Db2 (リレーショナル)」** の組み合わせや、逆に **「CICS + IMS DB (DL/I)」**、あるいは純粋なバッチジョブから **「バッチ COBOL + IMS DB」** でアクセスする形態も広く存在します。

---

## 2. CICS や Db2 との対比

本プロジェクトではすでに CICS および Db2 の連携設計（[設計文書 77](file:///d:/workspace/cobol-on-java/docs/design/77-spring-cics-db2.md)）が進んでいます。これらと比較すると IMS の位置づけが明確になります。

| 観点 | CICS | Db2 | IMS (TM / DB) |
| :--- | :--- | :--- | :--- |
| **主たる分類** | トランザクションモニタ (OLTP) | リレーショナル DBMS (RDBMS) | **TM**: トランザクションモニタ<br>**DB**: 階層型 DBMS |
| **データモデル** | （なし：VSAM / Db2 等を利用） | 関係モデル（テーブル、列、行） | **木構造（ルート・親・子セグメント）** |
| **COBOL 記述** | `EXEC CICS ... END-EXEC` | `EXEC SQL ... END-EXEC` | **`CALL 'CBLTDLI'`**（または `EXEC DLI`） |
| **トランザクション制御** | `EXEC CICS SYNCPOINT` | `COMMIT` / `ROLLBACK` | `CALL 'CBLTDLI' USING CHKP...`（チェックポイント） |
| **通信・対話形態** | 疑似会話（Pseudo-Conversational）、BMS画面 | （クライアント/サーバ等） | **メッセージキュー駆動**（Get Unique でキューから要求電文を取得、Insert で応答電文をキューへ送信） |
| **並行性・分離性** | タスク（CICS タスク内で複数プログラムを LINK/XCTL） | UOW (Unit of Work) / ロック | メッセージ毎にスレッド・従属領域で分離 |

---

## 3. COBOL プログラムから見た IMS の仕組み

COBOL から IMS を利用する場合、主に **「DL/I (Data Language/I)」** と **「PCB / PSB」** の理解が必須となります。

### 3.1 DL/I 呼び出し (`CALL 'CBLTDLI'`)
一般的な IMS COBOL プログラムでは、プリコンパイラによる構文（`EXEC DLI`）よりも、伝統的な **`CALL 'CBLTDLI'`**（COBOL-to-DLI 呼出しルーチン）を用いたサブルーチン呼出しが圧倒的多数を占めます。

```cobol
       CALL 'CBLTDLI' USING DLI-GU,
                            CUSTOMER-PCB,
                            CUSTOMER-IO-AREA,
                            CUSTOMER-SSA.
```

引数の構成：
1. **ファンクションコード (4バイト英数字)**:
   - `GU  ` (Get Unique): 特定キーのセグメントを直接取得（ランダム読込）
   - `GN  ` (Get Next): 階層順序に従って次のセグメントを取得（シーケンシャル読込）
   - `GNP ` (Get Next in Parent): 現在の親の範囲内で次の子セグメントを取得
   - `GHU `, `GHN `, `GHNP `: 更新前の保持つき取得 (Get Hold ...)
   - `ISRT`: 新規セグメントの挿入 (Insert)
   - `REPL`: 既存セグメントの置換 (Replace)
   - `DLET`: 既存セグメントの削除 (Delete)
   - `CHKP`: チェックポイント（コミットとリスタート位置の記録）
   - ※ IMS TM の電文送受信も同じ `GU` / `ISRT` を I/O PCB に対して発行する。
2. **PCB (Program Communication Block)**:
   - アクセス対象の DB や I/O パスを表す制御ブロック。
3. **I/O 領域 (受取/送信用バッファ)**:
   - セグメントデータや入出力電文を格納する COBOL の集団項目。
4. **SSA (Segment Search Argument, 省略可・複数可)**:
   - 探索条件（例: `CUSTROOT(CUSTID  = 12345678)`）を指定する文字列。

### 3.2 PSB と PCB の受け渡し
- **PSB (Program Specification Block)**: プログラムがアクセスを許可されている DB や電文キューの定義（カタログ）。
- **PCB (Program Communication Block)**: PSB の中に定義される個々の通信ブロック（I/O PCB, DB PCB）。

COBOL プログラムの `PROCEDURE DIVISION USING ...` の引数として、ランタイム（IMS）からこれら PCB のポインタ（アドレス）が渡されます。

```cobol
       LINKAGE SECTION.
       01  IO-PCB.
           05  IO-LTERM-NAME     PIC X(8).
           05  FILLER            PIC X(2).
           05  IO-STATUS-CODE    PIC X(2).   *> '  '(ブランク)=成功, 'GE'=Not Found 等
           ...
       01  DB-PCB.
           05  DB-DBD-NAME       PIC X(8).
           05  DB-SEG-LEVEL      PIC X(2).
           05  DB-STATUS-CODE    PIC X(2).   *> 状態コード ('  ', 'GE', 'II', etc.)
           05  DB-PROC-OPTIONS   PIC X(4).
           ...

       PROCEDURE DIVISION USING IO-PCB, DB-PCB.
       MAIN-PROCESS.
           *> I/O PCB を通じて電文を取得 (IMS TM)
           CALL 'CBLTDLI' USING DLI-GU, IO-PCB, INPUT-MESSAGE.
           ...
           *> DB PCB を通じて階層DBを検索 (IMS DB)
           CALL 'CBLTDLI' USING DLI-GU, DB-PCB, CUSTOMER-RECORD, CUSTOMER-SSA.
           IF DB-STATUS-CODE = '  '
               ...
```

---

## 4. `cobol-on-java` における「IMS 対応」とは何か？

「IMS 対応」と一言で言っても、移行対象資産の形態や利用している機能によって必要なスコープが大きく異なります。

### 4.1 対応形態の分類

| 形態 | 説明 | Java 移行時の位置づけ |
| :--- | :--- | :--- |
| **A. IMS バッチ / BMP**<br>*(Batch / Batch Message Processing)* | 画面・端末通信を行わず、バッチジョブから IMS DB を読み書きするプログラム。 | **最も需要が高く、移行の第一歩になりやすい。** JCL / `cobol-job` と連携し、階層DBアクセスをエミュレートする。 |
| **B. IMS TM オンライン**<br>*(MPP: Message Processing Program)* | 端末からの電文をキューイング経由で受信し、電文を返却するオンライン処理。 | キューイングを Spring Boot (REST/MQ) 等でラップし、電文を COBOL の `IO-PCB` へ注入する。 |
| **C. CICS + DL/I** | CICS トランザクションの中から IMS DB (DL/I) を呼び出す形態。 | `cobol-cics` のコンテキスト内で `CBLTDLI` を呼び出す。 |

### 4.2 具体的に「何ができるようになること」を意味するか

`cobol-on-java` において IMS 対応が実現した場合、以下が可能になります：

1. **`CALL 'CBLTDLI'` を含む COBOL プログラムが無修正でコンパイル・実行できる**:
   - コンパイラやランタイムが `CBLTDLI`（および C 形式インタフェース `AIBTDLI` 等）のサブルーチン呼び出しを解決できる。
2. **階層構造のセグメントの読み書き（CRUD）ができる**:
   - `GU`, `GN`, `GNP`, `ISRT`, `REPL`, `DLET` の呼出しに対し、ステータスコード（正常 `'  '`, 該当なし `'GE'`, 重複キー `'II'` など）が正しく返却され、バッファに EBCDIC/バイナリデータが格納される。
3. **PSB/PCB のエミュレーションとエントリポイント引数バインディング**:
   - COBOL の `PROCEDURE DIVISION USING` に渡す PCB ポインタ・メモリ構造をランタイムが構築・注入できる。
4. **データストアの選択肢（バックエンド永続化）**:
   - 本物の IMS DB を JVM 上に再現することは困難なため、通常は **RDB（PostgreSQL, MySQL, Db2 等）上のテーブル**、または **NoSQL / ドキュメントDB / JSON** に階層データをマッピングして透過的にエミュレートする。
5. **トランザクション（UOW）の統合**:
   - `CHKP`（チェックポイント）やエラー時の ABEND / ロールバックが、Spring 等の JDBC トランザクションマネージャと連動する。

---

## 5. アーキテクチャ設計案（どのように実現するか）

本プロジェクトの [ADR-0007](decisions/0007-framework-neutral-subsystem-ports.md)（フレームワーク非依存の中立ポート）の思想に則り、IMS サブシステムも中立契約として設計するのが自然です。

```
[ COBOL プログラム (生成バイトコード) ]
         |
         | CALL 'CBLTDLI' USING FUNC, PCB, IO-AREA, SSA...
         v
+-------------------------------------------------------------+
| `cobol-ims` (中立コアモジュール)                            |
|  - CbltdliBridge / AibtdliBridge                            |
|  - DliCommand: GU, GN, GNP, ISRT, REPL, DLET, CHKP 解析     |
|  - SsaParser: セグメント探索引数 (SSA) のパース              |
|  - PcbManager: PCB 状態管理 (Status Code '  ', 'GE', etc.)   |
|  - DliDatabasePort (抽象ポート): 階層セグメントのナビゲーション |
|  - DliMessageQueuePort (抽象ポート): TM 電文入出力          |
+-------------------------------------------------------------+
         |                                |
         v                                v
[ アダプタ: RDB (Relational) ]     [ アダプタ: In-Memory / File ]
- 各セグメントを RDB テーブルに     - 単体テスト・検証用モック
  マッピング (親ID, 順序キー)        - 固定データセットからの読込
- SQL 自動生成 (SELECT/INSERT...)
```

### 5.1 主要コンポーネント

1. **`CbltdliRouter` / `CbltdliBridge`**:
   - `CALL 'CBLTDLI'` の呼出しを受け取り、第2引数が「I/O PCB」か「DB PCB」かを判定（PCB の型定義やフラグから識別）。
   - I/O PCB なら `DliMessageQueuePort` へ、DB PCB なら `DliDatabasePort` へディスパッチ。
2. **`SsaParser` (Segment Search Argument)**:
   - `SEGNAME*(FIELD  = VALUE)` といった文字列形式の SSA を解析し、対象セグメント名・比較演算子・比較値を構造化。
3. **`DliDatabaseEngine` (階層ツリーナビゲータ)**:
   - 階層型 DB のカレント位置（Current Position / Parentage）を保持。
   - `GN`（次へ）や `GNP`（親の配下で次へ）を正確にトラバースするステートマシン。
4. **ステータスコードの忠実な再現**:
   - COBOL 業務ロジックはステータスコード（`STATUS-CODE = 'GE'` 等）で分岐しているため、メインフレームと全く同一のステータスコードを返却することが極めて重要。

---

## 6. 対応検討における課題・注意点

1. **階層構造から RDB へのスキーマ設計**:
   - IMS DB の DBD (Database Description) を解析し、親セグメントの主キーを子セグメントに外部キーとして持たせる正規化マッピングが必要になります。
2. **階層ポインタ・走査順序（Hierarchical Sequence）の保証**:
   - IMS は物理的・論理的な階層順序（深さ優先探索）でデータを走査します。RDB 上でエミュレートする際、`ORDER BY` などの並び順を完全に一致させる必要があります。
3. **可変長セグメント・非正規化データ**:
   - COBOL の `OCCURS DEPENDING ON` や、同一セグメントタイプ内で複数のレコードレイアウトを同居させているケースがあり、バイト配列レベルの柔軟性が求められます。
4. **まずはバッチ (IMS DB のみ) からスコープを絞るのが現実的**:
   - IMS TM (オンライン) は電文端末制御や MFS (Message Format Service) などの複雑な画面制御が絡むため、まずは **「バッチ JCL 実行時における `CBLTDLI` (IMS DB) 呼出し」** を最初のスコープ（MVP）として検討するのが推奨されます。

---

## 7. まとめ

- **IMS** は、**階層型データベース (IMS DB)** と **トランザクションモニタ (IMS TM)** の2つの顔を持つメインフレームの超重要サブシステムです。
- COBOL からは主に **`CALL 'CBLTDLI'`** という手続き呼出しを介して利用されます。
- `cobol-on-java` における「IMS 対応」のゴールは、**既存の COBOL コードに手を加えることなく、JVM 上の RDB やメモリモデルと連動して `CALL 'CBLTDLI'` が期待通りのデータとステータスコードを返すこと** です。
- CICS や Db2 と同様に、中立ポート（`cobol-ims`）を切り出し、永続化エンジン（RDB 等）をプラグイン可能にするアーキテクチャが適合します。
