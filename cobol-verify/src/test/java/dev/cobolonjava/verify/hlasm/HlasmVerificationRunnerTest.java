package dev.cobolonjava.verify.hlasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 測る道具そのものを測る。
 *
 * <p>CLAUDE.md §6 は「検査の道具が、処理系の失敗を作ってはならない」と言う。HLASM では
 * その裏返しも要る。<b>道具が処理系の成功を作ってはならない。</b>誤った機械語を
 * 参照実装も自分も同じように実行すれば結果は一致するので、組み立てを先に確かめずに
 * 実行だけを数えると、測定が緑のまま嘘をつく (設計 27 §3)。
 */
class HlasmVerificationRunnerTest {

    private static final String ADD_ONE = String.join("\n",
            "TEST     CSECT",
            "         USING TEST,15",
            "         L     2,0(0,1)",
            "         AP    0(2,2),ONE",
            "         SR    15,15",
            "         BR    14",
            "ONE      DC    PL1'1'",
            "         END");

    private static HlasmCaseOutcome run(HlasmVerificationRunner.Source source) {
        return HlasmVerificationRunner.standard().run(source);
    }

    private static HlasmVerificationRunner.Source source(String text, String obj, String in,
                                                         String out) {
        return new HlasmVerificationRunner.Source("case.asm", "decimal", text, obj, in, out);
    }

    @Test
    @DisplayName("期待値が無ければ、組み立てが通ったことだけを数える")
    void countsAssemblyOnlyWithoutExpectations() {
        assertEquals(HlasmCaseOutcome.Status.ASSEMBLED,
                run(source(ADD_ONE, null, null, null)).status());
    }

    @Test
    @DisplayName("組み立てを断れば REJECTED であり、診断を残す")
    void recordsRejectionWithItsDiagnostic() {
        HlasmCaseOutcome outcome = run(source(String.join("\n",
                "TEST     CSECT",
                "         WTO   'HELLO'",
                "         END"), null, null, null));
        assertEquals(HlasmCaseOutcome.Status.REJECTED, outcome.status());
        assertTrue(outcome.reason().contains("macros are not supported yet"), outcome.reason());
    }

    @Test
    @DisplayName("機械語が期待値と一致すれば実行へ進む")
    void proceedsToExecutionWhenTheObjectCodeMatches() {
        // 組み立て表から採った期待値。L / AP / SR / BR / DC の 15 バイト
        String object = "58201000 FA102000F00E 1BFF 07FE 1C";
        HlasmCaseOutcome outcome = run(source(ADD_ONE, object, "123C", expectedAfterAddingOne()));
        assertEquals(HlasmCaseOutcome.Status.PASSED, outcome.status());
    }

    /**
     * 機械語が期待値と違えば<b>実行しない</b>。誤った機械語を動かした結果を
     * 「実行の不一致」として数えると、組み立てと実行のどちらが悪いのか分からなくなる。
     */
    @Test
    @DisplayName("機械語が違えば WRONG_OBJECT で止め、実行まで進まない")
    void stopsAtWrongObjectCodeWithoutExecuting() {
        HlasmCaseOutcome outcome = run(source(ADD_ONE, "00000000", "123C",
                expectedAfterAddingOne()));
        assertEquals(HlasmCaseOutcome.Status.WRONG_OBJECT, outcome.status());
        assertTrue(outcome.reason().contains("object code differs"), outcome.reason());
    }

    @Test
    @DisplayName("実行の結果が違えば WRONG_OUTPUT である")
    void reportsWrongOutput() {
        HlasmCaseOutcome outcome = run(source(ADD_ONE, null, "123C", "RC=00000000\n"));
        assertEquals(HlasmCaseOutcome.Status.WRONG_OUTPUT, outcome.status());
    }

    /** 異常終了も観測できる結果である。壊れたことにはしない。 */
    @Test
    @DisplayName("異常終了は結果として数え、破損とは区別する")
    void recordsAbendAsAnObservableResult() {
        HlasmCaseOutcome outcome = run(source(ADD_ONE, null, "ABCD", "ABEND S0C7\n"));
        assertEquals(HlasmCaseOutcome.Status.PASSED, outcome.status());
    }

