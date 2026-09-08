package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.Origin;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.item.FloatingItem;
import dev.cobolonjava.runtime.item.NumericItem;
import dev.cobolonjava.runtime.item.Usage;
import dev.cobolonjava.runtime.picture.Picture;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code VALUE} 句から記憶域の初期イメージを作る (要件 FR-013)。
 *
 * <p>符号化はランタイムに任せる。数値は {@link NumericItem#encode}、
 * 浮動小数点は {@link FloatingItem#encode}、文字は {@link CodePage} である。
 * コンパイラが独自にバイトを組み立てると、実行時に同じ値を入れたときとずれる。
 *
 * <h2>指定のない場所は空白で埋める</h2>
 * <p>規格は {@code VALUE} のない作業場所の初期値を<b>未定義</b>としている。
 * 参照実装が何で埋めるかは確認できていないため、空白を既定とし、
 * 埋める文字を差し替えられるようにしてある (暫定判断 P-025)。
 *
 * <h2>REDEFINES で重ねた項目は初期化しない</h2>
 * <p>COBOL は {@code REDEFINES} の中に {@code VALUE} を書くことを禁じている。
 * 重ねた項目を素通しで書くと、<b>重ねる先の初期値を空白で塗り潰してしまう</b>。
 * したがって重ねた側は飛ばす。
 */
public final class InitialImage {

    private final CodePage codePage;
    private final char quoteCharacter;
    private final byte fill;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private InitialImage(CodePage codePage, char quoteCharacter, byte fill) {
        this.codePage = codePage;
        this.quoteCharacter = quoteCharacter;
        this.fill = fill;
    }

    /**
     * 01 レベル 1 個分の初期イメージ。
     *
     * @param item  01 レベルまたは独立項目
     * @param bytes その項目の記憶域の初期内容
     */
    public record RecordImage(DataItem item, byte[] bytes) {
    }

    /**
     * 組み立ての結果。
     *
     * @param storage プログラムの記憶域の全体。各 01 レベルをその位置へ並べたもの
     */
    public record Result(List<RecordImage> records, byte[] storage, List<Diagnostic> diagnostics) {

        public boolean succeeded() {
            return diagnostics.isEmpty();
        }

        /** 名前で初期イメージを探す。 */
        public byte[] imageOf(String name) {
            for (RecordImage image : records) {
                if (name.equalsIgnoreCase(image.item().name())) {
                    return image.bytes();
                }
            }
            throw new IllegalArgumentException("no such record: " + name);
        }
    }

    /** 既定 (IBM-1047、図形定数 {@code QUOTE} は {@code "}、空白で充填) で組み立てる。 */
    public static Result build(DataLayout layout) {
        return build(layout, CodePages.DEFAULT, '"');
    }

    /**
     * 初期イメージを組み立てる。
     *
     * @param quoteCharacter 図形定数 {@code QUOTE} が表す文字。{@code APOST} 指定では {@code '}
     */
    public static Result build(DataLayout layout, CodePage codePage, char quoteCharacter) {
        InitialImage builder = new InitialImage(codePage, quoteCharacter, codePage.space());
        List<RecordImage> images = new ArrayList<>();
        byte[] storage = new byte[layout.totalLength()];
        Arrays.fill(storage, codePage.space());
        for (DataItem record : layout.records()) {
            if (record.section() == DataSection.LINKAGE) {
                // 連絡節の項目は記憶域を持たない。初期値を書く先がない
                builder.checkNoInitialValue(record);
                continue;
            }
            byte[] image = builder.repeat(builder.imageOf(record), record);
            images.add(new RecordImage(record, image));
            // 01 レベルの REDEFINES は同じ位置に重なる。書いた順に上書きされる
            System.arraycopy(image, 0, storage, record.base(),
                    Math.min(image.length, storage.length - record.base()));
        }
        return new Result(List.copyOf(images), storage, List.copyOf(builder.diagnostics));
    }

    /**
     * 連絡節に {@code VALUE} が書かれていないか確かめる。
     *
     * <p>初期化する先がないので、書かれていれば書き間違いである。規格も禁じている
     * (88 レベルの条件名は別で、これは記憶域を占めない)。
     */
    private void checkNoInitialValue(DataItem item) {
        if (hasInitialValue(item)) {
            report(item.origin(), "VALUE is not allowed in the LINKAGE SECTION: "
                    + describe(item));
        }
        item.children().forEach(this::checkNoInitialValue);
    }

    /** 項目 1 回分のイメージ。 */
    private byte[] imageOf(DataItem item) {
        byte[] image = new byte[item.length()];
        Arrays.fill(image, fill);
        if (item.isElementary()) {
            writeElementary(image, item);
            return image;
        }
        for (DataItem child : item.children()) {
            if (child.isAlias()) {
                // 66 レベルは記憶域を持たない。書けば名前を付けた先の初期値を消してしまう
                continue;
            }
            if (child.redefinesName() != null) {
                if (hasInitialValue(child)) {
                    report(child.origin(), "VALUE is not allowed in a REDEFINES item: "
                            + describe(child));
                }
                continue;
            }
            byte[] childImage = repeat(imageOf(child), child);
            int at = child.offset() - item.offset();
            if (at + childImage.length <= image.length) {
                System.arraycopy(childImage, 0, image, at, childImage.length);
            }
        }
        if (item.initialValue() != null) {
            // 群項目の VALUE は中身を英数字として一括で埋める。下位の項目を書いたあとに
            // 上書きする — 群に VALUE を書いたなら、下位に VALUE を書くことは許されない
            writeText(image, item, item.initialValue(), false);
        }
        return image;
    }

    /** 反復のある項目は 1 回分を繰り返す。{@code VALUE} はすべての反復に効く。 */
    private byte[] repeat(byte[] one, DataItem item) {
        if (item.occurs() == 1) {
            return one;
        }
        byte[] all = new byte[one.length * item.occurs()];
        for (int i = 0; i < item.occurs(); i++) {
            System.arraycopy(one, 0, all, i * one.length, one.length);
        }
        return all;
    }

    private static boolean hasInitialValue(DataItem item) {
        if (item.initialValue() != null) {
            return true;
        }
        for (DataItem child : item.children()) {
            if (hasInitialValue(child)) {
                return true;
            }
        }
        return false;
    }

    // ---- 基本項目 ----

    private void writeElementary(byte[] image, DataItem item) {
        LiteralValue value = item.initialValue();
        if (value == null) {
            return;
        }
        Usage usage = item.usage();
        if (usage != null && usage.isFloatingPoint()) {
            writeFloating(image, item, value, usage);
            return;
        }
        Picture picture = item.picture();
        if (picture == null) {
            return;
        }
        if (picture.isNumeric()) {
            writeNumeric(image, item, value, picture, usage == null ? Usage.DISPLAY : usage);
            return;
        }
        writeText(image, item, value, item.justified());
    }

    private void writeNumeric(byte[] image, DataItem item, LiteralValue value,
                              Picture picture, Usage usage) {
        Decimal number = numberOf(item, value, picture.scale());
        if (number == null) {
            return;
        }
        try {
            NumericItem descriptor = NumericItem.of(picture.source(), usage);
            if (item.signPosition() != SignPosition.UNSIGNED) {
                descriptor = descriptor.withSignPosition(item.signPosition());
            }
            if (!descriptor.fits(number)) {
                // 黙って上位桁を落とすと、宣言と初期値が食い違ったまま動き出す
                report(item.origin(), "VALUE does not fit in " + describe(item) + ": " + number);
                return;
            }
            byte[] encoded = descriptor.encode(number);
            System.arraycopy(encoded, 0, image, 0, Math.min(encoded.length, image.length));
        } catch (RuntimeException e) {
            report(item.origin(), "cannot encode VALUE for " + describe(item) + ": " + e.getMessage());
        }
    }

    private Decimal numberOf(DataItem item, LiteralValue value, int scale) {
        if (value instanceof LiteralValue.Number number) {
            return number.value();
        }
        if (value instanceof LiteralValue.Figure figure
                && figure.constant() == LiteralValue.FigurativeConstant.ZERO) {
            return Decimal.zero(Math.max(scale, 0));
        }
        report(item.origin(), "a numeric item takes a numeric VALUE: " + describe(item));
        return null;
    }

    private void writeFloating(byte[] image, DataItem item, LiteralValue value, Usage usage) {
        Decimal number = numberOf(item, value, 0);
        if (number == null) {
            return;
        }
        FloatingItem descriptor = usage == Usage.COMP_1 ? FloatingItem.comp1() : FloatingItem.comp2();
        byte[] encoded = descriptor.encode(number.toBigDecimal().stripTrailingZeros());
        System.arraycopy(encoded, 0, image, 0, Math.min(encoded.length, image.length));
    }

    /** 英数字・英字・編集項目、および群項目への書き込み。 */
    private void writeText(byte[] image, DataItem item, LiteralValue value, boolean justified) {
        byte[] bytes = textBytes(item, value, image.length);
        if (bytes == null) {
            return;
        }
        if (bytes.length > image.length) {
            report(item.origin(), "VALUE is longer than " + describe(item)
                    + " (" + bytes.length + " > " + image.length + ")");
            return;
        }
        // 右寄せは JUSTIFIED RIGHT のときだけ。既定は左寄せで残りは空白
        int at = justified ? image.length - bytes.length : 0;
        System.arraycopy(bytes, 0, image, at, bytes.length);
    }

    private byte[] textBytes(DataItem item, LiteralValue value, int length) {
        if (value instanceof LiteralValue.Text text) {
            return codePage.encode(text.text());
        }
        if (value instanceof LiteralValue.Repeated repeated) {
            return repeatToLength(codePage.encode(repeated.text()), length);
        }
        if (value instanceof LiteralValue.Figure figure) {
            byte[] filled = new byte[length];
            Arrays.fill(filled, figureByte(figure.constant()));
            return filled;
        }
        report(item.origin(), "an alphanumeric item takes an alphanumeric VALUE: " + describe(item));
        return null;
    }

    /** {@code ALL '定数'} は項目の長さいっぱいまで繰り返し、途中で切る。 */
    private static byte[] repeatToLength(byte[] unit, int length) {
        if (unit.length == 0) {
            return new byte[length];
        }
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = unit[i % unit.length];
        }
        return out;
    }

    private byte figureByte(LiteralValue.FigurativeConstant constant) {
        return switch (constant) {
            case ZERO -> codePage.digit(0);
            case SPACE -> codePage.space();
            case HIGH_VALUE -> (byte) 0xFF;
            case LOW_VALUE, NULL -> (byte) 0x00;
            case QUOTE -> codePage.ch(quoteCharacter);
        };
    }

    private static String describe(DataItem item) {
        return item.name() == null ? "FILLER" : item.name();
    }

    private void report(Origin origin, String message) {
        diagnostics.add(new Diagnostic(origin, message));
    }
}
