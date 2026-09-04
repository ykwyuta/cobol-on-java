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

/**
 * {@code ADD CORRESPONDING} と {@code SUBTRACT CORRESPONDING} (要件 FR-044)。
 *
 * <p>{@code MOVE CORRESPONDING} との違いは 2 つある。対象が<b>数値の基本項目どうしの組</b>に
 * 限られること、そして {@code ON SIZE ERROR} が組ごとではなく<b>全体で 1 つ</b>であることである。
 */
@Tag("V1")
class CorrespondingArithmeticTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(CorrespondingArithmeticTest.class.getClassLoader());
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

    /** 名前が 2 つ合い、1 つは名前が合わない組。 */
    private static final List<String> PAIRS = List.of(
            "01 WS-A.",
            "   05 N1 PIC 9(3) VALUE 010.",
            "   05 N2 PIC 9(3) VALUE 020.",
            "   05 N3 PIC 9(3) VALUE 030.",
            "01 WS-B.",
            "   05 N1 PIC 9(3) VALUE 100.",
            "   05 N2 PIC 9(3) VALUE 200.",
            "   05 N9 PIC 9(3) VALUE 900.");

    @Test
    @DisplayName("名前の合う数値項目どうしを足す (FR-044)")
    void addCorrespondingAddsEachMatchingPair() {
        // N1 と N2 だけが増える。N9 は名前が合わないので動かない
        assertEquals("010020030" + "110220900", run(PAIRS,
                "ADD CORRESPONDING WS-A TO WS-B."));
    }

    @Test
    @DisplayName("SUBTRACT CORRESPONDING は受取項目から引く (FR-044)")
    void subtractCorrespondingSubtractsFromTheReceiver() {
        assertEquals("010020030" + "090180900", run(PAIRS,
                "SUBTRACT CORRESPONDING WS-A FROM WS-B."));
    }

    @Test
    @DisplayName("CORR と書いても同じである (FR-044)")
    void corrIsTheSameAsCorresponding() {
        assertEquals("010020030" + "110220900", run(PAIRS,
                "ADD CORR WS-A TO WS-B."));
    }

    @Test
    @DisplayName("ROUNDED はすべての組に効く (FR-042, FR-044)")
    void roundedAppliesToEveryPair() {
        // 0.25 を足すと 1.25 と 2.25。丸めれば 1.3 と 2.3、切り捨てれば 1.2 と 2.2
        assertEquals("025025" + "1323", run(
                List.of("01 WS-A.",
                        "   05 N1 PIC 9V99 VALUE 0.25.",
                        "   05 N2 PIC 9V99 VALUE 0.25.",
                        "01 WS-B.",
                        "   05 N1 PIC 9V9 VALUE 1.0.",
                        "   05 N2 PIC 9V9 VALUE 2.0."),
                "ADD CORRESPONDING WS-A TO WS-B ROUNDED."));
    }

    @Test
    @DisplayName("数値でない組は選ばない (FR-044)")
    void nonNumericPairsAreNotSelected() {
        // X は英数字なので足しようがない。N だけが増える
        assertEquals("ab" + "001" + "cd" + "011", run(
                List.of("01 WS-A.",
                        "   05 X PIC X(2) VALUE 'ab'.",
                        "   05 N PIC 9(3) VALUE 001.",
                        "01 WS-B.",
                        "   05 X PIC X(2) VALUE 'cd'.",
                        "   05 N PIC 9(3) VALUE 010."),
                "ADD CORRESPONDING WS-A TO WS-B."));
    }

    @Test
    @DisplayName("ON SIZE ERROR は全体で 1 度だけ通る (FR-043, FR-044)")
    void theSizeErrorBranchRunsOnlyOnce() {
        // 2 つの組がどちらもあふれる。組ごとに通っていれば計数は 2 になる
        assertEquals("900900" + "100100" + "1", run(
                List.of("01 WS-A.",
                        "   05 N1 PIC 9(3) VALUE 900.",
                        "   05 N2 PIC 9(3) VALUE 900.",
                        "01 WS-B.",
                        "   05 N1 PIC 9(3) VALUE 100.",
                        "   05 N2 PIC 9(3) VALUE 100.",
                        "01 WS-C PIC 9 VALUE 0."),
                "ADD CORRESPONDING WS-A TO WS-B",
                "    ON SIZE ERROR ADD 1 TO WS-C",
                "END-ADD."));
    }

    @Test
    @DisplayName("あふれた組だけが変わらずに残る (FR-043)")
    void onlyTheOverflowingReceiverIsLeftAlone() {
        // N1 はあふれるので 100 のまま。N2 は収まるので増える
        assertEquals("900001" + "100101" + "1", run(
                List.of("01 WS-A.",
                        "   05 N1 PIC 9(3) VALUE 900.",
                        "   05 N2 PIC 9(3) VALUE 001.",
                        "01 WS-B.",
                        "   05 N1 PIC 9(3) VALUE 100.",
                        "   05 N2 PIC 9(3) VALUE 100.",
                        "01 WS-C PIC 9 VALUE 0."),
                "ADD CORRESPONDING WS-A TO WS-B",
                "    ON SIZE ERROR ADD 1 TO WS-C",
                "END-ADD."));
    }

    @Test
    @DisplayName("あふれなければ NOT ON SIZE ERROR を通る (FR-043)")
    void theNotOnSizeErrorBranchRunsWhenNothingOverflows() {
        assertEquals("010020030" + "110220900" + "1", run(
                List.of("01 WS-A.",
                        "   05 N1 PIC 9(3) VALUE 010.",
                        "   05 N2 PIC 9(3) VALUE 020.",
                        "   05 N3 PIC 9(3) VALUE 030.",
                        "01 WS-B.",
                        "   05 N1 PIC 9(3) VALUE 100.",
                        "   05 N2 PIC 9(3) VALUE 200.",
                        "   05 N9 PIC 9(3) VALUE 900.",
                        "01 WS-C PIC 9 VALUE 0."),
                "ADD CORRESPONDING WS-A TO WS-B",
                "    NOT ON SIZE ERROR ADD 1 TO WS-C",
                "END-ADD."));
    }

    @Test
    @DisplayName("集団項目の添字を引き継ぐ (FR-024, FR-044)")
    void subscriptsOnTheGroupCarryIntoEachPair() {
        // 2 番目の要素の 2 だけが WS-B へ足される
        assertEquals("001002" + "012", run(
                List.of("01 WS-A.",
                        "   05 R OCCURS 2 TIMES.",
                        "      10 N PIC 9(3).",
                        "01 WS-B.",
                        "   05 N PIC 9(3) VALUE 010."),
                "MOVE 1 TO N OF R (1)",
                "MOVE 2 TO N OF R (2)",
                "ADD CORRESPONDING R (2) TO WS-B."));
    }

    @Test
    @DisplayName("数値の組が 1 つもなければ誤りとして報告する (FR-044)")
    void anEmptyCorrespondenceIsReported() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-A.", "   05 X PIC X.", "01 WS-B.", "   05 X PIC X."),
                "ADD CORRESPONDING WS-A TO WS-B.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("no numeric elementary pairs"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("基本項目を書いたら誤りとして報告する (FR-044)")
    void anElementaryOperandIsReported() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-A PIC 9(3).", "01 WS-B.", "   05 N PIC 9(3)."),
                "ADD CORRESPONDING WS-A TO WS-B.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("requires a group item"),
                result.diagnostics().toString());
    }
}
