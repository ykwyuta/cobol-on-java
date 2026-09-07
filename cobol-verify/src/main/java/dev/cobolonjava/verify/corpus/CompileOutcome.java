package dev.cobolonjava.verify.corpus;

import java.util.List;

/**
 * 1 本を翻訳にかけた結果 (要件 NFR-042)。
 *
 * <p>大事なのは<b>断ったのか、壊れたのか</b>の区別である。
 *
 * <ul>
 *   <li>{@link Status#COMPILED} — 通った
 *   <li>{@link Status#REJECTED} — <b>読めないと言って断った</b>。未対応の構文であり、
 *       診断が「どこが読めなかったか」を持っている。これは処理系の正しい振る舞いである
 *   <li>{@link Status#CRASHED} — <b>言わずに壊れた</b>。例外が処理系の外へ出た。
 *       診断を出す道を通っていないのだから、これは処理系の欠陥である
 * </ul>
 *
 * <p>合格率だけを数えるなら 2 つを分ける必要はない。分けるのは、直す順が違うからである。
 * 断ったものは<b>まだ書いていない機能</b>の一覧であり、壊れたものは<b>いま直すべき不具合</b>
 * である。混ぜて数えると、後者が前者に埋もれる。
 *
 * @param diagnostics 断ったときの言い分。通ったときは空
 * @param failure 壊れたときの例外の名前と文面。それ以外は {@code null}
 */
public record CompileOutcome(String name, String group, Status status,
                             List<String> diagnostics, String failure) {

    /** 翻訳にかけた結末。 */
    public enum Status {
        /** 通った。 */
        COMPILED,
        /** 読めないと言って断った。 */
        REJECTED,
        /** 言わずに壊れた。 */
        CRASHED
    }

    /** 通った。 */
    public static CompileOutcome compiled(String name, String group) {
        return new CompileOutcome(name, group, Status.COMPILED, List.of(), null);
    }

    /** 断った。 */
    public static CompileOutcome rejected(String name, String group, List<String> diagnostics) {
        return new CompileOutcome(name, group, Status.REJECTED, List.copyOf(diagnostics), null);
    }

    /** 壊れた。 */
    public static CompileOutcome crashed(String name, String group, Throwable thrown) {
        String failure = thrown.getClass().getSimpleName()
                + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage());
        return new CompileOutcome(name, group, Status.CRASHED, List.of(), failure);
    }

    /**
     * 数えるときの理由。
     *
     * <p>診断の文面には位置や名前が混ざる。そのままでは 1 件ずつ違う理由になってしまい、
     * <b>何がいちばん詰まっているか</b>が見えない。位置と引用符の中身を落として揃える。
     */
    public String reason() {
        return switch (status) {
            case COMPILED -> "";
            case CRASHED -> Reasons.normalized(failure);
            case REJECTED -> diagnostics.isEmpty() ? "(no diagnostic)"
                    : Reasons.normalized(diagnostics.get(0));
        };
    }
}
