package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.cics.bms.BmsAid;
import dev.cobolonjava.cics.bms.BmsTerminalInput;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** INQUIRE / SET TERMINAL UCTRANST と EIBTRMID (暫定判断 P-130)。 */
@Tag("V1")
class CicsTerminalSettingsTest {

    private static final CodePage CP = CodePages.DEFAULT;

    private static ProgramContext context(CicsEnvironment environment, Optional<String> terminal) {
        CicsExecution execution = new CicsExecution(new CicsTaskContext(new CicsTaskId("task_uctran"),
                TransId.of("TX01"), "terminal-settings", Instant.EPOCH).withTerminalId(terminal), 0, environment);
        return ProgramContext.standard().withCodePage(CP).withServices(
                RuntimeServices.builder().service(CicsExecution.class, execution).build());
    }

    private static Storage fullword(int value) {
        return Storage.copyOf(ByteBuffer.allocate(4).putInt(value).array());
    }

    @Test
    @DisplayName("自分の端末の大文字変換を読み書きし、設定はregionに残る")
    void inquiresAndSetsOwnTerminal() {
        CicsEnvironment environment = CicsEnvironment.unconfigured();
        ProgramContext context = context(environment, Optional.of("T001"));
        Storage area = fullword(0);

        CicsRuntimeOps.inquireTerminalCondition(context, null, CP.encode("T001"), area.whole(), false);
        assertArrayEquals(fullword(CicsCvda.NOUCTRAN).array(), area.array());

        CicsRuntimeOps.setTerminalCondition(context, "T001", null, fullword(CicsCvda.UCTRAN).whole(), false);
        ProgramContext nextTask = context(environment, Optional.of("T001"));
        CicsRuntimeOps.inquireTerminalCondition(nextTask, "T001", null, area.whole(), false);
        assertArrayEquals(fullword(451).array(), area.array());
        assertEquals(CicsCvda.NOUCTRAN, CicsCvda.forName("nouctran"));

        byte[] eib = new CicsEib(new CicsTaskContext(new CicsTaskId("task_eib"), TransId.of("TX01"), "eib",
                Instant.EPOCH).withTerminalId(Optional.of("T1")), 0, CP).storage().array();
        assertEquals("T1  ", CP.decode(java.util.Arrays.copyOfRange(eib, CicsEib.EIBTRMID_OFFSET,
                CicsEib.EIBTRMID_OFFSET + CicsEib.EIBTRMID_LENGTH)));
    }

    @Test
    @DisplayName("ほかの端末、端末の無いtask、UCTRANSTに無い値は推測せず失敗させる")
    void rejectsWhatIsNotModelled() {
        CicsEnvironment environment = CicsEnvironment.unconfigured();
        ProgramContext context = context(environment, Optional.of("T001"));
        assertThrows(CicsTaskStateException.class, () -> CicsRuntimeOps.inquireTerminalCondition(
                context, "T002", null, fullword(0).whole(), false));
        assertThrows(CicsTaskStateException.class, () -> CicsRuntimeOps.inquireTerminalCondition(
                context(environment, Optional.empty()), "T001", null, fullword(0).whole(), false));
        assertThrows(CicsTaskStateException.class, () -> CicsRuntimeOps.setTerminalCondition(
                context, "T001", null, fullword(450).whole(), false));
        assertThrows(IllegalArgumentException.class, () -> CicsCvda.forName("ACQUIRED"));
    }

    @Test
    @DisplayName("UCTRANの端末入力は英小文字だけを大文字にする")
    void uppercasesOnlyLatinLetters() {
        BmsTerminalInput input = new BmsTerminalInput(BmsAid.ENTER, 0,
                List.of(new BmsTerminalInput.FieldInput("NAME", 1, "Smith-ö 12")));
        assertEquals("SMITH-ö 12", input.uppercased().fields().get(0).value());
    }
}
