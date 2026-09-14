package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** COMP-1 / COMP-2 項目の転記・COMPUTE・比較 (暫定判断 P-127)。 */
@Tag("V1")
class FloatingPointItemTest {

    private static final class Loader extends ClassLoader {

        Loader() {
            super(FloatingPointItemTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String source(String... procedure) {
        StringBuilder out = new StringBuilder();
        for (String line : List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. FLOATS.",
                "DATA DIVISION.", "WORKING-STORAGE SECTION.",
                "01  OUT.",
                "    03  R PIC 9(4)V99.",
                "    03  S PIC S9(10)V99 SIGN LEADING SEPARATE.",
                "    03  FLAGS PIC X(3) VALUE '---'.",
                "01  F1 COMP-1.",
                "01  F2 COMP-2.",
                "PROCEDURE DIVISION.")) {
            out.append("       ").append(line).append('\n');
        }
        for (String line : procedure) {
            out.append("           ").append(line).append('\n');
        }
        return out.toString();
    }

    @Test
    @DisplayName("浮動小数点から固定小数点へは最下位の桁で丸め、比較は格納した値で行う")
    void roundsIntoFixedPointAndCompares() throws ReflectiveOperationException {
        CobolCompiler.Result result = CobolCompiler.standard().compile("FLOATS.cbl", source(
                "COMPUTE F1 = FUNCTION NUMVAL('1.23')",
                // COMP-1 の 1.23 は 1.2299995... である。切り捨てなら 1.22 になる
                "MOVE F1 TO R",
                "MOVE 0 TO F2",
                "IF F2 = ZERO MOVE 'Z' TO FLAGS(1:1) END-IF",
                "COMPUTE F2 = FUNCTION NUMVAL('12.34')",
                "COMPUTE F2 = F2 * -1",
                "MOVE F2 TO S",
                "IF F2 < 0 MOVE 'N' TO FLAGS(2:1) END-IF",
                "IF F1 > 9999.99 MOVE 'G' TO FLAGS(3:1) END-IF",
                "GOBACK."));
        assertTrue(result.succeeded(), result.diagnostics().toString());

        CobolProgram program = (CobolProgram) new Loader().define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        byte[] storage = program.runFresh().array();

        assertEquals("000123-000000001234ZN-",
                CodePages.DEFAULT.decode(Arrays.copyOfRange(storage, 0, 22)));
    }

    @Test
    @DisplayName("浮動小数点の除算・べき乗・ADD 系の文・SIZE ERROR・数字編集への転記は断る")
    void rejectsUnsupportedForms() {
        for (String[] rejected : List.of(
                new String[] {"COMPUTE F2 = F1 / 3", "supports only +, - and *"},
                new String[] {"COMPUTE R = F1 ** 2", "supports only +, - and *"},
                new String[] {"ADD F1 TO R", "use COMPUTE"},
                new String[] {"COMPUTE F1 = R ON SIZE ERROR CONTINUE END-COMPUTE", "ON SIZE ERROR"})) {
            CobolCompiler.Result result = CobolCompiler.standard().compile("FLOATS.cbl",
                    source(rejected[0], "GOBACK."));

            assertFalse(result.succeeded(), rejected[0]);
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains(rejected[1])),
                    rejected[0] + " " + result.diagnostics());
        }
    }
}
