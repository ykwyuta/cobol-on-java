package dev.cobolonjava.cics.bms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * アセンブラ形式で書かれたBMSマクロを、名前・命令・operandの文へ切り出す。
 *
 * <p>行の規則は公開されているアセンブラの原始文形式に従う。1〜71桁が本文、72桁目が
 * 空白でなければ次の行へ続き、続きは16桁目から始まる。1桁目の{@code *}は注記行である。
 * operandは括弧と引用符の外にある空白で終わり、残りは注記である。ただしカンマの直後で
 * 行が続く場合は、続きの行のoperandへつながる。
 *
 * <p>引用符の中は71桁目まで空白も値に含め、続きの行の16桁目から再開する。
 * {@code ''}は引用符1文字、{@code &&}はアンパサンド1文字を表す。
 */
final class BmsMacroReader {

    private static final int BODY_END = 71;
    private static final int CONTINUATION_COLUMN = 72;
    private static final int CONTINUATION_START = 16;

    /** operandの値。括弧つきの並び、引用符つき文字列、または裸の語のいずれかである。 */
    sealed interface Value permits Word, Quoted, Sublist {
    }

    record Word(String text) implements Value {
    }

    record Quoted(String text) implements Value {
    }

    record Sublist(List<String> items) implements Value {
        Sublist {
            items = List.copyOf(items);
        }
    }

    /**
     * 1つの原始文。
     *
     * @param line 文が始まった物理行 (1始まり)
     */
    record Statement(int line, String label, String operation,
                     Map<String, Value> keywords, List<String> positionals) {
        Statement {
            keywords = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(keywords));
            positionals = List.copyOf(positionals);
        }
    }

    private final List<String> lines;
    private int row;
    private int column;

    private BmsMacroReader(String source) {
        this.lines = source.lines().toList();
    }

    static List<Statement> read(String source) {
        Objects.requireNonNull(source, "source");
        return new BmsMacroReader(source).statements();
    }

    private List<Statement> statements() {
        List<Statement> out = new ArrayList<>();
        row = 0;
        while (row < lines.size()) {
            String line = lines.get(row);
            if (line.isBlank() || line.startsWith("*") || line.startsWith(".*")) {
                row++;
                continue;
            }
            out.add(statement());
        }
        return List.copyOf(out);
    }

    private Statement statement() {
        int startLine = row + 1;
        column = 0;
        String label = null;
        if (current() != ' ') {
            label = token();
        }
        skipBlanks();
        String operation = token();
        if (operation.isEmpty()) {
            throw new BmsDefinitionException(startLine, "missing macro name");
        }
        skipBlanks();
        Map<String, Value> keywords = new LinkedHashMap<>();
        List<String> positionals = new ArrayList<>();
        if (!atEndOfBody()) {
            operands(startLine, keywords, positionals);
        }
        skipRestOfStatement();
        return new Statement(startLine, label == null ? null : label.toUpperCase(Locale.ROOT),
                operation.toUpperCase(Locale.ROOT), keywords, positionals);
    }

    private void operands(int startLine, Map<String, Value> keywords, List<String> positionals) {
        while (true) {
            String name = token();
            if (name.isEmpty()) {
                throw new BmsDefinitionException(row + 1, "malformed operand");
            }
            if (current() == '=') {
                column++;
                String key = name.toUpperCase(Locale.ROOT);
                if (keywords.containsKey(key)) {
                    throw new BmsDefinitionException(row + 1, "duplicate operand " + key);
                }
                keywords.put(key, value());
            } else {
                positionals.add(name.toUpperCase(Locale.ROOT));
            }
            if (current() != ',') {
                return;
            }
            column++;
            // カンマの直後が空白で行が続くなら、operandは次の行の16桁目から続く
            if (atEndOfBody() || current() == ' ') {
                if (!continued()) {
                    throw new BmsDefinitionException(row + 1,
                            "operand list ends with a comma but the statement is not continued");
                }
                nextContinuationLine();
            }
        }
    }

    private Value value() {
        char first = current();
        if (first == '\'') {
            return new Quoted(quoted());
        }
        if (first == '(') {
            column++;
            List<String> items = new ArrayList<>();
            while (true) {
                if (current() == '\'') {
                    items.add(quoted());
                } else {
                    items.add(token().toUpperCase(Locale.ROOT));
                }
                if (current() == ',') {
                    column++;
                    if (atEndOfBody() || current() == ' ') {
                        if (!continued()) {
                            throw new BmsDefinitionException(row + 1, "unterminated sublist");
                        }
                        nextContinuationLine();
                    }
                    continue;
                }
                if (current() == ')') {
                    column++;
                    return new Sublist(items);
                }
                throw new BmsDefinitionException(row + 1, "unterminated sublist");
            }
        }
        String word = token();
        if (word.isEmpty()) {
            throw new BmsDefinitionException(row + 1, "operand has no value");
        }
        return new Word(word.toUpperCase(Locale.ROOT));
    }

    private String quoted() {
        column++;
        StringBuilder text = new StringBuilder();
        while (true) {
            if (atEndOfBody()) {
                if (!continued()) {
                    throw new BmsDefinitionException(row + 1, "unterminated quoted string");
                }
                nextContinuationLine();
                continue;
            }
            char c = current();
            column++;
            if (c == '\'') {
                if (!atEndOfBody() && current() == '\'') {
                    column++;
                    text.append('\'');
                    continue;
                }
                return text.toString();
            }
            if (c == '&') {
                if (!atEndOfBody() && current() == '&') {
                    column++;
                }
                text.append('&');
                continue;
            }
            text.append(c);
        }
    }

    /** 名前、命令、裸の値に使う語。区切りの記号か空白で終わる。 */
    private String token() {
        StringBuilder out = new StringBuilder();
        while (!atEndOfBody()) {
            char c = current();
            if (c == ' ' || c == ',' || c == '=' || c == '(' || c == ')' || c == '\'') {
                break;
            }
            out.append(c);
            column++;
        }
        return out.toString();
    }

    private void skipBlanks() {
        while (!atEndOfBody() && current() == ' ') {
            column++;
        }
    }

    /** 注記と、注記の続きの行を読み捨てる。 */
    private void skipRestOfStatement() {
        while (continued()) {
            row++;
        }
        row++;
    }

    private boolean continued() {
        String line = lines.get(row);
        return line.length() >= CONTINUATION_COLUMN
                && line.charAt(CONTINUATION_COLUMN - 1) != ' ';
    }

    private void nextContinuationLine() {
        row++;
        if (row >= lines.size()) {
            throw new BmsDefinitionException(row, "continuation line is missing");
        }
        String line = lines.get(row);
        int indent = Math.min(line.length(), CONTINUATION_START - 1);
        if (!line.substring(0, indent).isBlank()) {
            throw new BmsDefinitionException(row + 1,
                    "continuation line must start in column " + CONTINUATION_START);
        }
        column = CONTINUATION_START - 1;
    }

    private boolean atEndOfBody() {
        String line = lines.get(row);
        return column >= Math.min(line.length(), BODY_END);
    }

    private char current() {
        return atEndOfBody() ? ' ' : lines.get(row).charAt(column);
    }
}
