# cobol-db2

Db2 SQL、SQLCA fidelity、cursor方針、UOW profileのフレームワーク非依存コアである。
Spring、JDBC、接続プールの型を公開APIへ含めない。

```java
UnitOfWorkOptions options = new UnitOfWorkOptions(
        Db2ExecutionProfile.SPRING_MANAGED,
        Duration.ofSeconds(30), false, false);

try (Db2TaskRuntime db2 = new Db2TaskRuntime(options, uowPort, sqlExecutor)) {
    SqlOutcome result = db2.execute(plan, bindings, cobolSession);
    db2.commit(); // SQL COMMIT / CICS SYNCPOINT
    db2.complete();
}
```

`WITH HOLD`必須taskでは`DB2_DRIVER_MANAGED_HOLD`をtask開始時に選ぶ。native profileの
`UnitOfWork`はcommitをまたいでも同じ`ResourceLeaseId`を返さなければならず、異なるIDは
profile違反として実行前に拒否される。scopeを明示完了せず抜けた場合はactive UOWをrollbackし、
task-scoped portを必ずcloseする。

現在はexperimentalな中立モデルに、固定長文字、COMP-3、数字DISPLAY、BINARY、null indicatorの
型付きhost variable / codecを追加した。通常UOW、non-cursor SQL subset、非hold cursorの
`OPEN` / `FETCH` / `CLOSE`は
`cobol-spring-boot-4-autoconfigure`のSpring Boot 4.1.1 adapterへ接続済みである。
Db2 JDBC driver管理のconnection lease / UOWは`cobol-db2-jdbc`へ接続済みである。native SQL executor、
SQLコプロセッサ、VARCHAR group、日付・時刻・LOB、
SQLCA値の完全な構築、高機能cursor、実Db2でのcommit後FETCHは未実装・未検証である。
