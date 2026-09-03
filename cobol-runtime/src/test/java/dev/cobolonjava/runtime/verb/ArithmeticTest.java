package dev.cobolonjava.runtime.verb;

import static dev.cobolonjava.runtime.TestSupport.assertHex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.decimal.CobolRounding;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.item.NumericItem;
import dev.cobolonjava.runtime.item.Usage;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class ArithmeticTest {

    private static final NumericItem ITEM = NumericItem.of("S9(3)V99", Usage.COMP_3);

    private static DataView newView() {
        return Storage.allocate(ITEM.byteLength()).whole();
    }

    @Test
    @DisplayName("ON SIZE ERROR を指定しない場合、上位桁は黙って切り捨てられる (FR-043)")
    void withoutSizeErrorHighOrderDigitsAreTruncated() {
        DataView v = newView();
        Arithmetic.store(ITEM, v, Decimal.parse("1234.56"), CobolRounding.TRUNCATION);
        // 999.99 までしか入らない。上位の 1 が落ちて 234.56 になる
        assertHex("23456C", v.toByteArray());
    }

    @Test
    @DisplayName("ON SIZE ERROR を指定した場合、収まらなければ受取項目を変更しない (FR-043)")
    void withSizeErrorTargetIsLeftUnchanged() {
        DataView v = newView();
        Arithmetic.store(ITEM, v, Decimal.parse("111.11"), CobolRounding.TRUNCATION);
        assertHex("11111C", v.toByteArray());

        boolean sizeError = Arithmetic.storeChecked(ITEM, v, Decimal.parse("1234.56"),
                CobolRounding.TRUNCATION);
        assertTrue(sizeError, "桁があふれたので SIZE ERROR 条件が立つ");
        assertHex("11111C", v.toByteArray(), "受取項目は変更されない");
    }

    @Test
    @DisplayName("小数部の切り捨ては SIZE ERROR ではない (FR-043)")
    void fractionTruncationIsNotSizeError() {
        DataView v = newView();
        boolean sizeError = Arithmetic.storeChecked(ITEM, v, Decimal.parse("123.456"),
                CobolRounding.TRUNCATION);
        assertFalse(sizeError);
        assertHex("12345C", v.toByteArray());
    }

    @Test
    @DisplayName("ROUNDED は格納時の丸めモードとして効く (FR-042)")
    void roundingAppliesOnStore() {
        DataView v = newView();
        Arithmetic.store(ITEM, v, Decimal.parse("123.456"), CobolRounding.NEAREST_AWAY_FROM_ZERO);
        assertHex("12346C", v.toByteArray());

        Arithmetic.store(ITEM, v, Decimal.parse("123.456"), CobolRounding.TRUNCATION);
        assertHex("12345C", v.toByteArray());
    }

    @Test
    @DisplayName("丸めた結果があふれる場合も SIZE ERROR になる")
    void roundingCanCauseSizeError() {
        DataView v = newView();
        boolean sizeError = Arithmetic.storeChecked(ITEM, v, Decimal.parse("999.999"),
                CobolRounding.NEAREST_AWAY_FROM_ZERO);
        assertTrue(sizeError, "丸めた結果 1000.00 は 3 桁の整数部に収まらない");
    }

    @Test
    @DisplayName("ゼロ除算は ON SIZE ERROR の対象になる (FR-043)")
    void divisionByZeroIsSizeError() {
        assertTrue(Arithmetic.divideChecked(Decimal.parse("1"), Decimal.zero(0), 2,
                CobolRounding.TRUNCATION).isEmpty());
        assertEquals("0.50", Arithmetic.divideChecked(Decimal.parse("1"), Decimal.parse("2"), 2,
                CobolRounding.TRUNCATION).orElseThrow().toString());
    }

    @Test
    @DisplayName("MOVE は常に切り捨てで格納する。ROUNDED を持たないため (FR-060)")
    void moveAlwaysTruncates() {
        DataView v = newView();
        Move.numeric(Decimal.parse("123.456"), ITEM, v);
        assertHex("12345C", v.toByteArray());
        Move.numeric(Decimal.parse("1234.56"), ITEM, v);
        assertHex("23456C", v.toByteArray());
    }
}
