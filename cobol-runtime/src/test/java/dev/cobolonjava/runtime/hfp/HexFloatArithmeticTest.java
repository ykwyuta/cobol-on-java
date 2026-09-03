package dev.cobolonjava.runtime.hfp;

import static dev.cobolonjava.runtime.TestSupport.assertHex;
import static dev.cobolonjava.runtime.TestSupport.bytes;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class HexFloatArithmeticTest {

    private static byte[] hfp(String value) {
        return HexFloat.encodeLong(new BigDecimal(value));
    }

    @Test
    @DisplayName("基本的な加減乗除 (FR-032)")
    void basicArithmetic() {
        assertHex("4130000000000000", HexFloatArithmetic.addLong(hfp("1"), hfp("2")));
        assertHex("4120000000000000", HexFloatArithmetic.subtractLong(hfp("3"), hfp("1")));
        assertHex("4160000000000000", HexFloatArithmetic.multiplyLong(hfp("2"), hfp("3")));
        assertHex("4130000000000000", HexFloatArithmetic.divideLong(hfp("6"), hfp("2")));
    }

    @Test
    @DisplayName("加減算では指数を揃える際に桁が捨てられる。厳密な計算とは結果が異なる (FR-032)")
    void alignmentDiscardsDigits() {
        // 実機で確認した挙動。1 - 1e-17 はちょうど 1.0 になる。
        // 揃えた時点で減数が消えるためである。
        // 「厳密に引いてから切り捨てる」と 40FFFFFFFFFFFFFF (1.0 のひとつ下) になってしまう
        assertHex("4110000000000000", HexFloatArithmetic.subtractLong(hfp("1"), hfp("1e-17")));
        assertHex("4110000000000000", HexFloatArithmetic.addLong(hfp("1"), hfp("1e-17")));
        assertHex("4110000000000000", HexFloatArithmetic.subtractLong(hfp("1"), hfp("1e-30")));
    }

    @Test
    @DisplayName("指数差が小さければ桁は捨てられない (FR-032)")
    void smallExponentDifferenceKeepsDigits() {
        // 1 - 0.5 は正確に 0.5 になる
        assertHex("4080000000000000", HexFloatArithmetic.subtractLong(hfp("1"), hfp("0.5")));
    }

    @Test
    @DisplayName("結果がゼロなら真のゼロ (全ビット 0) になる (FR-032)")
    void zeroResultIsTrueZero() {
        assertHex("0000000000000000", HexFloatArithmetic.subtractLong(hfp("1"), hfp("1")));
        assertHex("0000000000000000", HexFloatArithmetic.addLong(hfp("1"), hfp("-1")));
        assertHex("0000000000000000", HexFloatArithmetic.multiplyLong(hfp("0"), hfp("5")));
    }

    @Test
    @DisplayName("ゼロのオペランドは相手をそのまま返す (FR-032)")
    void zeroOperandReturnsTheOther() {
        assertHex("4130000000000000", HexFloatArithmetic.addLong(hfp("0"), hfp("3")));
        assertHex("4130000000000000", HexFloatArithmetic.addLong(hfp("3"), hfp("0")));
        assertHex("C130000000000000", HexFloatArithmetic.subtractLong(hfp("0"), hfp("3")),
                "ゼロから引くと符号が反転する");
    }

    @Test
    @DisplayName("正規化により先頭の 16 進桁が 0 でなくなる (FR-032)")
    void resultIsNormalized() {
        // 1.0 (4110...) から 0.9375 (410F...) を引くと 0.0625 = 1/16 になる。
        // 差の小数部は先頭桁が 0 のままでは表せないので、左へずらして特性を 1 減らす
        byte[] a = bytes("4110000000000000");
        byte[] b = bytes("410F000000000000");
        assertHex("4010000000000000", HexFloatArithmetic.subtractLong(a, b));
    }

    @Test
    @DisplayName("ゼロ除算は誤りとして検出する")
    void divideByZero() {
        assertThrows(ArithmeticException.class,
                () -> HexFloatArithmetic.divideLong(hfp("1"), hfp("0")));
    }

    @Test
    @DisplayName("短形式でも同じ規則が適用される (FR-032)")
    void shortForm() {
        byte[] one = HexFloat.encodeShort(BigDecimal.ONE);
        byte[] two = HexFloat.encodeShort(new BigDecimal("2"));
        assertHex("41300000", HexFloatArithmetic.addShort(one, two));
        assertHex("C1100000", HexFloatArithmetic.subtractShort(one, two));
        assertHex("41200000", HexFloatArithmetic.multiplyShort(one, two));
    }
}
