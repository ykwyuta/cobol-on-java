package dev.cobolonjava.runtime.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * バイト列をレコードへ切る (要件 FR-110, FR-141)。
 *
 * <p>切り方と<b>形が壊れているの意味</b>は同じところで決まる。編成ごとに書き分ければ、
 * 順編成のジョブだけが半端に気付いて相対編成のジョブは気付かない、ということになる
 * (暫定判断 P-053)。
 */
@Tag("V1")
class RecordFramingTest {

    private static final DataSetAttributes FIXED =
            new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT);
    private static final DataSetAttributes VARIABLE =
            new DataSetAttributes(RecordFormat.VARIABLE, 8, CodePages.DEFAULT);
    private static final DataSetAttributes LINE =
            new DataSetAttributes(RecordFormat.LINE, 8, CodePages.DEFAULT);

    private static byte[] bytes(String text) {
        return CodePages.DEFAULT.encode(text);
    }

    private static String decode(byte[] value) {
        return CodePages.DEFAULT.decode(value);
    }

    /** {@code RDW} を付けたレコードを 1 つ作る。 */
    private static byte[] framed(String text) {
        byte[] out = new byte[text.length() + 4];
        out[0] = (byte) ((text.length() + 4) >> 8);
        out[1] = (byte) (text.length() + 4);
        System.arraycopy(bytes(text), 0, out, 4, text.length());
        return out;
    }

    /** バイト列をつなぐ。 */
    private static byte[] joined(byte[] first, byte[] second) {
        byte[] out = new byte[first.length + second.length];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    @Test
    @DisplayName("固定長は長さで割り切れれば壊れていない (FR-110)")
    void fixedRecordsDivideEvenly() {
        RecordFraming.Framed framed = RecordFraming.split(bytes("aaabbb"), FIXED, false);

        assertFalse(framed.damaged());
        assertEquals(2, framed.records().size());
        assertEquals("bbb", decode(framed.records().get(1)));
    }

    @Test
    @DisplayName("固定長で半端が残れば、そこが壊れた場所になる (FR-141)")
    void aFixedRemainderIsDamage() {
        RecordFraming.Framed framed = RecordFraming.split(bytes("aaabbbcc"), FIXED, false);

        // 切れたところまでは読める。読めていないのはその先である
        assertEquals(2, framed.records().size());
        assertEquals(2, framed.damagedAt());
    }

    @Test
    @DisplayName("レコード長が決まっていなければ何も切れない (FR-141)")
    void aLengthOfZeroCannotCut() {
        RecordFraming.Framed framed = RecordFraming.fixed(bytes("aaa"), 0);

        assertTrue(framed.records().isEmpty());
        assertEquals(0, framed.damagedAt());
    }

    @Test
    @DisplayName("可変長は RDW でつながっていれば壊れていない (FR-110)")
    void variableRecordsFollowTheirPrefix() {
        RecordFraming.Framed framed =
                RecordFraming.split(joined(framed("aaa"), framed("z")), VARIABLE, false);

        assertFalse(framed.damaged());
        assertEquals(2, framed.records().size());
        assertEquals("aaa", decode(framed.records().get(0)));
    }

    @Test
    @DisplayName("可変長で RDW がつながらなければ、そこが壊れた場所になる (FR-141)")
    void aBrokenPrefixIsDamage() {
        byte[] cut = new byte[9];
        System.arraycopy(framed("aaa"), 0, cut, 0, 7);
        // 2 つめの RDW が 2 バイトしかない
        RecordFraming.Framed framed = RecordFraming.split(cut, VARIABLE, false);

        assertEquals(1, framed.records().size());
        assertEquals(1, framed.damagedAt());
    }

    @Test
    @DisplayName("可変長は RDW を含めたまま持てる (FR-137)")
    void theVariablePrefixCanBeKept() {
        RecordFraming.Framed kept = RecordFraming.split(framed("aaa"), VARIABLE, true);
        RecordFraming.Framed dropped = RecordFraming.split(framed("aaa"), VARIABLE, false);

        // 整列の道具は制御文の位置を RDW から数えるので、含めたまま渡す
        assertEquals(7, kept.records().get(0).length);
        assertEquals(3, dropped.records().get(0).length);
        assertArrayEquals(bytes("aaa"), dropped.records().get(0));
    }

    @Test
    @DisplayName("行順に壊れた形はない (FR-110)")
    void linesAreNeverDamaged() {
        RecordFraming.Framed framed = RecordFraming.split(bytes("aa\nbbb"), LINE, false);

        // どこで切れても行は行である。最後の改行がなくても 1 レコードとして扱う
        assertFalse(framed.damaged());
        assertEquals(2, framed.records().size());
        assertEquals("bbb", decode(framed.records().get(1)));
    }
}
