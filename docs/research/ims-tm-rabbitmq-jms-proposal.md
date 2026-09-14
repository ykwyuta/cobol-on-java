# 検討報告: JMS による IMS TM メッセージキュー基盤の実現と、UOW 原子性の扱い

| 項目 | 内容 |
| :--- | :--- |
| **作成日** | 2026-09-11 (2026-09-13 改訂) |
| **対象サブシステム** | IMS TM メッセージキューイング層 |
| **バックエンド技術** | **JMS 3.0 (Jakarta Messaging)** + RabbitMQ (Docker Compose) |
| **対応要件** | **無し** ([P-098](../decisions/provisional.md)) |
| **検証レベル** | **V1 / V0** |
| **関連文書** | [ims-tm-and-mq-architecture.md](ims-tm-and-mq-architecture.md), [ADR-0007 (中立ポート)](../decisions/0007-framework-neutral-subsystem-ports.md), [ADR-0014](../decisions/0014-ims-tm-rabbitmq-jms-neutral-queue.md) |
| **改訂理由** | [2026-09-13 批判的レビュー](../reviews/2026-09-13-ims-research-critical-review.md) の IR-23（2PC の欠落）, IR-25, IR-26, IR-27 |

> ### 改訂の要点
>
> 改訂前の本書は、実機の UOW 原子性（RRS による 2 相コミット）を**黙って落としていた**。
> RabbitMQ は XA をサポートしないため、`session.commit()` と JDBC コミットの 2 本立てでは
> 電文の消失と二重処理が構造的に起きる。§5.3 を全面的に書き直し、
> **at-least-once + 冪等化 (inbox 方式)** を採ることと、**残る差**を明記した。
>
> あわせて電文の順序保証（§5.4）、複数セグメント電文と代替 PCB（§5.5）、
> 依存の版の未確認（§4.1）を追加した。

---

## 1. 検討の目的と方針

[ims-tm-and-mq-architecture.md](ims-tm-and-mq-architecture.md) で確認したとおり、実機では **IBM MQ + OTMA** を経由して IMS TM に電文を投入する構成が一般的である。

ローカル開発・OSS 検証環境では IBM MQ 実機の利用やライセンス調達が制約となるため、次の方針を採る。

1. **JMS (Jakarta Messaging 3.x) を介した抽象化**: ランタイム層は AMQP 等の固有プロトコルに直接依存せず、標準規格である JMS を介して送受信する。接続ファクトリの差し替えで別製品へ移れる状態を保つ。
2. **Docker Compose による OSS ブローカのローカル提供**: 軽量なコンテナで CI とローカル開発を閉じる。
3. **COBOL からの透過性**: COBOL アプリケーションは従来どおり `CALL 'CBLTDLI' USING 'GU  ', IO-PCB, IN-BUFFER` および `ISRT` を実行する。
   - **ただし透過性は完全ではない。**UOW の原子性が実機と異なる（§5.3）。

---

## 2. システム全体アーキテクチャ

```
[ 外部クライアント (Web/REST, テストドライバ, 外部連携) ]
                          |
                          | (1) 要求電文
                          v
+-------------------------------------------------------------------+
| メッセージブローカ (Docker Compose)                               |
|   [ Queue: ims.trx.CUST001 ]  <--- トランザクション名ごとのキュー |
|   [ Queue: ims.reply.queue ]  <--- 応答返信用のキュー             |
+-------------------------------------------------------------------+
                          ^
                          | JMS 3.0 API
                          v
+-------------------------------------------------------------------+
| `cobol-ims-tm` (ランタイム・中立キューポート)                     |
|   1. JMS BytesMessage の受信 (手動 ACK)                           |
|   2. 電文ヘッダ・フォーマット変換 (LLZZ の取り扱い)               |
|   3. inbox による冪等化 (処理済み電文キーの記録)                  |
|   4. 仮想 I/O PCB の状態管理 ('  ' / 'QC')                        |
|   5. SPA の持ち回り (会話型)                                      |
+-------------------------------------------------------------------+
                          |
                          | (2) PROCEDURE DIVISION USING IO-PCB...
                          v
+-------------------------------------------------------------------+
| COBOL 業務プログラム (MPP)                                        |
+-------------------------------------------------------------------+
```

---

## 3. ブローカと Docker Compose 設計 (`infra/rabbitmq/`)

`infra/db2/` の構成を踏襲する。

