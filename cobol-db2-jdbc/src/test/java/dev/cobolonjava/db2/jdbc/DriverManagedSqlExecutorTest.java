package dev.cobolonjava.db2.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.db2.CursorHoldStrategy;
import dev.cobolonjava.db2.CursorOptions;
import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.Db2TaskRuntime;
import dev.cobolonjava.db2.ResourceLeaseId;
import dev.cobolonjava.db2.RollbackReason;
import dev.cobolonjava.db2.SqlBindings;
import dev.cobolonjava.db2.SqlHostVariable;
import dev.cobolonjava.db2.SqlOperation;
import dev.cobolonjava.db2.SqlPlan;
import dev.cobolonjava.db2.SqlValueDescriptor;
import dev.cobolonjava.db2.UnitOfWorkOptions;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class DriverManagedSqlExecutorTest {

    @Test
    @DisplayName("native executorのDMLとSELECTは同じdriver-managed UOWへ参加する")
    void executesStatementsOnNativeUnitOfWork() throws SQLException {
        JdbcDataSource dataSource = dataSource();
        execute(dataSource, "create table account(id int primary key, name char(8))");
        DataView output = Storage.allocate(8).whole();

        try (CobolSession session = session();
                Db2TaskRuntime task = task(dataSource)) {
            assertEquals(1, task.execute(statement("I", SqlOperation.INSERT,
                            "insert into account values (1, 'ALICE')"),
                    SqlBindings.NONE, session).rowCount());
            task.commit();
            assertEquals(0, task.execute(statement("S", SqlOperation.SELECT_ONE,
                            "select name from account where id = 1"),
                    output(output), session).sqlCode());
            assertEquals("ALICE   ", CodePages.IBM_1047.decode(output.toByteArray()));
            task.complete();
        }

        assertEquals(1, count(dataSource));
    }

    @Test
    @DisplayName("native WITH HOLD cursorを中立SQL portからcommit後もFETCHする")
    void fetchesHeldCursorAcrossCommit() throws SQLException {
        JdbcDataSource dataSource = dataSource();
        execute(dataSource, "create table account(id int primary key, name char(8))");
        execute(dataSource, "insert into account values (1, 'ONE'), (2, 'TWO')");
        DataView output = Storage.allocate(8).whole();

        try (CobolSession session = session();
                Db2TaskRuntime task = task(dataSource)) {
            task.execute(cursor("O", SqlOperation.OPEN_CURSOR,
                    "select name from account order by id"), SqlBindings.NONE, session);
            task.execute(cursor("F1", SqlOperation.FETCH_CURSOR, "FETCH HCUR"),
                    output(output), session);
            assertEquals("ONE     ", CodePages.IBM_1047.decode(output.toByteArray()));
            task.commit();

            task.execute(cursor("F2", SqlOperation.FETCH_CURSOR, "FETCH HCUR"),
                    output(output), session);
            assertEquals("TWO     ", CodePages.IBM_1047.decode(output.toByteArray()));
            task.execute(cursor("C", SqlOperation.CLOSE_CURSOR, "CLOSE HCUR"),
                    SqlBindings.NONE, session);
            task.complete();
        }
    }

    @Test
    @DisplayName("dialectとnative hold strategyの不一致をJDBC実行前に拒否する")
    void rejectsUnsupportedPlansBeforeJdbcExecution() {
        JdbcDataSource dataSource = dataSource();
        DriverManagedUnitOfWorkPort port = new DriverManagedUnitOfWorkPort(
                new TestProvider(dataSource));
        DriverManagedJdbcUnitOfWork unit =
                (DriverManagedJdbcUnitOfWork) port.begin(options());
        DriverManagedSqlExecutor executor = new DriverManagedSqlExecutor();
        try (CobolSession session = session()) {
            assertThrows(IllegalArgumentException.class, () -> executor.execute(
                    new SqlPlan("X", "OTHER", SqlOperation.DELETE,
                            "delete from account", CursorOptions.none()),
                    SqlBindings.NONE, session, unit));
            CursorOptions rejected = new CursorOptions("HCUR", true,
                    CursorHoldStrategy.REJECT_UNVERIFIED,
                    false, false, false, false);
            assertThrows(UnsupportedOperationException.class, () -> executor.execute(
                    new SqlPlan("H", "DB2", SqlOperation.OPEN_CURSOR,
                            "select 1", rejected),
                    SqlBindings.NONE, session, unit));
        }
        unit.rollback(RollbackReason.cleanup());
        port.close();
    }

    private static Db2TaskRuntime task(JdbcDataSource dataSource) {
        return new Db2TaskRuntime(options(),
                new DriverManagedUnitOfWorkPort(new TestProvider(dataSource)),
                new DriverManagedSqlExecutor());
    }

    private static UnitOfWorkOptions options() {
        return new UnitOfWorkOptions(Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD,
                Duration.ofSeconds(5), false, true);
    }

    private static SqlPlan statement(String id, SqlOperation operation, String sql) {
        return new SqlPlan(id, "DB2", operation, sql, CursorOptions.none());
    }

    private static SqlPlan cursor(String id, SqlOperation operation, String sql) {
        return new SqlPlan(id, "DB2", operation, sql,
                new CursorOptions("HCUR", true,
                        CursorHoldStrategy.DB2_DRIVER_MANAGED_HOLD,
                        false, false, false, false));
    }

    private static SqlBindings output(DataView value) {
        return new SqlBindings(List.of(SqlHostVariable.output(value,
                new SqlValueDescriptor.FixedCharacter(CodePages.IBM_1047), null)));
    }

    private static JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static void execute(JdbcDataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static int count(JdbcDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                var result = statement.executeQuery("select count(*) from account")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static CobolSession session() {
        return CobolRuntime.builder(ProgramCatalog.builder().build()).build().openSession();
    }

    private static final class TestProvider implements Db2NativeConnectionProvider {

        private final JdbcDataSource dataSource;

        private TestProvider(JdbcDataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public Db2NativeConnectionLease acquire() throws SQLException {
            Connection connection = dataSource.getConnection();
            return new Db2NativeConnectionLease() {
                @Override
                public ResourceLeaseId id() {
                    return new ResourceLeaseId("test-native-lease");
                }

                @Override
                public Connection connection() {
                    return connection;
                }

                @Override
                public void release(LeaseReleaseDisposition disposition) throws SQLException {
                    connection.close();
                }
            };
        }
    }
}
