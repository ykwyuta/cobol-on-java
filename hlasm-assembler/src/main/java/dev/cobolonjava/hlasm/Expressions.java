package dev.cobolonjava.hlasm;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.util.Locale;

/**
 * HLASM の式の評価。
 *
 * <p>項は自己定義項 ({@code 123}, {@code X'1F'}, {@code C'AB'}, {@code B'1010'})、記号、
 * 所在カウンタ {@code *}、長さ属性 {@code L'記号} である。演算子は {@code + - * /} で、
 * 乗除が加減より先に結合する。除算は 0 で割ると 0 になる (HLASM の規則)。
 *
 * <p>{@code *} は所在カウンタと乗算の両方に使う。項を待っているときは所在カウンタ、
 * 演算子を待っているときは乗算と読む。文脈で決まるので曖昧にはならない。
 */
public final class Expressions {

    /** 記号を引く。未定義なら {@code null} を返す。 */
    @FunctionalInterface
    public interface Lookup {
        Symbol find(String name);
    }

    private final Lookup lookup;
    private final CodePage codePage;
    private final int line;
    private final String text;
    private final Value locationCounter;
    private int at;

    private Expressions(String text, Value locationCounter, Lookup lookup, CodePage codePage,
                        int line) {
        this.text = text;
        this.locationCounter = locationCounter;
        this.lookup = lookup;
        this.codePage = codePage;
        this.line = line;
    }

    /** 式 1 つを評価する。末尾に読み残しがあれば断る。 */
    public static Value evaluate(String text, Value locationCounter, Lookup lookup,
                                 CodePage codePage, int line) {
        Expressions parser = new Expressions(text.trim(), locationCounter, lookup, codePage, line);
        Value value = parser.expression();
        parser.skipBlanks();
        if (parser.at < parser.text.length()) {
            throw new AssemblyException(line, "unexpected text in expression: " + text);
        }
        return value;
    }

    /** 絶対値であることを求める式。 */
    public static int absolute(String text, Value locationCounter, Lookup lookup,
                               CodePage codePage, int line) {
        Value value = evaluate(text, locationCounter, lookup, codePage, line);
        if (!value.isAbsolute()) {
            throw new AssemblyException(line, "an absolute expression is required here: " + text);
        }
        return value.value();
    }

    private Value expression() {
        Value left = term();
        while (true) {
            skipBlanks();
            char c = peek();
            if (c == '+') {
                at++;
                left = add(left, term());
            } else if (c == '-') {
                at++;
                left = subtract(left, term());
            } else {
                return left;
            }
        }
    }

    private Value term() {
        Value left = factor();
        while (true) {
            skipBlanks();
            char c = peek();
            if (c == '*' || c == '/') {
                at++;
                Value right = factor();
                if (!left.isAbsolute() || !right.isAbsolute()) {
                    throw new AssemblyException(line,
                            "a relocatable term cannot be multiplied or divided");
                }
                // 0 による除算は 0 とする。HLASM がそう定めている
                left = Value.absolute(c == '*' ? left.value() * right.value()
                        : right.value() == 0 ? 0 : left.value() / right.value());
            } else {
                return left;
            }
        }
    }

    private Value factor() {
        skipBlanks();
        char c = peek();
        if (c == '+') {
            at++;
            return factor();
        }
        if (c == '-') {
            at++;
            Value value = factor();
            if (!value.isAbsolute()) {
                throw new AssemblyException(line, "a relocatable term cannot be negated alone");
            }
            return Value.absolute(-value.value());
        }
        return primary();
    }

    private Value primary() {
        skipBlanks();
        if (at >= text.length()) {
            throw new AssemblyException(line, "the expression ends too early: " + text);
        }
        char c = text.charAt(at);
        if (c == '(') {
            at++;
            Value value = expression();
            skipBlanks();
            if (peek() != ')') {
                throw new AssemblyException(line, "unbalanced parentheses in expression: " + text);
            }
            at++;
            return value;
        }
        if (c == '*') {
            at++;
            if (locationCounter == null) {
                throw new AssemblyException(line, "the location counter is not available here");
            }
            return locationCounter;
        }
        if (Character.isDigit(c)) {
            return Value.absolute(decimal());
        }
        if (isNameStart(c)) {
            int start = at;
            while (at < text.length() && isNamePart(text.charAt(at))) {
                at++;
            }
            String word = text.substring(start, at).toUpperCase(Locale.ROOT);
            // X'..' / C'..' / B'..' / L'..' は、綴りの直後に引用符が来たときだけそう読む
            if (at < text.length() && text.charAt(at) == '\'') {
                switch (word) {
                    case "X" -> {
                        return Value.absolute(hexTerm());
                    }
                    case "B" -> {
                        return Value.absolute(binaryTerm());
                    }
                    case "C" -> {
                        return Value.absolute(characterTerm());
                    }
                    case "L" -> {
                        return Value.absolute(lengthAttribute());
                    }
                    default -> throw new AssemblyException(line,
                            "unknown self-defining term: " + word + "'");
                }
            }
            Symbol symbol = lookup.find(word);
            if (symbol == null) {
                throw new AssemblyException(line, "undefined symbol: " + word);
            }
            return symbol.value();
        }
        throw new AssemblyException(line, "unexpected character in expression: " + c);
    }

