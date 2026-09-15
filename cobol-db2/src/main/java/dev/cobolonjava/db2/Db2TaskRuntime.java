package dev.cobolonjava.db2;

import dev.cobolonjava.runtime.interop.CobolSession;
import java.util.Objects;

/** 一つの同期taskでprofile固定、遅延UOW、明示syncpoint、cleanupを強制する。 */
public final class Db2TaskRuntime implements AutoCloseable {

    private final UnitOfWorkOptions options;
    private final UnitOfWorkPort unitOfWorkPort;
    private final SqlExecutorPort sqlExecutor;
    private UnitOfWork current;
    private ResourceLeaseId nativeLease;
    private Thread owner;
    private boolean closed;

    public Db2TaskRuntime(UnitOfWorkOptions options,
                          UnitOfWorkPort unitOfWorkPort,
                          SqlExecutorPort sqlExecutor) {
        this.options = Objects.requireNonNull(options, "options");
        this.unitOfWorkPort = Objects.requireNonNull(unitOfWorkPort, "unitOfWorkPort");
        this.sqlExecutor = Objects.requireNonNull(sqlExecutor, "sqlExecutor");
        requireProfile("UOW port", unitOfWorkPort.profile());
        requireProfile("SQL executor", sqlExecutor.profile());
    }

    public Db2ExecutionProfile profile() {
        return options.profile();
    }

    public SqlOutcome execute(SqlPlan plan, SqlBindings bindings, CobolSession session) {
        enter();
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(session, "session");
        validateCursorPolicy(plan);
        UnitOfWork unit = activeUnitOfWork();
        SqlOutcome outcome;
        try {
            outcome = Objects.requireNonNull(
                    sqlExecutor.execute(plan, bindings, session, unit),
                    "SQL executor outcome");
        } catch (RuntimeException | Error failure) {
            if (unit.state() == UnitOfWorkState.ROLLED_BACK) {
                current = null;
            } else if (unit.state() != UnitOfWorkState.ACTIVE) {
                failure.addSuppressed(new UnitOfWorkStateException(
                        "SQL executor failed with unexpected UOW state " + unit.state()));
            }
            throw failure;
        }
        if (outcome.databaseRolledBackUnitOfWork()) {
            requireState(unit, UnitOfWorkState.ROLLED_BACK, "database rollback");
            current = null;
        } else {
            requireActive(unit);
        }
        return outcome;
    }

    /** SQL COMMITまたはCICS SYNCPOINT。次のUOWは次回SQLまで開始しない。 */
    public void commit() {
        enter();
        if (current == null) {
            return;
        }
        requireActive(current);
        current.commit();
        requireState(current, UnitOfWorkState.COMMITTED, "commit");
        current = null;
    }

    /** SQL ROLLBACKまたはCICS SYNCPOINT ROLLBACK。 */
    public void rollback(RollbackReason reason) {
        enter();
        Objects.requireNonNull(reason, "reason");
        if (current == null) {
            return;
        }
        requireActive(current);
        current.rollback(reason);
        requireState(current, UnitOfWorkState.ROLLED_BACK, "rollback");
        current = null;
    }

    /**
     * 現在の UOW の中で action を行う。UOW が無ければ始める。
     *
     * <p>STRICT の会話ストアが、業務の SQL と同じ UOW で会話の表を更新するために使う (設計 77 §4.6、暫定判断 P-143)。
     * action が失敗しても UOW は閉じない。rollback するのは呼び手である。
     */
    public void inUnitOfWork(Runnable action) {
        enter();
        Objects.requireNonNull(action, "action");
        activeUnitOfWork();
        action.run();
        requireActive(current);
    }

    /** 正常task終了。active UOWだけをcommitしてruntimeを閉じる。 */
    public void complete() {
        enter();
        finish(this::commit);
    }

    /** ABEND等の異常task終了。active UOWだけをrollbackしてruntimeを閉じる。 */
    public void abort(RollbackReason reason) {
        enter();
        Objects.requireNonNull(reason, "reason");
        finish(() -> rollback(reason));
    }

    /** 完了方法が指定されずscopeを抜けた場合はcommitせずrollbackする。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        enter();
        finish(() -> rollback(RollbackReason.cleanup()));
    }

    private UnitOfWork activeUnitOfWork() {
        if (current == null) {
            current = Objects.requireNonNull(unitOfWorkPort.begin(options), "UOW port result");
            requireProfile("started UOW", current.profile());
            ResourceLeaseId lease = Objects.requireNonNull(
                    current.resourceLeaseId(), "UOW resource lease id");
            if (profile() == Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD) {
                if (nativeLease == null) {
                    nativeLease = lease;
                } else if (!nativeLease.equals(lease)) {
                    throw new Db2ProfileMismatchException(
                            "DB2_DRIVER_MANAGED_HOLD changed its task-scoped resource lease");
                }
            }
        }
        requireActive(current);
        return current;
    }

    private void validateCursorPolicy(SqlPlan plan) {
        CursorOptions cursor = plan.cursorOptions();
        if (!cursor.withHold()) {
            return;
        }
        switch (cursor.holdStrategy()) {
            case REJECT_UNVERIFIED -> throw new UnverifiedHoldCursorException(
                    "WITH HOLD cursor has no approved strategy: " + cursor.cursorName());
            case PORTABLE_SPOOL -> {
                if (profile() != Db2ExecutionProfile.SPRING_MANAGED) {
                    throw new Db2ProfileMismatchException(
                            "PORTABLE_SPOOL belongs to SPRING_MANAGED profile");
                }
            }
            case DB2_DRIVER_MANAGED_HOLD -> {
                if (profile() != Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD) {
                    throw new Db2ProfileMismatchException(
                            "native WITH HOLD requires DB2_DRIVER_MANAGED_HOLD profile");
                }
            }
            case NOT_HELD -> throw new AssertionError("invalid CursorOptions invariant");
        }
    }

    private void requireProfile(String component, Db2ExecutionProfile actual) {
        if (options.profile() != actual) {
            throw new Db2ProfileMismatchException(component + " uses " + actual
                    + " but task uses " + options.profile());
        }
    }

    private void requireActive(UnitOfWork unit) {
        requireState(unit, UnitOfWorkState.ACTIVE, "use");
    }

    private static void requireState(
            UnitOfWork unit, UnitOfWorkState expected, String operation) {
        if (unit.state() != expected) {
            throw new UnitOfWorkStateException("UOW state after " + operation + " is "
                    + unit.state() + ", expected " + expected);
        }
    }

    private void enter() {
        if (closed) {
            throw new UnitOfWorkStateException("Db2 task runtime is closed");
        }
        Thread currentThread = Thread.currentThread();
        if (owner == null) {
            owner = currentThread;
        } else if (owner != currentThread) {
            throw new UnitOfWorkStateException("Db2 task runtime belongs to thread "
                    + owner.getName() + ", not " + currentThread.getName());
        }
    }

    private void finish(Runnable completion) {
        Throwable failure = null;
        try {
            completion.run();
        } catch (Throwable problem) {
            failure = problem;
        }
        try {
            unitOfWorkPort.close();
        } catch (Throwable cleanup) {
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        } finally {
            closed = true;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new UnitOfWorkStateException("unexpected checked UOW failure: " + failure);
        }
    }
}
