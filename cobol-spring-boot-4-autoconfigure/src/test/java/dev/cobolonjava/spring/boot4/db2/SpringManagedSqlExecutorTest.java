package dev.cobolonjava.spring.boot4.db2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.db2.CursorOptions;
import dev.cobolonjava.db2.CursorHoldStrategy;
import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.Db2ProfileMismatchException;
import dev.cobolonjava.db2.Db2TaskRuntime;
import dev.cobolonjava.db2.RollbackReason;
import dev.cobolonjava.db2.SqlBindingMode;
import dev.cobolonjava.db2.SqlBindings;
import dev.cobolonjava.db2.SqlHostVariable;
import dev.cobolonjava.db2.SqlOperation;
import dev.cobolonjava.db2.SqlOutcome;
import dev.cobolonjava.db2.SqlPlan;
import dev.cobolonjava.db2.SqlValueDescriptor;
import dev.cobolonjava.db2.UnitOfWork;
import dev.cobolonjava.db2.UnitOfWorkOptions;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.data.BinaryDecimal;
import dev.cobolonjava.runtime.data.NumProcMode;
import dev.cobolonjava.runtime.data.PackedDecimal;
import dev.cobolonjava.runtime.data.TruncMode;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.CobolSessionStateException;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

@Tag("V1")
class SpringManagedSqlExecutorTest {

    @Test
    @DisplayName("COBOL host変数でDMLと単一行SELECTを実行して同じSpring UOWで確定する")
    void executesDmlAndSelectWithCobolHostVariables() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = prepare(dataSource);
        SpringManagedUnitOfWorkPort port = port(dataSource);
        SpringManagedSqlExecutor executor = new SpringManagedSqlExecutor(dataSource);
        try (CobolSession session = session();
                Db2TaskRuntime task = new Db2TaskRuntime(options(), port, executor)) {
            SqlOutcome inserted = task.execute(
                    plan("I1", SqlOperation.INSERT,
                            "insert into account(id, name, amount) values (?, ?, ?)"),
                    new SqlBindings(List.of(
                            inputBinary(1), inputText("ALICE   ", 8), inputPacked("123.45"))),
                    session);
            assertEquals(1, inserted.rowCount());
            task.commit();

            DataView name = Storage.allocate(8).whole();
            DataView amount = Storage.allocate(PackedDecimal.byteLength(5)).whole();
            SqlOutcome selected = task.execute(
                    plan("S1", SqlOperation.SELECT_ONE,
                            "select name, amount from account where id = ?"),
                    new SqlBindings(List.of(
                            inputBinary(1),
                            outputText(name),
                            outputPacked(amount, null))),
                    session);

            assertEquals(0, selected.sqlCode());
            assertEquals(1, selected.rowCount());
            assertEquals("ALICE   ", CodePages.IBM_1047.decode(name.toByteArray()));
            assertEquals(Decimal.parse("123.45"), PackedDecimal.decode(
                    amount.toByteArray(), 2, NumProcMode.NOPFD));
            task.complete();
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from account", Integer.class));
    }

    @Test
    @DisplayName("executor更新はtask rollbackで取り消される")
    void participatesInSpringRollback() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = prepare(dataSource);
        try (CobolSession session = session();
                Db2TaskRuntime task = new Db2TaskRuntime(options(), port(dataSource),
                        new SpringManagedSqlExecutor(dataSource))) {
            task.execute(plan("I1", SqlOperation.INSERT,
                            "insert into account(id, name, amount) values (?, ?, ?)"),
                    new SqlBindings(List.of(
                            inputBinary(1), inputText("ROLLBACK", 8), inputPacked("1.00"))),
                    session);
            task.rollback(new RollbackReason(RollbackReason.Kind.EXPLICIT, "TEST"));
            task.complete();
        }

