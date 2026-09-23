package dev.cobolonjava.pli;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 算術に効く翻訳時オプション (Enterprise PL/I Programming Guide, "RULES" と "LIMITS")。
 *
 * <p>{@code *PROCESS} は前処理で原文から外すが、生成クラスが実行時に使う原文へ
 * {@code /*PLI-OPTIONS ...*}{@code /} の注記として残し、ここで読み直す。翻訳と実行で
 * 同じ規則を使うためである。
 *
 * @param ans        {@code RULES(ANS)} なら真。既定は {@code RULES(IBM)}
 * @param decimalLow {@code FIXEDDEC(n,m)} の n。式に m を超えない精度の被演算子しかなければ n が上限
 * @param decimalHigh {@code FIXEDDEC(n,m)} の m。式に n を超える精度の被演算子があれば m が上限
 * @param binaryLow  {@code FIXEDBIN(n,m)} の n
 * @param binaryHigh {@code FIXEDBIN(n,m)} の m
 */
record PliOptions(boolean ans, int decimalLow, int decimalHigh, int binaryLow, int binaryHigh) {

    /** 既定: RULES(IBM)、LIMITS(FIXEDDEC(15,31) FIXEDBIN(31,63))。 */
    static final PliOptions DEFAULT = new PliOptions(false, 15, 31, 31, 63);

    static final String MARKER = "/*PLI-OPTIONS ";

    private static final Pattern NOTE = Pattern.compile("/\\*PLI-OPTIONS (.*?)\\*/",
            Pattern.DOTALL);
    private static final Pattern RULES = Pattern.compile("\\bRULES\\s*\\(([^)]*)\\)");
    private static final Pattern FIXEDDEC = Pattern.compile(
            "\\bFIXEDDEC\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)");
    private static final Pattern FIXEDBIN = Pattern.compile(
            "\\bFIXEDBIN\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)");

    /** 実行時の原文に残した注記から読む。無ければ既定。 */
    static PliOptions fromSource(String source) {
        Matcher note = NOTE.matcher(source);
        PliOptions options = DEFAULT;
        while (note.find()) {
            options = options.with(note.group(1));
        }
        return options;
    }

    /**
     * {@code *PROCESS} の指定を重ねる。あとに書いたほうが勝つ。
     *
     * @throws IllegalArgumentException 規格が許さない組み合わせ ({@code FIXEDDEC(31,15)} など)
     */
    PliOptions with(String process) {
        String text = process.toUpperCase(Locale.ROOT);
        boolean rulesAns = ans;
        Matcher rules = RULES.matcher(text);
        while (rules.find()) {
            for (String suboption : rules.group(1).split("[\\s,]+")) {
                if (suboption.equals("ANS")) rulesAns = true;
                if (suboption.equals("IBM")) rulesAns = false;
            }
        }
        int[] decimal = limits(FIXEDDEC.matcher(text), decimalLow, decimalHigh, 15, 31, "FIXEDDEC");
        int[] binary = limits(FIXEDBIN.matcher(text), binaryLow, binaryHigh, 31, 63, "FIXEDBIN");
        return new PliOptions(rulesAns, decimal[0], decimal[1], binary[0], binary[1]);
    }

    private static int[] limits(Matcher matcher, int low, int high, int small, int large,
                                String name) {
        int[] result = {low, high};
        while (matcher.find()) {
            int first = Integer.parseInt(matcher.group(1));
            int second = matcher.group(2) == null ? first : Integer.parseInt(matcher.group(2));
            if ((first != small && first != large) || (second != small && second != large)
                    || first > second) {
                // FIXEDDEC(31,15) のように、上の限りが下の限りより小さい指定は許されない
                throw new IllegalArgumentException(name + "(" + first + "," + second
                        + ") is not a valid LIMITS suboption");
            }
            result[0] = first;
            result[1] = second;
        }
        return result;
    }

    /** 実行時の原文に残す注記。 */
    static String note(String process) {
        return MARKER + process.replace("*/", "* /") + "*/";
    }
}
