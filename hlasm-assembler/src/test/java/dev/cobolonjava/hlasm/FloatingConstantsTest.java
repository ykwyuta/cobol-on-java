package dev.cobolonjava.hlasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 浮動小数点の {@code DC}。
 *
 * <p>期待値は HLASM Language Reference の既定の丸め (HFP は方式 1、BFP と DFP は最近接偶数) と、
 * IEEE 754 の符号化から手で導いた。<b>実機の HLASM とは突き合わせていない</b> (z/OS probe の
 * {@code ASMDC2})。BFP の値は JDK の {@code Float} / {@code Double} とも一致する。
 */
class FloatingConstantsTest {

    private static String hexOf(String operand) {
        Assembler.Result result = Assembler.assemble("TEST.asm", String.join("\n",
                "TEST     CSECT",
                "         DC    " + operand,
                "         END"));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        ObjectModule module = result.module();
        return module.hex(0, module.text().length);
    }

    private static Assembler.Result assemble(String operand) {
        return Assembler.assemble("TEST.asm", String.join("\n",
                "TEST     CSECT",
                "         DC    " + operand,
                "         END"));
    }

    // --- HFP ---

    @Test
    @DisplayName("E'0.1' は失われる最初のビットに 1 を足して 4019999A になる")
    void roundsShortHexadecimalByAddingOneInTheFirstLostBit() {
        assertEquals("4019999A", hexOf("E'0.1'"));
        assertEquals("C019999A", hexOf("E'-0.1'"));
        assertEquals("4019999A", hexOf("EH'0.1'"));
    }

    @Test
    @DisplayName("E'1' は特性 41、小数部 1 で正規化する")
    void normalizesHexadecimal() {
        assertEquals("41100000", hexOf("E'1'"));
        assertEquals("C1180000", hexOf("E'-1.5'"));
        assertEquals("42640000", hexOf("E'100'"));
        assertEquals("00000000", hexOf("E'0'"));
    }

    @Test
    @DisplayName("D'0.1' と L'0.1'。拡張形式の下の特性は上より 14 小さい")
    void encodesLongAndExtendedHexadecimal() {
        assertEquals("401999999999999A", hexOf("D'0.1'"));
        assertEquals("4019999999999999" + "329999999999999A", hexOf("L'0.1'"));
    }

    @Test
    @DisplayName("丸めで小数部があふれたら、指数を 1 つ上げる")
    void carriesTheRoundingIntoTheExponent() {
        // 0.99999999 は 16 進 6 桁に収まらず、丸めると 1 になる
        assertEquals("41100000", hexOf("E'0.99999999'"));
    }

    // --- BFP ---

    @Test
    @DisplayName("EB / DB / LB は IEEE 754 の 2 進で、最近接偶数に丸める")
    void encodesBinaryFloatingPoint() {
        assertEquals(String.format("%08X", Float.floatToIntBits(0.1f)), hexOf("EB'0.1'"));
        assertEquals("3DCCCCCD", hexOf("EB'0.1'"));
        assertEquals("3FB999999999999A", hexOf("DB'0.1'"));
        assertEquals(String.format("%016X", Double.doubleToLongBits(-2.5e-300)), hexOf("DB'-2.5E-300'"));
        assertEquals("3FFF" + "0".repeat(28), hexOf("LB'1'"));
    }

    @Test
    @DisplayName("2 進の非正規化数")
    void encodesSubnormalBinary() {
        assertEquals(String.format("%08X", Float.floatToIntBits(1e-40f)), hexOf("EB'1E-40'"));
    }

    // --- DFP ---

    @Test
    @DisplayName("ED / DD / LD は DPD で符号化する")
    void encodesDecimalFloatingPoint() {
        assertEquals("22500001", hexOf("ED'1'"));
        assertEquals("22400001", hexOf("ED'0.1'"));
        assertEquals("2238000000000001", hexOf("DD'1'"));
        assertEquals("2234000000000001", hexOf("DD'0.1'"));
        assertEquals("22080000000000000000000000000001", hexOf("LD'1'"));
    }

    @Test
    @DisplayName("DFP は書いた値の量子を保つ。DD'1.50' は係数 150、指数 -2")
    void keepsTheQuantum() {
        assertEquals("22300000000000D0", hexOf("DD'1.50'"));
    }

    @Test
    @DisplayName("先頭の桁が 8 以上なら結合欄の形が変わる")
    void usesTheLargeDigitCombination() {
        assertEquals("6E38000000000000", hexOf("DD'9000000000000000'"));
    }

    @Test
    @DisplayName("DPD の 3 桁の符号化")
    void encodesDeclets() {
        assertEquals(0x005, FloatingConstants.declet(0, 0, 5));
        assertEquals(0x008, FloatingConstants.declet(0, 0, 8));
        assertEquals(0x00C, FloatingConstants.declet(8, 0, 0));
        assertEquals(0x0FF, FloatingConstants.declet(9, 9, 9));
        assertEquals(0x0D0, FloatingConstants.declet(1, 5, 0));
    }

    // --- 境界と断るもの ---

    @Test
    @DisplayName("D は 8 バイト境界に合わせる")
    void alignsLongConstants() {
        Assembler.Result result = Assembler.assemble("TEST.asm", String.join("\n",
                "TEST     CSECT",
                "         DC    X'01'",
                "         DC    D'1'",
                "         END"));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        assertEquals("01000000000000004110000000000000", result.module().hex(0, 16));
    }

    @Test
    @DisplayName("丸めの指定、特別な値、長さの修飾子は断る")
    void refusesWhatItCannotAssembleFaithfully() {
        assertFalse(assemble("EB'1R4'").succeeded());
        assertFalse(assemble("D'(INF)'").succeeded());
        assertFalse(assemble("EL3'1'").succeeded());
        assertFalse(assemble("E'1E80'").succeeded(), "too large for HFP");
    }
}
