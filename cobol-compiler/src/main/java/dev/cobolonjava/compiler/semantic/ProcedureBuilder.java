package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.parser.CobolParser;
import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.Origin;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.antlr.v4.runtime.ParserRuleContext;

/**
 * 手続き部の構文木から文の並びを作る (要件 FR-060)。
 *
 * <p>一意名はここで記憶域の割り付けへ結び付ける ({@link ReferenceResolver})。
 * 意味論そのものはランタイムが持つため、ここが作るのは<b>何をどれに対して行うか</b>だけである。
 *
 * <h2>段落の前に文を置ける</h2>
 * <p>手続き部は段落を持たずに文だけを書ける。その場合の文は名前のない段落に入れる。
 */
public final class ProcedureBuilder {

    private final ReferenceResolver resolver;
    private final DataLayout layout;
    private final List<Diagnostic> diagnostics;

    private ProcedureBuilder(DataLayout layout, List<Diagnostic> diagnostics) {
        this.resolver = new ReferenceResolver(layout, diagnostics);
        this.layout = layout;
        this.diagnostics = diagnostics;
    }

    /**
     * 段落 1 個。
     *
     * @param name       段落名。名前のない先頭の並びは {@code null}
     * @param statements 文の並び
     */
    public record Paragraph(String name, List<Statement> statements, Origin origin) {

        public Paragraph {
            statements = List.copyOf(statements);
        }
    }

    /** 組み立ての結果。 */
    public record Result(List<Paragraph> paragraphs, List<Diagnostic> diagnostics) {

        public boolean succeeded() {
            return diagnostics.isEmpty();
        }

        /** すべての段落の文を、書かれた順に並べたもの。 */
        public List<Statement> statements() {
            List<Statement> all = new ArrayList<>();
            for (Paragraph paragraph : paragraphs) {
                all.addAll(paragraph.statements());
            }
            return List.copyOf(all);
        }
    }

