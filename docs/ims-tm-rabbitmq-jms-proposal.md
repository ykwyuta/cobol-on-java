# 検討報告: RabbitMQ + JMS による IMS TM メッセージキュー基盤の実現

| 項目 | 内容 |
| :--- | :--- |
| **作成日** | 2026-09-11 |
| **対象サブシステム** | IMS TM (Transaction Manager) メッセージキューイング層 |
| **バックエンド技術** | **RabbitMQ (Docker Compose)** + **JMS 3.0 (Jakarta Messaging)** |
| **クライアントライブラリ** | `rabbitmq-jms` (RabbitMQ JMS Client) |
| **関連文書** | [docs/ims-overview-and-support-scope.md](ims-overview-and-support-scope.md), [docs/ims-tm-and-mq-architecture.md](ims-tm-and-mq-architecture.md), [ADR-0007 (中立ポート)](decisions/0007-framework-neutral-subsystem-ports.md) |

---

## 1. 検討の目的と方針

前回の検討（[docs/ims-tm-and-mq-architecture.md](ims-tm-and-mq-architecture.md)）で確認した通り、実機のメインフレーム環境では **IBM MQ + OTMA** を経由して IMS TM に電文を投入する構成が一般的です。

しかし、ローカル開発・OSS 検証環境では IBM MQ 実機の利用やライセンス調達が制約となるため、本検討では以下の方針を採用します：

1. **Docker Compose による RabbitMQ のローカル提供**:
   - 軽量・オープンソースで稼働する RabbitMQ コンテナを `infra/rabbitmq/` 配下に用意。
2. **JMS (Jakarta Messaging 3.x) を介した抽象化**:
   - COBOL / ランタイム層は RabbitMQ の AMQP 固有プロトコルに直接依存せず、標準規格である **JMS (Jakarta Messaging)** を介してメッセージを送受信する。
   - これにより、将来的に実案件で IBM MQ や ActiveMQ、AWS SQS 等に変更する場合でも、接続ファクトリ（`ConnectionFactory`）の差し替えだけで対応可能（中立性の維持）。
3. **COBOL からの完全透過性**:
   - COBOL アプリケーションは従来のまま **`CALL 'CBLTDLI' USING 'GU  ', IO-PCB, IN-BUFFER`** および **`ISRT`** を実行する。JMS や RabbitMQ の存在はランタイム内部で完全に隠蔽される。

---

## 2. システム全体アーキテクチャ

```
[ 外部クライアント (Web/REST, テストドライバ, 外部連携) ]
                          |
                          | (1) 要求電文 (バイト列 or テキスト)
                          v
+-------------------------------------------------------------------+
| Docker Compose: RabbitMQ                                          |
|                                                                   |
|   [ Queue: ims.trx.CUST001 ]  <--- トランザクション名ごとのキュー |
|   [ Queue: ims.reply.queue ]  <--- 応答返信用の一時/共通キュー    |
+-------------------------------------------------------------------+
                          ^
                          | JMS 3.0 API (rabbitmq-jms)
                          v
+-------------------------------------------------------------------+
| `cobol-ims-tm` (ランタイム・中立キューポート)                     |
|                                                                   |
|   1. JmsQueuePort / JmsImsMessageConsumer                         |
|      - JMS BytesMessage または MessageConsumer.receive() で待機   |
|   2. 電文ヘッダ・フォーマット変換                                 |
|      - JMSヘッダ (JMSCorrelationID, JMSReplyTo) の退避           |
|      - IMS 固有の LLZZ プレフィックス (4バイト) の付与・除去      |
|   3. 仮想 I/O PCB の状態管理                                      |
|      - STATUS-CODE: 正常('  '), 空('QC')                          |
+-------------------------------------------------------------------+
                          |
                          | (2) PROCEDURE DIVISION USING IO-PCB...
                          v
+-------------------------------------------------------------------+
| COBOL 業務プログラム (MPP: Message Processing Program)            |
|                                                                   |
|   - CALL 'CBLTDLI' USING 'GU  ', IO-PCB, IN-BUFFER.               |
|   - (業務ロジック実行 / DB更新)                                   |
|   - CALL 'CBLTDLI' USING 'ISRT', IO-PCB, OUT-BUFFER.              |
+-------------------------------------------------------------------+
```

---

## 3. RabbitMQ と Docker Compose 設計 (`infra/rabbitmq/`)

本プロジェクトの `infra/db2/` の構成を踏襲し、`infra/rabbitmq/` を設計します。

### 3.1 `infra/rabbitmq/compose.yaml`
```yaml
name: cobol-on-java-rabbitmq

services:
  rabbitmq:
    image: rabbitmq:3.13-management
    hostname: rabbitmq
    restart: unless-stopped
    ports:
      - "${RABBITMQ_PORT:-5672}:5672"       # AMQP / JMS 通信用
      - "${RABBITMQ_MGMT_PORT:-15672}:15672" # 管理 Web UI 用 (http://localhost:15672)
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

管理コンソール（ポート 15672）からキューの蓄積状況、メッセージの中身、COBOL プログラムのコンシューマ接続状況をブラウザ上で視覚的に確認・デバッグできます。

---

## 4. JMS 連携と `rabbitmq-jms` の仕組み

### 4.1 依存ライブラリの選定
RabbitMQ はネイティブプロトコルとして AMQP 0-9-1 を使用しますが、公式から **`com.rabbitmq:rabbitmq-jms`**（RabbitMQ JMS Client）が提供されています。これを利用することで、Spring Boot や Java 標準の `jakarta.jms.*` インタフェースをそのまま使用できます。

```xml
<!-- pom.xml (cobol-ims-jms または テストスコープ) -->
<dependency>
    <groupId>com.rabbitmq</groupId>
    <artifactId>rabbitmq-jms</artifactId>
    <version>3.3.0</version>
