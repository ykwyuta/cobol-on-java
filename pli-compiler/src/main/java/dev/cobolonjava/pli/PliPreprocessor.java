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
    private static final Pattern RULES_ANS = Pattern.compile("\\bRULES\\s*\\([^)]*\\bANS\\b");
    private static final Pattern LIMITS = Pattern.compile("\\bLIMITS\\s*\\(");
    private final IncludeResolver includes;

    public PliPreprocessor(IncludeResolver includes) {
        this.includes = Objects.requireNonNull(includes, "includes");
    }

    public static PliPreprocessor withoutIncludes() {
        return new PliPreprocessor(name -> java.util.Optional.empty());
    }

    public Result process(String fileName, String source) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Matcher process = PROCESS.matcher(source);
        while (process.find()) {
            String options = process.group().toUpperCase(Locale.ROOT);
            // 算術の結果の精度と、数を文字にしたときの幅はこの 2 つで変わる。既定の規則
            // (RULES(IBM)、LIMITS の既定) しか持たないので、読み飛ばして違う数を出すより断る (P-183)
            if (RULES_ANS.matcher(options).find() || LIMITS.matcher(options).find()) {
                diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, fileName,
                        lineOf(source, process.start()), 1,
                        "*PROCESS RULES(ANS) and LIMITS are not supported yet (P-183)"));
            }
        }
        String current = PROCESS.matcher(source).replaceAll("\n");
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
