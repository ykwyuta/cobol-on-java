# IBM ホストにおける IMS (Information Management System) と COBOL 実行系の対応検討

| 項目 | 内容 |
| :--- | :--- |
| 作成日 | 2026-09-11 (2026-09-13 改訂) |
| 対応要件 | **FR-164 のみ**, NFR-032 |
| 検証レベル | **V1 / V0** |
| 改訂理由 | [2026-09-13 批判的レビュー](../reviews/2026-09-13-ims-research-critical-review.md) |

> ### この資料の前提
>
> **1. 要件は FR-164 の 1 本しかない。**その全文は「`CBLTDLI` / `AIBTDLI` のインタフェースを
> 定義し、PCB マスク・SSA・状態コードを扱えるようにする。実際のデータベースへの写像は
> 差し替え可能なアダプタとして提供する。**IMS 連携はフェーズ 4 の目標とし、初期リリースでは
> インタフェース定義のみとする**」である。本資料が第 4 章以降で扱う RDB ストレージ、TM、MFS、
> 運用ツールには**対応する要件がまだ無い** ([P-098](../decisions/provisional.md))。
>
> **2. IMS には外の基準 (oracle) が存在しない。**言語仕様には NIST CCVS85 が、命令の挙動には
> Hercules があるが、DL/I にはどちらも無い。要件 §4.3 は FR-150〜164 を **V1**（仕様準拠、
> 実行による裏取り無し）止まりと明記している。本資料の記述は断りがない限り V1 または V0 で
> あり、「忠実」「完全」「100%」とは書かない ([P-099](../decisions/provisional.md))。
>
> **3. 未検証の断定は `provisional.md` の P-098〜P-110 に登録した。**

---

## 1. IMS とは何か？

### 1.1 誕生と位置づけ
IMS（Information Management System）は、1960年代にアポロ計画を支援するために開発された、**商用としては最初期の階層型データベース管理システム（DBMS）兼オンライントランザクション処理（OLTP）システム**である。

誕生から半世紀以上が経過した現在でも、金融・保険・運輸・官公庁などの高スループット基幹システムで稼働している。

### 1.2 IMS を構成する2つの柱

```
+-------------------------------------------------------------------+
|                           IMS システム                            |
|                                                                   |
|  +-----------------------------+  +----------------------------+  |
|  |       IMS TM (旧 IMS/DC)     |  |          IMS DB            |  |
|  |  (Transaction Manager)      |  |  (Hierarchical Database)   |  |
|  |  - メッセージキューイング    |  |  - 階層型データモデル      |  |
|  |  - 端末・ネットワーク制御   |  |  - DL/I インタフェース     |  |
|  |  - トランザクションスケジューラ |  |  - 高速バッファ            |  |
|  +-----------------------------+  +----------------------------+  |
+-------------------------------------------------------------------+
```

1. **IMS DB (Database Manager)**:
   - **階層型データモデル**を採用したデータベース。関係モデルの「表と外部キー結合」とは異なり、ツリー構造（ルートセグメント、子セグメント、孫セグメント…）としてデータを格納・探索する。
   - 検索・更新インタフェースには SQL ではなく **DL/I (Data Language/I)** という低水準 API を用いる。

2. **IMS TM (Transaction Manager, 旧称 IMS/DC)**:
   - オンライントランザクション処理とメッセージキューイングを担うモニタ。
   - 3270端末、IBM MQ、OTMA 経由のメッセージを受信し、キューへ蓄積する。
   - スケジューラが従属領域（MPP など）で COBOL などの業務プログラムを起動し、メッセージを渡して処理させる。

※ 現場によっては「IMS TM + Db2」「CICS + IMS DB (DL/I)」「バッチ COBOL + IMS DB」といった組み合わせも広く存在する。

---

## 2. CICS や Db2 との対比

本プロジェクトではすでに CICS および Db2 の連携設計（[設計文書 77](../design/77-spring-cics-db2.md)）が進んでいる。