</dependency>
<dependency>
    <groupId>jakarta.jms</groupId>
    <artifactId>jakarta.jms-api</artifactId>
    <version>3.1.0</version>
</dependency>
```

### 4.2 JMS 接続とキューの初期化
```java
// ConnectionFactory のセットアップ (中立アダプタ層)
RMQConnectionFactory factory = new RMQConnectionFactory();
factory.setHost("localhost");
factory.setPort(5672);
factory.setUsername("guest");
factory.setPassword("guest");

Connection connection = factory.createConnection();
Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
Queue trxQueue = session.createQueue("ims.trx.CUST001");
```

---

## 5. COBOL ランタイム（`CALL 'CBLTDLI'`）との橋渡し

COBOL の MPP（Message Processing Program）が呼ぶ `CBLTDLI` の動作を、JMS API にどうマッピングするかを設計します。

### 5.1 電文の表現形式と `BytesMessage`
COBOL の電文は EBCDIC やバイナリ（`COMP` 等）を含むため、JMS では **`jakarta.jms.BytesMessage`** を使用します。

```
+-------------------------------------------------------------+
| JMS BytesMessage (Payload)                                  |
|                                                             |
|  [ LL (2B) ][ ZZ (2B) ][ 業務データ (EBCDIC/バイナリ...) ]  |
+-------------------------------------------------------------+
```

### 5.2 各 DL/I コールの内部実装

1. **`GU` (Get Unique - 電文の取得)**:
   - 対象キュー（例: `ims.trx.<TRX_CODE>`）の `MessageConsumer` からメッセージを受信（`receive(timeout)`）。
   - 受信成功時:
     - メッセージの `JMSCorrelationID` および `JMSReplyTo`（応答先キュー）を内部のコンテキスト（`ImsTransactionContext`）に保持。
     - バイト列（`LLZZ` プレフィックス＋データ）を COBOL の受取バッファ（引数の I/O AREA）へ転記。
     - `IO-PCB` の `IO-STATUS-CODE` に `'  '` (正常) を設定。
   - キューが空（タイムアウト）の場合:
     - `IO-STATUS-CODE` に `'QC'` (Queue Empty) を設定（COBOL 側はこれを見て終了処理へ移行）。

2. **`ISRT` (Insert - 応答電文の送信)**:
   - COBOL の送信バッファ（I/O AREA）からバイト配列を読み出し。
   - `session.createBytesMessage()` を生成し、`ImsTransactionContext` に保存されている `JMSCorrelationID` をセット。
   - `JMSReplyTo` で指定された応答キュー（または既定の出力キュー）へ `MessageProducer.send()`。
   - `IO-STATUS-CODE` に `'  '` を設定。

3. **トランザクション確定 (コミット) と UOW**:
   - COBOL のループ終了時（または `CHKP` チェックポイント発行時）に、JMS セッションの `session.commit()` を呼び出し、メッセージの受信完了と応答送信を確定。
   - ABEND 発生時は `session.rollback()` を呼び、キューへメッセージをリキュー（再試行または DLQ: 死線キューへ退避）。

---

## 6. この方式のメリット

1. **完全なローカル閉塞・自動テスト容易性**:
   - 商用 IBM MQ の実機やライセンスが不要。Docker Compose または Testcontainers（`RabbitMQContainer`）を使って、GitHub Actions / ローカル PC 上で CI テストが 100% 完結します。
2. **JMS によるポータビリティ**:
   - 業務コードやランタイムコアは `jakarta.jms.*` のみに依存するため、もし将来ユーザーが「本番は AWS SQS にしたい」「ActiveMQ Artemis にしたい」「実機 IBM MQ に繋ぎたい」となった場合でも、接続ドライバと設定を変えるだけで一切のコード修正が発生しません。
3. **可視化とデバッグ性**:
   - RabbitMQ Management UI（管理画面）により、COBOL MPP が今どの電文を処理中なのか、キューに何件溜まっているのかをリアルタイムに確認できます。

---

## 7. 実装ステップの提案

- **Step 1 (`infra/rabbitmq`)**:
  - `infra/rabbitmq/compose.yaml` を配置し、ローカルで起動できるコンテナ環境を準備。
- **Step 2 (`cobol-ims-tm` 中立契約)**:
  - `ImsQueuePort` インタフェースを定義し、キューの取得（`poll`）・送信（`send`）の契約を固定。
- **Step 3 (`cobol-ims-jms` アダプタ)**:
  - `rabbitmq-jms` を用いた `JmsImsQueueAdapter` を実装。
- **Step 4 (結合テスト)**:
  - Docker Compose / Testcontainers 上の RabbitMQ に対し、Java テストドライバが電文を投入 → COBOL MPP が `CALL 'CBLTDLI' USING 'GU  '` で取得して大文字変換 → `CALL 'CBLTDLI' USING 'ISRT'` で応答 → Java 側で検証、というエンドツーエンド試験を実施。
