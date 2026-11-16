package dev.cobolonjava.runtime.function;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Year;
import java.time.YearMonth;
import java.util.HexFormat;

/** 数値や記憶域のバイトを文字表現へ変換する組み込み関数。 */
public final class ConversionFunctions {
    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    private ConversionFunctions() {
    }

    public static byte[] bytes(CodePage page, String function, byte[] data) {
        return switch (function) {
            case "HEX-OF" -> page.encode(HEX.formatHex(data));
            case "BIT-OF" -> {
                StringBuilder bits = new StringBuilder(Math.multiplyExact(data.length, 8));
                for (byte value : data) {
                    for (int bit = 7; bit >= 0; bit--) bits.append(value >>> bit & 1);
                }
                yield page.encode(bits.toString());
            }
            case "HEX-TO-CHAR" -> HEX.parseHex(page.decode(data));
            case "BIT-TO-CHAR" -> readBits(page.decode(data));
            default -> throw new IllegalArgumentException(function);
        };
    }

    private static byte[] readBits(String input) {
        if (input.length() % 8 != 0) {
            throw new IllegalArgumentException("BIT-TO-CHAR expects a multiple of eight bits");
        }
        byte[] output = new byte[input.length() / 8];
        for (int i = 0; i < input.length(); i++) {
            char bit = input.charAt(i);
            if (bit != '0' && bit != '1') {
                throw new IllegalArgumentException("invalid bit at character " + (i + 1));
            }
            output[i / 8] |= (byte) ((bit - '0') << (7 - i % 8));
        }
        return output;
    }

    public static Decimal numvalF(ProgramContext context, CodePage page, byte[] input,
                                   boolean comma, boolean test) {
        String text = page.decode(input);
        int invalid = exponentError(text, comma ? ',' : '.');
        if (test) return Decimal.of(invalid, 0);
        if (invalid == 0) {
            try {
                String normalized = text.replace(" ", "").replace(',', '.');
                BigDecimal value = new BigDecimal(normalized);
                // COBOL の浮動小数点指数の範囲を超える値を巨大な固定小数点へ展開しない。
                long exponent = (long) value.precision() - value.scale() - 1;
                if (exponent >= -6_176 && exponent <= 6_144) {
                    return Decimal.parse(normalized);
                }
            } catch (NumberFormatException outOfRange) {
                // The run unit reports an invalid numeric argument.
            }
        }
        context.setReturnCode(16);
        Ops.stopRun();
        throw new IllegalStateException("STOP RUN returned");
    }

    private static int exponentError(String text, char decimalPoint) {
        int at = skipSpaces(text, 0);
        if (signAt(text, at)) at = skipSpaces(text, at + 1);
        int digits = 0;
        boolean point = false;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c >= '0' && c <= '9') {
                digits++;
            } else if (c == decimalPoint && !point) {
                point = true;
            } else {
                break;
            }
            at++;
        }
        if (digits == 0) return at + 1;
        at = skipSpaces(text, at);
        if (at < text.length() && text.charAt(at) == 'E') {
            at = skipSpaces(text, at + 1);
            if (signAt(text, at)) at = skipSpaces(text, at + 1);
            int start = at;
            while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') at++;
            if (at == start) return at + 1;
            at = skipSpaces(text, at);
        }
        return at == text.length() ? 0 : at + 1;
    }

    private static int skipSpaces(String text, int at) {
        while (at < text.length() && text.charAt(at) == ' ') at++;
        return at;
    }

    private static boolean signAt(String text, int at) {
        return at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-');
    }

    public static Decimal testDate(Decimal input, boolean ordinal) {
        BigDecimal decimal = input.toBigDecimal().setScale(0, RoundingMode.DOWN);
        if (decimal.signum() < 0 || decimal.compareTo(BigDecimal.valueOf(99_999_999)) > 0) {
            return Decimal.of(1, 0);
        }
        int value = decimal.intValueExact();
        int year = value / (ordinal ? 1_000 : 10_000);
        if (year < 1601 || year > 9999) return Decimal.of(1, 0);
        if (ordinal) {
            int day = value % 1_000;
            return Decimal.of(day >= 1 && day <= Year.of(year).length() ? 0 : 2, 0);
        }
        int month = value / 100 % 100;
        int day = value % 100;
        if (month < 1 || month > 12) return Decimal.of(2, 0);
        return Decimal.of(day >= 1 && day <= YearMonth.of(year, month).lengthOfMonth() ? 0 : 3, 0);
    }

    public static Decimal combinedDateTime(Decimal date, Decimal seconds) {
        BigDecimal result = date.toBigDecimal().add(seconds.toBigDecimal().movePointLeft(5)
                .setScale(20, RoundingMode.DOWN)).stripTrailingZeros();
        return Decimal.of(result.unscaledValue(), result.scale());
    }
}