        assertEquals(0, jdbc.queryForObject("select count(*) from account", Integer.class));
    }

    @Test
    @DisplayName("該当なしを+100とし出力storageを変更しない")
    void reportsNoDataWithoutMutatingOutput() {
        DataSource dataSource = dataSource();
        prepare(dataSource);
        DataView output = view(CodePages.IBM_1047.encode("KEEP"));
        try (CobolSession session = session();
                Db2TaskRuntime task = new Db2TaskRuntime(options(), port(dataSource),
                        new SpringManagedSqlExecutor(dataSource))) {
            SqlOutcome outcome = task.execute(
                    plan("S100", SqlOperation.SELECT_ONE,
                            "select name from account where id = ?"),
                    new SqlBindings(List.of(inputBinary(99), outputText(output))), session);

            assertEquals(100, outcome.sqlCode());
            assertEquals("02000", outcome.sqlState());
            assertArrayEquals(CodePages.IBM_1047.encode("KEEP"), output.toByteArray());
            task.complete();
        }
    }

    @Test
    @DisplayName("SELECTしたSQL NULLは値を維持して2byte indicatorを-1にする")
    void writesNullIndicatorWithoutChangingValue() {
        DataSource dataSource = dataSource();
        prepare(dataSource);
        DataView value = view(CodePages.IBM_1047.encode("KEEP"));
        DataView indicator = Storage.allocate(2).whole();
        try (CobolSession session = session();
                Db2TaskRuntime task = new Db2TaskRuntime(options(), port(dataSource),
                        new SpringManagedSqlExecutor(dataSource))) {
            SqlOutcome outcome = task.execute(
                    plan("NULL", SqlOperation.SELECT_ONE,
                            "select cast(null as varchar(4))"),
                    new SqlBindings(List.of(SqlHostVariable.output(value,
                            new SqlValueDescriptor.FixedCharacter(CodePages.IBM_1047),
                            indicator))), session);

            assertEquals(0, outcome.sqlCode());
            assertArrayEquals(CodePages.IBM_1047.encode("KEEP"), value.toByteArray());
            assertEquals(-1, BinaryDecimal.decode(indicator.toByteArray(), 0).signum());
            task.complete();
        }
    }

    @Test
    @DisplayName("複数行SELECTを-811とし出力storageを変更しない")
    void reportsMultipleRowsWithoutMutatingOutput() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = prepare(dataSource);
        jdbc.update("insert into account values (1, 'A', 1)");
        jdbc.update("insert into account values (2, 'B', 2)");
        DataView output = view(CodePages.IBM_1047.encode("KEEP"));
        try (CobolSession session = session();
                Db2TaskRuntime task = new Db2TaskRuntime(options(), port(dataSource),
                        new SpringManagedSqlExecutor(dataSource))) {
            SqlOutcome outcome = task.execute(
                    plan("S811", SqlOperation.SELECT_ONE,
                            "select name from account order by id"),
                    new SqlBindings(List.of(outputText(output))), session);

            assertEquals(-811, outcome.sqlCode());
            assertEquals("21000", outcome.sqlState());
            assertArrayEquals(CodePages.IBM_1047.encode("KEEP"), output.toByteArray());
            task.complete();
        }
    }

    @Test
    @DisplayName("全SELECT出力の検証完了前は一項目もstorageへ反映しない")
    void appliesSelectOutputsAllOrNothing() {
        DataSource dataSource = dataSource();
        prepare(dataSource);
        DataView text = view(CodePages.IBM_1047.encode("KEEP"));
        DataView tooSmall = view(PackedDecimal.encode(Decimal.parse("111"), 3, 0, true));
        try (CobolSession session = session();
                Db2TaskRuntime task = new Db2TaskRuntime(options(), port(dataSource),
                        new SpringManagedSqlExecutor(dataSource))) {
            assertThrows(ArithmeticException.class, () -> task.execute(
                    plan("LOSS", SqlOperation.SELECT_ONE,
                            "select 'OK', cast(1234 as decimal(4,0))"),
                    new SqlBindings(List.of(outputText(text),
                            new SqlHostVariable(tooSmall, SqlBindingMode.OUTPUT,
                                    new SqlValueDescriptor.PackedDecimal(3, 0, true), null))),
                    session));
        }

        assertArrayEquals(CodePages.IBM_1047.encode("KEEP"), text.toByteArray());
        assertEquals(Decimal.parse("111"), PackedDecimal.decode(
                tooSmall.toByteArray(), 0, NumProcMode.NOPFD));
    }

    @Test
    @DisplayName("JDBC例外のcode、SQLSTATE、chainをSqlOutcomeへ保存する")
    void capturesJdbcFailureDiagnostics() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = prepare(dataSource);
        jdbc.update("insert into account values (1, 'A', 1)");
        try (CobolSession session = session();
                Db2TaskRuntime task = new Db2TaskRuntime(options(), port(dataSource),
                        new SpringManagedSqlExecutor(dataSource))) {
            SqlOutcome outcome = task.execute(
                    plan("DUP", SqlOperation.INSERT,
                            "insert into account(id, name, amount) values (?, ?, ?)"),
                    new SqlBindings(List.of(
                            inputBinary(1), inputText("DUP     ", 8), inputPacked("1.00"))),
                    session);

            assertEquals("23505", outcome.sqlState());
            assertFalse(outcome.diagnostics().isEmpty());
            assertEquals("23505", outcome.diagnostics().getFirst().sqlState());
            task.rollback(RollbackReason.cleanup());
            task.complete();
        }
    }

    @Test
    @DisplayName("異なるDataSourceのUOWとexecutorをSQL取得前に拒否する")
    void rejectsMismatchedUnitOfWorkResource() {
        DataSource first = dataSource();
        DataSource second = dataSource();
        SpringManagedUnitOfWorkPort port = port(first);
        UnitOfWork unit = port.begin(options());

        try (CobolSession session = session()) {
            assertThrows(Db2ProfileMismatchException.class,
                    () -> new SpringManagedSqlExecutor(second).execute(
                            plan("D", SqlOperation.DELETE, "delete from account"),
                            SqlBindings.NONE, session, unit));
        }
        unit.rollback(RollbackReason.cleanup());
        port.close();
    }

    @Test
    @DisplayName("閉じたCOBOL sessionをSQL取得前に拒否する")
    void rejectsClosedCobolSession() {
        DataSource dataSource = dataSource();
        SpringManagedUnitOfWorkPort port = port(dataSource);
        UnitOfWork unit = port.begin(options());
        CobolSession session = session();
        session.close();

        assertThrows(CobolSessionStateException.class,
                () -> new SpringManagedSqlExecutor(dataSource).execute(
                        plan("D", SqlOperation.DELETE, "delete from account"),
                        SqlBindings.NONE, session, unit));
        unit.rollback(RollbackReason.cleanup());
        port.close();
    }

    @Test
    @DisplayName("非hold cursorをOPEN、反復FETCH、終端+100、CLOSEできる")
    void opensFetchesAndClosesCursor() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = prepare(dataSource);
        jdbc.update("insert into account values (1, 'ALICE', 10.25)");
        jdbc.update("insert into account values (2, 'BOB', 20.50)");
        DataView name = Storage.allocate(8).whole();
        DataView amount = Storage.allocate(PackedDecimal.byteLength(5)).whole();
        try (CobolSession session = session();
                Db2TaskRuntime task = new Db2TaskRuntime(options(), port(dataSource),
                        new SpringManagedSqlExecutor(dataSource))) {
            SqlOutcome opened = task.execute(cursorPlan("OPEN-C1", SqlOperation.OPEN_CURSOR,
                            "select name, amount from account where id >= ? order by id", "C1"),
                    new SqlBindings(List.of(inputBinary(1))), session);
            assertEquals(0, opened.sqlCode());

            SqlBindings outputs = new SqlBindings(List.of(
                    outputText(name), outputPacked(amount, null)));
            assertEquals(1, task.execute(cursorPlan("FETCH-C1", SqlOperation.FETCH_CURSOR,
                    "FETCH C1", "C1"), outputs, session).rowCount());
            assertEquals("ALICE   ", CodePages.IBM_1047.decode(name.toByteArray()));
            assertEquals(Decimal.parse("10.25"), PackedDecimal.decode(
                    amount.toByteArray(), 2, NumProcMode.NOPFD));

            assertEquals(1, task.execute(cursorPlan("FETCH-C1", SqlOperation.FETCH_CURSOR,
                    "FETCH C1", "C1"), outputs, session).rowCount());
            assertEquals("BOB     ", CodePages.IBM_1047.decode(name.toByteArray()));
            byte[] lastName = name.toByteArray();
            byte[] lastAmount = amount.toByteArray();

            SqlOutcome exhausted = task.execute(cursorPlan("FETCH-C1", SqlOperation.FETCH_CURSOR,
                    "FETCH C1", "C1"), outputs, session);
            assertEquals(100, exhausted.sqlCode());
            assertArrayEquals(lastName, name.toByteArray());
            assertArrayEquals(lastAmount, amount.toByteArray());

            assertEquals(0, task.execute(cursorPlan("CLOSE-C1", SqlOperation.CLOSE_CURSOR,
                    "CLOSE C1", "C1"), SqlBindings.NONE, session).sqlCode());
            assertThrows(IllegalStateException.class,
                    () -> task.execute(cursorPlan("FETCH-C1", SqlOperation.FETCH_CURSOR,
                            "FETCH C1", "C1"), outputs, session));
        }
    }

    @Test
    @DisplayName("commitで非hold cursorを閉じ次UOWからのFETCHを拒否する")
    void closesCursorAtCommitBoundary() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = prepare(dataSource);
        jdbc.update("insert into account values (1, 'ALICE', 1)");
        DataView name = Storage.allocate(8).whole();
        try (CobolSession session = session();
                Db2TaskRuntime task = new Db2TaskRuntime(options(), port(dataSource),
                        new SpringManagedSqlExecutor(dataSource))) {
            task.execute(cursorPlan("OPEN-C2", SqlOperation.OPEN_CURSOR,
                    "select name from account", "C2"), SqlBindings.NONE, session);
            task.commit();

            assertThrows(IllegalStateException.class,
                    () -> task.execute(cursorPlan("FETCH-C2", SqlOperation.FETCH_CURSOR,
                                    "FETCH C2", "C2"),
                            new SqlBindings(List.of(outputText(name))), session));
        }
    }

    @Test
    @DisplayName("Spring cursor経路ではWITH HOLDを明示的に拒否する")
    void rejectsWithHoldCursor() {
        DataSource dataSource = dataSource();
        prepare(dataSource);
        CursorOptions hold = new CursorOptions("HC", true,
                CursorHoldStrategy.PORTABLE_SPOOL, false, false, false, false);
        SqlPlan plan = new SqlPlan("OPEN-HC", "DB2", SqlOperation.OPEN_CURSOR,
                "select name from account", hold);
        try (CobolSession session = session();
                Db2TaskRuntime task = new Db2TaskRuntime(options(), port(dataSource),
                        new SpringManagedSqlExecutor(dataSource))) {
            UnsupportedOperationException failure = assertThrows(
                    UnsupportedOperationException.class,
                    () -> task.execute(plan, SqlBindings.NONE, session));
            assertEquals("WITH HOLD requires the DB2_DRIVER_MANAGED_HOLD profile: HC",
                    failure.getMessage());
        }
    }

    private static SpringManagedUnitOfWorkPort port(DataSource dataSource) {
        return new SpringManagedUnitOfWorkPort(
                dataSource, new JdbcTransactionManager(dataSource));
    }

    private static UnitOfWorkOptions options() {
        return new UnitOfWorkOptions(Db2ExecutionProfile.SPRING_MANAGED,
                Duration.ofSeconds(5), false, false);
    }

    private static SqlPlan plan(String id, SqlOperation operation, String sql) {
        return new SqlPlan(id, "DB2", operation, sql, CursorOptions.none());
    }

    private static SqlPlan cursorPlan(
            String id, SqlOperation operation, String sql, String cursorName) {
        return new SqlPlan(id, "DB2", operation, sql,
                new CursorOptions(cursorName, false, CursorHoldStrategy.NOT_HELD,
                        false, false, false, false));
    }

    private static SqlHostVariable inputBinary(int value) {
        return SqlHostVariable.input(view(BinaryDecimal.encode(
                        Decimal.of(value, 0), 4, 0, TruncMode.BIN)),
                new SqlValueDescriptor.BinaryInteger(4, 0, TruncMode.BIN), null);
    }

    private static SqlHostVariable inputText(String value, int length) {
        if (value.length() != length) {
            throw new IllegalArgumentException("test text length mismatch");
        }
        return SqlHostVariable.input(view(CodePages.IBM_1047.encode(value)),
                new SqlValueDescriptor.FixedCharacter(CodePages.IBM_1047), null);
    }

    private static SqlHostVariable inputPacked(String value) {
        return SqlHostVariable.input(view(PackedDecimal.encode(
                        Decimal.parse(value), 5, 2, true)),
                new SqlValueDescriptor.PackedDecimal(5, 2, true), null);
    }

    private static SqlHostVariable outputText(DataView value) {
        return SqlHostVariable.output(value,
                new SqlValueDescriptor.FixedCharacter(CodePages.IBM_1047), null);
    }

    private static SqlHostVariable outputPacked(DataView value, DataView indicator) {
        return SqlHostVariable.output(value,
                new SqlValueDescriptor.PackedDecimal(5, 2, true), indicator);
    }

    private static DataView view(byte[] bytes) {
        return Storage.copyOf(bytes).whole();
    }

    private static JdbcTemplate prepare(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table account("
                + "id int primary key, name varchar(8), amount decimal(5,2))");
        return jdbc;
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static CobolSession session() {
        return CobolRuntime.builder(ProgramCatalog.builder().build()).build().openSession();
    }
}
