package dev.cobolonjava.verify.execute;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CCVS85 の検査プログラムが印字した報告を読む (暫定判断 P-062)。
 *
 * <p>検査プログラムは最後に自分の成績をまとめて印字する。
 *
 * <pre>
 *                     END OF TEST-  NC101A
 *
 *                     093 OF 093  TESTS WERE EXECUTED SUCCESSFULLY
 *                     NO  TEST(S) FAILED
 *                     NO  TEST(S) DELETED
 *                     NO  TEST(S) REQUIRE INSPECTION
 * </pre>
 *
 * <p>数える相手は<b>プログラム自身が書いたこの 4 行</b>である。1 行ずつの
 * {@code PASS} / {@code FAIL} を数えても近い値は出るが、検査スイートが自分で
 * 決めた数え方に従うほうが正しい。行を数えると、見出しの
 * 「{@code FEATURE PASS PARAGRAPH-NAME}」まで数えてしまう。
 *
 * <p>{@code END OF TEST-} が無ければ、プログラムは<b>最後まで行かなかった</b>。
 * 途中で止まったものを「落ちた検査が 0 だから合格」と数えてはならない。
 */
public final class TestReport {

    /** 「093 OF 093  TESTS WERE EXECUTED SUCCESSFULLY」。 */
    private static final Pattern EXECUTED =
            Pattern.compile("(\\d+) OF (\\d+)\\s+TESTS WERE EXECUTED SUCCESSFULLY");
    /** 「NO  TEST(S) FAILED」または「003 TEST(S) FAILED」。 */
    private static final Pattern FAILED = counted("FAILED");
    private static final Pattern DELETED = counted("DELETED");
    private static final Pattern INSPECTION = counted("REQUIRE INSPECTION");
    /** 最後まで行った印。 */
    private static final Pattern END_OF_TEST = Pattern.compile("END OF TEST-");

    private static Pattern counted(String what) {
        return Pattern.compile("(NO|\\d+)\\s+TEST\\(S\\) " + what);
    }

    private TestReport() {
    }

    /** 報告が最後まで書かれているか。 */
    public static boolean isComplete(String text) {
        return END_OF_TEST.matcher(text).find();
    }

    /**
     * そのソースは<b>自分で答え合わせをする</b>検査か。
     *
     * <p>CCVS85 には、動かして合否を見る検査のほかに<b>翻訳の診断を見るための検査</b>が
     * ある ({@code NC302M} や {@code RW301M} のような {@code M} で終わる名前のもの)。
     * 「Message expected for above statement」と書いてあり、<b>紙を人が見る</b>ものである。
     * 報告を書く仕掛けを持っていないので、動かして「報告が無い」と数えると、
     * 道具が処理系の失敗を作ることになる。
     *
     * <p>見分けるのは<b>報告を書く文面をソースが持っているか</b>である。名前の形で
     * 決めない。名前の付け方は配布物の都合であって、中身の性質ではない。
     */
    public static boolean isSelfChecking(String source) {
        return END_OF_TEST.matcher(source).find();
    }

    /** 流れた検査の数。読み取れなければ空。 */
    public static OptionalInt executed(String text) {
        Matcher matcher = EXECUTED.matcher(text);
        return matcher.find() ? OptionalInt.of(Integer.parseInt(matcher.group(1)))
                : OptionalInt.empty();
    }

    /** プログラムが持っている検査の数。読み取れなければ空。 */
    public static OptionalInt total(String text) {
        Matcher matcher = EXECUTED.matcher(text);
        return matcher.find() ? OptionalInt.of(Integer.parseInt(matcher.group(2)))
                : OptionalInt.empty();
    }

    public static int failed(String text) {
        return count(FAILED, text);
    }

    public static int deleted(String text) {
        return count(DELETED, text);
    }

    public static int inspected(String text) {
        return count(INSPECTION, text);
    }

    /** 「NO」は 0 である。書かれていなければ 0 とする。 */
    private static int count(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return 0;
        }
        String value = matcher.group(1);
        return value.equals("NO") ? 0 : Integer.parseInt(value);
    }
}
