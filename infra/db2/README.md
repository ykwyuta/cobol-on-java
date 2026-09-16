# Db2 Community Edition検証環境

IBM Container RegistryのDb2 Community Editionを、ローカル適合性試験専用に起動する。
本番用途には使用しない。

```powershell
Copy-Item .env.example .env
# .envのDB2_PASSWORDを変更する
docker compose --env-file .env up -d
docker compose --env-file .env ps
```

初回起動はデータベース作成を含むため数分かかる。`healthy`になった後、リポジトリルートで
次の実Db2試験を実行する。

```powershell
$env:DB2_IT_ENABLED = "true"
$env:DB2_HOST = "localhost"
$env:DB2_PORT = "50000"
$env:DB2_DATABASE = "COBOLDB"
$env:DB2_USER = "db2inst1"
$env:DB2_PASSWORD = "<infra/db2/.envと同じ値>"
mvn -pl cobol-db2-jdbc -am test
```

STRICT の会話ストア、端末の登録、START / TD の置き場、SPRING_MANAGED / DB2_DRIVER_MANAGED_HOLD の task 境界
(暫定判断 P-143・P-144) を、H2 と同じ試験のまま実Db2で流すには次を使う。試験ごとに使い捨ての schema
(`CJT` で始まる名前) に DDL を流し、終われば表と schema を消す。

```powershell
mvn -pl cobol-spring-boot-4-autoconfigure -am test "-Dtest=Db2StrictStoresIntegrationTest*" "-Dsurefire.failIfNoSpecifiedTests=false"
```

`DB2_IT_ENABLED`が未設定の場合、この実Db2試験だけをskipする。IBM JCCはテストscopeであり、
実行時のdriver版は親POMの`db2-jcc.version`で固定する。資格情報を含む`.env`はGit管理外である。

停止は`docker compose --env-file .env stop`、再開は`docker compose --env-file .env start`を使う。
データを削除する場合に限り、対象projectを確認してから`docker compose --env-file .env down -v`を実行する。

## 2026-09-11のローカル検証基準

- server: Db2 Community 12.1.5.0
- image: `icr.io/db2_community/db2:12.1.5.0`
- 検証時digest: `sha256:2de8151713c261843868c5c3411b57be6ae79d99d70a5b3022337836776bfda6`
- client: IBM JCC 12.1.4.0（Maven test scope）
- database / port: `COBOLDB` / `50000`
- 制限: 4 CPU / 8 GiB、named volume `db2-data`

初回起動、health check、commit後FETCH試験、コンテナ再起動、再起動後の同試験を通過した。
詳細と未検証項目は[検証報告](../../docs/report/20260911-db2-community-validation.md)を参照する。

## IMS のデータベースの置き場 (cobol-ims-rdb) を Db2 で流す

`cobol-ims-rdb` は IMS のデータベースを RDB の表に置く (設計 78 §3.2、ADR-0013、暫定判断 P-160)。
Db2 は 2026-09-16 に対応した。実 Db2 の試験は次で流す。

```powershell
$env:DB2_IT_ENABLED = "true"
$env:DB2_HOST = "localhost"; $env:DB2_PORT = "50000"; $env:DB2_DATABASE = "COBOLDB"
$env:DB2_USER = "db2inst1"; $env:DB2_PASSWORD = "<infra/db2/.envと同じ値>"
mvn -pl cobol-ims-rdb -am test
```

Bank-of-Z を Db2 の置き場へ流すには、`-Dcobol.ims.jdbc.url=jdbc:db2://localhost:50000/COBOLDB` と
`-Dcobol.ims.jdbc.user` / `.password` を与える。

### 2026-09-16 の検証基準

- server: Db2 12.1 (`DB2/LINUXX8664`、`SQL120150`)、image digest は上と同じ
- client: IBM JCC 12.1.4.0 (Maven の test scope)
- `Db2StoreIntegrationTest` の 4 件 (生バイトの往復と階層の順、根の版による競合、inbox と検査点、取引コードの借用) が通った
- Bank-of-Z の読み込み 5 本が全段 RC=0、セグメント数 100 / 265 / 265 / 265 / 265。オンライン 5 本も全て復帰コード 0

方言の差は 3 つだけだった。製品名に機種が入る (`DB2/LINUXX8664`)、`VARBINARY` の上限が 32672 byte、
素の `SELECT CURRENT_TIMESTAMP` が使えず `FROM SYSIBM.SYSDUMMY1` が要る。`CREATE TABLE IF NOT EXISTS`、
`SELECT ... FOR UPDATE`、`DEFAULT CURRENT_TIMESTAMP` はそのまま通る。<b>JCC はトランザクションが動いたままの
`close()` を断る</b> (ERRORCODE=-4471) ので、置き場は閉じる前に巻き戻すようにした。
