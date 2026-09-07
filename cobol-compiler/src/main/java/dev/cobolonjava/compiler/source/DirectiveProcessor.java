package dev.cobolonjava.compiler.source;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * コンパイラ指示文の処理 (要件 FR-092)。
 *
 * <pre>
 * &gt;&gt;DEFINE 定数名 AS 値 [OVERRIDE] | AS OFF | AS PARAMETER
 * &gt;&gt;IF 条件 ... &gt;&gt;ELSE ... &gt;&gt;END-IF
 * &gt;&gt;EVALUATE 対象 ... &gt;&gt;WHEN 値 ... &gt;&gt;WHEN OTHER ... &gt;&gt;END-EVALUATE
 * &gt;&gt;PUSH ALL ... &gt;&gt;POP ALL
 * &gt;&gt;CALLINTERFACE 種別
 * </pre>
 *
 * <h2>指示文は行で終わる</h2>
 * <p>指示文に終止符はない。<b>行が終われば指示文も終わる</b>。正規化後のテキストには
 * 行の区切りが残っていないため、語が持つ出自 (ファイルと行) の変わり目を行の終わりとみなす。
 * 文字単位で出自を保持している設計が、ここでも効いている。
 *
 * <h2>COPY と REPLACE のあとに処理する</h2>
 * <p>コピー句の中に書かれた {@code >>IF} を処理し、かつ主ソースの {@code >>DEFINE} を
 * そこへ届けるためである。展開してから偽の分岐を捨てる順序になるが、
 * <b>結果は同じ</b>である — {@code >>IF} で囲まれた {@code COPY} も、展開されたうえで捨てられる。
 * 違いが出るのは、捨てられる側のコピー句が存在しないときだけである (暫定判断 P-020)。
 *
 * <h2>未定義の定数との比較は誤りとする</h2>
 * <p>{@code >>IF X = 1} の {@code X} が未定義のとき、これを黙って偽とすると
 * <b>定数名の打ち間違いが条件の書き間違いとして通ってしまう</b>。条件付き翻訳は
 * 「どちらのソースが生きているか」を決めるものであり、黙って落とすのは最も避けたい誤りである。
 * 定義の有無を問いたいときは {@code >>IF X DEFINED} を使う。
 */
public final class DirectiveProcessor {

    /** 指示文の始まり。 */
    private static final String PREFIX = ">>";

    private final Map<String, String> parameters;
    private State state = new State();
    private final Deque<State> pushed = new ArrayDeque<>();
    private final Deque<Frame> frames = new ArrayDeque<>();

    private DirectiveProcessor(Map<String, String> parameters) {
        this.parameters = parameters;
    }

    /** 指示文を処理する。{@code AS PARAMETER} に供給する値はない。 */
    public static NormalizedSource apply(NormalizedSource source) {
        return apply(source, Map.of());
    }

    /**
     * 指示文を処理する。
     *
     * @param parameters {@code >>DEFINE 名 AS PARAMETER} に供給する値。
     *                   翻訳時オプションから与えられる
     */
    public static NormalizedSource apply(NormalizedSource source, Map<String, String> parameters) {
        return PreprocessorLexer.emit(apply(PreprocessorLexer.lex(source), parameters));
    }

    /** 語の列に対して指示文を処理する。 */
    public static List<TextWord> apply(List<TextWord> words, Map<String, String> parameters) {
        return new DirectiveProcessor(parameters).run(words);
    }

    private List<TextWord> run(List<TextWord> words) {
        List<TextWord> out = new ArrayList<>();
        int i = 0;
        while (i < words.size()) {
            TextWord word = words.get(i);
            if (isDirective(word)) {
                int end = lineEnd(words, i);
                execute(words.subList(i, end));
                i = end;
                continue;
            }
            if (outputting()) {
                out.add(word);
            }
            i++;
        }
        if (!frames.isEmpty()) {
            throw new SourceFormatException(frames.peek().origin,
                    "conditional compilation directive is not terminated");
        }
        return out;
    }

    private static boolean isDirective(TextWord word) {
        return word.kind() == TextWordKind.WORD && word.text().startsWith(PREFIX);
    }

