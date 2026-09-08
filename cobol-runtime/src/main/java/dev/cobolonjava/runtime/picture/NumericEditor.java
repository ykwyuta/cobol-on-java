package dev.cobolonjava.runtime.picture;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.picture.Picture.Cell;
import dev.cobolonjava.runtime.picture.Picture.Kind;
import java.util.Arrays;
import java.util.List;

/**
 * 数値編集 (要件 FR-030)。数値を数字編集項目の PICTURE に従ってバイト列へ変換する。
 *
 * <p>ホストではこの処理が {@code ED} / {@code EDMK} 命令として実現されている。
 * 出力バイト列は帳票やファイルにそのまま現れるため、L3 互換の対象である。
 */
public final class NumericEditor {

    private NumericEditor() {
    }

    /**
     * 値を数字編集項目のバイト列へ変換する。
     *
     * @throws IllegalArgumentException PICTURE が数字編集項目でない場合
     */
    public static byte[] edit(Decimal value, Picture picture, CodePage codePage) {
        if (picture.category() != Picture.Category.NUMERIC_EDITED) {
            throw new IllegalArgumentException("not a numeric-edited picture: " + picture);
        }
        List<Cell> cells = picture.cells();
        int size = picture.size();

        char fill = suppressionFill(cells);

        if (picture.blankWhenZero() && value.isZero()) {
            return spaces(size, codePage);
        }

        // すべての数字位置が抑制可能で値がゼロなら、項目全体が抑制される。
        // Z の場合は空白、* の場合は小数点を残してすべてアスタリスクになる。
        if (value.isZero() && allDigitPositionsSuppressible(cells)) {
            if (fill == ' ') {
                return spaces(size, codePage);
            }
            char[] all = new char[size];
            Arrays.fill(all, '*');
            int pos = 0;
            for (Cell c : cells) {
                if (c.kind() == Kind.DECIMAL_POINT) {
                    all[pos] = '.';
                }
                pos += c.width();
            }
            return encode(all, codePage);
        }

        String digits = value.storedDigits(picture.digits(), picture.scale());
        boolean negative = value.signum() < 0;

        int n = cells.size();
        String[] text = new String[n];
        boolean[] suppressed = new boolean[n];

        int decimalPointIndex = indexOf(cells, Kind.DECIMAL_POINT);
        int suppressEnd = lastSuppressibleBefore(cells, decimalPointIndex);
        int firstFloat = indexOf(cells, Kind.FLOAT);
        // 抑制される並びが<b>どこから始まるか</b>。その左にある挿入文字は固定挿入で
        // あり、抑制されない。$**.99 の $ がそれである (NC170A の MPY-TEST-F2-20)
        int firstSuppressible = firstSuppressible(cells);

        int di = 0;
        boolean significant = false;
        for (int i = 0; i < n; i++) {
            Cell c = cells.get(i);
            switch (c.kind()) {
                case DIGIT -> {
                    text[i] = String.valueOf(digits.charAt(di++));
                    significant = true;
                }
                case SUPPRESS -> {
                    char d = digits.charAt(di++);
                    if (!significant && d == '0' && i <= suppressEnd) {
                        suppressed[i] = true;
                        text[i] = String.valueOf(fill);
                    } else {
                        significant = true;
                        text[i] = String.valueOf(d);
                    }
                }
                case FLOAT -> {
                    if (i == firstFloat) {
                        // 浮動挿入の先頭位置は記号のための予備であり、数字を消費しない
                        suppressed[i] = true;
                        text[i] = String.valueOf(fill);
                    } else {
                        char d = digits.charAt(di++);
                        if (!significant && d == '0' && i <= suppressEnd) {
                            suppressed[i] = true;
                            text[i] = String.valueOf(fill);
                        } else {
                            significant = true;
                            text[i] = String.valueOf(d);
                        }
                    }
                }
                case INSERT -> {
                    if (!significant && i > firstSuppressible && i <= suppressEnd) {
                        suppressed[i] = true;
                        text[i] = String.valueOf(fill);
                    } else {
                        text[i] = String.valueOf(c.literal());
                    }
                }
                case DECIMAL_POINT -> {
                    // 小数点として書かれた文字をそのまま出す。
                    // DECIMAL-POINT IS COMMA ならコンマである
                    text[i] = String.valueOf(c.literal());
                    significant = true;
                }
                case SIGN -> text[i] = String.valueOf(signChar(c.literal(), negative));
                case CR -> text[i] = negative ? "CR" : "  ";
                case DB -> text[i] = negative ? "DB" : "  ";
                default -> throw new IllegalStateException(
                        "unexpected cell kind in numeric editing: " + c.kind());
            }
        }

        // 浮動挿入記号は、抑制された位置のうち最も右に置く。
        // <b>浮動の並びのすぐ右にある挿入文字も置き場になる</b> ($$$,999.99 の コンマ)
        if (firstFloat >= 0) {
            int pos = -1;
            for (int i = firstFloat; i < n && i <= suppressEnd; i++) {
                Kind kind = cells.get(i).kind();
                if (suppressed[i] && (kind == Kind.FLOAT || kind == Kind.INSERT)) {
                    pos = i;
                }
            }
            if (pos >= 0) {
                // 置き場が挿入文字なら、浮動の記号そのものを出す
                char symbol = cells.get(pos).kind() == Kind.FLOAT
                        ? cells.get(pos).literal()
                        : cells.get(firstFloat).literal();
                text[pos] = String.valueOf(signChar(symbol, negative));
            }
        }

        StringBuilder sb = new StringBuilder(size);
        for (String t : text) {
            sb.append(t);
        }
        return encode(sb.toString().toCharArray(), codePage);
    }


