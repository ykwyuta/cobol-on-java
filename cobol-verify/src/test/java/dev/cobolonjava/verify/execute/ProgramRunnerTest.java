package dev.cobolonjava.verify.execute;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.verify.corpus.CorpusRunner;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 翻訳して動かし、報告を読む道具そのものの検査 (暫定判断 P-062)。
 *
 * <p>道具に検査を仕込むのは、<b>道具が作った失敗を処理系の失敗と読み違えない</b>ため
 * である。読み違えると、数そのものが信用できなくなる。
 *
 * <p>ここで動かすのは CCVS85 ではなく、報告の形だけを真似た小さなプログラムである。
 * 処理系の不具合を当てにするわけにはいかない。不具合は直るからである。
 */
@Tag("V1")
class ProgramRunnerTest {

    private static final ProgramRunner RUNNER = new ProgramRunner(CobolCompiler.standard(), 30);

    /**
     * 報告の形をした紙を 1 枚書くだけのプログラム。
     *
     * <p>文面は作業場所の項目に組み立てる。1 行に書くと<b>72 桁を越えて切れる</b>。
     * 検査のソースが切れると、道具ではなく検査の書き方が失敗を作る。
     */
    private static String reporting(String executed, String total, String failed) {
        return source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. TINY.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT PRINT-FILE ASSIGN TO PRTDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  PRINT-FILE.",
                "01  PRINT-REC PIC X(60).",
                "WORKING-STORAGE SECTION.",
                "01  L-END.",
                "    05 FILLER PIC X(21) VALUE '     END OF TEST-  T'.",
                "01  L-SUM.",
                "    05 FILLER PIC X(5) VALUE SPACE.",
                "    05 FILLER PIC XXX VALUE '" + executed + "'.",
                "    05 FILLER PIC X(4) VALUE ' OF '.",
                "    05 FILLER PIC XXX VALUE '" + total + "'.",
                "    05 FILLER PIC X(36) VALUE",
                "       '  TESTS WERE EXECUTED SUCCESSFULLY'.",
                "01  L-FAIL.",
                "    05 FILLER PIC X(5) VALUE SPACE.",
                "    05 FILLER PIC XXX VALUE '" + failed + "'.",
                "    05 FILLER PIC X(16) VALUE ' TEST(S) FAILED'.",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    OPEN OUTPUT PRINT-FILE",
                "    MOVE L-END TO PRINT-REC",
                "    WRITE PRINT-REC",
                "    MOVE L-SUM TO PRINT-REC",
                "    WRITE PRINT-REC",
                "    MOVE L-FAIL TO PRINT-REC",
                "    WRITE PRINT-REC",
                "    CLOSE PRINT-FILE",
                "    STOP RUN.");
    }

    private static String source(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append("       ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static RunOutcome run(String text) {
        return RUNNER.run(new CorpusRunner.Source("TINY.cbl", "XX", text));
    }

    @Test
    @DisplayName("落ちた検査が無ければ通ったとする (P-062)")
    void aCleanReportPasses() {
        RunOutcome outcome = run(reporting("012", "012", "NO "));
        assertEquals(RunOutcome.Status.PASSED, outcome.status(), outcome.failure());
        assertEquals(12, outcome.executed());
        assertEquals(0, outcome.failed());
    }

    @Test
    @DisplayName("落ちた検査があれば落ちたとする (P-062)")
    void aFailingReportFails() {
        RunOutcome outcome = run(reporting("009", "012", "003"));
        assertEquals(RunOutcome.Status.FAILED, outcome.status(), outcome.failure());
        assertEquals(9, outcome.executed());
        assertEquals(3, outcome.failed());
    }

    @Test
    @DisplayName("翻訳が通らないものは、動かなかったのとは別に数える (P-062)")
    void aProgramThatDoesNotCompileIsCountedApart() {
        RunOutcome outcome = run(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. BAD.",
                "PROCEDURE DIVISION.",
                "    FROBNICATE THE WIDGET."));
        assertEquals(RunOutcome.Status.NOT_COMPILED, outcome.status());
    }

    @Test
    @DisplayName("報告を書く仕掛けの無いものは、動かさずに数える (P-062)")
    void aProgramWithoutTheReportMachineryIsNotRun() {
        // 翻訳の診断を見るための検査である。動かして「報告が無い」と数えると、
        // 道具が処理系の失敗を作ることになる
        RunOutcome outcome = run(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. QUIET.",
                "PROCEDURE DIVISION.",
                "    STOP RUN."));
        assertEquals(RunOutcome.Status.COMPILE_ONLY, outcome.status());
    }

    @Test
    @DisplayName("報告を書くはずのものが書かなければ壊れたとする (P-062)")
    void aTestThatFailsToWriteItsReportCrashed() {
        // 紙が無ければ、通ったのか落ちたのかが分からない。分からないものを通ったと
        // 数えてはならない
        RunOutcome outcome = run(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. QUIET.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01  L-END PIC X(20) VALUE '  END OF TEST-  Q'.",
                "PROCEDURE DIVISION.",
                "    STOP RUN."));
        assertEquals(RunOutcome.Status.CRASHED, outcome.status());
    }

    @Test
    @DisplayName("呼ばれる側は、呼ぶ側と一緒に翻訳して 1 本として数える (P-062)")
    void aCalledProgramIsCompiledWithItsCaller() {
        // 別々に動かすと、呼ぶ側は「呼び先が無い」で落ち、呼ばれる側は
        // 「引数が渡されていない」で落ちる。どちらも道具が作った失敗である
        ExecutionReport report = RUNNER.run(List.of(
                new CorpusRunner.Source("XX101A.cbl", "XX", calling()),
                new CorpusRunner.Source("XX101A,SUBRTN,XX102A.cbl", "XX", subroutine())));
        assertEquals(1, report.outcomes().size());
        assertEquals(1, report.passed(), report.outcomes().toString());
    }

    /** 副プログラムを呼んでから報告を書くプログラム。 */
    private static String calling() {
        return reporting("001", "001", "NO ").replace(
                "    OPEN OUTPUT PRINT-FILE",
                "    CALL 'XX102A'\n           OPEN OUTPUT PRINT-FILE");
    }

    private static String subroutine() {
        return source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. XX102A.",
                "PROCEDURE DIVISION.",
                "    EXIT PROGRAM.");
    }

    @Test
    @DisplayName("返ってこないものは見捨てて次へ進む (P-062)")
    void anEndlessProgramIsAbandoned() {
        RunOutcome outcome = new ProgramRunner(CobolCompiler.standard(), 1)
                .run(new CorpusRunner.Source("LOOP.cbl", "XX", source(
                        "IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. LOOPER.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-N PIC 9(9) COMP VALUE 0.",
                        "01 L-END PIC X(20) VALUE '  END OF TEST-  L'.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    PERFORM UNTIL WS-N < 0",
                        "        CONTINUE",
                        "    END-PERFORM",
                        "    STOP RUN.")));
        assertEquals(RunOutcome.Status.TIMED_OUT, outcome.status());
    }

    @Test
    @DisplayName("束をまとめて流せる (P-062)")
    void aBatchIsCounted() {
        ExecutionReport report = RUNNER.run(List.of(
                new CorpusRunner.Source("A.cbl", "XX", reporting("001", "001", "NO ")),
                new CorpusRunner.Source("B.cbl", "XX", reporting("000", "001", "001"))));
        assertEquals(1, report.passed());
        assertEquals(1, report.failed());
    }
}
