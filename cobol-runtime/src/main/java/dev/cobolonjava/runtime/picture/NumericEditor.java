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
                    if (!significant && i < suppressEnd) {
                        suppressed[i] = true;
                        text[i] = String.valueOf(fill);
                    } else {
                        text[i] = String.valueOf(c.literal());
                    }
                }
                case DECIMAL_POINT -> {
                    text[i] = ".";
                    significant = true;
                }
                case SIGN -> text[i] = String.valueOf(signChar(c.literal(), negative));
                case CR -> text[i] = negative ? "CR" : "  ";
                case DB -> text[i] = negative ? "DB" : "  ";
                default -> throw new IllegalStateException(
                        "unexpected cell kind in numeric editing: " + c.kind());
            }
        }

        // 浮動挿入記号は、抑制された浮動位置のうち最も右に置く
        if (firstFloat >= 0) {
            int pos = -1;
            for (int i = 0; i < n; i++) {
                Cell c = cells.get(i);
                if (c.kind() == Kind.FLOAT && suppressed[i]) {
                    pos = i;
                }
            }
            if (pos >= 0) {
                text[pos] = String.valueOf(signChar(cells.get(pos).literal(), negative));
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

    /** 抑制の対象となる最後のセルの位置。抑制は小数点を越えない。 */
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
