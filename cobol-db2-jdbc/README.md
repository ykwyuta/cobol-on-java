# cobol-db2-jdbc

`DB2_DRIVER_MANAGED_HOLD` profileのため、Spring transaction / connection管理を通さず
task専用JDBC connection leaseを所有するadapter moduleである。

現在の増分は次を実装する。

- 専用`Db2NativeConnectionProvider`から最初のUOWで一度だけleaseを取得する
- 同じ物理`Connection`と`ResourceLeaseId`を複数commit間で維持する
- `autoCommit=false`、taskのread-only属性、`HOLD_CURSORS_OVER_COMMIT`を設定・検証する
- commit時は非hold資源だけ、rollback / task close時は全資源を閉じる
- task終了時に取得時のconnection属性へresetし、成功時だけ`REUSABLE`でproviderへ返す
- commit / rollback / resource close / reset失敗時は`DISCARD`で返す
- `DriverManagerDb2NativeConnectionProvider`でIBM JCCから非poolのtask専用connectionを生成する
- `DriverManagedSqlExecutor`でDML、単一行SELECT、forward-only / read-only cursorを同じlease上で実行する
- task timeoutをJDBC statement timeoutへ切り上げ秒単位で写像する
- SQLSTATE class `08`またはJDBC resource close失敗を検出したleaseは再利用せず`DISCARD`する

本番構成のproviderはIBM Db2 JDBC `DataSource`または専用poolをアプリケーション側で所有する。
このDataSourceをSpring transaction managerへ登録してはならない。資格情報やJDBC URLは外部構成から
providerへ渡し、本module自身では読み込まない。

`Db2ContainerIntegrationTest`は明示的に有効化した場合だけIBM JCCで実Db2へ接続する。生のJDBC
resource lifecycleに加え、中立`SqlExecutorPort`からのDML / SELECT、COBOL host variableへの出力、
`OPEN WITH HOLD`、commit後FETCH、明示CLOSEを検証する。
接続断・プロセス停止・`-911` / `-913`の試験は後続増分である。
