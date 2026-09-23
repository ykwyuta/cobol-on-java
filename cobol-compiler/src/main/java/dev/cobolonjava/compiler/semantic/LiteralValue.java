package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.parser.CobolParser;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.codepage.UnrepresentableCharacterException;
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
     * 文字定数を符号化するコードページ。
     *
     * <p>翻訳時のコードページは処理系のどこでも {@code CodePages.DEFAULT} に固定してある。
     * {@code CODEPAGE} オプションで選べるようにするのは別の増分であり、暫定判断 P-177
     * に残した。ここで見ているのは「選ばれたコードページ」ではなく
     * <b>いま必ず使われるコードページ</b>である。
     */
    CodePage SOURCE_CODE_PAGE = CodePages.DEFAULT;

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

    /**
     * 国字定数 ({@code N'..'} と {@code NX'..'})。
     *
     * <p>{@link Text} と種別を分けたのは、バイトの意味が違うからである。英数字の定数として
     * 扱う箇所に黙って流れ込むと、UTF-16 のバイトを EBCDIC として書いてしまう。
     *
     * @param text  文字。{@code NX} では符号単位の数と同じ長さの {@link #HEX_PLACEHOLDER} の並び
     * @param bytes UTF-16 (ビッグエンディアン) のバイト
     */
    record National(String text, byte[] bytes) implements LiteralValue {

        public National {
            Objects.requireNonNull(text, "text");
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof National that && Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "National[NX'" + HexFormat.of().withUpperCase().formatHex(bytes) + "']";
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
            return literalOf(context.LITERAL().getText());
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
            return literalOf(context.LITERAL().getText());
        }
        if (context.NUMBER() != null) {
            String spelling = context.NUMBER().getText();
            return new Number(Decimal.parse(spelling), spelling);
        }
        return new Figure(constantOf(context.figurativeWord().getText().toUpperCase(Locale.ROOT)));
    }

    /**
     * 字句のままの定数を読む。{@code N'..'} と {@code NX'..'} は国字定数、ほかは文字定数である。
     */
    static LiteralValue literalOf(String spelling) {
        char first = Character.toUpperCase(spelling.charAt(0));
        if (first == 'N') {
            if (Character.toUpperCase(spelling.charAt(1)) == 'X') {
                byte[] bytes = HexFormat.of().parseHex(spelling, 3, spelling.length() - 1);
                return new National(String.valueOf(HEX_PLACEHOLDER).repeat(bytes.length / 2), bytes);
            }
            String text = unquote(spelling.substring(1));
            return new National(text, text.getBytes(java.nio.charset.StandardCharsets.UTF_16BE));
        }
        return textOf(spelling);
    }

    /**
     * 字句のままの定数を文字定数にする。
     *
     * <p>16 進の桁が正しいことは字句解析で確かめてある。
     */
    static Text textOf(String spelling) {
        char first = spelling.charAt(0);
        if (first == 'N' || first == 'n') {
            // ALL N'..' と、国字定数を英数字の定数として読む箇所。国字の図形定数はまだ持たない
            throw new IllegalArgumentException("a national literal is not supported here: " + spelling);
        }
        if (first == 'X' || first == 'x') {
            byte[] bytes = HexFormat.of().parseHex(spelling, 2, spelling.length() - 1);
            return new Text(String.valueOf(HEX_PLACEHOLDER).repeat(bytes.length), bytes);
        }
        Text text = new Text(unquote(spelling));
        requireRepresentable(text.text());
        return text;
    }

    /**
     * 文字定数が翻訳時のコードページで表せることを確かめる (要件 FR-051, FR-181)。
     *
     * <p><b>ここを素通りさせると原文の文字が消える。</b>IBM-1047 は日本語を持たないので、
     * {@code VALUE '山田太郎'} は診断も警告もないまま {@code X'3F3F3F3F'} (EBCDIC の SUB)
     * になる。翻訳は成功し、実行も成功し、{@code DISPLAY} は空行を出す。書いた人が
     * 気付ける場所がどこにもない。
     *
     * <p>16 進定数は通さない。{@code X'0E45650F'} はコードページを経由しないバイトであり、
     * 日本語の資産をいま扱える唯一の書き方だからである (設計 28 章)。
     */
    private static void requireRepresentable(String text) {
        if (!SOURCE_CODE_PAGE.canEncode(text)) {
            // 文面は 1 か所で作る。判定と診断で言うことがずれないようにするためである
            SOURCE_CODE_PAGE.encode(text);
        }
    }

    /**
     * 定数を読めなかったときの診断の文面。
     *
     * <p>コードページで表せない文字は<b>理由を名指しする</b>。{@code invalid literal: 山田太郎}
     * とだけ言われても、綴りが悪いのか処理系が持っていないのかが分からない。
     * それ以外の誤り (数字定数の綴りなど) はこれまでと同じ文面のままにしてある。
     */
    static String invalidLiteral(String spelling, RuntimeException failure) {
        return failure instanceof UnrepresentableCharacterException unrepresentable
                ? unrepresentable.getMessage()
                : "invalid literal: " + spelling;
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
