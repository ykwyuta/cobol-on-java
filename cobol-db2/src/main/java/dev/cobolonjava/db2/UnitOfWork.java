package dev.cobolonjava.db2;

/** JDBCやSpring transaction statusを公開しないadapter所有UOW。 */
public interface UnitOfWork {

    Db2ExecutionProfile profile();

    ResourceLeaseId resourceLeaseId();

    UnitOfWorkState state();

    void commit();

    void rollback(RollbackReason reason);
}