    /** 指示文が置かれた行の終わり。出自の行が変わったところである。 */
    private static int lineEnd(List<TextWord> words, int start) {
        Origin origin = words.get(start).origin();
        int i = start + 1;
        while (i < words.size()) {
            Origin next = words.get(i).origin();
            if (next.line() != origin.line() || !next.fileName().equals(origin.fileName())) {
                break;
            }
            i++;
        }
        return i;
    }

    private void execute(List<TextWord> line) {
        Origin origin = line.get(0).origin();
        String name = line.get(0).text().substring(PREFIX.length()).toUpperCase(Locale.ROOT);
        List<TextWord> operands = line.subList(1, line.size());

        switch (name) {
            case "IF" -> pushIf(origin, operands);
            case "ELSE" -> elseBranch(origin, operands);
            case "END-IF" -> endBlock(origin, "IF");
            case "EVALUATE" -> pushEvaluate(origin, operands);
            case "WHEN" -> whenBranch(origin, operands);
            case "END-EVALUATE" -> endBlock(origin, "EVALUATE");
            // 以下は分岐に関係しないので、生きている枝の中だけで効く
            case "DEFINE" -> {
                if (outputting()) {
                    define(origin, operands);
                }
            }
            case "PUSH" -> {
                if (outputting()) {
                    pushed.push(state.copy());
                }
            }
            case "POP" -> {
                if (outputting()) {
                    pop(origin);
                }
            }
            case "CALLINTERFACE" -> {
                if (outputting()) {
                    state.callInterface = joined(operands).toUpperCase(Locale.ROOT);
                }
            }
            default -> throw new SourceFormatException(origin,
                    "unknown compiler directive: " + line.get(0).text());
        }
    }

    // ---- 条件付き翻訳 ----

    private void pushIf(Origin origin, List<TextWord> operands) {
        boolean parent = outputting();
        boolean taken = parent && evaluateCondition(origin, operands);
        frames.push(Frame.conditional(origin, parent, taken));
    }

    private void elseBranch(Origin origin, List<TextWord> operands) {
        Frame frame = requireFrame(origin, "IF", "ELSE");
        if (frame.sawElse) {
            throw new SourceFormatException(origin, ">>ELSE appears twice for one >>IF");
        }
        frame.sawElse = true;
        if (!operands.isEmpty()) {
            throw new SourceFormatException(origin, ">>ELSE takes no operands");
        }
        frame.active = frame.parentActive && !frame.branchTaken;
        frame.branchTaken = true;
    }

    private void pushEvaluate(Origin origin, List<TextWord> operands) {
        boolean parent = outputting();
        if (operands.size() == 1 && operands.get(0).isWord("TRUE")) {
            // >>WHEN が条件を書く形。対象は持たない
            frames.push(Frame.truthEvaluate(origin, parent));
            return;
        }
        if (!parent) {
            // 死んでいる枝の中では対象を解決しない。未定義の定数で誤りにしないためである
            frames.push(Frame.evaluate(origin, false, null));
            return;
        }
        Resolved subject = resolve(origin, operands, 0);
        requireEnd(origin, operands, subject.next());
        frames.push(Frame.evaluate(origin, true, requireDefined(origin, subject)));
    }

    private void whenBranch(Origin origin, List<TextWord> operands) {
        Frame frame = requireFrame(origin, "EVALUATE", "WHEN");
        boolean matched;
        if (operands.size() == 1 && operands.get(0).isWord("OTHER")) {
            matched = !frame.branchTaken;
        } else if (frame.branchTaken || !frame.parentActive) {
            matched = false;
        } else if (frame.truthMode) {
            matched = evaluateCondition(origin, operands);
        } else {
            Resolved when = resolve(origin, operands, 0);
            requireEnd(origin, operands, when.next());
            matched = compare(origin, frame.subject, "=", requireDefined(origin, when));
        }
        frame.active = frame.parentActive && matched;
        frame.branchTaken |= matched;
    }

    private void endBlock(Origin origin, String opener) {
        Frame frame = frames.peek();
        if (frame == null || !frame.opener.equals(opener)) {
            throw new SourceFormatException(origin, ">>END-" + opener + " without >>" + opener);
        }
        frames.pop();
    }

    private Frame requireFrame(Origin origin, String opener, String directive) {
        Frame frame = frames.peek();
        if (frame == null || !frame.opener.equals(opener)) {
            throw new SourceFormatException(origin,
                    ">>" + directive + " without >>" + opener);
        }
        return frame;
    }

