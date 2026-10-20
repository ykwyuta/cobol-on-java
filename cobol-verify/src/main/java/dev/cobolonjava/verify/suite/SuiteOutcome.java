package dev.cobolonjava.verify.suite;

/**
 * 検査スイートの 1 本の結末 (設計 87)。
 *
 * @param module   モジュール (ZA、ZD など)
 * @param program  主プログラムの名前
 * @param expect   スイートが期待した結末
 * @param status   こちらの結末
 * @param reason   通らなかった理由。止まった原文の行を含む診断か、実行時の例外である。通れば空
 * @param expected 報告に出るはずの検査の数 (coverage.json)
 * @param passed   PASS と書かれた検査の数
 * @param failed   FAIL と書かれた検査の数
 * @param missing  一覧にあるのに報告に出なかった検査の数
 * @param zeusWording 断ったとき、こちらの診断が Zeus の診断と同じ語を含んでいたか。文面は処理系ごとに違うので
 *                 合否には使わない。参考に数えるだけである
 */
public record SuiteOutcome(String module, String program, SuiteManifest.Expect expect, Status status,
                           String reason, int expected, int passed, int failed, int missing,
                           boolean zeusWording) {

    /** 結末。 */
    public enum Status {
        /** 期待どおり (動かして全検査が通った、翻訳が通った、または翻訳が断った)。 */
        PASSED,
        /** 動いたが、落ちた検査か出なかった検査があるか、終了コードが違う。 */
        FAILED,
        /** 翻訳できなかった (断るべきでないものを断った)。 */
        NOT_COMPILED,
        /** 断るべきものを翻訳してしまった。 */
        ACCEPTED_WRONGLY,
        /** 翻訳系か実行時が例外で落ちた。報告が書けていない。 */
        CRASHED,
        /** 時間の限りまでに返らなかった。 */
        TIMED_OUT
    }

    public boolean succeeded() {
        return status == Status.PASSED;
    }

    static SuiteOutcome simple(String module, String program, SuiteManifest.Expect expect, Status status,
                               String reason, int expected) {
        return new SuiteOutcome(module, program, expect, status, reason, expected, 0, 0,
                status == Status.PASSED ? 0 : expected, false);
    }
}
