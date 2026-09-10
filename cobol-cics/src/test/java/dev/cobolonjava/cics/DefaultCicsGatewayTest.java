package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.program.ProgramNotFoundException;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class DefaultCicsGatewayTest {

    @Test
    @DisplayName("LINKは同じCobolSessionでCOMMAREAを参照渡ししcopy済み結果を返す")
    void linksProgramInSameSession() {
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("CHILD", MutatingProgram::new)
                .build();
        try (CobolSession session = CobolRuntime.builder(catalog).build().openSession()) {
            DefaultCicsGateway gateway = gateway(session, (action, task) -> { });
            CicsCommandOutcome outcome = gateway.execute(
                    new LinkCommand(ProgramId.of("CHILD"),
                            new CicsPayload(new byte[] {1, 2}, Map.of("DATA", new byte[] {9}))),
                    task());

            ContinueControl control = assertInstanceOf(ContinueControl.class, outcome.control());
            assertArrayEquals(new byte[] {7, 2}, control.payload().commarea());
            assertArrayEquals(new byte[] {9}, control.payload().containers().get("DATA"));
            assertEquals(0, outcome.responseCode());
        }
    }

    @Test
    @DisplayName("XCTLとRETURNはprogramを直接起動せずtask coordinator向け制御結果へ変換する")
    void mapsNonReturningControlCommands() {
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("NEXT", MutatingProgram::new)
                .build();
        try (CobolSession session = CobolRuntime.builder(catalog).build().openSession()) {
            DefaultCicsGateway gateway = gateway(session, (action, task) -> { });
            CicsPayload payload = CicsPayload.ofCommarea(new byte[] {1});

            TransferControl transfer = assertInstanceOf(TransferControl.class,
                    gateway.execute(new XctlCommand(ProgramId.of("NEXT"), payload), task()).control());
            TaskCompletion completion = assertInstanceOf(TaskCompletion.class,
                    gateway.execute(ReturnCommand.next(TransId.of("NXT1"), payload), task()).control());

            assertEquals(ProgramId.of("NEXT"), transfer.target());
            assertEquals(Optional.of(TransId.of("NXT1")), completion.nextTransaction());
        }
    }

    @Test
    @DisplayName("SYNCPOINTをDb2型に依存しないportへ委譲する")
    void delegatesSyncpointToNeutralPort() {
        List<SyncpointAction> actions = new ArrayList<>();
        try (CobolSession session = emptySession()) {
            DefaultCicsGateway gateway = gateway(session,
                    (action, context) -> actions.add(action));

            SyncpointCompletion completion = assertInstanceOf(SyncpointCompletion.class,
                    gateway.execute(new SyncpointCommand(SyncpointAction.ROLLBACK), task()).control());

            assertEquals(List.of(SyncpointAction.ROLLBACK), actions);
            assertEquals(SyncpointAction.ROLLBACK, completion.action());
        }
    }

    @Test
    @DisplayName("ABENDはtask ID、code、CANCEL、dump方針を持つ構造化異常になる")
    void terminatesTaskWithStructuredAbend() {
        try (CobolSession session = emptySession()) {
            DefaultCicsGateway gateway = gateway(session, (action, task) -> { });

            CicsAbend failure = assertThrows(CicsAbend.class, () -> gateway.execute(
                    AbendCommand.user(CicsAbendCode.of("B123"), true, true), task()));

            assertEquals(task().taskId(), failure.taskId());
            assertEquals(CicsAbendCode.of("B123"), failure.code());
            assertEquals(true, failure.cancelHandlers());
            assertEquals(false, failure.dumpRequested());
        }
    }

    @Test
    @DisplayName("ABCODE未指定は????かつNODUMP相当として異常終了する")
    void appliesUnspecifiedAbendDefaults() {
        try (CobolSession session = emptySession()) {
            CicsAbend failure = assertThrows(CicsAbend.class, () -> gateway(
                    session, (action, task) -> { }).execute(
                            AbendCommand.unspecified(false), task()));

            assertEquals("????", failure.code().value());
            assertEquals(false, failure.dumpRequested());
        }
    }

    @Test
    @DisplayName("application ABCODEは予約済みA始まりと安全でない文字を拒否する")
    void rejectsInvalidApplicationAbendCodes() {
        assertThrows(IllegalArgumentException.class, () -> AbendCommand.user(
                CicsAbendCode.of("A123"), false, false));
        assertThrows(IllegalArgumentException.class, () -> CicsAbendCode.of("B 12"));
    }

    @Test
    @DisplayName("別taskと別threadからのgateway利用を資源アクセス前に拒否する")
    void rejectsWrongTaskAndThread() {
        try (CobolSession session = emptySession()) {
            DefaultCicsGateway gateway = gateway(session, (action, task) -> { });
            CicsTaskContext other = new CicsTaskContext(
                    new CicsTaskId("task_000000000002"), TransId.of("TX01"),
                    "owner", Instant.parse("2026-09-10T00:00:00Z"));

            assertThrows(CicsTaskStateException.class,
                    () -> gateway.execute(ReturnCommand.complete(), other));
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> CompletableFuture.runAsync(() ->
                            gateway.execute(ReturnCommand.complete(), task())).join());
            assertInstanceOf(CicsTaskStateException.class, failure.getCause());
        }
    }

    @Test
    @DisplayName("command payload上限違反ではLINK targetを起動しない")
    void validatesBeforeLinkInvocation() {
        int[] calls = {0};
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("CHILD", () -> new CobolProgram() {
                    @Override
                    public byte[] initialStorage() {
                        return new byte[0];
                    }

                    @Override
                    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
                        calls[0]++;
                    }
                }).build();
        CicsTransactionDefinition strict = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("ENTRY"), Duration.ofSeconds(5),
                1, 0, 0, 0, true);
        try (CobolSession session = CobolRuntime.builder(catalog).build().openSession()) {
            DefaultCicsGateway gateway = new DefaultCicsGateway(
                    task(), strict, session, (action, task) -> { });

            assertThrows(CicsInputLimitException.class, () -> gateway.execute(
                    new LinkCommand(ProgramId.of("CHILD"),
                            CicsPayload.ofCommarea(new byte[] {1, 2})), task()));
            assertEquals(0, calls[0]);
        }
    }

    @Test
    @DisplayName("未登録LINK先をPGMIDERRとして返しprogram内部障害と区別する")
    void mapsMissingLinkTargetToPgmiderr() {
        try (CobolSession session = emptySession()) {
            CicsCommandOutcome outcome = gateway(session, (action, task) -> { }).execute(
                    new LinkCommand(ProgramId.of("MISSING"),
                            CicsPayload.ofCommarea(new byte[] {1, 2})), task());

            assertEquals(CicsResponseCode.PGMIDERR, outcome.responseCode());
            assertEquals(1, outcome.responseCode2());
            ContinueControl control = assertInstanceOf(ContinueControl.class, outcome.control());
            assertArrayEquals(new byte[] {1, 2}, control.payload().commarea());
        }
    }

    @Test
    @DisplayName("未登録XCTL先を移送前にPGMIDERRとして返す")
    void mapsMissingXctlTargetToPgmiderrBeforeTransfer() {
        try (CobolSession session = emptySession()) {
            CicsPayload payload = CicsPayload.ofCommarea(new byte[] {1, 2});

            CicsCommandOutcome outcome = gateway(session, (action, task) -> { }).execute(
                    new XctlCommand(ProgramId.of("MISSING"), payload), task());

            assertEquals(CicsResponseCode.PGMIDERR, outcome.responseCode());
            assertEquals(1, outcome.responseCode2());
            ContinueControl control = assertInstanceOf(
                    ContinueControl.class, outcome.control());
            assertArrayEquals(new byte[] {1, 2}, control.payload().commarea());
            assertEquals(false, session.failed());
        }
    }

    @Test
    @DisplayName("XCTLの解決probeは登録済みprogramのfactoryを先行実行しない")
    void doesNotInstantiateXctlTargetWhileProbingAvailability() {
        int[] creations = {0};
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("TARGET", () -> {
                    creations[0]++;
                    throw new ProgramNotFoundException(
                            "NESTED", new ClassNotFoundException("nested dependency"));
                })
                .build();
        try (CobolSession session = CobolRuntime.builder(catalog).build().openSession()) {
            DefaultCicsGateway gateway = gateway(session, (action, task) -> { });

            CicsCommandOutcome outcome = gateway.execute(new XctlCommand(
                    ProgramId.of("TARGET"), CicsPayload.empty()), task());

            assertInstanceOf(TransferControl.class, outcome.control());
            assertEquals(0, creations[0]);
            assertThrows(ProgramNotFoundException.class,
                    () -> session.runMain("TARGET"));
            assertEquals(1, creations[0]);
        }
    }

    @Test
    @DisplayName("LINK先program内部の未解決CALLをPGMIDERRへ誤変換しない")
    void preservesFailureRaisedInsideLinkedProgram() {
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("CHILD", () -> new CobolProgram() {
                    @Override
                    public byte[] initialStorage() {
                        return new byte[0];
                    }

                    @Override
                    public void run(
                            Storage storage, ProgramContext context, DataView[] arguments) {
                        throw new ProgramNotFoundException(
                                "NESTED", new ClassNotFoundException("nested call"));
                    }
                }).build();
        try (CobolSession session = CobolRuntime.builder(catalog).build().openSession()) {
            DefaultCicsGateway gateway = gateway(session, (action, task) -> { });

            assertThrows(ProgramNotFoundException.class, () -> gateway.execute(
                    new LinkCommand(ProgramId.of("CHILD"), CicsPayload.empty()), task()));
        }
    }

    private static DefaultCicsGateway gateway(CobolSession session, SyncpointPort syncpoints) {
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("ENTRY"), Duration.ofSeconds(5),
                32_767, 16, 1_024, 4_096, true);
        return new DefaultCicsGateway(task(), definition, session, syncpoints);
    }

    private static CicsTaskContext task() {
        return new CicsTaskContext(
                new CicsTaskId("task_000000000001"), TransId.of("TX01"),
                "owner", Instant.parse("2026-09-10T00:00:00Z"));
    }

    private static CobolSession emptySession() {
        return CobolRuntime.builder(ProgramCatalog.builder().build()).build().openSession();
    }

    private static final class MutatingProgram implements CobolProgram {

        @Override
        public byte[] initialStorage() {
            return new byte[0];
        }

        @Override
        public void run(Storage storage, ProgramContext context, DataView[] arguments) {
            arguments[0].set(0, (byte) 7);
        }
    }
}
