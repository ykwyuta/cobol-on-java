package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 級条件 (要件 FR-046)。
 *
 * <p>{@code IF X IS NUMERIC} は、項目の<b>中身が何でできているか</b>を問う。比べる
 * 相手は無い。入力を検めるところで必ず使われるので、読めないと資産がまるごと通らない。
 */
@Tag("V1")
class ClassConditionTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(ClassConditionTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String run(List<String> special, List<String> storage, String... procedure) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. CLASSES.")) {
            FixedFormatSource.append(sb, line);
        }
        if (!special.isEmpty()) {
            for (String line : List.of("ENVIRONMENT DIVISION.",
                    "CONFIGURATION SECTION.", "SPECIAL-NAMES.")) {
                FixedFormatSource.append(sb, line);
            }
            for (String line : special) {
                FixedFormatSource.append(sb, line);
            }
        }
        for (String line : List.of("DATA DIVISION.", "WORKING-STORAGE SECTION.")) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : storage) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : List.of("PROCEDURE DIVISION.", "MAIN-START.")) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : procedure) {
            FixedFormatSource.append(sb, line);
        }
        FixedFormatSource.append(sb, "    STOP RUN.");

        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, sb.toString());
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            ((CobolProgram) type.getDeclaredConstructor().newInstance())
                    .runFresh(ProgramContext.capturing(sink));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot load the generated program", e);
        }
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
    }

    private static final String[] ASK_NUMERIC = {
        "    IF WS-X IS NUMERIC DISPLAY 'YES' ELSE DISPLAY 'NO' END-IF."};

    @Test
    @DisplayName("英数字項目が数字かどうかを見る (FR-046)")
    void anAlphanumericItemIsNumericWhenEveryByteIsADigit() {
        assertEquals("YES|", run(List.of(),
                List.of("01 WS-X PIC X(5) VALUE '12345'."), ASK_NUMERIC));
        assertEquals("NO|", run(List.of(),
                List.of("01 WS-X PIC X(5) VALUE '12 45'."), ASK_NUMERIC));
        assertEquals("NO|", run(List.of(),
                List.of("01 WS-X PIC X(5) VALUE 'ABCDE'."), ASK_NUMERIC));
    }

    @Test
    @DisplayName("符号を持つ数値項目では、符号の場所まで見る (FR-046)")
    void aSignedItemAlsoChecksItsSign() {
        // その項目として<b>読めるかどうか</b>で決める。ゾーンや符号ニブルの規則を
        // 書き写すと、読み出しの規則と 2 つに分かれてずれる
        assertEquals("YES|", run(List.of(),
                List.of("01 WS-X PIC S9(3) VALUE -12."), ASK_NUMERIC));
        // 中身を英数字として壊してから問う
        assertEquals("NO|", run(List.of(),
                List.of("01 WS-G.", "    05 WS-X PIC S9(3).",
                        "01 WS-R REDEFINES WS-G PIC X(3)."),
                "    MOVE 'ABC' TO WS-R",
                "    IF WS-X IS NUMERIC DISPLAY 'YES' ELSE DISPLAY 'NO' END-IF."));
    }

    @Test
    @DisplayName("NOT を書けば向きが逆になる (FR-046)")
    void notReversesTheTest() {
        assertEquals("YES|", run(List.of(),
                List.of("01 WS-X PIC X(3) VALUE 'ABC'."),
                "    IF WS-X IS NOT NUMERIC DISPLAY 'YES' ELSE DISPLAY 'NO' END-IF."));
    }

    @Test
    @DisplayName("英字かどうかを見る。空白も英字として通る (FR-046)")
    void alphabeticAllowsSpaces() {
        assertEquals("YES|", run(List.of(),
                List.of("01 WS-X PIC X(5) VALUE 'AB CD'."),
                "    IF WS-X IS ALPHABETIC DISPLAY 'YES' ELSE DISPLAY 'NO' END-IF."));
        assertEquals("NO|", run(List.of(),
                List.of("01 WS-X PIC X(5) VALUE 'AB1CD'."),
                "    IF WS-X IS ALPHABETIC DISPLAY 'YES' ELSE DISPLAY 'NO' END-IF."));
    }

    @Test
    @DisplayName("大文字と小文字を分けて見る (FR-046)")
    void theCaseCanBeAsked() {
        assertEquals("YES|", run(List.of(),
                List.of("01 WS-X PIC X(3) VALUE 'abc'."),
                "    IF WS-X IS ALPHABETIC-LOWER DISPLAY 'YES'",
                "        ELSE DISPLAY 'NO' END-IF."));
        assertEquals("NO|", run(List.of(),
                List.of("01 WS-X PIC X(3) VALUE 'abc'."),
                "    IF WS-X IS ALPHABETIC-UPPER DISPLAY 'YES'",
                "        ELSE DISPLAY 'NO' END-IF."));
    }

    @Test
    @DisplayName("CLASS 句で決めた級を問える (FR-046)")
    void aClassMayBeWrittenInSpecialNames() {
        assertEquals("YES|NO|", run(
                List.of("    CLASS HEXDIGIT IS '0' THRU '9' 'A' THRU 'F'."),
                List.of("01 WS-X PIC X(3) VALUE '1AF'.",
                        "01 WS-Y PIC X(3) VALUE '1AG'."),
                "    IF WS-X IS HEXDIGIT DISPLAY 'YES' ELSE DISPLAY 'NO' END-IF",
                "    IF WS-Y IS HEXDIGIT DISPLAY 'YES' ELSE DISPLAY 'NO' END-IF."));
    }

    @Test
    @DisplayName("級条件は EVALUATE の主語にも置ける (FR-046, FR-047)")
    void aClassConditionMayBeAnEvaluateSubject() {
        assertEquals("DIGITS|", run(List.of(),
                List.of("01 WS-X PIC X(3) VALUE '123'."),
                "    EVALUATE WS-X NUMERIC",
                "        WHEN TRUE DISPLAY 'DIGITS'",
                "        WHEN FALSE DISPLAY 'OTHER'",
                "    END-EVALUATE."));
        assertEquals("OTHER|", run(List.of(),
                List.of("01 WS-X PIC X(3) VALUE 'ABC'."),
                "    EVALUATE WS-X NUMERIC",
                "        WHEN TRUE DISPLAY 'DIGITS'",
                "        WHEN FALSE DISPLAY 'OTHER'",
                "    END-EVALUATE."));
    }

    @Test
    @DisplayName("符号を問う相手は算術式でよい (FR-046)")
    void aSignConditionMayAskAboutAnExpression() {
        assertEquals("YES|", run(List.of(),
                List.of("01 WS-N PIC 9 VALUE 2."),
                "    IF 3 ** WS-N - 9 IS ZERO DISPLAY 'YES'",
                "        ELSE DISPLAY 'NO' END-IF."));
    }

    @Test
    @DisplayName("知らない級の名前は誤りとして報告する (FR-046)")
    void anUndefinedClassNameIsReported() {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. CLASSES.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-X PIC X(3).",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    IF WS-X IS WHATEVER DISPLAY 'YES' END-IF.",
                "    STOP RUN.")) {
            FixedFormatSource.append(sb, line);
        }

        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, sb.toString());

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("undefined class-name"),
                () -> result.diagnostics().toString());
    }
}
