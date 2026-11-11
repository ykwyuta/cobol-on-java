package dev.cobolonjava.pli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * マクロ前処理 (Enterprise PL/I Language Reference, Chapter "Preprocessor facilities")。
 *
 * <p>前処理の文 ({@code %DECLARE}、{@code %} 付きの代入、{@code %IF}、{@code %DO}、
 * {@code %SELECT}、{@code %ACTIVATE}、{@code %DEACTIVATE}、{@code %REPLACE}、{@code %NOTE}、
 * {@code %GO TO}、前処理の手続き) を原文を読み進めながら実行し、活性な名を値で置き換える。
 * 置き換えた値は RESCAN なら読み直す。
 *
 * <p>Zeus の {@code crates/zeus-pli/src/preproc.rs} から移した (同じ権利者の処理系)。Zeus は
 * 前処理の手続きの本体を構文解析器の AST にして実行するが、こちらの構文解析器は実行時の
 * 文を作るので、手続きの本体は字句の並びのまま実行する。実機と突き合わせていない。
 *
 * <p>前処理の文が 1 つも無い原文は<b>1 文字も変えずに</b>返す。{@code %PAGE} などの listing の
 * 指示は構文解析器が読むので、ここでは原文のまま通す。{@code %INCLUDE} はこの前に
 * {@link PliPreprocessor} が展開しているので、{@code %IF} の中の {@code %INCLUDE} も
 * 条件によらず取り込まれる (暫定判断 P-197)。
 */
final class PliMacroProcessor {

    /** 構文解析器が読む listing の指示。前処理では実行せず原文のまま通す。 */
    private static final Set<String> LISTING = Set.of("PAGE", "SKIP", "PRINT", "NOPRINT",
            "PUSH", "POP");

    private enum Kind { IDENT, NUMBER, STRING, OP }

    /**
     * 字句。{@code text} は名なら大文字、文字列なら引用符を外した中身、それ以外は綴りそのもの。
     * {@code raw} は原文の綴り、{@code leading} は前にある空白と注記である。
     */
    private record Tok(Kind kind, String text, String raw, String leading, int line,
                       String suffix) {

        boolean is(String value) {
            return (kind == Kind.OP || kind == Kind.IDENT) && text.equals(value);
        }

        Tok at(int newLine, String newLeading) {
            return new Tok(kind, text, raw, newLeading, newLine, suffix);
        }
    }

    /** 前処理の値。FIXED、CHARACTER、BIT(1) の 3 つである。 */
    private record Val(Long fixed, String chars, Boolean bit) {

        static Val of(long value) {
            return new Val(value, null, null);
        }

        static Val of(String value) {
            return new Val(null, value, null);
        }

        static Val of(boolean value) {
            return new Val(null, null, value);
        }

        boolean isChar() {
            return chars != null;
        }

        long num() {
            if (fixed != null) return fixed;
            if (bit != null) return bit ? 1 : 0;
            String text = chars.strip();
            boolean negative = text.startsWith("-");
            if (negative || text.startsWith("+")) {
                text = text.substring(1).strip();
            }
            try {
                long value = Long.parseLong(text);
                return negative ? -value : value;
            } catch (NumberFormatException notANumber) {
                // 数にならない文字を FIXED へ移すと実機では CONVERSION になるはずである。
                // Zeus は 0 にしている。こちらは断る
                throw new MacroException(0, "cannot convert '" + chars + "' to FIXED");
            }
        }

        /** FIXED は 8 桁の欄に右寄せした文字になる (LRM "Preprocessor data types")。 */
        String text() {
            if (fixed != null) return String.format(Locale.ROOT, "%8d", fixed);
            if (bit != null) return bit ? "1" : "0";
            return chars;
        }

        boolean truth() {
            if (bit != null) return bit;
            if (fixed != null) return fixed != 0;
            return chars.contains("1");
        }
    }

    private enum Scan { RESCAN, ONCE }

    private record Proc(List<String> params, boolean returnsChar, boolean statement,
                        List<Tok> body) {
    }

    /** 文の実行のあとどこへ行くか。 */
    private record Flow(String kind, String label, Val value) {
        static final Flow NEXT = new Flow("NEXT", null, null);
    }

    static final class MacroException extends RuntimeException {
        final int line;

        MacroException(int line, String message) {
            super(message);
            this.line = line;
        }
    }

    private final Map<String, Val> vars = new HashMap<>();
    private final Set<String> chars = new HashSet<>();
    private final Map<String, Proc> procs = new HashMap<>();
    private final Map<String, Scan> active = new HashMap<>();
    private final List<Diagnostic> notes = new ArrayList<>();
    private final String fileName;
    private final StringBuilder out = new StringBuilder();
    private int outLine = 1;
    /** 最後に書き出した字句。文の形の手続きの呼び出しが文の頭にあるかを見る。 */
    private Tok lastOut;
    private int depth;
    private int counter;
    private long gotos;

    private PliMacroProcessor(String fileName) {
        this.fileName = fileName;
    }

    record Result(String source, List<Diagnostic> diagnostics) {
    }

