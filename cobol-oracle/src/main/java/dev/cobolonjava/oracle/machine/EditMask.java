package dev.cobolonjava.oracle.machine;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.picture.Picture;
import dev.cobolonjava.runtime.picture.Picture.Cell;
import dev.cobolonjava.runtime.picture.Picture.Kind;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * PICTURE から {@code ED} 命令の編集マスクを生成する。
 *
 * <p>これは本来<b>コンパイラ側の処理</b>である。参照実装は数字編集項目への転記を
 * {@code ED} 命令 1 個に落としており、その際に PICTURE からこのマスクを組み立てている。
 * ここに置いているのは、まず {@code NumericEditor} を V2 で裏付けるために必要だからであり、
 * コンパイラ (P0-b) の実装時にはそちらへ移す。
 *
 * <h2>ED 命令の意味論 (Hercules 上での実測により確定)</h2>
 * <ul>
 *   <li>出力の長さはマスクの長さに等しい</li>
 *   <li>マスクの先頭バイトが<b>充填文字</b>である</li>
 *   <li>{@code 0x20} 桁選択子 — 数字を 1 桁取り出す。有意性が立っておらず数字が 0 なら充填文字を出す</li>
 *   <li>{@code 0x21} 有意性開始子 — 桁選択子と同じだが、処理後に必ず有意性を立てる</li>
 *   <li>その他 — メッセージ文字。有意性が立っていればそのまま、いなければ充填文字を出す</li>
 *   <li>数字の符号が正であれば、末尾で有意性が落ちる。これにより {@code CR} / {@code DB} が
 *       正数のときだけ充填文字に置き換わる</li>
 *   <li>ソースの符号ニブルに達した時点で処理が止まる。<b>マスクの桁位置の数と
 *       ソースの数字ニブルの数は一致していなければならない</b></li>
 * </ul>
 *
 * <h2>結果は COBOL の項目より 1 バイト長い</h2>
 * <p>マスクの先頭バイトは充填文字であり、COBOL の数字編集項目の一部ではない。
 * したがって<b>編集項目の内容は {@code ED} の結果のオフセット 1 以降</b>である。
 * 参照実装も、作業域に対して {@code ED} を実行したうえで作業域 + 1 から項目へ転記している。
 * {@link #editedFieldLength} はこの関係を表す。
 *
 * <h2>有意性開始子の置き場所</h2>
 * <p>実測により、有意性開始子は「数字が 0 かつ有意性が立っていなければ充填文字を出す」ため、
 * <b>常に表示すべき桁そのものに置いてはならない</b>。正しくは、<b>常に表示する最初の位置の
 * ひとつ手前の抑制位置</b>に置く。例えば {@code ZZ9.99} のマスクは
 * {@code 40 20 21 20 4B 20 20} であり、{@code 40 20 20 21 4B 20 20} ではない。
 * 後者では値 0.45 が {@code "    .45"} となり、COBOL が要求する {@code "  0.45"} にならない。
 */
public final class EditMask {

    /** 桁選択子。 */
    public static final byte DIGIT_SELECTOR = 0x20;
    /** 有意性開始子。 */
    public static final byte SIGNIFICANCE_STARTER = 0x21;
    /** フィールド分離子。 */
    public static final byte FIELD_SEPARATOR = 0x22;

    private EditMask() {
    }

    /**
     * 数字編集項目の PICTURE から編集マスクを生成する。
     *
     * @throws UnsupportedOperationException 平の {@code ED} 命令では表現できない PICTURE の場合。
     *         浮動挿入 ({@code $$$} など)、固定符号 ({@code +} {@code -})、および
     *         常に表示する桁を 1 つも持たない PICTURE ({@code ZZZZ} など) が該当する。
     *         後者は「全桁が抑制対象で値がゼロなら項目全体を抑制する」という COBOL 固有の規則を
     *         持ち、参照実装も {@code ED} 単独では実現していないと考えられる。
     */
    public static byte[] forPicture(Picture picture, CodePage codePage) {
        return forPicture(picture, codePage, picture.digits());
    }

    /**
     * ソースの数字ニブル数が PICTURE の桁数より多い場合に対応したマスクを生成する。
     *
     * <p>パック10進項目の数字ニブル数は {@code 2 x バイト長 - 1} であり、PICTURE の桁数が
     * <b>偶数のときは 1 個多くなる</b> (先頭の未使用ニブル)。{@code ED} はマスクの桁位置の数と
     * ソースの数字ニブル数が一致していないと正しく動かないため、余分なぶんだけ
     * <b>充填文字の直後に桁選択子を追加する</b>。
     *
     * <p>追加した桁位置は常に 0 を読み、有意性が立っていないので充填文字を出す。
     * したがって編集項目の内容は {@link #editedFieldOffset} 以降になる。
     *
     * @param sourceDigitNibbles ソースのパック10進項目が持つ数字ニブルの数
     */
    public static byte[] forPicture(Picture picture, CodePage codePage, int sourceDigitNibbles) {
        int extra = sourceDigitNibbles - picture.digits();
        if (extra < 0) {
            throw new IllegalArgumentException(
                    "source has fewer digit nibbles (" + sourceDigitNibbles
                            + ") than the picture requires (" + picture.digits() + ")");
        }
        byte[] base = buildMask(picture, codePage);
        if (extra == 0) {
            return base;
        }
        byte[] out = new byte[base.length + extra];
        out[0] = base[0];
        java.util.Arrays.fill(out, 1, 1 + extra, DIGIT_SELECTOR);
        System.arraycopy(base, 1, out, 1 + extra, base.length - 1);
        return out;
    }

    /**
     * 編集項目の内容がマスクの結果のどこから始まるか。
     * 先頭の充填文字と、桁合わせのために追加した桁選択子のぶんだけ後ろになる。
     */
    public static int editedFieldOffset(Picture picture, int sourceDigitNibbles) {
        return 1 + (sourceDigitNibbles - picture.digits());
    }

    private static byte[] buildMask(Picture picture, CodePage codePage) {
        if (picture.category() != Picture.Category.NUMERIC_EDITED) {
            throw new IllegalArgumentException("not a numeric-edited picture: " + picture);
        }
        List<Cell> cells = picture.cells();
        for (Cell c : cells) {
            if (c.kind() == Kind.SIGN) {
                throw new UnsupportedOperationException(
                        "a fixed sign cannot be expressed by an ED mask: " + picture.source());
            }
        }

        int firstAlwaysPrint = indexOfFirstAlwaysPrinting(cells);
        if (firstAlwaysPrint < 0) {
            throw new UnsupportedOperationException(
                    "a picture without any always-printing digit position is not expressible by a plain ED mask: "
                            + picture.source());
        }
        int significanceStarter = lastSuppressibleBefore(cells, firstAlwaysPrint);
        if (significanceStarter < 0) {
            throw new UnsupportedOperationException(
                    "a picture whose leading digit position is not suppressible is not expressible by "
                            + "a plain ED mask (the compiler uses UNPK plus insertion instead): "
                            + picture.source());
        }

        byte fill = fillCharacter(cells, codePage);
        int firstFloat = indexOfFirstFloat(cells);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(fill);
        for (int i = 0; i < cells.size(); i++) {
            Cell c = cells.get(i);
            switch (c.kind()) {
                case SUPPRESS -> out.write(i == significanceStarter ? SIGNIFICANCE_STARTER : DIGIT_SELECTOR);
                case FLOAT -> {
                    if (i == firstFloat) {
                        // 浮動挿入の先頭位置は記号のための予備であり、数字を消費しない。
                        // マスク上はメッセージ文字 (充填文字と同じ値) にしておき、
                        // EDMK が返した位置に応じて後から記号を書き込む。
                        out.write(fill);
                    } else {
                        out.write(i == significanceStarter ? SIGNIFICANCE_STARTER : DIGIT_SELECTOR);
                    }
                }
                case DIGIT -> out.write(DIGIT_SELECTOR);
                case INSERT -> out.write(codePage.ch(c.literal()));
                case DECIMAL_POINT -> out.write(codePage.ch('.'));
                case CR -> {
                    out.write(codePage.ch('C'));
                    out.write(codePage.ch('R'));
                }
                case DB -> {
                    out.write(codePage.ch('D'));
                    out.write(codePage.ch('B'));
                }
                default -> throw new UnsupportedOperationException(
                        "cell kind " + c.kind() + " is not expressible by a plain ED mask: "
                                + picture.source());
            }
        }
        return out.toByteArray();
    }

    /**
     * マスクの長さから、COBOL の数字編集項目のバイト長を求める。
     * マスクの先頭は充填文字であり項目には含まれないため、1 バイト短い。
     */
    public static int editedFieldLength(byte[] mask) {
        return mask.length - 1;
    }

    /** ソースの数字ニブル数を考慮した編集項目のバイト長。 */
    public static int editedFieldLength(byte[] mask, Picture picture, int sourceDigitNibbles) {
        return mask.length - editedFieldOffset(picture, sourceDigitNibbles);
    }

    /** マスクが要求する数字の桁数。ソースのパック10進項目の数字ニブル数と一致していなければならない。 */
    public static int digitPositions(byte[] mask) {
        int n = 0;
        for (byte b : mask) {
            if (b == DIGIT_SELECTOR || b == SIGNIFICANCE_STARTER) {
                n++;
            }
        }
        return n;
    }

    /** 充填文字。{@code *} による小切手保護があればアスタリスク、なければ空白。 */
    private static byte fillCharacter(List<Cell> cells, CodePage codePage) {
        for (Cell c : cells) {
            if (c.kind() == Kind.SUPPRESS && c.literal() == '*') {
                return codePage.ch('*');
            }
        }
        return codePage.space();
    }

    /** 常に表示される最初の位置。数字位置 {@code 9} を優先し、なければ小数点。 */
    private static int indexOfFirstAlwaysPrinting(List<Cell> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).kind() == Kind.DIGIT) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 抑制の対象になりうる最後のセル。有意性開始子はここに置く。
     *
     * <p>浮動挿入では、先頭の 1 個は記号のための予備で数字を消費しないため対象から外す。
     */
    private static int lastSuppressibleBefore(List<Cell> cells, int limit) {
        int firstFloat = indexOfFirstFloat(cells);
        int last = -1;
        for (int i = 0; i < limit; i++) {
            Kind k = cells.get(i).kind();
            if (k == Kind.SUPPRESS || (k == Kind.FLOAT && i != firstFloat)) {
                last = i;
            }
        }
        return last;
    }

    private static int indexOfFirstFloat(List<Cell> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).kind() == Kind.FLOAT) {
                return i;
            }
        }
        return -1;
    }

    /** この PICTURE が浮動挿入を使うかどうか。使う場合は {@code EDMK} が必要になる。 */
    public static boolean usesFloatingInsertion(Picture picture) {
        return indexOfFirstFloat(picture.cells()) >= 0;
    }

    /**
     * 有意な数字が 1 つも見つからなかった場合に備えて、{@code EDMK} の前に汎用レジスタへ
     * 入れておくアドレスのオフセット。
     *
     * <p>{@code EDMK} は有意な数字が見つかったときだけレジスタを更新する。値がゼロの場合は
     * 更新されないため、あらかじめ「最初に表示される数字の位置」を入れておく必要がある。
     * それは有意性開始子の 1 つ後ろである。
     */
    public static int significanceStarterSuccessorOffset(Picture picture, int sourceDigitNibbles) {
        List<Cell> cells = picture.cells();
        int firstAlwaysPrint = indexOfFirstAlwaysPrinting(cells);
        int significanceStarter = lastSuppressibleBefore(cells, firstAlwaysPrint);
        int extra = sourceDigitNibbles - picture.digits();
        // マスク上の位置は「充填文字 1 バイト + 桁合わせの追加分 + セル番号」
        return 1 + extra + significanceStarter + 1;
    }

    /**
     * 浮動挿入で書き込む記号。
     *
     * <p>{@code $} は常に通貨記号、{@code +} は符号に応じて {@code +} か {@code -}、
     * {@code -} は負のときだけ {@code -} で正のときは空白になる。
     */
    public static char floatingCharacter(Picture picture, boolean negative) {
        int firstFloat = indexOfFirstFloat(picture.cells());
        if (firstFloat < 0) {
            throw new IllegalArgumentException("picture has no floating insertion: " + picture.source());
        }
        char symbol = picture.cells().get(firstFloat).literal();
        return switch (symbol) {
            case '+' -> negative ? '-' : '+';
            case '-' -> negative ? '-' : ' ';
            default -> symbol;
        };
    }
}