    /** 構文木の手続き部から文の並びを作る。 */
    public static Result build(CobolParser.CompilationUnitContext tree, DataLayout layout) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        ProcedureBuilder builder = new ProcedureBuilder(layout, diagnostics);
        List<Paragraph> paragraphs = new ArrayList<>();
        for (CobolParser.ProgramUnitContext unit : tree.programUnit()) {
            if (unit.procedureDivision() != null) {
                builder.addBody(unit.procedureDivision().procedureBody(), paragraphs);
            }
        }
        builder.checkPerformTargets(paragraphs);
        return new Result(List.copyOf(paragraphs), List.copyOf(diagnostics));
    }

    /**
     * {@code PERFORM} が呼ぶ段落が実在するか確かめる。
     *
     * <p>段落はあとから書かれることもあるため、すべての段落を組み立てたあとに見る。
     */
    private void checkPerformTargets(List<Paragraph> paragraphs) {
        List<String> names = new ArrayList<>();
        for (Paragraph paragraph : paragraphs) {
            names.add(paragraph.name());
        }
        for (Paragraph paragraph : paragraphs) {
            for (Statement statement : paragraph.statements()) {
                checkPerformTargets(statement, names);
            }
        }
    }

    private void checkPerformTargets(Statement statement, List<String> names) {
        if (statement instanceof Statement.If branch) {
            branch.onTrue().forEach(s -> checkPerformTargets(s, names));
            branch.onFalse().forEach(s -> checkPerformTargets(s, names));
            return;
        }
        if (!(statement instanceof Statement.Perform perform)) {
            return;
        }
        perform.body().forEach(s -> checkPerformTargets(s, names));
        if (!perform.callsParagraph()) {
            return;
        }
        int from = names.indexOf(perform.target());
        if (from < 0) {
            report(perform.origin(), "undefined paragraph: " + perform.target());
            return;
        }
        if (perform.through() == null) {
            return;
        }
        int to = names.indexOf(perform.through());
        if (to < 0) {
            report(perform.origin(), "undefined paragraph: " + perform.through());
        } else if (to < from) {
            // 逆順に書かれた THRU は、書いた人の意図と実行される範囲が食い違う
            report(perform.origin(), "PERFORM THRU names paragraphs in reverse order: "
                    + perform.target() + " comes after " + perform.through());
        }
    }

    private void addBody(CobolParser.ProcedureBodyContext body, List<Paragraph> paragraphs) {
        List<Statement> leading = statementsOf(body.sentence());
        if (!leading.isEmpty()) {
            paragraphs.add(new Paragraph(null, leading, leading.get(0).origin()));
        }
        for (CobolParser.ParagraphContext paragraph : body.paragraph()) {
            paragraphs.add(new Paragraph(
                    paragraph.paragraphName().getText().toUpperCase(Locale.ROOT),
                    statementsOf(paragraph.sentence()),
                    ReferenceResolver.originOf(paragraph)));
        }
    }

    private List<Statement> statementsOf(List<CobolParser.SentenceContext> sentences) {
        List<Statement> statements = new ArrayList<>();
        for (CobolParser.SentenceContext sentence : sentences) {
            for (CobolParser.StatementContext statement : sentence.statement()) {
                Statement built = statementOf(statement);
                if (built != null) {
                    statements.add(built);
                }
            }
        }
        return statements;
    }

    private Statement statementOf(CobolParser.StatementContext context) {
        if (context.moveStatement() != null) {
            return moveOf(context.moveStatement());
        }
        if (context.ifStatement() != null) {
            return ifOf(context.ifStatement());
        }
        if (context.performStatement() != null) {
            return performOf(context.performStatement());
        }
        if (context.displayStatement() != null) {
            return displayOf(context.displayStatement());
        }
        if (context.continueStatement() != null) {
            return new Statement.Continue(ReferenceResolver.originOf(context));
        }
        if (context.addStatement() != null) {
            return addOf(context.addStatement());
        }
        if (context.subtractStatement() != null) {
            return subtractOf(context.subtractStatement());
        }
        if (context.multiplyStatement() != null) {
            return multiplyOf(context.multiplyStatement());
        }
        if (context.divideStatement() != null) {
            return divideOf(context.divideStatement());
        }
        report(ReferenceResolver.originOf(context), "statement is not supported yet");
        return null;
    }

    private Statement displayOf(CobolParser.DisplayStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        List<Operand> operands = operandsOf(context.arithmeticOperand(), origin);
        if (operands.contains(null)) {
            return null;
        }
        if (context.UPON() != null) {
            // 出力先の指定は環境部の SPECIAL-NAMES と結び付く。まだ扱えない
            report(origin, "DISPLAY ... UPON is not supported yet");
            return null;
        }
        return new Statement.Display(operands, context.ADVANCING() == null, origin);
    }

    // ---- 制御構造 ----

    private Statement ifOf(CobolParser.IfStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        Condition condition = conditionOf(context.condition());
        if (condition == null) {
            return null;
        }
        List<Statement> onTrue = branchOf(context.ifBranch(0));
        List<Statement> onFalse = context.ifBranch().size() > 1
                ? branchOf(context.ifBranch(1))
                : List.of();
        return new Statement.If(condition, onTrue, onFalse, origin);
    }

    /** {@code NEXT SENTENCE} は「この文の残りを飛ばす」ことであり、いまは空の並びとする。 */
    private List<Statement> branchOf(CobolParser.IfBranchContext context) {
        return listOf(context.statement());
    }

    private Statement performOf(CobolParser.PerformStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        String target = null;
        String through = null;
        if (context.procedureReference() != null) {
            List<CobolParser.ParagraphNameContext> names =
                    context.procedureReference().paragraphName();
            target = names.get(0).getText().toUpperCase(Locale.ROOT);
            through = names.size() > 1 ? names.get(1).getText().toUpperCase(Locale.ROOT) : null;
        }

        Operand times = null;
        Condition until = null;
        boolean testAfter = false;
        CobolParser.PerformPhraseContext phrase = context.performPhrase();
        if (phrase != null) {
            if (phrase.TIMES() != null) {
                times = operandOf(phrase.arithmeticOperand(), origin);
                if (times == null) {
                    return null;
                }
            } else {
                until = conditionOf(phrase.condition());
                if (until == null) {
                    return null;
                }
                testAfter = phrase.AFTER() != null;
            }
        }

        List<Statement> body = new ArrayList<>();
        for (CobolParser.StatementContext statement : context.statement()) {
            Statement built = statementOf(statement);
            if (built != null) {
                body.add(built);
            }
        }
        return new Statement.Perform(target, through, times, until, testAfter, body, origin);
    }

    // ---- 条件 ----

    private Condition conditionOf(CobolParser.ConditionContext context) {
        return orOf(context.orCondition());
    }

    private Condition orOf(CobolParser.OrConditionContext context) {
        Condition result = null;
        for (CobolParser.AndConditionContext operand : context.andCondition()) {
            Condition next = andOf(operand);
            if (next == null) {
                return null;
            }
            result = result == null ? next : new Condition.Or(result, next);
        }
        return result;
    }

    private Condition andOf(CobolParser.AndConditionContext context) {
        Condition result = null;
        for (CobolParser.NotConditionContext operand : context.notCondition()) {
            Condition next = notOf(operand);
            if (next == null) {
                return null;
            }
            result = result == null ? next : new Condition.And(result, next);
        }
        return result;
    }

    private Condition notOf(CobolParser.NotConditionContext context) {
        Condition inner = simpleOf(context.simpleCondition());
        if (inner == null) {
            return null;
        }
        return context.NOT() == null ? inner : new Condition.Not(inner);
    }

    private Condition simpleOf(CobolParser.SimpleConditionContext context) {
        if (context.condition() != null) {
            return conditionOf(context.condition());
        }
        if (context.relationCondition() != null) {
            return relationOf(context.relationCondition());
        }
        if (context.signCondition() != null) {
            return signOf(context.signCondition());
        }
        return conditionNameOf(context.conditionNameCondition());
    }

    private Condition relationOf(CobolParser.RelationConditionContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        Operand left = operandOf(context.arithmeticOperand(0), origin);
        Operand right = operandOf(context.arithmeticOperand(1), origin);
        if (left == null || right == null) {
            return null;
        }
        Condition.Comparison comparison = comparisonOf(context.relationalOperator());
        if (comparison == null) {
            report(origin, "unknown relational operator: "
                    + context.relationalOperator().getText());
            return null;
        }
        return relation(left, comparison, right, origin);
    }

    /**
     * 関係条件を組み立てる。<b>両辺が数値なら代数的な比較</b>、そうでなければ
     * コードページの照合順序による比較になる。
     */
    private Condition relation(Operand left, Condition.Comparison comparison, Operand right,
                               Origin origin) {
        boolean numeric = isNumeric(left, true) && isNumeric(right, true);
        return new Condition.Relation(left, comparison, right, numeric, origin);
    }

    /** 被演算子が数値として扱われるか。定数は受取側に合わせるので、既定の見方を渡す。 */
    private static boolean isNumeric(Operand operand, boolean literalDefault) {
        if (operand instanceof Operand.Reference reference) {
            return DataCategory.of(reference.reference()).isNumeric();
        }
        LiteralValue value = ((Operand.Literal) operand).value();
        return DataCategory.of(value, literalDefault).isNumeric();
    }

    private static Condition.Comparison comparisonOf(
            CobolParser.RelationalOperatorContext context) {
        Condition.Comparison comparison = bodyOf(context.relationalOperatorBody());
        if (comparison == null) {
            return null;
        }
        return context.NOT() == null ? comparison : comparison.negate();
    }

    private static Condition.Comparison bodyOf(
            CobolParser.RelationalOperatorBodyContext context) {
        boolean orEqual = context.OR() != null;
        if (context.GREATER() != null || context.GREATER_SIGN() != null) {
            return orEqual ? Condition.Comparison.GREATER_OR_EQUAL : Condition.Comparison.GREATER;
        }
        if (context.LESS() != null || context.LESS_SIGN() != null) {
            return orEqual ? Condition.Comparison.LESS_OR_EQUAL : Condition.Comparison.LESS;
        }
        if (context.GREATER_EQUAL_SIGN() != null) {
            return Condition.Comparison.GREATER_OR_EQUAL;
        }
        if (context.LESS_EQUAL_SIGN() != null) {
            return Condition.Comparison.LESS_OR_EQUAL;
        }
        if (context.NOT_EQUAL_SIGN() != null) {
            return Condition.Comparison.NOT_EQUAL;
        }
        if (context.EQUAL() != null || context.EQUAL_SIGN() != null) {
            return Condition.Comparison.EQUAL;
        }
        return null;
    }

    /** 符号条件はゼロとの比較へ展開する。 */
    private Condition signOf(CobolParser.SignConditionContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        Operand operand = operandOf(context.arithmeticOperand(), origin);
        if (operand == null) {
            return null;
        }
        if (!isNumeric(operand, true)) {
            report(origin, "a sign condition requires a numeric operand");
            return null;
        }
        Condition.Comparison comparison;
        if (context.POSITIVE() != null) {
            comparison = Condition.Comparison.GREATER;
        } else if (context.NEGATIVE() != null) {
            comparison = Condition.Comparison.LESS;
        } else {
            comparison = Condition.Comparison.EQUAL;
        }
        if (context.NOT() != null) {
            comparison = comparison.negate();
        }
        Operand zero = new Operand.Literal(
                new LiteralValue.Figure(LiteralValue.FigurativeConstant.ZERO));
        return new Condition.Relation(operand, comparison, zero, true, origin);
    }

    /**
     * 条件名 (88 レベル) は、親の項目と値を比べる関係条件へ展開する。
     * 値が複数あれば選言、{@code THRU} の範囲なら 2 つの比較の連言になる。
     */
    private Condition conditionNameOf(CobolParser.ConditionNameConditionContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        String name = context.identifier().qualifiedDataName().dataName(0).getText()
                .toUpperCase(Locale.ROOT);
        for (DataItem item : layout.all()) {
            for (DataItem.ConditionName conditionName : item.conditionNames()) {
                if (name.equals(conditionName.name())) {
                    return conditionNameCondition(item, conditionName, origin);
                }
            }
        }
        report(origin, "undefined condition-name: " + name);
        return null;
    }

    private Condition conditionNameCondition(DataItem item, DataItem.ConditionName conditionName,
                                             Origin origin) {
        Operand subject = new Operand.Reference(
                new DataReference(item, List.of(), null, origin));
        Condition result = null;
        for (DataItem.ValueRange range : conditionName.values()) {
            Condition test;
            if (range.to() == null) {
                test = relation(subject, Condition.Comparison.EQUAL,
                        new Operand.Literal(range.from()), origin);
            } else {
                test = new Condition.And(
                        relation(subject, Condition.Comparison.GREATER_OR_EQUAL,
                                new Operand.Literal(range.from()), origin),
                        relation(subject, Condition.Comparison.LESS_OR_EQUAL,
                                new Operand.Literal(range.to()), origin));
            }
            result = result == null ? test : new Condition.Or(result, test);
        }
        return result;
    }

    // ---- 算術文 ----

    /**
     * {@code ADD}。{@code GIVING} があれば {@code TO} のあとも被演算子になり、
     * なければ受取項目になる。
     */
    private Statement addOf(CobolParser.AddStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        List<Operand> operands = operandsOf(context.arithmeticOperand(), origin);
        if (context.GIVING() == null) {
            if (context.roundedOperand().isEmpty()) {
                report(origin, "ADD without GIVING requires TO");
                return null;
            }
            return arithmetic(Statement.Arithmetic.Operator.ADD, operands,
                    Statement.Arithmetic.Operator.ADD, targetsOf(context.roundedOperand(), origin),
                    context.sizeErrorPhrases(), origin);
        }
        operands.addAll(operandsOf(context.roundedOperand(), origin));
        return arithmetic(Statement.Arithmetic.Operator.ADD, operands, null,
                targetsOf(context.roundedTarget()), context.sizeErrorPhrases(), origin);
    }

    /**
     * {@code SUBTRACT}。{@code GIVING} がなければ引かれる側が受取項目になり、
     * あれば <b>引かれる側を先頭に置いて左から引く</b>。
     */
    private Statement subtractOf(CobolParser.SubtractStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        List<Operand> subtrahends = operandsOf(context.arithmeticOperand(), origin);
        if (context.GIVING() == null) {
            // 引く側をまず足し合わせ、その和を受取項目から引く
            return arithmetic(Statement.Arithmetic.Operator.ADD, subtrahends,
                    Statement.Arithmetic.Operator.SUBTRACT,
                    targetsOf(context.roundedOperand(), origin), context.sizeErrorPhrases(), origin);
        }
        List<Operand> operands = operandsOf(context.roundedOperand(), origin);
        operands.addAll(subtrahends);
        return arithmetic(Statement.Arithmetic.Operator.SUBTRACT, operands, null,
                targetsOf(context.roundedTarget()), context.sizeErrorPhrases(), origin);
    }

    private Statement multiplyOf(CobolParser.MultiplyStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        List<Operand> multiplier = operandsOf(List.of(context.arithmeticOperand()), origin);
        if (context.GIVING() == null) {
            return arithmetic(Statement.Arithmetic.Operator.MULTIPLY, multiplier,
                    Statement.Arithmetic.Operator.MULTIPLY,
                    targetsOf(context.roundedOperand(), origin), context.sizeErrorPhrases(), origin);
        }
        List<Operand> operands = new ArrayList<>(multiplier);
        operands.addAll(operandsOf(context.roundedOperand(), origin));
        return arithmetic(Statement.Arithmetic.Operator.MULTIPLY, operands, null,
                targetsOf(context.roundedTarget()), context.sizeErrorPhrases(), origin);
    }

    /**
     * {@code DIVIDE}。{@code INTO} と {@code BY} で割る側と割られる側が入れ替わる。
     */
    private Statement divideOf(CobolParser.DivideStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        List<Operand> first = operandsOf(List.of(context.arithmeticOperand()), origin);
        boolean into = context.INTO() != null;
        if (context.GIVING() == null) {
            if (!into) {
                report(origin, "DIVIDE ... BY requires GIVING");
                return null;
            }
            return arithmetic(Statement.Arithmetic.Operator.DIVIDE, first,
                    Statement.Arithmetic.Operator.DIVIDE,
                    targetsOf(context.roundedOperand(), origin), context.sizeErrorPhrases(), origin);
        }
        List<Operand> second = operandsOf(context.roundedOperand(), origin);
        List<Operand> operands = new ArrayList<>();
        // INTO は「割られる側があとに書かれる」ので、畳む順に入れ替える
        operands.addAll(into ? second : first);
        operands.addAll(into ? first : second);
        return arithmetic(Statement.Arithmetic.Operator.DIVIDE, operands, null,
                targetsOf(context.roundedTarget()), context.sizeErrorPhrases(), origin);
    }

    private Statement arithmetic(Statement.Arithmetic.Operator fold, List<Operand> operands,
                                 Statement.Arithmetic.Operator accumulate,
                                 List<Statement.Arithmetic.Target> targets,
                                 CobolParser.SizeErrorPhrasesContext phrases, Origin origin) {
        if (operands.contains(null) || targets.contains(null) || targets.isEmpty()) {
            // 解決できなかった参照は報告済みである
            return null;
        }
        for (Statement.Arithmetic.Target target : targets) {
            if (!DataCategory.of(target.reference()).isNumeric()) {
                report(origin, "an arithmetic statement requires a numeric receiver: "
                        + describe(target.reference()));
                return null;
            }
        }
        return new Statement.Arithmetic(fold, operands, accumulate, targets,
                sizeErrorOf(phrases), origin);
    }

    /**
     * {@code ON SIZE ERROR} と {@code NOT ON SIZE ERROR} の文。
     * どちらも書かれていなければ {@code null} を返し、検査そのものを行わない。
     */
    private Statement.Arithmetic.SizeError sizeErrorOf(
            CobolParser.SizeErrorPhrasesContext phrases) {
        if (phrases == null
                || (phrases.onSizeErrorPhrase() == null && phrases.notOnSizeErrorPhrase() == null)) {
            return null;
        }
        List<Statement> onError = phrases.onSizeErrorPhrase() == null
                ? List.of()
                : listOf(phrases.onSizeErrorPhrase().statement());
        List<Statement> otherwise = phrases.notOnSizeErrorPhrase() == null
                ? List.of()
                : listOf(phrases.notOnSizeErrorPhrase().statement());
        return new Statement.Arithmetic.SizeError(onError, otherwise);
    }

    private List<Statement> listOf(List<CobolParser.StatementContext> contexts) {
        List<Statement> statements = new ArrayList<>();
        for (CobolParser.StatementContext context : contexts) {
            Statement built = statementOf(context);
            if (built != null) {
                statements.add(built);
            }
        }
        return statements;
    }

    private List<Operand> operandsOf(List<? extends ParserRuleContext> contexts, Origin origin) {
        List<Operand> operands = new ArrayList<>();
        for (ParserRuleContext context : contexts) {
            CobolParser.ArithmeticOperandContext operand =
                    context instanceof CobolParser.RoundedOperandContext rounded
                            ? rounded.arithmeticOperand()
                            : (CobolParser.ArithmeticOperandContext) context;
            operands.add(operandOf(operand, origin));
        }
        return operands;
    }

    private Operand operandOf(CobolParser.ArithmeticOperandContext context, Origin origin) {
        if (context.literal() != null) {
            try {
                return new Operand.Literal(LiteralValue.of(context.literal()));
            } catch (RuntimeException e) {
                report(origin, "invalid literal: " + context.literal().getText());
                return null;
            }
        }
        DataReference reference = resolver.resolve(context.identifier());
        return reference == null ? null : new Operand.Reference(reference);
    }

    /** {@code GIVING} がない形で受取項目になる被演算子。 */
    private List<Statement.Arithmetic.Target> targetsOf(
            List<CobolParser.RoundedOperandContext> contexts, Origin origin) {
        List<Statement.Arithmetic.Target> targets = new ArrayList<>();
        for (CobolParser.RoundedOperandContext context : contexts) {
            if (context.arithmeticOperand().identifier() == null) {
                report(origin, "a literal cannot receive the result of an arithmetic statement");
                targets.add(null);
                continue;
            }
            DataReference reference = resolver.resolve(context.arithmeticOperand().identifier());
            targets.add(reference == null ? null
                    : new Statement.Arithmetic.Target(reference, context.ROUNDED() != null));
        }
        return targets;
    }

    private List<Statement.Arithmetic.Target> targetsOf(
            List<CobolParser.RoundedTargetContext> contexts) {
        List<Statement.Arithmetic.Target> targets = new ArrayList<>();
        for (CobolParser.RoundedTargetContext context : contexts) {
            DataReference reference = resolver.resolve(context.identifier());
            targets.add(reference == null ? null
                    : new Statement.Arithmetic.Target(reference, context.ROUNDED() != null));
        }
        return targets;
    }

    private Statement moveOf(CobolParser.MoveStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        Operand source = operandOf(context.moveSource(), origin);
        List<Statement.Move.Target> targets = new ArrayList<>();
        for (CobolParser.IdentifierContext target : context.identifier()) {
            DataReference reference = resolver.resolve(target);
            if (reference == null || source == null) {
                continue;
            }
            Statement.Move.Target checked = checkMove(source, reference, origin);
            if (checked != null) {
                targets.add(checked);
            }
        }
        if (source == null || targets.size() != context.identifier().size()) {
            // 解決できなかった参照と書けない組み合わせは報告済みである。文は組み立てない
            return null;
        }
        boolean corresponding = context.CORRESPONDING() != null || context.CORR() != null;
        return new Statement.Move(source, targets, corresponding, origin);
    }

    /** 分類の組み合わせを検査し、転記の種類を決める。 */
    private Statement.Move.Target checkMove(Operand source, DataReference target, Origin origin) {
        DataCategory receiver = DataCategory.of(target);
        DataCategory sender = categoryOf(source, receiver);
        if (!MoveRules.isAllowed(sender, receiver)) {
            report(origin, "MOVE to " + describe(target) + " is not allowed: "
                    + MoveRules.reason(sender, receiver));
            return null;
        }
        return new Statement.Move.Target(target, MoveRules.kindOf(sender, receiver));
    }

    /** 送出側の分類。図形定数 {@code ZERO} は受取側に合わせて数値にも文字にもなる。 */
    private static DataCategory categoryOf(Operand source, DataCategory receiver) {
        if (source instanceof Operand.Reference reference) {
            return DataCategory.of(reference.reference());
        }
        boolean numericReceiver = receiver.isNumeric() || receiver == DataCategory.NUMERIC_EDITED;
        return DataCategory.of(((Operand.Literal) source).value(), numericReceiver);
    }

    private static String describe(DataReference reference) {
        return reference.item().name() == null ? "FILLER" : reference.item().name();
    }

    private Operand operandOf(CobolParser.MoveSourceContext context, Origin origin) {
        if (context.literal() != null) {
            try {
                return new Operand.Literal(LiteralValue.of(context.literal()));
            } catch (RuntimeException e) {
                report(origin, "invalid literal: " + context.literal().getText());
                return null;
            }
        }
        DataReference reference = resolver.resolve(context.identifier());
        return reference == null ? null : new Operand.Reference(reference);
    }

    private void report(Origin origin, String message) {
        diagnostics.add(new Diagnostic(origin, message));
    }
}
