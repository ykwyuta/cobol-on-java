package dev.cobolonjava.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Java ソースを直接生成する言語で共用する。 */
public final class JavaSource {

    private JavaSource() {
    }

    public static void write(Path root, String className, String source) throws IOException {
        Path target = root.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(target.getParent());
        Files.writeString(target, source);
    }

    public static String literal(String value) {
        StringBuilder out = new StringBuilder("\"");
        value.chars().forEach(c -> escape(out, (char) c));
        return out.append('"').toString();
    }

    /** 改行と末尾空白を保つテキストブロック。長い本文は JVM の定数長の制限に合わせて分ける。 */
    public static String textExpression(String value, int indentation) {
        String indent = " ".repeat(indentation);
        int maxChunk = 8_000;
        List<String> blocks = new ArrayList<>();
        for (int start = 0; start < value.length();) {
            int end = start + Math.min(maxChunk, value.length() - start);
            if (end < value.length()) {
                int newline = value.lastIndexOf('\n', end - 1);
                if (newline >= start) {
                    end = newline + 1;
                } else if (Character.isHighSurrogate(value.charAt(end - 1))) {
                    end--;
                }
            }
            blocks.add(textBlock(value.substring(start, end), indent));
            start = end;
        }
        return switch (blocks.size()) {
            case 0 -> "\"\"";
            case 1 -> blocks.getFirst();
            default -> "String.join(\"\",\n" + indent
                    + String.join(",\n" + indent, blocks) + ")";
        };
    }

    private static String textBlock(String value, String indent) {
        StringBuilder out = new StringBuilder("\"\"\"\n");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\n' && (i == 0 || value.charAt(i - 1) == '\n')) {
                out.append(indent);
            }
            if (c == '\n') {
                out.append('\n');
            } else if (c == ' ' && (i + 1 == value.length() || value.charAt(i + 1) == '\n')) {
                out.append("\\s");
            } else {
                escape(out, c);
            }
        }
        if (value.charAt(value.length() - 1) != '\n') {
            out.append("\\\n");
        }
        return out.append(indent).append("\"\"\"").toString();
    }

    private static void escape(StringBuilder out, char c) {
        switch (c) {
            case '\\', '"' -> out.append('\\').append(c);
            case '\t' -> out.append("\\t");
            case '\r' -> out.append("\\r");
            case '\n' -> out.append("\\n");
            default -> {
                if (Character.isISOControl(c)) {
                    out.append("\\%03o".formatted((int) c));
                } else {
                    out.append(c);
                }
            }
        }
    }
}
