# RabbitMQ (IMS TM のメッセージ基盤) 検証環境

IMS TM の電文のキューをローカルで動かすためのブローカである (ADR-0014、設計 78 §4)。
ローカル検証専用であり、本番用途には使用しない。

```powershell
Copy-Item .env.example .env
# .env の RABBITMQ_PASSWORD を変更する
docker compose --env-file .env up -d
docker compose --env-file .env ps
```

`healthy` になったら、管理 UI は <http://localhost:15672> (利用者とパスワードは `.env` の値) である。
キューの中身、未処理の電文、消費者の数をここで見る。

リポジトリルートで、実ブローカを使う試験を流す。

```powershell
$env:RABBITMQ_IT_ENABLED = "true"
$env:RABBITMQ_HOST = "localhost"
$env:RABBITMQ_PORT = "5672"
$env:RABBITMQ_USER = "cobol"
$env:RABBITMQ_PASSWORD = "<infra/rabbitmq/.env と同じ値>"
mvn -pl cobol-ims-jms -am test
```

`RABBITMQ_IT_ENABLED` を設定しなければ、実ブローカを使う試験だけがスキップされる。Db2 の環境と同じ構えである。

停止は `docker compose --env-file .env stop`、再開は `docker compose --env-file .env start` を使う。
データを消すときに限り、対象の project を確かめてから `docker compose --env-file .env down -v` を実行する。

## 版

- image: `rabbitmq:4.1-management` (管理 UI 込み)
- JMS クライアント: `com.rabbitmq.jms:rabbitmq-jms` (Maven の test scope)。版は親 POM で固定する
- 制限: 2 CPU / 2 GiB、named volume `rabbitmq-data`

ADR-0014 は「依存ライブラリとブローカの版は、実際に依存解決と起動を確認してから固定する」と決めている。
依存の解決は確認した (`jakarta.jms-api` 3.1.0、`rabbitmq-jms` 3.4.0、`amqp-client` 5.22.0)。
<b>ブローカの起動と実ブローカの試験は、まだ確認していない</b> (作った環境で docker が動かなかった、暫定判断 P-162)。
確認した時点で、image の digest と結果をここに書き足す。
