package dev.cobolonjava.runtime.picture;

import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.picture.Picture.Category;
import dev.cobolonjava.runtime.picture.Picture.Cell;
import dev.cobolonjava.runtime.picture.Picture.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PICTURE 文字列の解析 (要件 FR-030)。
 *
 * <p>桁数・バイト長・小数位置・編集の役割を、参照実装と同じ規則で決定する。
 * 特にバイト長と小数位置は、項目のオフセットと格納バイト列に直結するため L3 互換の対象である。
 */
public final class PictureParser {

    /** 既定の通貨記号。{@code CURRENCY SIGN} 句 (要件 FR-054) で変更できる。 */
    public static final char DEFAULT_CURRENCY = '$';

    private PictureParser() {
    }

    /** 既定の小数点。{@code DECIMAL-POINT IS COMMA} と書けばコンマになる。 */
    public static final char DEFAULT_DECIMAL_POINT = '.';

    public static Picture parse(String source) {
        return parse(source, DEFAULT_CURRENCY);
    }

    public static Picture parse(String source, char currency) {
        return parse(source, currency, DEFAULT_DECIMAL_POINT);
    }

    /**
     * PICTURE を読む (要件 FR-054)。
     *
     * <p>{@code decimalPoint} は小数点として書く文字である。{@code DECIMAL-POINT IS COMMA}
     * と書けばコンマになり、そのとき<b>ピリオドは桁区切り</b>になる。2 つの役目が
     * 入れ替わるだけで、編集の仕組みは変わらない。区切りの文字はセルが覚えているので、
     * 出てくるバイトも入れ替わる。
     */
    public static Picture parse(String source, char currency, char decimalPoint) {
        if (source == null || source.isBlank()) {
            throw new PictureSyntaxException("PICTURE character-string must not be empty");
        }
        List<String> symbols = expand(source.trim().toUpperCase(Locale.ROOT), currency);

        boolean signed = false;
        if (!symbols.isEmpty() && symbols.get(0).equals("S")) {
            signed = true;
            symbols = symbols.subList(1, symbols.size());
        }
        if (symbols.contains("S")) {
            throw new PictureSyntaxException("S may appear only as the first symbol: " + source);
        }

        long floatingCount = countFloating(symbols, currency);
        boolean floatingCurrency = count(symbols, String.valueOf(currency)) >= 2;
        boolean floatingPlus = count(symbols, "+") >= 2;
        boolean floatingMinus = count(symbols, "-") >= 2;

        List<Cell> cells = new ArrayList<>();
        List<Boolean> slotStored = new ArrayList<>();
        int pointIndex = -1;
        int size = 0;
        boolean sawV = false;
        boolean anyAlpha = false;
        boolean anyAlnum = false;
        boolean anyNational = false;
        boolean anyEditing = false;
        boolean anyInsertion = false;
        boolean anyNine = false;
        boolean leadingP = false;
        boolean sawStoredDigit = false;

        for (String sym : symbols) {
            switch (sym) {
                case "9" -> {
                    cells.add(new Cell(Kind.DIGIT, '9', 1));
                    slotStored.add(true);
                    size++;
                    anyNine = true;
                    sawStoredDigit = true;
                }
                case "Z", "*" -> {
                    cells.add(new Cell(Kind.SUPPRESS, sym.charAt(0) == 'Z' ? ' ' : '*', 1));
                    slotStored.add(true);
                    size++;
                    anyEditing = true;
                    sawStoredDigit = true;
                }
                case "P" -> {
                    if (!sawStoredDigit) {
                        leadingP = true;
                    }
                    slotStored.add(false);
                }
                case "V" -> {
                    if (sawV) {
                        throw new PictureSyntaxException("V may appear only once: " + source);
                    }
                    sawV = true;
                    pointIndex = slotStored.size();
                }
                case ".", "," -> {
                    boolean point = sym.charAt(0) == decimalPoint;
                    if (point) {
                        if (pointIndex >= 0) {
                            throw new PictureSyntaxException(
                                    "more than one decimal point: " + source);
                        }
                        pointIndex = slotStored.size();
                        cells.add(new Cell(Kind.DECIMAL_POINT, sym.charAt(0), 1));
                    } else {
                        cells.add(new Cell(Kind.INSERT, sym.charAt(0), 1));
                    }
                    size++;
                    anyEditing = true;
                }
                case "/", "0" -> {
                    cells.add(new Cell(Kind.INSERT, sym.charAt(0), 1));
                    size++;
                    anyEditing = true;
                    anyInsertion = true;
                }
                case "B" -> {
                    cells.add(new Cell(Kind.INSERT, ' ', 1));
                    size++;
                    anyEditing = true;
                    anyInsertion = true;
                }
                case "CR", "DB" -> {
                    cells.add(new Cell(sym.equals("CR") ? Kind.CR : Kind.DB, sym.charAt(0), 2));
                    size += 2;
                    anyEditing = true;
                }
                case "A" -> {
                    cells.add(new Cell(Kind.ALPHA, 'A', 1));
                    size++;
                    anyAlpha = true;
                }
                case "X" -> {
                    cells.add(new Cell(Kind.ALNUM, 'X', 1));
                    size++;
                    anyAlnum = true;
                }
                case "N" -> {
                    cells.add(new Cell(Kind.ALNUM, 'N', 1));
                    size++;
                    anyNational = true;
                }
                default -> {
                    char c = sym.charAt(0);
                    if (c == currency) {
                        addSignOrCurrency(cells, slotStored, c, floatingCurrency);
                        size++;
                        anyEditing = true;
                        if (floatingCurrency) {
                            sawStoredDigit = true;
                        }
                    } else if (c == '+' || c == '-') {
                        boolean floating = (c == '+') ? floatingPlus : floatingMinus;
                        addSignOrCurrency(cells, slotStored, c, floating);
                        size++;
                        anyEditing = true;
                        if (floating) {
                            sawStoredDigit = true;
                        }
                    } else {
                        throw new PictureSyntaxException(
                                "unsupported PICTURE symbol '" + sym + "' in: " + source);
                    }
                }
            }
        }

        // 浮動挿入の先頭 1 個は数字を消費しない
        int firstFloatSlot = -1;
        int slotIdx = 0;
        for (Cell cell : cells) {
            if (cell.kind() == Kind.DIGIT || cell.kind() == Kind.SUPPRESS || cell.kind() == Kind.FLOAT) {
                if (cell.kind() == Kind.FLOAT && firstFloatSlot < 0) {
                    firstFloatSlot = slotIdx;
                }
                slotIdx++;
            }
        }
        if (firstFloatSlot >= 0) {
            int seen = -1;
            for (int i = 0; i < slotStored.size(); i++) {
                if (slotStored.get(i)) {
                    seen++;
                    if (seen == firstFloatSlot) {
                        slotStored.set(i, false);
                        break;
                    }
                }
            }
        }

        if (leadingP && !sawV) {
            pointIndex = 0;
        }
        if (pointIndex < 0) {
            pointIndex = slotStored.size();
        }

        int digits = 0;
        int lastStoredSlot = -1;
        for (int i = 0; i < slotStored.size(); i++) {
            if (slotStored.get(i)) {
                digits++;
                lastStoredSlot = i;
            }
        }
        int scale = lastStoredSlot < 0 ? 0 : lastStoredSlot + 1 - pointIndex;

        // COBOL の分類規則:
        //   英字項目       — A だけからなる
        //   英数字編集項目 — A または X を 1 個以上含み、かつ B / 0 / / を 1 個以上含む
        //   英数字項目     — A または X を含み、挿入文字を含まない
        //   数字編集項目   — 数字位置と編集記号を含む
        //   数字項目       — 9 S V P だけからなる
        // 国字項目は N だけからなる。国字編集 (N と B 0 /) と国字の数字 (USAGE NATIONAL の 9) は
        // まだ持たない。混ぜた PICTURE を英数字として読むと、1 文字 2 バイトの長さを取り違える
        if (anyNational) {
            if (anyAlnum || anyAlpha || anyEditing || anyNine || sawV || signed
                    || slotStored.size() > 0) {
                throw new PictureSyntaxException("national-edited PICTUREs (N mixed with other"
                        + " symbols) are not supported yet: " + source);
            }
            return new Picture(source, String.join("", symbols), Category.NATIONAL, size, 0, 0,
                    SignPosition.UNSIGNED, false, cells);
        }
        Category category;
        if (anyAlnum || anyAlpha) {
            if (anyInsertion) {
                category = Category.ALPHANUMERIC_EDITED;
            } else if (anyAlpha && !anyAlnum && !anyNine) {
                category = Category.ALPHABETIC;
            } else {
                category = Category.ALPHANUMERIC;
            }
        } else if (anyEditing) {
            category = Category.NUMERIC_EDITED;
        } else {
            category = Category.NUMERIC;
        }

        if (category == Category.NUMERIC_EDITED && signed) {
            throw new PictureSyntaxException(
                    "S is not allowed in a numeric-edited PICTURE: " + source);
        }

        SignPosition signPosition = signed ? SignPosition.TRAILING : SignPosition.UNSIGNED;
        String expanded = String.join("", symbols);
        if (signed) {
            expanded = "S" + expanded;
        }
        return new Picture(source, expanded, category, size, digits, scale,
                signPosition, false, cells);
    }

