package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.parser.CobolParser;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.util.Locale;

/**
 * 定数 1 個 (要件 FR-013)。{@code VALUE} 句と 88 レベルの条件名が使う。
 *
 * <p>図形定数は「ある文字で埋める」という意味しか持たない。したがって
 * 文字定数・数字定数・図形定数・{@code ALL 定数} の 4 つに正規化する。
 */
public sealed interface LiteralValue {

    /** 文字定数。引用符は外してある。 */
    record Text(String text) implements LiteralValue {
    }

    /**
     * 数字定数。
     *
     * <p>値のほかに<b>原文の綴り</b>を持つ。英数字の受取項目へ数字定数を転記するとき、
     * 規格はそれを<b>英数字定数として扱う</b>と決めている。値に直してから書き戻すと
     * {@code 0123456789} の先頭の {@code 0} が消えてしまう (要件 FR-061)。
     *
     * @param source 原文に書かれたとおりの綴り
     */
    record Number(Decimal value, String source) implements LiteralValue {

        /** 翻訳系が組み立てた数字定数。原文の綴りを持たないので値から作る。 */
        public Number(Decimal value) {
            this(value, value.toBigDecimal().toPlainString());
        }
    }

    /** 図形定数。項目の長さいっぱいまで埋める。 */
    record Figure(FigurativeConstant constant) implements LiteralValue {
    }

    /** {@code ALL '定数'}。項目の長さいっぱいまで繰り返す。 */
    record Repeated(String text) implements LiteralValue {
    }

    /** 図形定数の種別。 */
    enum FigurativeConstant {
        ZERO, SPACE, HIGH_VALUE, LOW_VALUE, QUOTE, NULL
    }

    /**
     * 構文木の定数を読む。
     *
     * @throws IllegalArgumentException 知らない図形定数の場合
     */
    static LiteralValue of(CobolParser.LiteralContext context) {
        if (context.LITERAL() != null) {
            return new Text(unquote(context.LITERAL().getText()));
        }
        if (context.NUMBER() != null) {
            String spelling = context.NUMBER().getText();
            return new Number(Decimal.parse(spelling), spelling);
        }
        CobolParser.FigurativeConstantContext figurative = context.figurativeConstant();
        if (figurative.LITERAL() != null) {
            return new Repeated(unquote(figurative.LITERAL().getText()));
        }
        // getText() は語を詰めて返すので "ALLZERO" のようになる。ALL は繰り返しの指定であり、
        // 図形定数そのものには効かない (ZERO も ALL ZERO も「0 で埋める」である)
        String text = figurative.getText().toUpperCase(Locale.ROOT);
        if (text.startsWith("ALL")) {
            text = text.substring("ALL".length());
        }
        return new Figure(constantOf(text));
    }

    private static FigurativeConstant constantOf(String text) {
        return switch (text) {
            case "ZERO", "ZEROS", "ZEROES" -> FigurativeConstant.ZERO;
            case "SPACE", "SPACES" -> FigurativeConstant.SPACE;
            case "HIGH-VALUE", "HIGH-VALUES" -> FigurativeConstant.HIGH_VALUE;
            case "LOW-VALUE", "LOW-VALUES" -> FigurativeConstant.LOW_VALUE;
            case "QUOTE", "QUOTES" -> FigurativeConstant.QUOTE;
            case "NULL", "NULLS" -> FigurativeConstant.NULL;
            default -> throw new IllegalArgumentException("unknown figurative constant: " + text);
        };
    }

    /** 引用符を外し、二重の引用符を 1 個へ戻す。 */
    private static String unquote(String text) {
        char quote = text.charAt(0);
        return text.substring(1, text.length() - 1).replace("" + quote + quote, "" + quote);
    }
}
