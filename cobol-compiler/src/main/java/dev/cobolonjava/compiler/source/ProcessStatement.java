package dev.cobolonjava.compiler.source;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * プロセス文 {@code CBL} / {@code PROCESS} の読み取り (要件 FR-093)。
 *
 * <pre>
 * CBL     SOURCEFORMAT(FREE),TRUNC(BIN),APOST
 * PROCESS NOSEQ
 * </pre>
 *
 * <h2>読み取りより前に処理する</h2>
 * <p>プロセス文は<b>参照形式そのものを決める</b> ({@code SOURCEFORMAT})。したがって
 * 読み取り器を選ぶ前に、生のソースから取り出さなければならない。鶏と卵に見えるが、
 * プロセス文はソースの先頭に、注釈と空行だけを挟んで現れるという制約があるため、
 * カラムの解釈をほとんどせずに読める。
 *
 * <h2>行は空にして残す</h2>
 * <p>取り出したプロセス文の行は<b>削らずに空行へ置き換える</b>。行を削ると、
 * 以降のすべての行番号がずれて、診断が元のソースを指せなくなる (要件 FR-094)。
 *
 * <h2>カラムの解釈は最小限にする</h2>
 * <p>行の全体と、一連番号領域を除いた部分の両方を見て、先頭の語が {@code CBL} または
 * {@code PROCESS} であればプロセス文とみなす。この時点では参照形式が決まっていないので、
 * どちらのカラム規則も前提にできない。
 */
public final class ProcessStatement {

    private static final int SEQUENCE_AREA_WIDTH = 6;

    private ProcessStatement() {
    }

    /**
     * プロセス文を取り出した結果。
     *
     * @param options 読み取ったオプション
     * @param source  プロセス文の行を空行へ置き換えたソース
     */
    public record Scan(CompilerOptions options, String source) {
    }

    /** ソースの先頭からプロセス文を読み取る。 */
    public static Scan scan(String source) {
        String[] lines = source.split("\\R", -1);
        Map<String, String> options = new LinkedHashMap<>();
        int i = 0;
        boolean changed = false;

        for (; i < lines.length; i++) {
            String statement = statementText(lines[i]);
            if (statement != null) {
                parseOptions(statement, options);
                lines[i] = "";
                changed = true;
                continue;
            }
            if (!isSkippable(lines[i])) {
                break;
            }
        }
        return new Scan(new CompilerOptions(options),
                changed ? String.join("\n", lines) : source);
    }

    /**
     * プロセス文であればオプションの並びを、そうでなければ {@code null} を返す。
     */
    private static String statementText(String line) {
        String direct = optionsAfterKeyword(line);
        if (direct != null) {
            return direct;
        }
        return line.length() > SEQUENCE_AREA_WIDTH
                ? optionsAfterKeyword(line.substring(SEQUENCE_AREA_WIDTH))
                : null;
    }

    private static String optionsAfterKeyword(String text) {
        String trimmed = text.strip();
        for (String keyword : new String[] {"CBL", "PROCESS"}) {
            if (trimmed.length() >= keyword.length()
                    && trimmed.regionMatches(true, 0, keyword, 0, keyword.length())) {
                String rest = trimmed.substring(keyword.length());
                if (rest.isEmpty() || rest.charAt(0) == ' ') {
                    return rest.strip();
                }
            }
        }
        return null;
    }

    /** プロセス文の前に置いてよい行か。空行と注釈行だけである。 */
    private static boolean isSkippable(String line) {
        if (line.isBlank()) {
            return true;
        }
        if (line.strip().startsWith(SourceText.INLINE_COMMENT)) {
            return true;
        }
        // 固定形式の注釈行。7 桁目が * または /
        return line.length() >= SourceLine.INDICATOR_COLUMN
                && (line.charAt(SourceLine.INDICATOR_COLUMN - 1) == '*'
                    || line.charAt(SourceLine.INDICATOR_COLUMN - 1) == '/');
    }

    /**
     * オプションの並びを読む。処理系の起動時に与えられた指定も同じ綴りで書ける
     * ようにするため、外から呼べる形にしてある (要件 FR-093)。
     *
     * @param text {@code SSRANGE,ARITH(EXTEND)} のような並び
     */
    public static CompilerOptions parse(String text) {
        Map<String, String> options = new LinkedHashMap<>();
        parseOptions(text, options);
        return new CompilerOptions(options);
    }

    /**
     * オプションの並びを読む。区切りは読点か空白、値は括弧の中に書く。
     * 括弧の中の読点は区切りではない ({@code XREF(SHORT,FULL)} のような指定があるため)。
     */
    private static void parseOptions(String text, Map<String, String> options) {
        for (String token : split(text)) {
            int paren = token.indexOf('(');
            if (paren < 0) {
                options.put(token.toUpperCase(Locale.ROOT), "");
                continue;
            }
            if (!token.endsWith(")")) {
                throw new SourceFormatException(
                        "unbalanced parenthesis in a compiler option: " + token);
            }
            options.put(token.substring(0, paren).toUpperCase(Locale.ROOT),
                    token.substring(paren + 1, token.length() - 1));
        }
    }

    private static List<String> split(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    throw new SourceFormatException(
                            "unbalanced parenthesis in a compiler option: " + text);
                }
            }
            if (depth == 0 && (c == ',' || c == ' ')) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (depth != 0) {
            throw new SourceFormatException("unbalanced parenthesis in a compiler option: " + text);
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
