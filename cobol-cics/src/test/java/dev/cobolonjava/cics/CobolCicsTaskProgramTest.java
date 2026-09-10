package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class CobolCicsTaskProgramTest {

    @Test
    @DisplayName("LINKは同じsessionで子programのCOMMAREA変更を呼出元へ戻す")
    void executesLinkInSameCobolSession() {
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("MAIN", () -> program((context, arguments) ->
                        CicsRuntimeOps.link(context, "CHILD", arguments[0])))
                .cobolProgram("CHILD", () -> program((context, arguments) ->
                        arguments[0].set(0, (byte) 7)))
                .build();

        TaskCompletion result = executor(catalog, 4).execute(
                definition("MAIN"), payload(1, 2), task(), noSyncpoints());

        assertArrayEquals(new byte[] {7, 2}, result.payload().commarea());
        assertEquals(Optional.empty(), result.nextTransaction());
    }

    @Test
    @DisplayName("XCTLはJava stackを増やさず同じsessionで次programへ移る")
    void executesXctlLoopInSameSession() {
        List<String> trace = new ArrayList<>();
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("FIRST", () -> program((context, arguments) -> {
                    trace.add("FIRST");
                    CicsRuntimeOps.xctl(context, "SECOND", arguments[0]);
                }))
                .cobolProgram("SECOND", () -> program((context, arguments) -> {
                    trace.add("SECOND");
                    arguments[0].set(1, (byte) 8);
                })).build();

        TaskCompletion result = executor(catalog, 4).execute(
                definition("FIRST"), payload(1, 2), task(), noSyncpoints());

        assertEquals(List.of("FIRST", "SECOND"), trace);
        assertArrayEquals(new byte[] {1, 8}, result.payload().commarea());
    }

    @Test
    @DisplayName("RETURN TRANSIDを正常制御としてcoordinatorへ返す")
    void returnsPseudoConversationControl() {
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("MAIN", () -> program((context, arguments) -> {
                    arguments[0].set(0, (byte) 9);
                    CicsRuntimeOps.returnTask(context, "NXT1", arguments[0]);
                })).build();

        TaskCompletion result = executor(catalog, 4).execute(
                definition("MAIN"), payload(1, 2), task(), noSyncpoints());

        assertEquals(Optional.of(TransId.of("NXT1")), result.nextTransaction());
        assertArrayEquals(new byte[] {9, 2}, result.payload().commarea());
    }

    @Test
    @DisplayName("SYNCPOINTはtask boundaryへ委譲してprogramを継続する")
    void delegatesSyncpointAndContinues() {
        List<SyncpointAction> actions = new ArrayList<>();
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("MAIN", () -> program((context, arguments) -> {
                    CicsRuntimeOps.syncpoint(context, false);
                    CicsRuntimeOps.syncpoint(context, true);
                    arguments[0].set(0, (byte) 6);
                })).build();

        TaskCompletion result = executor(catalog, 4).execute(
                definition("MAIN"), payload(1), task(),
                (action, ignored) -> actions.add(action));

        assertEquals(List.of(SyncpointAction.COMMIT, SyncpointAction.ROLLBACK), actions);
        assertArrayEquals(new byte[] {6}, result.payload().commarea());
    }

    @Test
    @DisplayName("無限XCTLとSTOP RUNをtask異常として拒否する")
    void rejectsTransferLoopAndStopRun() {
        ProgramCatalog looping = ProgramCatalog.builder()
                .cobolProgram("LOOP", () -> program((context, arguments) ->
                        CicsRuntimeOps.xctl(context, "LOOP", arguments[0])))
                .build();
        assertThrows(CicsTaskStateException.class, () -> executor(looping, 2).execute(
                definition("LOOP"), payload(1), task(), noSyncpoints()));

        ProgramCatalog stopping = ProgramCatalog.builder()
                .cobolProgram("STOPPER", () -> program((context, arguments) -> Ops.stopRun()))
                .build();
        assertThrows(CicsTaskStateException.class, () -> executor(stopping, 2).execute(
                definition("STOPPER"), payload(1), task(), noSyncpoints()));
    }

    @Test
    @DisplayName("program起動前にtask TRANSIDと入力上限を再検査する")
    void validatesPublicExecutionBoundaryBeforeRunningProgram() {
        List<String> trace = new ArrayList<>();
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("MAIN", () -> program((context, arguments) -> trace.add("ran")))
                .build();
        CicsTransactionDefinition limited = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("MAIN"), Duration.ofSeconds(5),
                1, 0, 0, 0, true);

        assertThrows(CicsInputLimitException.class, () -> executor(catalog, 2).execute(
                limited, payload(1, 2), task(), noSyncpoints()));
        CicsTaskContext wrongTask = new CicsTaskContext(
                new CicsTaskId("task_000000000005"), TransId.of("BAD1"),
                "owner", Instant.parse("2026-09-10T02:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> executor(catalog, 2).execute(
                limited, payload(1), wrongTask, noSyncpoints()));
        assertEquals(List.of(), trace);
    }

    private static CobolCicsTaskProgram executor(ProgramCatalog catalog, int maxTransfers) {
        return new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).build(), maxTransfers);
    }

    private static CicsTransactionDefinition definition(String program) {
        return new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of(program), Duration.ofSeconds(5),
                16, 4, 16, 32, true);
    }

    private static CicsPayload payload(int... bytes) {
        byte[] commarea = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            commarea[i] = (byte) bytes[i];
        }
        return new CicsPayload(commarea, Map.of("DATA", new byte[] {3}));
    }

    private static CicsTaskContext task() {
        return new CicsTaskContext(
                new CicsTaskId("task_000000000003"), TransId.of("TX01"),
                "owner", Instant.parse("2026-09-10T02:00:00Z"));
    }

    private static SyncpointPort noSyncpoints() {
        return (action, task) -> { };
    }

    private static CobolProgram program(ProgramBody body) {
        return new CobolProgram() {
            @Override
            public byte[] initialStorage() {
                return new byte[0];
            }

            @Override
            public void run(Storage storage, ProgramContext context, DataView[] arguments) {
                body.run(context, arguments);
            }
        };
    }

    @FunctionalInterface
    private interface ProgramBody {
        void run(ProgramContext context, DataView[] arguments);
    }
}