    /** {@code L'記号} と {@code L'*}。 */
    private int lengthAttribute() {
        at++;
        skipBlanks();
        if (peek() == '*') {
            at++;
            // 所在カウンタの長さ属性は、この文が組み立てる長さである。増分 1 では使わない
            throw new AssemblyException(line, "L'* is not supported yet");
        }
        int start = at;
        while (at < text.length() && isNamePart(text.charAt(at))) {
            at++;
        }
        String name = text.substring(start, at).toUpperCase(Locale.ROOT);
        Symbol symbol = lookup.find(name);
        if (symbol == null) {
            throw new AssemblyException(line, "undefined symbol in length attribute: " + name);
        }
        return symbol.length();
    }

    private int decimal() {
        int start = at;
        while (at < text.length() && Character.isDigit(text.charAt(at))) {
            at++;
        }
        try {
            return Integer.parseInt(text.substring(start, at));
        } catch (NumberFormatException failure) {
            throw new AssemblyException(line, "the decimal term is too large: "
                    + text.substring(start, at));
        }
    }

    private int hexTerm() {
        String digits = quoted();
        if (digits.isEmpty() || digits.length() > 8) {
            throw new AssemblyException(line, "a hexadecimal term takes 1 to 8 digits: " + digits);
        }
        try {
            return (int) Long.parseLong(digits, 16);
        } catch (NumberFormatException failure) {
            throw new AssemblyException(line, "not a hexadecimal term: " + digits);
        }
    }

    private int binaryTerm() {
        String digits = quoted();
        if (digits.isEmpty() || digits.length() > 32) {
            throw new AssemblyException(line, "a binary term takes 1 to 32 digits: " + digits);
        }
        try {
            return (int) Long.parseLong(digits, 2);
        } catch (NumberFormatException failure) {
            throw new AssemblyException(line, "not a binary term: " + digits);
        }
    }

    /** {@code C'AB'} は文字をコードページのバイトとして右詰めに並べた数である。 */
    private int characterTerm() {
        String characters = quoted();
        byte[] bytes = characters.getBytes(codePage.charset());
        if (bytes.length == 0 || bytes.length > 4) {
            throw new AssemblyException(line,
                    "a character term takes 1 to 4 bytes: '" + characters + "'");
        }
        int value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xFF);
        }
        return value;
    }

    /** 引用符で囲んだ中身。2 つ重ねた引用符は 1 つの引用符である。 */
    private String quoted() {
        at++;
        StringBuilder out = new StringBuilder();
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == '\'') {
                if (at + 1 < text.length() && text.charAt(at + 1) == '\'') {
                    out.append('\'');
                    at += 2;
                    continue;
                }
                at++;
                return out.toString();
            }
            out.append(c);
            at++;
        }
        throw new AssemblyException(line, "a quoted term is not closed: " + text);
    }

    private Value add(Value left, Value right) {
        if (left.isRelocatable() && right.isRelocatable()) {
            throw new AssemblyException(line, "two relocatable terms cannot be added");
        }
        return new Value(left.value() + right.value(),
                left.isRelocatable() ? left.section() : right.section());
    }

    private Value subtract(Value left, Value right) {
        if (right.isAbsolute()) {
            return new Value(left.value() - right.value(), left.section());
        }
        if (left.isRelocatable() && left.section().equals(right.section())) {
            // 同じ節の 2 点の差は、節がどこに置かれても変わらないので絶対値になる
            return Value.absolute(left.value() - right.value());
        }
        throw new AssemblyException(line,
                "a relocatable term can only be subtracted from the same control section");
    }

    private void skipBlanks() {
        while (at < text.length() && text.charAt(at) == ' ') {
            at++;
        }
    }

    private char peek() {
        return at < text.length() ? text.charAt(at) : '\0';
    }

    static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '@' || c == '#' || c == '$' || c == '_';
    }

    static boolean isNamePart(char c) {
        return isNameStart(c) || Character.isDigit(c);
    }
}
