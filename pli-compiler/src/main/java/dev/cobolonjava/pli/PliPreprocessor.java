package dev.cobolonjava.pli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PL/I のコンパイラ指示と include を、字句解析より前に処理する。 */
public final class PliPreprocessor {

    /** {@code *PROCESS} と前処理の {@code %PROCESS} (LRM "%PROCESS statement")。 */
    private static final Pattern PROCESS = Pattern.compile(
            "(?im)^\\s*[*%]PROCESS\\b[^;]*;\\s*");
    /** {@code %PROCINC member;}: *PROCESS だけを持つメンバーを取り込む (LRM "%PROCINC directive")。 */
    private static final Pattern PROCINC = Pattern.compile(
            "(?im)^\\s*%PROCINC\\s+([A-Z_$#@][A-Z0-9_$#@]*)\\s*;");
    private static final Pattern INCLUDE = Pattern.compile(
            "(?im)%INCLUDE\\s+([A-Z_$#@][A-Z0-9_$#@.-]*)\\s*;");
    private final IncludeResolver includes;

    public PliPreprocessor(IncludeResolver includes) {
        this.includes = Objects.requireNonNull(includes, "includes");
    }

    public static PliPreprocessor withoutIncludes() {
        return new PliPreprocessor(name -> java.util.Optional.empty());
    }

    public Result process(String fileName, String source) {
        java.util.Set<String> included = new java.util.HashSet<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        Matcher procinc = PROCINC.matcher(source);
        StringBuilder withProcinc = new StringBuilder();
        while (procinc.find()) {
            String member = procinc.group(1).toUpperCase(Locale.ROOT);
            String text = includes.resolve(member).orElse(null);
            String replacement = "";
            if (text == null) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, fileName,
                        lineOf(source, procinc.start()), 1, "%PROCINC member not found: " + member));
            } else {
                String rest = PROCESS.matcher(text).replaceAll("").replaceAll("(?s)/\\*.*?\\*/", "").strip();
                if (!rest.isEmpty()) {
                    // 取り込むメンバーは *PROCESS の文だけを持たなければならない
                    diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, fileName,
                            lineOf(source, procinc.start()), 1, "%PROCINC member " + member
                                    + " contains a statement other than *PROCESS"));
                } else {
                    Matcher kept = PROCESS.matcher(text);
                    StringBuilder processes = new StringBuilder();
                    while (kept.find()) processes.append(kept.group().strip()).append(' ');
                    replacement = processes.toString().strip() + "\n";
                }
            }
            procinc.appendReplacement(withProcinc, Matcher.quoteReplacement(replacement));
        }
        procinc.appendTail(withProcinc);
        source = withProcinc.toString();
        boolean linedir = false;
        // *PROCESS の算術の指定 (RULES と LIMITS) は、実行時の原文に注記として残し、実行時の
        // 算術も同じ規則にする (PliOptions)。以前は RULES(ANS) と LIMITS を断っていた (P-183)
        Matcher process = PROCESS.matcher(source);
        StringBuilder kept = new StringBuilder();
        while (process.find()) {
            String options = process.group().strip();
            options = options.substring("*PROCESS".length(), options.length() - 1).strip();
            // LINEDIR: %LINE を許し、%INCLUDE を許さない (Programming Guide "LINEDIR")
            linedir |= options.toUpperCase(Locale.ROOT).matches("(?s).*\\bLINEDIR\\b.*")
                    && !options.toUpperCase(Locale.ROOT).matches("(?s).*\\bNOLINEDIR\\b.*");
            try {
                PliOptions.DEFAULT.with(options);
            } catch (IllegalArgumentException invalid) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, fileName,
                        lineOf(source, process.start()), 1, invalid.getMessage()));
            }
            process.appendReplacement(kept, Matcher.quoteReplacement(
                    PliOptions.note(options) + "\n"));
        }
        process.appendTail(kept);
        String current = kept.toString();
        // 展開後にも include が現れる場合があるため、有限の深さで繰り返す。
        for (int depth = 0; depth < 32; depth++) {
            Matcher matcher = INCLUDE.matcher(current);
            if (!matcher.find()) {
                // include を展開しきってから、マクロ前処理の文を実行する
                PliMacroProcessor.Result macro = PliMacroProcessor.process(fileName, current,
                        includes, included, linedir);
                diagnostics.addAll(macro.diagnostics());
                return new Result(macro.source(), List.copyOf(diagnostics));
            }
            if (linedir) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, fileName,
                        lineOf(current, matcher.start()), 1,
                        "%INCLUDE is not allowed under the LINEDIR compiler option"));
            }
            StringBuffer expanded = new StringBuffer();
            do {
                String member = matcher.group(1).toUpperCase(Locale.ROOT);
                included.add(member);
                String replacement = includes.resolve(member).orElse(null);
                if (replacement == null) {
                    int line = 1 + current.substring(0, matcher.start()).replace("\r", "")
                            .length() - current.substring(0, matcher.start()).replace("\r", "")
                            .replace("\n", "").length();
                    diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, fileName,
                            line, 1, "include member not found: " + member));
                    replacement = "";
                }
                // 差し込んだ行の数だけ後ろの行番号がずれる。差し込んだあとに「ここは元の何行目か」
                // の印を置き、字句解析がそこから数え直す (SOURCELINE と診断の行を原文の行にする)
                replacement = replacement + LINE_MARK + logicalLine(current, matcher.end()) + "*/";
                matcher.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
            } while (matcher.find());
            matcher.appendTail(expanded);
            current = expanded.toString();
        }
        diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, fileName, 1, 1,
                "include nesting exceeds 32 levels"));
        return new Result(current, List.copyOf(diagnostics));
    }

    /** 行番号の印。字句解析 (PliSyntax) がこの注記を読んで行を数え直す。 */
    static final String LINE_MARK = "/*#LINE ";

    /** 印で数え直したときの、その位置の行番号。 */
    private static int logicalLine(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            } else if (source.startsWith(LINE_MARK, i)) {
                int end = source.indexOf("*/", i);
                line = Integer.parseInt(source.substring(i + LINE_MARK.length(), end).strip());
                i = end + 1;
            }
        }
        return line;
    }

    private static int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }

    public record Result(String source, List<Diagnostic> diagnostics) {
        public boolean succeeded() {
            return diagnostics.stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
        }
    }
}
