package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.interop.ProgramId;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class CicsCommandModelTest {

    @Test
    @DisplayName("LINK、XCTL、RETURN、SYNCPOINT、ABENDを閉じたcommand型として表す")
    void exposesClosedCommandAndControlModels() {
        CicsPayload payload = CicsPayload.ofCommarea(new byte[] {1});
        CicsCommand[] commands = {
            new LinkCommand(ProgramId.of("CHILD"), payload),
            new XctlCommand(ProgramId.of("NEXT"), payload),
            ReturnCommand.next(TransId.of("NXT1"), payload),
            new SyncpointCommand(SyncpointAction.COMMIT),
            AbendCommand.user(CicsAbendCode.of("B123"), true, false)
        };
        CicsControl[] controls = {
            new ContinueControl(payload),
            new TransferControl(ProgramId.of("NEXT"), payload),
            new TaskCompletion(Optional.of(TransId.of("NXT1")), payload),
            new SyncpointCompletion(SyncpointAction.COMMIT)
        };

        assertEquals(5, commands.length);
        assertEquals(4, controls.length);
        assertInstanceOf(ReturnCommand.class, commands[2]);
        assertInstanceOf(TaskCompletion.class, controls[2]);
        assertTrue(ReturnCommand.complete().nextTransaction().isEmpty());
        AbendCommand abend = assertInstanceOf(AbendCommand.class, commands[4]);
        assertEquals("B123", abend.effectiveCode().value());
        assertTrue(abend.dumpRequested());
    }

    @Test
    @DisplayName("task contextはtransport型を持たず安全な相関値だけを保持する")
    void createsFrameworkNeutralTaskContext() {
        CicsTaskContext task = new CicsTaskContext(
                new CicsTaskId("task_000000000001"), TransId.of("TX01"),
                "tenant:user", Instant.parse("2026-09-10T00:00:00Z"));

        assertEquals("TX01", task.transactionId().value());
        assertEquals("tenant:user", task.owner());
    }

    @Test
    @DisplayName("DFHRESPは保証済みconditionだけを大文字小文字によらず解決する")
    void resolvesSupportedResponseConditionNames() {
        assertEquals(CicsResponseCode.NORMAL, CicsResponseCode.forCondition("normal"));
        assertEquals(CicsResponseCode.PGMIDERR, CicsResponseCode.forCondition(" PGMIDERR "));
        assertEquals(CicsResponseCode.ERROR_HANDLER_KEY,
                CicsResponseCode.handlerKey("error"));
        assertThrows(IllegalArgumentException.class,
                () -> CicsResponseCode.forCondition("ERROR"));
        // NOTFND は公開の表で数 (13) を、NOTOPEN は WRITEQ TD / READQ TD の頁で数 (19) を確かめた。
        // 返す命令を持たない INVMPSZ は解決しない
        assertEquals(13, CicsResponseCode.forCondition("NOTFND"));
        assertEquals(19, CicsResponseCode.forCondition("NOTOPEN"));
        assertThrows(IllegalArgumentException.class,
                () -> CicsResponseCode.forCondition("INVMPSZ"));
    }
}
