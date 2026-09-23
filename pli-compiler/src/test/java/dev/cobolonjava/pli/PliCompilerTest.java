package dev.cobolonjava.pli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.interop.DeployCatalogManifest;
import dev.cobolonjava.db2.Db2Execution;
import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.Db2TaskRuntime;
import dev.cobolonjava.db2.ResourceLeaseId;
import dev.cobolonjava.db2.RollbackReason;
import dev.cobolonjava.db2.SqlBindings;
import dev.cobolonjava.db2.SqlExecutorPort;
import dev.cobolonjava.db2.SqlOperation;
import dev.cobolonjava.db2.SqlOutcome;
import dev.cobolonjava.db2.SqlPlan;
import dev.cobolonjava.db2.UnitOfWork;
import dev.cobolonjava.db2.UnitOfWorkOptions;
import dev.cobolonjava.db2.UnitOfWorkPort;
import dev.cobolonjava.db2.UnitOfWorkState;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class PliCompilerTest {

    @TempDir
    Path temporary;

    /**
     * PRINT ファイルの list-directed は、項目を左端と tab 位置 25, 49, 73 に揃える
     * (LRM "PRINT attribute")。N は FIXED BIN(31) なので幅 14 の欄に右寄せになる。
     */
    private static final String HELLO_LINE = String.format("%-24s%-24s%-24s%14s",
            "HELLO ", "WORLD", " ", "3") + System.lineSeparator();

    private static final String PROGRAM = """
            HELLO: PROCEDURE OPTIONS(MAIN);
              DCL WHO CHAR(8) INIT('WORLD');
              DCL N FIXED BIN(31) INIT(0);
              DO WHILE (N < 3);
                N = N + 1;
              END;
              IF N = 3 THEN CALL SAY;
              RETURN;

              SAY: PROCEDURE;
                PUT SKIP LIST('HELLO ', TRIM(WHO), ' ', N);
                RETURN;
              END SAY;
            END HELLO;
            """;

    @Test
    void generatedClassExecutesStructuredPli() throws Exception {
        PliCompiler.Result result = PliCompiler.standard().compile("HELLO.pli", PROGRAM);
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("cobol.generated.HELLO", result.className());

        Class<?> generated = new GeneratedLoader().define(result.className(), result.classFile());
        CobolProgram program = (CobolProgram) generated.getDeclaredConstructor().newInstance();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        program.runFresh(ProgramContext.capturing(output));

        assertEquals(HELLO_LINE,
                output.toString(StandardCharsets.UTF_8));
        assertNotNull(program.programSignature());
        assertNotNull(program.procedureManifest());
    }

    @Test
    void iterativeDoSupportsPositiveAndNegativeSteps() throws Exception {
        PliCompiler.Result result = PliCompiler.standard().compile("LOOPS.pli", """
                LOOPS: PROCEDURE OPTIONS(MAIN);
                  DCL I FIXED BIN(31);
                  DCL TOTAL FIXED BIN(31) INIT(0);
                  DO I = 1 TO 5;
                    TOTAL = TOTAL + I;
                  END;
                  DO I = 5 TO 1 BY -2;
                    TOTAL = TOTAL + I;
                  END;
                  PUT SKIP LIST(TOTAL);
                END LOOPS;
                """);
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        Class<?> generated = new GeneratedLoader().define(result.className(), result.classFile());
        CobolProgram program = (CobolProgram) generated.getDeclaredConstructor().newInstance();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        program.runFresh(ProgramContext.capturing(output));

        // TOTAL は FIXED BIN(31)。文字にすると 10 進の精度 11、幅 14 の欄に右寄せになる
        assertEquals(String.format("%14s", "24") + System.lineSeparator(),
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void bankOfZPliAssetsAreAcceptedWithoutSourceChanges() throws Exception {
        Path root = Path.of("..", "reference", "Bank-of-Z-main", "src", "base");
        for (Path source : new Path[] {
                root.resolve(Path.of("batch", "pli", "BNKSTMT.pli")),
                root.resolve(Path.of("ims", "pli", "IBLOGIN.pli"))}) {
            PliCompiler.Result result = PliCompiler.standard().compile(
                    source.getFileName().toString(), Files.readString(source));
            assertTrue(result.succeeded(), () -> source + ": " + result.diagnostics());
        }
    }

    @Test
    void processAndIncludeAreExpandedBeforeParsing() {
        PliPreprocessor preprocessor = new PliPreprocessor(name ->
                name.equals("BODY") ? java.util.Optional.of("PUT SKIP LIST('OK');")
                        : java.util.Optional.empty());
        PliCompiler.Result result = new PliCompiler(preprocessor).compile("INCLUDE.pli", """
                *PROCESS SYSTEM(IMS);
                INCLUDE: PROCEDURE OPTIONS(MAIN);
                  %INCLUDE BODY;
                END INCLUDE;
                """);
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
    }

    @Test
    void commandLineWritesADeployCatalog() throws Exception {
        Path source = temporary.resolve("HELLO.pli");
        Path output = temporary.resolve("out");
        Files.writeString(source, PROGRAM);

        Main.main(new String[] {"-d", output.toString(), source.toString()});

        assertTrue(Files.isRegularFile(output.resolve("cobol/generated/HELLO.class")));
        String catalog = Files.readString(output.resolve("META-INF/cobol/programs.json"));
        assertTrue(catalog.contains("\"programId\": \"HELLO\""));
        assertTrue(catalog.contains("\"allowedPackage\": \"cobol.generated\""));
        DeployCatalogManifest manifest = DeployCatalogManifest.fromJson(catalog);
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {
                output.toUri().toURL()}, PliCompilerTest.class.getClassLoader())) {
            CobolProgram deployed = manifest.toProgramCatalog()
                    .resolve(ProgramId.of("HELLO"), loader);
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            deployed.runFresh(ProgramContext.capturing(captured));
            assertEquals(HELLO_LINE,
                    captured.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void embeddedSqlUsesTheSharedDb2Port() throws Exception {
        String source = """
                PLISQL: PROCEDURE OPTIONS(MAIN);
                  EXEC SQL INCLUDE SQLCA;
                  DCL KEY CHAR(8) INIT('A0000001');
                  DCL NAME CHAR(10);
                  EXEC SQL SELECT ACCOUNT_NAME INTO :NAME
                           FROM ACCOUNT WHERE ACCOUNT_NUMBER = :KEY;
                  IF SQLCODE = 0 THEN PUT SKIP LIST(TRIM(NAME));
                  EXEC SQL COMMIT WORK;
                  RETURN;
                END PLISQL;
                """;
        PliCompiler.Result result = PliCompiler.standard().compile("PLISQL.pli", source);
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        GeneratedLoader loader = new GeneratedLoader();
        Class<?> generated = loader.define(result.className(), result.classFile());
        CobolProgram program = (CobolProgram) generated.getDeclaredConstructor().newInstance();
        RecordingSql sql = new RecordingSql();
        FakeUnit unit = new FakeUnit();
        UnitOfWorkPort units = new UnitOfWorkPort() {
            public Db2ExecutionProfile profile() { return Db2ExecutionProfile.SPRING_MANAGED; }
            public UnitOfWork begin(UnitOfWorkOptions options) { return unit; }
            public void close() { }
        };
        Db2Execution execution = new Db2Execution(new Db2TaskRuntime(new UnitOfWorkOptions(
                Db2ExecutionProfile.SPRING_MANAGED, Duration.ofSeconds(5), false, false),
                units, sql));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("PLISQL", program.programSignature(), () -> program).build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CobolSession session = CobolRuntime.builder(catalog).classLoader(loader).build()
                .openSession(output, RuntimeServices.builder()
                        .service(Db2Execution.class, execution).build())) {
            execution.bind(session);
            session.runMain("PLISQL");
        }

        assertEquals("ALICE" + System.lineSeparator(), output.toString(StandardCharsets.UTF_8));
        assertEquals(List.of(SqlOperation.SELECT_ONE),
                sql.plans.stream().map(SqlPlan::operation).toList());
        assertEquals("SELECT ACCOUNT_NAME FROM ACCOUNT WHERE ACCOUNT_NUMBER = ?",
                sql.plans.get(0).normalizedSql());
        assertEquals(UnitOfWorkState.COMMITTED, unit.state);
    }

    private static final class FakeUnit implements UnitOfWork {
        UnitOfWorkState state = UnitOfWorkState.ACTIVE;
        public Db2ExecutionProfile profile() { return Db2ExecutionProfile.SPRING_MANAGED; }
        public ResourceLeaseId resourceLeaseId() { return new ResourceLeaseId("pli-test"); }
        public UnitOfWorkState state() { return state; }
        public void commit() { state = UnitOfWorkState.COMMITTED; }
        public void rollback(RollbackReason reason) { state = UnitOfWorkState.ROLLED_BACK; }
    }

    private static final class RecordingSql implements SqlExecutorPort {
        final List<SqlPlan> plans = new ArrayList<>();
        public Db2ExecutionProfile profile() { return Db2ExecutionProfile.SPRING_MANAGED; }
        public SqlOutcome execute(SqlPlan plan, SqlBindings bindings,
                                  CobolSession session, UnitOfWork unit) {
            plans.add(plan);
            bindings.values().stream().filter(value -> value.mode().isOutput()).findFirst()
                    .orElseThrow().value().setBytes(CodePages.DEFAULT.encode("ALICE     "));
            return SqlOutcome.success(1);
        }
    }

    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader() {
            super(PliCompilerTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
