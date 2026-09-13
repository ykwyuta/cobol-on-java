# cobol-spring-boot-4-autoconfigure

Spring Boot 4.x固有型を中立なCICS / Db2 portへ接続するadapter moduleである。
初期増分はSpring Boot 4.1.1を基準に、通常の`SPRING_MANAGED` Db2 UOWを提供する。

- 同一`DataSource`の`JdbcTransactionManager`または`DataSourceTransactionManager`だけを許可する
- CICS task内の各UOWを`PROPAGATION_REQUIRES_NEW`でプログラム的に開始する
- timeoutとread-only属性を`TransactionDefinition`へ写像する
- commit / rollback / task closeを中立`UnitOfWork`状態へ写像する
- Spring transaction-bound `Connection`で`INSERT` / `UPDATE` / `DELETE` / 単一行`SELECT`を実行する
- Spring transaction-bound `Connection`で非hold・forward-only・read-only cursorの`OPEN` / `FETCH` / `CLOSE`を実行する
- cursorをCOBOL session identityとUOWへ結び、commit / rollback / task close前にResultSetとStatementを閉じる
- 固定長文字、COMP-3、数字DISPLAY、BINARYと2byte null indicatorを型付きhost variableで変換する
- SQL出力を全項目検証後に一括反映し、桁落ち・丸め・文字置換を拒否する
- JDBC exception / warning chainを値非包含の`SqlDiagnostic`へ最大64件保存する
- `UnitOfWorkPort`をprototype beanとして構成し、利用者定義beanを優先する
- `DB2_DRIVER_MANAGED_HOLD` profileでは自動構成せず、Spring管理JDBCとの混在を防ぐ

この増分にはscroll / sensitive / update cursor、session単独close / `CANCEL`連動、VARCHAR group、日付・時刻・LOB、Db2固有SQLCA mapper、SQLコプロセッサ、
Db2 driver管理`WITH HOLD` adapter、CICS MVC / Session adapterはまだ含まれない。
SQL試験はH2によるV1構造試験であり、実Db2のSQLCODEやrollback挙動の適合性証拠ではない。
