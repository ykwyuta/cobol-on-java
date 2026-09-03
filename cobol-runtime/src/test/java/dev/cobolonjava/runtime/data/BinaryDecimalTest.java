package dev.cobolonjava.runtime.data;

import static dev.cobolonjava.runtime.TestSupport.assertHex;
import static dev.cobolonjava.runtime.TestSupport.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.config.UndefinedBehavior;
import dev.cobolonjava.runtime.decimal.Decimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("V1")
class BinaryDecimalTest {

    @ParameterizedTest(name = "PIC S9({0}) COMP は {1} バイト")
    @CsvSource({"1,2", "4,2", "5,4", "9,4", "10,8", "18,8"})
    @DisplayName("記憶域の幅は桁数から決まる (FR-031)")
    void byteLength(int digits, int expected) {
        assertEquals(expected, BinaryDecimal.byteLength(digits));
    }

    @Test
    @DisplayName("19 桁以上の 2 進項目は未実装であることを明示する (provisional P-005)")
    void wideBinaryIsNotImplemented() {
        assertThrows(UnsupportedOperationException.class, () -> BinaryDecimal.byteLength(19));
    }

    @Test
    @DisplayName("バイト順はビッグエンディアン、負数は 2 の補数 (FR-031)")
    void bigEndianTwosComplement() {
        assertHex("04D2", BinaryDecimal.encode(Decimal.parse("1234"), 4, 0, TruncMode.STD));
        assertHex("FB2E", BinaryDecimal.encode(Decimal.parse("-1234"), 4, 0, TruncMode.STD));
        assertHex("000004D2", BinaryDecimal.encode(Decimal.parse("1234"), 9, 0, TruncMode.STD));
    }

    @Test
    @DisplayName("TRUNC(STD) は PICTURE の桁数へ 10 進的に切り捨てる (FR-045)")
    void truncStd() {
        // PIC S9(4) COMP に 32000 を格納すると 4 桁へ切り捨てられて 2000 になる
        assertHex("07D0", BinaryDecimal.encode(Decimal.parse("32000"), 4, 0, TruncMode.STD));
    }

    @Test
    @DisplayName("TRUNC(BIN) は 10 進的な切り捨てを行わない (FR-045)")
    void truncBin() {
        // 同じ値が記憶域のフルレンジを使って 32000 のまま格納される
        assertHex("7D00", BinaryDecimal.encode(Decimal.parse("32000"), 4, 0, TruncMode.BIN));
    }

    @Test
    @DisplayName("TRUNC(OPT) は未定義領域であり、既定は安全側 = STD 相当 (FR-205)")
    void truncOptDefaultsToSafeBehaviour() {
        assertEquals(TruncMode.STD, TruncMode.OPT.resolve(UndefinedBehavior.SAFE));
        assertHex("07D0", BinaryDecimal.encode(Decimal.parse("32000"), 4, 0,
                TruncMode.OPT.resolve(UndefinedBehavior.SAFE)));
    }

    @Test
    @DisplayName("TRUNC(OPT) は互換モードでは切り捨てを省く挙動を模倣する (FR-205)")
    void truncOptMimicsHostInCompatibilityMode() {
        assertEquals(TruncMode.BIN, TruncMode.OPT.resolve(UndefinedBehavior.MIMIC));
        assertHex("7D00", BinaryDecimal.encode(Decimal.parse("32000"), 4, 0,
                TruncMode.OPT.resolve(UndefinedBehavior.MIMIC)));
    }

    @Test
    @DisplayName("未解決の TRUNC(OPT) を符号化に渡すのは誤りとして検出する")
    void unresolvedOptIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BinaryDecimal.encode(Decimal.parse("1"), 4, 0, TruncMode.OPT));
    }

    @Test
    @DisplayName("復号は 2 の補数として解釈する")
    void decode() {
        assertEquals(0, Decimal.parse("1234").compareTo(BinaryDecimal.decode(bytes("04D2"), 0)));
        assertEquals(0, Decimal.parse("-1234").compareTo(BinaryDecimal.decode(bytes("FB2E"), 0)));
        assertEquals(0, Decimal.parse("12.34").compareTo(BinaryDecimal.decode(bytes("04D2"), 2)));
    }
}
