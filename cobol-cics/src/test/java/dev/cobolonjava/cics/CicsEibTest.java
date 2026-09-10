package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.ByteBuffer;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class CicsEibTest {

    @Test
    @DisplayName("DFHEIBLK互換位置へTRANSIDとCOMMAREA長を設定する")
    void initializesFixedLayoutTaskFields() {
        CicsEib eib = new CicsEib(task("T1"), 258, CodePages.IBM_1047);

        assertEquals(CicsEib.SIZE, eib.storage().size());
        assertEquals("T1  ", CodePages.IBM_1047.decode(eib.storage()
                .view(CicsEib.EIBTRNID_OFFSET, CicsEib.EIBTRNID_LENGTH).toByteArray()));
        assertArrayEquals(new byte[] {0x01, 0x02}, eib.storage()
                .view(CicsEib.EIBCALEN_OFFSET, CicsEib.EIBCALEN_LENGTH).toByteArray());
        assertEquals(0, fullword(eib, CicsEib.EIBRESP_OFFSET));
        assertEquals(0, fullword(eib, CicsEib.EIBRESP2_OFFSET));
        assertEquals(0, eib.storage().array()[0]);
    }

    @Test
    @DisplayName("command結果のRESPとRESP2をsigned fullwordで反映する")
    void updatesCommandResponseFields() {
        CicsEib eib = new CicsEib(task("TX01"), 0, CodePages.IBM_1047);

        eib.updateResponse(27, -42);

        assertEquals(27, fullword(eib, CicsEib.EIBRESP_OFFSET));
        assertEquals(-42, fullword(eib, CicsEib.EIBRESP2_OFFSET));
    }

    @Test
    @DisplayName("CICS runtime操作は非zero command結果もEIBへ反映してから検査する")
    void runtimeOperationPublishesResponseBeforeFailure() {
        CicsExecution execution = new CicsExecution(task("TX01"), 4);
        execution.bind((command, ignored) -> new CicsCommandOutcome(
                27, 42, new ContinueControl(((LinkCommand) command).payload())));
        ProgramContext context = ProgramContext.standard().withServices(RuntimeServices.builder()
                .service(CicsExecution.class, execution)
                .build());
        Storage commarea = Storage.copyOf(CodePages.IBM_1047.encode("DATA"));

        assertThrows(CicsTaskStateException.class,
                () -> CicsRuntimeOps.link(context, "CHILD", commarea.whole()));

        CicsEib eib = execution.eib(context.codePage());
        assertEquals(27, fullword(eib, CicsEib.EIBRESP_OFFSET));
        assertEquals(42, fullword(eib, CicsEib.EIBRESP2_OFFSET));
    }

    @Test
    @DisplayName("EIBCALENのhalfword範囲を越える入力を拒否する")
    void rejectsCommareaLengthOutsideEibcalen() {
        assertThrows(IllegalArgumentException.class,
                () -> new CicsEib(task("TX01"), Short.MAX_VALUE + 1, CodePages.IBM_1047));
        assertThrows(IllegalArgumentException.class,
                () -> new CicsExecution(task("TX01"), Short.MAX_VALUE + 1));
    }

    private static int fullword(CicsEib eib, int offset) {
        return ByteBuffer.wrap(eib.storage().view(offset, Integer.BYTES).toByteArray()).getInt();
    }

    private static CicsTaskContext task(String transId) {
        return new CicsTaskContext(
                new CicsTaskId("task_000000000006"), TransId.of(transId),
                "eib-test", Instant.parse("2026-09-10T04:00:00Z"));
    }
}
