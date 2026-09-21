package dev.cobolonjava.hlasm;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.data.PackedDecimal;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.data.ZonedDecimal;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code DC} と {@code DS} の演算項 1 つ。
 *
 * <p>形は {@code 反復係数 型 修飾子 '値'} である。{@code A} と {@code Y} だけは値を括弧で囲み、
 * 中身が式になる。
 *
 * <p>境界合わせは型ごとに決まる ({@code F} と {@code A} は 4、{@code H} と {@code Y} は 2)。
 * 長さ修飾子 {@code L} を書いた定数は境界合わせをしない。これは HLASM の規則であり、
 * {@code DS 0F} で境界だけを合わせる書き方がこの規則に依存している。
 *
 * <p>倍率 ({@code P} 型の {@code Sn}) と指数 ({@code En}) の修飾子は受け取らない。
 * 10 進定数の倍率を黙って 0 として組み立てると、値が 10 の冪だけずれるためである。
 */
public final class Constants {

    /** 型が決める既定の長さと境界。 */
    private record TypeRule(int defaultLength, int alignment, boolean lengthFromValue) {
    }

    private Constants() {
    }

    private static TypeRule ruleOf(char type, int line) {
        return switch (type) {
            case 'C' -> new TypeRule(1, 1, true);
            case 'X' -> new TypeRule(1, 1, true);
            case 'B' -> new TypeRule(1, 1, true);
            case 'F' -> new TypeRule(4, 4, false);
            case 'H' -> new TypeRule(2, 2, false);
            case 'A' -> new TypeRule(4, 4, false);
            case 'Y' -> new TypeRule(2, 2, false);
            case 'P' -> new TypeRule(1, 1, true);
            case 'Z' -> new TypeRule(1, 1, true);
            case 'D' -> new TypeRule(8, 8, false);
            default -> throw new AssemblyException(line, "unsupported constant type: " + type);
        };
    }

    /** 組み立てた 1 演算項。 */
    public record Piece(int alignment, int length, int duplication, byte[] bytes,
                        List<AddressReference> references) {

        public Piece {
            bytes = bytes == null ? null : bytes.clone();
            references = List.copyOf(references);
        }

        /** この演算項が占める合計の長さ。 */
        public int totalLength() {
            return length * duplication;
        }

        @Override
        public byte[] bytes() {
            return bytes == null ? null : bytes.clone();
        }
    }

    /** {@code A(label)} のように、2 周目でなければ値が決まらない番地定数。 */
    public record AddressReference(int offset, int length, String expression) {
    }

    /**
     * 演算項 1 つを読む。
     *
     * @param values 値を組み立てるなら {@code true} ({@code DC})、場所だけ取るなら {@code false} ({@code DS})
     */
    public static Piece parse(String operand, boolean values, Scope scope, int line) {
        int at = 0;
        int duplication = 1;
        boolean hasDuplication = false;
        while (at < operand.length() && Character.isDigit(operand.charAt(at))) {
            at++;
            hasDuplication = true;
        }
        if (hasDuplication) {
            duplication = Integer.parseInt(operand.substring(0, at));
        }
        if (at >= operand.length()) {
            throw new AssemblyException(line, "a constant requires a type: " + operand);
        }
        char type = Character.toUpperCase(operand.charAt(at));
        at++;
        TypeRule rule = ruleOf(type, line);

        Integer explicitLength = null;
        if (at < operand.length() && (operand.charAt(at) == 'L' || operand.charAt(at) == 'l')) {
            at++;
            int start = at;
            while (at < operand.length() && Character.isDigit(operand.charAt(at))) {
                at++;
            }
            if (start == at) {
                throw new AssemblyException(line, "the length modifier requires a number: " + operand);
            }
            explicitLength = Integer.parseInt(operand.substring(start, at));
            if (explicitLength <= 0) {
                throw new AssemblyException(line, "the length modifier must be positive: " + operand);
            }
        }
        if (at < operand.length() && "SEP".indexOf(Character.toUpperCase(operand.charAt(at))) >= 0
                && at + 1 < operand.length() && isModifierDigit(operand, at + 1)) {
            throw new AssemblyException(line, "the scale and exponent modifiers are not supported: "
                    + operand);
        }

        String nominal = null;
        if (at < operand.length()) {
            char open = operand.charAt(at);
            if (open == '\'') {
                Quoted quoted = readQuoted(operand, at, line);
                nominal = quoted.text();
                at = quoted.end();
            } else if (open == '(') {
                int close = matching(operand, at, line);
                nominal = operand.substring(at + 1, close);
                at = close + 1;
            } else {
                throw new AssemblyException(line, "unexpected text in the constant: " + operand);
            }
        }
        if (at < operand.length()) {
            throw new AssemblyException(line, "unexpected text after the constant: " + operand);
        }
        if (values && nominal == null) {
            throw new AssemblyException(line, "DC requires a value: " + operand);
        }

        int alignment = explicitLength == null ? rule.alignment() : 1;
        if (nominal == null) {
            int length = explicitLength != null ? explicitLength : rule.defaultLength();
            return new Piece(alignment, length, duplication, null, List.of());
        }
        return build(type, rule, explicitLength, alignment, duplication, nominal, values, scope, line);
    }

