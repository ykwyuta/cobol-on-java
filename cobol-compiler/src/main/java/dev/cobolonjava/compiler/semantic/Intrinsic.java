package dev.cobolonjava.compiler.semantic;

import java.util.Locale;

/**
 * 組み込み関数 (要件 FR-070)。
 *
 * <p>ここに並んでいるのは<b>値が一意に決まる</b>ものだけである。三角関数や対数のように
 * 結果の桁数が処理系の決めごとになるものは、まだ入れていない (暫定判断 P-065)。
 * 書かれていれば「まだ書けない」と断る。<b>近い値を黙って返すほうが悪い</b>。
 *
 * @param arguments 引数の数。{@code -1} は「1 個以上いくつでも」
 * @param takes 引数の受け取り方
 * @param returns 戻り値の分類
 */
public enum Intrinsic {

    /** 引数の文字位置の数。翻訳時に決まる。 */
    LENGTH(1, Argument.ANY, Result.INTEGER),
    /** 英小文字を大文字にする。長さは変わらない。 */
    UPPER_CASE(1, Argument.ALPHANUMERIC, Result.SAME_LENGTH),
    /** 英大文字を小文字にする。 */
    LOWER_CASE(1, Argument.ALPHANUMERIC, Result.SAME_LENGTH),
    /** バイトの並びを逆にする。 */
    REVERSE(1, Argument.ALPHANUMERIC, Result.SAME_LENGTH),
    /** 照合順序の何番目かから文字を得る。 */
    CHAR(1, Argument.NUMERIC, Result.ONE_CHARACTER),
    /** 文字が照合順序の何番目か。 */
    ORD(1, Argument.ALPHANUMERIC, Result.INTEGER),
    /** 最大値。 */
    MAX(-1, Argument.NUMERIC, Result.NUMERIC),
    /** 最小値。 */
    MIN(-1, Argument.NUMERIC, Result.NUMERIC),
    /** 合計。 */
    SUM(-1, Argument.NUMERIC, Result.NUMERIC),
    /** 何番目の引数が最大か。 */
    ORD_MAX(-1, Argument.NUMERIC, Result.INTEGER),
    /** 何番目の引数が最小か。 */
    ORD_MIN(-1, Argument.NUMERIC, Result.INTEGER),
    /** 最大と最小の差。 */
    RANGE(-1, Argument.NUMERIC, Result.NUMERIC),
    /** 引数を超えない最大の整数。 */
    INTEGER(1, Argument.NUMERIC, Result.INTEGER),
    /** 0 の側へ切り捨てた整数。 */
    INTEGER_PART(1, Argument.NUMERIC, Result.INTEGER),
    /** 剰余。符号は除数に従う。 */
    MOD(2, Argument.NUMERIC, Result.NUMERIC),
    /** 剰余。符号は被除数に従う。 */
    REM(2, Argument.NUMERIC, Result.NUMERIC),
    /** 階乗。 */
    FACTORIAL(1, Argument.NUMERIC, Result.INTEGER),
    /** 数字の綴りを数値として読む。 */
    NUMVAL(1, Argument.ALPHANUMERIC, Result.NUMERIC),
    /** 通貨記号つきの綴りを数値として読む。第 2 引数は落とす記号である。 */
    NUMVAL_C(-2, Argument.ALPHANUMERIC, Result.NUMERIC),
    /** 並べ替えた真ん中。 */
    MEDIAN(-1, Argument.NUMERIC, Result.NUMERIC),
    /** 最大と最小の平均。 */
    MIDRANGE(-1, Argument.NUMERIC, Result.NUMERIC),
    /** {@code YYYYMMDD} から通日へ。1601 年 1 月 1 日が 1 である。 */
    INTEGER_OF_DATE(1, Argument.NUMERIC, Result.INTEGER),
    /** {@code YYYYDDD} から通日へ。 */
    INTEGER_OF_DAY(1, Argument.NUMERIC, Result.INTEGER),
    /** 通日から {@code YYYYMMDD} へ。 */
    DATE_OF_INTEGER(1, Argument.NUMERIC, Result.INTEGER),
    /** 通日から {@code YYYYDDD} へ。 */
    DAY_OF_INTEGER(1, Argument.NUMERIC, Result.INTEGER),
    /** いまの日付と時刻。21 文字である。 */
    CURRENT_DATE(0, Argument.ANY, Result.TIMESTAMP),
    /** 翻訳した日付と時刻。21 文字である。 */
    WHEN_COMPILED(0, Argument.ANY, Result.TIMESTAMP),
    /** 相加平均。 */
    MEAN(-1, Argument.NUMERIC, Result.NUMERIC),
    /** 母分散。平均からのずれの 2 乗の平均である。 */
    VARIANCE(-1, Argument.NUMERIC, Result.NUMERIC),
    /** 標準偏差。分散の平方根である。 */
    STANDARD_DEVIATION(-1, Argument.NUMERIC, Result.NUMERIC),
    /** 平方根。 */
    SQRT(1, Argument.NUMERIC, Result.NUMERIC),
    /** 自然対数。 */
    LOG(1, Argument.NUMERIC, Result.NUMERIC),
    /** 常用対数。 */
    LOG10(1, Argument.NUMERIC, Result.NUMERIC),
    /** 指数関数。 */
    EXP(1, Argument.NUMERIC, Result.NUMERIC),
    /** 10 のべき乗。 */
    EXP10(1, Argument.NUMERIC, Result.NUMERIC),
    /** 正弦。引数はラジアンである。 */
    SIN(1, Argument.NUMERIC, Result.NUMERIC),
    /** 余弦。 */
    COS(1, Argument.NUMERIC, Result.NUMERIC),
    /** 正接。 */
    TAN(1, Argument.NUMERIC, Result.NUMERIC),
    /** 逆正弦。 */
    ASIN(1, Argument.NUMERIC, Result.NUMERIC),
    /** 逆余弦。 */
    ACOS(1, Argument.NUMERIC, Result.NUMERIC),
    /** 逆正接。 */
    ATAN(1, Argument.NUMERIC, Result.NUMERIC),
    /** 元金 1 に対する毎期の返済額。 */
    ANNUITY(2, Argument.NUMERIC, Result.NUMERIC),
    /** 割引率と将来の金額から、いまの価値を求める。 */
    PRESENT_VALUE(-1, Argument.NUMERIC, Result.NUMERIC),
    /** 0 以上 1 未満の乱数。引数を書けば種になる。 */
    RANDOM(-3, Argument.NUMERIC, Result.NUMERIC);