```yaml
name: cobol-on-java-rabbitmq

services:
  rabbitmq:
    # 版は実際に依存解決と起動を確認してから固定する (4.1 節)
    image: rabbitmq:${RABBITMQ_VERSION:-3.13}-management
    hostname: rabbitmq
    restart: unless-stopped
    ports:
      - "${RABBITMQ_PORT:-5672}:5672"
      - "${RABBITMQ_MGMT_PORT:-15672}:15672"
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_USER:-guest}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASS:-guest}
    volumes:
      - rabbitmq-data:/var/lib/rabbitmq
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "check_port_connectivity"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 20s

volumes:
  rabbitmq-data:
```

管理コンソールからキューの蓄積状況とコンシューマ接続状況を確認できる。

---

## 4. JMS 連携

### 4.1 依存ライブラリの選定（**版は未確認**）

RabbitMQ のネイティブプロトコルは AMQP 0-9-1 であり、JMS を使うには JMS クライアントを挟む。

> **改訂前は次を確認せずに書いていた。使う前に実際に確かめる**
> （CLAUDE.md §6「道具にも検査を仕込む」）。
>
> - `com.rabbitmq:rabbitmq-jms` の版と `jakarta.jms:jakarta.jms-api` 3.x の組み合わせ
> - `rabbitmq:3.13-management`。**2026 年時点では 4.x 世代であり、3.13 は新規採用に向かない可能性がある**
> - トピックを使う場合に必要な **`rabbitmq_jms_topic_exchange` プラグイン**の有効化。
>   改訂前の compose には無かった。キューのみなら不要だが、その前提を書いていなかった
>
> 版を固定する際は、依存解決と起動を確認した日付を記録する。

### 4.2 JMS 接続とキューの初期化

```java
// ConnectionFactory のセットアップ (中立アダプタ層に閉じる)
ConnectionFactory factory = /* ブローカ固有の ConnectionFactory */;
Connection connection = factory.createConnection();
// 手動 ACK。JDBC コミット成功後に ACK する (5.3 節)
Session session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);
Queue trxQueue = session.createQueue("ims.trx.CUST001");
```

`ConnectionFactory` の生成だけがブローカ固有であり、それ以外は `jakarta.jms.*` に閉じる。

---

## 5. COBOL ランタイム（`CALL 'CBLTDLI'`）との橋渡し

### 5.1 電文の表現形式

COBOL の電文は EBCDIC やバイナリを含むため、JMS では `jakarta.jms.BytesMessage` を使う。

```
+-------------------------------------------------------------+
| JMS BytesMessage (Payload)                                  |
|  [ LL (2B) ][ ZZ (2B) ][ 業務データ (EBCDIC/バイナリ...) ]  |
+-------------------------------------------------------------+
```

会話型トランザクションでは、この後ろではなく**先頭に SPA が来る**（[ims-tm-and-mq-architecture.md](ims-tm-and-mq-architecture.md) §3.4）。

### 5.2 各 DL/I コールの内部実装

1. **`GU`（電文の取得）**:
   - 対象キューの `MessageConsumer` から `receive(timeout)` で受信する。
   - 受信成功時: 対応付け情報（相関 ID、応答先）を内部コンテキストへ保持し、バイト列を COBOL の受取バッファへ転記して `IO-STATUS-CODE` に `'  '` を設定する。
   - **inbox に処理済みキーがあれば、その電文は再配信であるため捨てて次を取る**（§5.3）。
   - キューが空（タイムアウト）の場合: `'QC'` を設定する。COBOL 側はこれを見て終了処理へ移る。
2. **`GN`（I/O PCB に対して）**: 入力電文の 2 セグメント目以降を取る（§5.5）。
3. **`ISRT`（応答電文の送信）**: 送信バッファからバイト配列を読み出し、`BytesMessage` を生成して応答先へ送る。複数セグメント出力の場合は組み立てて `PURG` で確定する（§5.5）。
4. **コミット**: §5.3 の手順による。

### 5.3 UOW の原子性【改訂で全面的に書き直し】

#### 問題

実機では、OTMA と IBM MQ が RRS 等と連携し、「電文をデキューする」「IMS DB / Db2 を更新する」「応答を返す」を**一連のトランザクションとして原子的に確定する**。

改訂前の本書は、これを `session.commit()`（JMS）と JDBC コミットの**2 本立て**に置き換えていた。**RabbitMQ は XA をサポートしない。**したがって次が構造的に起こる。

- JMS コミット後・JDBC コミット前に落ちる → **電文は消えたが DB は更新されていない（電文の消失）**
- JDBC コミット後・JMS コミット前に落ちる → **DB は更新済みなのに電文が再配信される（二重処理）**

同じ資料群の [ims-tm-and-mq-architecture.md](ims-tm-and-mq-architecture.md) §2.2 が「RRS と連携して原子的に同期保証する」と説明した直後に、この性質を黙って落としていたことになる。

#### 採る方式: at-least-once + 冪等化 (inbox)

