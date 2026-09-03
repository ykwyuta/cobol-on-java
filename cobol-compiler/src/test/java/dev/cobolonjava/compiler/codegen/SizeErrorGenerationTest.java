package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@code ON SIZE ERROR} を翻訳して実行し、受取項目に何が残るかを確かめる。 */
@Tag("V1")
class SizeErrorGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(SizeErrorGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String run(List<String> storage, String... procedure) {
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
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            Storage executed = program.runFresh();
            return CodePages.DEFAULT.decode(executed.array());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
    }

    /** 受取項目と、条件が立ったかどうかを書き分ける印。 */
    private static final List<String> STORAGE = List.of(
            "01 WS-A PIC 9(3) VALUE 900.",
            "01 WS-R PIC X VALUE '-'.");

    @Test
    @DisplayName("ON SIZE ERROR があれば受取項目は変わらない (FR-043)")
    void aCheckedOverflowLeavesTheReceiverAlone() {
        // 900 + 200 = 1100 は 3 桁に収まらない。指定があれば 900 のまま
        assertEquals("900E", run(STORAGE,
                "ADD 200 TO WS-A",
                "    ON SIZE ERROR MOVE 'E' TO WS-R",
                "END-ADD."));
    }

    @Test
    @DisplayName("ON SIZE ERROR がなければ上位桁が切り捨てられる (FR-043)")
    void anUncheckedOverflowIsTruncated() {
        // 同じ計算でも、指定がなければ 100 が残る
        assertEquals("100-", run(STORAGE, "ADD 200 TO WS-A."));
    }

    @Test
    @DisplayName("収まるなら NOT ON SIZE ERROR の側を通る (FR-043)")
    void aResultThatFitsTakesTheOtherBranch() {
        assertEquals("950O", run(STORAGE,
                "ADD 50 TO WS-A",
                "    ON SIZE ERROR MOVE 'E' TO WS-R",
                "    NOT ON SIZE ERROR MOVE 'O' TO WS-R",
                "END-ADD."));
    }

    @Test
    @DisplayName("NOT ON SIZE ERROR だけでも書ける (FR-043)")
    void theNotPhraseMayStandAlone() {
        assertEquals("900-", run(STORAGE,
                "ADD 200 TO WS-A",
                "    NOT ON SIZE ERROR MOVE 'O' TO WS-R",
                "END-ADD."));
    }

    @Test
    @DisplayName("0 除算は SIZE ERROR 条件を立てる (FR-043)")
    void divisionByZeroRaisesTheCondition() {
        // 割る前に除数を調べる。受取項目は変わらない
        assertEquals("900000E", run(
                List.of("01 WS-A PIC 9(3) VALUE 900.",
                        "01 WS-Z PIC 9(3) VALUE 0.",
                        "01 WS-R PIC X VALUE '-'."),
                "DIVIDE WS-Z INTO WS-A",
                "    ON SIZE ERROR MOVE 'E' TO WS-R",
                "END-DIVIDE."));
    }

    @Test
    @DisplayName("0 でなければ普通に割る (FR-043)")
    void anOrdinaryDivisionStillWorks() {
        assertEquals("300003O", run(
                List.of("01 WS-A PIC 9(3) VALUE 900.",
                        "01 WS-Z PIC 9(3) VALUE 3.",
                        "01 WS-R PIC X VALUE '-'."),
                "DIVIDE WS-Z INTO WS-A",
                "    ON SIZE ERROR MOVE 'E' TO WS-R",
                "    NOT ON SIZE ERROR MOVE 'O' TO WS-R",
                "END-DIVIDE."));
    }

    @Test
    @DisplayName("受取項目が複数なら、収まったものだけが書き換わる (FR-043)")
    void onlyTheReceiversThatFitAreWritten() {
        // WS-A は 1100 になって収まらず 900 のまま、WS-B は 200 になる
        assertEquals("900200E", run(
                List.of("01 WS-A PIC 9(3) VALUE 900.",
                        "01 WS-B PIC 9(3) VALUE 000.",
                        "01 WS-R PIC X VALUE '-'."),
                "ADD 200 TO WS-A WS-B",
                "    ON SIZE ERROR MOVE 'E' TO WS-R",
                "END-ADD."));
    }

    @Test
    @DisplayName("SIZE ERROR の中に別の文を書ける (FR-043, FR-061)")
    void theSizeErrorPhraseHoldsOrdinaryStatements() {
        assertEquals("900999E", run(
                List.of("01 WS-A PIC 9(3) VALUE 900.",
                        "01 WS-B PIC 9(3) VALUE 000.",
                        "01 WS-R PIC X VALUE '-'."),
                "ADD 200 TO WS-A",
                "    ON SIZE ERROR",
                "        MOVE 999 TO WS-B",
                "        MOVE 'E' TO WS-R",
                "END-ADD."));
    }

    @Test
    @DisplayName("SIZE ERROR の中で PERFORM を書ける (FR-043, FR-061)")
    void theSizeErrorPhraseMayPerformAParagraph() {
        // 条件が立てば PERFORM と素直な流れで 2 回、立たなければ流れの 1 回だけ
        assertEquals("900002", run(
                List.of("01 WS-A PIC 9(3) VALUE 900.", "01 WS-N PIC 9(3) VALUE 0."),
                "MAIN-START.",
                "    ADD 200 TO WS-A",
                "        ON SIZE ERROR PERFORM COUNT-IT",
                "    END-ADD.",
                "COUNT-IT.",
                "    ADD 1 TO WS-N."));
        assertEquals("950001", run(
                List.of("01 WS-A PIC 9(3) VALUE 900.", "01 WS-N PIC 9(3) VALUE 0."),
                "MAIN-START.",
                "    ADD 50 TO WS-A",
                "        ON SIZE ERROR PERFORM COUNT-IT",
                "    END-ADD.",
                "COUNT-IT.",
                "    ADD 1 TO WS-N."));
    }
}
