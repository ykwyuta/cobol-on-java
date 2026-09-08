package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.Origin;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.item.NumericItem;
import dev.cobolonjava.runtime.item.Usage;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code INITIALIZE} が書き込むバイト列を翻訳時に組み立てる (要件 FR-060)。
 *
 * <h2>なぜ 1 個ずつの転記にしないのか</h2>
 * <p>{@code INITIALIZE} は配下の基本項目それぞれへの転記の集まりである。素直に展開すると
 * <b>表の反復の数だけ転記が並ぶ</b>。{@code OCCURS 1000} の表なら 1000 個である。
 *
 * <p>入る値が翻訳時に決まるなら、書き込まれるバイト列も決まる。であれば<b>まとめて
 * 1 回で書けばよい</b>。ここではその並びを組み立てる。
 *
 * <h2>触らない場所は書かない</h2>
 * <p>{@code FILLER} は初期化の対象外である ({@code WITH FILLER} を書いた場合を除く)。
 * {@code REDEFINES} で重ねた項目も対象外である。したがって<b>一括で塗り潰すことはできない</b>。
 * 書き込む場所を印で覚えておき、連続する部分ごとに分けて書く。
 *
 * <h2>符号化はランタイムに任せる</h2>
 * <p>ゼロを詰めるといっても、パック 10 進なら {@code 000C}、数字編集項目なら編集した結果に
 * なる。コンパイラが独自に組み立てると<b>実行時に同じ値を転記したときとずれる</b>。
 * 実際に {@link Ops} を呼んで書かせている。
 */
public final class InitializeImage {

    private final CodePage codePage;
    private final boolean withFiller;
    private final List<Replacing> replacing;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private InitializeImage(CodePage codePage, boolean withFiller, List<Replacing> replacing) {
        this.codePage = codePage;
        this.withFiller = withFiller;
        this.replacing = List.copyOf(replacing);
    }

    /**
     * {@code REPLACING 分類 DATA BY 値} の指定 1 個。
     *
     * @param category 対象の分類
     * @param value    入れる値。定数のみ
     */
    public record Replacing(Category category, LiteralValue value) {
    }

    /** {@code REPLACING} で書ける分類。 */
    public enum Category {
        ALPHABETIC, ALPHANUMERIC, ALPHANUMERIC_EDITED, NUMERIC, NUMERIC_EDITED;

        /** 項目の分類がこれに当たるか。 */
        public boolean matches(DataCategory actual) {
            return switch (this) {
                case ALPHABETIC -> actual == DataCategory.ALPHABETIC;
                case ALPHANUMERIC -> actual == DataCategory.ALPHANUMERIC;
                case ALPHANUMERIC_EDITED -> actual == DataCategory.ALPHANUMERIC_EDITED;
                case NUMERIC -> actual.isNumeric();
                case NUMERIC_EDITED -> actual == DataCategory.NUMERIC_EDITED;
            };
        }
    }

    /**
     * 書き込む部分 1 つ。
     *
     * @param offset 項目の先頭からの位置
     * @param bytes  書き込むバイト列
     */
    public record Run(int offset, byte[] bytes) {
    }

    /** 組み立ての結果。 */
    public record Result(List<Run> runs, List<Diagnostic> diagnostics) {

        public boolean succeeded() {
            return !Diagnostic.blocking(diagnostics);
        }
    }

    /**
     * 項目 1 個ぶんの書き込みを組み立てる。
     *
     * <p>書かれた項目そのものは<b>1 回分</b>である。表なら添字で 1 つに絞られている。
     * 配下の表はすべての回を初期化する。
     *
     * @param item       初期化する項目。集団項目でも基本項目でもよい
     * @param withFiller {@code FILLER} も初期化するか
     * @param replacing  {@code REPLACING} の指定
     */
    public static Result build(DataItem item, boolean withFiller, List<Replacing> replacing,
                               CodePage codePage) {
        InitializeImage builder = new InitializeImage(codePage, withFiller, replacing);
        // 書かれた項目そのものは 1 回分である。表なら添字で 1 つに絞られている
        int length = item.length();
        byte[] image = new byte[length];
        boolean[] written = new boolean[length];
        builder.fillOnce(item, 0, image, written);
        return new Result(runsOf(image, written), List.copyOf(builder.diagnostics));
    }

