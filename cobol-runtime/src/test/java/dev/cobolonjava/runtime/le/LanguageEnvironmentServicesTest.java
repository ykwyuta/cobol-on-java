package dev.cobolonjava.runtime.le;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Language Environment の日付の callable service (暫定判断 P-139)。 */
@Tag("V1")
class LanguageEnvironmentServicesTest {

    private static final CodePage CP = CodePages.DEFAULT;

    private static DataView vstring(String text) {
        byte[] encoded = CP.encode(text);
        return Storage.copyOf(ByteBuffer.allocate(2 + encoded.length).putShort((short) encoded.length)
                .put(encoded).array()).whole();
    }

    private static DataView area(int length) {
        return Storage.allocate(length).whole();
    }

    /** 戻り値は {Lilian, severity, message 番号}。 */
    private static int[] days(String date, String picture) {
        DataView output = area(4);
        DataView fc = area(12);
        LanguageEnvironmentServices.ceedays(CP, List.of(vstring(date), vstring(picture), output, fc));
        ByteBuffer token = ByteBuffer.wrap(fc.toByteArray());
        return new int[] {ByteBuffer.wrap(output.toByteArray()).getInt(), token.getShort(0), token.getShort(2)};
    }

    @Test
    @DisplayName("CEEDAYSは1582年10月15日を1とするLilianの日を返し、誤りはfeedback codeとLilian 0で返す")
    void convertsDatesToLilian() {
        assertEquals(1, days("15821015", "YYYYMMDD")[0]);
        long expected = LocalDate.of(2026, 9, 15).toEpochDay() - LocalDate.of(1582, 10, 14).toEpochDay();
        int[] ok = days("2026-09-15", "YYYY-MM-DD");
        assertEquals(expected, ok[0]);
        assertEquals(0, ok[1]);

        DataView fc = area(12);
        LanguageEnvironmentServices.ceedays(CP, List.of(vstring("20260230"), vstring("YYYYMMDD"), area(4), fc));
        byte[] token = fc.toByteArray();
        assertEquals(3, ByteBuffer.wrap(token).getShort(0));
        assertEquals(2508, ByteBuffer.wrap(token).getShort(2));
        assertEquals(1, token[4]);
        assertEquals("CEE", CP.decode(java.util.Arrays.copyOfRange(token, 5, 8)));

        assertEquals(0, days("20260230", "YYYYMMDD")[0]);
        assertEquals(2517, days("20261301", "YYYYMMDD")[2]);
        assertEquals(2520, days("2026AB01", "YYYYMMDD")[2]);
        assertEquals(2513, days("15821014", "YYYYMMDD")[2]);
        assertEquals(2507, days("2026091", "YYYYMMDD")[2]);
    }

    @Test
    @DisplayName("確かめていない絵と、VSTRINGの長さが渡した域を越える形は失敗させる")
    void rejectsUnverifiedForms() {
        assertThrows(IllegalArgumentException.class, () -> days("Sep 15 2026", "Mmm DD YYYY"));
        DataView overlong = Storage.copyOf(ByteBuffer.allocate(10).putShort((short) 10)
                .put(CP.encode("YYYYMMDD")).array()).whole();
        assertThrows(IllegalArgumentException.class, () -> LanguageEnvironmentServices.ceedays(CP,
                List.of(vstring("20260915"), overlong, area(4), area(12))));
    }

    @Test
    @DisplayName("CEELOCTは時計の地方時のLilianの日、1582年10月14日からの秒、17文字のGregorianを返す")
    void returnsLocalDateAndTime() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-15T01:02:03.456Z"), ZoneOffset.ofHours(9));
        DataView lilian = area(4);
        DataView seconds = area(8);
        DataView gregorian = area(17);
        DataView fc = area(12);
        LanguageEnvironmentServices.ceeloct(CP, clock, List.of(lilian, seconds, gregorian, fc));

        long day = LocalDate.of(2026, 9, 15).toEpochDay() - LocalDate.of(1582, 10, 14).toEpochDay();
        assertEquals(day, ByteBuffer.wrap(lilian.toByteArray()).getInt());
        assertEquals("20260915100203456", CP.decode(gregorian.toByteArray()));
        BigDecimal stored = Ops.readFloat(seconds.storage(), seconds.offset(), 8).toBigDecimal();
        BigDecimal wanted = BigDecimal.valueOf(day * 86_400L + 10 * 3600 + 2 * 60 + 3).add(new BigDecimal("0.456"));
        assertTrue(stored.subtract(wanted).abs().compareTo(new BigDecimal("0.001")) < 0, stored.toPlainString());
        assertEquals(12, fc.toByteArray().length);
        for (byte value : fc.toByteArray()) {
            assertEquals(0, value);
        }
    }
}
