package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.parser.CobolParser;
import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.Origin;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
        report(ReferenceResolver.originOf(context), "statement is not supported yet");
        return null;
    }

    private Statement moveOf(CobolParser.MoveStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        Operand source = operandOf(context.moveSource(), origin);
        List<DataReference> targets = new ArrayList<>();
        for (CobolParser.IdentifierContext target : context.identifier()) {
            DataReference reference = resolver.resolve(target);
            if (reference != null) {
                targets.add(reference);
            }
        }
        if (source == null || targets.size() != context.identifier().size()) {
            // 解決できなかった参照は誤りとして報告済みである。文は組み立てない
            return null;
        }
        boolean corresponding = context.CORRESPONDING() != null || context.CORR() != null;
        return new Statement.Move(source, targets, corresponding, origin);
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