    private static void addSignOrCurrency(List<Cell> cells, List<Boolean> slotStored,
                                          char c, boolean floating) {
        if (floating) {
            cells.add(new Cell(Kind.FLOAT, c, 1));
            slotStored.add(true);
        } else if (c == '+' || c == '-') {
            cells.add(new Cell(Kind.SIGN, c, 1));
        } else {
            cells.add(new Cell(Kind.INSERT, c, 1));
        }
    }

    private static long countFloating(List<String> symbols, char currency) {
        return count(symbols, String.valueOf(currency)) + count(symbols, "+") + count(symbols, "-");
    }

    private static long count(List<String> symbols, String sym) {
        return symbols.stream().filter(sym::equals).count();
    }

    /** 繰り返し指定 {@code 9(5)} を展開し、{@code CR} / {@code DB} を 1 個の記号として取り出す。 */
    private static List<String> expand(String s, char currency) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            String sym;
            if (c == 'C' && i + 1 < s.length() && s.charAt(i + 1) == 'R') {
                out.add("CR");
                i += 2;
                continue;
            }
            if (c == 'D' && i + 1 < s.length() && s.charAt(i + 1) == 'B') {
                out.add("DB");
                i += 2;
                continue;
            }
            sym = String.valueOf(c);
            i++;
            int repeat = 1;
            if (i < s.length() && s.charAt(i) == '(') {
                int close = s.indexOf(')', i);
                if (close < 0) {
                    throw new PictureSyntaxException("unterminated repetition count in: " + s);
                }
                String countText = s.substring(i + 1, close).trim();
                try {
                    repeat = Integer.parseInt(countText);
                } catch (NumberFormatException e) {
                    throw new PictureSyntaxException("invalid repetition count '" + countText + "' in: " + s);
                }
                if (repeat < 1) {
                    throw new PictureSyntaxException("repetition count must be positive in: " + s);
                }
                i = close + 1;
            }
            for (int r = 0; r < repeat; r++) {
                out.add(sym);
            }
        }
        return out;
    }
}
