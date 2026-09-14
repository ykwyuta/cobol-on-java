# 解説: IMS TM のアーキテクチャと MQ (IBM MQ / メッセージキュー) との関係

| 項目 | 内容 |
| :--- | :--- |
| **作成日** | 2026-09-11 (2026-09-13 改訂) |
| **対象サブシステム** | IMS TM (Transaction Manager / 旧 IMS/DC) |
| **関連技術** | IBM MQ, OTMA, MPP, SPA (Scratch Pad Area) |
| **対応要件** | **無し** ([P-098](../decisions/provisional.md)) |
| **検証レベル** | **V1 / V0** |
| **関連文書** | [ims-overview-and-support-scope.md](ims-overview-and-support-scope.md), [ims-tm-rabbitmq-jms-proposal.md](ims-tm-rabbitmq-jms-proposal.md) |
| **改訂理由** | [2026-09-13 批判的レビュー](../reviews/2026-09-13-ims-research-critical-review.md) の IR-24（SPA の欠落）, IR-25 |

> ### 改訂の要点
>
> 改訂前の本書は「IMS TM は CICS に比べて対話状態の管理が少なく、Java/Spring 環境への
> 置き換え設計が非常に見通しやすい」と結論していた。**これは会話型トランザクションと
> SPA (Scratch Pad Area) を数え落とした結論であり、成り立たない。**§3.4 と §4.3 を追加し、
> 結論を書き直した。
>
> あわせて、電文が 1 セグメントとは限らないこと（`GN` / `PURG`）、宛先が 1 つとは限らない
> こと（`CHNG` / 代替 PCB）を §3.5 に追加した。

---

## 1. はじめに：IMS TM の本質は「キューイングエンジン」である

CICS が「端末とスレッド（タスク）が直接対話するモデル」であるのに対し、**IMS TM の本質は「メッセージキューイング駆動型のパイプライン」**である。

1960年代に設計された IMS TM は、**「電文の受信」と「業務プログラムの実行」を非同期・キューで分離**した初期のシステムだった。

```
[クライアント/端末]
       |
       | (1) 電文投入 (入力メッセージ)
       v
+-------------------------------------------------------------+
| IMS TM (制御領域: Control Region)                          |
|   [ 入力メッセージキュー ]                                  |
|     |  (2) トランザクションコードを見てキューイング         |
|     v                                                       |
|   [ 業務別トランザクションキュー (TXA, TXB...) ]            |
+-------------------------------------------------------------+
       |
       | (3) 空いているワーカ領域が電文をデキュー (GU 呼出し)
       v
+-------------------------------------------------------------+
| 従属領域 (MPP: Message Processing Program)                  |
|   COBOL プログラム                                          |
|     - CALL 'CBLTDLI' USING GU, IO-PCB, REQ-DATA.            |
|     - (業務処理 / DB 読込・更新)                            |
|     - CALL 'CBLTDLI' USING ISRT, IO-PCB, RES-DATA.          |
+-------------------------------------------------------------+
       |
       | (4) 応答電文をキューへプット (ISRT 呼出し)
       v
+-------------------------------------------------------------+
| IMS TM (制御領域)  [ 出力メッセージキュー ]                 |
+-------------------------------------------------------------+
       |
       | (5) 端末 / 送信元へ返送
       v
[クライアント/端末]
```

---

## 2. IMS TM と MQ (IBM MQ) の関係

「IMS TM 自体がメッセージキューなら、IBM MQ とは何が違うのか」という点は最初に突き当たる疑問である。

両者の関係は競合ではなく、**「外部とのエンタープライズ統合を担うのが MQ、ホスト内部で COBOL プログラムを動かすのが IMS TM」**という相補的な関係にある。

### 2.1 両者の違い

| 観点 | IMS TM メッセージキュー | IBM MQ |
| :--- | :--- | :--- |
| **主たるスコープ** | **z/OS ホスト「内部」の局所キュー** | **システム間・異機種間をまたぐ「広域」キュー** |
| **プロトコル** | IMS 独自内部構造 / 内部データセット | TCP/IP 経由の MQI |
| **プログラムの責務** | COBOL を起動・スケジューリングするモニタそのもの | メッセージの配送・蓄積 |
| **COBOL からの呼出し** | **`CALL 'CBLTDLI' USING 'GU  ', IO-PCB...`**<br>※プログラムはキュー製品を意識しない | **`CALL 'MQGET' ...` / `CALL 'MQPUT' ...`**<br>※プログラムが直接 MQ ライブラリを呼ぶ |

### 2.2 実業務での典型的な連携形態：OTMA を介したブリッジ

現代のシステムでは「オープン系の Web アプリや API ゲートウェイが IBM MQ に電文を投げ、それを IMS TM が拾って COBOL を動かす」という構成が用いられる。これをつなぐ仕組みが **OTMA (Open Transaction Manager Access)** である。

