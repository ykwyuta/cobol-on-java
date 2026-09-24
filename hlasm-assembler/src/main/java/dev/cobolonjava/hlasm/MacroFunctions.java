package dev.cobolonjava.hlasm;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.ToIntFunction;
import java.util.function.Function;

/** 条件付きアセンブリの文字値変換関数。 */
final class MacroFunctions {

    private MacroFunctions() {
    }

    static String character(String name, String argument, CodePage codePage, int line,
                            Function<String, String> character,
                            ToIntFunction<String> arithmetic) {
        String kind = name.toUpperCase(Locale.ROOT);
        boolean arithmeticArgument = kind.startsWith("A2") || kind.equals("BYTE")
                || kind.equals("SIGNED");
        int number = arithmeticArgument ? arithmetic.applyAsInt(argument) : 0;
        String text = arithmeticArgument ? "" : character.apply(argument);
        byte[] bytes = codePage.encode(text);
        return switch (kind) {
            case "A2B" -> bits(number);
            case "A2C" -> characters(ByteBuffer.allocate(4).putInt(number).array(), codePage);
            case "A2D" -> signed(number);
            case "A2X" -> String.format(Locale.ROOT, "%08X", number);
            case "B2C" -> characters(binaryBytes(text, line), codePage);
            case "B2D" -> signed(binaryNumber(text, line));
            case "B2X" -> binaryHex(text, line);
            case "BYTE" -> {
                if (number < 0 || number > 255) {
                    throw new AssemblyException(line, "BYTE requires 0..255");
                }
                yield characters(new byte[]{(byte) number}, codePage);
            }
            case "C2B" -> bits(bytes);
            case "C2D" -> signed(characterNumber(bytes, line));
            case "C2X" -> HexFormat.of().withUpperCase().formatHex(bytes);
            case "D2B" -> text.isEmpty() ? "" : bits(decimalNumber(text, line));
            case "D2C" -> text.isEmpty() ? ""
                    : characters(ByteBuffer.allocate(4).putInt(decimalNumber(text, line)).array(),
                    codePage);
            case "D2X" -> text.isEmpty() ? ""
                    : String.format(Locale.ROOT, "%08X", decimalNumber(text, line));
            case "DCVAL" -> unpair(text);
            case "DEQUOTE" -> dequote(text);
            case "DOUBLE" -> text.replace("&", "&&").replace("'", "''");
            case "LOWER" -> text.toLowerCase(Locale.ROOT);
            case "SIGNED" -> Integer.toString(number);
            case "UPPER" -> text.toUpperCase(Locale.ROOT);
            case "X2B" -> hexBits(text, line);
            case "X2C" -> characters(hexBytes(text, line), codePage);
            case "X2D" -> signed(hexNumber(text, line));
            case "ESYM" -> {
                String external = System.getProperty("hlasm.esym." + text);
                if (external == null) {
                    throw new AssemblyException(line, "external symbol is unavailable: " + text);
                }
                yield external;
            }
            default -> throw new AssemblyException(line,
                    "unsupported character function: " + name);
        };
    }

    private static String signed(int number) {
        return number < 0 ? Integer.toString(number) : "+" + number;
    }

    private static String bits(int value) {
        return String.format(Locale.ROOT, "%32s", Integer.toBinaryString(value))
                .replace(' ', '0');
    }

    private static String bits(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 8);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%8s",
                    Integer.toBinaryString(value & 0xFF)).replace(' ', '0'));
        }
        return result.toString();
    }

    private static String characters(byte[] bytes, CodePage codePage) {
        return new String(bytes, codePage.charset());
    }

    private static byte[] binaryBytes(String text, int line) {
        if (text.isEmpty()) {
            return new byte[0];
        }
        if (!text.matches("[01]+")) {
            throw new AssemblyException(line, "binary function requires 0 and 1 only");
        }
        String padded = "0".repeat((8 - text.length() % 8) % 8) + text;
        byte[] result = new byte[padded.length() / 8];
        for (int k = 0; k < result.length; k++) {
            result[k] = (byte) Integer.parseInt(padded.substring(k * 8, k * 8 + 8), 2);
        }
        return result;
    }

    private static int binaryNumber(String text, int line) {
        if (text.isEmpty()) {
            return 0;
        }
        if (text.length() > 32 || !text.matches("[01]+")) {
            throw new AssemblyException(line, "B2D requires at most 32 binary digits");
        }
        return (int) Long.parseLong(text, 2);
    }

    private static String binaryHex(String text, int line) {
        if (text.isEmpty()) {
            return "";
        }
        if (!text.matches("[01]+")) {
            throw new AssemblyException(line, "B2X requires binary digits");
        }
        String padded = "0".repeat((4 - text.length() % 4) % 4) + text;
        StringBuilder result = new StringBuilder(padded.length() / 4);
        for (int k = 0; k < padded.length(); k += 4) {
            result.append(Character.toUpperCase(Character.forDigit(
                    Integer.parseInt(padded.substring(k, k + 4), 2), 16)));
        }
        return result.toString();
    }

    private static int characterNumber(byte[] bytes, int line) {
        if (bytes.length > 4) {
            throw new AssemblyException(line, "C2D requires at most four bytes");
        }
        int value = 0;
        for (byte item : bytes) {
            value = value << 8 | (item & 0xFF);
        }
        return value;
    }

    private static int decimalNumber(String text, int line) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException failure) {
            throw new AssemblyException(line, "decimal function requires a signed 32-bit value");
        }
    }

    private static String hexBits(String text, int line) {
        if (!text.matches("[0-9A-Fa-f]*")) {
            throw new AssemblyException(line, "X2B requires hexadecimal digits");
        }
        StringBuilder result = new StringBuilder(text.length() * 4);
        for (int k = 0; k < text.length(); k++) {
            result.append(String.format(Locale.ROOT, "%4s",
                    Integer.toBinaryString(Character.digit(text.charAt(k), 16)))
                    .replace(' ', '0'));
        }
        return result.toString();
    }

    private static byte[] hexBytes(String text, int line) {
        if (!text.matches("[0-9A-Fa-f]*")) {
            throw new AssemblyException(line, "X2C requires hexadecimal digits");
        }
        String padded = text.length() % 2 == 0 ? text : "0" + text;
        return HexFormat.of().parseHex(padded);
    }

    private static int hexNumber(String text, int line) {
        if (text.isEmpty()) {
            return 0;
        }
        if (text.length() > 8 || !text.matches("[0-9A-Fa-f]+")) {
            throw new AssemblyException(line, "X2D requires at most eight hexadecimal digits");
        }
        return (int) Long.parseLong(text, 16);
    }

    private static String unpair(String text) {
        StringBuilder result = new StringBuilder();
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            result.append(c);
            if ((c == '&' || c == '\'') && k + 1 < text.length()
                    && text.charAt(k + 1) == c) {
                k++;
            }
        }
        return result.toString();
    }

    private static String dequote(String text) {
        if (text.length() <= 1) {
            return text;
        }
        int first = text.charAt(0) == '\'' ? 1 : 0;
        int last = text.charAt(text.length() - 1) == '\'' ? text.length() - 1
                : text.length();
        return text.substring(first, last);
    }
}
