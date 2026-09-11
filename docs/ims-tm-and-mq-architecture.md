# 解説: IMS TM のアーキテクチャと MQ (IBM MQ / メッセージキュー) との関係

| 項目 | 内容 |
| :--- | :--- |
| **作成日** | 2026-09-11 |
| **対象サブシステム** | IMS TM (Transaction Manager / 旧 IMS/DC) |
| **関連技術** | IBM MQ (WebSphere MQ), OTMA (Open Transaction Manager Access), MPP (Message Processing Program) |
| **関連文書** | [docs/ims-overview-and-support-scope.md](ims-overview-and-support-scope.md), [docs/ims-rdb-storage-engine-report.md](ims-rdb-storage-engine-report.md) |

---

## 1. はじめに：IMS TM の本質は「キューイングエンジン」である

CICS が「端末とスレッド（タスク）が直接対話する RPC/Web サーバ的なモデル」であるのに対し、**IMS TM (旧称 IMS/DC) の本質は「メッセージキューイング駆動型のパイプライン」** です。

1960年代に設計された IMS TM は、世界で最も初期に**「電文の受信（I/O）」と「業務プログラムの実行（CPU 処理）」を完全に非同期・キューで分離**したシステムでした。

```
[クライアント/端末]
       |
       | ① 電文投入 (入力メッセージ)
       v
+-------------------------------------------------------------+
| IMS TM (制御領域: Control Region)                          |
|                                                             |
|   [ 入力メッセージキュー (Input Queue) ]                    |
|     |                                                       |
|     |  ② トランザクションコード (先頭8バイト) を見てキューイング
|     v                                                       |
|   [ 業務別トランザクションキュー (TXA, TXB...) ]            |
+-------------------------------------------------------------+
       |
       | ③ 空いているワーカ領域が電文をデキュー (GU 呼出し)
       v
+-------------------------------------------------------------+
| 従属領域 (MPP: Message Processing Program)                  |
|   COBOL プログラム                                          |
|     - CALL 'CBLTDLI' USING GU, IO-PCB, REQ-DATA.            |
|     - (業務処理 / DB 読込・更新)                            |
|     - CALL 'CBLTDLI' USING ISRT, IO-PCB, RES-DATA.          |
+-------------------------------------------------------------+
       |
       | ④ 応答電文をキューへプット (ISRT 呼出し)
       v
+-------------------------------------------------------------+
| IMS TM (制御領域)                                           |
|   [ 出力メッセージキュー (Output Queue) ]                   |
+-------------------------------------------------------------+
       |
       | ⑤ 端末 / 送信元へ返送
       v
[クライアント/端末]
```

---

## 2. IMS TM と MQ (IBM MQ) の関係

「IMS TM 自体がメッセージキューなら、IBM MQ (WebSphere MQ) とは何が違うのか？ どういう関係なのか？」という点は、多くの方が最初に突き当たる疑問です。

結論から言うと、両者の関係は競合ではなく、**「外部とのエンタープライズ統合を担うのが MQ、ホスト内部で COBOL プログラムを動かすのが IMS TM」** という **相補的な関係（フロントとバックエンド）** にあります。

### 2.1 両者の違いの比較

| 観点 | IMS TM メッセージキュー | IBM MQ (旧 WebSphere MQ) |
| :--- | :--- | :--- |
| **誕生年代** | 1960年代後半〜 | 1990年代前半〜 |
| **主たるスコープ** | **z/OS ホスト「内部」の局所キュー**<br>（IMS 制御領域と MPP 領域間の電文授受） | **システム間・異機種間をまたぐ「広域」キュー**<br>（Windows, Linux, クラウド, ホスト間） |
| **プロトコル** | IMS 独自内部メモリ構造 / 内部データセット | TCP/IP 経由の MQI (Message Queue Interface) |
| **プログラムの責務** | COBOL を起動・スケジューリングする機能（モニタ）そのもの | 単なるメッセージの配送・蓄積（プログラム起動機能はトリガー程度） |
| **COBOL からの呼出し** | **`CALL 'CBLTDLI' USING 'GU  ', IO-PCB...`**<br>※プログラムはキュー製品を意識しない | **`CALL 'MQGET' ...` / `CALL 'MQPUT' ...`**<br>※プログラムが直接 MQ ライブラリを呼ぶ |