    /** 印の付いた部分を、連続するかたまりへまとめる。 */
    private static List<Run> runsOf(byte[] image, boolean[] written) {
        List<Run> runs = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= written.length; i++) {
            boolean on = i < written.length && written[i];
            if (on && start < 0) {
                start = i;
            } else if (!on && start >= 0) {
                byte[] bytes = new byte[i - start];
                System.arraycopy(image, start, bytes, 0, bytes.length);
                runs.add(new Run(start, bytes));
                start = -1;
            }
        }
        return List.copyOf(runs);
    }

    /**
     * 項目を辿って、書き込む場所と値を決める。
     *
     * <p>反復のある項目は<b>すべての回</b>を初期化する。
     *
     * @param at 項目の先頭からの位置
     */
    private void fill(DataItem item, int at, byte[] image, boolean[] written) {
        if (item.redefinesName() != null) {
            // 重ねた項目は初期化しない。重ねる先が同じ場所を持っている
            return;
        }
        int one = item.length();
        for (int i = 0; i < item.occurs(); i++) {
            fillOnce(item, at + i * one, image, written);
        }
    }

    private void fillOnce(DataItem item, int at, byte[] image, boolean[] written) {
        if (!item.isElementary()) {
            for (DataItem child : item.children()) {
                fill(child, at + child.offset() - item.offset(), image, written);
            }
            return;
        }
        if (item.name() == null && !withFiller) {
            // FILLER は初期化の対象外である
            return;
        }
        LiteralValue value = valueFor(item);
        if (value == null) {
            return;
        }
        writeElementary(item, value, at, image, written);
    }

    /**
     * 基本項目に入る値。
     *
     * <p>{@code REPLACING} に当たればその値、当たらなければ<b>分類ごとの既定</b>である。
     * 数値と数字編集にはゼロ、それ以外には空白が入る。
     */
    private LiteralValue valueFor(DataItem item) {
        DataCategory category = DataCategory.of(item);
        for (Replacing rule : replacing) {
            if (rule.category().matches(category)) {
                return rule.value();
            }
        }
        if (replacing.isEmpty()) {
            return defaultFor(category);
        }
        // REPLACING を書いたら、当たらなかった項目は変えない
        return null;
    }

    private static LiteralValue defaultFor(DataCategory category) {
        if (category == DataCategory.GROUP) {
            return null;
        }
        return new LiteralValue.Figure(category.isNumeric()
                || category == DataCategory.NUMERIC_EDITED
                ? LiteralValue.FigurativeConstant.ZERO
                : LiteralValue.FigurativeConstant.SPACE);
    }

    /**
     * 基本項目 1 個へ書き込む。
     *
     * <p>実際に {@link Ops} を呼ぶ。ゼロを詰めるといっても、パック 10 進なら {@code 000C}、
     * 数字編集項目なら編集した結果になる。コンパイラが独自に組み立てると、
     * 実行時に同じ値を転記したときとずれる。
     */
    private void writeElementary(DataItem item, LiteralValue value, int at, byte[] image,
                                 boolean[] written) {
        int length = item.length();
        if (at + length > image.length) {
            return;
        }
        Storage scratch = Storage.allocate(length);
        try {
            DataCategory category = DataCategory.of(item);
            if (category == DataCategory.NUMERIC_EDITED) {
                Ops.moveNumericEdited(numberOf(item, value), item.picture(), scratch, 0, codePage);
            } else if (category.isNumeric()) {
                Ops.moveNumeric(numberOf(item, value), numericItemOf(item), scratch, 0);
            } else {
                Ops.moveAlphanumeric(textOf(value, length), scratch, 0, length,
                        item.justified(), codePage);
            }
        } catch (RuntimeException e) {
            report(item.origin(), "cannot initialize " + describe(item) + ": " + e.getMessage());
            return;
        }
        System.arraycopy(scratch.array(), 0, image, at, length);
        for (int i = 0; i < length; i++) {
            written[at + i] = true;
        }
    }

    private NumericItem numericItemOf(DataItem item) {
        NumericItem descriptor = NumericItem.of(item.picture().source(),
                item.usage() == null ? Usage.DISPLAY : item.usage());
        return item.signPosition() == SignPosition.UNSIGNED
                ? descriptor
                : descriptor.withSignPosition(item.signPosition());
    }

    /** 数値項目へ入れる値。 */
    private Decimal numberOf(DataItem item, LiteralValue value) {
        int scale = item.picture() == null ? 0 : item.picture().scale();
        if (value instanceof LiteralValue.Number number) {
            return number.value();
        }
        if (value instanceof LiteralValue.Figure figure
                && figure.constant() == LiteralValue.FigurativeConstant.ZERO) {
            return Decimal.zero(scale);
        }
        throw new IllegalArgumentException("a numeric item requires a numeric value");
    }

    /** 英数字項目へ入れるバイト列。図形定数は項目の長さいっぱいまで埋める。 */
    private byte[] textOf(LiteralValue value, int length) {
        if (value instanceof LiteralValue.Text text) {
            return codePage.encode(text.text());
        }
        if (value instanceof LiteralValue.Number number) {
            return codePage.encode(number.value().toBigDecimal().toPlainString());
        }
        byte[] out = new byte[length];
        if (value instanceof LiteralValue.Repeated repeated) {
            byte[] unit = codePage.encode(repeated.text());
            for (int i = 0; i < length; i++) {
                out[i] = unit[i % unit.length];
            }
            return out;
        }
        Arrays.fill(out, figureByte(((LiteralValue.Figure) value).constant()));
        return out;
    }

    private byte figureByte(LiteralValue.FigurativeConstant constant) {
        return switch (constant) {
            case ZERO -> codePage.digit(0);
            case SPACE -> codePage.space();
            case HIGH_VALUE -> (byte) 0xFF;
            case LOW_VALUE, NULL -> (byte) 0x00;
            case QUOTE -> codePage.encode("\"")[0];
        };
    }

    private void report(Origin origin, String message) {
        diagnostics.add(new Diagnostic(origin, message));
    }

    private static String describe(DataItem item) {
        return item.name() == null ? "FILLER" : item.name();
    }
}
