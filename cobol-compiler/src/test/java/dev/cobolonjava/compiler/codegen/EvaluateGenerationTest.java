package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** {@code EVALUATE} を翻訳して実行し、どの枝を通ったかを出力で確かめる。 */
@Tag("V1")
class EvaluateGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(EvaluateGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String output(List<String> storage, String... procedure) {
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
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, sb.toString());
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            program.runFresh(ProgramContext.capturing(sink));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
    }

    /** 主語 1 個の値による分岐。値を変えて通った枝を返す。 */
    private static String branchFor(String value) {
        return output(List.of("01 WS-G PIC X VALUE '" + value + "'."),
                "EVALUATE WS-G",
                "    WHEN 'A' DISPLAY 'GOOD'",
                "    WHEN 'B' DISPLAY 'FAIR'",
                "    WHEN OTHER DISPLAY 'POOR'",
                "END-EVALUATE.");
    }

    @Test
    @DisplayName("主語と目的語が一致した枝を通る (FR-061)")
    void theMatchingBranchIsTaken() {
        assertEquals("GOOD|", branchFor("A"));
        assertEquals("FAIR|", branchFor("B"));
        assertEquals("POOR|", branchFor("C"));
    }

    @Test
    @DisplayName("最初に当たった枝だけを通る (FR-061)")
    void onlyTheFirstMatchingBranchRuns() {
        assertEquals("ONE|", output(List.of("01 WS-N PIC 9 VALUE 1."),
                "EVALUATE WS-N",
                "    WHEN 1 DISPLAY 'ONE'",
                "    WHEN 1 DISPLAY 'AGAIN'",
                "END-EVALUATE."));
    }

    @Test
    @DisplayName("同じ本体に複数の WHEN を並べられる (FR-061)")
    void severalWhenClausesMayShareABody() {
        assertEquals("VOWEL|", output(List.of("01 WS-G PIC X VALUE 'E'."),
                "EVALUATE WS-G",
                "    WHEN 'A'",
                "    WHEN 'E'",
                "    WHEN 'I' DISPLAY 'VOWEL'",
                "    WHEN OTHER DISPLAY 'OTHER'",
                "END-EVALUATE."));
    }

    @Test
    @DisplayName("THRU で範囲を書ける (FR-061)")
    void aThruRangeMatchesEverythingBetween() {
        assertEquals("DIGIT|", output(List.of("01 WS-G PIC X VALUE '5'."),
                "EVALUATE WS-G",
                "    WHEN '0' THRU '9' DISPLAY 'DIGIT'",
                "    WHEN OTHER DISPLAY 'OTHER'",
                "END-EVALUATE."));
    }

    @Test
    @DisplayName("EVALUATE TRUE では WHEN が条件になる (FR-061)")
    void evaluateTrueTakesConditionsInItsWhen() {
        assertEquals("BIG|", output(List.of("01 WS-N PIC 9(3) VALUE 100."),
                "EVALUATE TRUE",
                "    WHEN WS-N < 10 DISPLAY 'SMALL'",
                "    WHEN WS-N > 50 DISPLAY 'BIG'",
                "    WHEN OTHER DISPLAY 'MIDDLE'",
                "END-EVALUATE."));
    }

    @Test
    @DisplayName("ALSO で主語を並べられる (FR-061)")
    void severalSubjectsAreMatchedTogether() {
        assertEquals("BOTH|", output(
                List.of("01 WS-A PIC X VALUE 'X'.", "01 WS-B PIC X VALUE 'Y'."),
                "EVALUATE WS-A ALSO WS-B",
                "    WHEN 'X' ALSO 'Z' DISPLAY 'FIRST'",
                "    WHEN 'X' ALSO 'Y' DISPLAY 'BOTH'",
                "    WHEN OTHER DISPLAY 'OTHER'",
                "END-EVALUATE."));
    }

    @Test
    @DisplayName("ANY はその位置を問わない (FR-061)")
    void anyMatchesWhateverIsThere() {
        assertEquals("FIRST|", output(
                List.of("01 WS-A PIC X VALUE 'X'.", "01 WS-B PIC X VALUE 'Q'."),
                "EVALUATE WS-A ALSO WS-B",
                "    WHEN 'X' ALSO ANY DISPLAY 'FIRST'",
                "    WHEN OTHER DISPLAY 'OTHER'",
                "END-EVALUATE."));
    }

    @Test
    @DisplayName("NOT は目的語との一致を反転する (FR-061)")
    void notInvertsTheMatch() {
        assertEquals("NOT-A|", output(List.of("01 WS-G PIC X VALUE 'B'."),
                "EVALUATE WS-G",
                "    WHEN NOT 'A' DISPLAY 'NOT-A'",
                "    WHEN OTHER DISPLAY 'OTHER'",
                "END-EVALUATE."));
    }

    @Test
    @DisplayName("WHEN OTHER がなければどこにも当たらないことがある (FR-061)")
    void withoutWhenOtherNothingMayRun() {
        assertEquals("", output(List.of("01 WS-G PIC X VALUE 'Z'."),
                "EVALUATE WS-G",
                "    WHEN 'A' DISPLAY 'GOOD'",
                "END-EVALUATE."));
    }

    @Test
    @DisplayName("EVALUATE は入れ子にできる (FR-061)")
    void evaluateStatementsNest() {
        assertEquals("INNER|", output(
                List.of("01 WS-A PIC X VALUE 'X'.", "01 WS-B PIC X VALUE 'Y'."),
                "EVALUATE WS-A",
                "    WHEN 'X'",
                "        EVALUATE WS-B",
                "            WHEN 'Y' DISPLAY 'INNER'",
                "            WHEN OTHER DISPLAY 'INNER-OTHER'",
                "        END-EVALUATE",
                "    WHEN OTHER DISPLAY 'OUTER'",
                "END-EVALUATE."));
    }

    @Test
    @DisplayName("STOP RUN はそこで実行を終える (FR-061)")
    void stopRunEndsTheProgram() {
        assertEquals("BEFORE|", output(List.of("01 WS-A PIC X."),
                "MAIN-START.",
                "    DISPLAY 'BEFORE'",
                "    STOP RUN.",
                "NEVER-REACHED.",
                "    DISPLAY 'AFTER'."));
    }

    @Test
    @DisplayName("GOBACK も同じく実行を終える (FR-061)")
    void gobackEndsTheProgramToo() {
        assertEquals("BEFORE|", output(List.of("01 WS-A PIC X."),
                "MAIN-START.",
                "    DISPLAY 'BEFORE'",
                "    GOBACK.",
                "NEVER-REACHED.",
                "    DISPLAY 'AFTER'."));
    }

    @Test
    @DisplayName("PERFORM の中の STOP RUN も全体を終える (FR-061)")
    void stopRunInsideAPerformEndsEverything() {
        // 段落は別のメソッドになるため、単に戻るだけでは呼び出し元へ返ってしまう
        assertEquals("IN|", output(List.of("01 WS-A PIC X."),
                "MAIN-START.",
                "    PERFORM STOPPER",
                "    DISPLAY 'AFTER'.",
                "STOPPER.",
                "    DISPLAY 'IN'",
                "    STOP RUN."));
    }
}
