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
        return numeric(bytes, item, codePage, SIGNS_PRIMARY);
    }

    /** NUMPROC(NOPFD) か NUMPROC(MIG) と NUMCLS(PRIM): 符号つきは C・D・F。 */
    public static final int SIGNS_PRIMARY = 0;
    /** NUMCLS(ALT): 符号つきは A〜F。 */
    public static final int SIGNS_ALTERNATE = 1;
    /** NUMPROC(PFD): 符号つきは C・D と、値が 0 のときの F。 */
    public static final int SIGNS_PREFERRED = 2;

    /**
     * @param signs 符号の表。翻訳オプション NUMPROC と NUMCLS が決める
     *              ({@link #SIGNS_PRIMARY} ほか)
     */
    public static boolean numeric(byte[] bytes, NumericItem item, CodePage codePage,
                                  int signs) {
        return switch (item.usage()) {
            case DISPLAY -> zoned(bytes, item, codePage, signs);
            case COMP_3 -> packed(bytes, item.signPosition().isSigned(), signs);
            // 2 進項目はバイトの並びがそのまま数である。数でない形を取れない
            default -> true;
        };
    }

    /** ゾーン 10 進が数字か。符号の置き場によって見るところが変わる。 */
    private static boolean zoned(byte[] bytes, NumericItem item, CodePage codePage,
                                 int signs) {
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
                if (!validSign(zone, true, signs, zeroDigits(bytes, first, last, false))) {
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
    private static boolean packed(byte[] bytes, boolean signed, int signs) {
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
                if (!validSign(low, signed, signs,
                        zeroDigits(bytes, 0, bytes.length - 1, true))) {
                    return false;
                }
            } else if (low > 9) {
                return false;
            }
        }
        return true;
    }

    /**
     * 級条件で数字として通る符号のニブル。
     *
     * <p><b>機械が受け付ける形 (A〜F) とは違う</b>。Enterprise COBOL は NUMPROC(NOPFD) と
     * 導入時の既定 NUMCLS(PRIM) の下で、符号つきの項目には C・D・F だけを、符号なしの項目には
     * F だけを数字として通す (プログラミングの手引きの「NUMERIC 級条件で有効な符号」の表)。
     * 以前は A〜F をどれも通し、符号なしの詰め 10 進は符号を見ていなかったので、
     * {@code PIC 9(5) COMP-3} に X'12345C' が入っていても NUMERIC になっていた
     * (ZCVS の ZA106A で見つかった)。
     *
     * <p>NUMCLS(ALT) は符号つきの項目に A〜F を通す。NUMPROC(PFD) は優先符号だけを通し、
     * F は値が 0 のときだけ通す。符号なしの項目はどの表でも F だけである。この 2 つの表は
     * Zeus (同じ権利者の処理系) の {@code vm/eval.rs} と同じにした。実機と突き合わせていない。
     *
     * @param signed PICTURE に S があるか
     * @param signs  符号の表
     * @param zero   数字がすべて 0 か
     */
    private static boolean validSign(int nibble, boolean signed, int signs, boolean zero) {
        if (!signed) {
            return nibble == 0x0F;
        }
        return switch (signs) {
            case SIGNS_PREFERRED -> nibble == 0x0C || nibble == 0x0D || (nibble == 0x0F && zero);
            case SIGNS_ALTERNATE -> nibble >= 0x0A;
            default -> nibble == 0x0F || nibble == 0x0C || nibble == 0x0D;
        };
    }

    /** 数字のニブルがすべて 0 か。詰め 10 進は最後の下位ニブル (符号) を除く。 */
    private static boolean zeroDigits(byte[] bytes, int first, int last, boolean packed) {
        for (int i = first; i <= last; i++) {
            if ((bytes[i] & 0x0F) != 0 && !(packed && i == last)) {
                return false;
            }
            if (packed && (bytes[i] & 0xF0) != 0) {
                return false;
            }
        }
        return true;
    }

    /** 外部浮動小数点の項目が数字か。字の並びがその形をしていればよい。 */
    public static boolean externalFloating(byte[] bytes, CodePage codePage) {
        return dev.cobolonjava.runtime.picture.ExternalFloating.parse(
                codePage.decode(bytes), 0) != null;
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
