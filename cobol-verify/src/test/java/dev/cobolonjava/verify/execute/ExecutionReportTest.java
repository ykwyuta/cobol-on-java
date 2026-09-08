package dev.cobolonjava.verify.execute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 動かした結果の数え上げ (暫定判断 P-062)。 */
@Tag("V1")
class ExecutionReportTest {

    private static ExecutionReport report() {
        return new ExecutionReport(List.of(
                RunOutcome.reported("NC101A", "NC", 93, 93, 0, 0, 0, List.of()),
                RunOutcome.reported("NC104A", "NC", 40, 52, 7, 3, 2, List.of(
                        new TestReport.Failure("MULTIPLY BY", "MPY-TEST-1", ""),
                        new TestReport.Failure("MULTIPLY BY", "MPY-TEST-2", ""),
                        new TestReport.Failure("DIVIDE BY", "DIV-TEST-1", ""))),
                RunOutcome.notCompiled("CM101M", "CM", "extraneous input 'COMMUNICATION'"),
                RunOutcome.crashed("IX401M", "IX", "NullPointerException"),
                RunOutcome.timedOut("SQ999X", "SQ", 60)));
    }

    @Test
    @DisplayName("結末ごとに本数を数える (P-062)")
    void programsAreCountedByOutcome() {
        ExecutionReport report = report();
        assertEquals(1, report.passed());
        assertEquals(1, report.failed());
        assertEquals(1, report.notCompiled());
        assertEquals(1, report.crashed());
        assertEquals(1, report.timedOut());
        assertEquals(20.0, report.rate(), 0.001);
    }

    @Test
    @DisplayName("検査の数も別に数える (P-062)")
    void checksAreCountedSeparately() {
        // 本数だけでは粗い。1 本の中で 1 件落ちてもその本は落ちたことになる
        ExecutionReport report = report();
        assertEquals(133, report.executedChecks());
        assertEquals(7, report.failedChecks());
        assertEquals(3, report.deletedChecks());
        assertEquals(2, report.inspectedChecks());
        assertEquals(100.0 * 133 / 140, report.checkRate(), 0.001);
    }

    @Test
    @DisplayName("落ちた検査の多い順に並べる (P-062)")
    void theWorstProgramsComeFirst() {
        assertEquals(List.of("NC104A"), report().worst(5).stream().map(RunOutcome::name).toList());
    }

    @Test
    @DisplayName("翻訳できないものは「動かなかった」に含めない (P-062)")
    void notCompiledIsNotABrokenRun() {
        // まだ書いていない機能と、いま直すべき不具合を混ぜない
        assertEquals(List.of("IX401M", "SQ999X"),
                report().notRun().stream().map(RunOutcome::name).toList());
    }

    @Test
    @DisplayName("落ちた検査を機能ごとに数える (P-062)")
    void failuresAreCountedByFeature() {
        // 本数で数えると「1 本が全滅」までしか分からない。機能で数えると
        // どの言語機能が壊れているかが出る
        assertEquals(List.of("MULTIPLY BY", "DIVIDE BY"),
                report().failedFeatures(5).stream().map(java.util.Map.Entry::getKey).toList());
        assertEquals(2L, report().failedFeatures(5).get(0).getValue());
    }

    @Test
    @DisplayName("区分ごとに数える (P-062)")
    void groupsAreCountedSeparately() {
        assertEquals(2, report().byGroup().get("NC").outcomes().size());
        assertTrue(report().text("題").contains("NC"));
    }
}
