package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.compiler.source.Db2SystemCopyBookResolver;
import dev.cobolonjava.db2.Db2Execution;
import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.Db2TaskRuntime;
import dev.cobolonjava.db2.ResourceLeaseId;
import dev.cobolonjava.db2.RollbackReason;
import dev.cobolonjava.db2.SqlBindingMode;
import dev.cobolonjava.db2.SqlBindings;
import dev.cobolonjava.db2.SqlExecutorPort;
import dev.cobolonjava.db2.SqlHostVariable;
import dev.cobolonjava.db2.SqlOperation;
import dev.cobolonjava.db2.SqlOutcome;
import dev.cobolonjava.db2.SqlPlan;
import dev.cobolonjava.db2.SqlValueDescriptor;
import dev.cobolonjava.db2.UnitOfWork;
import dev.cobolonjava.db2.UnitOfWorkOptions;
import dev.cobolonjava.db2.UnitOfWorkPort;
import dev.cobolonjava.db2.UnitOfWorkState;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.storage.Storage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** EXEC SQL を翻訳し、実行計画と host variable を executor へ渡す結合試験。 */
@Tag("V1")
class Db2GenerationTest {

    private static final class Loader extends ClassLoader {

        Loader() {
            super(Db2GenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static final class FakeUnit implements UnitOfWork {
        UnitOfWorkState state = UnitOfWorkState.ACTIVE;

        @Override
        public Db2ExecutionProfile profile() {
            return Db2ExecutionProfile.SPRING_MANAGED;
        }

        @Override
        public ResourceLeaseId resourceLeaseId() {
            return new ResourceLeaseId("fake");
        }

        @Override
        public UnitOfWorkState state() {
            return state;
        }

        @Override
        public void commit() {
            state = UnitOfWorkState.COMMITTED;
        }

        @Override
        public void rollback(RollbackReason reason) {
            state = UnitOfWorkState.ROLLED_BACK;
        }
    }

    private static final class FakeExecutor implements SqlExecutorPort {
        final List<SqlPlan> plans = new ArrayList<>();
        final List<SqlBindings> bindings = new ArrayList<>();

        @Override
        public Db2ExecutionProfile profile() {
            return Db2ExecutionProfile.SPRING_MANAGED;
        }

        @Override
        public SqlOutcome execute(SqlPlan plan, SqlBindings values, CobolSession session, UnitOfWork unit) {
            plans.add(plan);
            bindings.add(values);
            return switch (plan.operation()) {
                case SELECT_ONE -> {
                    SqlHostVariable output = values.values().stream()
                            .filter(value -> value.mode() == SqlBindingMode.OUTPUT).findFirst().orElseThrow();
                    output.value().setBytes(((SqlValueDescriptor.FixedCharacter) output.descriptor())
                            .codePage().encode("ALICE     "));
                    yield SqlOutcome.success(1);
                }
                case FETCH_CURSOR -> new SqlOutcome(100, "02000", 0, List.of(), false);
                default -> SqlOutcome.success(1);
            };
        }
    }

    private static String source(String... lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append("       ").append(line).append('\n');
        }
        return out.toString();
    }

    private static final String[] DATA = {
        "IDENTIFICATION DIVISION.",
        "PROGRAM-ID. SQLPGM.",
        "DATA DIVISION.",
        "WORKING-STORAGE SECTION.",
        "EXEC SQL INCLUDE SQLCA END-EXEC.",
        "EXEC SQL DECLARE ACCOUNT TABLE",
        "    ( ACCOUNT_NUMBER CHAR(8) NOT NULL,",
        "      ACCOUNT_NAME CHAR(10) )",
        "END-EXEC.",
        "01  HV-NUMBER PIC X(8) VALUE 'A0000001'.",
        "01  HV-NAME PIC X(10).",
        "01  HV-BALANCE PIC S9(8)V99 COMP-3 VALUE 12.34.",
        "01  HV-GROUP.",
        "    05  HV-PART PIC X(2).",
        "EXEC SQL DECLARE ACC-CURSOR CURSOR FOR",
        "    SELECT ACCOUNT_NAME FROM ACCOUNT",
        "    WHERE ACCOUNT_NUMBER = :HV-NUMBER",
        "END-EXEC.",
        "LINKAGE SECTION.",
        "01  LK-AREA PIC X(4).",
        "PROCEDURE DIVISION USING LK-AREA.",
    };

    private static String program(String... procedure) {
        List<String> lines = new ArrayList<>(List.of(DATA));
        for (String line : procedure) {
            lines.add("    " + line);
        }
        return source(lines.toArray(String[]::new));
    }

    @Test
    @DisplayName("SELECT INTO・UPDATE・cursor・COMMIT を計画と host variable にして executor へ渡し、SQLCA を書き戻す")
    void executesTranslatedSql() throws ReflectiveOperationException {
        CobolCompiler.Result result = CobolCompiler.with(new Db2SystemCopyBookResolver())
                .compile("SQLPGM.cbl", program(
                        "EXEC SQL SELECT ACCOUNT_NAME INTO :HV-NAME",
                        "     FROM ACCOUNT WHERE ACCOUNT_NUMBER = :HV-NUMBER",
                        "END-EXEC",
                        "IF SQLCODE NOT = 0 OR HV-NAME NOT = 'ALICE'",
                        "    GOBACK",
                        "END-IF",
                        "EXEC SQL UPDATE ACCOUNT SET ACCOUNT_BALANCE = :HV-BALANCE",
                        "     WHERE ACCOUNT_NUMBER = :HV-NUMBER",
                        "END-EXEC",
                        "EXEC SQL OPEN ACC-CURSOR END-EXEC",
                        "EXEC SQL FETCH ACC-CURSOR INTO :HV-NAME END-EXEC",
                        "IF SQLCODE = 100 AND SQLSTATE = '02000'",
                        "   AND SQLCAID = 'SQLCA'",
                        "    MOVE 'PASS' TO LK-AREA",
                        "END-IF",
                        "EXEC SQL CLOSE ACC-CURSOR END-EXEC",
                        "EXEC SQL COMMIT WORK END-EXEC",
                        "GOBACK."));
        assertTrue(result.succeeded(), result.diagnostics().toString());

        Loader loader = new Loader();
        Class<?> type = loader.define(result.className(), result.classFile());
        FakeUnit unit = new FakeUnit();
        FakeExecutor executor = new FakeExecutor();
        UnitOfWorkPort port = new UnitOfWorkPort() {
            @Override
            public Db2ExecutionProfile profile() {
                return Db2ExecutionProfile.SPRING_MANAGED;
            }

            @Override
            public UnitOfWork begin(UnitOfWorkOptions options) {
                return unit;
            }

            @Override
            public void close() {
            }
        };
        Db2Execution execution = new Db2Execution(new Db2TaskRuntime(new UnitOfWorkOptions(
                Db2ExecutionProfile.SPRING_MANAGED, Duration.ofSeconds(5), false, false), port, executor));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("SQLPGM", () -> {
                    try {
                        return (CobolProgram) type.getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                })
                .build();
        Storage area = Storage.allocate(4);
        area.whole().setBytes(new byte[4]);
        try (CobolSession session = CobolRuntime.builder(catalog).classLoader(loader).build()
                .openSession(RuntimeServices.builder().service(Db2Execution.class, execution).build())) {
            execution.bind(session);
            session.runMain("SQLPGM", area.whole());
        }

        assertEquals("PASS", dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.decode(area.array()));
        assertEquals(List.of(SqlOperation.SELECT_ONE, SqlOperation.UPDATE, SqlOperation.OPEN_CURSOR,
                SqlOperation.FETCH_CURSOR, SqlOperation.CLOSE_CURSOR),
                executor.plans.stream().map(SqlPlan::operation).toList());
        assertEquals("SELECT ACCOUNT_NAME FROM ACCOUNT WHERE ACCOUNT_NUMBER = ?",
                executor.plans.get(0).normalizedSql());
        assertTrue(executor.plans.get(0).statementId().startsWith("SQLPGM.cbl:"));
        assertEquals(List.of(SqlBindingMode.INPUT, SqlBindingMode.OUTPUT),
                executor.bindings.get(0).values().stream().map(SqlHostVariable::mode).toList());
        SqlValueDescriptor.PackedDecimal balance = assertInstanceOf(SqlValueDescriptor.PackedDecimal.class,
                executor.bindings.get(1).values().get(0).descriptor());
        assertEquals(10, balance.digits());
        assertEquals(2, balance.scale());
        assertEquals("ACC-CURSOR", executor.plans.get(2).cursorOptions().cursorName());
        assertEquals("SELECT ACCOUNT_NAME FROM ACCOUNT WHERE ACCOUNT_NUMBER = ?",
                executor.plans.get(2).normalizedSql());
        assertEquals(1, executor.bindings.get(2).values().size());
        assertEquals(List.of(SqlBindingMode.OUTPUT),
                executor.bindings.get(3).values().stream().map(SqlHostVariable::mode).toList());
        assertEquals(UnitOfWorkState.COMMITTED, unit.state);
    }

    @Test
    @DisplayName("翻訳できない SQL、SQLCA の無い program、宣言の無い cursor、群の host variable は断る")
    void rejectsUntranslatableSql() {
        for (String[] rejected : List.of(
                new String[] {"EXEC SQL PREPARE S1 FROM :HV-NAME END-EXEC", "PREPARE"},
                new String[] {"EXEC SQL OPEN OTHER-CURSOR END-EXEC", "not declared before use"},
                new String[] {"EXEC SQL SELECT A INTO :HV-GROUP FROM T END-EXEC",
                        "must be CHAR, packed, zoned or binary"})) {
            CobolCompiler.Result result = CobolCompiler.with(new Db2SystemCopyBookResolver())
                    .compile("SQLPGM.cbl", program(rejected[0], "GOBACK."));

            assertFalse(result.succeeded(), rejected[0]);
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains(rejected[1])),
                    rejected[0] + " " + result.diagnostics());
        }

        CobolCompiler.Result noSqlca = CobolCompiler.standard().compile("NOSQLCA.cbl", source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. NOSQLCA.",
                "PROCEDURE DIVISION.",
                "    EXEC SQL COMMIT WORK END-EXEC",
                "    GOBACK."));
        assertFalse(noSqlca.succeeded());
        assertTrue(noSqlca.diagnostics().stream().anyMatch(d -> d.message().contains("INCLUDE SQLCA")),
                noSqlca.diagnostics().toString());
    }
}
