package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.Storage;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** BIF DEEDIT の公開仕様の振る舞い。 */
@Tag("V1")
class CicsBifDeeditTest {

    private static final CodePage CP = CodePages.IBM_1047;

    private static byte[] deedit(String value) {
        CicsExecution execution = new CicsExecution(new CicsTaskContext(new CicsTaskId("task_deedit"),
                TransId.of("TX01"), "deedit-test", Instant.EPOCH), 0);
        ProgramContext context = ProgramContext.standard().withCodePage(CP).withServices(
                RuntimeServices.builder().service(CicsExecution.class, execution).build());
        Storage field = Storage.copyOf(CP.encode(value));
        CicsRuntimeOps.deedit(context, field.whole());
        return field.array();
    }

    @Test
    @DisplayName("数字以外を除いて右へ詰め、左を 0 で埋める")
    void keepsDigitsRightJustified() {
        assertEquals("0000123450", CP.decode(deedit("1,234.50  ")));
        assertEquals("0000000042", CP.decode(deedit("  42      ")));
        assertEquals("0000000000", CP.decode(deedit("ABC       ")));
    }

    @Test
    @DisplayName("負号か CR で終われば右端の byte に負のゾーンを置く")
    void marksNegativeZone() {
        byte[] expected = CP.encode("0000012345");
        expected[9] = (byte) 0xD5;
        assertArrayEquals(expected, deedit("1,234.5-  "));
        expected = CP.encode("0000000789");
        expected[9] = (byte) 0xD9;
        assertArrayEquals(expected, deedit("7.89CR    "));
    }
}