| 観点 | CICS | Db2 | IMS (TM / DB) |
| :--- | :--- | :--- | :--- |
| **主たる分類** | トランザクションモニタ | リレーショナル DBMS | **TM**: トランザクションモニタ<br>**DB**: 階層型 DBMS |
| **データモデル** | （なし：VSAM / Db2 等を利用） | 関係モデル | **木構造（ルート・親・子セグメント）** |
| **COBOL 記述** | `EXEC CICS ... END-EXEC` | `EXEC SQL ... END-EXEC` | **`CALL 'CBLTDLI'`**（または `EXEC DLI`） |
| **トランザクション制御** | `EXEC CICS SYNCPOINT` | `COMMIT` / `ROLLBACK` | `CALL 'CBLTDLI' USING CHKP...` |
| **通信・対話形態** | 疑似会話、BMS画面 | （クライアント/サーバ等） | **メッセージキュー駆動**。会話型は SPA で状態を持ち回る |
| **並行性・分離性** | タスク | UOW / ロック | メッセージ毎にスレッド・従属領域で分離 |

> 疑似会話に対応するのが IMS の**会話型トランザクションと SPA (Scratch Pad Area)** である。
> 「IMS は対話状態が少ない」という見方は SPA を数え落としたものであり、成り立たない
> （[ims-tm-and-mq-architecture.md](ims-tm-and-mq-architecture.md) §3.4）。

---

## 3. COBOL プログラムから見た IMS の仕組み

### 3.1 DL/I 呼び出し (`CALL 'CBLTDLI'`)

一般的な IMS COBOL プログラムでは、プリコンパイラによる構文（`EXEC DLI`）よりも、**`CALL 'CBLTDLI'`** を用いたサブルーチン呼出しが多数を占める。

```cobol
       CALL 'CBLTDLI' USING DLI-GU,
                            CUSTOMER-PCB,
                            CUSTOMER-IO-AREA,
                            CUSTOMER-SSA.
```

引数の構成：

1. **ファンクションコード (4バイト英数字)**:

   | コード | 役割 | 本プロジェクトの扱い |
   | --- | --- | --- |
   | `GU  ` | 特定キーのセグメントを直接取得 | 対応 |
   | `GN  ` | 階層順序に従って次のセグメントを取得 | 対応 |
   | `GNP ` | 現在の親の範囲内で次の子セグメントを取得 | 対応 |
   | `GHU ` / `GHN ` / `GHNP` | 更新前の保持つき取得 (Get Hold) | 対応 |
   | `ISRT` | 新規セグメントの挿入 | 対応 |
   | `REPL` | 既存セグメントの置換 | 対応 |
   | `DLET` | 既存セグメントの削除 | 対応 |
   | `CHKP` | チェックポイント（**コミット + 位置の破棄**） | 対応。位置破棄まで再現する |
   | `XRST` | 再始動 | **L0 (未対応)** ([P-110](../decisions/provisional.md)) |
   | `PURG` | 出力電文の確定 (TM) | 契約に含める必要あり |
   | `CHNG` | 代替 PCB の宛先変更 (TM) | 契約に含める必要あり |

   ※ IMS TM の電文送受信も `GU` / `GN` / `ISRT` を I/O PCB に対して発行する。

2. **PCB (Program Communication Block)**: アクセス対象の DB や I/O パスを表す制御ブロック。
3. **I/O 領域**: セグメントデータや入出力電文を格納する COBOL の集団項目。
4. **SSA (Segment Search Argument, 省略可・複数可)**: 探索条件。文法は [ims-dli-complex-semantics-report.md](ims-dli-complex-semantics-report.md) §3 を参照。

### 3.2 PSB と PCB の受け渡し

- **PSB (Program Specification Block)**: プログラムがアクセスを許可されている DB や電文キューの定義。
- **PCB (Program Communication Block)**: PSB の中に定義される個々の通信ブロック（I/O PCB, DB PCB）。

COBOL プログラムの `PROCEDURE DIVISION USING ...` の引数として、ランタイムからこれら PCB のポインタが渡される。