### 2.2 実業務での典型的な連携形態：OTMA を介したブリッジ

現代の銀行や大企業のシステムでは、**「Java/オープン系の Web アプリや API ゲートウェイが IBM MQ に電文を投げ、それを IMS TM が拾って COBOL を動かす」** という構成が標準です。

これをつなぐ仕組みが **OTMA (Open Transaction Manager Access)** です。

```
[オープン系 Web/API サーバ]
         |
         | 1. MQPUT (JSON/XML/固定長電文)
         v
+-------------------------------------------------------------+
| IBM MQ (分散環境 / z/OS)                                    |
|   [ 送信キュー (Request Queue) ]                             |
+-------------------------------------------------------------+
         |
         | 2. IMS MQ Bridge (または OTMA クライアント)
         v
+-------------------------------------------------------------+
| z/OS: IMS 制御領域 (IMS Control Region)                     |
|   OTMA インタフェース                                       |
|     |                                                       |
|     | 3. IMS の内部入力キューへ電文を転送                   |
|     v                                                       |
|   [ IMS トランザクションキュー ]                            |
+-------------------------------------------------------------+
         |
         | 4. CALL 'CBLTDLI' (GU) で電文取得
         v
+-------------------------------------------------------------+
| MPP 領域: COBOL 業務プログラム                              |
|   - 業務計算、Db2 や IMS DB の更新                          |
|   - CALL 'CBLTDLI' (ISRT) で応答電文を出力                  |
+-------------------------------------------------------------+
         |
         | 5. OTMA 経由で MQ の応答キュー (Reply Queue) へ戻る
         v
+-------------------------------------------------------------+
| IBM MQ [ 応答キュー ]                                       |
+-------------------------------------------------------------+
         |
         | 6. MQGET で結果取得
         v
[オープン系 Web/API サーバ]
```

#### なぜ COBOL から直接 MQ を叩かないのか？
1. **既存 COBOL コードの資産保護**:
   COBOL 側は 1970 年代から変わらず **`CALL 'CBLTDLI' USING GU, IO-PCB`** と書いているだけです。外部の接続口が 3270 端末から IBM MQ に変わっても、COBOL プログラムを 1 行も書き換える必要がありません。
2. **トランザクション整合性 (2相コミット / 2PC)**:
   OTMA と IBM MQ は z/OS の RRS (Resource Recovery Services) 等と連携し、「MQ から電文を抜く」「IMS DB / Db2 を更新する」「MQ へ応答を返す」を一連のトランザクションとして原子的に同期保証します。

---

## 3. COBOL プログラム（MPP）の実行モデル

IMS TM 上で動くオンラインプログラム（**MPP: Message Processing Program**）には、Web アプリや CICS とは異なる独特のライフサイクルがあります。

### 3.1 「メッセージ駆動ループ」のコード例

COBOL の MPP プログラムは、一度起動すると「キューが空になるまでループして電文を処理し続ける」という書き方をします。

```cobol
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CUSTMPP.
       ENVIRONMENT DIVISION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  DLI-GU              PIC X(4) VALUE 'GU  '.
       01  DLI-ISRT            PIC X(4) VALUE 'ISRT'.
       01  IN-BUFFER.
           05  IN-LL           PIC S9(4) COMP.     *> 電文長 (2バイトバイナリ)
           05  IN-ZZ           PIC S9(4) COMP.     *> IMS 予約領域 (2バイト)
           05  IN-TRANC        PIC X(8).           *> トランザクションコード
           05  IN-DATA         PIC X(500).         *> 業務入力データ
       01  OUT-BUFFER.
           05  OUT-LL          PIC S9(4) COMP.
           05  OUT-ZZ          PIC S9(4) COMP.
           05  OUT-DATA        PIC X(500).

       LINKAGE SECTION.
       01  IO-PCB.
           05  LTERM-NAME      PIC X(8).
           05  FILLER          PIC X(2).
           05  IO-STATUS       PIC X(2).           *> '  '=正常, 'QC'=キューが空
           ...
       01  DB-PCB.
           ...

       PROCEDURE DIVISION USING IO-PCB, DB-PCB.
       MAIN-LOGIC.
           *> 最初の電文を取得
           CALL 'CBLTDLI' USING DLI-GU, IO-PCB, IN-BUFFER.

           *> キューに電文がある限りループ処理する
           PERFORM UNTIL IO-STATUS = 'QC'
               *> 業務ロジック (DB 検索・更新)
               PERFORM PROCESS-BUSINESS-DATA

               *> 応答電文の作成と送信 (ISRT)
               MOVE 504 TO OUT-LL
               MOVE 0   TO OUT-ZZ
               MOVE 'RESPONSE OK...' TO OUT-DATA
               CALL 'CBLTDLI' USING DLI-ISRT, IO-PCB, OUT-BUFFER

               *> 次の電文を取得 (なければ IO-STATUS に 'QC' が返る)
               CALL 'CBLTDLI' USING DLI-GU, IO-PCB, IN-BUFFER
           END-PERFORM.

           GOBACK.
```

