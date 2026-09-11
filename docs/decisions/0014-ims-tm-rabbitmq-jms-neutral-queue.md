# ADR-0014: IMS TM のメッセージ基盤を RabbitMQ と JMS 3.0 中立アダプタで構成する

| 項目 | 内容 |
| :--- | :--- |
| 状態 | 採用 |
| 日付 | 2026-09-11 |
| 関連要件 | FR-150〜156, ARC-4, ARC-7 |
| 関連設計 | [設計 78](../design/78-ims-subsystem.md), [ADR-0007 (中立ポート)](0007-framework-neutral-subsystem-ports.md) |

## 文脈

IMS TM (Transaction Manager) は、メッセージキュー駆動のオンライントランザクションモニタである。メインフレーム実機環境では、オープン系システムとメインフレーム間の通信に **IBM MQ + OTMA (Open Transaction Manager Access)** を組み合わせ、ホスト内 MPP (Message Processing Program) が `CALL 'CBLTDLI' USING 'GU  ', IO-PCB` で電文を取得・処理する構成が主流である。

しかし、開発・テスト環境や OSS CI 環境において商用 IBM MQ の実機やライセンスを調達することは困難であり、軽量・コンテナ環境（Docker Compose）で完結するオープンソースのメッセージングバックエンドが求められる。また、特定のメッセージング製品にランタイムが密結合することを避けなければならない。

## 決定

IMS TM のキューバックエンドとして **RabbitMQ (Docker Compose)** を標準開発環境とし、ランタイム接続には標準規格である **JMS 3.0 (Jakarta Messaging)** による中立アダプタを採用する。

1. **JMS 3.0 (Jakarta Messaging) による抽象化**:
   - `cobol-ims` コアは中立ポート `ImsQueuePort` を定義し、アダプタ層は `rabbitmq-jms` を用いて `jakarta.jms.*` API でメッセージを送受信する。
   - 将来的に ActiveMQ Artemis や実機 IBM MQ、AWS SQS に移行する場合でも、`ConnectionFactory` の差し替えのみで対応可能とする。
2. **COBOL からの完全透過性**:
   - COBOL プログラムは、`CALL 'CBLTDLI'` で `IO-PCB` に対し `GU`（デキュー）および `ISRT`（エンキュー）を発行する。
   - `jakarta.jms.BytesMessage` を用い、EBCDIC 生バイト列と `LLZZ` プレフィックスを透過的に変換・転記する。
   - キューが空（タイムアウト）の際は、メインフレーム標準のステータスコード `'QC'` (Queue Empty) を返却してプログラムのループを終了させる。
3. **ローカル環境の標準化**:
   - `infra/rabbitmq/compose.yaml` に `rabbitmq:3.13-management` を配置し、ローカル開発・テストおよび Management Web UI（ポート 15672）によるキュー監視を標準提供する。

## 影響

- **プラス影響**:
  - 商用ライセンス不要で、ローカル PC や GitHub Actions（Testcontainers）上で 100% 閉じた自動テストが可能。
  - JMS 規格に準拠しているため、クラウド環境や別製品へのポータビリティが確保される。
- **マイナス影響 / トレードオフ**:
  - AMQP と JMS の仕様差異（ヘッダの扱いやトランザクションセマンティクス）をアダプタ層で吸収する実装が必要。
