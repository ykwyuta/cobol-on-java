package dev.cobolonjava.pli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PL/I のコンパイラ指示と include を、字句解析より前に処理する。 */
public final class PliPreprocessor {

    private static final Pattern PROCESS = Pattern.compile(
            "(?im)^\\s*\\*PROCESS\\b[^;]*;\\s*");
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
        List<Diagnostic> diagnostics = new ArrayList<>();
        // *PROCESS の算術の指定 (RULES と LIMITS) は、実行時の原文に注記として残し、実行時の
        // 算術も同じ規則にする (PliOptions)。以前は RULES(ANS) と LIMITS を断っていた (P-183)
        Matcher process = PROCESS.matcher(source);
        StringBuilder kept = new StringBuilder();
        while (process.find()) {
            String options = process.group().strip();
            options = options.substring("*PROCESS".length(), options.length() - 1).strip();
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
                return new Result(current, List.copyOf(diagnostics));
            }
            StringBuffer expanded = new StringBuffer();
            do {
                String member = matcher.group(1).toUpperCase(Locale.ROOT);
                String replacement = includes.resolve(member).orElse(null);
                if (replacement == null) {
                    int line = 1 + current.substring(0, matcher.start()).replace("\r", "")
                            .length() - current.substring(0, matcher.start()).replace("\r", "")
                            .replace("\n", "").length();
                    diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, fileName,
                            line, 1, "include member not found: " + member));
                    replacement = "";
                }
                matcher.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
            } while (matcher.find());
            matcher.appendTail(expanded);
            current = expanded.toString();
        }
        diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, fileName, 1, 1,
                "include nesting exceeds 32 levels"));
        return new Result(current, List.copyOf(diagnostics));
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
