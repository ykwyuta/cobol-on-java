package dev.cobolonjava.verify.execute;

/**
 * 検査プログラム 1 本を<b>動かした</b>結果 (要件 NFR-040、暫定判断 P-062)。
 *
 * <p>翻訳が通ることと、正しく動くことは別である。CCVS85 の検査プログラムは
 * <b>自分で答え合わせをして印字する</b>ので、動かして読めば「規格どおりに動くか」まで
 * 測れる。要件 NFR-040 が言う合格率は本来こちらである。
 *
 * <p>結末を分けるのは、直す順が違うからである。
 *
 * <ul>
 *   <li>{@link Status#PASSED} — 全部の検査に通った
 *   <li>{@link Status#COMPILE_ONLY} — 翻訳の診断を見るための検査。動かさない
 *   <li>{@link Status#FAILED} — 動いたが、落ちた検査がある。<b>いま直すべき不具合</b>
 *   <li>{@link Status#NOT_COMPILED} — 翻訳が通らない。まだ書いていない機能である
 *   <li>{@link Status#CRASHED} — 例外が外へ出た、または報告を書かずに終わった
 *   <li>{@link Status#TIMED_OUT} — 返ってこなかった
 * </ul>
 *
 * @param executed  流れた検査の数
 * @param total     プログラムが持っている検査の数
 * @param failed    落ちた検査の数
 * @param deleted   流さなかった検査の数 ({@code DELETED})
 * @param inspected 人が見て判断する検査の数 ({@code INSPECTION})
 * @param failure   壊れたときの言い分。それ以外は {@code null}
 * @param failures  落ちた検査 1 件ずつ。機能ごとに数え上げるために持つ
 */
public record RunOutcome(String name, String group, Status status,
                         int executed, int total, int failed, int deleted, int inspected,
                         String failure, java.util.List<TestReport.Failure> failures) {

    public RunOutcome {
        failures = java.util.List.copyOf(failures);
    }

    /** 動かした結末。 */
    public enum Status {
        /** 全部の検査に通った。 */
        PASSED,
        /** 翻訳の診断を見るための検査。動かさない。 */
        COMPILE_ONLY,
        /** 動いたが、落ちた検査がある。 */
        FAILED,
        /** 翻訳が通らない。 */
        NOT_COMPILED,
        /** 例外が外へ出た、または報告を書かずに終わった。 */
        CRASHED,
        /** 返ってこなかった。 */
        TIMED_OUT
    }

    /**
     * 動かさない検査。
     *
     * <p>翻訳の診断を見るためのもので、報告を書く仕掛けを持っていない。
     * 動かして「報告が無い」と数えると、道具が処理系の失敗を作ることになる。
     */
    public static RunOutcome compileOnly(String name, String group) {
        return new RunOutcome(name, group, Status.COMPILE_ONLY, 0, 0, 0, 0, 0, null,
                java.util.List.of());
    }

    public static RunOutcome notCompiled(String name, String group, String reason) {
        return new RunOutcome(name, group, Status.NOT_COMPILED, 0, 0, 0, 0, 0, reason,
                java.util.List.of());
    }

    public static RunOutcome crashed(String name, String group, String failure) {
        return new RunOutcome(name, group, Status.CRASHED, 0, 0, 0, 0, 0, failure,
                java.util.List.of());
    }

    public static RunOutcome timedOut(String name, String group, long seconds) {
        return new RunOutcome(name, group, Status.TIMED_OUT, 0, 0, 0, 0, 0,
                "the program did not finish within " + seconds + " seconds",
                java.util.List.of());
    }

    /**
     * 報告を読み取れたときの結末。
     *
     * <p>落ちた検査が 1 つも無ければ通ったとする。{@code DELETED} と
     * {@code INSPECTION} は<b>落ちたことにしない</b>。前者は検査スイート自身が
     * 「この処理系では流さない」と決めたもの、後者は人が紙を見て判断するものである。
     */
    public static RunOutcome reported(String name, String group, int executed, int total,
                                      int failed, int deleted, int inspected,
                                      java.util.List<TestReport.Failure> failures) {
        return new RunOutcome(name, group, failed == 0 ? Status.PASSED : Status.FAILED,
                executed, total, failed, deleted, inspected, null, failures);
    }
}