    private static boolean isModifierDigit(String operand, int at) {
        return Character.isDigit(operand.charAt(at)) || operand.charAt(at) == '-'
                || operand.charAt(at) == '+';
    }

    private static Piece build(char type, TypeRule rule, Integer explicitLength, int alignment,
                               int duplication, String nominal, boolean values, Scope scope,
                               int line) {
        List<AddressReference> references = new ArrayList<>();
        List<byte[]> encoded = new ArrayList<>();
        // 1 つの定数に値を複数書ける。F'1,2,3' は 3 つの語であり、反復係数とは別に並ぶ
        List<String> items = type == 'C' ? List.of(nominal) : Statement.split(nominal, line);
        int unit = explicitLength != null ? explicitLength : rule.defaultLength();
        for (String item : items) {
            byte[] bytes = switch (type) {
                case 'C' -> character(item, explicitLength, scope.codePage());
                case 'X' -> hex(item, explicitLength, line);
                case 'B' -> binary(item, explicitLength, line);
                case 'F', 'H' -> integer(item, explicitLength == null
                        ? rule.defaultLength() : explicitLength, line);
                case 'P' -> packed(item, explicitLength, line);
                case 'Z' -> zoned(item, explicitLength, scope.codePage(), line);
                case 'A', 'Y' -> new byte[explicitLength == null
                        ? rule.defaultLength() : explicitLength];
                default -> throw new AssemblyException(line, "unsupported constant type: " + type);
            };
            if (type == 'A' || type == 'Y') {
                references.add(new AddressReference(
                        encoded.stream().mapToInt(b -> b.length).sum(), bytes.length, item));
            }
            if (rule.lengthFromValue() && explicitLength == null) {
                unit = bytes.length;
            }
            encoded.add(bytes);
        }
        if (encoded.size() > 1 && encoded.stream().anyMatch(b -> b.length != encoded.get(0).length)) {
            // 長さが値ごとに違うと、反復係数を掛けた位置が決まらない
            throw new AssemblyException(line,
                    "values in one constant must have the same length: " + nominal);
        }
        int length = encoded.get(0).length;
        byte[] bytes = new byte[length * encoded.size()];
        int at = 0;
        for (byte[] part : encoded) {
            System.arraycopy(part, 0, bytes, at, part.length);
            at += part.length;
        }
        if (!values) {
            // DS は場所だけを取り、値は書かない
            return new Piece(alignment, bytes.length, duplication, null, List.of());
        }
        return new Piece(alignment, bytes.length, duplication, bytes, references);
    }

    /** {@code C} は左詰めで、余りはコードページの空白で埋める。長いほうは右を切る。 */
    private static byte[] character(String text, Integer explicitLength, CodePage codePage) {
        byte[] raw = text.getBytes(codePage.charset());
        int length = explicitLength != null ? explicitLength : Math.max(raw.length, 0);
        byte[] out = new byte[length];
        java.util.Arrays.fill(out, codePage.space());
        System.arraycopy(raw, 0, out, 0, Math.min(raw.length, length));
        return out;
    }

