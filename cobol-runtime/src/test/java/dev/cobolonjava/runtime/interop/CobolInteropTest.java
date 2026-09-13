package dev.cobolonjava.runtime.interop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.procedure.ProcedureDescriptor;
import dev.cobolonjava.runtime.procedure.ProcedureId;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Java / COBOL 共通カタログと実行単位の契約。 */
@Tag("V1")
class CobolInteropTest {

    private static final class CounterProgram implements CobolProgram {

        @Override
        public byte[] initialStorage() {
            return new byte[] {0};
        }

        @Override
        public void run(Storage storage, ProgramContext context, DataView[] arguments) {
            storage.array()[0]++;
            if (arguments.length > 0) {
                arguments[0].set(0, storage.array()[0]);
            }
            Ops.programReturn();
        }
    }

    private static final class ExitProgram implements CobolProgram {

        @Override
        public byte[] initialStorage() {
            return new byte[] {0};
        }

        @Override
        public void run(Storage storage, ProgramContext context, DataView[] arguments) {
            Ops.exitProgram(context);
            storage.array()[0] = 1;
        }
    }

    private static final class StopProgram implements CobolProgram {

        @Override
        public byte[] initialStorage() {
            return new byte[0];
        }

        @Override
        public void run(Storage storage, ProgramContext context, DataView[] arguments) {
            context.setReturnCode(12);
            Ops.stopRun();
        }
    }

    private static final class CallsJavaProgram implements CobolProgram {

        @Override
        public byte[] initialStorage() {
            return new byte[0];
        }

        @Override
        public void run(Storage storage, ProgramContext context, DataView[] arguments) {
            Ops.call(context, "JAVASUB", getClass().getClassLoader(), arguments);
            Ops.programReturn();
        }
    }

    private static ProgramCatalog.Builder programs() {
        return ProgramCatalog.builder().revision("test-r1")
                .cobolProgram("COUNTER", CounterProgram::new)
                .cobolProgram("EXITPGM", ExitProgram::new)
                .cobolProgram("STOPPGM", StopProgram::new);
    }

    @Test
    @DisplayName("program name は大文字小文字と周囲の空白を区別しない")
    void programIdsAreNormalized() {
        assertEquals(ProgramId.of("PAYROLL"), ProgramId.of(" payroll "));
        assertEquals(ProgramId.of("SUB1"), ProgramId.from(
                CodePages.DEFAULT.encode("sub1   "), CodePages.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> ProgramId.of("  "));
        assertThrows(IllegalArgumentException.class, () -> ProgramId.of("A\nB"));
    }

    @Test
    @DisplayName("正規化後に同名になる登録は起動前に拒否する")
    void duplicateRegistrationsAreRejected() {
        ProgramCatalog.Builder builder = ProgramCatalog.builder()
                .cobolProgram("sub-a", CounterProgram::new);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> builder.javaProgram(" SUB-A ", () -> (context, arguments) -> { }));

        assertTrue(failure.getMessage().contains("SUB-A"));
    }