```
[オープン系 Web/API サーバ]
         |  1. MQPUT
         v
[ IBM MQ 送信キュー ]
         |  2. IMS MQ Bridge / OTMA クライアント
         v
[ z/OS: IMS 制御領域 → OTMA → IMS トランザクションキュー ]
         |  3. CALL 'CBLTDLI' (GU) で電文取得
         v
[ MPP 領域: COBOL 業務プログラム → ISRT で応答 ]
         |  4. OTMA 経由で MQ の応答キューへ
         v
[ IBM MQ 応答キュー ] --5. MQGET--> [オープン系 Web/API サーバ]
```

#### なぜ COBOL から直接 MQ を叩かないのか？

1. **既存 COBOL コードの資産保護**: COBOL 側は `CALL 'CBLTDLI' USING GU, IO-PCB` と書いているだけである。外部の接続口が 3270 端末から IBM MQ に変わっても、COBOL プログラムを書き換える必要がない。
2. **トランザクション整合性（2 相コミット）**: OTMA と IBM MQ は z/OS の RRS (Resource Recovery Services) 等と連携し、「MQ から電文を抜く」「IMS DB / Db2 を更新する」「MQ へ応答を返す」を一連のトランザクションとして原子的に同期保証する。

> **この 2 番目の性質が、JVM 上での再現において最も高くつく。**オープン環境で同じ原子性を
> 得るには XA (2 相コミット) が要る。本プロジェクトはこれを採らず、at-least-once + 冪等化で
> 代替する。**その差は実機との差として明記する**
> （[ims-tm-rabbitmq-jms-proposal.md](ims-tm-rabbitmq-jms-proposal.md) §5.3、[P-104](../decisions/provisional.md)）。

---

## 3. COBOL プログラム（MPP）の実行モデル

### 3.1 「メッセージ駆動ループ」のコード例（非会話型）

```cobol
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CUSTMPP.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  DLI-GU              PIC X(4) VALUE 'GU  '.
       01  DLI-ISRT            PIC X(4) VALUE 'ISRT'.
       01  IN-BUFFER.
           05  IN-LL           PIC S9(4) COMP.     *> 電文長 (2バイトバイナリ)
           05  IN-ZZ           PIC S9(4) COMP.     *> IMS 予約領域 (2バイト)
           05  IN-TRANC        PIC X(8).           *> トランザクションコード
           05  IN-DATA         PIC X(500).
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

       PROCEDURE DIVISION USING IO-PCB, DB-PCB.
       MAIN-LOGIC.
           CALL 'CBLTDLI' USING DLI-GU, IO-PCB, IN-BUFFER.
           PERFORM UNTIL IO-STATUS = 'QC'
               PERFORM PROCESS-BUSINESS-DATA
               MOVE 504 TO OUT-LL
               MOVE 0   TO OUT-ZZ
               MOVE 'RESPONSE OK...' TO OUT-DATA
               CALL 'CBLTDLI' USING DLI-ISRT, IO-PCB, OUT-BUFFER
               CALL 'CBLTDLI' USING DLI-GU, IO-PCB, IN-BUFFER
           END-PERFORM.
           GOBACK.
```

### 3.2 ポイント

- **`LLZZ` プレフィックス**: IMS TM の電文は、先頭 4 バイトに電文長 (`LL`) とフラグ領域 (`ZZ`) を持つ。
- **`QC` ステータスコード**: キューが空になると `QC` が返る。プログラムはループを抜けて終了する。

### 3.3 この例は最も単純な形である

**上のコードは「非会話型・1 セグメント入力・1 セグメント出力・単一宛先」という最も単純な形**であり、これだけを前提に設計すると次の 3 つが漏れる。

### 3.4 会話型トランザクションと SPA（改訂で追加）

IMS には**会話型トランザクション**があり、その状態は **SPA (Scratch Pad Area)** で持ち回る。CICS の疑似会話に対応するのがこれである。

- **SPA はメッセージの第 1 セグメントとしてプログラムへ渡される。**プログラムは処理後に `ISRT` で SPA を書き戻す。次の入力では更新された SPA が渡ってくる。
- したがって、**会話型では入出力バッファのレイアウトが §3.1 の例と変わる。**先頭に SPA が来る。
- CICS の COMMAREA と同じ課題が発生する。すなわち**永続化、サイズ、有効期限、クラッシュ回復、会話の打ち切り**である。

`cobol-on-java` では [ADR-0008](../decisions/0008-cics-on-spring-mvc-and-session.md) の会話状態ストアと共通化できるかを検討する。

### 3.5 電文は 1 セグメントとは限らず、宛先も 1 つとは限らない（改訂で追加）

| 機構 | 内容 | 「1 往復」モデルで起きること |
| --- | --- | --- |
| **I/O PCB への `GN`** | 入力電文の 2 セグメント目以降を取る | 2 セグメント目以降が読まれない |
| **`PURG`** | 複数の `ISRT` で組み立てた出力電文を確定する | 1 セグメント目だけ送られる |
| **`CHNG`** | 代替 PCB の宛先を差し替える | 別の宛先へ送れない |
| **代替 PCB (ALTPCB) への `ISRT`** | 別の LTERM や別トランザクションへ送る | 同上 |