### 3.2 ポイント
- **`LLZZ` プレフィックス**:
  IMS TM の電文は、先頭 4 バイトに **電文長（`LL`）** と **フラグ領域（`ZZ`）** が必須です。
- **`QC` ステータスコード**:
  キューが空になると、`CALL 'CBLTDLI'` はエラーではなく **`QC` (Queue Empty)** を返します。これを受けてプログラムはループを抜け、終了（`GOBACK`）します。

---

## 4. `cobol-on-java` における IMS TM エミュレーションの方向性

もし将来、本プロジェクトで IMS TM のオンライン COBOL プログラム（MPP）を JVM 上で動かす場合、アーキテクチャは非常にシンプルかつモダンに設計できます。

### 4.1 キューエンジンとしての親和性
IMS TM の本質が「メッセージキュー」であるため、Java エコシステムとの親和性は抜群です。

```
[ 外部世界: REST API / JMS / IBM MQ / RabbitMQ / Kafka ]
                           |
                           v
+---------------------------------------------------------+
| Spring Boot アプリケーション (リスナー)                  |
|   - @JmsListener / @RabbitListener / REST コントローラ  |
+---------------------------------------------------------+
                           |
                           | Java メモリオブジェクト / Queue
                           v
+---------------------------------------------------------+
| `cobol-ims-tm` (中立トランザクションマネージャ)          |
|   - 仮想 I/O PCB の生成                                  |
|   - LLZZ プレフィックスの自動付与                        |
|   - BlockingQueue<byte[]> によるトランザクションキュー   |
+---------------------------------------------------------+
                           |
                           | PROCEDURE DIVISION USING IO-PCB...
                           v
+---------------------------------------------------------+
| COBOL MPP プログラム (バイトコード実行)                 |
|   - CALL 'CBLTDLI' USING GU   -> キューから poll()      |
|   - CALL 'CBLTDLI' USING ISRT -> 応答キューへ put()     |
+---------------------------------------------------------+
```

### 4.2 CICS 対応との対比
- **CICS の難しさ**:
  画面端末（3270/BMS）、疑似会話（セッション維持）、タスク内制御移行（`LINK`, `XCTL`）、複雑な例外ハンドラ（`HANDLE CONDITION`）など、Web フレームワークとのインピーダンスミスマッチが大きい。
- **IMS TM の容易さ**:
  単なる **「バイト配列をキューから取って（`GU`）、バイト配列をキューへ返す（`ISRT`）」** だけなので、プログラミングモデルとしては極めてクリーンであり、Spring Boot のメッセージング基盤（JMS / Spring Integration）へ容易にマッピングできます。

---

## 5. まとめ

1. **IMS TM の正体**:
   - 画面モニタというよりも、**超高速なオンライントランザクション・メッセージキューイングエンジン**。
2. **IBM MQ との関係**:
   - 競合ではなく **協調関係**。
   - オープン系 ⇔ ホスト間の広域ネットワーク通信を **IBM MQ** が担い、ホスト内の電文キューイングと COBOL 起動を **IMS TM** が担う（**OTMA** がその間をブリッジする）。
   - COBOL プログラム側は MQ を意識せず、1970年代から変わらない **`CALL 'CBLTDLI' USING GU/ISRT, IO-PCB`** を呼ぶだけでよい。
3. **移行時の位置づけ**:
   - CICS に比べて対話状態の管理が少なく、**「純粋なメッセージ駆動（キューイン・キューアウト）」** であるため、Java/Spring 環境（JMS / MQ / REST）への置き換え設計が非常に見通しやすい領域です。
