package dev.cobolonjava.verify.corpus;

/**
 * 診断の文面を、数えられる形へ揃える (要件 NFR-042)。
 *
 * <p>「{@code NC101A.cbl:327: unknown statement 'COMPUTE'}」と
 * 「{@code IF402A.cbl:88: unknown statement 'EVALUATE'}」は、数えるときには<b>同じ</b>
 * 「読めない文がある」でありたい。ところが位置と名前が混ざっているので、そのままでは
 * 別々の理由として 1 件ずつ並ぶ。500 本を流せば 500 通りの理由が出て、<b>何がいちばん
 * 詰まっているか</b>が見えなくなる。
 *
 * <p>そこで数字と引用符の中身を伏せてから数える。伏せるのは<b>数えるときだけ</b>で、
 * 元の文面は残してある。直す人が見るのはそちらだからである。
 */
final class Reasons {

    private Reasons() {
    }

    /** 位置と個別の名前を伏せる。 */
    static String normalized(String message) {
        if (message == null) {
            return "";
        }
        // ファイル名と行番号の頭書きを落とす。位置が分からないときの <unknown> も同じ
        String text = message;
        while (true) {
            int colon = text.indexOf(": ");
            if (colon <= 0) {
                break;
            }
            String head = text.substring(0, colon);
            if (!head.equals("<unknown>") && !head.matches("[^ ]*:\\d+(:\\d+)?")) {
                break;
            }
            text = text.substring(colon + 2);
        }
        return text.replaceAll("'[^']*'", "'…'")
                .replaceAll("\"[^\"]*\"", "\"…\"")
                .replaceAll("\\b\\d+\\b", "n")
                .strip();
    }
}