**これらを扱わないと、該当する資産は「動かない」ではなく「部分的に正しく見える壊れ方」をする。**中立ポートの契約に含めるか、明示的に L0 として診断する（[設計 78](../design/78-ims-subsystem.md) §4.1）。

---

## 4. `cobol-on-java` における IMS TM エミュレーションの方向性

### 4.1 キューエンジンとしての親和性

IMS TM の本質がメッセージキューであるため、Java エコシステムとの対応付けは素直である。

```
[ 外部世界: REST API / JMS / IBM MQ / RabbitMQ / Kafka ]
                           |
                           v
+---------------------------------------------------------+
| Spring Boot アプリケーション (リスナー)                  |
+---------------------------------------------------------+
                           |
                           v
+---------------------------------------------------------+
| `cobol-ims-tm` (中立トランザクションマネージャ)          |
|   - 仮想 I/O PCB の生成                                  |
|   - LLZZ プレフィックスの取り扱い                        |
|   - SPA の持ち回り (会話型)                              |
|   - inbox による冪等化 (P-104)                           |
+---------------------------------------------------------+
                           |
                           v
+---------------------------------------------------------+
| COBOL MPP プログラム (バイトコード実行)                 |
|   - CALL 'CBLTDLI' USING GU   -> キューから受信         |
|   - CALL 'CBLTDLI' USING ISRT -> 応答キューへ送信        |
+---------------------------------------------------------+
```

### 4.2 CICS 対応との対比（改訂で書き直し）

改訂前の本節は「CICS は難しく、IMS TM は容易」と結論していた。**SPA を数に入れると、この対比は成り立たない。**

| 論点 | CICS | IMS TM |
| :--- | :--- | :--- |
| 画面制御 | BMS。プログラムが `SEND MAP` で能動的に指示する | **MFS。プログラムに画面操作コマンドが現れず、制御領域が背後で変換する。**論理ページングもある（[ims-mfs-bms-comparison-and-web-ui-proposal.md](ims-mfs-bms-comparison-and-web-ui-proposal.md)） |
| 対話状態 | 疑似会話。COMMAREA / チャネル | **会話型トランザクション。SPA。**課題は COMMAREA とほぼ同じ |
| 制御移行 | `LINK` / `XCTL` | 該当が少ない |
| 例外処理 | `HANDLE CONDITION` 等が複雑 | 状態コードによる分岐 |
| 電文の構造 | COMMAREA | **複数セグメント、`PURG`、代替 PCB** |
| UOW | `SYNCPOINT` | **実機は RRS による 2PC。JVM 上では再現しない** |

**IMS TM が CICS より素直なのは制御移行と例外処理であり、対話状態と画面制御はむしろ MFS の分だけ重い。**「純粋なメッセージ駆動だから見通しやすい」という結論は、非会話型・MFS バイパスの構成に限って成り立つ。

### 4.3 現実的な段階

1. **第 1 段階（非会話型・MFS バイパス）**: 電文直結。§3.1 のモデル。ここは確かに素直である。
2. **第 2 段階**: 複数セグメント電文（`GN` / `PURG`）と代替 PCB（`CHNG`）。
3. **第 3 段階**: 会話型と SPA。[ADR-0008](../decisions/0008-cics-on-spring-mvc-and-session.md) の会話状態ストアとの共通化。
4. **第 4 段階**: MFS。[AR-12](../reviews/2026-09-09-interop-adversarial-review.md) の BMS spike 合格が前提。

---

## 5. まとめ

1. **IMS TM の正体**: 画面モニタというよりも、**オンライントランザクション・メッセージキューイングエンジン**である。
2. **IBM MQ との関係**: 競合ではなく協調関係にある。オープン系 ⇔ ホスト間の広域通信を **IBM MQ** が担い、ホスト内の電文キューイングと COBOL 起動を **IMS TM** が担う（**OTMA** がその間をブリッジする）。COBOL プログラム側は MQ を意識せず `CALL 'CBLTDLI'` を呼ぶだけでよい。
3. **移行時の位置づけ**:
   - **非会話型・単一セグメント・MFS バイパス**の構成に限れば、Java/Spring 環境への置き換えは見通しやすい。ここから着手するのが現実的である。
   - しかし **SPA（会話型）、複数セグメント電文、代替 PCB、MFS、そして実機の 2 相コミット**を数に入れると、CICS より容易とは言えない。改訂前の結論はこれらを数え落としていた。
   - 特に **UOW の原子性は JVM 上で再現しない**（at-least-once + 冪等化で代替する）。これは COBOL からの透過性が成り立たない領域であり、明記する必要がある ([P-104](../decisions/provisional.md))。
