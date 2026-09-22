package dev.cobolonjava.cics.bms;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.util.Objects;

/**
 * field の長さを 3270 の画面位置 (cell) で数える (設計 79 §8.6)。
 *
 * <p>BMS の {@code LENGTH} は文字数ではなく画面 buffer の位置の数である。SBCS は 1 文字が
 * 1 位置だが、DBCS は 1 文字が 2 位置を占め、混在 field では前後のシフトアウト (X'0E') と
 * シフトイン (X'0F') も 1 位置ずつ占める。混在コードページの符号化はこの規則どおりに byte を
 * 置くので、<b>符号化した byte 数がそのまま cell 数になる</b>。
 *
 * <p>IBM-930 で測った値:
 *
 * <pre>
 * "AB"     -&gt; X'C1 C2'                        2 byte = 2 cell
 * "山田"   -&gt; X'0E 4565 4563 0F'              6 byte = 6 cell
 * "A山B"   -&gt; X'C1 0E 4565 0F C2'             6 byte = 6 cell
 * "ｱｲ"     -&gt; X'81 82'                        2 byte = 2 cell (半角カタカナは SBCS)
 * </pre>
 *
 * <p>文字数で数えると DBCS は 1 文字も入らない。以前はここを {@code String.length()} で
 * 数えており、RECEIVE MAP は「1 文字が 1 byte にならない」と断っていた。
 *
 * <p><b>実機と突き合わせていない</b>: cell 数と byte 数が一致するという上の規則は、JDK の
 * 混在コードページの符号化を測って確かめたものであり、3270 のデータストリームを採って
 * 照合したものではない (暫定判断 P-119)。
 */
public final class BmsFieldText {

    private BmsFieldText() {
    }

    /**
     * この文字列が占める cell 数。
     *
     * @throws dev.cobolonjava.runtime.codepage.UnrepresentableCharacterException
     *         コードページで表せない文字があった場合
     */
    public static int cells(String text, CodePage codePage) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(codePage, "codePage");
        return codePage.encode(text).length;
    }

    /**
     * 右側を {@code pad} で埋めて、ちょうど {@code cells} 個の cell を占める文字列にする。
     *
     * <p>埋める数は必ず {@link #cells} から出す。cell 数は文字列の連結について加法的ではない
     * (「山」4 cell + 「田」4 cell に対して「山田」は 6 cell。シフト符号が 1 組で済むため) ので、
     * 部品ごとに数えて足すと桁がずれる。埋め文字は SBCS であり、1 文字が確かに 1 cell になる。
     */
    public static String fill(String text, int cells, char pad, CodePage codePage) {
        int width = cells(text, codePage);
        if (width > cells) {
            throw new IllegalArgumentException(
                    "text occupies " + width + " screen positions but only " + cells + " are available");
        }
        return text + String.valueOf(requireSingleCell(pad, codePage)).repeat(cells - width);
    }

    /** 左側を埋めて、ちょうど {@code cells} 個の cell を占める文字列にする (JUSTIFY=RIGHT)。 */
    public static String fillLeft(String text, int cells, char pad, CodePage codePage) {
        int width = cells(text, codePage);
        if (width > cells) {
            throw new IllegalArgumentException(
                    "text occupies " + width + " screen positions but only " + cells + " are available");
        }
        return String.valueOf(requireSingleCell(pad, codePage)).repeat(cells - width) + text;
    }

    /**
     * {@code cells} 個の cell に収まるところまで切る。
     *
     * <p>切るのは<b>符号位置の単位</b>である。byte の途中で切ると DBCS の 1 文字が半分になり、
     * 復号できない byte 列が画面に残る。切った結果が短くなったぶんは呼び出し側が埋める。
     */
    public static String truncate(String text, int cells, CodePage codePage) {
        if (cells(text, codePage) <= cells) {
            return text;
        }
        int end = text.length();
        while (end > 0) {
            end -= Character.charCount(text.codePointBefore(end));
            String candidate = text.substring(0, end);
            if (cells(candidate, codePage) <= cells) {
                return candidate;
            }
        }
        return "";
    }

    private static char requireSingleCell(char pad, CodePage codePage) {
        if (codePage.encode(String.valueOf(pad)).length != 1) {
            throw new IllegalArgumentException("padding character '" + pad + "' is not a single screen position");
        }
        return pad;
    }
}