```cobol
       LINKAGE SECTION.
       01  IO-PCB.
           05  IO-LTERM-NAME     PIC X(8).
           05  FILLER            PIC X(2).
           05  IO-STATUS-CODE    PIC X(2).   *> '  '=成功, 'QC'=キューが空 等
           ...
       01  DB-PCB.
           05  DB-DBD-NAME       PIC X(8).
           05  DB-SEG-LEVEL      PIC X(2).
           05  DB-STATUS-CODE    PIC X(2).   *> '  ', 'GA', 'GE', 'GB', 'II' 等
           05  DB-PROC-OPTIONS   PIC X(4).
           ...

       PROCEDURE DIVISION USING IO-PCB, DB-PCB.
       MAIN-PROCESS.
           CALL 'CBLTDLI' USING DLI-GU, IO-PCB, INPUT-MESSAGE.
           ...
           CALL 'CBLTDLI' USING DLI-GU, DB-PCB, CUSTOMER-RECORD, CUSTOMER-SSA.
           IF DB-STATUS-CODE = '  '
               ...
```

### 3.3 DBD と PSB は「解析すべき入力」である

`CALL 'CBLTDLI'` を受けるだけでは動かない。**DBD（データベース記述）と PSB の定義が無いと、SSA の項目修飾もシーケンスフィールドの切り出しも `PROCOPT` の判定もできない。**DBDGEN / PSBGEN はアセンブラマクロであり、その解析は本対応の最大の作業項目になる（[設計 78](../design/78-ims-subsystem.md) §2.1）。

---

## 4. `cobol-on-java` における「IMS 対応」とは何か？

### 4.1 対応形態の分類

| 形態 | 説明 | Java 移行時の位置づけ |
| :--- | :--- | :--- |
| **A. IMS バッチ / BMP** | 画面・端末通信を行わず、バッチジョブから IMS DB を読み書きするプログラム。 | **最も需要が高く、移行の第一歩になりやすい。**JCL 側の入口は `PGM=DFSRRC00,PARM='DLI,プログラム名,PSB名'` であり、これを `cobol-job` から起動できるようにするのが第 1 増分である（[設計 78](../design/78-ims-subsystem.md) §7.1）。 |
| **B. IMS TM オンライン (MPP)** | 端末からの電文をキューイング経由で受信し、電文を返却するオンライン処理。 | キューイングを Spring Boot 等でラップし、電文を `IO-PCB` へ注入する。会話型は SPA の持ち回りが要る。 |
| **C. CICS + DL/I** | CICS トランザクションの中から IMS DB を呼び出す形態。 | `cobol-cics` のコンテキスト内で `CBLTDLI` を呼び出す。 |

### 4.2 具体的に「何ができるようになること」を意味するか

1. **`CALL 'CBLTDLI'` を含む COBOL プログラムをソース修正なしにコンパイル・実行できる**
   - コンパイラやランタイムが `CBLTDLI` / `AIBTDLI` の呼び出しを解決する。
2. **階層構造のセグメントの読み書きができる**
   - `GU`, `GN`, `GNP`, `ISRT`, `REPL`, `DLET` に対し、状態コードが返却され、バッファに生バイトが格納される。
3. **DBD / PSB を解析し、PCB を構築してエントリポイントへ束縛する**
4. **データストアの選択肢**
   - RDB 上に階層データを格納してエミュレートする（[ims-rdb-storage-engine-report.md](ims-rdb-storage-engine-report.md)）。
5. **トランザクションの統合**
   - `CHKP` やエラー時の ABEND が、JDBC トランザクションと連動する。

### 4.3 何ができないか（対応範囲の線引き）

要件 §4.1 の原則に従い、**L0（未対応）は必ず検出して診断を出す。黙って近い結果を返さない。**

| 機能 | 扱い | 理由 |
| --- | --- | --- |
| **HDAM / PHDAM の全件走査順序** | 再現しない（診断を出す） | ルートの物理順序をランダマイジングモジュールが決めるため、キー順にならない ([P-102](../decisions/provisional.md)) |
| **二次索引 (`PROCSEQ=`)** | **L0** | 別のフィールドで階層順序そのものを差し替えるため、単一順序モデルの前提を壊す ([P-103](../decisions/provisional.md)) |
| **論理関係 (`LCHILD` / 連結セグメント)** | **L0** | データが木でなく網になる |
| **GSAM** | **L0** | `CHKP` / `XRST` と併せて別途設計 |
| **Fast Path (DEDB / MSDB)** | **L0** | `FLD` コール等、別の API 群 |
| **symbolic `CHKP` の領域退避 / `XRST`** | **L0** | ([P-110](../decisions/provisional.md)) |

