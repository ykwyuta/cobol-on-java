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
    private final List<Diagnostic> diagnostics;

    private ProcedureBuilder(DataLayout layout, List<Diagnostic> diagnostics) {
        this.resolver = new ReferenceResolver(layout, diagnostics);
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
        return new Result(List.copyOf(paragraphs), List.copyOf(diagnostics));
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
                    origin);
        }
        operands.addAll(operandsOf(context.roundedOperand(), origin));
        return arithmetic(Statement.Arithmetic.Operator.ADD, operands, null,
                targetsOf(context.roundedTarget()), origin);
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
                    targetsOf(context.roundedOperand(), origin), origin);
        }
        List<Operand> operands = operandsOf(context.roundedOperand(), origin);
        operands.addAll(subtrahends);
        return arithmetic(Statement.Arithmetic.Operator.SUBTRACT, operands, null,
                targetsOf(context.roundedTarget()), origin);
    }

    private Statement multiplyOf(CobolParser.MultiplyStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        List<Operand> multiplier = operandsOf(List.of(context.arithmeticOperand()), origin);
        if (context.GIVING() == null) {
            return arithmetic(Statement.Arithmetic.Operator.MULTIPLY, multiplier,
                    Statement.Arithmetic.Operator.MULTIPLY,
                    targetsOf(context.roundedOperand(), origin), origin);
        }
        List<Operand> operands = new ArrayList<>(multiplier);
        operands.addAll(operandsOf(context.roundedOperand(), origin));
        return arithmetic(Statement.Arithmetic.Operator.MULTIPLY, operands, null,
                targetsOf(context.roundedTarget()), origin);
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
                    targetsOf(context.roundedOperand(), origin), origin);
        }
        List<Operand> second = operandsOf(context.roundedOperand(), origin);
        List<Operand> operands = new ArrayList<>();
        // INTO は「割られる側があとに書かれる」ので、畳む順に入れ替える
        operands.addAll(into ? second : first);
        operands.addAll(into ? first : second);
        return arithmetic(Statement.Arithmetic.Operator.DIVIDE, operands, null,
                targetsOf(context.roundedTarget()), origin);
    }

    private Statement arithmetic(Statement.Arithmetic.Operator fold, List<Operand> operands,
                                 Statement.Arithmetic.Operator accumulate,
                                 List<Statement.Arithmetic.Target> targets, Origin origin) {
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
        return new Statement.Arithmetic(fold, operands, accumulate, targets, origin);
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
