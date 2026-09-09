package dev.cobolonjava.verify.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 流した結果の数え上げ (要件 NFR-040, NFR-042)。
 */
@Tag("V1")
class CorpusReportTest {

    private static CorpusReport report() {
        return new CorpusReport(List.of(
                CompileOutcome.compiled("A.cbl", "NC"),
                CompileOutcome.rejected("B.cbl", "NC",
                        List.of("B.cbl:1: unknown statement 'EVALUATE'")),
                CompileOutcome.rejected("C.cbl", "SQ",
                        List.of("C.cbl:9: unknown statement 'EVALUATE'")),
                CompileOutcome.crashed("D.cbl", "SQ", new IllegalStateException("boom"))));
    }

    @Test
    @DisplayName("全体の合格率を出す (NFR-040)")
    void theOverallRateIsCounted() {
        CorpusReport report = report();

        assertEquals(1, report.compiled());
        assertEquals(2, report.rejected());
        assertEquals(1, report.crashed());
        assertEquals(25.0, report.rate(), 0.001);
    }

    @Test
    @DisplayName("区分ごとにも数える (NFR-040)")
    void eachGroupIsCountedOnItsOwn() {
        // 1 つの数では、どこが弱いかが分からない
        CorpusReport report = report();

        assertEquals(List.of("NC", "SQ"), List.copyOf(report.byGroup().keySet()));
        assertEquals(50.0, report.byGroup().get("NC").rate(), 0.001);
        assertEquals(0.0, report.byGroup().get("SQ").rate(), 0.001);
    }

    @Test
    @DisplayName("区分を選んで数え直せる (NFR-040)")
    void asubsetOfGroupsCanBeCounted() {
        // 要件 13 章の受け入れ基準は「入出力以外のモジュール」の合格率である
        CorpusReport only = report().only(List.of("NC"));

        assertEquals(2, only.outcomes().size());
        assertEquals(50.0, only.rate(), 0.001);
    }

    @Test
    @DisplayName("理由は多い順に並ぶ (NFR-042)")
    void reasonsComeInOrderOfHowManyTheyStop() {
        // これが「次に何を書くか」の一覧である。1 件を止めている構文より
        // 100 件を止めている構文を先に書くほうがよい
        CorpusReport report = report();

        assertEquals("unknown statement 'EVALUATE'", report.reasons(3).get(0).getKey());
        assertEquals(2L, report.reasons(3).get(0).getValue());
    }

    @Test
    @DisplayName("壊れたものは名前で並べて出す (NFR-042)")
    void crashesAreListedByName() {
        assertEquals(List.of("D.cbl"), report().crashes().stream()
                .map(CompileOutcome::name).toList());
    }

    @Test
    @DisplayName("機械が読む形は数だけを出す (NFR-042)")
    void theMachineReadableFormCarriesNumbersOnly() {
        // 公開するのは網羅率の数値であり、コーパスそのものではない
        String csv = report().csv();

        assertTrue(csv.startsWith("group,total,compiled,rejected,crashed,rate\n"), csv);
        assertTrue(csv.contains("NC,2,1,1,0,50.0"), csv);
        assertTrue(csv.contains("ALL,4,1,2,1,25.0"), csv);
        assertFalse(csv.contains("A.cbl"), csv);
    }

    @Test
    @DisplayName("1 本も無ければ合格率は 0 である (NFR-042)")
    void anEmptyRunIsNotADivisionByZero() {
        assertEquals(0.0, new CorpusReport(List.of()).rate(), 0.001);
    }
}