    /** 引数の受け取り方。 */
    public enum Argument {
        /** 数値として読む。 */
        NUMERIC,
        /** バイト列として読む。 */
        ALPHANUMERIC,
        /** 読まない。項目の長さだけを見る。 */
        ANY
    }

    /** 戻り値の分類。 */
    public enum Result {
        /** 整数。 */
        INTEGER,
        /** 数値。 */
        NUMERIC,
        /** 引数と同じ長さのバイト列。 */
        SAME_LENGTH,
        /** 1 バイト。 */
        ONE_CHARACTER,
        /** {@code YYYYMMDDhhmmsscc±hhmm} の 21 文字。 */
        TIMESTAMP;

        /** 数値として使えるか。 */
        public boolean isNumeric() {
            return this == INTEGER || this == NUMERIC;
        }
    }

    private final int arguments;
    private final Argument takes;
    private final Result returns;

    Intrinsic(int arguments, Argument takes, Result returns) {
        this.arguments = arguments;
        this.takes = takes;
        this.returns = returns;
    }

    public Argument takes() {
        return takes;
    }

    public Result returns() {
        return returns;
    }

    /** 書かれた引数の数を受け付けるか。 */
    public boolean accepts(int given) {
        return switch (arguments) {
            case -1 -> given >= 1;
            case -2 -> given == 1 || given == 2;
            case -3 -> given == 0 || given == 1;
            default -> given == arguments;
        };
    }

    /** 受け付ける引数の数の説明。診断に書く。 */
    public String arity() {
        return switch (arguments) {
            case -1 -> "one or more arguments";
            case -2 -> "one or two arguments";
            case -3 -> "no arguments or one argument";
            case 0 -> "no arguments";
            case 1 -> "one argument";
            default -> arguments + " arguments";
        };
    }

    /** ソースに書かれた綴り。ハイフンで綴る。 */
    public String spelling() {
        return name().replace('_', '-');
    }

    /**
     * 綴りから引く。
     *
     * @return 知らない関数なら {@code null}
     */
    public static Intrinsic of(String spelling) {
        String name = spelling.toUpperCase(Locale.ROOT).replace('-', '_');
        for (Intrinsic candidate : values()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        return null;
    }
}
