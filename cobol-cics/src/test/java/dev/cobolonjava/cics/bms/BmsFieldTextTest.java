package dev.cobolonjava.cics.bms;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.codepage.UnrepresentableCharacterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** field の長さを画面位置 (cell) で数える規則。 */
@Tag("V1")
class BmsFieldTextTest {

    private static final CodePage MIXED = CodePages.IBM_930;
    private static final String YAMADA = "山田";
    private static final String KATAKANA = "ｱｲ";

    @Test
    @DisplayName("DBCSは1文字2桁、シフト符号も1桁ずつ占める。半角カタカナは1桁")
    void countsScreenPositionsNotCharacters() {
        assertEquals(2, BmsFieldText.cells("AB", MIXED));
        // X'0E 4565 4563 0F'
        assertEquals(6, BmsFieldText.cells(YAMADA, MIXED));
        // X'C1 0E 4565 0F C2'
        assertEquals(6, BmsFieldText.cells("A山B", MIXED));
        // 半角カタカナはSBCSなので、文字数と桁数が一致する
        assertEquals(2, BmsFieldText.cells(KATAKANA, MIXED));
        assertEquals(2, KATAKANA.length());
    }

    @Test
    @DisplayName("桁数は連結について加法的でない。詰め物は必ず全体から数える")
    void doesNotAddUpAcrossConcatenation() {
        // シフト符号が1組で済むので、1文字ずつ数えて足すと2桁多く見える
        assertEquals(4, BmsFieldText.cells("山", MIXED));
        assertEquals(4, BmsFieldText.cells("田", MIXED));
        assertEquals(6, BmsFieldText.cells(YAMADA, MIXED));

        String filled = BmsFieldText.fill(YAMADA, 8, ' ', MIXED);
        assertEquals(YAMADA + "  ", filled);
        assertEquals(8, BmsFieldText.cells(filled, MIXED));
        assertArrayEquals(new byte[] {0x0E, 0x45, 0x65, 0x45, 0x63, 0x0F, 0x40, 0x40},
                MIXED.encode(filled));
    }

    @Test
    @DisplayName("右寄せも桁数で詰める")
    void fillsFromTheLeftForRightJustify() {
        String filled = BmsFieldText.fillLeft("山", 6, ' ', MIXED);
        assertEquals("  山", filled);
        assertEquals(6, BmsFieldText.cells(filled, MIXED));
    }

    @Test
    @DisplayName("桁に収まらない値は断る")
    void refusesValuesThatDoNotFit() {
        assertThrows(IllegalArgumentException.class, () -> BmsFieldText.fill(YAMADA, 5, ' ', MIXED));
        assertThrows(UnrepresentableCharacterException.class,
                () -> BmsFieldText.cells(YAMADA, CodePages.IBM_1047));
    }

    @Test
    @DisplayName("切るのは符号位置の単位である。byteの途中で切らない")
    void truncatesOnCharacterBoundaries() {
        // 5桁にはDBCSが1文字 (シフト符号を含めて4桁) しか入らない
        assertEquals("山", BmsFieldText.truncate(YAMADA, 5, MIXED));
        assertEquals(YAMADA, BmsFieldText.truncate(YAMADA, 6, MIXED));
        assertEquals("", BmsFieldText.truncate(YAMADA, 3, MIXED));
        assertEquals("AB", BmsFieldText.truncate("ABC", 2, MIXED));
    }
}
