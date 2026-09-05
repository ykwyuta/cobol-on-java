package dev.cobolonjava.job.jcl;

import dev.cobolonjava.job.JobDiagnostic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 目録手続きとシンボリックパラメタの展開 (要件 FR-131)。
 *
 * <p>カードを読み終えたあと、モデルを組み立てる前に挟まる段である。ここを抜けたカードには
 * <b>手続きの呼び出しもシンボリックパラメタも残っていない</b>。組み立てる側は、手続きを
 * 知らずに済む。
 *
 * <p>COBOL のプリプロセッサと構文解析の分け方と同じ考え方である。字面の置き換えを先に
 * 済ませておけば、意味を取る側は 1 つの形だけを見ればよい。
 *
 * <h2>手続きの中の名前</h2>
 * <p>手続きが 1 ステップなら、展開したステップの名前は<b>呼び出し側の名前</b>である。
 * 複数ステップなら {@code 呼び出し名.手続きの中の名前} になる。1 つのときに素直な名前が
 * 付くほうが、{@code COND} で名指すときに書きやすい。
 */
public final class JclExpander {

    /** 手続きの入れ子の深さの上限。これを超えるのは書き間違いである。 */
    private static final int MAX_DEPTH = 8;

    private final JclLibrary library;
    private final List<JobDiagnostic> diagnostics;
    /** ジョブの中に書かれた手続き。{@code PROC} から {@code PEND} までである。 */
    private final Map<String, List<JclCard>> inStream = new LinkedHashMap<>();
    private final JclSymbols symbols = new JclSymbols();

    private JclExpander(JclLibrary library, List<JobDiagnostic> diagnostics) {
        this.library = library;
        this.diagnostics = diagnostics;
    }

    /** カードを展開する。 */
    public static List<JclCard> expand(List<JclCard> cards, JclLibrary library,
                                       List<JobDiagnostic> diagnostics) {
        JclExpander expander = new JclExpander(library, diagnostics);
        return expander.expand(expander.collectProcedures(cards), expander.symbols, 0);
    }

