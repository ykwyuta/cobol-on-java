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
        builder.checkProcedureTargets(paragraphs);
        return new Result(List.copyOf(paragraphs), List.copyOf(diagnostics));
    }

    /**
     * {@code PERFORM} と {@code GO TO} が名指す段落が実在するか確かめる。
     *
     * <p>段落はあとから書かれることもあるため、すべての段落を組み立てたあとに見る。
     */
    private void checkProcedureTargets(List<Paragraph> paragraphs) {
        List<String> names = new ArrayList<>();
        for (Paragraph paragraph : paragraphs) {
            names.add(paragraph.name());
        }
        for (Paragraph paragraph : paragraphs) {
            for (Statement statement : paragraph.statements()) {
                checkProcedureTargets(statement, names);
            }
        }
    }

    private void checkProcedureTargets(Statement statement, List<String> names) {
        for (List<Statement> nested : nestedStatements(statement)) {
            nested.forEach(s -> checkProcedureTargets(s, names));
        }
        if (statement instanceof Statement.GoTo goTo) {
            if (!names.contains(goTo.target())) {
                report(goTo.origin(), "undefined paragraph: " + goTo.target());
            }
            return;
        }
        if (!(statement instanceof Statement.Perform perform) || !perform.callsParagraph()) {
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

    /**
     * 文の中に入れ子になっている文の並び。
     *
     * <p>条件分岐と繰り返しだけでなく、{@code ON SIZE ERROR} や {@code ON OVERFLOW} の
     * 中にも文が書ける。1 か所で数え上げておかないと、新しい文を足すたびに
     * 走査の抜けができる。
     */
    private static List<List<Statement>> nestedStatements(Statement statement) {
        if (statement instanceof Statement.If branch) {
            return List.of(branch.onTrue(), branch.onFalse());
        }
        if (statement instanceof Statement.Perform perform) {
            return List.of(perform.body());
        }
        if (statement instanceof Statement.Arithmetic arithmetic) {
            return sizeErrorStatements(arithmetic.sizeError());
        }
        if (statement instanceof Statement.Compute compute) {
            return sizeErrorStatements(compute.sizeError());
        }
        if (statement instanceof Statement.StringStatement text) {
            return overflowStatements(text.overflow());
        }
        if (statement instanceof Statement.Unstring unstring) {
            return overflowStatements(unstring.overflow());
        }
        return List.of();
    }

    private static List<List<Statement>> sizeErrorStatements(
            Statement.Arithmetic.SizeError sizeError) {
        return sizeError == null
                ? List.of()
                : List.of(sizeError.onError(), sizeError.otherwise());
    }

    private static List<List<Statement>> overflowStatements(Statement.Overflow overflow) {
        return overflow == null
                ? List.of()
                : List.of(overflow.onOverflow(), overflow.otherwise());
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
        if (context.evaluateStatement() != null) {
            return evaluateOf(context.evaluateStatement());
        }
        if (context.stopStatement() != null) {
            return new Statement.Stop(ReferenceResolver.originOf(context));
        }
        if (context.stringStatement() != null) {
            return stringOf(context.stringStatement());
        }
        if (context.unstringStatement() != null) {
            return unstringOf(context.unstringStatement());
        }
        if (context.inspectStatement() != null) {
            return inspectOf(context.inspectStatement());
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
        if (context.computeStatement() != null) {
            return computeOf(context.computeStatement());
        }
        if (context.goToStatement() != null) {
            CobolParser.GoToStatementContext goTo = context.goToStatement();
            return new Statement.GoTo(goTo.paragraphName().getText().toUpperCase(Locale.ROOT),
                    ReferenceResolver.originOf(goTo));
        }
        if (context.exitStatement() != null) {
            // EXIT は何もしない。CONTINUE と同じ扱いでよい
            return new Statement.Continue(ReferenceResolver.originOf(context.exitStatement()));
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

    // ---- STRING / UNSTRING ----

    private Statement stringOf(CobolParser.StringStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        List<CobolParser.IdentifierContext> identifiers = context.identifier();
        DataReference target = resolver.resolve(identifiers.get(0));
        DataReference pointer = context.POINTER() == null
                ? null
                : resolver.resolve(identifiers.get(1));
        if (target == null || (context.POINTER() != null && pointer == null)) {
            return null;
        }
        if (pointer != null && !DataCategory.of(pointer).isNumeric()) {
            report(origin, "WITH POINTER requires a numeric item: " + describe(pointer));
            return null;
        }

        List<Statement.StringStatement.StringSource> sources = new ArrayList<>();
        for (CobolParser.StringSourceContext source : context.stringSource()) {
            List<CobolParser.ArithmeticOperandContext> operands = source.arithmeticOperand();
            // DELIMITED BY SIZE でなければ、最後の被演算子が区切りである
            int valueCount = source.SIZE() != null ? operands.size() : operands.size() - 1;
            List<Operand> values = new ArrayList<>();
            for (int i = 0; i < valueCount; i++) {
                values.add(operandOf(operands.get(i), origin));
            }
            Operand delimiter = source.SIZE() != null
                    ? null
                    : operandOf(operands.get(operands.size() - 1), origin);
            if (values.contains(null) || (source.SIZE() == null && delimiter == null)) {
                return null;
            }
            sources.add(new Statement.StringStatement.StringSource(values, delimiter));
        }
        return new Statement.StringStatement(sources, target, pointer,
                overflowOf(context.overflowPhrases()), origin);
    }

    private Statement unstringOf(CobolParser.UnstringStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        DataReference source = resolver.resolve(context.identifier(0));
        if (source == null) {
            return null;
        }

        List<Statement.Unstring.UnstringDelimiter> delimiters = new ArrayList<>();
        for (CobolParser.UnstringDelimiterContext delimiter : context.unstringDelimiter()) {
            Operand value = operandOf(delimiter.arithmeticOperand(), origin);
            if (value == null) {
                return null;
            }
            delimiters.add(new Statement.Unstring.UnstringDelimiter(value,
                    delimiter.ALL() != null));
        }

        List<Statement.Unstring.UnstringTarget> targets = new ArrayList<>();
        for (CobolParser.UnstringTargetContext target : context.unstringTarget()) {
            List<CobolParser.IdentifierContext> parts = target.identifier();
            int next = 1;
            DataReference field = resolver.resolve(parts.get(0));
            DataReference delimiterInto = target.DELIMITER() == null
                    ? null
                    : resolver.resolve(parts.get(next++));
            DataReference countInto = target.COUNT() == null
                    ? null
                    : resolver.resolve(parts.get(next));
            if (field == null
                    || (target.DELIMITER() != null && delimiterInto == null)
                    || (target.COUNT() != null && countInto == null)) {
                return null;
            }
            if (countInto != null && !DataCategory.of(countInto).isNumeric()) {
                report(origin, "COUNT IN requires a numeric item: " + describe(countInto));
                return null;
            }
            targets.add(new Statement.Unstring.UnstringTarget(field, delimiterInto, countInto));
        }

        // 送出項目のあとに並ぶ識別子のうち、POINTER と TALLYING は末尾に来る
        int extra = context.identifier().size() - 1;
        DataReference pointer = null;
        DataReference tallying = null;
        if (context.TALLYING() != null) {
            tallying = resolver.resolve(context.identifier(extra));
            extra--;
            if (tallying == null) {
                return null;
            }
        }
        if (context.POINTER() != null) {
            pointer = resolver.resolve(context.identifier(extra));
            if (pointer == null) {
                return null;
            }
        }
        for (DataReference counter : new DataReference[] {pointer, tallying}) {
            if (counter != null && !DataCategory.of(counter).isNumeric()) {
                report(origin, "POINTER and TALLYING require a numeric item: "
                        + describe(counter));
                return null;
            }
        }
        return new Statement.Unstring(source, delimiters, targets, pointer, tallying,
                overflowOf(context.overflowPhrases()), origin);
    }

    /** {@code ON OVERFLOW} の文。どちらも書かれていなければ {@code null} を返す。 */
    private Statement.Overflow overflowOf(CobolParser.OverflowPhrasesContext phrases) {
        if (phrases == null
                || (phrases.onOverflowPhrase() == null && phrases.notOnOverflowPhrase() == null)) {
            return null;
        }
        List<Statement> onOverflow = phrases.onOverflowPhrase() == null
                ? List.of()
                : listOf(phrases.onOverflowPhrase().statement());
        List<Statement> otherwise = phrases.notOnOverflowPhrase() == null
                ? List.of()
                : listOf(phrases.notOnOverflowPhrase().statement());
        return new Statement.Overflow(onOverflow, otherwise);
    }

    // ---- INSPECT ----

    private Statement inspectOf(CobolParser.InspectStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        DataReference target = resolver.resolve(context.identifier());
        if (target == null) {
            return null;
        }

        List<Statement.Inspect.InspectClause> clauses = new ArrayList<>();
        if (context.tallyingPhrase() != null) {
            for (CobolParser.TallyingCounterContext counter
                    : context.tallyingPhrase().tallyingCounter()) {
                DataReference into = resolver.resolve(counter.identifier());
                if (into == null) {
                    return null;
                }
                if (!DataCategory.of(into).isNumeric()) {
                    report(origin, "TALLYING requires a numeric counter: " + describe(into));
                    return null;
                }
                for (CobolParser.TallyingSpecContext spec : counter.tallyingSpec()) {
                    Statement.Inspect.InspectClause clause = tallyingSpecOf(spec, into, origin);
                    if (clause == null) {
                        return null;
                    }
                    clauses.add(clause);
                }
            }
        }
        if (context.replacingPhrase() != null) {
            for (CobolParser.ReplacingSpecContext spec : context.replacingPhrase().replacingSpec()) {
                Statement.Inspect.InspectClause clause = replacingSpecOf(spec, origin);
                if (clause == null) {
                    return null;
                }
                clauses.add(clause);
            }
        }

        Statement.Inspect.Converting converting = null;
        if (context.convertingPhrase() != null) {
            CobolParser.ConvertingPhraseContext phrase = context.convertingPhrase();
            Operand from = inspectOperandOf(phrase.inspectOperand(0), origin);
            Operand to = inspectOperandOf(phrase.inspectOperand(1), origin);
            Statement.Inspect.RegionSpec region = regionOf(phrase.inspectRegion(), origin);
            if (from == null || to == null || region == null) {
                return null;
            }
            converting = new Statement.Inspect.Converting(from, to, region);
        }
        return new Statement.Inspect(target, clauses, converting, origin);
    }

    private Statement.Inspect.InspectClause tallyingSpecOf(CobolParser.TallyingSpecContext context,
                                                           DataReference counter, Origin origin) {
        Statement.Inspect.RegionSpec region = regionOf(context.inspectRegion(), origin);
        if (region == null) {
            return null;
        }
        if (context.CHARACTERS() != null) {
            return new Statement.Inspect.InspectClause(
                    Statement.Inspect.Kind.CHARACTERS, null, null, counter, region);
        }
        Operand pattern = inspectOperandOf(context.inspectOperand(), origin);
        if (pattern == null) {
            return null;
        }
        Statement.Inspect.Kind kind = context.ALL() != null
                ? Statement.Inspect.Kind.ALL
                : Statement.Inspect.Kind.LEADING;
        return new Statement.Inspect.InspectClause(kind, pattern, null, counter, region);
    }

    private Statement.Inspect.InspectClause replacingSpecOf(
            CobolParser.ReplacingSpecContext context, Origin origin) {
        Statement.Inspect.RegionSpec region = regionOf(context.inspectRegion(), origin);
        if (region == null) {
            return null;
        }
        List<CobolParser.InspectOperandContext> operands = context.inspectOperand();
        if (context.CHARACTERS() != null) {
            Operand to = inspectOperandOf(operands.get(0), origin);
            return to == null ? null : new Statement.Inspect.InspectClause(
                    Statement.Inspect.Kind.CHARACTERS, null, to, null, region);
        }
        Operand pattern = inspectOperandOf(operands.get(0), origin);
        Operand to = inspectOperandOf(operands.get(1), origin);
        if (pattern == null || to == null) {
            return null;
        }
        Statement.Inspect.Kind kind;
        if (context.ALL() != null) {
            kind = Statement.Inspect.Kind.ALL;
        } else if (context.LEADING() != null) {
            kind = Statement.Inspect.Kind.LEADING;
        } else {
            kind = Statement.Inspect.Kind.FIRST;
        }
        return new Statement.Inspect.InspectClause(kind, pattern, to, null, region);
    }

    /** {@code BEFORE} / {@code AFTER} の指定。書かれていなければ項目の全体になる。 */
    private Statement.Inspect.RegionSpec regionOf(List<CobolParser.InspectRegionContext> contexts,
                                                  Origin origin) {
        Operand after = null;
        Operand before = null;
        for (CobolParser.InspectRegionContext context : contexts) {
            Operand operand = inspectOperandOf(context.inspectOperand(), origin);
            if (operand == null) {
                return null;
            }
            if (context.AFTER() != null) {
                after = operand;
            } else {
                before = operand;
            }
        }
        return new Statement.Inspect.RegionSpec(after, before);
    }

    private Operand inspectOperandOf(CobolParser.InspectOperandContext context, Origin origin) {
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
        List<Statement.Perform.Varying> varying = List.of();
        CobolParser.PerformPhraseContext phrase = context.performPhrase();
        if (phrase != null) {
            testAfter = phrase.performTest() != null && phrase.performTest().AFTER() != null;
            if (phrase.TIMES() != null) {
                times = operandOf(phrase.arithmeticOperand(), origin);
                if (times == null) {
                    return null;
                }
            } else if (phrase.varyingPhrase() != null) {
                varying = varyingOf(phrase, origin);
                if (varying == null) {
                    return null;
                }
            } else {
                until = conditionOf(phrase.condition());
                if (until == null) {
                    return null;
                }
            }
        }

        List<Statement> body = new ArrayList<>();
        for (CobolParser.StatementContext statement : context.statement()) {
            Statement built = statementOf(statement);
            if (built != null) {
                body.add(built);
            }
        }
        return new Statement.Perform(target, through, times, until, testAfter, varying, body,
                origin);
    }

    /**
     * {@code VARYING} … {@code AFTER} … を外側から内側の順に並べる。
     *
     * <p>{@code AFTER} は入れ子の内側であり、並び順がそのまま深さになる。
     *
     * @return 組み立てられなければ {@code null}
     */
    private List<Statement.Perform.Varying> varyingOf(CobolParser.PerformPhraseContext phrase,
                                                      Origin origin) {
        List<CobolParser.VaryingSpecContext> specs = new ArrayList<>();
        specs.add(phrase.varyingPhrase().varyingSpec());
        for (CobolParser.VaryingAfterPhraseContext after : phrase.varyingAfterPhrase()) {
            specs.add(after.varyingSpec());
        }

        List<Statement.Perform.Varying> varying = new ArrayList<>();
        for (CobolParser.VaryingSpecContext spec : specs) {
            DataReference target = resolver.resolve(spec.identifier());
            Operand from = operandOf(spec.arithmeticOperand(0), origin);
            Operand by = operandOf(spec.arithmeticOperand(1), origin);
            Condition until = conditionOf(spec.condition());
            if (target == null || from == null || by == null || until == null) {
                return null;
            }
            varying.add(new Statement.Perform.Varying(target, from, by, until));
        }
        return varying;
    }

    /**
     * {@code EVALUATE} を {@code IF} の連なりへ展開する。
     *
     * <p>{@code EVALUATE} は「主語と目的語を突き合わせ、最初に当たった枝を通る」ものであり、
     * <b>{@code IF} … {@code ELSE IF} … {@code ELSE} と同じ意味である</b>。
     * 別の形として持つと、コード生成が同じ分岐を 2 度書くことになる。
     */
    private Statement evaluateOf(CobolParser.EvaluateStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        List<CobolParser.EvaluateSubjectContext> subjects = context.evaluateSubject();

        List<Condition> conditions = new ArrayList<>();
        List<List<Statement>> bodies = new ArrayList<>();
        for (CobolParser.EvaluateBranchContext branch : context.evaluateBranch()) {
            Condition condition = branchCondition(branch, subjects, origin);
            if (condition == null) {
                return null;
            }
            conditions.add(condition);
            bodies.add(listOf(branch.statement()));
        }

        // WHEN OTHER の文は、いちばん外側の ELSE になる
        List<Statement> otherwise = context.OTHER() == null
                ? List.of()
                : listOf(context.statement());

        Statement result = null;
        for (int i = conditions.size() - 1; i >= 0; i--) {
            List<Statement> elseBranch = result == null ? otherwise : List.of(result);
            result = new Statement.If(conditions.get(i), bodies.get(i), elseBranch, origin);
        }
        return result;
    }

    /** 1 つの枝の条件。同じ本体に並べた複数の {@code WHEN} は選言になる。 */
    private Condition branchCondition(CobolParser.EvaluateBranchContext branch,
                                      List<CobolParser.EvaluateSubjectContext> subjects,
                                      Origin origin) {
        List<CobolParser.EvaluateObjectContext> objects = branch.evaluateObject();
        if (objects.size() % subjects.size() != 0) {
            report(origin, "the number of WHEN objects does not match the number of subjects");
            return null;
        }
        Condition result = null;
        for (int start = 0; start < objects.size(); start += subjects.size()) {
            Condition alternative = null;
            for (int i = 0; i < subjects.size(); i++) {
                Condition test = objectCondition(subjects.get(i), objects.get(start + i), origin);
                if (test == null) {
                    return null;
                }
                if (test == ALWAYS_TRUE) {
                    // ANY はその位置を問わないという指定である
                    continue;
                }
                alternative = alternative == null ? test : new Condition.And(alternative, test);
            }
            if (alternative == null) {
                alternative = alwaysTrue(origin);
            }
            result = result == null ? alternative : new Condition.Or(result, alternative);
        }
        return result;
    }

    /**
     * {@code ANY} を表す印。同一性で見分ける。
     * 万一漏れても<b>つねに成り立つ条件として振る舞う</b>ようにしてある。
     */
    private static final Condition ALWAYS_TRUE = alwaysTrue(null);

    /** つねに成り立つ条件。{@code 0 = 0} で表す。 */
    private static Condition alwaysTrue(Origin origin) {
        Operand zero = new Operand.Literal(
                new LiteralValue.Figure(LiteralValue.FigurativeConstant.ZERO));
        return new Condition.Relation(zero, Condition.Comparison.EQUAL, zero, true, origin);
    }

    /**
     * 主語 1 個と目的語 1 個の突き合わせ。
     *
     * <p>主語が {@code TRUE} / {@code FALSE} なら目的語は条件そのもの、
     * そうでなければ<b>主語と目的語の値を比べる</b>。
     */
    private Condition objectCondition(CobolParser.EvaluateSubjectContext subject,
                                      CobolParser.EvaluateObjectContext object, Origin origin) {
        if (object.ANY() != null) {
            return ALWAYS_TRUE;
        }
        boolean truthMode = subject.TRUE() != null || subject.FALSE() != null;
        if (truthMode) {
            Condition condition = truthObject(object, origin);
            if (condition == null) {
                return null;
            }
            // EVALUATE FALSE は、当たる枝の条件が成り立たないことを問う
            return subject.FALSE() == null ? condition : new Condition.Not(condition);
        }
        return valueObject(subject.arithmeticOperand(), object, origin);
    }

    /** {@code EVALUATE TRUE} の目的語。条件として読む。 */
    private Condition truthObject(CobolParser.EvaluateObjectContext object, Origin origin) {
        if (object.TRUE() != null) {
            return alwaysTrue(origin);
        }
        if (object.FALSE() != null) {
            return new Condition.Not(alwaysTrue(origin));
        }
        if (object.condition() != null) {
            return conditionOf(object.condition());
        }
        report(origin, "EVALUATE TRUE requires a condition in its WHEN");
        return null;
    }

    /** 値を比べる目的語。{@code THRU} なら範囲になる。 */
    private Condition valueObject(CobolParser.ArithmeticOperandContext subject,
                                  CobolParser.EvaluateObjectContext object, Origin origin) {
        Operand left = operandOf(subject, origin);
        List<Operand> values = valuesOf(object, origin);
        if (left == null || values == null || values.contains(null)) {
            return null;
        }
        Condition test = values.size() == 1
                ? relation(left, Condition.Comparison.EQUAL, values.get(0), origin)
                : new Condition.And(
                        relation(left, Condition.Comparison.GREATER_OR_EQUAL, values.get(0), origin),
                        relation(left, Condition.Comparison.LESS_OR_EQUAL, values.get(1), origin));
        return object.NOT() == null ? test : new Condition.Not(test);
    }

    /**
     * 目的語から比べる値を取り出す。
     *
     * <p>主語が {@code TRUE} でない場合、名前だけの目的語は<b>条件名ではなく値</b>である。
     * 文法だけでは見分けられないため、ここで読み替える。
     */
    private List<Operand> valuesOf(CobolParser.EvaluateObjectContext object, Origin origin) {
        if (!object.arithmeticOperand().isEmpty()) {
            List<Operand> values = new ArrayList<>();
            for (CobolParser.ArithmeticOperandContext value : object.arithmeticOperand()) {
                values.add(operandOf(value, origin));
            }
            return values;
        }
        CobolParser.IdentifierContext name = soleNameOf(object.condition());
        if (name != null) {
            DataReference reference = resolver.resolve(name);
            return reference == null ? null : List.of(new Operand.Reference(reference));
        }
        report(origin, "a WHEN object must be a value when the subject is not TRUE or FALSE");
        return null;
    }

    /** 条件が「名前だけ」であれば、その名前を返す。 */
    private static CobolParser.IdentifierContext soleNameOf(
            CobolParser.ConditionContext condition) {
        if (condition == null || condition.orCondition().andCondition().size() != 1) {
            return null;
        }
        CobolParser.AndConditionContext and = condition.orCondition().andCondition(0);
        if (and.notCondition().size() != 1 || and.notCondition(0).NOT() != null) {
            return null;
        }
        CobolParser.SimpleConditionContext simple = and.notCondition(0).simpleCondition();
        return simple.conditionNameCondition() == null
                ? null
                : simple.conditionNameCondition().identifier();
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

    /**
     * {@code COMPUTE}。ほかの算術文との違いは式を取ることだけである。
     */
    private Statement computeOf(CobolParser.ComputeStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        List<Statement.Arithmetic.Target> targets = targetsOf(context.roundedTarget());
        Expression value = expressionOf(context.expression(), origin);
        if (value == null || targets.contains(null) || targets.isEmpty()) {
            return null;
        }
        for (Statement.Arithmetic.Target target : targets) {
            if (!DataCategory.of(target.reference()).isNumeric()) {
                report(origin, "an arithmetic statement requires a numeric receiver: "
                        + describe(target.reference()));
                return null;
            }
        }
        return new Statement.Compute(value, targets, sizeErrorOf(context.sizeErrorPhrases()),
                origin);
    }

    /**
     * 算術式を木にする。優先順位と結合は文法が決めており、ここは形を写すだけである。
     *
     * @return 組み立てられなければ {@code null}
     */
    private Expression expressionOf(CobolParser.ExpressionContext context, Origin origin) {
        if (context instanceof CobolParser.OperandExpressionContext operand) {
            Operand value = operandOf(operand.arithmeticOperand(), origin);
            return value == null ? null : new Expression.Value(value);
        }
        if (context instanceof CobolParser.ParenthesizedExpressionContext parens) {
            return expressionOf(parens.expression(), origin);
        }
        if (context instanceof CobolParser.UnaryExpressionContext unary) {
            Expression inner = expressionOf(unary.expression(), origin);
            if (inner == null) {
                return null;
            }
            // 単項の + は何もしない
            return unary.MINUS_SIGN() == null ? inner : new Expression.Negate(inner);
        }
        if (context instanceof CobolParser.PowerExpressionContext) {
            report(origin, "exponentiation is not supported yet");
            return null;
        }
        return binaryOf(context, origin);
    }

    private Expression binaryOf(CobolParser.ExpressionContext context, Origin origin) {
        Expression.Operator operator;
        List<CobolParser.ExpressionContext> parts;
        if (context instanceof CobolParser.MultiplicativeExpressionContext multiplicative) {
            operator = multiplicative.TIMES_SIGN() != null
                    ? Expression.Operator.MULTIPLY
                    : Expression.Operator.DIVIDE;
            parts = multiplicative.expression();
        } else {
            CobolParser.AdditiveExpressionContext additive =
                    (CobolParser.AdditiveExpressionContext) context;
            operator = additive.PLUS_SIGN() != null
                    ? Expression.Operator.ADD
                    : Expression.Operator.SUBTRACT;
            parts = additive.expression();
        }
        Expression left = expressionOf(parts.get(0), origin);
        Expression right = expressionOf(parts.get(1), origin);
        if (left == null || right == null) {
            return null;
        }
        return new Expression.Binary(operator, left, right);
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
