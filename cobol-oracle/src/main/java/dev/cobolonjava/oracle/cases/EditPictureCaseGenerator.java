package dev.cobolonjava.oracle.cases;

import java.util.ArrayList;
import java.util.List;

/**
 * 数字編集項目の PICTURE を機械的に生成する (要件 NFR-041、決定事項 D-15)。
 *
 * <p>PICTURE の<b>形</b>は pairwise で縮約し、各 PICTURE に対して値の境界は<b>全列挙</b>する。
 *
 * <table>
 *   <caption>PICTURE の形の因子と水準</caption>
 *   <tr><th>因子</th><th>水準</th></tr>
 *   <tr><td>抑制記号</td><td>{@code Z}、{@code *}</td></tr>
 *   <tr><td>抑制する整数桁数</td><td>1, 2, 4</td></tr>
 *   <tr><td>常に表示する整数桁数</td><td>1, 2</td></tr>
 *   <tr><td>小数桁数</td><td>0, 2, 3</td></tr>
 *   <tr><td>挿入文字</td><td>なし、{@code ,}、{@code /}</td></tr>
 *   <tr><td>末尾の符号表示</td><td>なし、{@code CR}、{@code DB}</td></tr>
 * </table>
 *
 * <p>常に表示する整数桁を 1 桁以上にしているのは、平の {@code ED} 命令で表現できる
 * PICTURE に限るためである ({@link dev.cobolonjava.oracle.machine.EditMask} 参照)。
 *
 * <p><b>{@code *} と {@code CR} / {@code DB} の組み合わせは除いている。</b>
 * {@code ED} は正数のとき {@code CR} / {@code DB} を<b>充填文字</b>で置き換えるため
 * {@code *} 充填では {@code "**"} になるが、COBOL の規則では<b>空白</b>になる。
 * 参照実装がこの差をどう埋めているかは未確認であり、provisional.md の P-016 に記録している。
 */
public final class EditPictureCaseGenerator {

    private static final char[] SUPPRESSION = {'Z', '*'};
    private static final int[] SUPPRESSED_DIGITS = {1, 2, 4};
    private static final int[] ALWAYS_PRINTED_DIGITS = {1, 2};
    private static final int[] DECIMAL_PLACES = {0, 2, 3};
    private static final char[] INSERTION = {0, ',', '/'};
    private static final String[] TRAILING = {"", "CR", "DB"};

    /**
     * 生成された 1 件のケース。
     *
     * @param picture PICTURE 文字列
     * @param value   値の分類
     */
    public record Case(String picture, EditValueClass value) {
        @Override
        public String toString() {
            return picture + " <- " + value;
        }
    }

    private EditPictureCaseGenerator() {
    }

    /** PICTURE の形を pairwise で生成する。 */
    public static List<String> pictures() {
        int[] levelCounts = {
                SUPPRESSION.length, SUPPRESSED_DIGITS.length, ALWAYS_PRINTED_DIGITS.length,
                DECIMAL_PLACES.length, INSERTION.length, TRAILING.length
        };
        List<String> out = new ArrayList<>();
        for (int[] row : PairwiseCovering.generate(levelCounts)) {
            char suppression = SUPPRESSION[row[0]];
            String trailing = TRAILING[row[5]];
            if (suppression == '*' && !trailing.isEmpty()) {
                // ED の充填文字による置き換えと COBOL の規則が食い違う組み合わせ (P-016)
                continue;
            }
            String picture = build(suppression, SUPPRESSED_DIGITS[row[1]],
                    ALWAYS_PRINTED_DIGITS[row[2]], DECIMAL_PLACES[row[3]],
                    INSERTION[row[4]], trailing);
            if (!out.contains(picture)) {
                out.add(picture);
            }
        }
        return out;
    }

    /** PICTURE の形 x 値の境界の全組み合わせ。 */
    public static List<Case> generate() {
        List<Case> cases = new ArrayList<>();
        for (String picture : pictures()) {
            for (EditValueClass value : EditValueClass.values()) {
                cases.add(new Case(picture, value));
            }
        }
        return cases;
    }

    private static String build(char suppression, int suppressed, int alwaysPrinted,
                                int decimals, char insertion, String trailing) {
        StringBuilder sb = new StringBuilder();
        if (insertion != 0 && suppressed >= 2) {
            // 抑制部を挿入文字で分ける。抑制領域の中にある挿入文字も抑制されることを確かめる
            int left = suppressed / 2;
            sb.append(String.valueOf(suppression).repeat(left));
            sb.append(insertion);
            sb.append(String.valueOf(suppression).repeat(suppressed - left));
        } else {
            sb.append(String.valueOf(suppression).repeat(suppressed));
        }
        sb.append("9".repeat(alwaysPrinted));
        if (decimals > 0) {
            sb.append('.').append("9".repeat(decimals));
        }
        sb.append(trailing);
        return sb.toString();
    }
}
