# PostgreSQL (IMS DB の置き場) 検証環境

IMS のデータベースを RDB の表に置く `cobol-ims-rdb` を、実 PostgreSQL で流すためのものである
(設計 78 §3.2、ADR-0013、暫定判断 P-160)。ローカル検証専用であり、本番用途には使用しない。

```powershell
Copy-Item .env.example .env
# .env の POSTGRES_PASSWORD を変更する
docker compose --env-file .env up -d --wait
docker compose --env-file .env ps
```

`healthy` になったら、リポジトリルートで実 PostgreSQL の試験を流す。

```powershell
$env:POSTGRES_IT_ENABLED = "true"
$env:POSTGRES_HOST = "localhost"
$env:POSTGRES_PORT = "5432"
$env:POSTGRES_DB = "imsdb"
$env:POSTGRES_USER = "cobol"
$env:POSTGRES_PASSWORD = "<infra/postgres/.env と同じ値>"
mvn -pl cobol-ims-rdb -am test
```

`POSTGRES_IT_ENABLED` を設定しなければ、実 PostgreSQL を使う試験だけがスキップされる。Db2・RabbitMQ の
環境と同じ構えである。試験は実行ごとに使い捨ての schema (`ims_it_` で始まる名前) を作り、終われば消す。

Bank-of-Z を PostgreSQL の置き場へ流すには、`-Dcobol.ims.jdbc.url` に JDBC の URL を与える (P-160)。

```powershell
java "-Dcobol.ims.jdbc.url=jdbc:postgresql://localhost:5432/imsdb" `
     "-Dcobol.ims.jdbc.user=cobol" "-Dcobol.ims.jdbc.password=<.env と同じ値>" `
     -cp <classpath> dev.cobolonjava.job.Main -d <出力> -w <作業> -b <置き場> <JCL>
```

停止は `docker compose --env-file .env stop`、再開は `docker compose --env-file .env start` を使う。
データを消すときに限り、対象の project を確かめてから `docker compose --env-file .env down -v` を実行する。

## 版

- image: `postgres:17-alpine`
- クライアント: `org.postgresql:postgresql` (Maven の test scope)。版は Spring Boot の BOM が決める
- 制限: 2 CPU / 2 GiB、named volume `postgres-data`
- `initdb` は `--lc-collate=C --lc-ctype=C`。`HIERARCHY_PATH` の並びを照合順序に左右されない形にするため
  (読み込むときは Java の側で並べ直すので、置き場の照合順序には頼らない)

## 2026-09-16 のローカル検証基準

- server: PostgreSQL 17.11 (x86_64-pc-linux-musl)
- image: `postgres:17-alpine`
- 検証時 digest: `sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73`
- client: `org.postgresql:postgresql` 42.7.13 (Maven の test scope)
- database / port: `imsdb` / `5432`、照合順序は `datcollate` / `datctype` とも `C`

<b>`ImsSchema` の PostgreSQL の枝 (`BYTEA`、`COLLATE "C"`) は、これが初めての実行である</b> (暫定判断 P-160)。
`PostgresStoreIntegrationTest` の 4 件 (生バイトの往復と階層の順、根の版による競合、inbox と検査点、取引コードの借用) が
そのまま通った。Bank-of-Z も流し、<b>読み込み 5 本が全段 RC=0 でセグメント数 100 / 265 / 265 / 265 / 265</b>、
<b>オンライン 5 本が全て復帰コード 0 で応答 10 件</b>となり、データセットと H2 の置き場の結果と一致した。

`SHOW lc_collate` は PostgreSQL 15 以降では使えない (設定項目から外れた)。照合順序は
`SELECT datcollate, datctype FROM pg_database WHERE datname = 'imsdb'` で確かめる。
