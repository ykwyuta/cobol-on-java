package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.compiler.source.ProcessStatement;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code ARITH(EXTEND)} (要件 FR-041)。
 *
 * <p>変わるのは 2 つである。数字項目の PICTURE の上限が 18 桁から 31 桁へ、中間結果の総桁数の
 * 上限が 30 桁から 31 桁へ上がる。2 進の項目は 18 桁のままとした (暫定判断 P-005)。
 *
 * <p>以前は {@code ARITH(EXTEND)} を断っていた (その前は黙って COMPAT で翻訳していた)。
 * COMPAT で 19 桁以上の PICTURE を書いても、上限を調べずに受け取っていた。
 */
class ArithExtendGenerationTest {

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(ArithExtendGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String sourceOf(List<String> storage, String... procedure) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. ARITHX.",
                "DATA DIVISION.", "WORKING-STORAGE SECTION.")) {
            sb.append("       ").append(line).append('\n');
        }
        for (String line : storage) {
            sb.append("       ").append(line).append('\n');
        }
        sb.append("       PROCEDURE DIVISION.\n");
        for (String line : procedure) {
            sb.append("       ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static CobolCompiler.Result compile(String options, List<String> storage,
                                                String... procedure) {
        CompilerOptions given = options == null ? CompilerOptions.NONE
                : ProcessStatement.parse(options);
        return CobolCompiler.standard().withOptions(given)
                .compile("EXTEND.cbl", sourceOf(storage, procedure));
    }

    private static String run(CobolCompiler.Result result) {
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            Storage executed = program.runFresh();
            return CodePages.DEFAULT.decode(executed.array());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
    }

    @Test
    @DisplayName("EXTEND では 31 桁の項目を持て、1 / 3 を 30 桁の小数まで出せる")
    void holdsThirtyOneDigits() {
        String stored = run(compile("ARITH(EXTEND)",
                List.of("01 A PIC 9V9(30)."),
                "COMPUTE A = 1 / 3."));
        assertEquals("0" + "3".repeat(30), stored);
    }

    @Test
    @DisplayName("COMPAT では 19 桁以上の PICTURE を断る")
    void refusesMoreThanEighteenDigitsUnderCompat() {
        CobolCompiler.Result result = compile(null, List.of("01 A PIC 9V9(18)."), "GOBACK.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("ARITH(COMPAT)"),
                () -> result.diagnostics().toString());
        assertTrue(compile(null, List.of("01 A PIC 9V9(17)."), "GOBACK.").succeeded());
    }

    @Test
    @DisplayName("EXTEND でも 32 桁は断る")
    void refusesMoreThanThirtyOneDigitsUnderExtend() {
        CobolCompiler.Result result = compile("ARITH(EXTEND)",
                List.of("01 A PIC S9(32) COMP-3."), "GOBACK.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("ARITH(EXTEND)"),
                () -> result.diagnostics().toString());
    }

    @Test
    @DisplayName("2 進の項目は EXTEND でも 18 桁まで (P-005)")
    void keepsBinaryItemsAtEighteenDigits() {
        CobolCompiler.Result result = compile("ARITH(EXTEND)",
                List.of("01 A PIC S9(19) COMP."), "GOBACK.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("binary items of 19 digits"),
                () -> result.diagnostics().toString());
    }

    /**
     * 中間結果の上限が 30 から 31 へ上がると、削られる小数部が 1 桁減る。
     *
     * <p>{@code A / B} の商は整数部 18 桁 (被除数の整数部 + 除数の小数部 0)、小数部 17 桁
     * (dmax、受取項目から) で 35 桁になる。COMPAT は 5 桁、EXTEND は 4 桁あふれ、あふれた分
     * だけ小数部を削る。
     */
    @Test
    @DisplayName("中間結果は COMPAT で 30 桁、EXTEND で 31 桁に収める")
    void capsIntermediateResultsAtThirtyOrThirtyOneDigits() {
        List<String> storage = List.of(
                "01 A PIC 9(18) VALUE 1.",
                "01 B PIC 9(18) VALUE 3.",
                "01 R PIC 9V9(17).");
        String compat = run(compile(null, storage, "COMPUTE R = A / B."));
        String extend = run(compile("ARITH(EXTEND)", storage, "COMPUTE R = A / B."));
        String prefix = "0".repeat(17) + "1" + "0".repeat(17) + "3";
        assertEquals(prefix + "0" + "3".repeat(12) + "00000", compat);
        assertEquals(prefix + "0" + "3".repeat(13) + "0000", extend);
    }

    @Test
    @DisplayName("COMPAT では 19 桁以上の数字定数を断る")
    void refusesLongLiteralsUnderCompat() {
        CobolCompiler.Result result = compile(null,
                List.of("01 Z PIC 9(18) VALUE 0."),
                "COMPUTE Z = 1234567890123456789 + 1.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("numeric literal"),
                () -> result.diagnostics().toString());
    }

    @Test
    @DisplayName("EXTEND では 31 桁の数字定数を足せる")
    void addsThirtyOneDigitLiterals() {
        String stored = run(compile("ARITH(EXTEND)",
                List.of("01 Z PIC 9(31) VALUE 0."),
                "COMPUTE Z = 1234567890123456789012345678900 + 1."));
        assertEquals("1234567890123456789012345678901", stored);
    }
}
