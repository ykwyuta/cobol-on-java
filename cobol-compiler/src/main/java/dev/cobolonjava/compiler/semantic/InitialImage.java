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
    /** 図形定数 {@code HIGH-VALUE} / {@code LOW-VALUE} が表すバイト (要件 FR-054)。 */
    private final byte highValue;
    private final byte lowValue;
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private InitialImage(CodePage codePage, char quoteCharacter, byte fill,
                         byte highValue, byte lowValue) {
        this.codePage = codePage;
        this.quoteCharacter = quoteCharacter;
        this.fill = fill;
        this.highValue = highValue;
        this.lowValue = lowValue;
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
            return !Diagnostic.blocking(diagnostics);
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
     * 照合順序を差し替えたうえで組み立てる (要件 FR-054)。
     *
     * <p>{@code PROGRAM COLLATING SEQUENCE} が書かれていれば、{@code VALUE HIGH-VALUE} の
     * 表すバイトが変わる。<b>並びのいちばん後ろに来る文字</b>だからである。
     */
    public static Result build(DataLayout layout, SpecialNames specialNames) {
        return build(layout, CodePages.DEFAULT, '"',
                specialNames.highValue(), specialNames.lowValue());
    }

    /**
     * 初期イメージを組み立てる。
     *
     * @param quoteCharacter 図形定数 {@code QUOTE} が表す文字。{@code APOST} 指定では {@code '}
     */
    public static Result build(DataLayout layout, CodePage codePage, char quoteCharacter) {
        return build(layout, codePage, quoteCharacter, (byte) 0xFF, (byte) 0x00);
    }

    /**
     * 初期イメージを組み立てる。
     *
     * @param highValue 図形定数 {@code HIGH-VALUE} が表すバイト (要件 FR-054)
     * @param lowValue  図形定数 {@code LOW-VALUE} が表すバイト
     */
    public static Result build(DataLayout layout, CodePage codePage, char quoteCharacter,
                               byte highValue, byte lowValue) {
        InitialImage builder = new InitialImage(codePage, quoteCharacter, codePage.space(),
                highValue, lowValue);
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
            if (record.redefinesName() != null) {
                // 重ねた項目は<b>記憶域へ書かない</b>。重ねる先が同じ場所を持っている。
                // 書くと、値の無い側の空白が<b>重ねる先の初期値を消してしまう</b>
                // (NC116A の「01 AN-00008-X-1 REDEFINES DS-L-00008」で消えていた)。
                // 規格は重ねた項目に VALUE を書くことを禁じているので、書く値も無い
                if (hasInitialValue(record)) {
                    builder.report(record.origin(), "VALUE is not allowed in a REDEFINES item: "
                            + describe(record));
                }
                continue;
            }
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
            writeText(image, item, item.initialValue());
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
        // JUSTIFIED は<b>初期値には効かない</b>。規格がそう決めている
        // (85 規格 JUSTIFIED 句の一般規則 (3))。右へ寄せるのは実行時の転記だけで
        // ある。X(3) JUST VALUE "XY" は "XY " になる (CCVS85 の NC107A)
        writeText(image, item, value);
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
    /**
     * 英数字項目の初期値を書き込む。
     *
     * <p>常に<b>左詰め</b>である。{@code JUSTIFIED} は初期値には効かない —— 規格が
     * そう決めている (85 規格 JUSTIFIED 句の一般規則 (3))。右へ寄せるのは実行時の
     * 転記だけである。{@code X(3) JUST VALUE "XY"} は {@code "XY "} になる。
     */
    private void writeText(byte[] image, DataItem item, LiteralValue value) {
        byte[] bytes = textBytes(item, value, image.length);
        if (bytes == null) {
            return;
        }
        if (bytes.length > image.length) {
            report(item.origin(), "VALUE is longer than " + describe(item)
                    + " (" + bytes.length + " > " + image.length + ")");
            return;
        }
        System.arraycopy(bytes, 0, image, 0, bytes.length);
    }

    private byte[] textBytes(DataItem item, LiteralValue value, int length) {
        if (value instanceof LiteralValue.Text text) {
            return text.bytes(codePage);
        }
        if (value instanceof LiteralValue.Repeated repeated) {
            return repeatToLength(repeated.bytes(codePage), length);
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
            case HIGH_VALUE -> highValue;
            case LOW_VALUE -> lowValue;
            // NULL は「あて先を持たない」を表すものであり、照合順序とは関わらない
            case NULL -> (byte) 0x00;
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
