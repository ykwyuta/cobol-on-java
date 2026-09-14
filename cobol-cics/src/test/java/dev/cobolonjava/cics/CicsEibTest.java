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
        assertArrayEquals(new byte[] {0, 0}, eib.storage()
                .view(CicsEib.EIBFN_OFFSET, CicsEib.EIBFN_LENGTH).toByteArray());
        assertArrayEquals(new byte[6], eib.storage()
                .view(CicsEib.EIBRCODE_OFFSET, CicsEib.EIBRCODE_LENGTH).toByteArray());
        assertEquals(0, fullword(eib, CicsEib.EIBRESP_OFFSET));
        assertEquals(0, fullword(eib, CicsEib.EIBRESP2_OFFSET));
        assertEquals(0, eib.storage().array()[0]);
    }

    @Test
    @DisplayName("task番号と地方時があればEIBTASKN・EIBDATE・EIBTIMEをPL4で置く")
    void writesPackedTaskNumberDateAndTime() {
        CicsTaskContext task = new CicsTaskContext(
                new CicsTaskId("task_000000000007"), TransId.of("TX01"), "eib-test",
                Instant.parse("2026-09-10T04:05:06Z"), java.util.OptionalInt.of(123),
                java.util.Optional.of(java.time.ZoneId.of("Asia/Tokyo")));

        CicsEib eib = new CicsEib(task, 0, CodePages.IBM_1047);

        assertArrayEquals(new byte[] {0x00, 0x00, 0x12, 0x3C}, packed(eib, CicsEib.EIBTASKN_OFFSET));
        // 2026年9月10日は通日253。C=1 (2000年代)
        assertArrayEquals(new byte[] {0x01, 0x26, 0x25, 0x3C}, packed(eib, CicsEib.EIBDATE_OFFSET));
        // 13:05:06 (JST)
        assertArrayEquals(new byte[] {0x01, 0x30, 0x50, 0x6C}, packed(eib, CicsEib.EIBTIME_OFFSET));
    }

    @Test
    @DisplayName("出どころの無いEIBTASKN・EIBDATE・EIBTIME・EIBAID・EIBCPOSN・EIBTRMIDは推測せずbinary zero")
    void leavesUnsourcedFieldsAsBinaryZero() {
        CicsEib eib = new CicsEib(task("TX01"), 0, CodePages.IBM_1047);

        assertArrayEquals(new byte[4], packed(eib, CicsEib.EIBTIME_OFFSET));
        assertArrayEquals(new byte[4], packed(eib, CicsEib.EIBDATE_OFFSET));
        assertArrayEquals(new byte[4], packed(eib, CicsEib.EIBTASKN_OFFSET));
        assertArrayEquals(new byte[4], eib.storage()
                .view(CicsEib.EIBTRMID_OFFSET, CicsEib.EIBTRMID_LENGTH).toByteArray());
        assertArrayEquals(new byte[2], eib.storage().view(CicsEib.EIBCPOSN_OFFSET, 2).toByteArray());
        assertEquals(0, eib.storage().array()[CicsEib.EIBAID_OFFSET]);
        assertThrows(IllegalArgumentException.class, () -> new CicsTaskContext(
                new CicsTaskId("task_1"), TransId.of("TX01"), "o", Instant.EPOCH,
                java.util.OptionalInt.of(10_000_000), java.util.Optional.empty()));
    }

    private static byte[] packed(CicsEib eib, int offset) {
        return eib.storage().view(offset, CicsEib.PACKED_LENGTH).toByteArray();
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
    @DisplayName("program controlのPGMIDERRをEIBFNと6byte EIBRCODEへ反映する")
    void completesProgramControlCommandFields() {
        CicsEib eib = new CicsEib(task("TX01"), 0, CodePages.IBM_1047);

        eib.completeCommand(0x0E02, CicsResponseCode.PGMIDERR, 1);

        assertArrayEquals(new byte[] {0x0E, 0x02}, eib.storage()
                .view(CicsEib.EIBFN_OFFSET, CicsEib.EIBFN_LENGTH).toByteArray());
        assertArrayEquals(new byte[] {0x01, 0, 0, 0, 0, 0}, eib.storage()
                .view(CicsEib.EIBRCODE_OFFSET, CicsEib.EIBRCODE_LENGTH).toByteArray());
        assertEquals(27, fullword(eib, CicsEib.EIBRESP_OFFSET));
        assertEquals(1, fullword(eib, CicsEib.EIBRESP2_OFFSET));
    }

    @Test
    @DisplayName("未分類RESPのEIBRCODEを推測せず拒否する")
    void rejectsUnknownEibrcodeMapping() {
        CicsEib eib = new CicsEib(task("TX01"), 0, CodePages.IBM_1047);

        assertThrows(IllegalArgumentException.class,
                () -> eib.completeCommand(0x0E02, 999, 0));
        assertThrows(IllegalArgumentException.class,
                () -> eib.completeCommand(0x1602, CicsResponseCode.PGMIDERR, 0));

        assertArrayEquals(new byte[] {0, 0}, eib.storage()
                .view(CicsEib.EIBFN_OFFSET, CicsEib.EIBFN_LENGTH).toByteArray());
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
        assertArrayEquals(new byte[] {0x0E, 0x02}, eib.storage()
                .view(CicsEib.EIBFN_OFFSET, CicsEib.EIBFN_LENGTH).toByteArray());
        assertArrayEquals(new byte[] {0x01, 0, 0, 0, 0, 0}, eib.storage()
                .view(CicsEib.EIBRCODE_OFFSET, CicsEib.EIBRCODE_LENGTH).toByteArray());
    }

    @Test
    @DisplayName("ASSIGN ABCODEは現在codeまたは空白を返してEIBFNを更新する")
    void assignsCurrentAbendCodeAndUpdatesFunction() {
        CicsExecution execution = new CicsExecution(task("TX01"), 0);
        ProgramContext context = ProgramContext.standard().withServices(RuntimeServices.builder()
                .service(CicsExecution.class, execution)
                .build());
        Storage receiver = Storage.allocate(4);

        CicsRuntimeOps.assignAbcode(context, receiver.whole());
        assertEquals("    ", context.codePage().decode(receiver.array()));

        execution.recordAbend(CicsAbendCode.of("B7"));
        CicsRuntimeOps.assignAbcode(context, receiver.whole());

        assertEquals("B7  ", context.codePage().decode(receiver.array()));
        CicsEib eib = execution.eib(context.codePage());
        assertArrayEquals(new byte[] {0x02, 0x08}, eib.storage()
                .view(CicsEib.EIBFN_OFFSET, CicsEib.EIBFN_LENGTH).toByteArray());
        assertEquals(0, fullword(eib, CicsEib.EIBRESP_OFFSET));
        assertThrows(IllegalArgumentException.class,
                () -> CicsRuntimeOps.assignAbcode(context, Storage.allocate(3).whole()));
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
