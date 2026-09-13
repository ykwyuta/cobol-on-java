# Db2 Community Editionローカル検証報告（2026-09-11）

## 結論

Docker Compose上のDb2 Community 12.1.5.0にIBM JCC 12.1.4.0で接続し、
`DB2_DRIVER_MANAGED_HOLD`の最小適合性試験に合格した。Springのtransaction / connection管理を
通さないtask専用JDBC connectionで、commit後にもhold cursorのFETCHを継続できる。

これはADR-0012の方式を限定されたローカル条件で裏付ける証拠であり、Db2連携全体の本番適合を
宣言するものではない。

## 検証環境

| 項目 | 値 |
| --- | --- |
| Db2 server | Community Edition 12.1.5.0 / Linux x86-64 |
| container image | `icr.io/db2_community/db2:12.1.5.0` |
| image digest | `sha256:2de8151713c261843868c5c3411b57be6ae79d99d70a5b3022337836776bfda6` |
| JDBC driver | IBM JCC 12.1.4.0（test scope） |
| database | `COBOLDB` |
| 公開port | `50000` |
| container制限 | 4 CPU / 8 GiB |
| 永続化 | named volume `db2-data`を`/database`へmount |

serverとJCCのpatch levelは一致していない。この組合せで本試験は成功したが、本番の認定版は
互換性表と組織の保守方針に基づいて別途固定する。

## 実施結果

`Db2ContainerIntegrationTest`で次を一つのfixtureに対して検証した。

1. `DriverManager`がDb2へ直接接続し、driver-managed UOWが`autoCommit=false`を設定する。
2. connectionの実効holdabilityが`HOLD_CURSORS_OVER_COMMIT`である。
3. forward-only / read-only / holdable ResultSetから1行目をFETCHする。
4. UOWをcommitし、次のUOWが同一JDBC `Connection` objectを使うことを確認する。
5. commit済みの同じResultSetから2行目をFETCHする。
6. rollbackでhold ResultSet / Statementを閉じる。
7. task closeで物理connectionを閉じる。
8. 中立`SqlExecutorPort`からINSERTと単一行SELECTを実行し、COBOL固定長host variableへ出力する。
9. 中立portから`OPEN WITH HOLD` / FETCH / COMMIT / FETCH / CLOSEを実行する。

初回起動後に1件、同じnamed volumeを残したコンテナ再起動後に1件を実行し、いずれも
failure 0 / error 0 / skip 0で成功した。再起動後のhealth checkとdatabase接続も成功した。
別の構造試験では、接続系SQLSTATE class `08`またはresource close失敗を通知されたleaseを
再利用候補から除外して`DISCARD`することを確認した。

## 未検証・release gate

- COBOLコンパイラが生成した`EXEC SQL`からnative profileまでのend-to-end実行
- poolを使う本番providerで、同一の物理connectionがcommit間に維持されること
- 接続断、Db2プロセス停止、container停止、JVM停止時のresource回収と再実行禁止
- lock timeout / deadlock、`-911` / `-913`、暗黙rollbackの分類
- SQLWarning chain、SQLCA各field、LOB / scroll / update cursor
- 負荷時のlease上限、待機timeout、長時間cursorの容量・lock影響
- Spring管理profileとdriver管理profileの混在拒否を含む`@SpringBootTest`

これらが完了するまで、実Db2試験済みという表現は本報告の最小ケースに限定する。
