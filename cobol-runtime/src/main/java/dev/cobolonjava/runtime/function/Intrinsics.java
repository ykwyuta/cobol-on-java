package dev.cobolonjava.runtime.function;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.decimal.CobolRounding;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.math.BigInteger;

/**
 * 組み込み関数のうち、<b>値が一意に決まる</b>もの (要件 FR-070)。
 *
 * <p>ここに置いてあるのは、規格の定義から答えが 1 つに決まる関数である。整数の切り方、
 * 文字の並べ替え、照合順序の位置、10 進の四則。どれも<b>近似が入らない</b>。
 *
 * <p>三角関数や対数のように<b>結果の桁数が処理系の決めごとになる</b>ものはここに無い。
 * 要件 FR-071 が「参照実装の仕様を優先し、Java の {@code Math} の結果をそのまま用いない」
 * としており、その仕様をまだ持っていないからである (暫定判断 P-065)。
 *
 * <p>引数はすでに {@link Decimal} かバイト列へ落ちている。項目の読み方は呼ぶ側の仕事で
 * あり、ここは<b>値の関数</b>だけを持つ。
 */
public final class Intrinsics {

    private static final Decimal ONE = Decimal.of(1, 0);

    /** 階乗の引数の上限。これを超えると桁数が実用の範囲を出る。 */
    private static final int FACTORIAL_LIMIT = 1000;

    private Intrinsics() {
    }

    // ---- 数値 ----

    /** {@code FUNCTION MAX}。 */
    public static Decimal max(Decimal[] values) {
        Decimal best = values[0];
        for (Decimal value : values) {
            if (value.compareTo(best) > 0) {
                best = value;
            }
        }
        return best;
    }

    /** {@code FUNCTION MIN}。 */
    public static Decimal min(Decimal[] values) {
        Decimal best = values[0];
        for (Decimal value : values) {
            if (value.compareTo(best) < 0) {
                best = value;
            }
        }
        return best;
    }

    /** {@code FUNCTION SUM}。 */
    public static Decimal sum(Decimal[] values) {
        Decimal total = Decimal.zero(0);
        for (Decimal value : values) {
            total = total.add(value);
        }
        return total;
    }

