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

## Bank-of-Z のオンラインをブローカ越しに測る

`verify ims-mpp` は、既定ではこの JVM の中のメモリのキューを使う。次のシステムプロパティを与えると
`cobol-ims-jms` が差し込まれ、電文がブローカを経由する (暫定判断 P-165)。`cobol-ims-jms` と JMS クライアントの
jar を classpath に足しておく。

```powershell
java "-Dcobol.ims.jms.factory=com.rabbitmq.jms.admin.RMQConnectionFactory" `
     "-Dcobol.ims.jms.factory.host=localhost" "-Dcobol.ims.jms.factory.port=5672" `
     "-Dcobol.ims.jms.factory.username=cobol" "-Dcobol.ims.jms.factory.password=<.env と同じ値>" `
     "-Dcobol.ims.jms.queue=IBLOGIN1" "-Dcobol.ims.jms.reply-prefix=IMS.LTERM." `
     "-Dcobol.ims.queue.lease-required=false" `
     -cp <classpath> dev.cobolonjava.verify.Main ims-mpp <置き場> -d <翻訳した組> -p IBLOGIN1 -s IBLOGIN -m <電文>
```

`cobol.ims.queue.lease-required=false` が要るのは、データセットの置き場が**取引コードの借用を持てない**ためである
(暫定判断 P-167)。借用が無ければ同じ取引コードを 2 領域が読んでも気づけないので、既定では領域を起こさずに断る。
ここは 1 領域しか動かさない測定なので、旗を立てて降りる。RDB の置き場 (`-Dcobol.ims.jdbc.url=...`) を使うときは
借用が効くので、この旗は要らない。

接続の欄は `cobol.ims.jms.factory.<欄>` が `ConnectionFactory` の setter に流れる (`host` なら `setHost`)。
測定の出力の「キュー:」の行に、メモリかブローカかが出る。応答が届いたかはブローカの側でも確かめられる。

```powershell
docker exec cobol-on-java-rabbitmq-rabbitmq-1 rabbitmqctl list_queues name messages
```

停止は `docker compose --env-file .env stop`、再開は `docker compose --env-file .env start` を使う。
データを消すときに限り、対象の project を確かめてから `docker compose --env-file .env down -v` を実行する。

## 版

- image: `rabbitmq:4.1-management` (管理 UI 込み)
- digest: `sha256:036dbf561d8040eaed77d0d326b44bd8fa1c9ac382d2310bd7f35970fae70613`
- ブローカ: RabbitMQ 4.1.8 / Erlang/OTP 27
- JMS クライアント: `com.rabbitmq.jms:rabbitmq-jms` (Maven の test scope)。版は親 POM で固定する
- 制限: 2 CPU / 2 GiB、named volume `rabbitmq-data`

ADR-0014 は「依存ライブラリとブローカの版は、実際に依存解決と起動を確認してから固定する」と決めている。
依存の解決は確認した (`jakarta.jms-api` 3.1.0、`rabbitmq-jms` 3.4.0、`amqp-client` 5.22.0)。

<b>ブローカの起動と実ブローカの試験は 2026-09-16 に確認した</b> (暫定判断 P-162)。
`docker compose up -d --wait` が 7 秒で `healthy` になり、`RABBITMQ_IT_ENABLED=true` で
`JmsMessageQueueIntegrationTest` の 2 件 (同期点での取り出しと応答の確定、巻き戻しでの再配信) が
15.49 秒で通った。確認した環境は docker 29.4.0 / compose v5.1.1 である。