---

## 5. アーキテクチャ設計案

[ADR-0007](../decisions/0007-framework-neutral-subsystem-ports.md)（フレームワーク非依存の中立ポート）の思想に則り、IMS サブシステムも中立契約として設計する。

```
[ COBOL プログラム (生成バイトコード) / cobol-job (DFSRRC00) ]
         |
         | CALL 'CBLTDLI' USING FUNC, PCB, IO-AREA, SSA...
         v
+-------------------------------------------------------------+
| `cobol-ims` (中立コアモジュール)                            |
|  - DbdParser / PsbParser / ImsDatabaseCatalog  <-- 前提     |
|  - CbltdliBridge / AibtdliBridge                            |
|  - SsaParser: SSA とコマンドコードのパース                  |
|  - PcbStateManager: カレント位置・親境界の管理              |
|  - DliDatabasePort (抽象ポート)                             |
|  - ImsQueuePort (抽象ポート)                                |
+-------------------------------------------------------------+
         |                                |
         v                                v
[ アダプタ: RDB ]                   [ アダプタ: In-Memory ]
```

### 5.1 主要コンポーネント

1. **`DbdParser` / `PsbParser`**: DBDGEN / PSBGEN を解析し、中立メタモデルを構築する。他のすべてがこれに依存する。
2. **`CbltdliBridge`**: 第 2 引数が I/O PCB か DB PCB かを判定してディスパッチする。
3. **`SsaParser`**: 無限定 SSA、複数 SSA、ブール結合、関係演算子 6 種、コマンドコードを解析する。**対応しない形は `AJ` で誤魔化さず、対応していないと分かる診断を出す。**
4. **`PcbStateManager`**: カレント位置と親境界を PCB インスタンスごとに独立して保持する。
5. **状態コードの再現**: `GA` / `GK` / `GP` / `AM` を含む（[設計 78](../design/78-ims-subsystem.md) §3.7）。

---

## 6. 対応検討における課題・注意点

1. **DBD / PSB の解析が前提になる**（§3.3）。最大の作業項目である。
2. **アクセス方式でルート順序が変わる**（§4.3）。HDAM は再現できない。
3. **階層走査順序を RDB 上で保証する仕組み**が要る。文字列パスによる方式には、兄弟順序・ギャップ枯渇・照合順序という 3 つの弱点がある（[ims-rdb-storage-engine-report.md](ims-rdb-storage-engine-report.md) §5.2、[P-101](../decisions/provisional.md)）。
4. **可変長セグメント・非正規化データ**: `OCCURS DEPENDING ON` や同一セグメント内の複数レイアウトがあり、バイト配列レベルの柔軟性が求められる。
5. **`CHKP` は位置を破棄する**（[P-110](../decisions/provisional.md)）。コミットとしてだけ扱うと `GN` ループの結果が変わる。
6. **まずはバッチ (IMS DB のみ) からスコープを絞る**のが現実的である。IMS TM は MFS と SPA が絡むため、最初のスコープは「**バッチ JCL からの `DFSRRC00` 起動と `CBLTDLI` (IMS DB) 呼出し**」とする。

---

## 7. まとめ

- **IMS** は、**階層型データベース (IMS DB)** と **トランザクションモニタ (IMS TM)** の 2 つの顔を持つ。
- COBOL からは主に **`CALL 'CBLTDLI'`** を介して利用される。
- `cobol-on-java` における IMS 対応の目標は、**既存の COBOL コードを修正せずに `CALL 'CBLTDLI'` が期待どおりのデータと状態コードを返すこと**である。ただし現時点でこれは目標であって、達成の裏付け（oracle）は無い。
- CICS や Db2 と同様に、中立ポート（`cobol-ims`）を切り出し、永続化エンジンをプラグイン可能にするアーキテクチャが適合する。
- **要件は FR-164 の 1 本しかなく、それは「初期リリースではインタフェース定義のみ」と定めている。**この資料の第 4 章以降を実装するには、まず要件を書く必要がある ([P-098](../decisions/provisional.md))。