    /**
     * 1 本が返ってこなくても残りを測り続ける。処理系の失敗が測定を止めてはならない。
     *
     * <p>限りは 2 つある。{@code Cpu} の命令数と、この実行器の時間である。どちらが先に
     * 当たるかは機械の速さで変わるので、文面では決めない。<b>大事なのは、どちらであっても
     * {@code WRONG_OUTPUT} ではなく {@code CRASHED} になること</b>である。暴走した 1 本を
     * 「結果が期待と違った」に数えると、実装の欠陥が未対応の機能に混ざる。
     */
    @Test
    @DisplayName("返ってこない 1 本は見捨て、結果の不一致ではなく破損として数える")
    void abandonsACaseThatNeverFinishes() {
        HlasmVerificationRunner runner = new HlasmVerificationRunner(1);
        HlasmCaseOutcome outcome = runner.run(new HlasmVerificationRunner.Source(
                "loop.asm", "control", String.join("\n",
                        "TEST     CSECT",
                        "         USING TEST,15",
                        "LOOP     B     LOOP",
                        "         END"),
                null, null, "RC=00000000\n"));
        assertEquals(HlasmCaseOutcome.Status.CRASHED, outcome.status());
    }

    @Test
    @DisplayName("報告は組み立てと実行を別々に数える")
    void countsAssemblyAndExecutionSeparately() {
        HlasmVerificationReport report = new HlasmVerificationReport(List.of(
                run(source(ADD_ONE, null, null, null)),
                run(source(ADD_ONE, "00000000", "123C", expectedAfterAddingOne())),
                run(source(ADD_ONE, null, "123C", expectedAfterAddingOne())),
                run(source(ADD_ONE, null, "123C", "RC=00000000\n"))));

        // 4 本すべて組み立てには通った
        assertEquals(4, report.assembled());
        // 実行まで届いたのは 2 本。機械語が違った 1 本は実行していない
        assertEquals(2, report.executionCases());
        assertEquals(1, report.count(HlasmCaseOutcome.Status.WRONG_OBJECT));
        assertEquals(1, report.count(HlasmCaseOutcome.Status.PASSED));
        assertEquals(50.0, report.executionRate(), 0.01);
        assertTrue(report.csv().contains("wrong_object"));

        // 機械語の期待値を持っていたのは 1 本だけ。持っていない 3 本を
        // 「一致した」に数えると、組み立ての一致率が実際より高く出る
        assertEquals(1, report.objectCases());
        assertEquals(0, report.objectMatched());
        assertEquals(0.0, report.objectRate(), 0.01);
    }

    /**
     * 比べた本が 1 本も無いときに「0.0%」と書くと、全部外したように見える。
     * 実際には基準がまだ無いだけである。数が嘘をつかないようにする。
     */
    @Test
    @DisplayName("機械語の期待値が 1 つも無ければ、率ではなく「測っていない」と書く")
    void saysNotMeasuredInsteadOfZeroPercent() {
        HlasmVerificationReport report = new HlasmVerificationReport(List.of(
                run(source(ADD_ONE, null, null, null))));
        assertEquals(0, report.objectCases());
        assertTrue(report.text("HLASM").contains("機械語一致 測っていない"),
                report.text("HLASM"));
    }

    /** 作業域は 256 バイト。先頭にパック 10 進の 123 を置き、1 を足すと 124 になる。 */
    private static String expectedAfterAddingOne() {
        StringBuilder out = new StringBuilder("RC=00000000\n");
        for (int at = 0; at < HlasmVerificationRunner.WORK_AREA_BYTES; at += 16) {
            out.append(String.format("%04X  %s%n", at,
                    at == 0 ? "124C" + "0".repeat(28) : "0".repeat(32)));
        }
        return out.toString();
    }
}
