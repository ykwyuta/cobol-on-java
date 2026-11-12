package dev.cobolonjava.pli;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 数の PICTURE (Enterprise PL/I Language Reference, Chapter "Picture specification characters")。
 *
 * <p>9 Z * Y V , . / B $ S + - CR DB T I R F(n)、浮動の字の並び (drifting string)、および
 * 浮動小数の PICTURE (仮数 E|K 指数) を持つ。数の PICTURE の値は字の像として記憶域に置き、
 * 算術の値は FIXED DECIMAL(p,q) である。
 *
 * <p>Zeus の {@code crates/zeus-pli/src/picture.rs} から移した (同じ権利者の処理系)。浮動小数の
 * PICTURE の編集は、Zeus が倍精度の浮動小数で計算するところを BigDecimal で計算する。
 * 実機と突き合わせていない。
 */
final class PliPicture {

    /** PICTURE の 1 字の働き。{@code symbol} は通貨記号の綴り、符号の字、CR / DB、挿入の字。 */
    private record Item(char kind, String symbol) {
        static final char NINE = '9', Z = 'Z', STAR = '*', Y = 'Y', OVER = 'T', V = 'V',
                INSERT = ',', CURRENCY = '$', SIGN = 'S', CREDIT = 'C';

        boolean is(char k) {
            return kind == k;
        }
    }

    /** 編集した像と、上の桁を失ったか。 */
    record Edited(String text, boolean lost) {
    }

    final String spec;
    private final List<Item> items;
    /** 記憶域の字の数。 */
    final int length;
    /** 数字の位置の数。 */
    final int precision;
    /** V より右の数字の数から F を引いたもの。 */
    final int scale;
    private final int factor;
    /** 浮動の字の並びの [始め, 終わり)。無ければ null。 */
    private final int[] drift;
    private final PliPicture mantissa;
    private final PliPicture exponent;
    private final boolean exponentMarked;

    private PliPicture(String spec, List<Item> items, int length, int precision, int scale,
                       int factor, int[] drift, PliPicture mantissa, PliPicture exponent,
                       boolean exponentMarked) {
        this.spec = spec;
        this.items = items;
        this.length = length;
        this.precision = precision;
        this.scale = scale;
        this.factor = factor;
        this.drift = drift;
        this.mantissa = mantissa;
        this.exponent = exponent;
        this.exponentMarked = exponentMarked;
    }

    boolean isFloat() {
        return mantissa != null;
    }

    /** 符号を表せるか (S + - CR DB T I R)。 */
    boolean signed() {
        PliPicture body = mantissa != null ? mantissa : this;
        return body.items.stream().anyMatch(it -> it.is(Item.SIGN) || it.is(Item.CREDIT)
                || it.is(Item.OVER));
    }

