package dev.cobolonjava.spring.boot4.db2;

import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.Db2ProfileMismatchException;
import dev.cobolonjava.db2.ResourceLeaseId;
import dev.cobolonjava.db2.RollbackReason;
import dev.cobolonjava.db2.UnitOfWork;
import dev.cobolonjava.db2.UnitOfWorkOptions;
import dev.cobolonjava.db2.UnitOfWorkPort;
import dev.cobolonjava.db2.UnitOfWorkState;
import dev.cobolonjava.db2.UnitOfWorkStateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/** Spring JDBC transaction managerを中立UOW portへ適合させるtask-scoped adapter。 */
public final class SpringManagedUnitOfWorkPort implements UnitOfWorkPort {

    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;
    private final List<SpringUnitOfWork> units = new ArrayList<>();
    private Thread owner;
    private long sequence;
    private boolean closed;

    public SpringManagedUnitOfWorkPort(
            DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.transactionManager = Objects.requireNonNull(
                transactionManager, "transactionManager");
        if (!(transactionManager instanceof DataSourceTransactionManager jdbcManager)) {
            throw new Db2ProfileMismatchException(
                    "SPRING_MANAGED requires JdbcTransactionManager or "
                            + "DataSourceTransactionManager");
        }
        if (jdbcManager.getDataSource() != dataSource) {
            throw new Db2ProfileMismatchException(
                    "Spring transaction manager and COBOL Db2 adapter must use "
                            + "the identical DataSource instance");
        }
    }

    /** 検証済みの単一Db2 DataSource。SQL adapterとのidentity検査に使う。 */
    public DataSource dataSource() {
        return dataSource;
    }

    @Override
    public Db2ExecutionProfile profile() {
        return Db2ExecutionProfile.SPRING_MANAGED;
    }

    @Override
    public UnitOfWork begin(UnitOfWorkOptions options) {
        enter();
        UnitOfWorkOptions required = Objects.requireNonNull(options, "options");
        if (required.profile() != profile() || required.requiresDriverManagedHold()) {
            throw new Db2ProfileMismatchException(
                    "Spring managed UOW cannot execute DB2_DRIVER_MANAGED_HOLD tasks");
        }
        if (units.stream().anyMatch(unit -> unit.state() == UnitOfWorkState.ACTIVE)) {
            throw new UnitOfWorkStateException(
                    "Spring managed UOW port already has an active transaction");
        }

        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setName("cobol-db2-uow-" + (++sequence));
        definition.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        definition.setReadOnly(required.readOnly());
        definition.setTimeout(timeoutSeconds(required.timeout()));
        TransactionStatus status = transactionManager.getTransaction(definition);
        SpringUnitOfWork unit = new SpringUnitOfWork(
                new ResourceLeaseId("spring-uow-" + sequence), status);
        units.add(unit);
        return unit;
    }

