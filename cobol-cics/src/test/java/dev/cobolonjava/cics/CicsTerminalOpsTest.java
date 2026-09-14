package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.bms.BmsMapsetCatalog;
import dev.cobolonjava.cics.bms.BmsModel;
import dev.cobolonjava.cics.bms.BmsParser;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot;
import dev.cobolonjava.cics.bms.BmsSymbolicLayout;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.Storage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** SEND MAP / SEND TEXT / SEND CONTROL の runtime 操作。 */
@Tag("V1")
class CicsTerminalOpsTest {

    private static String card(String body, boolean continued) {
        return continued ? String.format("%-71s*", body) : body;
    }

    private static final BmsModel.Mapset MAPSET = BmsParser.parse(String.join("\n",
            card("OPSSET   DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,TIOAPFX=YES,", true),
            card("               CTRL=FREEKB", false),
            card("OPSMP    DFHMDI SIZE=(24,80)", false),
            card("NAME     DFHMDF POS=(2,2),LENGTH=6,ATTRB=(UNPROT,IC,FSET)", false),
            card("         DFHMSD TYPE=FINAL", false)) + "\n");

    private static ProgramContext context(CicsExecution execution) {
        return ProgramContext.standard().withServices(RuntimeServices.builder()
                .service(CicsExecution.class, execution)
                .build());
    }

    private static CicsExecution execution(CicsEnvironment environment) {
        return new CicsExecution(new CicsTaskContext(new CicsTaskId("task_terminal"),
                TransId.of("TX01"), "terminal-test", Instant.EPOCH), 0, environment);
    }

    private static CicsEnvironment withCatalog() {
        return CicsEnvironment.unconfigured().withMapsets(BmsMapsetCatalog.of(List.of(MAPSET)));
    }

    @Test
    @DisplayName("SEND MAPは記号マップから画面を作り、SEND CONTROLはMDTを落としkeyboardを残す")
    void sendsMapThenControl() {
        CicsExecution execution = execution(withCatalog());
        ProgramContext context = context(execution);
        int length = BmsSymbolicLayout.of(MAPSET, MAPSET.maps().get(0)).length();
        Storage symbolic = Storage.allocate(length);
        byte[] name = context.codePage().encode("ALICE ");
        System.arraycopy(name, 0, symbolic.array(), 15, name.length);

        CicsRuntimeOps.sendMapCondition(context, "OPSSET", "OPSMP", symbolic.whole(),
                CicsRuntimeOps.SEND_ERASE, CicsRuntimeOps.CURSOR_NONE, false);
        BmsScreenSnapshot sent = ((CicsTerminalScreen.MapScreen)
                execution.terminalScreen().orElseThrow()).snapshot();
        assertEquals("ALICE ", sent.field("NAME", 1).orElseThrow().data());
        assertTrue(sent.field("NAME", 1).orElseThrow().modified());
        assertTrue(sent.keyboardRestored(), "CTRL=FREEKB in the mapset");
        assertArrayEquals(new byte[] {0x18, 0x04}, execution.eib(context.codePage()).storage()
                .view(CicsEib.EIBFN_OFFSET, CicsEib.EIBFN_LENGTH).toByteArray());

        CicsRuntimeOps.sendControlCondition(context,
                CicsRuntimeOps.SEND_FRSET | CicsRuntimeOps.SEND_ALARM, 5, false);
        BmsScreenSnapshot controlled = ((CicsTerminalScreen.MapScreen)
                execution.terminalScreen().orElseThrow()).snapshot();
        assertFalse(controlled.field("NAME", 1).orElseThrow().modified());
        assertEquals("ALICE ", controlled.field("NAME", 1).orElseThrow().data());
        assertEquals(5, controlled.cursorOffset());
        assertTrue(controlled.alarm());

        CicsRuntimeOps.sendControlCondition(context,
                CicsRuntimeOps.SEND_ERASE | CicsRuntimeOps.SEND_FREEKB, CicsRuntimeOps.CURSOR_NONE, false);
        assertEquals(new CicsTerminalScreen.TextScreen("", true, false),
                execution.terminalScreen().orElseThrow());
    }

    @Test
    @DisplayName("SEND TEXTは既存画面の上ではERASEを要し、mapset定義の無いSEND MAPは失敗する")
    void sendsTextAndRejectsMissingDefinitions() {
        CicsExecution execution = execution(withCatalog());
        ProgramContext context = context(execution);
        Storage text = Storage.copyOf(context.codePage().encode("Session ended"));

        CicsRuntimeOps.sendTextCondition(context, text.whole(),
                CicsRuntimeOps.SEND_ERASE | CicsRuntimeOps.SEND_FREEKB, false);
        assertEquals(new CicsTerminalScreen.TextScreen("Session ended", true, false),
                execution.terminalScreen().orElseThrow());
        assertThrows(CicsTaskStateException.class, () ->
                CicsRuntimeOps.sendTextCondition(context, text.whole(), 0, false));
        assertThrows(CicsTaskStateException.class, () -> CicsRuntimeOps.sendMapCondition(
                context, "OPSSET", "OPSMP", null, CicsRuntimeOps.SEND_MAPONLY,
                CicsRuntimeOps.CURSOR_NONE, false));
        assertThrows(CicsTaskStateException.class, () -> CicsRuntimeOps.sendMapCondition(
                context, "NOSET", "OPSMP", null,
                CicsRuntimeOps.SEND_ERASE | CicsRuntimeOps.SEND_MAPONLY,
                CicsRuntimeOps.CURSOR_NONE, false));

        CicsExecution unconfigured = execution(CicsEnvironment.unconfigured());
        CicsTaskStateException missing = assertThrows(CicsTaskStateException.class, () ->
                CicsRuntimeOps.sendMapCondition(context(unconfigured), "OPSSET", "OPSMP", null,
                        CicsRuntimeOps.SEND_ERASE | CicsRuntimeOps.SEND_MAPONLY,
                        CicsRuntimeOps.CURSOR_NONE, false));
        assertTrue(missing.getMessage().contains("mapset catalog"), missing.getMessage());
    }
}