1. 処理済み電文のキー（メッセージ ID とトランザクション連番）を、**IMS DB の更新と同一 JDBC トランザクション**へ書く。
2. JMS は**手動 ACK** とし、**JDBC コミットが成功してから ACK する**。
3. 再配信された電文は inbox のキーで検出して捨てる。

```
   [電文受信] --> [業務処理] --> +-- IMS DB 更新 ---+
                                 |                  |  同一 JDBC トランザクション
                                 +-- inbox へ記録 --+
                                          |
                                     コミット成功
                                          |
                                     JMS を ACK
```

**これにより電文の消失は起きない。**ACK 前に落ちれば電文は再配信され、inbox が二重処理を防ぐ。

#### 残る差（明記する）

- **業務ロジックが 2 度呼ばれうる。**inbox の記録前に落ちた場合、業務処理はやり直される。ロールバックされるので DB は戻るが、**冪等でない副作用（外部送信、採番の消費、ログ出力）は 2 度起きうる**。
- inbox テーブルの保持期間と刈り取りが運用項目として増える。
- **これは COBOL からの透過性が成り立たない領域である。**リリースノートに明記する。

#### 代替案: XA

XA 対応ブローカ（ActiveMQ Artemis 等）と JTA トランザクションマネージャを使えば原子性を保てる。ただし **Spring Boot は 3.x で Atomikos / Bitronix の自動構成を落としており、JTA には第三者スターターの追加が要る**。この費用を払うかは利用者の判断とする ([P-104](../decisions/provisional.md))。

### 5.4 電文の順序【改訂で追加】

IMS はトランザクションコード単位で電文を FIFO 処理する。RabbitMQ では、複数コンシューマ、prefetch、ロールバックによる requeue のいずれでも順序が変わりうる。順序に依存する資産（同一口座への連続更新など）で結果が変わる。

**第 1 増分はキューあたり単一コンシューマ・`prefetch=1` を既定とする。**実機の複数 MPP 領域による並列スケジューリングは再現しない ([P-105](../decisions/provisional.md))。

### 5.5 電文は 1 セグメントとは限らない【改訂で追加】

`ImsQueuePort` の契約は「`GU` = 1 メッセージ受信」「`ISRT` = 1 メッセージ送信」では足りない。

| 機構 | 必要な理由 |
| --- | --- |
| **I/O PCB への `GN`** | 入力電文の 2 セグメント目以降を取る |
| **`PURG`** | 複数の `ISRT` で組み立てた出力電文を確定する |
| **`CHNG`** / **代替 PCB (ALTPCB)** | 別の LTERM や別トランザクションへ送る |
| **SPA** | 会話型トランザクションの状態 |

**これらが無いと、該当する資産は「1 セグメント目だけ送られる」のような部分的に正しく見える壊れ方をする。**契約を凍結する前に、対応するか L0 として診断するかを決める（[設計 78](../design/78-ims-subsystem.md) §6）。

---

## 6. この方式のメリットと限界

### メリット

1. **ローカル閉塞・自動テスト容易性**: 商用 IBM MQ の実機やライセンスが不要。Docker Compose または Testcontainers を使い、ローカル PC / CI 上で自動テストが完結する。
2. **JMS によるポータビリティ**: ランタイムコアは `jakarta.jms.*` にのみ依存するため、別のブローカへ移る際は接続ファクトリと設定の変更で済む。
   - ただし**トランザクションの意味論はブローカによって変わる**（XA の有無）。「一切のコード修正が発生しない」とは言えない。
3. **可視化とデバッグ性**: 管理 UI により、キューの滞留と消費状況をリアルタイムに確認できる。

### 限界

- **実機の UOW 原子性は再現しない**（§5.3）。
- **並列スケジューリングは再現しない**（§5.4）。
- **依存とブローカの版は未確認**（§4.1）。

---

## 7. 実装ステップの提案

- **Step 1 (`infra/rabbitmq`)**: compose を配置し、ローカルで起動できることと依存が解決できることを**実際に確認して版を固定する**。
- **Step 2 (`cobol-ims` 中立契約)**: `ImsQueuePort` を定義する。§5.5 の機構を契約に含めるか L0 とするかを決める。
- **Step 3 (`cobol-ims-jms` アダプタ)**: JMS を用いたアダプタを実装する。手動 ACK と inbox を含む。
- **Step 4 (結合テスト)**: テストドライバが電文を投入 → COBOL MPP が `GU` で取得 → `ISRT` で応答 → Java 側で検証、というエンドツーエンド試験。
- **Step 5 (障害注入テスト)**: **電文デキューと DB コミットの間で強制終了させ、電文が失われないこと・再処理が inbox で弾かれることを確認する。**§5.3 の方式はこれを通して初めて成立する。