    /** 前処理を実行する。前処理の文が無ければ原文をそのまま返す。 */
    static Result process(String fileName, String source) {
        List<Tok> tokens;
        try {
            tokens = tokenize(source, 1);
        } catch (MacroException unreadable) {
            // 字句にならない原文は、構文解析器が位置つきで断る
            return new Result(source, List.of());
        }
        if (!hasPreprocessorStatement(tokens)) {
            return new Result(source, List.of());
        }
        PliMacroProcessor processor = new PliMacroProcessor(fileName);
        List<Diagnostic> diagnostics = new ArrayList<>();
        try {
            Flow flow = processor.run(tokens, 0, tokens.size());
            if (flow.kind.equals("GOTO")) {
                throw new MacroException(lastLine(tokens), "%GO TO " + flow.label
                        + ": no such label");
            }
            if (flow != Flow.NEXT) {
                throw new MacroException(lastLine(tokens),
                        "%LEAVE / %ITERATE outside a %DO group");
            }
        } catch (MacroException failure) {
            diagnostics.addAll(processor.notes);
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, fileName,
                    Math.max(1, failure.line), 1, failure.getMessage()));
            return new Result(source, List.copyOf(diagnostics));
        }
        diagnostics.addAll(processor.notes);
        // *PROCESS の注記は実行時の算術を決める (PliOptions)。行をまたぐ注記は書き出しで
        // 落ちることがあるので、1 行目の頭に置き直す (行は増やさない)
        StringBuilder head = new StringBuilder();
        java.util.regex.Matcher note = java.util.regex.Pattern
                .compile("/\\*PLI-OPTIONS .*?\\*/", java.util.regex.Pattern.DOTALL)
                .matcher(source);
        while (note.find()) {
            head.append(note.group().replace("\n", " "));
        }
        String body = processor.out.toString();
        for (String kept : head.toString().split("(?<=\\*/)")) {
            if (!kept.isEmpty()) {
                body = body.replace(kept, "");
            }
        }
        return new Result(head + body, List.copyOf(diagnostics));
    }

    private static int lastLine(List<Tok> tokens) {
        return tokens.isEmpty() ? 1 : tokens.get(tokens.size() - 1).line;
    }

    private static boolean hasPreprocessorStatement(List<Tok> t) {
        for (int i = 0; i + 1 < t.size(); i++) {
            if (t.get(i).is("%") && !(t.get(i + 1).kind == Kind.IDENT
                    && LISTING.contains(t.get(i + 1).text))) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 字句

    private static List<Tok> tokenize(String source, int firstLine) {
        List<Tok> tokens = new ArrayList<>();
        int at = 0;
        int line = firstLine;
        int n = source.length();
        while (true) {
            int start = at;
            while (at < n) {
                char c = source.charAt(at);
                if (Character.isWhitespace(c)) {
                    at++;
                } else if (c == '/' && at + 1 < n && source.charAt(at + 1) == '*') {
                    int close = source.indexOf("*/", at + 2);
                    if (close < 0) {
                        throw new MacroException(line, "unterminated comment");
                    }
                    at = close + 2;
                } else {
                    break;
                }
            }
            String leading = source.substring(start, at);
            line += count(leading, '\n');
            if (at >= n) {
                break;
            }
            char c = source.charAt(at);
            int tokenStart = at;
            Kind kind;
            String text;
            String suffix = "";
            if (c == '\'' || c == '"') {
                StringBuilder value = new StringBuilder();
                at++;
                boolean closed = false;
                while (at < n) {
                    char d = source.charAt(at);
                    if (d == c) {
                        if (at + 1 < n && source.charAt(at + 1) == c) {
                            value.append(c);
                            at += 2;
                            continue;
                        }
                        at++;
                        closed = true;
                        break;
                    }
                    value.append(d);
                    at++;
                }
                if (!closed) {
                    throw new MacroException(line, "unterminated string literal");
                }
                int suffixStart = at;
                while (at < n && Character.isLetterOrDigit(source.charAt(at))) {
                    at++;
                }
                suffix = source.substring(suffixStart, at).toUpperCase(Locale.ROOT);
                kind = Kind.STRING;
                text = value.toString();
            } else if (Character.isDigit(c)) {
                while (at < n && (Character.isLetterOrDigit(source.charAt(at))
                        || source.charAt(at) == '.' || source.charAt(at) == '_')) {
                    at++;
                }
                kind = Kind.NUMBER;
                text = source.substring(tokenStart, at);
            } else if (Character.isLetter(c) || c == '_' || c == '$' || c == '#' || c == '@') {
                while (at < n && (Character.isLetterOrDigit(source.charAt(at))
                        || "_$#@".indexOf(source.charAt(at)) >= 0)) {
                    at++;
                }
                kind = Kind.IDENT;
                text = source.substring(tokenStart, at).toUpperCase(Locale.ROOT);
            } else {
                String two = at + 1 < n ? source.substring(at, at + 2) : "";
                if (List.of("^=", "¬=", "<=", ">=", "||", "**", "=>", "->", "^<", "^>", "¬<",
                        "¬>").contains(two)) {
                    at += 2;
                } else {
                    at++;
                }
                kind = Kind.OP;
                text = source.substring(tokenStart, at).replace('¬', '^');
            }
            String raw = source.substring(tokenStart, at);
            tokens.add(new Tok(kind, text, raw, leading, line, suffix));
            line += count(raw, '\n');
        }
        return tokens;
    }

    private static int count(String text, char c) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) n++;
        }
        return n;
    }

    private static String tokensText(List<Tok> t, int from, int to) {
        StringBuilder text = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(t.get(i).raw);
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- 書き出し

    /**
     * 字句を書き出す。原文の行に合わせて改行を置くので、翻訳の診断の行は原文の行のままである。
     * 同じ行の空白と注記はそのまま写す。行をまたぐ注記は改行だけにする。
     */
    private void emit(Tok tok) {
        if (tok.line > outLine) {
            out.append("\n".repeat(tok.line - outLine));
            outLine = tok.line;
            int lastNewline = tok.leading.lastIndexOf('\n');
            String indent = lastNewline >= 0 ? tok.leading.substring(lastNewline + 1)
                    : tok.leading;
            out.append(indent.contains("/*") ? " " : indent);
        } else if (tok.leading.indexOf('\n') < 0) {
            out.append(tok.leading);
        } else {
            out.append(' ');
        }
        out.append(tok.raw);
        outLine += count(tok.raw, '\n');
        lastOut = tok;
    }

    // ---------------------------------------------------------------- 原文の走査

    private MacroException error(Tok at, String message) {
        return new MacroException(at == null ? 1 : at.line, message);
    }

    private Flow run(List<Tok> t, int i, int end) {
        int start = i;
        while (i < end) {
            if (t.get(i).is("%") && i + 1 < end) {
                Tok next = t.get(i + 1);
                if (next.kind == Kind.IDENT && LISTING.contains(next.text)) {
                    int close = statementEnd(t, i + 1, end);
                    for (int j = i; j <= Math.min(close, end - 1); j++) {
                        emit(t.get(j));
                    }
                    i = close + 1;
                    continue;
                }
                int[] next2 = new int[1];
                Flow flow = statement(t, i + 1, end, next2);
                if (flow == Flow.NEXT) {
                    i = next2[0];
                } else if (flow.kind.equals("GOTO")) {
                    int target = labelAt(t, start, end, flow.label);
                    if (target < 0) {
                        return flow;
                    }
                    if (++gotos > 1_000_000) {
                        throw error(t.get(i), "%GO TO loop does not end");
                    }
                    i = target;
                } else {
                    return flow;
                }
                continue;
            }
            i = text(t, i, end);
        }
        return Flow.NEXT;
    }

    /** 本文の字句を 1 つ書き出す (活性な名なら置き換える)。次の位置を返す。 */
    private int text(List<Tok> t, int i, int end) {
        Tok tok = t.get(i);
        Scan scan = tok.kind == Kind.IDENT ? active.get(tok.text) : null;
        if (scan == null) {
            emit(tok);
            return i + 1;
        }
        String name = tok.text;
        Val value;
        int next = i + 1;
        Proc proc = procs.get(name);
        if (proc != null) {
            List<String> args = new ArrayList<>();
            int j = i + 1;
            if (j < end && t.get(j).is("(")) {
                int close = closing(t, j, end);
                int from = j + 1;
                int level = 0;
                for (int k = j + 1; k < close; k++) {
                    if (t.get(k).is("(")) level++;
                    if (t.get(k).is(")")) level--;
                    if (t.get(k).is(",") && level == 0) {
                        args.add(tokensText(t, from, k));
                        from = k + 1;
                    }
                }
                args.add(tokensText(t, from, close));
                j = close + 1;
            }
            if (proc.statement) {
                // 文の形 (STATEMENT): 位置の引数のあとに「仮引数名(引数)」を並べられ、
                // 文を終える ';' も置き換えに含む (LRM "STATEMENT option")
                boolean atStart = lastOut == null || lastOut.is(";") || lastOut.is(":");
                List<String> byPosition = new ArrayList<>(args);
                while (byPosition.size() < proc.params.size()) {
                    byPosition.add(null);
                }
                boolean keywords = false;
                while (j + 1 < end && t.get(j).kind == Kind.IDENT && t.get(j + 1).is("(")
                        && proc.params.contains(t.get(j).text)) {
                    int k = proc.params.indexOf(t.get(j).text);
                    int close = closing(t, j + 1, end);
                    byPosition.set(k, tokensText(t, j + 2, close));
                    keywords = true;
                    j = close + 1;
                }
                if ((atStart || keywords) && j < end && t.get(j).is(";")) {
                    j++;
                }
                args = byPosition.stream().map(a -> a == null ? "" : a).toList();
            }
            value = call(proc, args.stream().map(Val::of).toList(), tok);
            next = j;
        } else {
            value = vars.getOrDefault(name, Val.of(""));
        }
        List<Tok> replacement = new ArrayList<>();
        boolean first = true;
        for (Tok r : tokenize(value.text(), tok.line)) {
            replacement.add(r.at(tok.line, first ? tok.leading + r.leading : r.leading));
            first = false;
        }
        if (scan == Scan.RESCAN) {
            if (++depth > 100) {
                throw error(tok, "replacement of " + name + " does not end");
            }
            // 置き換えた名は、自分の値の中では置き換えない
            Scan saved = active.remove(name);
            try {
                int k = 0;
                while (k < replacement.size()) {
                    k = text(replacement, k, replacement.size());
                }
            } finally {
                if (saved != null) {
                    active.put(name, saved);
                }
                depth--;
            }
        } else {
            replacement.forEach(this::emit);
        }
        return next;
    }

    // ---------------------------------------------------------------- % 文

    /** {@code %} の次 (i) から始まる前処理の文を実行する。次の位置を {@code next[0]} に置く。 */
    private Flow statement(List<Tok> t, int i, int end, int[] next) {
        if (t.get(i).is(";")) {
            next[0] = i + 1;
            return Flow.NEXT;
        }
        List<String> labels = new ArrayList<>();
        int k = labels(t, i, end, labels);
        Tok at = t.get(Math.min(k, end - 1));
        int se = statementEnd(t, k, end);
        if (k >= end || t.get(k).kind != Kind.IDENT) {
            if (!labels.isEmpty() && k < end && t.get(k).is(";")) {
                next[0] = k + 1;
                return Flow.NEXT;
            }
            throw error(at, "preprocessor statement expected");
        }
        String keyword = t.get(k).text;
        int equals = indexAtDepth0(t, k + 1, se, "=");
        if (equals == k + 1 && !Set.of("IF", "DO", "SELECT", "DECLARE", "DCL")
                .contains(keyword)) {
            assign(keyword, eval(t, equals + 1, se, null), null);
            next[0] = se + 1;
            return Flow.NEXT;
        }
        switch (keyword) {
            case "DECLARE", "DCL" -> {
                declare(t, k + 1, se, at);
                next[0] = se + 1;
                return Flow.NEXT;
            }
            case "ACTIVATE", "ACT", "DEACTIVATE", "DEACT" -> {
                boolean on = keyword.startsWith("ACT");
                for (int j = k + 1; j < se; j++) {
                    if (t.get(j).kind != Kind.IDENT) {
                        continue;
                    }
                    String name = t.get(j).text;
                    Scan mode = Scan.RESCAN;
                    if (j + 1 < se && (t.get(j + 1).is("SCAN") || t.get(j + 1).is("NORESCAN"))) {
                        mode = Scan.ONCE;
                        j++;
                    } else if (j + 1 < se && t.get(j + 1).is("RESCAN")) {
                        j++;
                    }
                    if (on) {
                        active.put(name, mode);
                    } else {
                        active.remove(name);
                    }
                }
                next[0] = se + 1;
                return Flow.NEXT;
            }
            case "REPLACE" -> {
                if (k + 3 >= se || t.get(k + 1).kind != Kind.IDENT || !t.get(k + 2).is("BY")) {
                    throw error(at, "%REPLACE identifier BY constant; expected");
                }
                String name = t.get(k + 1).text;
                Tok constant = t.get(k + 3);
                Val value = switch (constant.kind) {
                    case NUMBER -> Val.of(integer(constant));
                    case STRING -> Val.of(constant.text);
                    case IDENT -> Val.of(constant.text);
                    default -> throw error(at, "%REPLACE needs a constant");
                };
                if (value.isChar()) {
                    chars.add(name);
                }
                vars.put(name, value);
                active.put(name, Scan.RESCAN);
                next[0] = se + 1;
                return Flow.NEXT;
            }
            case "NOTE" -> {
                note(t, k + 1, se, at);
                next[0] = se + 1;
                return Flow.NEXT;
            }
            case "IF" -> {
                int then = findPercent(t, k + 1, end, "THEN");
                if (then < 0) {
                    throw error(at, "%THEN expected");
                }
                boolean condition = eval(t, k + 1, then, null).truth();
                int u1 = then + 2;
                int u1e = unitEnd(t, u1, end);
                int u2 = -1;
                int u2e = u1e;
                if (u1e + 1 < end && t.get(u1e).is("%") && t.get(u1e + 1).is("ELSE")) {
                    u2 = u1e + 2;
                    u2e = unitEnd(t, u2, end);
                }
                next[0] = u2e;
                if (condition) {
                    return run(t, u1, u1e);
                }
                return u2 >= 0 ? run(t, u2, u2e) : Flow.NEXT;
            }
            case "DO" -> {
                int body = se + 1;
                int[] ends = groupEnd(t, body, end);
                next[0] = ends[0];
                if (se == k + 2 && t.get(k + 1).is("SKIP")) {
                    return Flow.NEXT;
                }
                return loop(t, k + 1, se, null, labels, () -> run(t, body, ends[1]));
            }
            case "SELECT" -> {
                int[] ends = groupEnd(t, se + 1, end);
                next[0] = ends[0];
                Val subject = se > k + 1 ? eval(t, k + 1, se, null) : null;
                int j = se + 1;
                while (j < ends[1]) {
                    if (!t.get(j).is("%")) {
                        throw error(t.get(j), "%WHEN or %OTHERWISE expected");
                    }
                    Tok word = t.get(j + 1);
                    if (word.is("WHEN")) {
                        int close = closing(t, j + 2, ends[1]);
                        int unitEnd = unitEnd(t, close + 1, ends[1]);
                        if (when(t, j + 3, close, subject, null)) {
                            return run(t, close + 1, unitEnd);
                        }
                        j = unitEnd;
                    } else if (word.is("OTHERWISE") || word.is("OTHER")) {
                        int unitEnd = unitEnd(t, j + 2, ends[1]);
                        return run(t, j + 2, unitEnd);
                    } else {
                        throw error(word, "%WHEN or %OTHERWISE expected");
                    }
                }
                return Flow.NEXT;
            }
            case "LEAVE", "ITERATE" -> {
                String label = k + 1 < se && t.get(k + 1).kind == Kind.IDENT
                        ? t.get(k + 1).text : null;
                next[0] = se + 1;
                return new Flow(keyword, label, null);
            }
            case "PROCEDURE", "PROC" -> {
                int[] ends = groupEnd(t, se + 1, end);
                next[0] = ends[0];
                if (labels.isEmpty()) {
                    throw error(at, "a preprocessor procedure needs a name");
                }
                List<String> params = new ArrayList<>();
                boolean returnsChar = false;
                boolean statementForm = false;
                for (int j = k + 1; j < se; j++) {
                    if (t.get(j).is("(")) {
                        j++;
                        while (j < se && !t.get(j).is(")")) {
                            if (t.get(j).kind == Kind.IDENT) {
                                params.add(t.get(j).text);
                            }
                            j++;
                        }
                    } else if (t.get(j).is("STATEMENT")) {
                        statementForm = true;
                    } else if (t.get(j).is("RETURNS")) {
                        returnsChar = j + 2 < se
                                && (t.get(j + 2).is("CHARACTER") || t.get(j + 2).is("CHAR"));
                        j += 3;
                    }
                }
                List<Tok> body = new ArrayList<>();
                for (int j = se + 1; j < ends[1]; j++) {
                    if (!t.get(j).is("%")) {
                        body.add(t.get(j));
                    }
                }
                procs.put(labels.get(0), new Proc(List.copyOf(params), returnsChar,
                        statementForm, List.copyOf(body)));
                return Flow.NEXT;
            }
            case "GO", "GOTO" -> {
                int j = k + 1;
                if (keyword.equals("GO") && j < se && t.get(j).is("TO")) {
                    j++;
                }
                if (j + 1 != se || t.get(j).kind != Kind.IDENT) {
                    throw error(at, "%GO TO: a label expected");
                }
                next[0] = se + 1;
                return new Flow("GOTO", t.get(j).text, null);
            }
            case "END" -> throw error(at, "%END without a group");
            default -> throw error(at, "preprocessor statement %" + keyword
                    + " is not supported");
        }
    }

    private void note(List<Tok> t, int from, int to, Tok at) {
        // %NOTE(message [, code])。code が 0-3 なら情報、4-7 は警告、8-11 は誤り、12 以上は重大。
        // 16 以上は前処理を止める (LRM "%NOTE statement")
        if (from >= to || !t.get(from).is("(")) {
            throw error(at, "%NOTE(message [, code]) expected");
        }
        int close = closing(t, from, to);
        int comma = indexAtDepth0(t, from + 1, close, ",");
        String message = eval(t, from + 1, comma < 0 ? close : comma, null).text();
        long code = comma < 0 ? 0 : eval(t, comma + 1, close, null).num();
        if (code >= 16) {
            throw error(at, message);
        }
        notes.add(new Diagnostic(code >= 8 ? Diagnostic.Severity.ERROR
                : Diagnostic.Severity.WARNING, fileName, at.line, 1, message));
    }

    private boolean when(List<Tok> t, int from, int to, Val subject, Map<String, Val> locals) {
        int start = from;
        int level = 0;
        for (int k = from; k <= to; k++) {
            if (k < to && t.get(k).is("(")) level++;
            if (k < to && t.get(k).is(")")) level--;
            if (k == to || (t.get(k).is(",") && level == 0)) {
                Val value = eval(t, start, k, locals);
                if (subject == null ? value.truth() : compare(subject, value) == 0) {
                    return true;
                }
                start = k + 1;
            }
        }
        return false;
    }

    /**
     * DO の指定 ({@code spec}) に従って本体を繰り返す。% 文の {@code %DO} と、前処理の手続きの
     * {@code DO} が共に使う。
     */
    private Flow loop(List<Tok> t, int from, int to, Map<String, Val> locals,
                      List<String> labels, Supplier<Flow> body) {
        long[] guard = {0};
        // 1 回動かす。null なら続け、そうでなければその流れで抜ける
        java.util.function.Function<Tok, Flow> once = at -> {
            if (++guard[0] > 100_000) {
                throw error(at, "%DO loop does not end");
            }
            Flow flow = body.get();
            boolean mine = flow.label == null || labels.contains(flow.label);
            if (flow == Flow.NEXT || (flow.kind.equals("ITERATE") && mine)) {
                return null;
            }
            if (flow.kind.equals("LEAVE") && mine) {
                return Flow.NEXT;
            }
            return flow;
        };
        Tok at = t.get(Math.max(0, from - 1));
        if (from == to) {
            Flow flow = once.apply(at);
            return flow == null ? Flow.NEXT : flow;
        }
        Tok head = t.get(from);
        if (head.is("LOOP") || head.is("FOREVER")) {
            while (true) {
                Flow flow = once.apply(at);
                if (flow != null) return flow;
            }
        }
        if (head.is("WHILE") || head.is("UNTIL")) {
            int[] whileUntil = whileUntil(t, from, to);
            while (true) {
                if (whileUntil[0] >= 0 && !eval(t, whileUntil[0], whileUntil[1], locals).truth()) {
                    return Flow.NEXT;
                }
                Flow flow = once.apply(at);
                if (flow != null) return flow;
                if (whileUntil[2] >= 0 && eval(t, whileUntil[2], whileUntil[3], locals).truth()) {
                    return Flow.NEXT;
                }
            }
        }
        if (head.kind != Kind.IDENT || from + 1 >= to || !t.get(from + 1).is("=")) {
            throw error(head, "unsupported preprocessor DO specification");
        }
        if (indexAtDepth0(t, from + 2, to, ",") >= 0) {
            throw error(head, "a preprocessor DO with more than one specification is not"
                    + " supported yet");
        }
        String name = head.text;
        int toAt = keywordAtDepth0(t, from + 2, to, "TO");
        int byAt = keywordAtDepth0(t, from + 2, to, "BY");
        int whileAt = keywordAtDepth0(t, from + 2, to, "WHILE");
        int untilAt = keywordAtDepth0(t, from + 2, to, "UNTIL");
        int repeatAt = keywordAtDepth0(t, from + 2, to, "REPEAT");
        int[] stops = {toAt, byAt, whileAt, untilAt, repeatAt, to};
        Val start = eval(t, from + 2, stop(stops, from + 2), locals);
        Long limit = toAt >= 0 ? eval(t, toAt + 1, stop(stops, toAt + 1), locals).num() : null;
        long by = byAt >= 0 ? eval(t, byAt + 1, stop(stops, byAt + 1), locals).num() : 1;
        assign(name, start, locals);
        while (true) {
            long current = lookup(name, locals).num();
            if (limit != null && ((by >= 0 && current > limit) || (by < 0 && current < limit))) {
                return Flow.NEXT;
            }
            if (whileAt >= 0 && !eval(t, whileAt + 1, stop(stops, whileAt + 1), locals)
                    .truth()) {
                return Flow.NEXT;
            }
            Flow flow = once.apply(at);
            if (flow != null) return flow;
            if (untilAt >= 0 && eval(t, untilAt + 1, stop(stops, untilAt + 1), locals)
                    .truth()) {
                return Flow.NEXT;
            }
            if (repeatAt >= 0) {
                assign(name, eval(t, repeatAt + 1, stop(stops, repeatAt + 1), locals), locals);
            } else if (limit == null && byAt < 0) {
                return Flow.NEXT;
            } else {
                assign(name, Val.of(lookup(name, locals).num() + by), locals);
            }
        }
    }

    private static int stop(int[] stops, int after) {
        int nearest = Integer.MAX_VALUE;
        for (int s : stops) {
            if (s >= after && s < nearest) nearest = s;
        }
        return nearest;
    }

    /** {@code WHILE(..)} と {@code UNTIL(..)} の式の範囲 [whileFrom, whileTo, untilFrom, untilTo]。 */
    private int[] whileUntil(List<Tok> t, int from, int to) {
        int[] ranges = {-1, -1, -1, -1};
        int j = from;
        while (j < to) {
            boolean isWhile = t.get(j).is("WHILE");
            if (!(isWhile || t.get(j).is("UNTIL")) || j + 1 >= to || !t.get(j + 1).is("(")) {
                throw error(t.get(j), "unsupported preprocessor DO specification");
            }
            int close = closing(t, j + 1, to);
            ranges[isWhile ? 0 : 2] = j + 2;
            ranges[isWhile ? 1 : 3] = close;
            j = close + 1;
        }
        return ranges;
    }

    private void declare(List<Tok> t, int from, int to, Tok at) {
        int i = from;
        while (i < to) {
            List<String> names = new ArrayList<>();
            if (t.get(i).is("(")) {
                i++;
                while (i < to && !t.get(i).is(")")) {
                    if (t.get(i).kind == Kind.IDENT) {
                        names.add(t.get(i).text);
                    }
                    i++;
                }
                i++;
            } else if (t.get(i).kind == Kind.IDENT) {
                names.add(t.get(i).text);
                i++;
            } else {
                throw error(at, "invalid %DECLARE");
            }
            boolean character = false;
            boolean entry = false;
            Scan scan = Scan.RESCAN;
            boolean inactive = false;
            Val initial = null;
            while (i < to && !t.get(i).is(",")) {
                Tok word = t.get(i);
                if (word.is("CHARACTER") || word.is("CHAR")) {
                    character = true;
                } else if (word.is("FIXED")) {
                    character = false;
                } else if (word.is("ENTRY")) {
                    entry = true;
                } else if (word.is("BUILTIN") || word.is("NOSCAN")) {
                    inactive = true;
                } else if (word.is("SCAN") || word.is("NORESCAN")) {
                    scan = Scan.ONCE;
                } else if (word.is("RESCAN")) {
                    scan = Scan.RESCAN;
                } else if ((word.is("INITIAL") || word.is("INIT")) && i + 1 < to
                        && t.get(i + 1).is("(")) {
                    int close = closing(t, i + 1, to);
                    initial = eval(t, i + 2, close, null);
                    i = close;
                } else if (word.is("(")) {
                    // 次元 (配列) はまだ持たない
                    throw error(word, "preprocessor arrays are not supported yet");
                } else {
                    throw error(word, "unsupported %DECLARE attribute " + word.raw);
                }
                i++;
            }
            i++;
            for (String name : names) {
                if (!entry) {
                    Val value = initial != null ? initial
                            : character ? Val.of("") : Val.of(0L);
                    if (character) {
                        chars.add(name);
                        value = Val.of(value.text());
                    } else {
                        value = Val.of(value.num());
                    }
                    vars.put(name, value);
                }
                if (inactive) {
                    active.remove(name);
                } else {
                    active.put(name, scan);
                }
            }
        }
    }

    // ---------------------------------------------------------------- 手続き

    private Val call(Proc proc, List<Val> args, Tok at) {
        if (++depth > 100) {
            throw error(at, "preprocessor procedures nested too deeply");
        }
        try {
            Map<String, Val> locals = new HashMap<>();
            for (int i = 0; i < proc.params.size(); i++) {
                locals.put(proc.params.get(i), i < args.size() ? args.get(i) : Val.of(""));
            }
            Flow flow = execute(proc.body, 0, proc.body.size(), locals);
            if (flow.kind.equals("GOTO")) {
                throw error(at, "GO TO " + flow.label + ": no such label");
            }
            Val value = flow.kind.equals("RETURN") ? flow.value : Val.of("");
            return proc.returnsChar ? Val.of(value.text()) : Val.of(value.num());
        } finally {
            depth--;
        }
    }

    /** 前処理の手続きの文の並びを実行する。 */
    private Flow execute(List<Tok> t, int i, int end, Map<String, Val> locals) {
        int start = i;
        while (i < end) {
            int[] next = new int[1];
            Flow flow = executeOne(t, i, end, locals, next);
            if (flow == Flow.NEXT) {
                i = next[0];
            } else if (flow.kind.equals("GOTO")) {
                int target = bodyLabelAt(t, start, end, flow.label);
                if (target < 0) {
                    return flow;
                }
                if (++gotos > 1_000_000) {
                    throw error(t.get(i), "GO TO loop does not end");
                }
                i = target;
            } else {
                return flow;
            }
        }
        return Flow.NEXT;
    }

    private Flow executeOne(List<Tok> t, int i, int end, Map<String, Val> locals, int[] next) {
        List<String> labels = new ArrayList<>();
        int k = labels(t, i, end, labels);
        if (k >= end) {
            next[0] = end;
            return Flow.NEXT;
        }
        Tok at = t.get(k);
        int se = statementEnd(t, k, end);
        if (at.is(";")) {
            next[0] = k + 1;
            return Flow.NEXT;
        }
        String keyword = at.kind == Kind.IDENT ? at.text : "";
        int equals = indexAtDepth0(t, k + 1, se, "=");
        if (equals == k + 1) {
            assign(keyword, eval(t, equals + 1, se, locals), locals);
            next[0] = se + 1;
            return Flow.NEXT;
        }
        switch (keyword) {
            case "DECLARE", "DCL" -> {
                localDeclare(t, k + 1, se, locals);
                next[0] = se + 1;
                return Flow.NEXT;
            }
            case "RETURN" -> {
                next[0] = se + 1;
                Val value = se > k + 1 ? eval(t, k + 1, se, locals) : Val.of("");
                return new Flow("RETURN", null, value);
            }
            case "IF" -> {
                int then = keywordAtDepth0(t, k + 1, se + 1, "THEN");
                if (then < 0) {
                    throw error(at, "THEN expected");
                }
                boolean condition = eval(t, k + 1, then, locals).truth();
                int u1e = bodyUnitEnd(t, then + 1, end);
                int u2 = -1;
                int u2e = u1e;
                if (u1e < end && t.get(u1e).is("ELSE")) {
                    u2 = u1e + 1;
                    u2e = bodyUnitEnd(t, u2, end);
                }
                next[0] = u2e;
                if (condition) {
                    return execute(t, then + 1, u1e, locals);
                }
                return u2 >= 0 ? execute(t, u2, u2e, locals) : Flow.NEXT;
            }
            case "DO" -> {
                int[] ends = bodyGroupEnd(t, se + 1, end);
                next[0] = ends[0];
                return loop(t, k + 1, se, locals, labels,
                        () -> execute(t, se + 1, ends[1], locals));
            }
            case "SELECT" -> {
                int[] ends = bodyGroupEnd(t, se + 1, end);
                next[0] = ends[0];
                Val subject = se > k + 1 ? eval(t, k + 1, se, locals) : null;
                int j = se + 1;
                while (j < ends[1]) {
                    if (t.get(j).is("WHEN")) {
                        int close = closing(t, j + 1, ends[1]);
                        int unitEnd = bodyUnitEnd(t, close + 1, ends[1]);
                        if (when(t, j + 2, close, subject, locals)) {
                            return execute(t, close + 1, unitEnd, locals);
                        }
                        j = unitEnd;
                    } else if (t.get(j).is("OTHERWISE") || t.get(j).is("OTHER")) {
                        int unitEnd = bodyUnitEnd(t, j + 1, ends[1]);
                        return execute(t, j + 1, unitEnd, locals);
                    } else {
                        throw error(t.get(j), "WHEN or OTHERWISE expected");
                    }
                }
                return Flow.NEXT;
            }
            case "LEAVE", "ITERATE" -> {
                next[0] = se + 1;
                return new Flow(keyword, se > k + 1 ? t.get(k + 1).text : null, null);
            }
            case "GO", "GOTO" -> {
                int j = k + 1;
                if (keyword.equals("GO") && j < se && t.get(j).is("TO")) {
                    j++;
                }
                if (j + 1 != se || t.get(j).kind != Kind.IDENT) {
                    throw error(at, "GO TO: a label expected");
                }
                next[0] = se + 1;
                return new Flow("GOTO", t.get(j).text, null);
            }
            default -> throw error(at, "statement not supported in a preprocessor procedure: "
                    + at.raw);
        }
    }

    private void localDeclare(List<Tok> t, int from, int to, Map<String, Val> locals) {
        int i = from;
        while (i < to) {
            List<String> names = new ArrayList<>();
            if (t.get(i).is("(")) {
                int close = closing(t, i, to);
                for (int j = i + 1; j < close; j++) {
                    if (t.get(j).kind == Kind.IDENT) names.add(t.get(j).text);
                }
                i = close + 1;
            } else {
                names.add(t.get(i).text);
                i++;
            }
            boolean character = false;
            while (i < to && !t.get(i).is(",")) {
                if (t.get(i).is("CHARACTER") || t.get(i).is("CHAR")) {
                    character = true;
                } else if (!t.get(i).is("FIXED")) {
                    throw error(t.get(i), "unsupported attribute in a preprocessor procedure: "
                            + t.get(i).raw);
                }
                i++;
            }
            i++;
            for (String name : names) {
                // 仮引数は渡された値を宣言の型へ移したものになる
                Val argument = locals.get(name);
                locals.put(name, character
                        ? Val.of(argument == null ? "" : argument.text())
                        : Val.of(argument == null ? 0L : argument.num()));
            }
        }
    }

    // ---------------------------------------------------------------- 値

    private void assign(String name, Val value, Map<String, Val> locals) {
        if (locals != null && locals.containsKey(name)) {
            // 局所の変数は宣言の型を保つ
            locals.put(name, locals.get(name).isChar() ? Val.of(value.text())
                    : Val.of(value.num()));
            return;
        }
        if (chars.contains(name)) {
            vars.put(name, Val.of(value.text()));
        } else if (!vars.containsKey(name)) {
            throw new MacroException(0, "preprocessor variable " + name + " is not declared");
        } else {
            vars.put(name, Val.of(value.num()));
        }
    }

    private Val lookup(String name, Map<String, Val> locals) {
        if (locals != null && locals.containsKey(name)) {
            return locals.get(name);
        }
        Val value = vars.get(name);
        if (value == null) {
            throw new MacroException(0, "preprocessor variable " + name + " is not declared");
        }
        return value;
    }

    private Val eval(List<Tok> t, int from, int to, Map<String, Val> locals) {
        if (from >= to) {
            throw error(t.get(Math.min(from, t.size() - 1)), "preprocessor expression expected");
        }
        int[] at = {from};
        try {
            Val value = binary(t, at, to, 0, locals);
            if (at[0] != to) {
                throw error(t.get(at[0]), "invalid preprocessor expression");
            }
            return value;
        } catch (MacroException failure) {
            if (failure.line == 0) {
                throw error(t.get(from), failure.getMessage());
            }
            throw failure;
        }
    }

    private static int precedence(Tok tok) {
        if (tok.kind != Kind.OP) return -1;
        return switch (tok.text) {
            case "|" -> 1;
            case "&" -> 2;
            case "=", "^=", "<", ">", "<=", ">=", "^<", "^>" -> 3;
            case "||" -> 4;
            case "+", "-" -> 5;
            case "*", "/" -> 6;
            case "**" -> 7;
            default -> -1;
        };
    }

    private Val binary(List<Tok> t, int[] at, int to, int minimum, Map<String, Val> locals) {
        Val left = unary(t, at, to, locals);
        while (at[0] < to) {
            Tok op = t.get(at[0]);
            int p = precedence(op);
            if (p < 0 || p < minimum) {
                break;
            }
            at[0]++;
            // ** は右から結ぶ
            Val right = binary(t, at, to, op.text.equals("**") ? p : p + 1, locals);
            left = apply(op, left, right);
        }
        return left;
    }

    private Val apply(Tok op, Val x, Val y) {
        return switch (op.text) {
            case "+" -> Val.of(x.num() + y.num());
            case "-" -> Val.of(x.num() - y.num());
            case "*" -> Val.of(x.num() * y.num());
            case "/" -> {
                if (y.num() == 0) {
                    throw error(op, "ZERODIVIDE in a preprocessor expression");
                }
                yield Val.of(x.num() / y.num());
            }
            case "**" -> Val.of(Math.round(Math.pow(x.num(), y.num())));
            case "||" -> Val.of(x.text() + y.text());
            case "&" -> Val.of(x.truth() && y.truth());
            case "|" -> Val.of(x.truth() || y.truth());
            default -> {
                int c = compare(x, y);
                yield Val.of(switch (op.text) {
                    case "=" -> c == 0;
                    case "^=" -> c != 0;
                    case "<" -> c < 0;
                    case ">" -> c > 0;
                    case "<=", "^>" -> c <= 0;
                    default -> c >= 0;
                });
            }
        };
    }

    private Val unary(List<Tok> t, int[] at, int to, Map<String, Val> locals) {
        if (at[0] >= to) {
            throw error(t.get(Math.min(at[0], t.size() - 1)), "preprocessor expression expected");
        }
        Tok tok = t.get(at[0]++);
        if (tok.is("-") || tok.is("+")) {
            Val value = binary(t, at, to, 7, locals);
            return Val.of(tok.is("-") ? -value.num() : value.num());
        }
        if (tok.is("^")) {
            return Val.of(!binary(t, at, to, 7, locals).truth());
        }
        if (tok.is("(")) {
            Val value = binary(t, at, to, 0, locals);
            if (at[0] >= to || !t.get(at[0]).is(")")) {
                throw error(tok, ") expected in a preprocessor expression");
            }
            at[0]++;
            return value;
        }
        if (tok.kind == Kind.NUMBER) {
            return Val.of(integer(tok));
        }
        if (tok.kind == Kind.STRING) {
            if (tok.suffix.equals("B")) {
                return Val.of(tok.text.contains("1"));
            }
            if (!tok.suffix.isEmpty()) {
                throw error(tok, "string constant with suffix " + tok.suffix
                        + " is not supported in a preprocessor expression");
            }
            return Val.of(tok.text);
        }
        if (tok.kind != Kind.IDENT) {
            throw error(tok, "invalid preprocessor expression");
        }
        List<Val> args = new ArrayList<>();
        boolean called = false;
        if (at[0] < to && t.get(at[0]).is("(")) {
            called = true;
            int close = closing(t, at[0], to);
            int start = at[0] + 1;
            int level = 0;
            for (int k = start; k <= close; k++) {
                if (k < close && t.get(k).is("(")) level++;
                if (k < close && t.get(k).is(")")) level--;
                if (k == close || (t.get(k).is(",") && level == 0)) {
                    if (k > start) {
                        args.add(eval(t, start, k, locals));
                    }
                    start = k + 1;
                }
            }
            at[0] = close + 1;
        }
        String name = tok.text;
        if (locals != null && locals.containsKey(name) && !called) {
            return locals.get(name);
        }
        Proc proc = procs.get(name);
        if (proc != null) {
            return call(proc, args, tok);
        }
        Val builtin = builtin(name, args);
        if (builtin != null) {
            return builtin;
        }
        if (!called && vars.containsKey(name)) {
            return vars.get(name);
        }
        // Zeus は宣言していない名を空の文字にする。こちらは断る
        throw error(tok, "preprocessor variable " + name + " is not declared");
    }

    private long integer(Tok number) {
        String digits = number.text.split("\\.")[0];
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException notInteger) {
            throw error(number, "preprocessor constant " + number.raw + " is not supported");
        }
    }

    private static int compare(Val x, Val y) {
        if (x.isChar() && y.isChar()) {
            // 文字どうしは短いほうを空白で埋めて比べる
            int n = Math.max(x.chars.length(), y.chars.length());
            String a = x.chars + " ".repeat(n - x.chars.length());
            String b = y.chars + " ".repeat(n - y.chars.length());
            return Integer.signum(a.compareTo(b));
        }
        return Long.compare(x.num(), y.num());
    }

    /** 前処理の組み込み関数 (LRM "Preprocessor built-in functions")。持たないものは null。 */
    private Val builtin(String name, List<Val> a) {
        java.util.function.IntFunction<String> s = i -> i < a.size() ? a.get(i).text() : "";
        java.util.function.IntFunction<Long> n = i -> i < a.size() ? a.get(i).num() : null;
        return switch (name) {
            case "LENGTH" -> Val.of(s.apply(0).length());
            case "SUBSTR" -> {
                String text = s.apply(0);
                int start = (int) Math.max(1, n.apply(1) == null ? 1 : n.apply(1)) - 1;
                if (start > text.length()) {
                    yield Val.of("");
                }
                int length = n.apply(2) == null ? text.length() - start
                        : (int) Math.max(0, n.apply(2));
                yield Val.of(text.substring(start, Math.min(text.length(), start + length)));
            }
            case "INDEX" -> Val.of(s.apply(0).indexOf(s.apply(1)) + 1);
            case "VERIFY" -> {
                String text = s.apply(0);
                String set = s.apply(1);
                int position = 0;
                for (int i = 0; i < text.length(); i++) {
                    if (set.indexOf(text.charAt(i)) < 0) {
                        position = i + 1;
                        break;
                    }
                }
                yield Val.of(position);
            }
            case "TRIM" -> Val.of(s.apply(0).strip());
            case "UPPERCASE" -> Val.of(s.apply(0).toUpperCase(Locale.ROOT));
            case "LOWERCASE" -> Val.of(s.apply(0).toLowerCase(Locale.ROOT));
            case "COPY" -> Val.of(s.apply(0).repeat((int) Math.max(0, n.apply(1))));
            case "REPEAT" -> Val.of(s.apply(0).repeat((int) Math.max(0, n.apply(1)) + 1));
            case "TRANSLATE" -> {
                String to = s.apply(1);
                String from = s.apply(2);
                StringBuilder text = new StringBuilder();
                for (char c : s.apply(0).toCharArray()) {
                    int p = from.indexOf(c);
                    text.append(p < 0 ? c : p < to.length() ? to.charAt(p) : ' ');
                }
                yield Val.of(text.toString());
            }
            case "MAX" -> Val.of(a.stream().mapToLong(Val::num).max().orElse(0));
            case "MIN" -> Val.of(a.stream().mapToLong(Val::num).min().orElse(0));
            case "QUOTE" -> Val.of("'" + s.apply(0).replace("'", "''") + "'");
            case "COUNTER" -> Val.of(String.format(Locale.ROOT, "%05d", ++counter));
            case "PARMSET" -> Val.of(!a.isEmpty() && !a.get(0).text().isEmpty());
            default -> null;
        };
    }

    // ---------------------------------------------------------------- 字句の並びの形

    private static int statementEnd(List<Tok> t, int i, int end) {
        int level = 0;
        for (; i < end; i++) {
            if (t.get(i).is("(")) level++;
            else if (t.get(i).is(")")) level--;
            else if (t.get(i).is(";") && level <= 0) return i;
        }
        return end;
    }

    /** 名札 ({@code 名:}) を読み飛ばして文の頭の位置を返す。 */
    private static int labels(List<Tok> t, int i, int end, List<String> labels) {
        while (i + 1 < end && t.get(i).kind == Kind.IDENT && t.get(i + 1).is(":")) {
            labels.add(t.get(i).text);
            i += 2;
        }
        return i;
    }

    private int closing(List<Tok> t, int open, int end) {
        int level = 0;
        for (int j = open; j < end; j++) {
            if (t.get(j).is("(")) level++;
            if (t.get(j).is(")") && --level == 0) return j;
        }
        throw error(t.get(open), "unbalanced parentheses in a preprocessor statement");
    }

    private static int indexAtDepth0(List<Tok> t, int from, int to, String op) {
        int level = 0;
        for (int j = from; j < to; j++) {
            if (t.get(j).is("(")) level++;
            else if (t.get(j).is(")")) level--;
            else if (level == 0 && t.get(j).kind == Kind.OP && t.get(j).text.equals(op)) return j;
        }
        return -1;
    }

    private static int keywordAtDepth0(List<Tok> t, int from, int to, String word) {
        int level = 0;
        for (int j = from; j < Math.min(to, t.size()); j++) {
            if (t.get(j).is("(")) level++;
            else if (t.get(j).is(")")) level--;
            else if (level == 0 && t.get(j).kind == Kind.IDENT && t.get(j).text.equals(word)) {
                return j;
            }
        }
        return -1;
    }

    private static int labelAt(List<Tok> t, int i, int end, String label) {
        for (; i + 1 < end; i++) {
            if (t.get(i).is("%")) {
                List<String> found = new ArrayList<>();
                labels(t, i + 1, end, found);
                if (found.contains(label)) return i;
            }
        }
        return -1;
    }

    private static int bodyLabelAt(List<Tok> t, int i, int end, String label) {
        for (; i + 1 < end; i++) {
            if (t.get(i).kind == Kind.IDENT && t.get(i).text.equals(label) && t.get(i + 1).is(":")
                    && (i == 0 || t.get(i - 1).is(";") || t.get(i - 1).is(":"))) {
                return i;
            }
        }
        return -1;
    }

    /** {@code %DO} などの本体の後ろ: [%END の文の次, %END の % の位置]。 */
    private int[] groupEnd(List<Tok> t, int i, int end) {
        int level = 1;
        for (; i < end; i++) {
            if (t.get(i).is("%")) {
                List<String> ignored = new ArrayList<>();
                int k = labels(t, i + 1, end, ignored);
                if (k >= end) break;
                Tok word = t.get(k);
                if (word.is("DO") || word.is("SELECT") || word.is("PROCEDURE") || word.is("PROC")) {
                    level++;
                } else if (word.is("END") && --level == 0) {
                    return new int[] {statementEnd(t, k, end) + 1, i};
                }
            }
        }
        throw error(t.get(Math.min(i, t.size() - 1)), "%END expected");
    }

    /** %THEN / %ELSE の後ろの単位 (% 文、または % の DO・SELECT の組) の終わり。 */
    private int unitEnd(List<Tok> t, int i, int end) {
        if (i >= end || !t.get(i).is("%")) {
            throw error(t.get(Math.min(i, end - 1)),
                    "a preprocessor statement must follow %THEN / %ELSE");
        }
        List<String> ignored = new ArrayList<>();
        int k = labels(t, i + 1, end, ignored);
        Tok word = t.get(k);
        if (word.is("DO") || word.is("SELECT")) {
            return groupEnd(t, statementEnd(t, k, end) + 1, end)[0];
        }
        if (word.is("IF")) {
            int then = findPercent(t, k + 1, end, "THEN");
            if (then < 0) {
                throw error(word, "%THEN expected");
            }
            int j = unitEnd(t, then + 2, end);
            if (j + 1 < end && t.get(j).is("%") && t.get(j + 1).is("ELSE")) {
                j = unitEnd(t, j + 2, end);
            }
            return j;
        }
        return statementEnd(t, k, end) + 1;
    }

    private static int findPercent(List<Tok> t, int i, int end, String word) {
        int level = 0;
        for (; i + 1 < end; i++) {
            if (t.get(i).is("(")) level++;
            else if (t.get(i).is(")")) level--;
            else if (level == 0 && t.get(i).is("%") && t.get(i + 1).is(word)) return i;
        }
        return -1;
    }

    /** 手続きの本体の DO・SELECT の組の後ろ: [END の文の次, END の位置]。 */
    private int[] bodyGroupEnd(List<Tok> t, int i, int end) {
        while (i < end) {
            List<String> ignored = new ArrayList<>();
            int k = labels(t, i, end, ignored);
            if (k < end && t.get(k).is("END")) {
                return new int[] {statementEnd(t, k, end) + 1, k};
            }
            i = bodyUnitEnd(t, i, end);
        }
        throw error(t.get(Math.min(i, t.size() - 1)), "END expected");
    }

    /** 手続きの本体で、i から始まる文 (IF・DO・SELECT はその組ごと) の終わり。 */
    private int bodyUnitEnd(List<Tok> t, int i, int end) {
        List<String> ignored = new ArrayList<>();
        int k = labels(t, i, end, ignored);
        if (k >= end) {
            throw error(t.get(Math.min(i, t.size() - 1)), "statement expected");
        }
        Tok word = t.get(k);
        boolean assignment = k + 1 < end && t.get(k + 1).is("=");
        if (!assignment && (word.is("DO") || word.is("SELECT"))) {
            return bodyGroupEnd(t, statementEnd(t, k, end) + 1, end)[0];
        }
        if (!assignment && word.is("IF")) {
            int then = keywordAtDepth0(t, k + 1, end, "THEN");
            if (then < 0) {
                throw error(word, "THEN expected");
            }
            int j = bodyUnitEnd(t, then + 1, end);
            if (j < end && t.get(j).is("ELSE")) {
                j = bodyUnitEnd(t, j + 1, end);
            }
            return j;
        }
        return statementEnd(t, k, end) + 1;
    }
}
