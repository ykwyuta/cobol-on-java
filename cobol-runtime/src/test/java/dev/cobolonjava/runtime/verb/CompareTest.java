package dev.cobolonjava.runtime.verb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.decimal.Decimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class CompareTest {

    @Test
    @DisplayName("数値比較は内部表現や桁数に影響されない (FR-046)")
    void numericComparisonIgnoresRepresentation() {
        assertEquals(0, Compare.numeric(Decimal.parse("1.0"), Decimal.parse("1.00")));
        assertEquals(0, Compare.numeric(Decimal.parse("001"), Decimal.parse("1")));
        assertTrue(Compare.numeric(Decimal.parse("-1"), Decimal.parse("0")) < 0);
        assertTrue(Compare.numeric(Decimal.parse("2"), Decimal.parse("1.99")) > 0);
    }

    @Test
    @DisplayName("負のゼロと正のゼロは数値としては等しい (FR-046)")
    void negativeZeroEqualsPositiveZero() {
        assertEquals(0, Compare.numeric(Decimal.zero(0).withSign(-1), Decimal.zero(0)));
    }

    @Test
    @DisplayName("英数字比較は EBCDIC の照合順序による。英字が数字より小さい (FR-053)")
    void alphanumericUsesEbcdicCollating() {
        var cp = CodePages.IBM_1047;
        assertTrue(Compare.alphanumeric(cp.encode("A"), cp.encode("0"), cp) < 0);
        assertEquals(0, Compare.alphanumeric(cp.encode("AB"), cp.encode("AB  "), cp));
    }

    @Test
    @DisplayName("比較結果はホストの条件コードへ写像できる")
    void conditionCodeMapping() {
        assertEquals(0, Compare.toConditionCode(0));
        assertEquals(1, Compare.toConditionCode(-5));
        assertEquals(2, Compare.toConditionCode(7));
    }
}
