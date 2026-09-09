# ADR-0012: WITH HOLD 必須タスクは Db2 ドライバ管理 UOW で実行する

| 項目 | 内容 |
| --- | --- |
| 状態 | 採用 |
| 日付 | 2026-09-09 |
| 関連要件 | FR-153, FR-154, FR-156, FR-160, FR-165, NFR-034, NFR-038 |
| 置換対象 | [ADR-0009](0009-db2-spring-managed-unit-of-work.md)の `WITH HOLD` に関する決定 |
| 関連設計 | [設計 77](../design/77-spring-cics-db2.md) |

> **実装状況（2026-09-10）:** 中立`Db2TaskRuntime`でprofile混在拒否、遅延UOW、commit間の
> `ResourceLeaseId` pin、全終了経路のport closeまで実装した。これはopaque IDによる構造契約であり、
> Db2 JDBC driverの同一物理connection、holdability、commit後FETCHはまだ実装・実機検証していない。

## 文脈

Db2 の `WITH HOLD` は commit 後も cursor を開いたまま次の FETCH を継続する。Spring の local
transaction manager は transaction 完了時に thread-bound Connection を解放するため、Spring 管理 UOW の
まま ResultSet だけを保持すると、返却済み connection の利用、pool 内の別 task との共有、resource leak を
招く。commit 前に残行を spool する方式も、lock、可視性、I/O、fetch error の発生時点を変える。

一方、`WITH HOLD` が業務上必須の資産では、近似ではなく IBM Db2 JDBC driver の holdability と同一の
物理 connection を複数 commit 間で維持する必要がある。

## 決定

Db2 実行を task 単位で次の二 profile に分離する。

| profile | UOW / connection 所有者 | 用途 |
| --- | --- | --- |
| `SPRING_MANAGED` | Spring `PlatformTransactionManager` / transaction-bound DataSource | `WITH HOLD` を必要としない通常 task |
| `DB2_DRIVER_MANAGED_HOLD` | Db2 adapter / task 専有 `ConnectionLease` | `WITH HOLD` が必須で、実 Db2 適合性試験済みの task |

profile は `CicsTransactionDefinition` または Java / batch entry definition に配備時に固定し、task 開始後に
変更しない。静的 call closure と SQL inventory に `WITH HOLD` がある task は、明示的に
`DB2_DRIVER_MANAGED_HOLD` を選ばない限り起動を拒否する。動的 call で未宣言の `WITH HOLD` に到達した
場合も OPEN 前に拒否する。

`DB2_DRIVER_MANAGED_HOLD` では次を必須とする。

- 最初の SQL で、Db2 adapter 専用の `Db2NativeConnectionProvider` から connection lease を遅延取得する。
- adapter は `autoCommit=false` とし、Connection または Statement に
  `HOLD_CURSORS_OVER_COMMIT` を明示する。driver / server の実効 holdability を起動時と試験で確認する。
- COBOL の `COMMIT` / `ROLLBACK` と CICS `SYNCPOINT` は、lease が所有する
  `Connection.commit()` / `rollback()` へ写像する。
- task 内の全 COBOL SQL は同じ lease を使う。Spring の `JdbcTemplate`、`DataSourceUtils`、
  `PlatformTransactionManager` が管理する SQL と同一 task 内で混在させない。
- 同じ UOW に参加する Java service は lease-aware な中立 port を経由する。`@Transactional`、Spring Data、
  JPA が暗黙に同じ UOW へ参加すると表示しない。
- ResultSet / Statement / Connection は `CobolSession` や Spring Session に保存せず、task-scoped registry が
  所有する。正常終了、ABEND、timeout、client disconnect、shutdown の全経路で cursor を閉じてから lease を返す。
- lease は HTTP request / CICS task の境界を越えない。疑似会話の次 task は検索条件と再開 token から再実行し、
  生きた cursor を引き継がない。
- dedicated pool または IBM driver の connection provider に最大 lease 数、取得 timeout、task 最大時間、
  leak 検知、graceful shutdown を設ける。pool 返却前に holdability、isolation、read-only、schema 等を検証して
  初期化し、初期化失敗 connection は破棄する。

`Db2NativeConnectionProvider` は Db2 adapter の実装詳細であり、中立 `cobol-db2` API は
`UnitOfWorkPort` / `SqlExecutorPort` だけを見る。DriverManager を生成コードや業務 Java へ公開しない。
接続生成には adapter が所有する IBM `DataSource` または dedicated provider を優先し、資格情報は外部 secret
から供給する。Spring IoC を構成に使ってもよいが、その DataSource を Spring transaction manager へ登録しない。

Spring Session は HTTP session ID と期限管理に引き続き使う。`DB2_DRIVER_MANAGED_HOLD` task の業務更新と
会話 envelope を原子的に保存する場合、会話表を同じ native lease で更新し、Spring Session に会話 payload /
version を保存しない。別 Session store へ payload / version を publish する構成は、outcome journal と
idempotency key による `NON_ATOMIC` 回復規則を適用する。

## 影響

- 必須の `WITH HOLD` を spool に置き換えず、Db2 JDBC の cursor 意味論に近い形で実行できる。
- Spring Boot は MVC、Session、security、configuration、observability を提供し続けるが、この profile の
  Db2 transaction と connection lifecycle は管理しない。
- 同一 task 内の Spring `@Transactional` Java service との原子性は失われる。必要なら service を
  lease-aware port へ移すか、task を `SPRING_MANAGED` で実行できる形へ変更する。
- 長時間 task が connection を専有するため、通常 pool と分離した capacity plan と admission control が必要になる。

## 却下した案

### `WITH HOLD` cursor だけ別 connection で実行する

同じ task に二つの local UOW が生じ、片方だけ commit / rollback する部分更新を防げないため採用しない。

### 生きた cursor を Spring Session に保存して次要求へ渡す

serializable でなく、node affinity、再起動、timeout、connection leak の問題を作り、CICS task 境界とも
一致しないため採用しない。

### SQL のたびに DriverManager で connection を新規生成する

同じ physical connection を commit 後も使う要件を満たさず、資格情報、容量、監視、shutdown の統制も
困難になるため採用しない。
