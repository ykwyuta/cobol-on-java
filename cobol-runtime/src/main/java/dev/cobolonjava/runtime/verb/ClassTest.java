package dev.cobolonjava.runtime.verb;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.item.NumericItem;

/**
 * 級条件 (要件 FR-046)。
 *
 * <p>{@code IF X IS NUMERIC} のように、項目の<b>中身が何でできているか</b>を問う。
 * 比べる相手は無く、バイトの並びだけを見る。
 *
 * <h2>数字かどうかは、項目の書き方で決まる</h2>
 * <p>符号を持つ数値項目では、符号の場所に符号として正しいものが入っているかまで見る。
 * 英数字項目に符号は無いので、<b>すべてのバイトが数字であること</b>だけを見る。
 * 判定の中身は、その項目を読むときの規則そのものである — 読めるなら数字であり、
 * 読めないなら数字ではない。別に書くと 2 つの規則がずれる。
 */
public final class ClassTest {

    private ClassTest() {
    }

    /** 英字の見方。 */
    public enum Letters {
        /** 大文字・小文字・空白。 */
        ANY,
        /** 小文字と空白。 */
        LOWER,
        /** 大文字と空白。 */
        UPPER
    }

    /**
     * 数値項目が数字か。
     *
     * <h2>読めることと、数字であることは違う</h2>
     * <p>ゾーン 10 進を<b>読む</b>ときは、符号でないバイトのゾーンニブルを見ない。
     * 機械がそう決まっているからで、EBCDIC の {@code "ABC"} (0xC1 0xC2 0xC3) は
     * {@code +123} として読める。しかし級条件は「中身が数字でできているか」を
     * 問うのであって、読めるかを問うのではない。<b>ゾーンまで見る</b>。
     *
     * <p>したがって読み出しをそのまま使うわけにはいかない。ここでは規格が言う
     * 「0〜9 の数字と、正しい作用符号だけからできているか」を見る。
     */
    public static boolean numeric(byte[] bytes, NumericItem item, CodePage codePage) {
        return switch (item.usage()) {
            case DISPLAY -> zoned(bytes, item, codePage);
            case COMP_3 -> packed(bytes);
            // 2 進項目はバイトの並びがそのまま数である。数でない形を取れない
            default -> true;
        };
    }

    /** ゾーン 10 進が数字か。符号の置き場によって見るところが変わる。 */
    private static boolean zoned(byte[] bytes, NumericItem item, CodePage codePage) {
        if (bytes.length == 0) {
            return false;
        }
        SignPosition sign = item.signPosition();
        int first = 0;
        int last = bytes.length - 1;
        if (sign.isSeparate()) {
            // 別に置いた符号は「+」か「-」の 1 文字である
            int at = sign.isLeading() ? first++ : last--;
            char written = codePage.decode(new byte[] {bytes[at]}).charAt(0);
            if (written != '+' && written != '-') {
                return false;
            }
        }
        int embedded = sign.isSigned() && !sign.isSeparate()
                ? (sign.isLeading() ? first : last)
                : -1;
        for (int i = first; i <= last; i++) {
            int zone = (bytes[i] >> 4) & 0x0F;
            int digit = bytes[i] & 0x0F;
            if (digit > 9) {
                return false;
            }
            if (i == embedded) {
                if (!validSign(zone)) {
                    return false;
                }
                continue;
            }
            // 符号でないところのゾーンは、そのコードページの数字のゾーンでなければならない
            if (zone != ((codePage.digit(0) >> 4) & 0x0F)) {
                return false;
            }
        }
        return true;
    }

    /** 詰め 10 進が数字か。最後のニブルが符号である。 */
    private static boolean packed(byte[] bytes) {
        if (bytes.length == 0) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            int high = (bytes[i] >> 4) & 0x0F;
            int low = bytes[i] & 0x0F;
            if (high > 9) {
                return false;
            }
            if (i == bytes.length - 1) {
                if (!validSign(low)) {
                    return false;
                }
            } else if (low > 9) {
                return false;
            }
        }
        return true;
    }

    /** 作用符号として通るニブル。機械が受け付ける形と同じである。 */
    private static boolean validSign(int nibble) {
        return nibble >= 0x0A;
    }

    /** 英数字項目が数字か。すべてのバイトが 0〜9 でなければならない。 */
    public static boolean digits(byte[] bytes, CodePage codePage) {
        byte zero = codePage.digit(0);
        byte nine = codePage.digit(9);
        for (byte value : bytes) {
            if ((value & 0xFF) < (zero & 0xFF) || (value & 0xFF) > (nine & 0xFF)) {
                return false;
            }
        }
        return bytes.length > 0;
    }

    /** 英字か。空白も英字として通る決まりである。 */
    public static boolean alphabetic(byte[] bytes, CodePage codePage, int letters) {
        Letters which = Letters.values()[letters];
        for (byte value : bytes) {
            if (value == codePage.space()) {
                continue;
            }
            char c = codePage.decode(new byte[] {value}).charAt(0);
            boolean ok = switch (which) {
                case ANY -> Character.isLetter(c);
                case LOWER -> Character.isLowerCase(c);
                case UPPER -> Character.isUpperCase(c);
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * 書いて決めた級 ({@code CLASS} 句) に入るか。
     *
     * @param allowed 級に入るバイトの並び。順は問わない
     */
    public static boolean member(byte[] bytes, byte[] allowed) {
        for (byte value : bytes) {
            boolean found = false;
            for (byte one : allowed) {
                if (one == value) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
