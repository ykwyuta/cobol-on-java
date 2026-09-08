package dev.cobolonjava.runtime.function;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CollatingSequence;
import dev.cobolonjava.runtime.decimal.CobolRounding;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Arrays;

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

    /**
     * {@code FUNCTION MEDIAN}。並べ替えた真ん中である。
     *
     * <p>個数が偶数なら真ん中 2 つの平均になる。2 で割るのは 10 進では割り切れるので、
     * 小数桁が 1 つ増えるだけで<b>近似は入らない</b>。
     */
    public static Decimal median(Decimal[] values) {
        Decimal[] sorted = values.clone();
        Arrays.sort(sorted, Decimal::compareTo);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1
                ? sorted[middle]
                : half(sorted[middle - 1].add(sorted[middle]));
    }

    /** {@code FUNCTION MIDRANGE}。最大と最小の平均である。 */
    public static Decimal midrange(Decimal[] values) {
        return half(max(values).add(min(values)));
    }

    /**
     * 2 で割る。10 進では必ず割り切れるので、小数桁を 1 つ増やしてから割る。
     */
    private static Decimal half(Decimal value) {
        return value.divide(TWO, value.scale() + 1, CobolRounding.TRUNCATION);
    }

    // ---- 近似が入る関数 ----

    /**
     * 結果を丸める桁数 (要件 FR-071、暫定判断 P-065)。
     *
     * <p>{@code double} が確かに持てるのは 10 進 15 桁ほどである。それより下は
     * 2 進から 10 進へ写したときの屑であり、そのまま返すと<b>意味のない桁</b>が
     * 見えてしまう。ここで落とす。
     *
     * <p>規格が求める精度はこれよりはるかに緩い。NIST CCVS85 は
     * {@code FUNCTION SIN(0)} を {@code -0.000020} 〜 {@code 0.000020} の範囲で、
     * {@code FUNCTION ANNUITY(2.9, 4)} を {@code 2.91252} 〜 {@code 2.91264} の範囲で
     * 判定している。<b>有効数字 6 桁ほど</b>あれば合格する。
     */
    private static final MathContext RESULT = new MathContext(15, RoundingMode.HALF_UP);

    /** {@code FUNCTION SQRT}。負の引数は 0 を返す。 */
    public static Decimal sqrt(Decimal value) {
        if (value.signum() < 0) {
            return Decimal.zero(0);
        }
        // 平方根だけは 10 進のまま正しく丸められる
        return rounded(value.toBigDecimal().sqrt(RESULT));
    }

    /** {@code FUNCTION LOG}。自然対数。引数が 0 以下なら 0 を返す。 */
    public static Decimal log(Decimal value) {
        return value.signum() <= 0 ? Decimal.zero(0) : fromDouble(Math.log(toDouble(value)));
    }

    /** {@code FUNCTION LOG10}。常用対数。引数が 0 以下なら 0 を返す。 */
    public static Decimal log10(Decimal value) {
        return value.signum() <= 0 ? Decimal.zero(0) : fromDouble(Math.log10(toDouble(value)));
    }

    /** {@code FUNCTION EXP}。 */
    public static Decimal exp(Decimal value) {
        return fromDouble(Math.exp(toDouble(value)));
    }

    /** {@code FUNCTION EXP10}。 */
    public static Decimal exp10(Decimal value) {
        return fromDouble(Math.pow(10, toDouble(value)));
    }

    /** {@code FUNCTION SIN}。引数はラジアンである。 */
    public static Decimal sin(Decimal value) {
        return fromDouble(Math.sin(toDouble(value)));
    }

    /** {@code FUNCTION COS}。 */
    public static Decimal cos(Decimal value) {
        return fromDouble(Math.cos(toDouble(value)));
    }

    /** {@code FUNCTION TAN}。 */
    public static Decimal tan(Decimal value) {
        return fromDouble(Math.tan(toDouble(value)));
    }

    /** {@code FUNCTION ASIN}。引数が -1 未満か 1 超なら 0 を返す。 */
    public static Decimal asin(Decimal value) {
        double argument = toDouble(value);
        return argument < -1 || argument > 1 ? Decimal.zero(0) : fromDouble(Math.asin(argument));
    }

    /** {@code FUNCTION ACOS}。 */
    public static Decimal acos(Decimal value) {
        double argument = toDouble(value);
        return argument < -1 || argument > 1 ? Decimal.zero(0) : fromDouble(Math.acos(argument));
    }

    /** {@code FUNCTION ATAN}。 */
    public static Decimal atan(Decimal value) {
        return fromDouble(Math.atan(toDouble(value)));
    }

    /** {@code FUNCTION MEAN}。相加平均である。 */
    public static Decimal mean(Decimal[] values) {
        return rounded(sum(values).toBigDecimal()
                .divide(BigDecimal.valueOf(values.length), RESULT));
    }

    /**
     * {@code FUNCTION VARIANCE}。<b>平均からのずれの 2 乗の平均</b>である。
     *
     * <p>標本分散ではなく母分散である。個数から 1 を引かない。規格がそう決めている。
     */
    public static Decimal variance(Decimal[] values) {
        BigDecimal average = mean(values).toBigDecimal();
        BigDecimal total = BigDecimal.ZERO;
        for (Decimal value : values) {
            BigDecimal deviation = value.toBigDecimal().subtract(average);
            total = total.add(deviation.multiply(deviation));
        }
        return rounded(total.divide(BigDecimal.valueOf(values.length), RESULT));
    }

    /** {@code FUNCTION STANDARD-DEVIATION}。分散の平方根である。 */
    public static Decimal standardDeviation(Decimal[] values) {
        return sqrt(variance(values));
    }

    /**
     * {@code FUNCTION ANNUITY}。<b>元金 1 に対する毎期の返済額</b>である。
     *
     * <pre>
     * r / (1 - (1 + r)^-n)
     * </pre>
     *
     * <p>利率が 0 なら分母が 0 になるので、{@code 1 / n} と定める。規格がそう決めている。
     */
    public static Decimal annuity(Decimal rate, Decimal periods) {
        double n = toDouble(periods);
        double r = toDouble(rate);
        if (r == 0) {
            return fromDouble(1 / n);
        }
        return fromDouble(r / (1 - Math.pow(1 + r, -n)));
    }

    /**
     * {@code FUNCTION PRESENT-VALUE}。将来の金額を<b>いまの価値へ割り引いた</b>合計である。
     *
     * @param arguments 先頭が割引率、続きが 1 期後・2 期後…の金額
     */
    public static Decimal presentValue(Decimal[] arguments) {
        double rate = toDouble(arguments[0]);
        double total = 0;
        for (int i = 1; i < arguments.length; i++) {
            total += toDouble(arguments[i]) / Math.pow(1 + rate, i);
        }
        return fromDouble(total);
    }

    /**
     * {@code FUNCTION RANDOM} が返す値。
     *
     * <p>並びそのものは {@code ProgramContext} が持っている。ここは<b>値の形</b>だけを
     * 決める。規格が求めるのは 0 以上 1 未満であることだけである。
     */
    public static Decimal randomValue(double value) {
        return fromDouble(value);
    }

    /** {@code double} へ落とす。 */
    private static double toDouble(Decimal value) {
        return value.toBigDecimal().doubleValue();
    }

    /** {@code double} から戻す。意味のない下位の桁は落とす。 */
    private static Decimal fromDouble(double value) {
        if (!Double.isFinite(value)) {
            throw new ArithmeticException("the result of an intrinsic function is not a number");
        }
        return rounded(new BigDecimal(value));
    }

    /** 有効数字を {@link #RESULT} の桁へ揃える。 */
    private static Decimal rounded(BigDecimal value) {
        BigDecimal trimmed = value.round(RESULT).stripTrailingZeros();
        return Decimal.parse((trimmed.scale() < 0 ? trimmed.setScale(0) : trimmed)
                .toPlainString());
    }

    // ---- 日付 ----

    /**
     * {@code FUNCTION INTEGER-OF-DATE}。{@code YYYYMMDD} を通日へ直す。
     *
     * <p>1601 年 1 月 1 日が 1 である。規格がそう決めている。暦はグレゴリオ暦であり、
     * 1601 年より前は扱わない。
     *
     * @return 日付として読めなければ 0
     */
    public static Decimal integerOfDate(Decimal yyyymmdd) {
        long value = toLong(yyyymmdd, "INTEGER-OF-DATE");
        LocalDate date = dateOf((int) (value / 10000), (int) (value / 100 % 100),
                (int) (value % 100));
        return date == null ? Decimal.zero(0) : Decimal.of(dayNumber(date), 0);
    }

    /**
     * {@code FUNCTION INTEGER-OF-DAY}。{@code YYYYDDD} を通日へ直す。
     *
     * @return 日付として読めなければ 0
     */
    public static Decimal integerOfDay(Decimal yyyyddd) {
        long value = toLong(yyyyddd, "INTEGER-OF-DAY");
        int year = (int) (value / 1000);
        int day = (int) (value % 1000);
        if (year < FIRST_YEAR || year > LAST_YEAR || day < 1) {
            return Decimal.zero(0);
        }
        LocalDate first = LocalDate.of(year, 1, 1);
        if (day > first.lengthOfYear()) {
            return Decimal.zero(0);
        }
        return Decimal.of(dayNumber(first.plusDays(day - 1L)), 0);
    }

    /**
     * {@code FUNCTION DATE-OF-INTEGER}。通日を {@code YYYYMMDD} へ直す。
     *
     * @return 範囲の外なら 0
     */
    public static Decimal dateOfInteger(Decimal days) {
        LocalDate date = dateOf(toLong(days, "DATE-OF-INTEGER"));
        return date == null
                ? Decimal.zero(0)
                : Decimal.of(date.getYear() * 10000L
                        + date.getMonthValue() * 100L + date.getDayOfMonth(), 0);
    }

    /**
     * {@code FUNCTION DAY-OF-INTEGER}。通日を {@code YYYYDDD} へ直す。
     *
     * @return 範囲の外なら 0
     */
    public static Decimal dayOfInteger(Decimal days) {
        LocalDate date = dateOf(toLong(days, "DAY-OF-INTEGER"));
        return date == null
                ? Decimal.zero(0)
                : Decimal.of(date.getYear() * 1000L + date.getDayOfYear(), 0);
    }

    /** 規格が数えはじめる日。 */
    private static final LocalDate EPOCH = LocalDate.of(1601, 1, 1);
    private static final int FIRST_YEAR = 1601;
    private static final int LAST_YEAR = 9999;
    private static final Decimal TWO = Decimal.of(2, 0);

    private static long dayNumber(LocalDate date) {
        return date.toEpochDay() - EPOCH.toEpochDay() + 1;
    }

    /** 通日から日付へ。範囲の外なら {@code null}。 */
    private static LocalDate dateOf(long dayNumber) {
        if (dayNumber < 1) {
            return null;
        }
        LocalDate date = EPOCH.plusDays(dayNumber - 1);
        return date.getYear() > LAST_YEAR ? null : date;
    }

    /** 年月日から日付へ。暦に無い日なら {@code null}。 */
    private static LocalDate dateOf(int year, int month, int day) {
        if (year < FIRST_YEAR || year > LAST_YEAR || month < 1 || month > 12 || day < 1) {
            return null;
        }
        LocalDate first = LocalDate.of(year, month, 1);
        return day > first.lengthOfMonth() ? null : first.withDayOfMonth(day);
    }

    private static long toLong(Decimal value, String function) {
        return toIndex(value, function);
    }

    /**
     * {@code FUNCTION CURRENT-DATE} が返す 21 文字を組み立てる。
     *
     * <pre>
     * YYYYMMDDhhmmsscc±hhmm
     * </pre>
     *
     * <p>末尾 5 文字は協定世界時からのずれである。ずれが分からない処理系は
     * {@code 00000} を置くと規格が決めているが、こちらは時計から取れるので入れる。
     */
    public static byte[] timestamp(ZonedDateTime now, CodePage codePage) {
        int offsetSeconds = now.getOffset().getTotalSeconds();
        char sign = offsetSeconds < 0 ? '-' : '+';
        int offsetMinutes = Math.abs(offsetSeconds) / 60;
        String text = String.format("%04d%02d%02d%02d%02d%02d%02d%c%02d%02d",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
                now.getHour(), now.getMinute(), now.getSecond(), now.getNano() / 10_000_000,
                sign, offsetMinutes / 60, offsetMinutes % 60);
        return codePage.encode(text);
    }

    /** {@code FUNCTION CURRENT-DATE} が返す文字数。 */
    public static final int TIMESTAMP_LENGTH = 21;

    // ---- 文字 ----

    /**
     * {@code FUNCTION CHAR}。照合順序の<b>何番目か</b>から文字を得る。1 から数える。
     *
     * <p>{@code PROGRAM COLLATING SEQUENCE} を差し替えれば答えが変わる。定義が
     * 照合順序を指しているためである (要件 FR-054)。
     */
    public static byte[] charOf(Decimal ordinal, CollatingSequence order) {
        return new byte[] { order.characterAt(toIndex(ordinal, "CHAR")) };
    }

    /** {@code FUNCTION ORD}。{@link #charOf} の逆である。 */
    public static Decimal ord(byte[] value, CollatingSequence order) {
        if (value.length == 0) {
            throw new IllegalArgumentException("FUNCTION ORD requires one character");
        }
        return Decimal.of(order.positionOf(value[0]), 0);
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
