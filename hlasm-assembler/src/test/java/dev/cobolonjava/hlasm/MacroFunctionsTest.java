package dev.cobolonjava.hlasm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.cobolonjava.runtime.codepage.CodePages;
import org.junit.jupiter.api.Test;

class MacroFunctionsTest {

    private static String function(String name, String value) {
        return MacroFunctions.character(name, value, CodePages.DEFAULT, 1,
                operand -> operand.substring(1, operand.length() - 1).replace("''", "'"),
                Integer::parseInt);
    }

    @Test
    void conversionExamplesFromIbmLanguageReference() {
        assertEquals("000000F1", function("A2X", "241"));
        assertEquals("F1", function("B2X", "'11110001'"));
        assertEquals("3", function("B2C", "'11110011'"));
        assertEquals("11110001", function("C2B", "'1'"));
        assertEquals("F1F2", function("C2X", "'12'"));
        assertEquals("FFFFFFF9", function("D2X", "'-7'"));
        assertEquals("+241", function("X2D", "'000F1'"));
        assertEquals("-15", function("X2D", "'FFFFFFF1'"));
        assertEquals("10", function("SIGNED", "10"));
        assertEquals("+10", function("A2D", "10"));
    }

    @Test
    void stringFunctionsHandlePairingOnce() {
        assertEquals("a&b", function("DCVAL", "'a&&b'"));
        assertEquals("a&&b", function("DOUBLE", "'a&b'"));
        assertEquals("Ab", function("DEQUOTE", "'Ab'"));
    }

    @Test
    void dequoteRemovesLeadingAndTrailingQuotesIndependently() {
        for (String[] example : new String[][] {
                {"'1F", "1F"}, {"1B'", "1B"}, {"'1F'", "1F"},
                {"''", ""}, {"'", "'"}
        }) {
            String value = MacroFunctions.character("DEQUOTE", "'ignored'",
                    CodePages.DEFAULT, 1, argument -> example[0], Integer::parseInt);
            assertEquals(example[1], value);
        }
    }
}