    /**
     * {@code FUNCTION ORD-MAX}。<b>何番目の引数が最大か</b>を返す。
     * 同じ値が並んでいれば先に書いたほうを採る。
     */
    public static Decimal ordMax(Decimal[] values) {
        int found = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i].compareTo(values[found]) > 0) {
                found = i;
            }
        }
        return Decimal.of(found + 1L, 0);
    }

    /** {@code FUNCTION ORD-MIN}。 */
    public static Decimal ordMin(Decimal[] values) {
        int found = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i].compareTo(values[found]) < 0) {
                found = i;
            }
        }
        return Decimal.of(found + 1L, 0);
    }

    /** {@code FUNCTION RANGE}。最大と最小の差である。 */
    public static Decimal range(Decimal[] values) {
        return max(values).subtract(min(values));
    }

    /**
     * {@code FUNCTION INTEGER}。<b>引数を超えない最大の整数</b>である。
     * 負の側では切り捨てと違う。{@code INTEGER(-1.5)} は {@code -2} になる。
     */
    public static Decimal integer(Decimal value) {
        return value.rescale(0, CobolRounding.TOWARD_LESSER);
    }

    /** {@code FUNCTION INTEGER-PART}。<b>0 の側へ</b>切り捨てる。 */
    public static Decimal integerPart(Decimal value) {
        return value.rescale(0, CobolRounding.TRUNCATION);
    }

    /**
     * {@code FUNCTION MOD}。{@code a - b * INTEGER(a / b)} である。
     *
     * <p>{@code REM} との違いは<b>商の丸め方</b>であり、結果の符号は<b>除数に従う</b>。
     * {@code MOD(-11, 5)} は {@code 4} だが {@code REM(-11, 5)} は {@code -1} である。
     */
    public static Decimal mod(Decimal dividend, Decimal divisor) {
        Decimal quotient = integer(exactQuotient(dividend, divisor));
        return dividend.subtract(divisor.multiply(quotient));
    }

    /** {@code FUNCTION REM}。{@code a - b * INTEGER-PART(a / b)} である。 */
    public static Decimal rem(Decimal dividend, Decimal divisor) {
        Decimal quotient = integerPart(exactQuotient(dividend, divisor));
        return dividend.subtract(divisor.multiply(quotient));
    }

    /**
     * 商を、整数部を決めるのに足りる桁で求める。
     *
     * <p>ここで丸めてはならない。{@code INTEGER} と {@code INTEGER-PART} が見るのは
     * <b>整数部と、0 でない小数部があるかどうか</b>だけであり、丸めた商から求めると
     * 境目で 1 ずれる。小数 1 桁あれば「割り切れたか」は判る。
     */
    private static Decimal exactQuotient(Decimal dividend, Decimal divisor) {
        return dividend.divide(divisor, 1, CobolRounding.TRUNCATION);
    }

    /** {@code FUNCTION FACTORIAL}。引数は 0 以上の整数である。 */
    public static Decimal factorial(Decimal value) {
        int n = toIndex(value, "FACTORIAL");
        if (n > FACTORIAL_LIMIT) {
            throw new IllegalArgumentException("FUNCTION FACTORIAL argument is too large: " + n);
        }
        BigInteger result = BigInteger.ONE;
        for (int i = 2; i <= n; i++) {
            result = result.multiply(BigInteger.valueOf(i));
        }
        return Decimal.of(result, 0);
    }

    // ---- 文字 ----

    /**
     * {@code FUNCTION CHAR}。照合順序の<b>何番目か</b>から文字を得る。1 から数える。
     *
     * <p>照合順序はコードページのバイト値そのものである (暫定判断 P-042)。
     */
    public static byte[] charOf(Decimal ordinal, CodePage codePage) {
        int position = toIndex(ordinal, "CHAR");
        if (position < 1 || position > 256) {
            throw new IllegalArgumentException(
                    "FUNCTION CHAR argument is outside 1..256: " + position);
        }
        return new byte[] { (byte) (position - 1) };
    }

    /** {@code FUNCTION ORD}。{@link #charOf} の逆である。 */
    public static Decimal ord(byte[] value, CodePage codePage) {
        if (value.length == 0) {
            throw new IllegalArgumentException("FUNCTION ORD requires one character");
        }
        return Decimal.of((value[0] & 0xFF) + 1L, 0);
    }

    /** {@code FUNCTION UPPER-CASE}。英小文字だけを大文字にする。長さは変わらない。 */
    public static byte[] upperCase(byte[] value, CodePage codePage) {
        return mapLetters(value, codePage, true);
    }

    /** {@code FUNCTION LOWER-CASE}。 */
    public static byte[] lowerCase(byte[] value, CodePage codePage) {
        return mapLetters(value, codePage, false);
    }

    /**
     * 英字だけを写す。<b>26 文字だけ</b>を対象にするのは、規格がそう決めているからである。
     * コードページを通すのは、EBCDIC の英字が連続していないためである。
     */
    private static byte[] mapLetters(byte[] value, CodePage codePage, boolean upper) {
        byte[] result = new byte[value.length];
        for (int i = 0; i < value.length; i++) {
            char c = codePage.decode(new byte[] { value[i] }).charAt(0);
            char mapped = upper
                    ? (c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c)
                    : (c >= 'A' && c <= 'Z' ? (char) (c - 'A' + 'a') : c);
            result[i] = mapped == c ? value[i] : codePage.ch(mapped);
        }
        return result;
    }

    /** {@code FUNCTION REVERSE}。バイトの並びを逆にする。 */
    public static byte[] reverse(byte[] value) {
        byte[] result = new byte[value.length];
        for (int i = 0; i < value.length; i++) {
            result[i] = value[value.length - 1 - i];
        }
        return result;
    }

    /**
     * {@code FUNCTION NUMVAL}。数字の綴りを数値として読む。
     *
     * <p>読めるのは<b>前後の空白、先頭か末尾の符号、数字、小数点</b>である。
     * 末尾の符号は {@code CR} と {@code DB} でも書ける。規格の外の綴りが来たときの
     * 振る舞いは決まっていないので、ここでは<b>読めた分だけ</b>を値にする。
     */
    public static Decimal numval(byte[] value, CodePage codePage) {
        return parse(codePage.decode(value), false);
    }

    /**
     * {@code FUNCTION NUMVAL-C}。通貨記号と桁区切りのコンマを落として読む。
     *
     * @param currency 落とす通貨記号。空なら記号を読み飛ばすだけである
     */
    public static Decimal numvalC(byte[] value, byte[] currency, CodePage codePage) {
        String text = codePage.decode(value);
        String symbol = codePage.decode(currency).trim();
        if (!symbol.isEmpty()) {
            text = text.replace(symbol, "");
        }
        return parse(text, true);
    }

    /**
     * 数字の綴りを読む。
     *
     * @param loose 通貨記号と桁区切りを読み飛ばすか ({@code NUMVAL-C})
     */
    private static Decimal parse(String text, boolean loose) {
        StringBuilder digits = new StringBuilder();
        int sign = 1;
        boolean seenDigit = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
                seenDigit = true;
            } else if (c == '.') {
                digits.append(c);
            } else if (c == '+' || c == '-') {
                // 先頭の符号でも末尾の符号でも意味は同じである
                sign = c == '-' ? -sign : sign;
            } else if ((c == 'C' || c == 'c') && matches(text, i, "CR")) {
                sign = -sign;
                i++;
            } else if ((c == 'D' || c == 'd') && matches(text, i, "DB")) {
                sign = -sign;
                i++;
            } else if (c == ' ' || (loose && (c == ',' || !seenDigit))) {
                continue;
            }
        }
        if (digits.isEmpty() || digits.toString().equals(".")) {
            return Decimal.zero(0);
        }
        Decimal parsed = Decimal.parse(digits.charAt(0) == '.' ? "0" + digits : digits.toString());
        return sign < 0 ? parsed.negate() : parsed;
    }

    private static boolean matches(String text, int at, String word) {
        return text.regionMatches(true, at, word, 0, word.length());
    }

    /** 個数や位置として使う引数を、整数として取り出す。 */
    private static int toIndex(Decimal value, String function) {
        Decimal truncated = integerPart(value);
        if (truncated.compareTo(value) != 0) {
            throw new IllegalArgumentException(
                    "FUNCTION " + function + " requires an integer argument: " + value);
        }
        return truncated.toBigDecimal().intValueExact();
    }
}