    /**
     * ジョブの中に書かれた手続きを取り分ける。
     *
     * <p>{@code PROC} から {@code PEND} までは、そこで動くのではなく<b>呼ばれたときに動く</b>。
     */
    private List<JclCard> collectProcedures(List<JclCard> cards) {
        List<JclCard> out = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            JclCard card = cards.get(i);
            if (!card.operation().equals("PROC") || card.name() == null) {
                if (card.operation().equals("PEND")) {
                    report(card, "PEND without a matching PROC");
                    continue;
                }
                out.add(card);
                continue;
            }
            String name = card.name().toUpperCase(Locale.ROOT);
            List<JclCard> body = new ArrayList<>();
            body.add(card);
            int at = i + 1;
            while (at < cards.size() && !cards.get(at).operation().equals("PEND")) {
                body.add(cards.get(at));
                at++;
            }
            if (at >= cards.size()) {
                report(card, "PROC " + name + " is not closed by PEND");
                return out;
            }
            inStream.put(name, body);
            i = at;
        }
        return out;
    }

    /**
     * カードを展開する。
     *
     * @param scope この範囲で効くシンボリックパラメタ。手続きの中では束ねたものになる
     */
    private List<JclCard> expand(List<JclCard> cards, JclSymbols scope, int depth) {
        List<JclCard> out = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            JclCard card = cards.get(i);
            switch (card.operation()) {
                case "SET" -> readSet(card, scope);
                case "INCLUDE" -> include(card, out, scope, depth);
                case "PEND", "PROC" -> report(card, card.operation() + " is out of place");
                case "EXEC" -> i = exec(card, cards, out, scope, depth, i);
                default -> out.add(substitute(card, scope));
            }
        }
        return out;
    }

    /** {@code SET 名前=値}。以降のカードで効く。 */
    private void readSet(JclCard card, JclSymbols scope) {
        for (String operand : JclOperands.split(substitute(card, scope).operands())) {
            String key = JclOperands.key(operand);
            if (key.isEmpty() || operand.trim().equals(key)) {
                report(card, "SET takes name=value: " + operand);
                continue;
            }
            scope.put(key, JclOperands.unquote(JclOperands.value(operand)));
        }
    }

    /** {@code INCLUDE MEMBER=名前}。メンバの本文をその場へ差し込む。 */
    private void include(JclCard card, List<JclCard> out, JclSymbols scope, int depth) {
        String member = memberOf(card, "INCLUDE", scope);
        if (member == null) {
            return;
        }
        List<JclCard> body = read(card, member, depth);
        if (body != null) {
            out.addAll(expand(body, scope, depth + 1));
        }
    }

    /**
     * {@code EXEC}。{@code PGM=} ならそのまま、手続きの名前なら展開する。
     *
     * @return 読み進めた位置
     */
    private int exec(JclCard card, List<JclCard> cards, List<JclCard> out, JclSymbols scope,
                     int depth, int at) {
        JclCard resolved = substitute(card, scope);
        String procedure = procedureOf(resolved);
        if (procedure == null) {
            out.add(resolved);
            return at;
        }
        // 手続きの呼び出しに続く DD カードは、手続きの中の DD への上書きである
        List<JclCard> overrides = new ArrayList<>();
        int next = at + 1;
        while (next < cards.size() && cards.get(next).operation().equals("DD")) {
            overrides.add(substitute(cards.get(next), scope));
            next++;
        }
        expandProcedure(resolved, procedure, overrides, out, scope, depth);
        return next - 1;
    }

    /** {@code EXEC} が呼ぶ手続きの名前。{@code PGM=} が書かれていれば {@code null}。 */
    private static String procedureOf(JclCard card) {
        String name = null;
        for (String operand : JclOperands.split(card.operands())) {
            String key = JclOperands.key(operand).toUpperCase(Locale.ROOT);
            if (key.equals("PGM")) {
                return null;
            }
            if (key.equals("PROC")) {
                name = JclOperands.value(operand).toUpperCase(Locale.ROOT);
            } else if (operand.trim().equals(JclOperands.key(operand)) && name == null) {
                // 鍵だけのオペランドは手続きの名前である
                name = key;
            }
        }
        return name;
    }

    private void expandProcedure(JclCard card, String procedure, List<JclCard> overrides,
                                 List<JclCard> out, JclSymbols scope, int depth) {
        List<JclCard> body = read(card, procedure, depth);
        if (body == null) {
            return;
        }
        JclSymbols bound = scope.copy();
        // 呼び出しで書いた値が強い。手続きの既定値はあとから、あるものを上書きせずに置く
        for (String operand : JclOperands.split(card.operands())) {
            String key = JclOperands.key(operand).toUpperCase(Locale.ROOT);
            if (key.equals("PGM") || key.equals("PROC") || key.equals("COND")
                    || key.equals("PARM") || operand.trim().equals(JclOperands.key(operand))) {
                continue;
            }
            bound.put(key, JclOperands.unquote(JclOperands.value(operand)));
        }
        List<JclCard> cards = new ArrayList<>(body);
        if (!cards.isEmpty() && cards.get(0).operation().equals("PROC")) {
            for (String operand : JclOperands.split(cards.remove(0).operands())) {
                String key = JclOperands.key(operand);
                if (!key.isEmpty() && !operand.trim().equals(key)) {
                    bound.putIfAbsent(key, JclOperands.unquote(JclOperands.value(operand)));
                }
            }
        }

        List<List<JclCard>> steps = stepsOf(cards);
        if (steps.isEmpty()) {
            report(card, "procedure " + procedure + " has no EXEC statement");
            return;
        }
        String outer = card.name() == null ? procedure : card.name().toUpperCase(Locale.ROOT);
        // 手続きの中でステップを名指す COND は、付け替えたあとの名前を指さなければならない
        Map<String, String> renamed = new LinkedHashMap<>();
        for (List<JclCard> step : steps) {
            String inner = innerName(step.get(0), outer);
            renamed.put(inner, steps.size() == 1 ? outer : outer + "." + inner);
        }
        String outerCond = operandOf(card, "COND");
        for (List<JclCard> step : steps) {
            emitStep(step, outer, bound, overrides, renamed, outerCond, out, depth);
        }
    }

    /** 手続きの中身を、{@code EXEC} を先頭とするステップへ切る。 */
    private static List<List<JclCard>> stepsOf(List<JclCard> cards) {
        List<List<JclCard>> steps = new ArrayList<>();
        for (JclCard card : cards) {
            if (card.operation().equals("EXEC")) {
                steps.add(new ArrayList<>(List.of(card)));
            } else if (!steps.isEmpty()) {
                steps.get(steps.size() - 1).add(card);
            }
        }
        return steps;
    }

    private void emitStep(List<JclCard> step, String outer, JclSymbols bound,
                          List<JclCard> overrides, Map<String, String> renamed, String outerCond,
                          List<JclCard> out, int depth) {
        JclCard exec = step.get(0);
        String inner = innerName(exec, outer);

        // 手続きの中身は、束ねたシンボリックパラメタで展開する
        List<JclCard> emitted = expand(step.subList(1, step.size()), bound, depth + 1);
        applyOverrides(inner, overrides, emitted);

        JclCard header = substitute(exec, bound).withName(renamed.get(inner));
        out.add(withCondition(header, renamed, outerCond));
        out.addAll(emitted);
    }

    private static String innerName(JclCard exec, String outer) {
        return exec.name() == null ? outer : exec.name().toUpperCase(Locale.ROOT);
    }

    /**
     * {@code COND} を整える。
     *
     * <p>2 つのことをする。手続きの中でステップを名指しているところを<b>付け替えたあとの
     * 名前</b>へ直すこと。そして、手続きの中に {@code COND} がなく呼び出し側にあれば、
     * <b>呼び出し側のものを引き継ぐ</b>ことである。呼び出しに付けた条件は、手続きの
     * すべてのステップに効く。
     */
    private JclCard withCondition(JclCard card, Map<String, String> renamed, String outerCond) {
        List<String> operands = new ArrayList<>(JclOperands.split(card.operands()));
        int at = -1;
        for (int i = 0; i < operands.size(); i++) {
            if (JclOperands.key(operands.get(i)).equalsIgnoreCase("COND")) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            if (outerCond == null) {
                return card;
            }
            operands.add("COND=" + outerCond);
            return card.withOperands(String.join(",", operands));
        }
        operands.set(at, "COND=" + rename(JclOperands.value(operands.get(at)), renamed));
        return card.withOperands(String.join(",", operands));
    }

    /** {@code COND} の中のステップ名を付け替える。 */
    private static String rename(String cond, Map<String, String> renamed) {
        String inner = JclOperands.unwrap(cond);
        List<String> items = inner.startsWith("(")
                ? JclOperands.split(inner)
                : List.of(inner);
        List<String> out = new ArrayList<>();
        boolean list = inner.startsWith("(");
        for (String item : items) {
            out.add(renameTest(item, renamed, list));
        }
        return "(" + String.join(",", out) + ")";
    }

    private static String renameTest(String item, Map<String, String> renamed, boolean list) {
        String text = item.trim();
        if (text.equalsIgnoreCase("EVEN") || text.equalsIgnoreCase("ONLY")) {
            return text;
        }
        List<String> parts = JclOperands.split(JclOperands.unwrap(text));
        if (parts.size() != 3) {
            return text;
        }
        String step = parts.get(2).trim().toUpperCase(Locale.ROOT);
        if (renamed.containsKey(step)) {
            parts.set(2, renamed.get(step));
        }
        String joined = String.join(",", parts);
        return list ? "(" + joined + ")" : joined;
    }

    /** カードのオペランドから、指定の鍵の値を取り出す。なければ {@code null}。 */
    private static String operandOf(JclCard card, String key) {
        for (String operand : JclOperands.split(card.operands())) {
            if (JclOperands.key(operand).equalsIgnoreCase(key)) {
                return JclOperands.value(operand);
            }
        }
        return null;
    }

    /**
     * {@code //手続きのステップ名.DD 名 DD ...} を当てる。
     *
     * <p>同じ名前があれば<b>差し替え</b>、なければ<b>足す</b>。
     */
    private void applyOverrides(String step, List<JclCard> overrides, List<JclCard> body) {
        for (JclCard override : overrides) {
            String name = override.name();
            if (name == null || !name.contains(".")) {
                report(override, "a DD after a procedure needs procstep.ddname: " + name);
                continue;
            }
            String qualifier = name.substring(0, name.indexOf('.')).toUpperCase(Locale.ROOT);
            String dd = name.substring(name.indexOf('.') + 1).toUpperCase(Locale.ROOT);
            if (!qualifier.equals(step)) {
                continue;
            }
            JclCard replacement = override.withName(dd);
            for (int i = 0; i < body.size(); i++) {
                if (dd.equalsIgnoreCase(body.get(i).name())) {
                    body.set(i, replacement);
                    replacement = null;
                    break;
                }
            }
            if (replacement != null) {
                body.add(replacement);
            }
        }
    }

    /** 手続きか {@code INCLUDE} のメンバを読む。 */
    private List<JclCard> read(JclCard card, String member, int depth) {
        if (depth >= MAX_DEPTH) {
            report(card, "procedures are nested too deeply: " + member);
            return null;
        }
        List<JclCard> body = inStream.get(member.toUpperCase(Locale.ROOT));
        if (body != null) {
            return body;
        }
        String text = library.member(member);
        if (text == null) {
            report(card, "no such procedure or member: " + member);
            return null;
        }
        return JclReader.read(text, diagnostics);
    }

    private String memberOf(JclCard card, String operation, JclSymbols scope) {
        for (String operand : JclOperands.split(substitute(card, scope).operands())) {
            if (JclOperands.key(operand).equalsIgnoreCase("MEMBER")) {
                return JclOperands.value(operand);
            }
        }
        report(card, operation + " needs MEMBER=");
        return null;
    }

    /** カードのオペランドのシンボリックパラメタを置き換える。 */
    private JclCard substitute(JclCard card, JclSymbols bound) {
        if (card.operands().indexOf('&') < 0) {
            return card;
        }
        List<String> unresolved = new ArrayList<>();
        String operands = bound.substitute(card.operands(), unresolved);
        for (String name : unresolved) {
            report(card, "no value for symbolic parameter: &" + name);
        }
        return card.withOperands(operands);
    }

    private void report(JclCard card, String message) {
        diagnostics.add(new JobDiagnostic(card.line(), message));
    }
}
