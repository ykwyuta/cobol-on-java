package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code DIVIDE ... REMAINDER} (要件 FR-044)。
 *
 * <p>ほかの算術文と違い、1 回の計算から<b>2 つの値</b>が出る。見どころは
 * 剰余が<b>切り捨てた商</b>から求まることである。商に {@code ROUNDED} を書いても、
 * 剰余の計算に使う商は丸めない。
 */
@Tag("V1")
class DivideRemainderTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(DivideRemainderTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static Storage execute(List<String> storage, String... procedure) {
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
            return program.runFresh();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
    }

    private static String run(List<String> storage, String... procedure) {
        return CodePages.DEFAULT.decode(execute(storage, procedure).array());
    }

    private static String hex(List<String> storage, String... procedure) {
        return HexFormat.of().withUpperCase().formatHex(execute(storage, procedure).array());
    }

    /** 商と剰余の受取項目 (整数)。 */
    private static final List<String> INTEGER = List.of(
            "01 WS-Q PIC 9(3) VALUE 0.",
            "01 WS-R PIC 9(3) VALUE 0.");

    @Test
    @DisplayName("INTO は割られる側があとに書かれる (FR-044)")
    void divideIntoTakesItsDividendSecond() {
        // 20 / 7 は商 2、剰余 6
        assertEquals("002" + "006", run(INTEGER,
                "DIVIDE 7 INTO 20 GIVING WS-Q REMAINDER WS-R."));
    }

    @Test
    @DisplayName("BY は割られる側が先に書かれる (FR-044)")
    void divideByTakesItsDividendFirst() {
        assertEquals("002" + "006", run(INTEGER,
                "DIVIDE 20 BY 7 GIVING WS-Q REMAINDER WS-R."));
    }

    @Test
    @DisplayName("割り切れれば剰余は 0 になる (FR-044)")
    void anExactDivisionLeavesNoRemainder() {
        assertEquals("005" + "000", run(INTEGER,
                "DIVIDE 20 BY 4 GIVING WS-Q REMAINDER WS-R."));
    }

    @Test
    @DisplayName("剰余は商の受取項目の桁で切ってから求める (FR-044)")
    void theRemainderComesFromTheTruncatedQuotient() {
        // 20 / 7 = 2.857... 商は 2 桁で切って 2.85。剰余は 20 - 2.85 x 7 = 0.05
        assertEquals("285" + "005", run(
                List.of("01 WS-Q PIC 9V99 VALUE 0.", "01 WS-R PIC 9V99 VALUE 0."),
                "DIVIDE 20 BY 7 GIVING WS-Q REMAINDER WS-R."));
    }

    @Test
    @DisplayName("商に ROUNDED を書いても剰余は丸めた商から求めない (FR-042, FR-044)")
    void roundingTheQuotientDoesNotChangeTheRemainder() {
        // 商は 2.857... を丸めて 2.9。剰余は切り捨てた 2.8 から 20 - 2.8 x 7 = 0.4。
        // 丸めた商から求めれば 20 - 2.9 x 7 = -0.3 になってしまう
        assertEquals("29" + "04", run(
                List.of("01 WS-Q PIC 9V9 VALUE 0.", "01 WS-R PIC 9V9 VALUE 0."),
                "DIVIDE 20 BY 7 GIVING WS-Q ROUNDED REMAINDER WS-R."));
    }

    @Test
    @DisplayName("剰余の符号は割られる側に従う (FR-044)")
    void theRemainderTakesTheSignOfTheDividend() {
        // -20 / 7 は商 -2 (ゼロ方向へ切り捨て)、剰余 -20 - (-2 x 7) = -6
        assertEquals("F0F2D0" + "F0F0D2" + "F0F0D6", hex(
                List.of("01 WS-D PIC S9(3) VALUE -20.",
                        "01 WS-Q PIC S9(3) VALUE 0.",
                        "01 WS-R PIC S9(3) VALUE 0."),
                "DIVIDE WS-D BY 7 GIVING WS-Q REMAINDER WS-R."));
    }

    @Test
    @DisplayName("商と剰余はどちらも書き込む前に求める (FR-044)")
    void bothResultsAreComputedBeforeEitherIsStored() {
        // 割られる側が商の受取項目でもある。先に商を書き込めば剰余が狂う
        assertEquals("002" + "006", run(
                List.of("01 WS-Q PIC 9(3) VALUE 020.", "01 WS-R PIC 9(3) VALUE 0."),
                "DIVIDE WS-Q BY 7 GIVING WS-Q REMAINDER WS-R."));
    }

    @Test
    @DisplayName("0 で割れば ON SIZE ERROR になり受取項目は変わらない (FR-043)")
    void aZeroDivisorRaisesSizeError() {
        assertEquals("111" + "222" + "0" + "1", run(
                List.of("01 WS-Q PIC 9(3) VALUE 111.",
                        "01 WS-R PIC 9(3) VALUE 222.",
                        "01 WS-Z PIC 9 VALUE 0.",
                        "01 WS-F PIC 9 VALUE 0."),
                "DIVIDE 20 BY WS-Z GIVING WS-Q REMAINDER WS-R",
                "    ON SIZE ERROR MOVE 1 TO WS-F",
                "END-DIVIDE."));
    }

    @Test
    @DisplayName("商があふれても受取項目は変わらない (FR-043)")
    void anOverflowingQuotientLeavesBothReceivers() {
        // 1000 は 2 桁に収まらない。入らなかった商から求めた剰余に意味はないので、
        // 剰余も書き込まない
        assertEquals("11" + "22" + "1", run(
                List.of("01 WS-Q PIC 9(2) VALUE 11.",
                        "01 WS-R PIC 9(2) VALUE 22.",
                        "01 WS-F PIC 9 VALUE 0."),
                "DIVIDE 1 INTO 1000 GIVING WS-Q REMAINDER WS-R",
                "    ON SIZE ERROR MOVE 1 TO WS-F",
                "END-DIVIDE."));
    }

    @Test
    @DisplayName("あふれなければ NOT ON SIZE ERROR を通る (FR-043)")
    void theNotOnSizeErrorBranchRunsWhenNothingOverflows() {
        assertEquals("002" + "006" + "1", run(
                List.of("01 WS-Q PIC 9(3) VALUE 0.",
                        "01 WS-R PIC 9(3) VALUE 0.",
                        "01 WS-F PIC 9 VALUE 0."),
                "DIVIDE 20 BY 7 GIVING WS-Q REMAINDER WS-R",
                "    NOT ON SIZE ERROR MOVE 1 TO WS-F",
                "END-DIVIDE."));
    }

    @Test
    @DisplayName("剰余だけが収まらなければ剰余だけが残る (FR-043)")
    void anOverflowingRemainderLeavesOnlyItself() {
        // 100 / 30 は商 3、剰余 10。商は収まるが剰余は 1 桁に収まらない
        assertEquals("003" + "5" + "1", run(
                List.of("01 WS-Q PIC 9(3) VALUE 0.",
                        "01 WS-R PIC 9 VALUE 5.",
                        "01 WS-F PIC 9 VALUE 0."),
                "DIVIDE 100 BY 30 GIVING WS-Q REMAINDER WS-R",
                "    ON SIZE ERROR MOVE 1 TO WS-F",
                "END-DIVIDE."));
    }

    @Test
    @DisplayName("REMAINDER を書かない DIVIDE はこれまでどおりである (FR-044)")
    void aDivideWithoutRemainderIsUnchanged() {
        assertEquals("002", run(
                List.of("01 WS-Q PIC 9(3) VALUE 0."),
                "DIVIDE 20 BY 7 GIVING WS-Q."));
    }
}
