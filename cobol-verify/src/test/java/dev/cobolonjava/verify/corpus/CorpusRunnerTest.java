package dev.cobolonjava.verify.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.Origin;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 資産の束を翻訳にかけて数える (要件 NFR-042)。
 *
 * <p>処理系そのものではなく<b>数え方</b>を確かめる。だから翻訳の道は差し替えてある。
 * 処理系の不具合を当てにすると、不具合が直った日にこの試験が壊れる。
 */
@Tag("V1")
class CorpusRunnerTest {

    private static CobolCompiler.Result ok() {
        return new CobolCompiler.Result("A", new byte[] {1}, null, List.of());
    }

    private static CobolCompiler.Result refused(String message) {
        return new CobolCompiler.Result(null, null, null,
                List.of(new Diagnostic(null, message)));
    }

    private static CorpusRunner.Source source(String name) {
        return new CorpusRunner.Source(name, "NC", "000100 IDENTIFICATION DIVISION.");
    }

    @Test
    @DisplayName("通ったものは通ったと数える (NFR-042)")
    void whatCompilesIsCounted() {
        CorpusRunner runner = new CorpusRunner((name, text) -> ok());

        CompileOutcome outcome = runner.run(source("A.cbl"));

        assertEquals(CompileOutcome.Status.COMPILED, outcome.status());
    }

    @Test
    @DisplayName("断ったものと壊れたものを分けて数える (NFR-042)")
    void refusingAndCrashingAreDifferent() {
        // 断ったのは「まだ書いていない機能」の一覧であり、壊れたのは「いま直す不具合」で
        // ある。混ぜて数えると後者が前者に埋もれる
        CorpusRunner refusing = new CorpusRunner((name, text) -> refused("unknown statement"));
        CorpusRunner crashing = new CorpusRunner((name, text) -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(CompileOutcome.Status.REJECTED, refusing.run(source("A.cbl")).status());
        CompileOutcome crashed = crashing.run(source("A.cbl"));
        assertEquals(CompileOutcome.Status.CRASHED, crashed.status());
        assertEquals("IllegalStateException: boom", crashed.failure());
    }

    @Test
    @DisplayName("1 本が壊れても残りを流し続ける (NFR-042)")
    void oneCrashDoesNotStopTheBatch() {
        // 500 本流して 1 本目で止まったら、残りの 499 本について何も分からない
        CorpusRunner runner = new CorpusRunner((name, text) -> {
            if (name.equals("B.cbl")) {
                throw new IllegalStateException("boom");
            }
            return ok();
        });

        CorpusReport report = runner.run(List.of(source("A.cbl"), source("B.cbl"),
                source("C.cbl")));

        assertEquals(3, report.outcomes().size());
        assertEquals(2, report.compiled());
        assertEquals(1, report.crashed());
    }

    @Test
    @DisplayName("StackOverflow も壊れたものとして数える (NFR-042)")
    void aStackOverflowIsACrashToo() {
        // 深い入れ子は再帰下降の構文解析を落とす。資産にはそういうものが実際にある
        CorpusRunner runner = new CorpusRunner((name, text) -> {
            throw new StackOverflowError();
        });

        assertEquals(CompileOutcome.Status.CRASHED, runner.run(source("A.cbl")).status());
    }

    @Test
    @DisplayName("理由から位置を落としてから数える (NFR-042)")
    void reasonsAreCountedWithoutPositions() {
        // 位置が混ざったままだと 500 本で 500 通りの理由になり、
        // 何がいちばん詰まっているかが見えなくなる
        CorpusRunner runner = new CorpusRunner((name, text) ->
                refused(name + ":" + text.length() + ": unknown statement 'EVALUATE'"));

        CorpusReport report = runner.run(List.of(source("A.cbl"), source("B.cbl")));

        assertEquals(List.of(Map.entry("unknown statement 'EVALUATE'", 2L)), report.reasons(5));
    }

    @Test
    @DisplayName("詰まった語は残す (NFR-042)")
    void theWordThatStoppedItIsKept() {
        // 「読めない文がある」だけでは、次に何を書けばよいのか分からない
        CorpusRunner runner = new CorpusRunner((name, text) ->
                refused("unknown statement '" + (name.startsWith("A") ? "EVALUATE" : "SEARCH")
                        + "'"));

        CorpusReport report = runner.run(List.of(source("A.cbl"), source("B.cbl")));

        assertEquals(2, report.reasons(5).size());
    }

    @Test
    @DisplayName("長い引用は尻だけ残して刈り込む (NFR-042)")
    void aLongQuotationKeepsItsTail() {
        // 構文解析の道具は、詰まった規則の先頭から拾えた語をぜんぶ並べることがある。
        // 引用が長いのは「そこまで読めた」ためであり、知りたいのは詰まった側の端である。
        // 頭を残すと、同じところで詰まった 2 本が別々の理由になって散る
        CorpusRunner runner = new CorpusRunner((name, text) ->
                refused("no viable alternative at input '" + name
                        + ".OPEN-FILES.OPENOUTPUTPRINT-FILE.GOTO50'"));

        CorpusReport report = runner.run(List.of(source("A.cbl"), source("B.cbl")));

        assertEquals(List.of(Map.entry("no viable alternative at input "
                + "'…NOUTPUTPRINT-FILE.GOTO50'", 2L)), report.reasons(5));
    }

    @Test
    @DisplayName("診断の位置は元のまま残す (NFR-042)")
    void theOriginalDiagnosticIsKept() {
        // 伏せるのは数えるときだけである。直す人が見るのは元の文面のほうである
        CorpusRunner runner = new CorpusRunner((name, text) ->
                new CobolCompiler.Result(null, null, null,
                        List.of(new Diagnostic(new Origin("A.cbl", 12, 7), "unknown statement"))));

        CompileOutcome outcome = runner.run(source("A.cbl"));

        assertTrue(outcome.diagnostics().get(0).contains("12"), outcome.diagnostics().toString());
    }
}
