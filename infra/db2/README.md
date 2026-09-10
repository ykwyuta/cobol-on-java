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
