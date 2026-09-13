package dev.cobolonjava.db2.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.Db2ProfileMismatchException;
import dev.cobolonjava.db2.ResourceLeaseId;
import dev.cobolonjava.db2.RollbackReason;
import dev.cobolonjava.db2.UnitOfWorkOptions;
import dev.cobolonjava.db2.UnitOfWorkState;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class DriverManagedUnitOfWorkPortTest {

    @Test
    @DisplayName("同じ物理connectionとleaseをcommit後の次UOWでも使用する")
    void keepsPhysicalConnectionAcrossCommits() throws SQLException {
        JdbcDataSource dataSource = dataSource();
        execute(dataSource, "create table event_log(id int primary key, payload varchar(20))");
        RecordingProvider provider = new RecordingProvider(dataSource);
        DriverManagedUnitOfWorkPort port = new DriverManagedUnitOfWorkPort(provider);

        DriverManagedJdbcUnitOfWork first =
                (DriverManagedJdbcUnitOfWork) port.begin(options(false));
        Connection physical = first.connection();
        execute(physical, "insert into event_log values (1, 'commit')");
        ResourceLeaseId leaseId = first.resourceLeaseId();
        first.commit();

        DriverManagedJdbcUnitOfWork second =
                (DriverManagedJdbcUnitOfWork) port.begin(options(false));
        assertSame(physical, second.connection());
        assertEquals(leaseId, second.resourceLeaseId());
        execute(second.connection(), "insert into event_log values (2, 'rollback')");
        second.rollback(new RollbackReason(RollbackReason.Kind.EXPLICIT, "TEST"));
        port.close();

        assertEquals(1, provider.acquireCount);
        assertEquals(LeaseReleaseDisposition.REUSABLE, provider.disposition);
        assertEquals(1, count(dataSource));
        assertEquals(true, provider.autoCommitAtRelease);
        assertEquals(provider.initialHoldability, provider.holdabilityAtRelease);
    }

    @Test
    @DisplayName("commitでは非hold資源だけを閉じrollbackではhold資源も閉じる")
    void appliesCursorCleanupAtUnitOfWorkBoundaries() {
        RecordingProvider provider = new RecordingProvider(dataSource());
        DriverManagedUnitOfWorkPort port = new DriverManagedUnitOfWorkPort(provider);
        AtomicInteger ordinaryClosed = new AtomicInteger();
        AtomicInteger heldClosed = new AtomicInteger();

        DriverManagedJdbcUnitOfWork first =
                (DriverManagedJdbcUnitOfWork) port.begin(options(false));
        first.registerResource(ordinaryClosed::incrementAndGet, false);
        first.registerResource(heldClosed::incrementAndGet, true);
        first.commit();

        assertEquals(1, ordinaryClosed.get());
        assertEquals(0, heldClosed.get());

        DriverManagedJdbcUnitOfWork second =
                (DriverManagedJdbcUnitOfWork) port.begin(options(false));
        second.rollback(RollbackReason.cleanup());
        assertEquals(1, heldClosed.get());
        port.close();
    }

    @Test
    @DisplayName("resource close失敗時はcommitせずrollbackしてleaseを破棄する")
    void rollsBackAndDiscardsLeaseOnResourceFailure() throws SQLException {
        JdbcDataSource dataSource = dataSource();
        execute(dataSource, "create table event_log(id int primary key, payload varchar(20))");
        RecordingProvider provider = new RecordingProvider(dataSource);
        DriverManagedUnitOfWorkPort port = new DriverManagedUnitOfWorkPort(provider);
        DriverManagedJdbcUnitOfWork unit =
                (DriverManagedJdbcUnitOfWork) port.begin(options(false));
        execute(unit.connection(), "insert into event_log values (1, 'rollback')");
        unit.registerResource(() -> {
            throw new SQLException("close failed");
        }, false);

        assertThrows(DriverManagedJdbcException.class, unit::commit);
        assertEquals(UnitOfWorkState.ROLLED_BACK, unit.state());
        port.close();

        assertEquals(LeaseReleaseDisposition.DISCARD, provider.disposition);
        assertEquals(0, count(dataSource));
    }

    @Test
    @DisplayName("native profile指定とtask内で不変なUOW optionを要求する")
    void rejectsWrongOrChangingOptions() {
        RecordingProvider provider = new RecordingProvider(dataSource());
        DriverManagedUnitOfWorkPort port = new DriverManagedUnitOfWorkPort(provider);

        assertThrows(Db2ProfileMismatchException.class,
                () -> port.begin(new UnitOfWorkOptions(
                        Db2ExecutionProfile.SPRING_MANAGED,
                        Duration.ofSeconds(5), false, false)));
        DriverManagedJdbcUnitOfWork first =
                (DriverManagedJdbcUnitOfWork) port.begin(options(false));
        first.commit();
        assertThrows(Db2ProfileMismatchException.class, () -> port.begin(options(true)));
        port.close();
        assertEquals(1, provider.acquireCount);
    }

    @Test
    @DisplayName("providerが閉じたconnectionを返した場合は取得を失敗させ破棄する")
    void rejectsClosedProviderConnection() throws SQLException {
        RecordingProvider provider = new RecordingProvider(dataSource());
        provider.closeBeforeReturn = true;
        DriverManagedUnitOfWorkPort port = new DriverManagedUnitOfWorkPort(provider);

        assertThrows(DriverManagedJdbcException.class, () -> port.begin(options(false)));

        assertEquals(LeaseReleaseDisposition.DISCARD, provider.disposition);
        assertFalse(provider.autoCommitAtRelease);
        port.close();
    }

    @Test
    @DisplayName("executorがconnection障害を通知したleaseは正常rollback後も破棄する")
    void discardsLeaseMarkedUnusableByExecutor() {
        RecordingProvider provider = new RecordingProvider(dataSource());
        DriverManagedUnitOfWorkPort port = new DriverManagedUnitOfWorkPort(provider);
        DriverManagedJdbcUnitOfWork unit =
                (DriverManagedJdbcUnitOfWork) port.begin(options(false));

        unit.markConnectionUnusable();
        unit.rollback(RollbackReason.cleanup());
        port.close();

        assertEquals(LeaseReleaseDisposition.DISCARD, provider.disposition);
    }

    private static UnitOfWorkOptions options(boolean readOnly) {
        return new UnitOfWorkOptions(Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD,
                Duration.ofSeconds(5), readOnly, true);
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void execute(JdbcDataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            execute(connection, sql);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static int count(JdbcDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("select count(*) from event_log")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static final class RecordingProvider implements Db2NativeConnectionProvider {

        private final JdbcDataSource dataSource;
        private int acquireCount;
        private LeaseReleaseDisposition disposition;
        private int initialHoldability;
        private int holdabilityAtRelease;
        private boolean autoCommitAtRelease;
        private boolean closeBeforeReturn;

        private RecordingProvider(JdbcDataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public Db2NativeConnectionLease acquire() throws SQLException {
            acquireCount++;
            Connection connection = dataSource.getConnection();
            initialHoldability = connection.getHoldability();
            if (closeBeforeReturn) {
                connection.close();
            }
            return new Db2NativeConnectionLease() {
                private boolean released;

                @Override
                public ResourceLeaseId id() {
                    return new ResourceLeaseId("native-test-lease");
                }

                @Override
                public Connection connection() {
                    return connection;
                }

                @Override
                public void release(LeaseReleaseDisposition requested) throws SQLException {
                    if (released) {
                        throw new IllegalStateException("lease released twice");
                    }
                    released = true;
                    disposition = requested;
                    if (!connection.isClosed()) {
                        autoCommitAtRelease = connection.getAutoCommit();
                        holdabilityAtRelease = connection.getHoldability();
                        connection.close();
                    }
                }
            };
        }
    }
}