    @Test
    @DisplayName("登録 factory の障害を未登録エラーへ読み替えない")
    void factoryFailuresAreNotReportedAsMissingPrograms() {
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("BROKEN", () -> {
                    throw new IllegalStateException("factory failed");
                })
                .build();

        try (CobolSession session = CobolRuntime.builder(catalog).build().openSession()) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> session.call("BROKEN"));
            assertEquals("factory failed", failure.getMessage());
        }
    }

    @Test
    @DisplayName("同じ session は WORKING-STORAGE を保ち、CANCEL で初期化する")
    void aSessionKeepsStateUntilCancel() {
        CobolRuntime runtime = CobolRuntime.builder(programs().build()).build();
        Storage answer = Storage.allocate(1);

        try (CobolSession session = runtime.openSession()) {
            session.call("counter", answer.whole());
            assertEquals(1, answer.array()[0]);
            session.call("COUNTER", answer.whole());
            assertEquals(2, answer.array()[0]);

            session.cancel("counter");
            session.call("COUNTER", answer.whole());
            assertEquals(1, answer.array()[0]);
            assertEquals(new CatalogRevision("test-r1"), session.catalogRevision());
        }
    }

    @Test
    @DisplayName("異なる session の WORKING-STORAGE は混ざらない")
    void sessionsDoNotShareState() {
        CobolRuntime runtime = CobolRuntime.builder(programs().build()).build();
        Storage first = Storage.allocate(1);
        Storage second = Storage.allocate(1);

        try (CobolSession one = runtime.openSession(); CobolSession two = runtime.openSession()) {
            one.call("COUNTER", first.whole());
            one.call("COUNTER", first.whole());
            two.call("COUNTER", second.whole());
        }

        assertEquals(2, first.array()[0]);
        assertEquals(1, second.array()[0]);
    }

    @Test
    @DisplayName("Java の call と main では EXIT PROGRAM の意味が異なる")
    void exitProgramUsesTheExplicitInvocationKind() {
        CobolRuntime runtime = CobolRuntime.builder(programs().build()).build();

        try (CobolSession called = runtime.openSession()) {
            CobolCallResult result = called.call("EXITPGM");
            assertEquals(0, result.workingStorage().array()[0]);
        }
        try (CobolSession main = runtime.openSession()) {
            CobolCallResult result = main.runMain("EXITPGM");
            assertEquals(1, result.workingStorage().array()[0]);
        }
    }

    @Test
    @DisplayName("COBOL の CALL は明示登録した Java を同じ DataView で呼ぶ")
    void cobolCallsARegisteredJavaProgramByReference() {
        ProgramCatalog catalog = programs()
                .cobolProgram("CALLJAVA", CallsJavaProgram::new)
                .javaProgram("JAVASUB", () -> (context, arguments) -> {
                    arguments.get(0).setBytes(new byte[] {7, 8, 9});
                    context.setReturnCode(4);
                })
                .build();
        Storage argument = Storage.copyOf(new byte[] {1, 2, 3});

        try (CobolSession session = CobolRuntime.builder(catalog).build().openSession()) {
            CobolCallResult result = session.call("CALLJAVA", argument.whole());
            assertArrayEquals(new byte[] {7, 8, 9}, argument.array());
            assertEquals(4, result.returnCode());
        }
    }

    @Test
    @DisplayName("登録 Java から同じ catalog の COBOL へ再入できる")
    void javaCanReenterCobolOnTheSameContext() {
        ProgramCatalog catalog = programs()
                .javaProgram("REENTER", () -> (context, arguments) ->
                        context.call("COUNTER", arguments.get(0)))
                .build();
        Storage argument = Storage.allocate(1);

        try (CobolSession session = CobolRuntime.builder(catalog).build().openSession()) {
            session.call("REENTER", argument.whole());
        }

        assertEquals(1, argument.array()[0]);
    }

    @Test
    @DisplayName("checked exception は登録 Java の失敗として cause を保つ")
    void checkedJavaFailuresKeepTheirCause() {
        ProgramCatalog catalog = ProgramCatalog.builder()
                .javaProgram("FAILJAVA", () -> (context, arguments) -> {
                    throw new IOException("downstream failed");
                })
                .build();

        try (CobolSession session = CobolRuntime.builder(catalog).build().openSession()) {
            JavaProgramException failure = assertThrows(JavaProgramException.class,
                    () -> session.call("FAILJAVA"));
            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(session.failed());
            assertThrows(CobolSessionStateException.class,
                    () -> session.call("FAILJAVA"));
        }
    }

    @Test
    @DisplayName("STOP RUN は結果に残り、その session を終了する")
    void stopRunTerminatesTheSession() {
        CobolSession session = CobolRuntime.builder(programs().build()).build().openSession();
        try {
            CobolCallResult result = session.call("STOPPGM");
            assertEquals(Termination.STOP_RUN, result.termination());
            assertEquals(12, result.returnCode());
            assertTrue(session.terminated());
            assertThrows(CobolSessionStateException.class,
                    () -> session.call("COUNTER"));
        } finally {
            session.close();
        }
    }

    @Test
    @DisplayName("session は最初に利用した thread へ固定する")
    void aSessionRejectsAnotherThread() throws InterruptedException {
        CobolSession session = CobolRuntime.builder(programs().build()).build().openSession();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        try {
            assertEquals(0, session.returnCode());
            Thread other = new Thread(() -> {
                try {
                    session.call("COUNTER");
                } catch (Throwable e) {
                    thrown.set(e);
                }
            }, "other-cobol-thread");
            other.start();
            other.join();

            CobolSessionStateException failure = assertInstanceOf(
                    CobolSessionStateException.class, thrown.get());
            assertTrue(failure.getMessage().contains(Thread.currentThread().getName()));
            assertFalse(session.failed());
        } finally {
            session.close();
        }
    }

    @Test
    @DisplayName("revision 未指定の別 catalog は異なる revision を持つ")
    void catalogRevisionsArePinnedAndDistinct() {
        assertNotEquals(ProgramCatalog.builder().build().revision(),
                ProgramCatalog.builder().build().revision());
    }

    @Test
    @DisplayName("Java入口は署名不一致をプログラム実行前に拒否する")
    void javaEntryRejectsSignatureMismatchBeforeExecution() {
        ProgramSignature signature = ProgramSignature.of("COUNTER", List.of(
                ProgramParameter.fixedReference("VALUE", 1, "one-byte-v1")));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("COUNTER", signature, CounterProgram::new)
                .build();

        try (CobolSession session = CobolRuntime.builder(catalog).build().openSession()) {
            assertThrows(ProgramSignatureMismatchException.class,
                    () -> session.call("COUNTER", Storage.allocate(2).whole()));
            assertFalse(session.failed());
            assertEquals(0, session.workingStorage("COUNTER").array()[0]);
            session.call("COUNTER", Storage.allocate(1).whole());
        }
    }

    @Test
    @DisplayName("COBOLからJavaへのCALLも署名不一致ならJavaを呼ばない")
    void cobolCallRejectsJavaSignatureMismatchBeforeInvocation() {
        AtomicBoolean invoked = new AtomicBoolean();
        ProgramSignature signature = ProgramSignature.of("JAVASUB", List.of(
                ProgramParameter.fixedReference("FIRST", 1, "first-v1"),
                ProgramParameter.fixedReference("SECOND", 1, "second-v1")));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("CALLJAVA", CallsJavaProgram::new)
                .javaProgram("JAVASUB", signature, () -> (context, arguments) ->
                        invoked.set(true))
                .build();

        try (CobolSession session = CobolRuntime.builder(catalog).build().openSession()) {
            assertThrows(ProgramSignatureMismatchException.class,
                    () -> session.call("CALLJAVA", Storage.allocate(1).whole()));
            assertFalse(invoked.get());
            assertTrue(session.failed());
        }
    }

    @Test
    @DisplayName("埋込みmanifestのないプログラムへ偽のSECTION範囲を渡せない")
    void directProcedureInvocationRequiresEmbeddedMetadata() {
        ProcedureDescriptor forged = new ProcedureDescriptor(
                ProcedureId.section("COUNTER", "FORGED"), 0, 0, false,
                "forged.cbl", 1, true, null);

        try (CobolSession session = CobolRuntime.builder(programs().build()).build()
                .openSession()) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> session.invokeProcedure(forged, Storage.allocate(1).whole()));
            assertTrue(failure.getMessage().contains("metadata embedded"));
            assertFalse(session.failed());
        }
    }
}