    /**
     * 数の PICTURE を読む。文字の PICTURE (A か X を含む) なら null を返す。
     *
     * @throws IllegalArgumentException 正しくない指定
     */
    static PliPicture parse(String spec) {
        StringBuilder raw = new StringBuilder();
        int factor = 0;
        for (int i = 0; i < spec.length(); i++) {
            char c = Character.toUpperCase(spec.charAt(i));
            if (c == '(') {
                int close = spec.indexOf(')', i);
                if (close < 0) throw new IllegalArgumentException("unbalanced ( in picture");
                int n;
                try {
                    n = Integer.parseInt(spec.substring(i + 1, close).strip());
                } catch (NumberFormatException bad) {
                    throw new IllegalArgumentException("invalid repetition factor in picture");
                }
                if (close + 1 >= spec.length()) {
                    throw new IllegalArgumentException("picture ends after repetition factor");
                }
                char next = spec.charAt(close + 1);
                if (next == '<') {
                    int end = spec.indexOf('>', close + 1);
                    if (end < 0) throw new IllegalArgumentException("unclosed <");
                    raw.append(spec.substring(close + 1, end + 1).repeat(n));
                    i = end;
                } else {
                    raw.append(String.valueOf(Character.toUpperCase(next)).repeat(n));
                    i = close + 1;
                }
                continue;
            }
            if (c == 'F' && i + 1 < spec.length() && spec.charAt(i + 1) == '(') {
                int close = spec.indexOf(')', i);
                if (close < 0) throw new IllegalArgumentException("unbalanced F(");
                try {
                    factor = Integer.parseInt(spec.substring(i + 2, close).strip()
                            .replaceFirst("^\\+", ""));
                } catch (NumberFormatException bad) {
                    throw new IllegalArgumentException("invalid F scale factor");
                }
                i = close;
                continue;
            }
            raw.append(spec.charAt(i));
        }
        String text = raw.toString();
        // 浮動小数の PICTURE: 仮数 E|K 指数
        int depth = 0;
        for (int k = 0; k < text.length(); k++) {
            char c = Character.toUpperCase(text.charAt(k));
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if ((c == 'E' || c == 'K') && depth == 0) {
                PliPicture mantissa = parse(text.substring(0, k));
                PliPicture exponent = parse(text.substring(k + 1));
                if (mantissa == null || exponent == null || mantissa.isFloat()
                        || exponent.isFloat()) {
                    throw new IllegalArgumentException("invalid floating-point picture");
                }
                if (exponent.scale != 0) {
                    throw new IllegalArgumentException(
                            "the exponent of a floating-point picture has no V");
                }
                boolean marked = c == 'E';
                return new PliPicture(spec, mantissa.items, mantissa.length + (marked ? 1 : 0)
                        + exponent.length, mantissa.precision, mantissa.scale, 0, mantissa.drift,
                        mantissa, exponent, marked);
            }
        }
        if (text.toUpperCase(Locale.ROOT).chars().anyMatch(c -> c == 'A' || c == 'X')) {
            return null;
        }
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toUpperCase(text.charAt(i));
            switch (c) {
                case '9' -> items.add(new Item(Item.NINE, ""));
                case 'Z' -> items.add(new Item(Item.Z, ""));
                case '*' -> items.add(new Item(Item.STAR, ""));
                case 'Y' -> items.add(new Item(Item.Y, ""));
                case 'T', 'I', 'R' -> items.add(new Item(Item.OVER, String.valueOf(c)));
                case 'V' -> items.add(new Item(Item.V, ""));
                case ',', '.', '/', 'B', '\'' -> items.add(new Item(Item.INSERT, String.valueOf(c)));
                case '$' -> items.add(new Item(Item.CURRENCY, "$"));
                case '<' -> {
                    int end = text.indexOf('>', i);
                    if (end < 0) throw new IllegalArgumentException("unclosed < in picture");
                    items.add(new Item(Item.CURRENCY, text.substring(i + 1, end)));
                    i = end;
                }
                case 'S', '+', '-' -> items.add(new Item(Item.SIGN, String.valueOf(c)));
                case 'C', 'D' -> {
                    char next = i + 1 < text.length()
                            ? Character.toUpperCase(text.charAt(i + 1)) : ' ';
                    if (next != 'R' && next != 'B') {
                        throw new IllegalArgumentException("invalid character '" + c
                                + "' in a numeric picture");
                    }
                    items.add(new Item(Item.CREDIT, "" + c + next));
                    i++;
                }
                default -> throw new IllegalArgumentException("invalid character '" + c
                        + "' in a numeric picture");
            }
        }
        // 浮動の字の並び: 同じ $ S + - が 2 つ以上、挿入の字や V を挟んで続く
        int[] drift = null;
        for (int k = 0; k < items.size() && drift == null; k++) {
            String symbol = driftSymbol(items.get(k));
            if (symbol == null) continue;
            int j = k + 1;
            int count = 1;
            while (j < items.size()) {
                Item item = items.get(j);
                if (symbol.equals(driftSymbol(item))) {
                    count++;
                } else if (!item.is(Item.INSERT) && !item.is(Item.V)) {
                    break;
                }
                j++;
            }
            // 後ろの挿入の字と V は並びの中で終わらない
            while (j > k + 1 && (items.get(j - 1).is(Item.INSERT) || items.get(j - 1).is(Item.V))) {
                j--;
            }
            if (count >= 2) {
                drift = new int[] {k, j};
            }
        }
        int precision = 0;
        int afterV = 0;
        boolean seenV = false;
        int length = 0;
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            boolean inDrift = drift != null && index > drift[0] && index < drift[1];
            boolean digit = switch (item.kind) {
                case Item.NINE, Item.Z, Item.STAR, Item.Y, Item.OVER -> true;
                case Item.CURRENCY, Item.SIGN -> inDrift;
                default -> false;
            };
            if (digit) {
                precision++;
                if (seenV) afterV++;
            }
            switch (item.kind) {
                case Item.V -> seenV = true;
                case Item.CURRENCY -> length += item.symbol.length();
                case Item.CREDIT -> length += 2;
                default -> length++;
            }
        }
        if (precision == 0) {
            throw new IllegalArgumentException("a numeric picture needs a digit position");
        }
        return new PliPicture(spec, List.copyOf(items), length, precision, afterV - factor,
                factor, drift, null, null, false);
    }

    private static String driftSymbol(Item item) {
        if (item.is(Item.CURRENCY)) return "$" + item.symbol;
        if (item.is(Item.SIGN)) return item.symbol;
        return null;
    }

    /**
     * 算術の値を像へ編集する。値は PICTURE の位取りで切り捨てる。上の桁を失えば
     * {@link Edited#lost()} が真になる。
     */
    Edited edit(BigDecimal value) {
        boolean negative = value.signum() < 0;
        int shownScale = Math.max(0, scale + factor);
        BigDecimal v = value.abs();
        if (factor > 0) {
            v = v.movePointLeft(factor);
        } else if (factor < 0) {
            v = v.movePointRight(-factor);
        }
        String all = v.setScale(shownScale, RoundingMode.DOWN).unscaledValue().toString();
        boolean lost = all.length() > precision;
        if (lost) {
            all = all.substring(all.length() - precision);
        }
        String padded = "0".repeat(precision - all.length()) + all;
        int[] digits = padded.chars().map(c -> c - '0').toArray();
        boolean zero = padded.chars().allMatch(c -> c == '0');
        int driftA = drift == null ? Integer.MAX_VALUE : drift[0];
        int driftB = drift == null ? Integer.MAX_VALUE : drift[1];
        Integer[] digitIndex = new Integer[items.size()];
        int d = 0;
        int vIndex = -1;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.is(Item.V) && vIndex < 0) vIndex = i;
            boolean isDigit = switch (item.kind) {
                case Item.NINE, Item.Z, Item.STAR, Item.Y, Item.OVER -> true;
                case Item.CURRENCY, Item.SIGN -> i >= driftA && i < driftB && i > driftA;
                default -> false;
            };
            digitIndex[i] = isDigit ? d++ : null;
        }
        final int v0 = vIndex;
        java.util.function.IntPredicate afterV = i -> v0 >= 0 && i > v0;
        // 有効な桁: 最初の 0 でない数字か、最初の強いた位置 (9 T I R) から先を出す
        Integer firstSignificant = null;
        for (int k = 0; k < digits.length; k++) {
            if (digits[k] != 0) {
                firstSignificant = k;
                break;
            }
        }
        Integer forcedFrom = null;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item.is(Item.NINE) || item.is(Item.OVER)
                    || (afterV.test(i) && !zero && digitIndex[i] != null)) {
                forcedFrom = digitIndex[i];
                break;
            }
        }
        Integer start;
        if (firstSignificant == null) {
            start = forcedFrom;
        } else if (forcedFrom == null) {
            start = firstSignificant;
        } else {
            start = Math.min(firstSignificant, forcedFrom);
        }
        boolean allSuppressed = start == null;
        StringBuilder out = new StringBuilder();
        boolean started = false;
        char fill = ' ';
        int driftSymbolAt = -1;
        if (drift != null) {
            int at = driftA;
            for (int i = driftA; i < driftB; i++) {
                if (digitIndex[i] != null && start != null && digitIndex[i] >= start) {
                    break;
                }
                if (digitIndex[i] != null || i == driftA) {
                    at = i;
                }
            }
            driftSymbolAt = at;
        }
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (i >= driftA && i < driftB) {
                if (item.is(Item.INSERT)) {
                    out.append(item.symbol.equals("B") || !started ? ' ' : item.symbol.charAt(0));
                } else if (!item.is(Item.V)) {
                    Integer k = digitIndex[i];
                    String symbol = driftText(item, negative);
                    if (k != null && start != null && k >= start) {
                        started = true;
                        out.append((char) ('0' + digits[k]));
                    } else if (i == driftSymbolAt && !allSuppressed) {
                        out.append(symbol);
                    } else {
                        out.append(" ".repeat(Math.max(1, symbol.length())));
                    }
                }
                continue;
            }
            switch (item.kind) {
                case Item.NINE -> {
                    started = true;
                    out.append((char) ('0' + digits[digitIndex[i]]));
                }
                case Item.Z, Item.STAR -> {
                    int k = digitIndex[i];
                    char suppress = item.is(Item.Z) ? ' ' : '*';
                    fill = suppress;
                    if (start != null && k >= start) {
                        started = true;
                        out.append((char) ('0' + digits[k]));
                    } else {
                        out.append(suppress);
                    }
                }
                case Item.Y -> {
                    int k = digitIndex[i];
                    started = true;
                    out.append(digits[k] == 0 ? ' ' : (char) ('0' + digits[k]));
                }
                case Item.OVER -> {
                    int x = digits[digitIndex[i]];
                    started = true;
                    char c = item.symbol.charAt(0);
                    char positive = "{ABCDEFGHI".charAt(x);
                    char minus = "}JKLMNOPQR".charAt(x);
                    out.append(((c == 'T' || c == 'I') && !negative) ? positive
                            : ((c == 'T' || c == 'R') && negative) ? minus : (char) ('0' + x));
                }
                case Item.V -> {
                }
                case Item.INSERT -> {
                    if (item.symbol.equals("B")) {
                        out.append(' ');
                    } else {
                        boolean noDigitsYet = out.chars().noneMatch(Character::isDigit)
                                && items.subList(0, i).stream()
                                        .noneMatch(x -> x.is(Item.Z) || x.is(Item.STAR));
                        boolean show = started || (afterV.test(i) && !zero) || noDigitsYet;
                        out.append(show ? item.symbol.charAt(0) : fill);
                    }
                }
                case Item.CURRENCY -> out.append(item.symbol);
                case Item.SIGN -> out.append(switch (item.symbol) {
                    case "S" -> negative ? '-' : '+';
                    case "+" -> negative ? ' ' : '+';
                    default -> negative ? '-' : ' ';
                });
                case Item.CREDIT -> out.append(negative ? item.symbol : "  ");
                default -> throw new IllegalStateException(String.valueOf(item.kind));
            }
        }
        // すべてを抑えた値は欄全体を空白にする (静的な通貨記号も)。* で埋めるときは除く
        if (allSuppressed && items.stream().anyMatch(x -> x.is(Item.Z))
                && items.stream().noneMatch(x -> x.is(Item.STAR) || x.is(Item.NINE)
                        || x.is(Item.OVER))) {
            return new Edited(" ".repeat(length), lost);
        }
        return new Edited(out.toString(), lost);
    }

    private static String driftText(Item item, boolean negative) {
        if (item.is(Item.CURRENCY)) return item.symbol;
        return switch (item.symbol) {
            case "S" -> negative ? "-" : "+";
            case "+" -> negative ? " " : "+";
            case "-" -> negative ? "-" : " ";
            default -> "";
        };
    }

    /**
     * 浮動小数の PICTURE へ編集する。仮数の最初の整数の位置が 0 でないように指数を決める。
     */
    Edited editFloat(BigDecimal value) {
        int p = mantissa.precision;
        int q = mantissa.scale;
        int integerDigits = p - q;
        BigInteger coefficient = BigInteger.ZERO;
        int power = 0;
        if (value.signum() != 0) {
            BigDecimal a = value.abs();
            int e10 = a.precision() - a.scale() - 1;
            for (int guard = 0; guard < 100; guard++) {
                power = e10 - (integerDigits - 1);
                BigInteger c = a.movePointLeft(power - q).setScale(0, RoundingMode.DOWN)
                        .toBigInteger();
                if (c.compareTo(BigInteger.TEN.pow(p)) >= 0) {
                    e10++;
                    continue;
                }
                if (p > 1 && c.compareTo(BigInteger.TEN.pow(p - 1)) < 0) {
                    e10--;
                    continue;
                }
                coefficient = c;
                break;
            }
            if (value.signum() < 0) {
                coefficient = coefficient.negate();
            }
        }
        Edited m = mantissa.edit(new BigDecimal(coefficient, Math.max(0, q)));
        Edited e = exponent.edit(BigDecimal.valueOf(power));
        return new Edited(m.text() + (exponentMarked ? "E" : "") + e.text(),
                m.lost() || e.lost());
    }

    /** 像の算術の値。像が PICTURE に合わなければ null。 */
    BigDecimal value(String text) {
        if (mantissa != null) {
            throw new UnsupportedOperationException("FLOAT picture values are not supported yet");
        }
        int position = 0;
        StringBuilder digits = new StringBuilder();
        boolean negative = false;
        int after = 0;
        boolean seenV = false;
        int driftA = drift == null ? Integer.MAX_VALUE : drift[0];
        int driftB = drift == null ? Integer.MAX_VALUE : drift[1];
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            boolean inDrift = i >= driftA && i < driftB;
            switch (item.kind) {
                case Item.V -> {
                    seenV = true;
                    continue;
                }
                case Item.NINE, Item.Z, Item.STAR, Item.Y -> {
                    if (position >= text.length()) return null;
                    char c = text.charAt(position++);
                    if (Character.isDigit(c)) digits.append(c);
                    else if (c == ' ' || c == '*') digits.append('0');
                    else return null;
                    if (seenV) after++;
                }
                case Item.OVER -> {
                    if (position >= text.length()) return null;
                    char c = text.charAt(position++);
                    int k;
                    if ((k = "{ABCDEFGHI".indexOf(c)) >= 0) {
                        digits.append((char) ('0' + k));
                    } else if ((k = "}JKLMNOPQR".indexOf(c)) >= 0) {
                        digits.append((char) ('0' + k));
                        negative = true;
                    } else if (Character.isDigit(c)) {
                        digits.append(c);
                    } else {
                        return null;
                    }
                    if (seenV) after++;
                }
                case Item.CURRENCY -> {
                    int n = item.symbol.length();
                    if (inDrift && i > driftA) {
                        if (position >= text.length()) return null;
                        char c = text.charAt(position);
                        if (Character.isDigit(c)) {
                            digits.append(c);
                            position++;
                        } else {
                            digits.append('0');
                            position += Math.max(1, n);
                        }
                        if (seenV) after++;
                    } else {
                        position += n;
                    }
                }
                case Item.SIGN -> {
                    if (position >= text.length()) return null;
                    char c = text.charAt(position++);
                    if (c == '-') negative = true;
                    if (inDrift && i > driftA) {
                        digits.append(Character.isDigit(c) ? c : '0');
                        if (seenV) after++;
                    }
                }
                case Item.CREDIT -> {
                    if (position + 2 > text.length()) return null;
                    if (text.substring(position, position + 2).equalsIgnoreCase(item.symbol)) {
                        negative = true;
                    }
                    position += 2;
                }
                default -> position++;
            }
        }
        BigInteger coefficient = digits.isEmpty() ? BigInteger.ZERO
                : new BigInteger(digits.toString());
        BigDecimal result = new BigDecimal(negative ? coefficient.negate() : coefficient, after);
        if (factor > 0) {
            result = result.movePointRight(factor);
        } else if (factor < 0) {
            result = result.movePointLeft(-factor);
        }
        return result;
    }
}