    /** {@code X} は右詰めで、余りは左を 0 で埋める。長いほうは左を切る。 */
    private static byte[] hex(String digits, Integer explicitLength, int line) {
        String text = digits.replace(" ", "");
        for (int k = 0; k < text.length(); k++) {
            if (Character.digit(text.charAt(k), 16) < 0) {
                throw new AssemblyException(line, "not a hexadecimal digit: " + text.charAt(k));
            }
        }
        // 奇数桁は左に 0 を足して 1 バイトに揃える
        String padded = text.length() % 2 == 0 ? text : "0" + text;
        byte[] raw = new byte[padded.length() / 2];
        for (int k = 0; k < raw.length; k++) {
            raw[k] = (byte) Integer.parseInt(padded.substring(k * 2, k * 2 + 2), 16);
        }
        return explicitLength == null ? raw : rightJustify(raw, explicitLength);
    }

    /** {@code B} は右詰めで、余りは左を 0 で埋める。 */
    private static byte[] binary(String digits, Integer explicitLength, int line) {
        String text = digits.replace(" ", "");
        for (int k = 0; k < text.length(); k++) {
            if (text.charAt(k) != '0' && text.charAt(k) != '1') {
                throw new AssemblyException(line, "not a binary digit: " + text.charAt(k));
            }
        }
        int bytes = Math.max(1, (text.length() + 7) / 8);
        String padded = "0".repeat(bytes * 8 - text.length()) + text;
        byte[] raw = new byte[bytes];
        for (int k = 0; k < bytes; k++) {
            raw[k] = (byte) Integer.parseInt(padded.substring(k * 8, k * 8 + 8), 2);
        }
        return explicitLength == null ? raw : rightJustify(raw, explicitLength);
    }

    /** {@code F} と {@code H} は 2 の補数の big endian である。 */
    private static byte[] integer(String text, int length, int line) {
        long value;
        try {
            value = Long.parseLong(text.trim());
        } catch (NumberFormatException failure) {
            throw new AssemblyException(line, "not an integer constant: " + text);
        }
        byte[] out = new byte[length];
        for (int k = length - 1; k >= 0; k--) {
            out[k] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return out;
    }

    private static byte[] packed(String text, Integer explicitLength, int line) {
        Decimal value = decimal(text, line);
        int digits = Math.max(1, value.magnitude().toString().length());
        byte[] raw = PackedDecimal.encode(value, digits, 0, true);
        return explicitLength == null ? raw : rightJustify(raw, explicitLength);
    }

    private static byte[] zoned(String text, Integer explicitLength, CodePage codePage, int line) {
        Decimal value = decimal(text, line);
        int digits = Math.max(1, value.magnitude().toString().length());
        byte[] raw = ZonedDecimal.encode(value, digits, 0, SignPosition.TRAILING, codePage);
        return explicitLength == null ? raw : rightJustify(raw, explicitLength);
    }

    private static Decimal decimal(String text, int line) {
        try {
            return Decimal.parse(text.trim());
        } catch (RuntimeException failure) {
            throw new AssemblyException(line, "not a decimal constant: " + text);
        }
    }

    /** 右詰めにする。左が余れば 0、足りなければ左を切る。 */
    private static byte[] rightJustify(byte[] raw, int length) {
        byte[] out = new byte[length];
        int copy = Math.min(raw.length, length);
        System.arraycopy(raw, raw.length - copy, out, length - copy, copy);
        return out;
    }

    /** 引用符の中身と、閉じ引用符の次の位置。2 つ重ねた引用符は 1 つの引用符である。 */
    private record Quoted(String text, int end) {
    }

    private static Quoted readQuoted(String operand, int at, int line) {
        StringBuilder out = new StringBuilder();
        int k = at + 1;
        while (k < operand.length()) {
            char c = operand.charAt(k);
            if (c == '\'') {
                if (k + 1 < operand.length() && operand.charAt(k + 1) == '\'') {
                    out.append('\'');
                    k += 2;
                    continue;
                }
                return new Quoted(out.toString(), k + 1);
            }
            out.append(c);
            k++;
        }
        throw new AssemblyException(line, "the constant value is not closed: " + operand);
    }

    private static int matching(String operand, int at, int line) {
        int depth = 0;
        boolean quoted = false;
        for (int k = at; k < operand.length(); k++) {
            char c = operand.charAt(k);
            if (c == '\'' && Quotes.isDelimiter(operand, k)) {
                quoted = !quoted;
            } else if (quoted) {
                continue;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return k;
            }
        }
        throw new AssemblyException(line, "unbalanced parentheses in the constant: " + operand);
    }

    /** 定数を組み立てるのに要る文脈。 */
    public interface Scope {
        CodePage codePage();
    }
}
