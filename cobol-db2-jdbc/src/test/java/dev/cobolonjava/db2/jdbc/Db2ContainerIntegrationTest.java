package dev.cobolonjava.db2.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.db2.CursorHoldStrategy;
import dev.cobolonjava.db2.CursorOptions;
import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.Db2TaskRuntime;
import dev.cobolonjava.db2.RollbackReason;
import dev.cobolonjava.db2.SqlBindings;
import dev.cobolonjava.db2.SqlHostVariable;
import dev.cobolonjava.db2.SqlOperation;
import dev.cobolonjava.db2.SqlOutcome;
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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@Tag("DB2_IT")
@EnabledIfEnvironmentVariable(named = "DB2_IT_ENABLED", matches = "(?i)true")
class Db2ContainerIntegrationTest {

    @Test
    @DisplayName("中立SQL portから実Db2のDMLとWITH HOLD cursorをcommit越しに実行する")
    void executesNativeSqlPortAcrossCommitOnDb2() throws SQLException {
        String url = jdbcUrl();
        String user = required("DB2_USER");
        String password = required("DB2_PASSWORD");
        String table = tableName();
        createFixture(url, user, password, table);

        DriverManagedUnitOfWorkPort port = new DriverManagedUnitOfWorkPort(
                new DriverManagerDb2NativeConnectionProvider(url, user, password));
        DataView selected = Storage.allocate(8).whole();
        DataView fetched = Storage.allocate(8).whole();
        try (CobolSession session = session();
                Db2TaskRuntime task = new Db2TaskRuntime(
                        options(), port, new DriverManagedSqlExecutor())) {
            SqlOutcome inserted = task.execute(statementPlan("I3", SqlOperation.INSERT,
                            "insert into " + table + " (ID, NAME) values (3, 'THREE')"),
                    SqlBindings.NONE, session);
            assertEquals(1, inserted.rowCount());
            task.commit();

            SqlOutcome one = task.execute(statementPlan("S3", SqlOperation.SELECT_ONE,
                            "select NAME from " + table + " where ID = 3"),
                    outputBindings(selected), session);
            assertEquals(0, one.sqlCode());
            assertEquals("THREE   ", CodePages.IBM_1047.decode(selected.toByteArray()));

            SqlPlan open = cursorPlan("OPEN-H", SqlOperation.OPEN_CURSOR,
                    "select NAME from " + table + " order by ID");
            assertEquals(0, task.execute(open, SqlBindings.NONE, session).sqlCode());
            assertEquals(0, task.execute(cursorPlan("FETCH-H", SqlOperation.FETCH_CURSOR,
                    "FETCH HCUR"), outputBindings(fetched), session).sqlCode());
            assertEquals("ONE     ", CodePages.IBM_1047.decode(fetched.toByteArray()));
            task.commit();

            assertEquals(0, task.execute(cursorPlan("FETCH-H2", SqlOperation.FETCH_CURSOR,
                    "FETCH HCUR"), outputBindings(fetched), session).sqlCode());
            assertEquals("TWO     ", CodePages.IBM_1047.decode(fetched.toByteArray()));
            assertEquals(0, task.execute(cursorPlan("CLOSE-H", SqlOperation.CLOSE_CURSOR,
                    "CLOSE HCUR"), SqlBindings.NONE, session).sqlCode());
            task.complete();
        } finally {
            dropFixture(url, user, password, table);
        }
    }

    @Test
    @DisplayName("実Db2で同一connectionのWITH HOLD cursorをcommit後もFETCHできる")
    void fetchesHeldCursorAfterCommitOnDb2() throws SQLException {
        String url = jdbcUrl();
        String user = required("DB2_USER");
        String password = required("DB2_PASSWORD");
        String table = tableName();
        createFixture(url, user, password, table);

        DriverManagerDb2NativeConnectionProvider provider =
                new DriverManagerDb2NativeConnectionProvider(url, user, password);
        DriverManagedUnitOfWorkPort port = new DriverManagedUnitOfWorkPort(provider);
        Connection physical = null;
        ResultSet rows = null;
        Statement statement = null;
        try {
            DriverManagedJdbcUnitOfWork first =
                    (DriverManagedJdbcUnitOfWork) port.begin(options());
            physical = first.connection();
            assertFalse(physical.getAutoCommit());
            assertEquals(ResultSet.HOLD_CURSORS_OVER_COMMIT, physical.getHoldability());
            statement = physical.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT);
            rows = statement.executeQuery("select ID from " + table + " order by ID");
            ResultSet heldRows = rows;
            Statement heldStatement = statement;
            first.registerResource(() -> {
                SQLException failure = null;
                try {
                    heldRows.close();
                } catch (SQLException problem) {
                    failure = problem;
                }
                try {
                    heldStatement.close();
                } catch (SQLException problem) {
                    if (failure == null) {
                        failure = problem;
                    } else {
                        failure.addSuppressed(problem);
                    }
                }
                if (failure != null) {
                    throw failure;
                }
            }, true);

            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            first.commit();

            DriverManagedJdbcUnitOfWork second =
                    (DriverManagedJdbcUnitOfWork) port.begin(options());
            assertSame(physical, second.connection());
            assertTrue(rows.next());
            assertEquals(2, rows.getInt(1));
            second.rollback(new RollbackReason(RollbackReason.Kind.EXPLICIT, "DB2-IT"));
            assertTrue(rows.isClosed());
            port.close();
            assertTrue(physical.isClosed());
        } finally {
            try {
                port.close();
            } finally {
                dropFixture(url, user, password, table);
            }
        }
    }

    private static UnitOfWorkOptions options() {
        return new UnitOfWorkOptions(Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD,
                Duration.ofSeconds(30), false, true);
    }

    private static void createFixture(
            String url, String user, String password, String table) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + table
                    + " (ID integer not null primary key, NAME char(8) not null)");
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into " + table + " (ID, NAME) values (?, ?)")) {
                insert.setInt(1, 1);
                insert.setString(2, "ONE");
                insert.executeUpdate();
                insert.setInt(1, 2);
                insert.setString(2, "TWO");
                insert.executeUpdate();
            }
        }
    }

    private static void dropFixture(
            String url, String user, String password, String table) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("drop table " + table);
        }
    }

    private static String jdbcUrl() {
        return "jdbc:db2://" + value("DB2_HOST", "localhost") + ":"
                + value("DB2_PORT", "50000") + "/"
                + value("DB2_DATABASE", "COBOLDB");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for Db2 integration tests");
        }
        return value;
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String tableName() {
        return "CJH_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private static SqlPlan statementPlan(
            String id, SqlOperation operation, String sql) {
        return new SqlPlan(id, "DB2", operation, sql, CursorOptions.none());
    }

    private static SqlPlan cursorPlan(
            String id, SqlOperation operation, String sql) {
        return new SqlPlan(id, "DB2", operation, sql,
                new CursorOptions("HCUR", true,
                        CursorHoldStrategy.DB2_DRIVER_MANAGED_HOLD,
                        false, false, false, false));
    }

    private static SqlBindings outputBindings(DataView output) {
        return new SqlBindings(java.util.List.of(SqlHostVariable.output(output,
                new SqlValueDescriptor.FixedCharacter(CodePages.IBM_1047), null)));
    }

    private static CobolSession session() {
        return CobolRuntime.builder(ProgramCatalog.builder().build()).build().openSession();
    }
}