    @Override
    public void close() {
        enter();
        Throwable failure = null;
        for (int index = units.size() - 1; index >= 0; index--) {
            SpringUnitOfWork unit = units.get(index);
            if (unit.state() != UnitOfWorkState.ACTIVE) {
                continue;
            }
            try {
                unit.rollback(RollbackReason.cleanup());
            } catch (Throwable cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        closed = true;
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private void enter() {
        if (closed) {
            throw new UnitOfWorkStateException("Spring managed UOW port is closed");
        }
        Thread current = Thread.currentThread();
        if (owner == null) {
            owner = current;
        } else if (owner != current) {
            throw new UnitOfWorkStateException(
                    "Spring managed UOW port belongs to thread " + owner.getName()
                            + ", not " + current.getName());
        }
    }

    private static int timeoutSeconds(Duration timeout) {
        long seconds = timeout.getSeconds() + (timeout.getNano() == 0 ? 0 : 1);
        if (seconds <= 0 || seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Spring transaction timeout must fit positive whole seconds: " + timeout);
        }
        return (int) seconds;
    }

    private final class SpringUnitOfWork implements SpringJdbcUnitOfWork {

        private final ResourceLeaseId leaseId;
        private final TransactionStatus status;
        private final List<AutoCloseable> resources = new ArrayList<>();
        private UnitOfWorkState state = UnitOfWorkState.ACTIVE;

        private SpringUnitOfWork(ResourceLeaseId leaseId, TransactionStatus status) {
            this.leaseId = leaseId;
            this.status = status;
        }

        @Override
        public Db2ExecutionProfile profile() {
            return Db2ExecutionProfile.SPRING_MANAGED;
        }

        @Override
        public ResourceLeaseId resourceLeaseId() {
            return leaseId;
        }

        @Override
        public UnitOfWorkState state() {
            return state;
        }

        @Override
        public void verifyUsableBy(DataSource expectedDataSource) {
            requireActive("execute SQL");
            if (expectedDataSource != dataSource) {
                throw new Db2ProfileMismatchException(
                        "Spring SQL executor and UOW must use the identical DataSource instance");
            }
        }

        @Override
        public void registerResource(AutoCloseable resource) {
            requireActive("register resource");
            AutoCloseable required = Objects.requireNonNull(resource, "resource");
            if (resources.stream().anyMatch(existing -> existing == required)) {
                throw new IllegalArgumentException("Spring UOW resource is already registered");
            }
            resources.add(required);
        }

        @Override
        public void unregisterResource(AutoCloseable resource) {
            requireActive("unregister resource");
            resources.removeIf(existing -> existing == resource);
        }

        @Override
        public void commit() {
            requireActive("commit");
            boolean rollbackOnly = status.isRollbackOnly();
            RuntimeException cleanupFailure = closeResources();
            if (cleanupFailure != null) {
                rollbackAfterCleanupFailure(cleanupFailure);
                throw cleanupFailure;
            }
            try {
                transactionManager.commit(status);
                state = rollbackOnly
                        ? UnitOfWorkState.ROLLED_BACK : UnitOfWorkState.COMMITTED;
            } catch (UnexpectedRollbackException rolledBack) {
                state = UnitOfWorkState.ROLLED_BACK;
                throw rolledBack;
            } catch (RuntimeException | Error failure) {
                state = UnitOfWorkState.FAILED;
                throw failure;
            }
        }

        @Override
        public void rollback(RollbackReason reason) {
            requireActive("rollback");
            Objects.requireNonNull(reason, "reason");
            RuntimeException cleanupFailure = closeResources();
            try {
                transactionManager.rollback(status);
                state = UnitOfWorkState.ROLLED_BACK;
            } catch (RuntimeException | Error failure) {
                state = UnitOfWorkState.FAILED;
                if (cleanupFailure != null) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }

        private RuntimeException closeResources() {
            RuntimeException failure = null;
            List<AutoCloseable> closing = new ArrayList<>(resources);
            resources.clear();
            for (int index = closing.size() - 1; index >= 0; index--) {
                try {
                    closing.get(index).close();
                } catch (Throwable cleanup) {
                    RuntimeException wrapped = new IllegalStateException(
                            "failed to close Spring UOW resource", cleanup);
                    if (failure == null) {
                        failure = wrapped;
                    } else {
                        failure.addSuppressed(wrapped);
                    }
                }
            }
            return failure;
        }

        private void rollbackAfterCleanupFailure(RuntimeException cleanupFailure) {
            try {
                transactionManager.rollback(status);
                state = UnitOfWorkState.ROLLED_BACK;
            } catch (RuntimeException | Error rollbackFailure) {
                state = UnitOfWorkState.FAILED;
                cleanupFailure.addSuppressed(rollbackFailure);
            }
        }

        private void requireActive(String operation) {
            enter();
            if (state != UnitOfWorkState.ACTIVE) {
                throw new UnitOfWorkStateException(
                        "cannot " + operation + " Spring UOW in state " + state);
            }
        }
    }
}
