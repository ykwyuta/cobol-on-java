package dev.cobolonjava.db2;

/** Spring管理またはDb2 driver管理UOWを開始する中立port。 */
public interface UnitOfWorkPort extends AutoCloseable {

    Db2ExecutionProfile profile();

    UnitOfWork begin(UnitOfWorkOptions options);

    /** task終了時にcursorを閉じ、native leaseやtransaction-bound resourceを解放する。 */
    @Override
    void close();
}
