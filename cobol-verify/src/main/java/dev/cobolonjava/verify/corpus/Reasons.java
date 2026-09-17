package dev.cobolonjava.verify.corpus;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
public final class Reasons {


    private Reasons() {
    }

    /** 位置と個別の名前を伏せる。 */
    public static String normalized(String message) {
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
        return shortened(text.replaceAll("\\b\\d+\\b", "n")).strip();
    }

    /**
     * 引用符の中身を刈り込む。
     *
     * <p>引用符の中には<b>どの語で詰まったか</b>が入っている。これは残したい。
     * {@code no viable alternative at input '…'} だけでは、何を書けばよいのか分からない。
     *
     * <p>ところが構文解析の道具は、詰まった規則の<b>先頭から拾えた語をぜんぶ</b>並べる
     * ことがある。長い引用は 1 本ごとに違うものになり、数がばらける。
     *
     * <p>刈り込むのは<b>頭のほう</b>である。引用が長いのは「そこまで読めた」ためであり、
     * 知りたいのは<b>詰まった側の端</b>だからである。頭を残すと段落名から始まる何百字が
     * 並び、同じ原因が別々の理由として散る。実際、はじめは頭を残していて、
     * 段分けと {@code ALTER} と相対添字という<b>3 つの別の原因</b>が 1 件ずつの理由に
     * 散らばって見えていた。
     */
    private static String shortened(String text) {
        StringBuilder out = new StringBuilder();
        Matcher quoted = Pattern.compile("'([^']*)'|\"([^\"]*)\"").matcher(text);
        while (quoted.find()) {
            String inside = quoted.group(1) != null ? quoted.group(1) : quoted.group(2);
            char mark = quoted.group(1) != null ? '\'' : '"';
            String kept = inside.length() <= KEPT ? inside
                    : "…" + inside.substring(inside.length() - KEPT);
            quoted.appendReplacement(out, Matcher.quoteReplacement(mark + kept + mark));
        }
        quoted.appendTail(out);
        return out.toString();
    }

    /** 引用符の中を残す長さ。 */
    private static final int KEPT = 24;
}