    private boolean outputting() {
        Frame frame = frames.peek();
        return frame == null || frame.active;
    }

    // ---- 条件式 ----

    /**
     * 条件を評価する。対応するのは以下の 2 つの形である。
     *
     * <pre>
     * 被演算子 [IS] [NOT] DEFINED
     * 被演算子 [IS] [NOT] 関係演算子 被演算子
     * </pre>
     */
    private boolean evaluateCondition(Origin origin, List<TextWord> operands) {
        if (operands.isEmpty()) {
            throw new SourceFormatException(origin, "a condition is required");
        }
        Resolved left = resolve(origin, operands, 0);
        int i = left.next();
        if (i < operands.size() && operands.get(i).isWord("IS")) {
            i++;
        }
        boolean negated = false;
        if (i < operands.size() && operands.get(i).isWord("NOT")) {
            negated = true;
            i++;
        }
        if (i >= operands.size()) {
            throw new SourceFormatException(origin,
                    "a condition requires DEFINED or a relational operator");
        }

        boolean result;
        if (operands.get(i).isWord("DEFINED")) {
            result = left.defined();
            i++;
        } else {
            String operator = operands.get(i).text();
            i++;
            if (i >= operands.size()) {
                throw new SourceFormatException(origin,
                        "the relational operator " + operator + " requires an operand");
            }
            Resolved right = resolve(origin, operands, i);
            i = right.next();
            result = compare(origin, requireDefined(origin, left), operator,
                    requireDefined(origin, right));
        }
        requireEnd(origin, operands, i);
        return negated != result;
    }

    private static void requireEnd(Origin origin, List<TextWord> operands, int i) {
        if (i != operands.size()) {
            throw new SourceFormatException(origin,
                    "unexpected text in a directive: " + operands.get(i).text());
        }
    }

    private boolean compare(Origin origin, Value left, String operator, Value right) {
        int order = left.compareTo(origin, right);
        return switch (operator) {
            case "=", "==" -> order == 0;
            case "<" -> order < 0;
            case ">" -> order > 0;
            case "<=" -> order <= 0;
            case ">=" -> order >= 0;
            case "<>" -> order != 0;
            default -> throw new SourceFormatException(origin,
                    "unknown relational operator: " + operator);
        };
    }

    private Value requireDefined(Origin origin, Resolved resolved) {
        if (!resolved.defined()) {
            // 打ち間違いを黙って偽にしない。定義の有無は DEFINED で問う
            throw new SourceFormatException(origin,
                    "undefined compilation constant: " + resolved.name());
        }
        return resolved.value();
    }

    /** 被演算子 1 個を読む。定数名は値へ解決する。 */
    private Resolved resolve(Origin origin, List<TextWord> operands, int index) {
        if (index >= operands.size()) {
            throw new SourceFormatException(origin, "an operand is required");
        }
        TextWord word = operands.get(index);
        if (word.kind() == TextWordKind.LITERAL) {
            return new Resolved(word.text(), Value.of(unquote(word.text())), true, index + 1);
        }
        String text = word.text();
        BigDecimal number = number(text);
        if (number != null) {
            return new Resolved(text, Value.of(number), true, index + 1);
        }
        String key = text.toUpperCase(Locale.ROOT);
        Value value = state.constants.get(key);
        return new Resolved(text, value, value != null, index + 1);
    }

    // ---- DEFINE ----

