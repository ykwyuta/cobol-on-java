package dev.cobolonjava.hlasm;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** 条件付きアセンブリに固有の数値関数とビット演算。 */
final class MacroArithmetic {

    private MacroArithmetic() {
    }

    static int evaluate(String source, CodePage codePage, int line,
                        Function<String, String> character) {
        String text = source.trim();
        if (text.isEmpty()) {
            throw new AssemblyException(line, "empty arithmetic expression");
        }
        text = expandFunctions(text, codePage, line, character);
        text = resolveGroupedBitOperations(text, codePage, line, character);
        return operations(text, codePage, line, character);
    }

    private static String resolveGroupedBitOperations(String text, CodePage codePage, int line,
                                                       Function<String, String> character) {
        String value = text;
        boolean changed;
        do {
            changed = false;
            java.util.ArrayDeque<Integer> opens = new java.util.ArrayDeque<>();
            boolean quoted = false;
            for (int at = 0; at < value.length(); at++) {
                char current = value.charAt(at);
                if (current == '\'' && Quotes.isDelimiter(value, at, quoted)) {
                    quoted = !quoted;
                } else if (!quoted && current == '(') {
                    opens.push(at);
                } else if (!quoted && current == ')' && !opens.isEmpty()) {
                    int open = opens.pop();
                    String inside = value.substring(open + 1, at);
                    if (hasInfixOperation(inside) || inside.matches("(?is)^\\s*NOT\\s+.*")) {
                        int number = operations(inside, codePage, line, character);
                        value = value.substring(0, open) + number + value.substring(at + 1);
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
        return value;
    }

    private static boolean hasInfixOperation(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)\\s+(?:AND|OR|XOR|SLA|SLL|SRA|SRL|FIND|INDEX)\\s+")
                .matcher(value);
        while (matcher.find()) {
            if (topLevel(value, matcher.start())) {
                return true;
            }
        }
        return false;
    }

    private static String expandFunctions(String text, CodePage codePage, int line,
                                          Function<String, String> character) {
        StringBuilder result = new StringBuilder();
        boolean quoted = false;
        for (int at = 0; at < text.length();) {
            char current = text.charAt(at);
            if (current == '\'' && Quotes.isDelimiter(text, at, quoted)) {
                quoted = !quoted;
                result.append(current);
                at++;
                continue;
            }
            if (!quoted && Character.isLetter(current)) {
                int end = at + 1;
                while (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) {
                    end++;
                }
                if (end < text.length() && text.charAt(end) == '(') {
                    int close = closing(text, end, line);
                    String name = text.substring(at, end).toUpperCase(Locale.ROOT);
                    List<String> args = Statement.split(text.substring(end + 1, close), line);
                    result.append(function(name, args, codePage, line, character));
                    at = close + 1;
                    continue;
                }
                result.append(text, at, end);
                at = end;
                continue;
            }
            result.append(current);
            at++;
        }
        return result.toString();
    }

    private static int function(String name, List<String> args, CodePage codePage, int line,
                                Function<String, String> character) {
        if (args.size() != (name.equals("FIND") || name.equals("INDEX") ? 2 : 1)) {
            throw new AssemblyException(line, "wrong argument count for " + name);
        }
        String value = character.apply(args.get(0));
        return switch (name) {
            case "B2A" -> parseUnsigned(value, 2, 32, line, name);
            case "X2A" -> parseUnsigned(value, 16, 8, line, name);
            case "C2A" -> characterValue(value, codePage, line);
            case "D2A" -> decimal(value, line);
            case "DCLEN" -> pairedLength(value);
            case "FIND" -> find(value, character.apply(args.get(1)));
            case "INDEX" -> index(value, character.apply(args.get(1)));
            case "ISBIN" -> valid(value, "[01]{1,32}", line) ? 1 : 0;
            case "ISDEC" -> valid(value, "[0-9]{1,10}", line)
                    && withinPositiveInt(value) ? 1 : 0;
            case "ISHEX" -> valid(value, "[0-9A-Fa-f]{1,8}", line) ? 1 : 0;
            case "ISSYM" -> valid(value, "[A-Za-z_@$#][A-Za-z0-9_@$#]{0,62}", line) ? 1 : 0;
            default -> throw new AssemblyException(line, "unsupported arithmetic function: " + name);
        };
    }

    private static boolean valid(String value, String pattern, int line) {
        if (value.isEmpty()) {
            throw new AssemblyException(line, "validation function requires a nonempty string");
        }
        return value.matches(pattern);
    }

    private static boolean withinPositiveInt(String value) {
        try {
            return Long.parseLong(value) <= Integer.MAX_VALUE;
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    private static int decimal(String value, int line) {
        if (value.isEmpty()) {
            return 0;
        }
        if (value.length() > 11 || !value.matches("[+-]?[0-9]+")) {
            throw new AssemblyException(line, "D2A requires a signed decimal string");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new AssemblyException(line, "D2A value exceeds 32 bits");
        }
    }

    private static int parseUnsigned(String value, int radix, int limit, int line, String name) {
        if (value.isEmpty()) {
            return 0;
        }
        String digits = radix == 2 ? "[01]+" : "[0-9A-Fa-f]+";
        if (value.length() > limit || !value.matches(digits)) {
            throw new AssemblyException(line, name + " has invalid digits or length");
        }
        return (int) Long.parseLong(value, radix);
    }

    private static int characterValue(String value, CodePage codePage, int line) {
        byte[] bytes = codePage.encode(value);
        if (bytes.length > 4) {
            throw new AssemblyException(line, "C2A requires at most four bytes");
        }
        int number = 0;
        for (byte item : bytes) {
            number = number << 8 | item & 0xFF;
        }
        return number;
    }

    private static int pairedLength(String value) {
        int length = 0;
        for (int at = 0; at < value.length(); at++) {
            length++;
            if ((value.charAt(at) == '&' || value.charAt(at) == '\'')
                    && at + 1 < value.length() && value.charAt(at + 1) == value.charAt(at)) {
                at++;
            }
        }
        return length;
    }

    private static int find(String first, String second) {
        for (int at = 0; at < first.length(); at++) {
            if (second.indexOf(first.charAt(at)) >= 0) {
                return at + 1;
            }
        }
        return 0;
    }

    private static int index(String first, String second) {
        return first.isEmpty() || second.isEmpty() ? 0 : first.indexOf(second) + 1;
    }

    private static int operations(String text, CodePage codePage, int line,
                                  Function<String, String> character) {
        String value = text.trim();
        if (value.startsWith("(") && closing(value, 0, line) == value.length() - 1) {
            return operations(value.substring(1, value.length() - 1), codePage, line, character);
        }
        java.util.regex.Matcher search = java.util.regex.Pattern.compile(
                "(?i)\\s+(FIND|INDEX)\\s+").matcher(value);
        while (search.find()) {
            if (topLevel(value, search.start())) {
                String left = character.apply(value.substring(0, search.start()));
                String right = character.apply(value.substring(search.end()));
                return search.group(1).equalsIgnoreCase("FIND")
                        ? find(left, right) : index(left, right);
            }
        }
        for (String group : List.of("SLA|SLL|SRA|SRL", "AND|OR|XOR")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "(?i)\\s+(" + group + ")\\s+").matcher(value);
            int start = 0;
            Integer accumulated = null;
            while (matcher.find()) {
                if (topLevel(value, matcher.start())) {
                    int left = operations(value.substring(start, matcher.start()),
                            codePage, line, character);
                    accumulated = accumulated == null ? left
                            : bitOperation(previousOperation(value, start), accumulated, left);
                    start = matcher.end();
                }
            }
            if (accumulated != null) {
                int right = operations(value.substring(start), codePage, line, character);
                return bitOperation(previousOperation(value, start), accumulated, right);
            }
        }
        if (value.regionMatches(true, 0, "NOT ", 0, 4)) {
            return ~operations(value.substring(4), codePage, line, character);
        }
        return Expressions.absolute(value, Value.absolute(0), name -> null, codePage, line);
    }

    private static boolean topLevel(String value, int end) {
        int depth = 0;
        boolean quoted = false;
        for (int at = 0; at < end; at++) {
            char c = value.charAt(at);
            if (c == '\'' && Quotes.isDelimiter(value, at, quoted)) {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            }
        }
        return depth == 0 && !quoted;
    }

    private static String previousOperation(String value, int start) {
        int end = start;
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        int begin = end;
        while (begin > 0 && Character.isLetter(value.charAt(begin - 1))) {
            begin--;
        }
        return value.substring(begin, end).toUpperCase(Locale.ROOT);
    }

    private static int bitOperation(String name, int left, int right) {
        int count = right & 63;
        return switch (name) {
            case "AND" -> left & right;
            case "OR" -> left | right;
            case "XOR" -> left ^ right;
            case "SLL" -> count >= 32 ? 0 : left << count;
            case "SRL" -> count >= 32 ? 0 : left >>> count;
            case "SRA" -> count >= 32 ? left < 0 ? -1 : 0 : left >> count;
            case "SLA" -> left & Integer.MIN_VALUE
                    | (count >= 31 ? 0 : (left & Integer.MAX_VALUE) << count & Integer.MAX_VALUE);
            default -> throw new AssertionError(name);
        };
    }

    private static int closing(String text, int open, int line) {
        int depth = 0;
        boolean quoted = false;
        for (int at = open; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c == '\'' && Quotes.isDelimiter(text, at, quoted)) {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')' && --depth == 0) {
                return at;
            }
        }
        throw new AssemblyException(line, "unbalanced arithmetic function: " + text);
    }
}
