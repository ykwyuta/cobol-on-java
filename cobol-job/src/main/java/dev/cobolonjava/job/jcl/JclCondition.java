package dev.cobolonjava.job.jcl;

import dev.cobolonjava.job.JobDiagnostic;
import dev.cobolonjava.job.StepCondition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code IF} の関係式 (要件 FR-131)。
 *
 * <p>{@code COND} と違って<b>「真なら動かす」</b>の向きで書く。内部モデルの向きと同じなので、
 * 裏返す必要がない。
 *
 * <pre>
 * //       IF (STEP1.RC = 0 &amp; RC &lt; 8) THEN
 * //       IF (ABEND) THEN
 * //       IF (¬STEP1.RUN) THEN
 * </pre>
 *
 * <p>結び付けは {@code &} ({@code AND})、{@code |} ({@code OR})、{@code ¬} ({@code NOT})。
 * 括弧で優先順位を書ける。{@code &} は {@code |} より強い。
 */
public final class JclCondition {

    private final List<String> tokens;
    private final JclCard card;
    private final List<JobDiagnostic> diagnostics;
    private int at;
    private boolean failed;

    private JclCondition(List<String> tokens, JclCard card, List<JobDiagnostic> diagnostics) {
        this.tokens = tokens;
        this.card = card;
        this.diagnostics = diagnostics;
    }

    /**
     * 関係式を読む。
     *
     * @return 読めなければ {@code null}
     */
    public static StepCondition parse(String text, JclCard card,
                                      List<JobDiagnostic> diagnostics) {
        JclCondition parser = new JclCondition(tokenize(text), card, diagnostics);
        StepCondition condition = parser.or();
        if (parser.failed) {
            return null;
        }
        if (parser.at < parser.tokens.size()) {
            parser.report("unexpected text in IF: " + parser.tokens.get(parser.at));
            return null;
        }
        return condition;
    }

    // ---- 字句 ----

    private static boolean isWordCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '#' || c == '@' || c == '$';
    }

    private static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (isWordCharacter(c)) {
                int end = i;
                while (end < text.length() && isWordCharacter(text.charAt(end))) {
                    end++;
                }
                out.add(text.substring(i, end));
                i = end;
                continue;
            }
            // 2 文字の関係演算子を先に見る
            if (i + 1 < text.length()) {
                String pair = text.substring(i, i + 2);
                if (pair.equals("<=") || pair.equals(">=") || pair.equals("<>")
                        || pair.equals("¬=") || pair.equals("!=")) {
                    out.add(pair);
                    i += 2;
                    continue;
                }
            }
            out.add(String.valueOf(c));
            i++;
        }
        return out;
    }

    // ---- 構文 ----

    private StepCondition or() {
        List<StepCondition> parts = new ArrayList<>();
        parts.add(and());
        while (match("|") || match("OR")) {
            parts.add(and());
        }
        return parts.size() == 1 ? parts.get(0) : new StepCondition.Any(parts);
    }

    private StepCondition and() {
        List<StepCondition> parts = new ArrayList<>();
        parts.add(not());
        while (match("&") || match("AND")) {
            parts.add(not());
        }
        return parts.size() == 1 ? parts.get(0) : new StepCondition.All(parts);
    }

    private StepCondition not() {
        if (match("¬") || match("!") || match("NOT")) {
            StepCondition inner = not();
            return inner == null ? null : new StepCondition.Not(inner);
        }
        return primary();
    }

    private StepCondition primary() {
        if (match("(")) {
            StepCondition inner = or();
            if (!match(")")) {
                report("IF is missing a closing parenthesis");
            }
            return inner;
        }
        return test();
    }

    /**
     * 試験 1 個。
     *
     * <p>形は 3 つある。{@code RC 関係 値}、{@code 名前.RC 関係 値}、
     * {@code ABEND} と {@code 名前.RUN} である。
     */
    private StepCondition test() {
        String word = word();
        if (word == null) {
            return null;
        }
        String upper = word.toUpperCase(Locale.ROOT);
        if (upper.equals("ABEND")) {
            return new StepCondition.OnlyIfAbend();
        }
        if (upper.equals("ABENDCC")) {
            report("ABENDCC is not supported yet");
            return null;
        }
        if (upper.equals("RC")) {
            return returnCode(null);
        }
        int dot = upper.lastIndexOf('.');
        if (dot < 0) {
            report("IF does not understand: " + word);
            return null;
        }
        String step = upper.substring(0, dot);
        String kind = upper.substring(dot + 1);
        return switch (kind) {
            case "RC" -> returnCode(step);
            case "RUN" -> new StepCondition.Ran(step);
            case "ABEND" -> new StepCondition.OnlyIfAbend();
            case "ABENDCC" -> {
                report("ABENDCC is not supported yet");
                yield null;
            }
            default -> {
                report("IF does not understand: " + word);
                yield null;
            }
        };
    }

    private StepCondition returnCode(String step) {
        String operator = word();
        if (operator == null) {
            return null;
        }
        StepCondition.Comparison comparison = comparisonOf(operator);
        if (comparison == null) {
            report("unknown comparison in IF: " + operator);
            return null;
        }
        String number = word();
        if (number == null) {
            return null;
        }
        try {
            return new StepCondition.ReturnCode(step, comparison, Integer.parseInt(number));
        } catch (NumberFormatException e) {
            report("a return code must be an integer: " + number);
            return null;
        }
    }

    private static StepCondition.Comparison comparisonOf(String text) {
        return switch (text.toUpperCase(Locale.ROOT)) {
            case "=", "==", "EQ" -> StepCondition.Comparison.EQ;
            case "<>", "!=", "¬=", "NE" -> StepCondition.Comparison.NE;
            case "<", "LT" -> StepCondition.Comparison.LT;
            case "<=", "LE" -> StepCondition.Comparison.LE;
            case ">", "GT" -> StepCondition.Comparison.GT;
            case ">=", "GE" -> StepCondition.Comparison.GE;
            default -> null;
        };
    }

    private boolean match(String token) {
        if (at < tokens.size() && tokens.get(at).equalsIgnoreCase(token)) {
            at++;
            return true;
        }
        return false;
    }

    private String word() {
        if (at >= tokens.size()) {
            report("IF ends in the middle of a condition");
            return null;
        }
        return tokens.get(at++);
    }

    private void report(String message) {
        if (!failed) {
            diagnostics.add(new JobDiagnostic(card.line(), message));
            failed = true;
        }
    }
}