    private void define(Origin origin, List<TextWord> operands) {
        if (operands.isEmpty()) {
            throw new SourceFormatException(origin, ">>DEFINE requires a constant-name");
        }
        String name = operands.get(0).text().toUpperCase(Locale.ROOT);
        int i = 1;
        if (i >= operands.size() || !operands.get(i).isWord("AS")) {
            throw new SourceFormatException(origin, ">>DEFINE requires AS");
        }
        i++;
        if (i >= operands.size()) {
            throw new SourceFormatException(origin, ">>DEFINE requires a value");
        }

        if (operands.get(i).isWord("OFF")) {
            state.constants.remove(name);
            return;
        }

        boolean override = operands.get(operands.size() - 1).isWord("OVERRIDE");
        List<TextWord> value = operands.subList(i, override ? operands.size() - 1 : operands.size());
        if (!override && state.constants.containsKey(name)) {
            // 黙って上書きすると、どちらの定義が効いているか追えなくなる
            throw new SourceFormatException(origin, "compilation constant " + name
                    + " is already defined; add OVERRIDE to redefine it");
        }

        if (value.size() == 1 && value.get(0).isWord("PARAMETER")) {
            String supplied = parameters.get(name);
            if (supplied == null) {
                // 供給されなければ定義されない。>>IF ... DEFINED で分岐できる
                state.constants.remove(name);
                return;
            }
            // 供給される値は COBOL のソース片である。引用符付きなら文字定数として読む
            state.constants.put(name, valueOf(supplied));
            return;
        }
        if (value.size() != 1) {
            throw new SourceFormatException(origin,
                    ">>DEFINE takes a single literal as its value");
        }
        TextWord single = value.get(0);
        state.constants.put(name, single.kind() == TextWordKind.LITERAL
                ? Value.of(unquote(single.text()))
                : valueOf(single.text()));
    }

    private void pop(Origin origin) {
        if (pushed.isEmpty()) {
            throw new SourceFormatException(origin, ">>POP without >>PUSH");
        }
        state = pushed.pop();
    }

    private static Value valueOf(String text) {
        if (text.length() >= 2 && (text.charAt(0) == '\'' || text.charAt(0) == '"')
                && text.charAt(text.length() - 1) == text.charAt(0)) {
            return Value.of(unquote(text));
        }
        BigDecimal number = number(text);
        return number != null ? Value.of(number) : Value.of(text);
    }

    private static BigDecimal number(String text) {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String unquote(String text) {
        char quote = text.charAt(0);
        return text.substring(1, text.length() - 1).replace("" + quote + quote, "" + quote);
    }

    private static String joined(List<TextWord> words) {
        StringBuilder sb = new StringBuilder();
        for (TextWord word : words) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(word.text());
        }
        return sb.toString();
    }

    /** 定数の値。数字と文字列を区別する。比較の規則が違うためである。 */
    private record Value(String text, BigDecimal number) {

        static Value of(String text) {
            return new Value(text, null);
        }

        static Value of(BigDecimal number) {
            return new Value(number.toPlainString(), number);
        }

        int compareTo(Origin origin, Value other) {
            if (number != null && other.number != null) {
                return number.compareTo(other.number);
            }
            if (number != null || other.number != null) {
                throw new SourceFormatException(origin,
                    "a numeric and an alphanumeric compilation constant cannot be compared");
            }
            return text.compareTo(other.text);
        }
    }

    /** 解決した被演算子。未定義でも名前を保つ。誤りに名前を出すためである。 */
    private record Resolved(String name, Value value, boolean defined, int next) {
    }

    /** {@code >>PUSH} / {@code >>POP} で退避する状態。 */
    private static final class State {

        private Map<String, Value> constants = new HashMap<>();
        private String callInterface;

        State copy() {
            State copy = new State();
            copy.constants = new HashMap<>(constants);
            copy.callInterface = callInterface;
            return copy;
        }
    }

    /** 条件付き翻訳の入れ子 1 段。 */
    private static final class Frame {

        private final String opener;
        private final Origin origin;
        private final boolean parentActive;
        private final Value subject;
        private final boolean truthMode;
        private boolean active;
        private boolean branchTaken;
        private boolean sawElse;

        private Frame(String opener, Origin origin, boolean parentActive, Value subject,
                      boolean truthMode) {
            this.opener = opener;
            this.origin = origin;
            this.parentActive = parentActive;
            this.subject = subject;
            this.truthMode = truthMode;
        }

        static Frame conditional(Origin origin, boolean parentActive, boolean taken) {
            Frame frame = new Frame("IF", origin, parentActive, null, false);
            frame.active = taken;
            frame.branchTaken = taken;
            return frame;
        }

        static Frame evaluate(Origin origin, boolean parentActive, Value subject) {
            return new Frame("EVALUATE", origin, parentActive, subject, false);
        }

        static Frame truthEvaluate(Origin origin, boolean parentActive) {
            return new Frame("EVALUATE", origin, parentActive, null, true);
        }
    }
}
