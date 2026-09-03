package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@code STRING} と {@code UNSTRING} を翻訳して実行し、記憶域の中身で確かめる。 */
@Tag("V1")
class StringGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(StringGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static CobolCompiler.Result compile(List<String> storage, String... procedure) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.")) {
            sb.append("       ").append(line).append('\n');
        }
        for (String line : storage) {
            sb.append("       ").append(line).append('\n');
        }
        sb.append("       PROCEDURE DIVISION.\n");
        for (String line : procedure) {
            sb.append("       ").append(line).append('\n');
        }
        return CobolCompiler.standard().compile(FILE, sb.toString());
    }

    private static String run(List<String> storage, String... procedure) {
        CobolCompiler.Result result = compile(storage, procedure);
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

    // ---- STRING ----

    @Test
    @DisplayName("STRING は送出項目をつないで書く (FR-065)")
    void stringJoinsItsSources() {
        // 期待値は記憶域の全体である。送出項目 WS-A WS-B もそのまま残る
        assertEquals("ABC" + "DE" + "ABCDE-----", run(
                List.of("01 WS-A PIC X(3) VALUE 'ABC'.",
                        "01 WS-B PIC X(2) VALUE 'DE'.",
                        "01 WS-T PIC X(10) VALUE ALL '-'."),
                "STRING WS-A WS-B DELIMITED BY SIZE INTO WS-T."));
    }

    @Test
    @DisplayName("受取項目の残りは埋めない (FR-065)")
    void theRestOfTheReceiverIsLeftAlone() {
        // MOVE が残りを空白で埋めるのとは違う。書いた分だけが変わる
        assertEquals("AB" + "AB--------", run(
                List.of("01 WS-A PIC X(2) VALUE 'AB'.",
                        "01 WS-T PIC X(10) VALUE ALL '-'."),
                "STRING WS-A DELIMITED BY SIZE INTO WS-T."));
    }

    @Test
    @DisplayName("DELIMITED BY で送出を切り詰められる (FR-065)")
    void delimitedByCutsTheSource() {
        // 空白の手前までしか書かれない
        assertEquals("AB CD" + "AB--------", run(
                List.of("01 WS-A PIC X(5) VALUE 'AB CD'.",
                        "01 WS-T PIC X(10) VALUE ALL '-'."),
                "STRING WS-A DELIMITED BY ' ' INTO WS-T."));
    }

    @Test
    @DisplayName("定数もつなげる (FR-065)")
    void aLiteralCanBeJoined() {
        assertEquals("1" + "A=1-------", run(
                List.of("01 WS-A PIC X VALUE '1'.", "01 WS-T PIC X(10) VALUE ALL '-'."),
                "STRING 'A=' WS-A DELIMITED BY SIZE INTO WS-T."));
    }

    @Test
    @DisplayName("WITH POINTER は書き始める位置を決め、書いたあとの位置を返す (FR-065)")
    void thePointerDecidesWhereToStartAndIsUpdated() {
        // 3 桁目から 2 バイト書き、ポインタは 5 になる
        assertEquals("AB" + "--AB------" + "05", run(
                List.of("01 WS-A PIC X(2) VALUE 'AB'.",
                        "01 WS-T PIC X(10) VALUE ALL '-'.",
                        "01 WS-P PIC 9(2) VALUE 3."),
                "STRING WS-A DELIMITED BY SIZE INTO WS-T WITH POINTER WS-P."));
    }

    @Test
    @DisplayName("受取項目に収まらなければ ON OVERFLOW を通る (FR-065)")
    void anOverflowingStringTakesItsOverflowBranch() {
        assertEquals("ABC" + "ABC" + "E", run(
                List.of("01 WS-A PIC X(3) VALUE 'ABC'.",
                        "01 WS-T PIC X(3) VALUE ALL '-'.",
                        "01 WS-R PIC X VALUE '-'."),
                "STRING WS-A WS-A DELIMITED BY SIZE INTO WS-T",
                "    ON OVERFLOW MOVE 'E' TO WS-R",
                "END-STRING."));
    }

    @Test
    @DisplayName("収まれば NOT ON OVERFLOW の側を通る (FR-065)")
    void aStringThatFitsTakesTheOtherBranch() {
        assertEquals("ABC" + "ABC" + "O", run(
                List.of("01 WS-A PIC X(3) VALUE 'ABC'.",
                        "01 WS-T PIC X(3) VALUE ALL '-'.",
                        "01 WS-R PIC X VALUE '-'."),
                "STRING WS-A DELIMITED BY SIZE INTO WS-T",
                "    ON OVERFLOW MOVE 'E' TO WS-R",
                "    NOT ON OVERFLOW MOVE 'O' TO WS-R",
                "END-STRING."));
    }

    // ---- UNSTRING ----

    @Test
    @DisplayName("UNSTRING は区切りで分けて配る (FR-065)")
    void unstringSplitsItsSource() {
        assertEquals("AB,CD" + "AB  " + "CD  ", run(
                List.of("01 WS-S PIC X(5) VALUE 'AB,CD'.",
                        "01 WS-1 PIC X(4) VALUE ALL ' '.",
                        "01 WS-2 PIC X(4) VALUE ALL ' '."),
                "UNSTRING WS-S DELIMITED BY ',' INTO WS-1 WS-2."));
    }

    @Test
    @DisplayName("受取項目への転記は左詰めで残りを空白にする (FR-065)")
    void eachFieldIsMovedAsAlphanumeric() {
        // STRING が残りを埋めないのとは対照的である
        assertEquals("A,BXXXX" + "A   " + "B   ", run(
                List.of("01 WS-S PIC X(7) VALUE 'A,BXXXX'.",
                        "01 WS-1 PIC X(4) VALUE ALL 'Z'.",
                        "01 WS-2 PIC X(4) VALUE ALL 'Z'."),
                "UNSTRING WS-S DELIMITED BY ',' OR 'X' INTO WS-1 WS-2."));
    }

    @Test
    @DisplayName("ALL は連続する区切りを 1 個として扱う (FR-065)")
    void allTreatsRepeatedDelimitersAsOne() {
        assertEquals("A,,,B A   B   ", run(
                List.of("01 WS-S PIC X(6) VALUE 'A,,,B '.",
                        "01 WS-1 PIC X(4) VALUE ALL ' '.",
                        "01 WS-2 PIC X(4) VALUE ALL ' '."),
                "UNSTRING WS-S DELIMITED BY ALL ',' INTO WS-1 WS-2."));
    }

    @Test
    @DisplayName("DELIMITER IN と COUNT IN を受け取れる (FR-065)")
    void theDelimiterAndCountCanBeCaptured() {
        assertEquals("AB,CDAB  ,02", run(
                List.of("01 WS-S PIC X(5) VALUE 'AB,CD'.",
                        "01 WS-1 PIC X(4) VALUE ALL ' '.",
                        "01 WS-D PIC X VALUE ' '.",
                        "01 WS-C PIC 9(2) VALUE 0."),
                "UNSTRING WS-S DELIMITED BY ','",
                "    INTO WS-1 DELIMITER IN WS-D COUNT IN WS-C."));
    }

    @Test
    @DisplayName("TALLYING IN は配った項目の数を返す (FR-065)")
    void tallyingCountsTheFieldsFilled() {
        assertEquals("A,B,CA   B   02", run(
                List.of("01 WS-S PIC X(5) VALUE 'A,B,C'.",
                        "01 WS-1 PIC X(4) VALUE ALL ' '.",
                        "01 WS-2 PIC X(4) VALUE ALL ' '.",
                        "01 WS-N PIC 9(2) VALUE 0."),
                "UNSTRING WS-S DELIMITED BY ',' INTO WS-1 WS-2 TALLYING IN WS-N."));
    }

    @Test
    @DisplayName("区切りを書かなければ受取項目の長さぶんを取る (FR-065)")
    void withoutDelimitersEachFieldTakesItsLength() {
        // 区切りがないので 1 つ目が 4 バイト、2 つ目が残りの 2 バイトを取る
        assertEquals("ABCDEF" + "ABCD" + "EF  ", run(
                List.of("01 WS-S PIC X(6) VALUE 'ABCDEF'.",
                        "01 WS-1 PIC X(4) VALUE ALL ' '.",
                        "01 WS-2 PIC X(4) VALUE ALL ' '."),
                "UNSTRING WS-S INTO WS-1 WS-2."));
    }

    @Test
    @DisplayName("送出を配りきれなければ ON OVERFLOW を通る (FR-065)")
    void anOverflowingUnstringTakesItsOverflowBranch() {
        assertEquals("A,B,CA   B   E", run(
                List.of("01 WS-S PIC X(5) VALUE 'A,B,C'.",
                        "01 WS-1 PIC X(4) VALUE ALL ' '.",
                        "01 WS-2 PIC X(4) VALUE ALL ' '.",
                        "01 WS-R PIC X VALUE '-'."),
                "UNSTRING WS-S DELIMITED BY ',' INTO WS-1 WS-2",
                "    ON OVERFLOW MOVE 'E' TO WS-R",
                "END-UNSTRING."));
    }

    @Test
    @DisplayName("数値でない POINTER は誤りとして報告する (FR-065)")
    void aNonNumericPointerIsReported() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-A PIC X(2).", "01 WS-T PIC X(4).", "01 WS-P PIC X."),
                "STRING WS-A DELIMITED BY SIZE INTO WS-T WITH POINTER WS-P.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("POINTER"),
                result.diagnostics().toString());
    }
}
