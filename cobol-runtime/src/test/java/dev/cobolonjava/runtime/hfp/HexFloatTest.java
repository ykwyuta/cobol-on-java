package dev.cobolonjava.runtime.hfp;

import static dev.cobolonjava.runtime.TestSupport.assertHex;
import static dev.cobolonjava.runtime.TestSupport.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("V1")
class HexFloatTest {

    @ParameterizedTest(name = "短形式: {0} -> {1}")
    @CsvSource({
            "1,      41100000",
            "-1,     C1100000",
            "0.5,    40800000",
            "16,     42100000",
            "256,    43100000",
            "0,      00000000",
            "0.0625, 40100000",
            "15,     41F00000",
            "-0.5,   C0800000",
    })
    @DisplayName("短形式 (COMP-1) のビット表現 (FR-032)")
    void shortForm(String value, String expectedHex) {
        assertHex(expectedHex, HexFloat.encodeShort(new BigDecimal(value)));
        assertEquals(0, new BigDecimal(value).compareTo(HexFloat.decodeShort(bytes(expectedHex))));
    }

    @Test
    @DisplayName("長形式 (COMP-2) のビット表現 (FR-032)")
    void longForm() {
        assertHex("4110000000000000", HexFloat.encodeLong(BigDecimal.ONE));
        assertHex("C110000000000000", HexFloat.encodeLong(BigDecimal.ONE.negate()));
        assertHex("0000000000000000", HexFloat.encodeLong(BigDecimal.ZERO));
        assertHex("4080000000000000", HexFloat.encodeLong(new BigDecimal("0.5")));
    }

    @Test
    @DisplayName("0.1 は 16 進浮動小数点で正確に表せず、切り捨てられる (FR-032)")
    void oneTenthIsTruncated() {
        // 0.1 x 16^6 = 1677721.6 -> 切り捨てて 0x199999
        assertHex("40199999", HexFloat.encodeShort(new BigDecimal("0.1")));
        // 復号すると 0.1 より小さい値になる
        BigDecimal decoded = HexFloat.decodeShort(bytes("40199999"));
        org.junit.jupiter.api.Assertions.assertTrue(decoded.compareTo(new BigDecimal("0.1")) < 0);
        assertEquals("0.099999964237213134765625", decoded.toPlainString(),
                "1677721 / 16^6 を厳密に表した値");
    }

    @Test
    @DisplayName("指数の基数が 16 であるため、有効精度が揺れる (FR-032)")
    void wobblingPrecision() {
        // 1.0 の小数部は 0x100000 で、先頭の 16 進桁に 3 ビットの先行ゼロが残る。
        // 15.0 の小数部は 0xF00000 で先行ゼロがない。同じ 24 ビットでも有効精度が異なる
        assertHex("41100000", HexFloat.encodeShort(BigDecimal.ONE));
        assertHex("41F00000", HexFloat.encodeShort(new BigDecimal("15")));
    }

    @Test
    @DisplayName("長形式は 56 ビットの小数部を持ち、double では保持できない精度がある (FR-032)")
    void longFormExceedsDoublePrecision() {
        // 56 ビットすべてが立った値。IEEE double の 53 ビット仮数では表せない
        byte[] allOnes = bytes("41FFFFFFFFFFFFFF");
        BigDecimal exact = HexFloat.decodeLong(allOnes);
        assertHex("41FFFFFFFFFFFFFF", HexFloat.encodeLong(exact),
                "厳密に往復すること。double を経由していたら往復しない");
    }

    @Test
    @DisplayName("小数部がゼロなら特性にかかわらず値はゼロ (FR-032)")
    void zeroFractionIsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(HexFloat.decodeShort(bytes("00000000"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(HexFloat.decodeShort(bytes("41000000"))));
    }

    @Test
    @DisplayName("符号化と復号は往復する")
    void roundTrip() {
        for (String v : new String[] {"1", "-1", "0.5", "1234.5", "-1234.5", "0.0625", "65536"}) {
            BigDecimal value = new BigDecimal(v);
            assertEquals(0, value.compareTo(HexFloat.decodeShort(HexFloat.encodeShort(value))), v);
            assertEquals(0, value.compareTo(HexFloat.decodeLong(HexFloat.encodeLong(value))), v);
        }
    }

    @Test
    @DisplayName("表現できない指数は誤りとして検出する")
    void exponentOutOfRange() {
        // 16^63 を超える値
        assertThrows(ArithmeticException.class,
                () -> HexFloat.encodeShort(BigDecimal.TEN.pow(80)));
    }
}
