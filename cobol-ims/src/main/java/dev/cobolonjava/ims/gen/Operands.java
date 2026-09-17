package dev.cobolonjava.ims.gen;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** DBD / PSB の演算項を読むための共通の検査。 */
public final class Operands {

    private static final Pattern NAME = Pattern.compile("[A-Z@#$][A-Z0-9@#$]{0,7}");

    private Operands() {
    }

    /** 知っているキーワードだけが書かれているか。知らないものは黙って飛ばさない。 */
    public static void allowOnly(MacroStatement statement, Map<String, String> keywords, Set<String> known) {
        for (String name : keywords.keySet()) {
            if (!known.contains(name)) {
                throw new ImsGenerationException(statement.line(),
                        statement.operation() + " keyword " + name + " is not supported yet");
            }
        }
    }

    public static String required(MacroStatement statement, Map<String, String> keywords, String name) {
        String value = keywords.get(name);
        if (value == null || value.isEmpty()) {
            throw new ImsGenerationException(statement.line(),
                    statement.operation() + " requires " + name + "=");
        }
        return value;
    }

    /** 1〜8 文字の名前 (セグメント名、DBD 名、PSB 名)。大文字にして返す。 */
    public static String name(MacroStatement statement, String value, String what) {
        String upper = value.toUpperCase(Locale.ROOT);
        if (!NAME.matcher(upper).matches()) {
            throw new ImsGenerationException(statement.line(), "invalid " + what + ": " + value);
        }
        return upper;
    }

    /** 正の 10 進数。 */
    public static int positive(MacroStatement statement, String value, String what) {
        try {
            int number = Integer.parseInt(value);
            if (number > 0) {
                return number;
            }
        } catch (NumberFormatException e) {
            // 下で断る
        }
        throw new ImsGenerationException(statement.line(), what + " must be a positive number: " + value);
    }

    /** {@code YES} / {@code NO}。書かれていなければ既定値。 */
    public static boolean yesNo(MacroStatement statement, String value, boolean absent, String what) {
        if (value == null) {
            return absent;
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "YES", "Y" -> true;
            case "NO", "N" -> false;
            default -> throw new ImsGenerationException(statement.line(), what + " must be YES or NO: " + value);
        };
    }

    /** 設計 78 §1.1 の L0 の機能。黙って近い形で受けず、対応していないと告げて断る。 */
    public static ImsGenerationException unsupported(MacroStatement statement, String what) {
        return new ImsGenerationException(statement.line(), what + " is not supported (design 78 section 1.1)");
    }
}
