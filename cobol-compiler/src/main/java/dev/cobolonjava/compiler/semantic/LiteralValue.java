package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.parser.CobolParser;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * 定数 1 個 (要件 FR-013)。{@code VALUE} 句と 88 レベルの条件名が使う。
 *
 * <p>図形定数は「ある文字で埋める」という意味しか持たない。したがって
 * 文字定数・数字定数・図形定数・{@code ALL 定数} の 4 つに正規化する。
 *
 * <p>16 進定数 ({@code X'7D'}) は文字定数の一種として持つ。種別を増やすと、定数を
 * 場合分けしている箇所が 16 進定数を黙って素通りさせる。文字定数と違うのは
 * <b>バイトが code page に依らない</b>ことだけなので、バイトを持たせて区別する。
 */
public sealed interface LiteralValue {

    /** 16 進定数の文字の欄を埋める文字。数字にも英字にも見えないものを置く。 */
    char HEX_PLACEHOLDER = '�';

    /**
     * 文字定数。引用符は外してある。
     *
     * @param text 文字。16 進定数ではバイト数と同じ長さの {@link #HEX_PLACEHOLDER} の並び
     * @param hex  16 進定数のバイト。文字定数では {@code null}
     */
    record Text(String text, byte[] hex) implements LiteralValue {

        public Text(String text) {
            this(text, null);
        }

        public Text {
            Objects.requireNonNull(text, "text");
            hex = hex == null ? null : hex.clone();
        }

        @Override
        public byte[] hex() {
            return hex == null ? null : hex.clone();
        }

        /** 16 進定数か。文字として読む箇所 (名前、1 文字の指定) はこれを断る。 */
        public boolean isHex() {
            return hex != null;
        }

        /** 記憶域へ置くバイト。16 進定数は code page を通さない。 */
        public byte[] bytes(CodePage codePage) {
            return hex != null ? hex.clone() : codePage.encode(text);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Text that && text.equals(that.text)
                    && Arrays.equals(hex, that.hex);
        }

        @Override
        public int hashCode() {
            return 31 * text.hashCode() + Arrays.hashCode(hex);
        }

        @Override
        public String toString() {
            return hex == null ? "Text[" + text + "]"
                    : "Text[X'" + HexFormat.of().withUpperCase().formatHex(hex) + "']";
        }
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

    /**
     * {@code ALL '定数'}。項目の長さいっぱいまで繰り返す。
     *
     * @param hex 16 進定数を繰り返すときのバイト。文字定数では {@code null}
     */
    record Repeated(String text, byte[] hex) implements LiteralValue {

        public Repeated(String text) {
            this(text, null);
        }

        public Repeated {
            Objects.requireNonNull(text, "text");
            hex = hex == null ? null : hex.clone();
        }

        @Override
        public byte[] hex() {
            return hex == null ? null : hex.clone();
        }

        /** 繰り返しの 1 回分のバイト。 */
        public byte[] bytes(CodePage codePage) {
            return hex != null ? hex.clone() : codePage.encode(text);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Repeated that && text.equals(that.text)
                    && Arrays.equals(hex, that.hex);
        }

        @Override
        public int hashCode() {
            return 31 * text.hashCode() + Arrays.hashCode(hex);
        }

        @Override
        public String toString() {
            return "Repeated[" + new Text(text, hex) + "]";
        }
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
            return textOf(context.LITERAL().getText());
        }
        if (context.NUMBER() != null) {
            String spelling = context.NUMBER().getText();
            return new Number(Decimal.parse(spelling), spelling);
        }
        CobolParser.FigurativeConstantContext figurative = context.figurativeConstant();
        if (figurative.LITERAL() != null) {
            Text unit = textOf(figurative.LITERAL().getText());
            return new Repeated(unit.text(), unit.hex());
        }
        // getText() は語を詰めて返すので "ALLZERO" のようになる。ALL は繰り返しの指定であり、
        // 図形定数そのものには効かない (ZERO も ALL ZERO も「0 で埋める」である)
        String text = figurative.getText().toUpperCase(Locale.ROOT);
        if (text.startsWith("ALL")) {
            text = text.substring("ALL".length());
        }
        return new Figure(constantOf(text));
    }

    /**
     * {@code INSPECT} の被演算子の定数を読む。
     *
     * <p>こちらには {@code ALL 定数} の分岐がない。{@code INSPECT} では {@code ALL} が
     * 句の種別を表す語だからである (85 規格 6.19.4)。
     */
    static LiteralValue of(CobolParser.InspectLiteralContext context) {
        if (context.LITERAL() != null) {
            return textOf(context.LITERAL().getText());
        }
        if (context.NUMBER() != null) {
            String spelling = context.NUMBER().getText();
            return new Number(Decimal.parse(spelling), spelling);
        }
        return new Figure(constantOf(context.figurativeWord().getText().toUpperCase(Locale.ROOT)));
    }

    /**
     * 字句のままの定数を文字定数にする。
     *
     * <p>16 進の桁が正しいことは字句解析で確かめてある。
     */
    static Text textOf(String spelling) {
        char first = spelling.charAt(0);
        if (first == 'X' || first == 'x') {
            byte[] bytes = HexFormat.of().parseHex(spelling, 2, spelling.length() - 1);
            return new Text(String.valueOf(HEX_PLACEHOLDER).repeat(bytes.length), bytes);
        }
        return new Text(unquote(spelling));
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