    private static char signChar(char symbol, boolean negative) {
        return switch (symbol) {
            case '+' -> negative ? '-' : '+';
            case '-' -> negative ? '-' : ' ';
            default -> symbol;
        };
    }

    private static char suppressionFill(List<Cell> cells) {
        for (Cell c : cells) {
            if (c.kind() == Kind.SUPPRESS && c.literal() == '*') {
                return '*';
            }
        }
        return ' ';
    }

    private static boolean allDigitPositionsSuppressible(List<Cell> cells) {
        boolean any = false;
        for (Cell c : cells) {
            if (c.kind() == Kind.DIGIT) {
                return false;
            }
            if (c.kind() == Kind.SUPPRESS || c.kind() == Kind.FLOAT) {
                any = true;
            }
        }
        return any;
    }

    private static int indexOf(List<Cell> cells, Kind kind) {
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).kind() == kind) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 抑制の対象となる最後のセルの位置。抑制は小数点を越えない。
     *
     * <p><b>抑制の並びのすぐ右にある挿入文字も、抑制の対象である</b>。規格は
     * 「浮動挿入の並びの中、またはそのすぐ右にある単純挿入文字は、その並びの一部である」
     * と決めている。{@code $$$,999.99} に 987.65 を入れると {@code    $987.65} になり、
     * コンマは消えて、その位置に通貨記号が来る (NC105A の EDIT-TEST-F1-124)。
     * 消さないと {@code   $,987.65} になる。
     */
    /**
     * 抑制される並びの先頭の位置。無ければ {@link Integer#MAX_VALUE}。
     *
     * <p>その左にある挿入文字は<b>固定挿入</b>である。規格は「抑制の並びの中、または
     * その右にある挿入文字」だけを抑制すると決めている。
     */
    private static int firstSuppressible(List<Cell> cells) {
        for (int i = 0; i < cells.size(); i++) {
            Kind k = cells.get(i).kind();
            if (k == Kind.SUPPRESS || k == Kind.FLOAT) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static int lastSuppressibleBefore(List<Cell> cells, int decimalPointIndex) {
        int last = -1;
        for (int i = 0; i < cells.size(); i++) {
            if (decimalPointIndex >= 0 && i > decimalPointIndex) {
                break;
            }
            Kind k = cells.get(i).kind();
            if (k == Kind.SUPPRESS || k == Kind.FLOAT) {
                last = i;
            }
        }
        if (last < 0) {
            return last;
        }
        for (int i = last + 1; i < cells.size(); i++) {
            if (decimalPointIndex >= 0 && i >= decimalPointIndex) {
                break;
            }
            if (cells.get(i).kind() != Kind.INSERT) {
                break;
            }
            last = i;
        }
        return last;
    }

    private static byte[] spaces(int size, CodePage codePage) {
        byte[] out = new byte[size];
        Arrays.fill(out, codePage.space());
        return out;
    }

    private static byte[] encode(char[] chars, CodePage codePage) {
        return codePage.encode(new String(chars));
    }
}
