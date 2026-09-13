package dev.cobolonjava.db2.jdbc;

import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.Db2ProfileMismatchException;
import dev.cobolonjava.db2.ResourceLeaseId;
import dev.cobolonjava.db2.RollbackReason;
import dev.cobolonjava.db2.UnitOfWork;
import dev.cobolonjava.db2.UnitOfWorkOptions;
import dev.cobolonjava.db2.UnitOfWorkPort;
import dev.cobolonjava.db2.UnitOfWorkState;
import dev.cobolonjava.db2.UnitOfWorkStateException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 一つのtaskで同じnative JDBC connectionを複数UOW間に保持するadapter。 */
public final class DriverManagedUnitOfWorkPort implements UnitOfWorkPort {

    private final Db2NativeConnectionProvider provider;
    private final List<ResourceEntry> resources = new ArrayList<>();
    private Db2NativeConnectionLease lease;
    private Connection connection;
    private ConnectionBaseline baseline;
    private UnitOfWorkOptions taskOptions;
    private NativeUnitOfWork active;
    private Thread owner;
    private boolean discardLease;
    private boolean closed;

    public DriverManagedUnitOfWorkPort(Db2NativeConnectionProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public Db2ExecutionProfile profile() {
        return Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD;
    }

    @Override
    public UnitOfWork begin(UnitOfWorkOptions options) {
        enter();
        UnitOfWorkOptions required = Objects.requireNonNull(options, "options");
        if (required.profile() != profile() || !required.requiresDriverManagedHold()) {
            throw new Db2ProfileMismatchException(
                    "driver-managed UOW requires a DB2_DRIVER_MANAGED_HOLD task");
        }
        if (active != null && active.state() == UnitOfWorkState.ACTIVE) {
            throw new UnitOfWorkStateException("driver-managed port already has an active UOW");
        }
        if (taskOptions == null) {
            acquire(required);
            taskOptions = required;
        } else if (!taskOptions.equals(required)) {
            throw new Db2ProfileMismatchException(
                    "driver-managed task cannot change its UOW options between commits");
        }
        active = new NativeUnitOfWork();
        return active;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        enter();
        Throwable failure = null;
        if (active != null && active.state() == UnitOfWorkState.ACTIVE) {
            try {
                active.rollback(RollbackReason.cleanup());
            } catch (Throwable problem) {
                discardLease = true;
                failure = problem;
            }
        } else {
            failure = append(failure, closeResources(false));
            if (connection != null) {
                try {
                    connection.rollback();
                } catch (Throwable problem) {
                    discardLease = true;
                    failure = append(failure, problem);
                }
            }
        }
        if (lease != null) {
            if (!discardLease) {
                try {
                    resetConnection();
                } catch (Throwable problem) {
                    discardLease = true;
                    failure = append(failure, problem);
                }
            }
            try {
                lease.release(discardLease
                        ? LeaseReleaseDisposition.DISCARD
                        : LeaseReleaseDisposition.REUSABLE);
            } catch (Throwable problem) {
                failure = append(failure, problem);
            }
        }
        closed = true;
        if (failure != null) {
            throw wrap("failed to close driver-managed Db2 task", failure);
        }
    }

    private void acquire(UnitOfWorkOptions options) {
        try {
            lease = Objects.requireNonNull(provider.acquire(), "provider lease");
            connection = Objects.requireNonNull(lease.connection(), "lease connection");
            Objects.requireNonNull(lease.id(), "lease id");
            if (connection.isClosed()) {
                throw new SQLException("provider returned a closed connection");
            }
            baseline = ConnectionBaseline.capture(connection);
            connection.setAutoCommit(false);
            connection.setReadOnly(options.readOnly());
            connection.setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT);
            verifyConfigured(options);
        } catch (Throwable failure) {
            discardLease = true;
            if (lease != null) {
                try {
                    lease.release(LeaseReleaseDisposition.DISCARD);
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
                lease = null;
            }
            connection = null;
            baseline = null;
            throw wrap("failed to acquire driver-managed Db2 connection", failure);
        }
    }

    private void verifyConfigured(UnitOfWorkOptions options) throws SQLException {
        if (connection.getAutoCommit()) {
            throw new SQLException("driver-managed connection remained in auto-commit mode");
        }
        if (connection.isReadOnly() != options.readOnly()) {
            throw new SQLException("driver did not apply the requested read-only mode");
        }
        if (connection.getHoldability() != ResultSet.HOLD_CURSORS_OVER_COMMIT) {
            throw new SQLException("driver did not apply HOLD_CURSORS_OVER_COMMIT");
        }
    }

    private void resetConnection() throws SQLException {
        SQLException failure = null;
        failure = reset(failure, () -> connection.setReadOnly(baseline.readOnly));
        failure = reset(failure,
                () -> connection.setTransactionIsolation(baseline.transactionIsolation));
        failure = reset(failure, () -> connection.setHoldability(baseline.holdability));
        failure = reset(failure, () -> connection.setCatalog(baseline.catalog));
        failure = reset(failure, () -> connection.setSchema(baseline.schema));
        failure = reset(failure, () -> connection.setAutoCommit(baseline.autoCommit));
        if (failure != null) {
            throw failure;
        }
        if (connection.getAutoCommit() != baseline.autoCommit
                || connection.isReadOnly() != baseline.readOnly
                || connection.getTransactionIsolation() != baseline.transactionIsolation
                || connection.getHoldability() != baseline.holdability
                || !Objects.equals(connection.getCatalog(), baseline.catalog)
                || !Objects.equals(connection.getSchema(), baseline.schema)) {
            throw new SQLException("connection state did not return to its acquisition baseline");
        }
    }

    private static SQLException reset(SQLException failure, SqlAction action) {
        try {
            action.run();
        } catch (SQLException problem) {
            if (failure == null) {
                return problem;
            }
            failure.addSuppressed(problem);
        }
        return failure;
    }

    private void registerResource(AutoCloseable resource, boolean holdAcrossCommit) {
        enter();
        AutoCloseable required = Objects.requireNonNull(resource, "resource");
        if (resources.stream().anyMatch(entry -> entry.resource == required)) {
            throw new IllegalArgumentException("driver-managed resource is already registered");
        }
        resources.add(new ResourceEntry(required, holdAcrossCommit));
    }

    private void unregisterResource(AutoCloseable resource) {
        enter();
        resources.removeIf(entry -> entry.resource == resource);
    }

    private RuntimeException closeResources(boolean preserveHeld) {
        RuntimeException failure = null;
        for (int index = resources.size() - 1; index >= 0; index--) {
            ResourceEntry entry = resources.get(index);
            if (preserveHeld && entry.holdAcrossCommit) {
                continue;
            }
            resources.remove(index);
            try {
                entry.resource.close();
            } catch (Throwable problem) {
                RuntimeException wrapped = new DriverManagedJdbcException(
                        "failed to close driver-managed JDBC resource", problem);
                if (failure == null) {
                    failure = wrapped;
                } else {
                    failure.addSuppressed(wrapped);
                }
            }
        }
        if (failure != null) {
            discardLease = true;
        }
        return failure;
    }

    private void enter() {
        if (closed) {
            throw new UnitOfWorkStateException("driver-managed UOW port is closed");
        }
        Thread current = Thread.currentThread();
        if (owner == null) {
            owner = current;
        } else if (owner != current) {
            throw new UnitOfWorkStateException(
                    "driver-managed UOW port belongs to thread " + owner.getName());
        }
    }

    private static Throwable append(Throwable primary, Throwable next) {
        if (next == null) {
            return primary;
        }
        if (primary == null) {
            return next;
        }
        primary.addSuppressed(next);
        return primary;
    }

    private static DriverManagedJdbcException wrap(String message, Throwable failure) {
        return failure instanceof DriverManagedJdbcException known
                ? known : new DriverManagedJdbcException(message, failure);
    }

    private final class NativeUnitOfWork implements DriverManagedJdbcUnitOfWork {

        private UnitOfWorkState state = UnitOfWorkState.ACTIVE;

        @Override
        public Db2ExecutionProfile profile() {
            return DriverManagedUnitOfWorkPort.this.profile();
        }

        @Override
        public ResourceLeaseId resourceLeaseId() {
            return lease.id();
        }

        @Override
        public UnitOfWorkState state() {
            return state;
        }

        @Override
        public Connection connection() {
            requireActive("access connection");
            return connection;
        }

        @Override
        public int queryTimeoutSeconds() {
            requireActive("access query timeout");
            long millis = taskOptions.timeout().toMillis();
            long seconds = Math.max(1L, (millis + 999L) / 1_000L);
            return (int) Math.min(Integer.MAX_VALUE, seconds);
        }

        @Override
        public void markConnectionUnusable() {
            requireActive("mark connection unusable");
            discardLease = true;
        }

        @Override
        public void registerResource(AutoCloseable resource, boolean holdAcrossCommit) {
            requireActive("register resource");
            DriverManagedUnitOfWorkPort.this.registerResource(resource, holdAcrossCommit);
        }

        @Override
        public void unregisterResource(AutoCloseable resource) {
            requireActive("unregister resource");
            DriverManagedUnitOfWorkPort.this.unregisterResource(resource);
        }

        @Override
        public void commit() {
            requireActive("commit");
            RuntimeException cleanup = closeResources(true);
            if (cleanup != null) {
                rollbackAfterFailure(cleanup);
                throw cleanup;
            }
            try {
                connection.commit();
                state = UnitOfWorkState.COMMITTED;
            } catch (SQLException failure) {
                discardLease = true;
                state = UnitOfWorkState.FAILED;
                throw wrap("driver-managed Db2 commit failed", failure);
            }
        }

        @Override
        public void rollback(RollbackReason reason) {
            requireActive("rollback");
            Objects.requireNonNull(reason, "reason");
            RuntimeException cleanup = closeResources(false);
            try {
                connection.rollback();
                state = UnitOfWorkState.ROLLED_BACK;
            } catch (SQLException failure) {
                discardLease = true;
                state = UnitOfWorkState.FAILED;
                if (cleanup != null) {
                    failure.addSuppressed(cleanup);
                }
                throw wrap("driver-managed Db2 rollback failed", failure);
            }
            if (cleanup != null) {
                throw cleanup;
            }
        }

        private void rollbackAfterFailure(RuntimeException primary) {
            try {
                connection.rollback();
                state = UnitOfWorkState.ROLLED_BACK;
            } catch (SQLException failure) {
                discardLease = true;
                state = UnitOfWorkState.FAILED;
                primary.addSuppressed(failure);
            }
        }

        private void requireActive(String operation) {
            enter();
            if (active != this || state != UnitOfWorkState.ACTIVE) {
                throw new UnitOfWorkStateException(
                        "cannot " + operation + " driver-managed UOW in state " + state);
            }
        }
    }

    private record ResourceEntry(AutoCloseable resource, boolean holdAcrossCommit) {
    }

    private record ConnectionBaseline(
            boolean autoCommit,
            boolean readOnly,
            int transactionIsolation,
            int holdability,
            String catalog,
            String schema) {

        private static ConnectionBaseline capture(Connection connection) throws SQLException {
            return new ConnectionBaseline(connection.getAutoCommit(), connection.isReadOnly(),
                    connection.getTransactionIsolation(), connection.getHoldability(),
                    connection.getCatalog(), connection.getSchema());
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
