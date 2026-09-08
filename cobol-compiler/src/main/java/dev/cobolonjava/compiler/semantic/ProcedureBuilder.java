package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.parser.CobolParser;
import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.Origin;
import dev.cobolonjava.runtime.file.KeyRelation;
import dev.cobolonjava.runtime.file.Organization;
import dev.cobolonjava.runtime.file.OpenMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final SpecialNames specialNames;
    private final Map<String, FileDescription> files;
    /** 通常の流れが始まる段落の番号。宣言部分はそれより前にある。 */
    private int firstNormal;

    private ProcedureBuilder(DataLayout layout, List<Diagnostic> diagnostics,
                             SpecialNames specialNames, Map<String, FileDescription> files) {
        this.resolver = new ReferenceResolver(layout, diagnostics);
        this.layout = layout;
        this.diagnostics = diagnostics;
        this.specialNames = specialNames;
        this.files = files;
    }

    /**
     * 段落 1 個。
     *
     * @param name       段落名。名前のない先頭の並びは {@code null}
     * @param statements 文の並び
     */
    /**
     * 段落 1 つ。
     *
     * @param segment 段分けの段番号 (要件 FR-061)。50 以上は<b>独立段</b>であり、
     *                別の段から制御が移るたびに {@code ALTER} の書き換えが元へ戻る
     */
    public record Paragraph(String name, List<Statement> statements, int segment,
                            Origin origin) {

        public Paragraph {
            statements = List.copyOf(statements);
        }

        public Paragraph(String name, List<Statement> statements, Origin origin) {
            this(name, statements, 0, origin);
        }

        /**
         * {@code ALTER} で飛び先を書き換えられる段落かどうか。
         *
         * <p>規格は<b>「{@code GO TO} だけを書いた段落」</b>に限っている。行き先が
         * 1 つでなければ、書き換える先が定まらない。
         *
         * @return 書き換えられるなら、その {@code GO TO}。そうでなければ {@code null}
         */
        public Statement.GoTo alterableGoTo() {
            List<Statement> body = statements;
            if (body.size() == 1 && body.get(0) instanceof Statement.Sentence sentence) {
                body = sentence.body();
            }
            return body.size() == 1 && body.get(0) instanceof Statement.GoTo goTo
                    ? goTo
                    : null;
        }
    }

    /**
     * 節 1 個。
     *
     * <p>節は段落をまとめたものである。ここが持つのは<b>どこからどこまでか</b>だけであり、
     * 中身は段落の並びのほうにある。
     *
     * @param first       節見出しの段落。節と同じ名前を持つ
     * @param last        節の最後の段落
     * @param declarative 宣言部分の節かどうか。通常の流れでは通らない
     */
    public record Section(String name, String first, String last, boolean declarative) {
    }

    /**
     * 宣言節 1 個 (要件 FR-105)。
     *
     * <p>{@code USE AFTER STANDARD ERROR PROCEDURE} が結び付けるのは、<b>どの入出力で
     * 呼ばれるか</b>である。ファイル名で指定するか、開き方で指定する。
     *
     * @param files ファイル名で指定したもの。開き方で指定していれば空
     * @param mode  開き方で指定したもの。ファイル名で指定していれば {@code null}
     */
    public record Declarative(String section, String first, String last,
                              List<FileDescription> files, OpenMode mode, Origin origin) {

        public Declarative {
            files = List.copyOf(files);
        }
    }

    /**
     * 組み立ての結果。
     *
     * @param parameters {@code PROCEDURE DIVISION USING} に並べた 01 レベル。書かれた順
     */
    public record Result(List<Paragraph> paragraphs, List<Section> sections,
                         List<Declarative> declaratives, int firstNormalParagraph,
                         List<DataItem> parameters, List<Diagnostic> diagnostics) {

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
    public static Result build(CobolParser.ProgramUnitContext program, DataLayout layout) {
        return build(program, layout, SpecialNames.standard());
    }

    /** 環境部の指定を踏まえて手続き部から文の並びを作る。 */
    public static Result build(CobolParser.ProgramUnitContext program, DataLayout layout,
                               SpecialNames specialNames) {
        return build(program, layout, specialNames, Map.of());
    }

    /** ファイルの宣言も踏まえて手続き部から文の並びを作る。 */
    public static Result build(CobolParser.ProgramUnitContext program, DataLayout layout,
                               SpecialNames specialNames, Map<String, FileDescription> files) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        ProcedureBuilder builder = new ProcedureBuilder(layout, diagnostics, specialNames, files);
        List<Paragraph> paragraphs = new ArrayList<>();
        List<Section> sections = new ArrayList<>();
        List<Declarative> declaratives = new ArrayList<>();
        List<DataItem> parameters = new ArrayList<>();
        for (CobolParser.ProgramUnitContext unit : List.of(program)) {
            if (unit.procedureDivision() != null) {
                parameters.addAll(builder.parametersOf(unit.procedureDivision()));
                builder.addBody(unit.procedureDivision().procedureBody(), paragraphs, sections,
                        declaratives);
            }
        }
        builder.checkProcedureTargets(paragraphs);
        builder.checkDeclaratives(declaratives);
        return new Result(List.copyOf(paragraphs), List.copyOf(sections),
                List.copyOf(declaratives), builder.firstNormal, List.copyOf(parameters),
                List.copyOf(diagnostics));
    }

    /**
     * {@code PROCEDURE DIVISION USING} に並べた項目。
     *
     * <p>並べられるのは<b>連絡節の 01 レベル</b>だけである。作業場所の項目を並べても
     * 渡される先がなく、01 レベル以外を並べても渡された領域の切り出し方が決まらない。
     */
    private List<DataItem> parametersOf(CobolParser.ProcedureDivisionContext context) {
        List<DataItem> parameters = new ArrayList<>();
        for (CobolParser.ProcedureParameterContext parameter : context.procedureParameter()) {
            if (parameter.VALUE() != null) {
                report(ReferenceResolver.originOf(parameter),
                        "BY VALUE is not supported yet");
                continue;
            }
            DataReference reference = resolver.resolve(parameter.identifier());
            if (reference == null) {
                continue;
            }
            DataItem item = reference.item();
            Origin origin = ReferenceResolver.originOf(parameter);
            if (item.section() != DataSection.LINKAGE) {
                report(origin, "PROCEDURE DIVISION USING requires a LINKAGE SECTION item: "
                        + describe(reference));
                continue;
            }
            if (item != item.record()) {
                report(origin, "PROCEDURE DIVISION USING requires an 01 level item: "
                        + describe(reference));
                continue;
            }
            parameters.add(item);
        }
        return parameters;
    }

    /**
     * {@code PERFORM} と {@code GO TO} が名指す段落が実在するか確かめる。
     *
     * <p>段落はあとから書かれることもあるため、すべての段落を組み立てたあとに見る。
     */
    /**
     * 宣言節の指定が重なっていないか確かめる (要件 FR-105)。
     *
     * <p>同じファイルを 2 つの節が受け持てば、どちらが動くか決まらない。
     */
    private void checkDeclaratives(List<Declarative> declaratives) {
        List<String> named = new ArrayList<>();
        List<OpenMode> modes = new ArrayList<>();
        for (Declarative declarative : declaratives) {
            for (FileDescription file : declarative.files()) {
                if (named.contains(file.name())) {
                    report(declarative.origin(),
                            "two USE procedures name the same file: " + file.name());
                }
                named.add(file.name());
            }
            OpenMode mode = declarative.mode();
            if (mode == null) {
                continue;
            }
            if (modes.contains(mode)) {
                report(declarative.origin(),
                        "two USE procedures name the same open mode: " + mode);
            }
            modes.add(mode);
        }
    }

    private void checkProcedureTargets(List<Paragraph> paragraphs) {
        List<String> names = new ArrayList<>();
        for (Paragraph paragraph : paragraphs) {
            names.add(paragraph.name());
        }
        alterable = paragraphs;
        for (Paragraph paragraph : paragraphs) {
            for (Statement statement : paragraph.statements()) {
                checkProcedureTargets(statement, names);
            }
        }
    }

    /** {@code ALTER} が書き換えられる段落かを見るために、段落の並びを覚えておく。 */
    private List<Paragraph> alterable = List.of();

    /**
     * {@code ALTER} の書き換え先を確かめる (要件 FR-063)。
     *
     * <p>書き換えられるのは<b>{@code GO TO} だけを書いた段落</b>である。行き先が
     * 1 つでなければ、書き換える先が定まらない。
     */
    private void checkAlter(Statement.Alter alter, List<String> names) {
        for (Statement.Alter.Change change : alter.changes()) {
            if (!names.contains(change.from()) || !names.contains(change.to())) {
                report(alter.origin(), "undefined paragraph: "
                        + (names.contains(change.from()) ? change.to() : change.from()));
                continue;
            }
            Paragraph target = alterable.get(names.indexOf(change.from()));
            if (target.alterableGoTo() == null) {
                report(alter.origin(), "ALTER requires a paragraph that holds"
                        + " a single GO TO: " + change.from());
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
        if (statement instanceof Statement.GoToDepending depending) {
            for (String target : depending.targets()) {
                if (!names.contains(target)) {
                    report(depending.origin(), "undefined paragraph: " + target);
                }
            }
            return;
        }
        if (statement instanceof Statement.Alter alter) {
            checkAlter(alter, names);
            return;
        }
        if (statement instanceof Statement.Sort sort) {
            checkSortProcedure(sort.input(), names, sort.origin());
            checkSortProcedure(sort.output(), names, sort.origin());
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
        if (statement instanceof Statement.Sequence sequence) {
            return List.of(sequence.statements());
        }
        if (statement instanceof Statement.Sentence sentence) {
            return List.of(sentence.body());
        }
        if (statement instanceof Statement.If branch) {
            return List.of(branch.onTrue(), branch.onFalse());
        }
        if (statement instanceof Statement.Perform perform) {
            return List.of(perform.body());
        }
        if (statement instanceof Statement.Arithmetic arithmetic) {
            return sizeErrorStatements(arithmetic.sizeError());
        }
        if (statement instanceof Statement.ArithmeticGroup group) {
            return sizeErrorStatements(group.sizeError());
        }
        if (statement instanceof Statement.DivideRemainder divide) {
            return sizeErrorStatements(divide.sizeError());
        }
        if (statement instanceof Statement.Call call) {
            return overflowStatements(call.exception());
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
        if (statement instanceof Statement.Read read) {
            List<List<Statement>> nested = new ArrayList<>();
            nested.add(read.atEnd());
            nested.add(read.notAtEnd());
            nested.addAll(keyCheckStatements(read.keyCheck()));
            return List.copyOf(nested);
        }
        if (statement instanceof Statement.Write write) {
            return keyCheckStatements(write.keyCheck());
        }
        if (statement instanceof Statement.Rewrite rewrite) {
            return keyCheckStatements(rewrite.keyCheck());
        }
        if (statement instanceof Statement.Delete delete) {
            return keyCheckStatements(delete.keyCheck());
        }
        if (statement instanceof Statement.Start start) {
            return keyCheckStatements(start.keyCheck());
        }
        if (statement instanceof Statement.Return returned) {
            return List.of(returned.atEnd(), returned.notAtEnd());
        }
        if (statement instanceof Statement.Search search) {
            List<List<Statement>> nested = new ArrayList<>();
            nested.add(search.atEnd());
            search.whens().forEach(when -> nested.add(when.statements()));
            return List.copyOf(nested);
        }
        if (statement instanceof Statement.SearchAll searchAll) {
            return List.of(searchAll.atEnd(), searchAll.whenStatements());
        }
        return List.of();
    }

    /** {@code INPUT PROCEDURE} と {@code OUTPUT PROCEDURE} の名指す節が実在するか。 */
    private void checkSortProcedure(Statement.Sort.Procedure procedure, List<String> names,
                                    Origin origin) {
        if (procedure == null) {
            return;
        }
        if (!names.contains(procedure.from())) {
            report(origin, "undefined paragraph: " + procedure.from());
            return;
        }
        if (procedure.through() != null && !names.contains(procedure.through())) {
            report(origin, "undefined paragraph: " + procedure.through());
        }
    }

    private static List<List<Statement>> sizeErrorStatements(
            Statement.Arithmetic.SizeError sizeError) {
        return sizeError == null
                ? List.of()
                : List.of(sizeError.onError(), sizeError.otherwise());
    }

    private static List<List<Statement>> keyCheckStatements(Statement.KeyCheck keyCheck) {
        return keyCheck == null
                ? List.of()
                : List.of(keyCheck.onInvalid(), keyCheck.otherwise());
    }

    private static List<List<Statement>> overflowStatements(Statement.Overflow overflow) {
        return overflow == null
                ? List.of()
                : List.of(overflow.onOverflow(), overflow.otherwise());
    }

    /**
     * 手続き部の中身を段落の並びにする。
     *
     * <p>節は<b>段落をまとめたもの</b>である。節見出しをその名前の段落として置き、
     * 範囲を別に覚えておけば、{@code PERFORM 節名} は段落の範囲の実行になる。
     * 段落の番号付けと飛び先の仕組みをそのまま使える。
     *
     * <p>宣言部分は<b>いちばん前に置く</b>。通常の流れはそのうしろから始まるので、
     * 落ちて入ってしまうことがない。
     */
    private void addBody(CobolParser.ProcedureBodyContext body, List<Paragraph> paragraphs,
                         List<Section> sections, List<Declarative> declaratives) {
        if (body.declarativesPart() != null) {
            for (CobolParser.DeclarativeSectionContext section
                    : body.declarativesPart().declarativeSection()) {
                addDeclarative(section, paragraphs, sections, declaratives);
            }
        }
        firstNormal = paragraphs.size();
        List<Statement> leading = statementsOf(body.sentence());
        if (!leading.isEmpty()) {
            paragraphs.add(new Paragraph(null, leading, leading.get(0).origin()));
        }
        for (CobolParser.ProcedureUnitContext unit : body.procedureUnit()) {
            if (unit.sectionHeader() != null) {
                addSection(unit, paragraphs, sections);
                continue;
            }
            addParagraph(unit.paragraph(0), paragraphs);
        }
    }

    private void addParagraph(CobolParser.ParagraphContext paragraph, List<Paragraph> paragraphs,
                              int segment) {
        paragraphs.add(new Paragraph(
                paragraph.paragraphName().getText().toUpperCase(Locale.ROOT),
                statementsOf(paragraph.sentence()), segment,
                ReferenceResolver.originOf(paragraph)));
    }

    private void addParagraph(CobolParser.ParagraphContext paragraph, List<Paragraph> paragraphs) {
        addParagraph(paragraph, paragraphs, 0);
    }

    private void addSection(CobolParser.ProcedureUnitContext unit, List<Paragraph> paragraphs,
                            List<Section> sections) {
        String name = unit.sectionHeader().paragraphName().getText().toUpperCase(Locale.ROOT);
        int segment = segmentOf(unit.sectionHeader());
        paragraphs.add(new Paragraph(name, statementsOf(unit.sentence()), segment,
                ReferenceResolver.originOf(unit.sectionHeader())));
        for (CobolParser.ParagraphContext paragraph : unit.paragraph()) {
            addParagraph(paragraph, paragraphs, segment);
        }
        sections.add(new Section(name, name,
                paragraphs.get(paragraphs.size() - 1).name(), false));
    }

    /**
     * 章の段番号 (要件 FR-061)。書かれていなければ 0 である。
     *
     * <p>0〜49 は常駐する段であり、ふつうの章と振る舞いが変わらない。50〜99 は
     * <b>独立段</b>であり、別の段から制御が移るたびに初期状態へ戻る。
     */
    private int segmentOf(CobolParser.SectionHeaderContext context) {
        if (context.NUMBER() == null) {
            return 0;
        }
        try {
            return Integer.parseInt(context.NUMBER().getText());
        } catch (NumberFormatException e) {
            report(ReferenceResolver.originOf(context),
                    "a segment number must be an integer: " + context.NUMBER().getText());
            return 0;
        }
    }

    /**
     * 宣言節を読む (要件 FR-105)。
     *
     * <p>{@code USE AFTER STANDARD ERROR PROCEDURE} は文ではない。その節が<b>いつ動くか</b>の
     * 宣言であり、入出力で異常が起きたときに呼ばれて、終われば元の場所へ戻る。
     */
    private void addDeclarative(CobolParser.DeclarativeSectionContext context,
                                List<Paragraph> paragraphs, List<Section> sections,
                                List<Declarative> declaratives) {
        String name = context.sectionHeader().paragraphName().getText().toUpperCase(Locale.ROOT);
        Origin origin = ReferenceResolver.originOf(context.sectionHeader());
        paragraphs.add(new Paragraph(name, statementsOf(context.sentence()), origin));
        for (CobolParser.ParagraphContext paragraph : context.paragraph()) {
            addParagraph(paragraph, paragraphs);
        }
        String last = paragraphs.get(paragraphs.size() - 1).name();
        sections.add(new Section(name, name, last, true));

        CobolParser.UseTargetContext target = context.useStatement().useTarget();
        OpenMode mode = modeOf(target);
        List<FileDescription> named = new ArrayList<>();
        for (org.antlr.v4.runtime.tree.TerminalNode identifier : target.IDENTIFIER()) {
            FileDescription file = fileOf(identifier.getText(), origin);
            if (file == null) {
                return;
            }
            named.add(file);
        }
        declaratives.add(new Declarative(name, name, last, named, mode, origin));
    }

    private static OpenMode modeOf(CobolParser.UseTargetContext target) {
        if (target.INPUT() != null) {
            return OpenMode.INPUT;
        }
        if (target.OUTPUT() != null) {
            return OpenMode.OUTPUT;
        }
        if (target.I_O() != null) {
            return OpenMode.IO;
        }
        return target.EXTEND() != null ? OpenMode.EXTEND : null;
    }

    /**
     * 文 (センテンス) の並びを組み立てる。
     *
     * <p>1 つの文を {@link Statement.Sentence} で束ねて残す。並べて出すだけなら束ねる
     * 必要はないが、{@code NEXT SENTENCE} の飛び先が<b>文の終わり</b>だからである。
     */
    private List<Statement> statementsOf(List<CobolParser.SentenceContext> sentences) {
        List<Statement> statements = new ArrayList<>();
        for (CobolParser.SentenceContext sentence : sentences) {
            List<Statement> body = listOf(sentence.statement());
            statements.add(new Statement.Sentence(body,
                    ReferenceResolver.originOf(sentence)));
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
            return new Statement.Stop(context.stopStatement().RUN() != null,
                    ReferenceResolver.originOf(context));
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
            return goToOf(context.goToStatement());
        }
        if (context.alterStatement() != null) {
            return alterOf(context.alterStatement());
        }
        if (context.searchStatement() != null) {
            return searchOf(context.searchStatement());
        }
        if (context.acceptStatement() != null) {
            return acceptOf(context.acceptStatement());
        }
        if (context.initializeStatement() != null) {
            return initializeOf(context.initializeStatement());
        }
        if (context.setStatement() != null) {
            return setOf(context.setStatement());
        }
        if (context.callStatement() != null) {
            return callOf(context.callStatement());
        }
        if (context.cancelStatement() != null) {
            return cancelOf(context.cancelStatement());
        }
        if (context.openStatement() != null) {
            return openOf(context.openStatement());
        }
        if (context.closeStatement() != null) {
            return closeOf(context.closeStatement());
        }
        if (context.readStatement() != null) {
            return readOf(context.readStatement());
        }
        if (context.writeStatement() != null) {
            return writeOf(context.writeStatement());
        }
        if (context.rewriteStatement() != null) {
            return rewriteOf(context.rewriteStatement());
        }
        if (context.deleteStatement() != null) {
            return deleteOf(context.deleteStatement());
        }
        if (context.startStatement() != null) {
            return startOf(context.startStatement());
        }
        if (context.sortStatement() != null) {
            return sortOf(context.sortStatement());
        }
        if (context.mergeStatement() != null) {
            return mergeOf(context.mergeStatement());
        }
        if (context.releaseStatement() != null) {
            return releaseOf(context.releaseStatement());
        }
        if (context.returnStatement() != null) {
            return returnOf(context.returnStatement());
        }
        if (context.exitStatement() != null) {
            Origin at = ReferenceResolver.originOf(context.exitStatement());
            if (context.exitStatement().PROGRAM() != null) {
                return new Statement.ExitProgram(at);
            }
            // EXIT だけなら何もしない。PERFORM ... THRU の終わりに置く段落のためにある
            return new Statement.Continue(at);
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
        SpecialNames.FunctionName upon = null;
        if (context.UPON() != null) {
            upon = outputOf(context.IDENTIFIER().getText(), origin);
            if (upon == null) {
                return null;
            }
        }
        return new Statement.Display(operands, context.ADVANCING() == null, upon, origin);
    }

    /**
     * {@code DISPLAY ... UPON 呼び名} の行き先 (要件 FR-135)。
     *
     * <p>呼び名は環境部の {@code SPECIAL-NAMES} で機能名と結び付ける。読み取る側の機能名を
     * 書いていれば誤りである。
     */
    private SpecialNames.FunctionName outputOf(String mnemonic, Origin origin) {
        SpecialNames.FunctionName function = specialNames.mnemonic(mnemonic);
        if (function == null) {
            report(origin, "undefined mnemonic name: " + mnemonic
                    + "; declare it in SPECIAL-NAMES");
            return null;
        }
        if (function == SpecialNames.FunctionName.SYSIN) {
            report(origin, "DISPLAY UPON requires an output device: " + mnemonic);
            return null;
        }
        return function;
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

    /** {@code NEXT SENTENCE} は「この文の残りを飛ばして次の文へ移る」ことである。 */
    private List<Statement> branchOf(CobolParser.IfBranchContext context) {
        return context.NEXT() != null
                ? List.of(new Statement.NextSentence(ReferenceResolver.originOf(context)))
                : listOf(context.statement());
    }

    /**
     * {@code SEARCH} (要件 FR-066)。
     *
     * <p>表をいまの指標の位置から順に見る。<b>指標は初期化しない</b>のが要である。
     * どこから見はじめるかは直前の {@code SET} が決める。すでに範囲の外なら
     * 一度も見ずに {@code AT END} へ行く。
     */
    private Statement searchOf(CobolParser.SearchStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        CobolParser.IdentifierContext tableName = context.identifier(0);
        DataItem table = resolver.resolveName(tableName.qualifiedDataName(), origin);
        if (table == null) {
            return null;
        }
        String name = table.name();
        if (!table.isTable()) {
            report(origin, "SEARCH requires a table: " + name);
            return null;
        }
        if (table.indexNames().isEmpty()) {
            report(origin, "SEARCH requires INDEXED BY on " + name);
            return null;
        }
        if (tableName.subscripts() != null) {
            report(origin, "SEARCH takes the table itself, not one occurrence: " + name);
            return null;
        }

        DataReference index = indexReference(table.indexNames().get(0), origin);
        if (context.ALL() != null) {
            return searchAllOf(context, table, index, origin);
        }
        DataReference varying = null;
        if (context.VARYING() != null) {
            DataReference given = resolver.resolve(context.identifier(1));
            if (given == null) {
                return null;
            }
            // この表の指標を書いたなら、それが進める指標になる。
            // ほかの項目なら、指標と一緒に進める
            if (given.item().isIndex()
                    && table.indexNames().contains(indexNameOf(given.item()))) {
                index = given;
            } else {
                varying = given;
            }
        }

        List<Statement> atEnd = context.atEndPhrase() == null
                ? List.of()
                : bodyOf(context.atEndPhrase().branchBody());
        List<Statement.Search.When> whens = new ArrayList<>();
        for (CobolParser.SearchWhenContext when : context.searchWhen()) {
            Condition condition = conditionOf(when.condition());
            if (condition == null) {
                return null;
            }
            whens.add(new Statement.Search.When(condition, bodyOf(when.branchBody())));
        }
        return new Statement.Search(index, varying, table.occurs(), atEnd, whens, origin);
    }

    /**
     * {@code SEARCH ALL} (要件 FR-066)。
     *
     * <p>2 分探索は<b>大きいか小さいかで半分を捨てる</b>仕組みである。したがって
     * 書ける条件は鍵と値の等号だけであり、表に {@code ASCENDING} / {@code DESCENDING KEY} が
     * 書かれていなければ向きが決まらない。
     *
     * <p>鍵は<b>書かれた順に、先頭から欠かさず</b>照合しなければならない。2 番目の鍵だけを
     * 指定しても、1 番目で並んでいる表は絞り込めない。
     */
    private Statement searchAllOf(CobolParser.SearchStatementContext context, DataItem table,
                                  DataReference index, Origin origin) {
        if (DataReference.tableChain(table).size() != 1) {
            // 2 分探索は鍵の位置を自分で計算する。外側の添字を受け取る道がまだない
            report(origin, "SEARCH ALL on a table inside another table is not supported yet: "
                    + table.name());
            return null;
        }
        if (table.searchKeys().isEmpty()) {
            report(origin, "SEARCH ALL requires ASCENDING or DESCENDING KEY on " + table.name());
            return null;
        }
        if (context.searchWhen().size() != 1) {
            report(origin, "SEARCH ALL takes a single WHEN");
            return null;
        }
        CobolParser.SearchWhenContext when = context.searchWhen().get(0);
        Condition condition = conditionOf(when.condition());
        if (condition == null) {
            return null;
        }

        List<Condition> parts = new ArrayList<>();
        if (!flattenConjunction(condition, parts)) {
            report(origin, "SEARCH ALL takes equality tests joined by AND");
            return null;
        }
        if (parts.size() > table.searchKeys().size()) {
            report(origin, "SEARCH ALL tests more keys than " + table.name() + " declares");
            return null;
        }

        List<Statement.SearchAll.KeyTest> keys = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            DataItem.SearchKey declared = table.searchKeys().get(i);
            Condition.Relation test = keyTestOf(parts.get(i), declared.name(), index, origin);
            if (test == null) {
                return null;
            }
            keys.add(new Statement.SearchAll.KeyTest(declared.ascending(), test));
        }

        List<Statement> atEnd = context.atEndPhrase() == null
                ? List.of()
                : bodyOf(context.atEndPhrase().branchBody());
        return new Statement.SearchAll(index, table.occurs(), keys, atEnd,
                bodyOf(when.branchBody()), origin);
    }

    /** 条件を {@code AND} でつないだ等号の並びへ開く。ほかの形が混ざれば偽を返す。 */
    private static boolean flattenConjunction(Condition condition, List<Condition> parts) {
        if (condition instanceof Condition.And and) {
            return flattenConjunction(and.left(), parts) && flattenConjunction(and.right(), parts);
        }
        if (condition instanceof Condition.Relation relation
                && relation.comparison() == Condition.Comparison.EQUAL) {
            parts.add(relation);
            return true;
        }
        return false;
    }

    /**
     * 等号の片側が、期待した鍵かどうかを見る。
     *
     * <p>鍵は左右どちらに書いてもよい。3 方向の比較のために<b>鍵を左へ揃える</b>。
     */
    private Condition.Relation keyTestOf(Condition part, String keyName, DataReference index,
                                        Origin origin) {
        Condition.Relation relation = (Condition.Relation) part;
        Condition.Relation ordered = null;
        if (namesItem(relation.left(), keyName)) {
            ordered = relation;
        } else if (namesItem(relation.right(), keyName)) {
            ordered = new Condition.Relation(relation.right(), Condition.Comparison.EQUAL,
                    relation.left(), relation.numeric(), relation.origin());
        }
        if (ordered == null) {
            report(origin, "SEARCH ALL must test the keys in the order they are declared;"
                    + " expected " + keyName);
            return null;
        }
        // 探索が動かすのはこの指標である。別の添字で引いていたら絞り込みにならない
        if (!subscriptedBy(ordered.left(), index)) {
            report(origin, "SEARCH ALL requires the key to be subscripted by the search index: "
                    + keyName);
            return null;
        }
        return ordered;
    }

    /**
     * {@code GO TO} (要件 FR-063)。
     *
     * <p>{@code DEPENDING ON} があれば、値が<b>何番目か</b>で飛び先が決まる。
     * 無ければ飛び先は 1 つだけである。
     */
    private Statement goToOf(CobolParser.GoToStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        List<String> targets = new ArrayList<>();
        for (CobolParser.ParagraphNameContext name : context.paragraphName()) {
            targets.add(name.getText().toUpperCase(Locale.ROOT));
        }
        if (context.DEPENDING() == null) {
            if (targets.size() > 1) {
                report(origin, "GO TO takes one procedure name unless DEPENDING ON is written");
                return null;
            }
            return new Statement.GoTo(targets.get(0), origin);
        }
        DataReference selector = resolver.resolve(context.identifier());
        if (selector == null) {
            return null;
        }
        if (!DataCategory.of(selector).isNumeric()) {
            report(origin, "GO TO ... DEPENDING ON requires an integer item: "
                    + describe(selector));
            return null;
        }
        return new Statement.GoToDepending(List.copyOf(targets), selector, origin);
    }

    /** {@code ALTER} (要件 FR-063)。 */
    private Statement alterOf(CobolParser.AlterStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        List<Statement.Alter.Change> changes = new ArrayList<>();
        for (CobolParser.AlterChangeContext change : context.alterChange()) {
            changes.add(new Statement.Alter.Change(
                    change.paragraphName(0).getText().toUpperCase(Locale.ROOT),
                    change.paragraphName(1).getText().toUpperCase(Locale.ROOT)));
        }
        return new Statement.Alter(List.copyOf(changes), origin);
    }

    private static boolean namesItem(Expression side, String name) {
        return Condition.Relation.operandOf(side) instanceof Operand.Reference reference
                && name.equals(reference.reference().item().name());
    }

    /** 参照の添字が、探索の指標そのものかどうか。 */
    private static boolean subscriptedBy(Expression side, DataReference index) {
        List<DataReference.Subscript> subscripts = ((Operand.Reference)
                Condition.Relation.operandOf(side)).reference().subscripts();
        return subscripts.size() == 1
                && subscripts.get(0) instanceof DataReference.Subscript.Variable variable
                && variable.offset() == 0
                && variable.reference().item() == index.item();
    }

    private DataReference indexReference(String name, Origin origin) {
        return new DataReference(layout.findIndex(name), List.of(), null, origin);
    }

    /**
     * 指標の実体から、書かれていた指標名へ戻す。
     *
     * <p>{@code INDEXED BY} が作る隠しデータ項目には印が付いている。
     * {@code USAGE IS INDEX} と書かれた指標データ項目には付かないので、
     * <b>そのときは書かれた名前がそのまま名前である</b>。
     */
    private static String indexNameOf(DataItem item) {
        String name = item.name();
        return name.startsWith(INDEX_MARK) ? name.substring(INDEX_MARK.length()) : name;
    }

    /** {@code INDEXED BY} が作る隠しデータ項目に付く印 ({@code DataDivisionBuilder} と対) 。 */
    private static final String INDEX_MARK = "IDX$";

    /**
     * {@code ACCEPT} (要件 FR-060、テスト時の固定は FR-204)。
     *
     * <p>送出側は日付と時刻の特殊レジスタか、端末から読んだ 1 行である。どちらも
     * <b>符号なし整数の表示形式のバイト列</b>として扱い、受け取る項目への詰め方は
     * 普通の転記と同じ規則で決める。
     */
    private Statement acceptOf(CobolParser.AcceptStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        DataReference target = resolver.resolve(context.identifier());
        if (target == null) {
            return null;
        }

        Statement.Accept.Source source = Statement.Accept.Source.CONSOLE;
        String register = null;
        CobolParser.AcceptSourceContext from = context.acceptSource();
        if (from != null) {
            if (from.identifier() != null) {
                String mnemonic = from.identifier().getText();
                SpecialNames.FunctionName function = specialNames.mnemonic(mnemonic);
                if (function == null) {
                    report(origin, "undefined mnemonic name: " + mnemonic
                            + "; declare it in SPECIAL-NAMES");
                    return null;
                }
                if (!function.isInput()) {
                    report(origin, "ACCEPT FROM requires an input device: " + mnemonic);
                    return null;
                }
                // 端末も標準入力も、読むのは 1 行である
            } else {
                source = Statement.Accept.Source.REGISTER;
                register = registerOf(from);
            }
        }

        DataCategory receiver = DataCategory.of(target);
        // 送出側は符号なし整数の表示形式である。分類は英数字として扱う
        if (!MoveRules.isAllowed(DataCategory.ALPHANUMERIC, receiver)
                && !receiver.isNumeric()) {
            report(origin, "ACCEPT into " + describe(target) + " is not allowed: "
                    + MoveRules.reason(DataCategory.ALPHANUMERIC, receiver));
            return null;
        }
        MoveRules.Kind kind = receiver.isNumeric()
                ? MoveRules.Kind.NUMERIC
                : MoveRules.Kind.ALPHANUMERIC;
        return new Statement.Accept(target, source, register, kind, origin);
    }

    /** 特殊レジスタの形式。ランタイムの {@code SpecialRegisters.Form} に対応する。 */
    private static String registerOf(CobolParser.AcceptSourceContext context) {
        if (context.DATE() != null) {
            return context.YYYYMMDD() != null ? "DATE_YYYYMMDD" : "DATE";
        }
        if (context.DAY() != null) {
            return context.YYYYDDD() != null ? "DAY_YYYYDDD" : "DAY";
        }
        return context.TIME() != null ? "TIME" : "DAY_OF_WEEK";
    }

    /**
     * {@code INITIALIZE} (要件 FR-060)。
     *
     * <p>書き込むバイト列を翻訳時に組み立てる ({@link InitializeImage})。基本項目ごとの
     * 転記へ素直に展開すると、表の反復の数だけ転記が並んでしまう。
     */
    private Statement initializeOf(CobolParser.InitializeStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        boolean withFiller = context.FILLER() != null;
        List<InitializeImage.Replacing> replacing = new ArrayList<>();
        for (CobolParser.InitializeReplacingContext rule : context.initializeReplacing()) {
            if (rule.identifier() != null) {
                // 値が実行時に決まる形は、反復の数だけ転記が並ぶことになる
                report(origin, "INITIALIZE ... REPLACING BY a data item is not supported yet");
                return null;
            }
            InitializeImage.Category category = categoryOf(rule.initializeCategory());
            try {
                replacing.add(new InitializeImage.Replacing(category,
                        LiteralValue.of(rule.literal())));
            } catch (RuntimeException e) {
                report(origin, "invalid literal: " + rule.literal().getText());
                return null;
            }
        }

        List<Statement> statements = new ArrayList<>();
        for (CobolParser.IdentifierContext identifier : context.identifier()) {
            DataReference target = resolver.resolve(identifier);
            if (target == null) {
                return null;
            }
            if (target.refMod() != null) {
                report(origin, "INITIALIZE cannot take a reference modification: "
                        + describe(target));
                return null;
            }
            statements.add(new Statement.Initialize(target, withFiller, replacing, origin));
        }
        return statements.size() == 1
                ? statements.get(0)
                : new Statement.Sequence(statements, origin);
    }

    private static InitializeImage.Category categoryOf(
            CobolParser.InitializeCategoryContext context) {
        if (context.ALPHABETIC() != null) {
            return InitializeImage.Category.ALPHABETIC;
        }
        if (context.ALPHANUMERIC_EDITED() != null) {
            return InitializeImage.Category.ALPHANUMERIC_EDITED;
        }
        if (context.ALPHANUMERIC() != null) {
            return InitializeImage.Category.ALPHANUMERIC;
        }
        return context.NUMERIC_EDITED() != null
                ? InitializeImage.Category.NUMERIC_EDITED
                : InitializeImage.Category.NUMERIC;
    }

    /**
     * {@code SET 条件名 TO TRUE} (要件 FR-068)。
     *
     * <p>条件名を成り立たせるとは、<b>親の項目にその条件名の値を入れる</b>ことである。
     * 値が複数書かれていれば最初のものを入れる。範囲なら下限を入れる。
     * したがって普通の {@code MOVE} へ展開できる。
     */
    private Statement setOf(CobolParser.SetStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        if (context.TRUE() != null) {
            List<Statement> moves = new ArrayList<>();
            for (CobolParser.IdentifierContext identifier : context.identifier()) {
                String name = identifier.qualifiedDataName().dataName(0).getText()
                        .toUpperCase(Locale.ROOT);
                Statement move = conditionNameMove(identifier, name, origin);
                if (move == null) {
                    return null;
                }
                moves.add(move);
            }
            return moves.size() == 1 ? moves.get(0) : new Statement.Sequence(moves, origin);
        }
        return indexSetOf(context, origin);
    }

    /**
     * {@code SET 指標名 TO n} と {@code SET 指標名 UP/DOWN BY n} (要件 FR-025)。
     *
     * <p>指標名が持つのは<b>何番目か</b>である。したがって {@code TO} は転記、
     * {@code UP BY} と {@code DOWN BY} は加算と減算になる。
     *
     * <p>{@code TO} の受取側は<b>指標名でなくてもよい</b>。整数の項目でもよく、そのときは
     * 「何番目か」がそこへ入る。規格がそう決めており、実資産も
     * {@code SET WS-COUNT TO IDX-1} と書く。ここが指標名に限られていると、
     * <b>表の何番目にいるかを取り出す手立てが無くなる</b>。
     *
     * <p>{@code UP BY} と {@code DOWN BY} の受取側は指標名に限る。動かしているのは
     * 表の中の位置そのものだからである。
     */
    private Statement indexSetOf(CobolParser.SetStatementContext context, Origin origin) {
        Operand value = operandOf(context.arithmeticOperand(), origin);
        if (value == null) {
            return null;
        }
        boolean stepping = context.TO() == null;
        List<Statement.Arithmetic.Target> targets = new ArrayList<>();
        for (CobolParser.IdentifierContext identifier : context.identifier()) {
            DataReference reference = resolver.resolve(identifier);
            if (reference == null) {
                return null;
            }
            if (stepping && !reference.item().isIndex()) {
                report(origin, "SET UP/DOWN BY requires an index name: " + describe(reference));
                return null;
            }
            if (!stepping && !reference.item().isIndex()
                    && !DataCategory.of(reference).isNumeric()) {
                report(origin, "SET TO requires an index name or an integer item: "
                        + describe(reference));
                return null;
            }
            targets.add(new Statement.Arithmetic.Target(reference, false));
        }

        if (context.TO() != null) {
            List<Statement> moves = new ArrayList<>();
            for (Statement.Arithmetic.Target target : targets) {
                Statement.Move.Target checked =
                        checkMove(value, target.reference(), origin, true);
                if (checked == null) {
                    return null;
                }
                moves.add(new Statement.Move(value, List.of(checked), false, origin));
            }
            return moves.size() == 1 ? moves.get(0) : new Statement.Sequence(moves, origin);
        }
        Statement.Arithmetic.Operator operator = context.UP() != null
                ? Statement.Arithmetic.Operator.ADD
                : Statement.Arithmetic.Operator.SUBTRACT;
        return new Statement.Arithmetic(Statement.Arithmetic.Operator.ADD, List.of(value),
                operator, targets, null, origin);
    }

    private Statement conditionNameMove(CobolParser.IdentifierContext context, String name,
                                        Origin origin) {
        for (DataItem item : layout.all()) {
            for (DataItem.ConditionName conditionName : item.conditionNames()) {
                if (!name.equals(conditionName.name())) {
                    continue;
                }
                if (conditionName.values().isEmpty()) {
                    report(origin, "condition-name has no value: " + name);
                    return null;
                }
                Operand source = new Operand.Literal(conditionName.values().get(0).from());
                DataReference target = resolver.resolveAs(item, context);
                if (target == null) {
                    return null;
                }
                Statement.Move.Target checked = checkMove(source, target, origin);
                return checked == null
                        ? null
                        : new Statement.Move(source, List.of(checked), false, origin);
            }
        }
        report(origin, "undefined condition-name: " + name);
        return null;
    }

    /**
     * {@code CALL}。
     *
     * <p>{@code BY REFERENCE} と {@code BY CONTENT} は<b>次の指定が現れるまで</b>
     * 後ろの引数すべてに効く。引数ごとに書き直す必要はない、という参照実装の規則である。
     */
    private Statement callOf(CobolParser.CallStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        Operand target = callTargetOf(context.callTarget(), origin);
        if (target == null) {
            return null;
        }

        boolean byContent = false;
        List<Statement.Call.Argument> arguments = new ArrayList<>();
        for (CobolParser.CallArgumentContext argument : context.callArgument()) {
            if (argument.VALUE() != null) {
                report(origin, "CALL ... BY VALUE is not supported yet");
                return null;
            }
            if (argument.REFERENCE() != null) {
                byContent = false;
                continue;
            }
            if (argument.CONTENT() != null) {
                byContent = true;
                continue;
            }
            Operand value = argumentOf(argument, origin);
            if (value == null) {
                return null;
            }
            // 定数は渡す先の領域を持たない。写しを渡すほかない
            boolean copied = byContent || value instanceof Operand.Literal;
            arguments.add(new Statement.Call.Argument(value, copied));
        }

        Statement.Overflow exception = exceptionOf(context.callExceptionPhrases());
        return new Statement.Call(target, arguments, exception, origin);
    }

    private Operand argumentOf(CobolParser.CallArgumentContext context, Origin origin) {
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

    private Statement cancelOf(CobolParser.CancelStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        List<Operand> targets = new ArrayList<>();
        for (CobolParser.CallTargetContext target : context.callTarget()) {
            Operand resolved = callTargetOf(target, origin);
            if (resolved == null) {
                return null;
            }
            targets.add(resolved);
        }
        return new Statement.Cancel(targets, origin);
    }

    /** 呼び先の名前。文字定数か、実行時に名前が決まるデータ項目である。 */
    private Operand callTargetOf(CobolParser.CallTargetContext context, Origin origin) {
        if (context.literal() != null) {
            try {
                LiteralValue value = LiteralValue.of(context.literal());
                if (!(value instanceof LiteralValue.Text)) {
                    report(origin, "a program name must be an alphanumeric literal");
                    return null;
                }
                return new Operand.Literal(value);
            } catch (RuntimeException e) {
                report(origin, "invalid literal: " + context.literal().getText());
                return null;
            }
        }
        DataReference reference = resolver.resolve(context.identifier());
        return reference == null ? null : new Operand.Reference(reference);
    }

    /** {@code ON EXCEPTION} と {@code NOT ON EXCEPTION} の文。 */
    private Statement.Overflow exceptionOf(CobolParser.CallExceptionPhrasesContext phrases) {
        if (phrases == null
                || (phrases.onExceptionPhrase() == null
                        && phrases.notOnExceptionPhrase() == null)) {
            return null;
        }
        List<Statement> onException = phrases.onExceptionPhrase() == null
                ? List.of()
                : listOf(phrases.onExceptionPhrase().statement());
        List<Statement> otherwise = phrases.notOnExceptionPhrase() == null
                ? List.of()
                : listOf(phrases.notOnExceptionPhrase().statement());
        return new Statement.Overflow(onException, otherwise);
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
            bodies.add(bodyOf(branch.branchBody()));
        }

        // WHEN OTHER の文は、いちばん外側の ELSE になる
        List<Statement> otherwise = context.OTHER() == null
                ? List.of()
                : bodyOf(context.branchBody());

        Statement result = null;
        for (int i = conditions.size() - 1; i >= 0; i--) {
            List<Statement> elseBranch = result == null ? otherwise : List.of(result);
            result = new Statement.If(conditions.get(i), bodies.get(i), elseBranch, origin);
        }
        return result;
    }

    /** 枝の中身。{@code NEXT SENTENCE} は「この文の残りを飛ばす」ことである。 */
    private List<Statement> bodyOf(CobolParser.BranchBodyContext context) {
        return context.NEXT() != null
                ? List.of(new Statement.NextSentence(ReferenceResolver.originOf(context)))
                : listOf(context.statement());
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
        return Condition.Relation.of(zero, Condition.Comparison.EQUAL, zero, true, origin);
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
        Operand operand = operandOf(subject, origin);
        Expression left = operand == null ? null : new Expression.Value(operand);
        List<Expression> values = valuesOf(object, origin);
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
    private List<Expression> valuesOf(CobolParser.EvaluateObjectContext object, Origin origin) {
        if (!object.expression().isEmpty()) {
            List<Expression> values = new ArrayList<>();
            for (CobolParser.ExpressionContext value : object.expression()) {
                values.add(expressionOf(value, origin));
            }
            return values;
        }
        CobolParser.IdentifierContext name = soleNameOf(object.condition());
        if (name != null) {
            DataReference reference = resolver.resolve(name);
            return reference == null
                    ? null
                    : List.of(new Expression.Value(new Operand.Reference(reference)));
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
        Expression left = expressionOf(context.expression(0), origin);
        Expression right = expressionOf(context.expression(1), origin);
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
        return Condition.Relation.of(left, comparison, right, numeric, origin);
    }

    /**
     * 式どうしの関係条件を組み立てる。
     *
     * <p>式が被演算子 1 個でなければ<b>必ず数値</b>である。四則の相手は数値しかない。
     */
    private Condition relation(Expression left, Condition.Comparison comparison, Expression right,
                               Origin origin) {
        boolean numeric = isNumeric(left, true) && isNumeric(right, true);
        return new Condition.Relation(left, comparison, right, numeric, origin);
    }

    /** 式が数値として扱われるか。 */
    private static boolean isNumeric(Expression expression, boolean literalDefault) {
        Operand operand = Condition.Relation.operandOf(expression);
        return operand == null || isNumeric(operand, literalDefault);
    }

    /** 被演算子が数値として扱われるか。定数は受取側に合わせるので、既定の見方を渡す。 */
    private static boolean isNumeric(Operand operand, boolean literalDefault) {
        if (operand instanceof Operand.Reference reference) {
            return DataCategory.of(reference.reference()).isNumeric();
        }
        if (operand instanceof Operand.Function function) {
            return function.returns().isNumeric();
        }
        LiteralValue value = ((Operand.Literal) operand).value();
        return DataCategory.of(value, literalDefault).isNumeric();
    }

    /**
     * 組み込み関数の呼び出しを組み立てる (要件 FR-070)。
     *
     * <p>知らない関数は<b>断る</b>。近い値を黙って返すより、書けないと言うほうがよい。
     * 三角関数や対数がここに無いのは、結果の桁数が処理系の決めごとであり、
     * その仕様をまだ持っていないからである (暫定判断 P-065)。
     */
    private Operand functionOf(CobolParser.FunctionCallContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        String spelling = context.functionName().getText().toUpperCase(Locale.ROOT);
        Intrinsic intrinsic = Intrinsic.of(spelling);
        if (intrinsic == null) {
            report(origin, "FUNCTION " + spelling + " is not supported yet");
            return null;
        }
        List<Expression> arguments = new ArrayList<>();
        for (CobolParser.ExpressionContext argument : context.expression()) {
            List<Expression> expanded = argumentsOf(argument, origin);
            if (expanded == null) {
                return null;
            }
            arguments.addAll(expanded);
        }
        if (!intrinsic.accepts(arguments.size())) {
            report(origin, "FUNCTION " + intrinsic.spelling() + " takes " + intrinsic.arity()
                    + " but " + arguments.size() + " were given");
            return null;
        }
        Intrinsic.Result returns = intrinsic.returns();
        if (intrinsic.takes() == Intrinsic.Argument.EITHER) {
            Boolean text = takesText(arguments, origin);
            if (text == null) {
                return null;
            }
            returns = text ? intrinsic.textResult() : intrinsic.returns();
        }
        boolean numericArguments = intrinsic.takes() == Intrinsic.Argument.NUMERIC
                || (intrinsic.takes() == Intrinsic.Argument.EITHER
                        && returns == intrinsic.returns());
        if (!numericArguments && !plainOperands(arguments)) {
            // 文字を受け取る関数の引数は項目か定数である。式を書いても足す先が無い
            report(origin, "FUNCTION " + intrinsic.spelling()
                    + " takes an item or a literal, not an arithmetic expression");
            return null;
        }
        return new Operand.Function(intrinsic, arguments, returns, origin);
    }

    /**
     * {@code MAX} や {@code MIN} の引数を<b>文字として</b>比べるかどうか。
     *
     * <p>規格は引数の種別をそろえることを求めている。混ぜて書かれたら断る。
     *
     * @return 文字なら {@code true}、数値なら {@code false}。混ざっていれば {@code null}
     */
    private Boolean takesText(List<Expression> arguments, Origin origin) {
        boolean text = false;
        boolean numeric = false;
        for (Expression argument : arguments) {
            if (!(argument instanceof Expression.Value value)) {
                numeric = true;
                continue;
            }
            if (isNumeric(value.operand(), true)) {
                numeric = true;
            } else {
                text = true;
            }
        }
        if (text && numeric) {
            report(origin, "the arguments of MAX, MIN, ORD-MAX and ORD-MIN must all be"
                    + " numeric or all be alphanumeric");
            return null;
        }
        return text;
    }

    /**
     * 組み込み関数の引数 1 つを組み立てる。
     *
     * <p>{@code ALL} と書いた添字があれば、<b>反復の数だけ引数へ展開する</b>。
     * {@code FUNCTION MAX(IND(ALL))} は {@code FUNCTION MAX(IND(1) … IND(5))} と同じで
     * ある。次元が 2 つ以上あれば、その組み合わせすべてになる。
     *
     * @return 展開した引数。読めなければ {@code null}
     */
    private List<Expression> argumentsOf(CobolParser.ExpressionContext context, Origin origin) {
        CobolParser.IdentifierContext identifier = allSubscriptedIdentifier(context);
        if (identifier == null) {
            Expression built = expressionOf(context, origin);
            return built == null ? null : List.of(built);
        }
        DataReference reference = resolver.resolve(identifier, true);
        if (reference == null) {
            return null;
        }
        return expandAll(reference, origin);
    }

    /**
     * {@code ALL} と書いた添字を持つ一意名 1 個だけの式か。
     *
     * @return そうでなければ {@code null}
     */
    private static CobolParser.IdentifierContext allSubscriptedIdentifier(
            CobolParser.ExpressionContext context) {
        if (!(context instanceof CobolParser.OperandExpressionContext operand)
                || operand.arithmeticOperand().identifier() == null) {
            return null;
        }
        CobolParser.IdentifierContext identifier = operand.arithmeticOperand().identifier();
        if (identifier.subscripts() == null) {
            return null;
        }
        for (CobolParser.SubscriptContext subscript : identifier.subscripts().subscript()) {
            if (subscript.ALL() != null) {
                return identifier;
            }
        }
        return null;
    }

    /**
     * {@code ALL} を反復の数だけ広げる。
     *
     * <p>反復の数が実行時に決まる表 ({@code OCCURS ... DEPENDING ON}) は広げられない。
     * 引数の数が翻訳時に決まらないためである。
     *
     * @return 広げた引数。広げられなければ {@code null}
     */
    private List<Expression> expandAll(DataReference reference, Origin origin) {
        List<DataItem> tables = DataReference.tableChain(reference.item());
        List<List<DataReference.Subscript>> rows = new ArrayList<>();
        rows.add(new ArrayList<>());
        for (int i = 0; i < tables.size(); i++) {
            DataReference.Subscript subscript = reference.subscripts().get(i);
            List<DataReference.Subscript> choices = new ArrayList<>();
            if (subscript instanceof DataReference.Subscript.All) {
                // OCCURS ... DEPENDING ON の表では、規格が言う「すべての反復」は
                // 実行時の個数である。こちらは宣言した最大で広げる (暫定判断 P-068)
                for (int n = 1; n <= tables.get(i).occurs(); n++) {
                    choices.add(new DataReference.Subscript.Constant(n));
                }
            } else {
                choices.add(subscript);
            }
            List<List<DataReference.Subscript>> grown = new ArrayList<>();
            for (List<DataReference.Subscript> row : rows) {
                for (DataReference.Subscript choice : choices) {
                    List<DataReference.Subscript> next = new ArrayList<>(row);
                    next.add(choice);
                    grown.add(next);
                }
            }
            rows = grown;
        }
        List<Expression> arguments = new ArrayList<>();
        for (List<DataReference.Subscript> row : rows) {
            arguments.add(new Expression.Value(new Operand.Reference(new DataReference(
                    reference.item(), row, reference.refMod(), origin))));
        }
        return arguments;
    }

    private static boolean plainOperands(List<Expression> arguments) {
        for (Expression argument : arguments) {
            if (!(argument instanceof Expression.Value)) {
                return false;
            }
        }
        return true;
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
        return Condition.Relation.of(operand, comparison, zero, true, origin);
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
                    // 添字は条件名のほうに書かれる。親が表なら、それを親への参照へ移す
                    DataReference parent = resolver.resolveAs(item, context.identifier());
                    return parent == null
                            ? null
                            : conditionNameCondition(parent, conditionName, origin);
                }
            }
        }
        report(origin, "undefined condition-name: " + name);
        return null;
    }

    private Condition conditionNameCondition(DataReference reference,
                                             DataItem.ConditionName conditionName,
                                             Origin origin) {
        Operand subject = new Operand.Reference(reference);
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
        if (context.CORRESPONDING() != null || context.CORR() != null) {
            return correspondingArithmeticOf(Statement.Arithmetic.Operator.ADD,
                    context.identifier(), context.roundedTarget(0),
                    context.sizeErrorPhrases(), origin);
        }
        List<Operand> operands = operandsOf(context.arithmeticOperand(), origin);
        if (context.GIVING() == null) {
            if (context.roundedOperand().isEmpty()) {
                report(origin, "ADD without GIVING requires TO");
                return null;
            }
            return arithmetic(Statement.Arithmetic.Operator.ADD, operands,
                    Statement.Arithmetic.Operator.ADD,
                    targetsOf(context.roundedOperand(), origin), false,
                    context.sizeErrorPhrases(), origin);
        }
        operands.addAll(operandsOf(context.roundedOperand(), origin));
        return arithmetic(Statement.Arithmetic.Operator.ADD, operands, null,
                targetsOf(context.roundedTarget()), true, context.sizeErrorPhrases(), origin);
    }

    /**
     * {@code SUBTRACT}。{@code GIVING} がなければ引かれる側が受取項目になり、
     * あれば <b>引かれる側を先頭に置いて左から引く</b>。
     */
    private Statement subtractOf(CobolParser.SubtractStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        if (context.CORRESPONDING() != null || context.CORR() != null) {
            return correspondingArithmeticOf(Statement.Arithmetic.Operator.SUBTRACT,
                    context.identifier(), context.roundedTarget(0),
                    context.sizeErrorPhrases(), origin);
        }
        List<Operand> subtrahends = operandsOf(context.arithmeticOperand(), origin);
        if (context.GIVING() == null) {
            // 引く側をまず足し合わせ、その和を受取項目から引く
            return arithmetic(Statement.Arithmetic.Operator.ADD, subtrahends,
                    Statement.Arithmetic.Operator.SUBTRACT,
                    targetsOf(context.roundedOperand(), origin), false,
                    context.sizeErrorPhrases(), origin);
        }
        List<Operand> operands = operandsOf(context.roundedOperand(), origin);
        operands.addAll(subtrahends);
        return arithmetic(Statement.Arithmetic.Operator.SUBTRACT, operands, null,
                targetsOf(context.roundedTarget()), true, context.sizeErrorPhrases(), origin);
    }

    private Statement multiplyOf(CobolParser.MultiplyStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        List<Operand> multiplier = operandsOf(List.of(context.arithmeticOperand()), origin);
        if (context.GIVING() == null) {
            return arithmetic(Statement.Arithmetic.Operator.MULTIPLY, multiplier,
                    Statement.Arithmetic.Operator.MULTIPLY,
                    targetsOf(context.roundedOperand(), origin), false,
                    context.sizeErrorPhrases(), origin);
        }
        List<Operand> operands = new ArrayList<>(multiplier);
        operands.addAll(operandsOf(context.roundedOperand(), origin));
        return arithmetic(Statement.Arithmetic.Operator.MULTIPLY, operands, null,
                targetsOf(context.roundedTarget()), true, context.sizeErrorPhrases(), origin);
    }

    /**
     * {@code DIVIDE}。{@code INTO} と {@code BY} で割る側と割られる側が入れ替わる。
     */
    private Statement divideOf(CobolParser.DivideStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        if (context.REMAINDER() != null) {
            return divideRemainderOf(context, origin);
        }
        List<Operand> first = operandsOf(List.of(context.arithmeticOperand(0)), origin);
        boolean into = context.INTO() != null;
        if (context.GIVING() == null) {
            if (!into) {
                report(origin, "DIVIDE ... BY requires GIVING");
                return null;
            }
            return arithmetic(Statement.Arithmetic.Operator.DIVIDE, first,
                    Statement.Arithmetic.Operator.DIVIDE,
                    targetsOf(context.roundedOperand(), origin), false,
                    context.sizeErrorPhrases(), origin);
        }
        List<Operand> second = operandsOf(context.roundedOperand(), origin);
        List<Operand> operands = new ArrayList<>();
        // INTO は「割られる側があとに書かれる」ので、畳む順に入れ替える
        operands.addAll(into ? second : first);
        operands.addAll(into ? first : second);
        return arithmetic(Statement.Arithmetic.Operator.DIVIDE, operands, null,
                targetsOf(context.roundedTarget()), true, context.sizeErrorPhrases(), origin);
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
        if (!checkReceivers(targets, true, origin)) {
            return null;
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

    /**
     * {@code ADD CORRESPONDING} と {@code SUBTRACT CORRESPONDING}。
     *
     * <p>名前の合う組ごとに {@code 受取項目 = 受取項目 演算 送出項目} を行う。
     * <b>数値の基本項目どうしの組だけ</b>が対象である。ほかの組は計算しようがないので
     * 選ばない。
     *
     * <p>{@code ON SIZE ERROR} は組ごとではなく<b>全体で 1 つ</b>である。
     * その関係を {@link Statement.ArithmeticGroup} で表す。
     */
    private Statement correspondingArithmeticOf(Statement.Arithmetic.Operator operator,
                                                CobolParser.IdentifierContext sourceContext,
                                                CobolParser.RoundedTargetContext targetContext,
                                                CobolParser.SizeErrorPhrasesContext phrases,
                                                Origin origin) {
        DataReference source = resolver.resolve(sourceContext);
        DataReference target = resolver.resolve(targetContext.identifier());
        if (source == null || target == null) {
            return null;
        }
        if (!checkCorrespondingOperand(source, origin)
                || !checkCorrespondingOperand(target, origin)) {
            return null;
        }
        boolean rounded = targetContext.ROUNDED() != null;

        List<Statement.Arithmetic> operations = new ArrayList<>();
        for (Correspondence.Pair pair : Correspondence.of(source.item(), target.item())) {
            DataReference from =
                    new DataReference(pair.source(), source.subscripts(), null, origin);
            DataReference to =
                    new DataReference(pair.target(), target.subscripts(), null, origin);
            if (!isNumericElementary(from) || !isNumericElementary(to)) {
                continue;
            }
            // 被演算子が 1 個なので畳み方は効かない。受取項目を巻き込む演算だけが意味を持つ
            operations.add(new Statement.Arithmetic(Statement.Arithmetic.Operator.ADD,
                    List.of(new Operand.Reference(from)), operator,
                    List.of(new Statement.Arithmetic.Target(to, rounded)), null, origin));
        }
        if (operations.isEmpty()) {
            report(origin, "CORRESPONDING found no numeric elementary pairs between "
                    + describe(source) + " and " + describe(target));
            return null;
        }
        return new Statement.ArithmeticGroup(operations, sizeErrorOf(phrases), origin);
    }

    private static boolean isNumericElementary(DataReference reference) {
        return reference.item().isElementary() && DataCategory.of(reference).isNumeric();
    }

    /**
     * {@code DIVIDE ... REMAINDER}。
     *
     * <p>{@code INTO} と {@code BY} で割る側と割られる側が入れ替わるのはほかの形と同じである。
     */
    private Statement divideRemainderOf(CobolParser.DivideStatementContext context,
                                        Origin origin) {
        Operand first = operandOf(context.arithmeticOperand(0), origin);
        Operand second = operandOf(context.arithmeticOperand(1), origin);
        Statement.Arithmetic.Target quotient = targetOf(context.roundedTarget(0));
        Statement.Arithmetic.Target remainder = targetOf(context.roundedTarget(1));
        if (first == null || second == null || quotient == null || remainder == null) {
            return null;
        }
        // 商も剰余も GIVING の右である。どちらも数字編集項目でよい
        if (!checkReceivers(List.of(quotient, remainder), true, origin)) {
            return null;
        }
        boolean into = context.INTO() != null;
        return new Statement.DivideRemainder(into ? second : first, into ? first : second,
                quotient, remainder, sizeErrorOf(context.sizeErrorPhrases()), origin);
    }

    private Statement.Arithmetic.Target targetOf(CobolParser.RoundedTargetContext context) {
        DataReference reference = resolver.resolve(context.identifier());
        return reference == null
                ? null
                : new Statement.Arithmetic.Target(reference, context.ROUNDED() != null);
    }

    private Statement arithmetic(Statement.Arithmetic.Operator fold, List<Operand> operands,
                                 Statement.Arithmetic.Operator accumulate,
                                 List<Statement.Arithmetic.Target> targets, boolean giving,
                                 CobolParser.SizeErrorPhrasesContext phrases, Origin origin) {
        if (operands.contains(null) || targets.contains(null) || targets.isEmpty()) {
            // 解決できなかった参照は報告済みである
            return null;
        }
        if (!checkReceivers(targets, giving, origin)) {
            return null;
        }
        return new Statement.Arithmetic(fold, operands, accumulate, targets,
                sizeErrorOf(phrases), origin);
    }

    /**
     * 算術文の受取項目が受け取れる形かどうか (要件 FR-041)。
     *
     * <p>{@code GIVING} の右に書かれた受取項目は<b>数字編集項目でもよい</b>。
     * {@code DIVIDE ... GIVING 編集項目 REMAINDER 編集項目} も書ける。
     * {@code GIVING} を書かない形の受取項目は<b>計算に加わる</b>ので、数値でなければならない。
     * 編集した文字列を読み戻して足すことはできない。
     */
    private boolean checkReceivers(List<Statement.Arithmetic.Target> targets, boolean giving,
                                   Origin origin) {
        for (Statement.Arithmetic.Target target : targets) {
            if (!receives(target.reference(), giving)) {
                report(origin, giving
                        ? "an arithmetic statement requires a numeric or numeric-edited receiver: "
                                + describe(target.reference())
                        : "an arithmetic statement requires a numeric receiver: "
                                + describe(target.reference()));
                return false;
            }
        }
        return true;
    }

    private static boolean receives(DataReference reference, boolean giving) {
        DataCategory category = DataCategory.of(reference);
        return category.isNumeric() || (giving && category == DataCategory.NUMERIC_EDITED);
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
        if (context.functionCall() != null) {
            return functionOf(context.functionCall());
        }
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
        if (context.CORRESPONDING() != null || context.CORR() != null) {
            return correspondingMoveOf(context, origin);
        }
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
        return new Statement.Move(source, targets, false, origin);
    }

    /**
     * {@code MOVE CORRESPONDING} を、名前の合う組の数だけの {@code MOVE} へ展開する。
     *
     * <p>展開をここで済ませておけば、コード生成は普通の {@code MOVE} を出すだけでよい。
     * 分類の組み合わせの検査も 1 組ずつ同じ経路を通る。
     */
    private Statement correspondingMoveOf(CobolParser.MoveStatementContext context,
                                          Origin origin) {
        DataReference source = correspondingOperand(context.moveSource(), origin);
        if (source == null) {
            return null;
        }
        List<Statement> moves = new ArrayList<>();
        for (CobolParser.IdentifierContext identifier : context.identifier()) {
            DataReference target = resolver.resolve(identifier);
            if (target == null) {
                return null;
            }
            if (!checkCorrespondingOperand(target, origin)) {
                return null;
            }
            List<Statement> expanded = correspondingMoves(source, target, origin);
            if (expanded == null) {
                return null;
            }
            moves.addAll(expanded);
        }
        return new Statement.Sequence(moves, origin);
    }

    /** 送出側は集団項目でなければならない。定数は書けない。 */
    private DataReference correspondingOperand(CobolParser.MoveSourceContext context,
                                               Origin origin) {
        if (context.identifier() == null) {
            report(origin, "MOVE CORRESPONDING requires a group item, not a literal");
            return null;
        }
        DataReference reference = resolver.resolve(context.identifier());
        if (reference == null) {
            return null;
        }
        return checkCorrespondingOperand(reference, origin) ? reference : null;
    }

    private boolean checkCorrespondingOperand(DataReference reference, Origin origin) {
        if (reference.item().isElementary()) {
            report(origin, "MOVE CORRESPONDING requires a group item: " + describe(reference));
            return false;
        }
        if (reference.refMod() != null) {
            report(origin, "MOVE CORRESPONDING cannot take a reference modification: "
                    + describe(reference));
            return false;
        }
        return true;
    }

    /**
     * 対応する組ごとの {@code MOVE}。
     *
     * <p>添字は<b>集団項目に書かれたものをそのまま引き継ぐ</b>。対応付けの対象から
     * {@code OCCURS} の項目を外してあるので、組になった項目の表の連なりは
     * 集団項目のものと同じである。
     */
    private List<Statement> correspondingMoves(DataReference source, DataReference target,
                                               Origin origin) {
        List<Correspondence.Pair> pairs =
                Correspondence.of(source.item(), target.item());
        if (pairs.isEmpty()) {
            // 何も移さない MOVE は書き間違いである。黙って通さない
            report(origin, "MOVE CORRESPONDING found no corresponding items between "
                    + describe(source) + " and " + describe(target));
            return null;
        }
        List<Statement> moves = new ArrayList<>();
        for (Correspondence.Pair pair : pairs) {
            DataReference from =
                    new DataReference(pair.source(), source.subscripts(), null, origin);
            DataReference to =
                    new DataReference(pair.target(), target.subscripts(), null, origin);
            Operand operand = new Operand.Reference(from);
            Statement.Move.Target checked = checkMove(operand, to, origin);
            if (checked == null) {
                return null;
            }
            moves.add(new Statement.Move(operand, List.of(checked), false, origin));
        }
        return moves;
    }

    /** 分類の組み合わせを検査し、転記の種類を決める。 */
    private Statement.Move.Target checkMove(Operand source, DataReference target, Origin origin) {
        return checkMove(source, target, origin, false);
    }

    /**
     * 分類の組み合わせを検査し、転記の種類を決める。
     *
     * @param allowIndex 受取側が指標名でもよいか。{@code SET 指標名 TO n} だけが許す
     */
    private Statement.Move.Target checkMove(Operand source, DataReference target, Origin origin,
                                            boolean allowIndex) {
        if (target.item().isIndex() && !allowIndex) {
            // 指標名はデータ項目ではない。書き込めるのは SET だけである
            report(origin, "an index name cannot receive a MOVE: " + describe(target));
            return null;
        }
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
        if (source instanceof Operand.Function function) {
            // 関数の値は「数値」か「英数字」のどちらかである。編集はしない
            return switch (function.returns()) {
                case INTEGER -> DataCategory.NUMERIC_INTEGER;
                case NUMERIC -> DataCategory.NUMERIC_NONINTEGER;
                case SAME_LENGTH, ONE_CHARACTER, TIMESTAMP, WIDEST ->
                        DataCategory.ALPHANUMERIC;
            };
        }
        boolean numericReceiver = receiver.isNumeric() || receiver == DataCategory.NUMERIC_EDITED;
        return DataCategory.of(((Operand.Literal) source).value(), numericReceiver);
    }

    private static String describe(DataReference reference) {
        return reference.item().name() == null ? "FILLER" : reference.item().name();
    }

    private Operand operandOf(CobolParser.MoveSourceContext context, Origin origin) {
        if (context.functionCall() != null) {
            return functionOf(context.functionCall());
        }
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

    // ---- 入出力文 ----

    /**
     * {@code OPEN} を組み立てる (要件 FR-102)。
     *
     * <p>1 つの文で開き方の違うファイルを並べられる。{@code OPEN INPUT A OUTPUT B} は
     * 2 つの独立した開き方であり、まとめて 1 つの状態にはならない。
     */
    private Statement openOf(CobolParser.OpenStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        List<Statement.Open.Opened> opened = new ArrayList<>();
        for (CobolParser.OpenPhraseContext phrase : context.openPhrase()) {
            OpenMode mode = modeOf(phrase);
            for (org.antlr.v4.runtime.tree.TerminalNode name : phrase.IDENTIFIER()) {
                FileDescription file = dataFileOf(name.getText(), "OPEN", origin);
                if (file == null) {
                    return null;
                }
                opened.add(new Statement.Open.Opened(file, mode));
            }
        }
        return new Statement.Open(opened, origin);
    }

    private static OpenMode modeOf(CobolParser.OpenPhraseContext phrase) {
        if (phrase.INPUT() != null) {
            return OpenMode.INPUT;
        }
        if (phrase.OUTPUT() != null) {
            return OpenMode.OUTPUT;
        }
        return phrase.I_O() != null ? OpenMode.IO : OpenMode.EXTEND;
    }

    private Statement closeOf(CobolParser.CloseStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        List<FileDescription> closed = new ArrayList<>();
        for (org.antlr.v4.runtime.tree.TerminalNode name : context.IDENTIFIER()) {
            FileDescription file = dataFileOf(name.getText(), "CLOSE", origin);
            if (file == null) {
                return null;
            }
            closed.add(file);
        }
        return new Statement.Close(closed, origin);
    }

    /**
     * {@code READ} を組み立てる (要件 FR-102, FR-103)。
     *
     * <p>{@code INTO} はレコード領域からの転記に展開する。読み込みそのものは領域までで、
     * そこから先は普通の {@code MOVE} と変わらない。
     */
    private Statement readOf(CobolParser.ReadStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        FileDescription file = dataFileOf(context.IDENTIFIER().getText(), "READ", origin);
        if (file == null) {
            return null;
        }
        Statement.Move into = null;
        if (context.into != null) {
            into = areaMove(file, context.into, origin);
            if (into == null) {
                return null;
            }
        }
        boolean next = context.NEXT() != null;
        if (next && file.access() != FileDescription.Access.DYNAMIC) {
            // NEXT と書けるのは動的アクセスだけである。ほかの様式では意味が決まっている
            report(origin, "READ ... NEXT requires ACCESS MODE IS DYNAMIC");
            return null;
        }
        List<Statement> atEnd = context.atEndPhrase() == null
                ? List.of()
                : bodyOf(context.atEndPhrase().branchBody());
        List<Statement> notAtEnd = context.notAtEndPhrase() == null
                ? List.of()
                : listOf(context.notAtEndPhrase().statement());
        int keyIndex = 0;
        if (context.key != null) {
            if (file.organization() != Organization.INDEXED) {
                report(origin, "READ ... KEY requires ORGANIZATION IS INDEXED: " + file.name());
                return null;
            }
            keyIndex = keyIndexOf(file, context.key, origin);
            if (keyIndex < 0) {
                return null;
            }
        }
        Statement.KeyCheck keyCheck = keyCheckOf(context.invalidKeyPhrase(),
                context.notInvalidKeyPhrase(), file, readsByKey(file, next), origin);
        if (keyCheck == null && context.invalidKeyPhrase() != null) {
            return null;
        }
        // listOf は組み立てられなかった文を落とす。誤りは診断として残っている
        return new Statement.Read(file, next, keyIndex, into, atEnd, notAtEnd, keyCheck, origin);
    }

    /**
     * 書かれた項目が、そのファイルの何番目の鍵か (要件 FR-100)。
     *
     * <p>{@code 0} が主鍵、{@code 1} 以降が {@code ALTERNATE RECORD KEY} の書かれた順である。
     *
     * @return 鍵でなければ {@code -1}
     */
    private int keyIndexOf(FileDescription file, CobolParser.IdentifierContext context,
                           Origin origin) {
        DataReference reference = resolver.resolve(context);
        if (reference == null) {
            return -1;
        }
        List<FileDescription.RecordKey> keys = file.keys();
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).reference().item() == reference.item()) {
                return i;
            }
        }
        report(origin, "not a RECORD KEY or ALTERNATE RECORD KEY of " + file.name() + ": "
                + reference.item().name());
        return -1;
    }

    /** その {@code READ} が鍵で引く形かどうか。動的アクセスでは {@code NEXT} の有無で決まる。 */
    private static boolean readsByKey(FileDescription file, boolean next) {
        return switch (file.access()) {
            case SEQUENTIAL -> false;
            case RANDOM -> true;
            case DYNAMIC -> !next;
        };
    }

    /**
     * {@code WRITE} を組み立てる (要件 FR-102)。
     *
     * <p>書くのに指定するのは<b>レコード名</b>である。どのファイルへ書くのかは、
     * そのレコードがどの {@code FD} の下にあるかで決まる。
     */
    private Statement writeOf(CobolParser.WriteStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        DataItem record = recordOf(context.IDENTIFIER().getText(), "WRITE", origin);
        if (record == null) {
            return null;
        }
        Statement.Move from = null;
        if (context.identifier() != null) {
            from = recordMove(record, context.identifier(), origin);
            if (from == null) {
                return null;
            }
        }
        FileDescription file = files.get(record.fileName());
        if (file.sort()) {
            report(origin, "WRITE cannot be used on a sort-merge file (SD); use RELEASE: "
                    + file.name());
            return null;
        }
        Statement.KeyCheck keyCheck = keyCheckOf(context.invalidKeyPhrase(),
                context.notInvalidKeyPhrase(), file, file.isKeyed(), origin);
        if (keyCheck == null && context.invalidKeyPhrase() != null) {
            return null;
        }
        Statement.Advancing advancing = advancingOf(context.advancingPhrase(), origin);
        if (context.advancingPhrase() != null && advancing == null) {
            return null;
        }
        return new Statement.Write(file, record, from, keyCheck, advancing, origin);
    }

    /**
     * {@code WRITE} の行送りを読む (要件 FR-102)。
     *
     * <p>{@code AFTER} は送ってから書き、{@code BEFORE} は書いてから送る。送る量は
     * 書かれた数か、実行時に決まるデータ項目である。
     *
     * @return 書かれていなければ {@code null}。読めなければ診断を残して {@code null}
     */
    private Statement.Advancing advancingOf(CobolParser.AdvancingPhraseContext context,
                                            Origin origin) {
        if (context == null) {
            return null;
        }
        boolean before = context.BEFORE() != null;
        if (context.PAGE() != null) {
            return new Statement.Advancing(null, null, true, before);
        }
        CobolParser.AdvancingLinesContext lines = context.advancingLines();
        if (lines.identifier() != null) {
            DataReference count = resolver.resolve(lines.identifier());
            return count == null ? null : new Statement.Advancing(null, count, false, before);
        }
        if (lines.NUMBER() == null) {
            // ZERO と綴られていれば 0 行である
            return new Statement.Advancing(0, null, false, before);
        }
        String written = lines.NUMBER().getText();
        try {
            return new Statement.Advancing(Integer.parseInt(written), null, false, before);
        } catch (NumberFormatException e) {
            report(origin, "ADVANCING requires an integer number of lines: " + written);
            return null;
        }
    }

    /**
     * {@code REWRITE} を組み立てる (要件 FR-102)。
     *
     * <p>書き換える相手は文に書かれていない。直前に読んだレコードであり、それを覚えているのは
     * 開いているファイルのほうである。したがってここで作るのは {@code WRITE} と同じ形になる。
     */
    private Statement rewriteOf(CobolParser.RewriteStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        DataItem record = recordOf(context.IDENTIFIER().getText(), "REWRITE", origin);
        if (record == null) {
            return null;
        }
        Statement.Move from = null;
        if (context.identifier() != null) {
            from = recordMove(record, context.identifier(), origin);
            if (from == null) {
                return null;
            }
        }
        FileDescription file = files.get(record.fileName());
        if (file.sort()) {
            report(origin, "REWRITE cannot be used on a sort-merge file (SD): " + file.name());
            return null;
        }
        Statement.KeyCheck keyCheck = keyCheckOf(context.invalidKeyPhrase(),
                context.notInvalidKeyPhrase(), file, file.isKeyed(), origin);
        if (keyCheck == null && context.invalidKeyPhrase() != null) {
            return null;
        }
        return new Statement.Rewrite(file, record, from, keyCheck, origin);
    }

    /**
     * {@code DELETE} を組み立てる (要件 FR-101, FR-102)。
     *
     * <p>消せるのは鍵で引く編成だけである。順編成には「そのレコードだけを消す」場所がない。
     */
    private Statement deleteOf(CobolParser.DeleteStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        FileDescription file = dataFileOf(context.IDENTIFIER().getText(), "DELETE", origin);
        if (file == null) {
            return null;
        }
        if (!file.isKeyed()) {
            report(origin, "DELETE requires a RELATIVE or INDEXED file: " + file.name());
            return null;
        }
        Statement.KeyCheck keyCheck = keyCheckOf(context.invalidKeyPhrase(),
                context.notInvalidKeyPhrase(), file, true, origin);
        if (keyCheck == null && context.invalidKeyPhrase() != null) {
            return null;
        }
        return new Statement.Delete(file, keyCheck, origin);
    }

    /**
     * {@code START} を組み立てる (要件 FR-101)。
     *
     * <p>鍵を省略すれば、そのファイルの鍵そのものと等しいレコードを探す。
     */
    private Statement startOf(CobolParser.StartStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        FileDescription file = dataFileOf(context.IDENTIFIER().getText(), "START", origin);
        if (file == null) {
            return null;
        }
        if (!file.isKeyed()) {
            report(origin, "START requires a RELATIVE or INDEXED file: " + file.name());
            return null;
        }
        int keyIndex = 0;
        DataReference key = file.organization() == Organization.INDEXED
                ? file.keys().get(0).reference()
                : file.relativeKey();
        if (context.identifier() != null) {
            if (file.organization() == Organization.INDEXED) {
                keyIndex = keyIndexOf(file, context.identifier(), origin);
                if (keyIndex < 0) {
                    return null;
                }
                key = file.keys().get(keyIndex).reference();
            } else {
                key = resolver.resolve(context.identifier());
            }
        }
        if (key == null) {
            report(origin, "START needs a key; declare RELATIVE KEY or name one: " + file.name());
            return null;
        }
        KeyRelation relation = KeyRelation.EQUAL;
        if (context.relationalOperator() != null) {
            relation = relationOf(comparisonOf(context.relationalOperator()), origin);
            if (relation == null) {
                return null;
            }
        }
        Statement.KeyCheck keyCheck = keyCheckOf(context.invalidKeyPhrase(),
                context.notInvalidKeyPhrase(), file, true, origin);
        if (keyCheck == null && context.invalidKeyPhrase() != null) {
            return null;
        }
        return new Statement.Start(file, keyIndex, key, relation, keyCheck, origin);
    }

    /** {@code KEY IS} の関係。等しくないものは探せない。範囲の端が決まらないからである。 */
    private KeyRelation relationOf(Condition.Comparison comparison, Origin origin) {
        if (comparison == null) {
            report(origin, "START does not understand this KEY relation");
            return null;
        }
        return switch (comparison) {
            case EQUAL -> KeyRelation.EQUAL;
            case GREATER -> KeyRelation.GREATER;
            case GREATER_OR_EQUAL -> KeyRelation.NOT_LESS;
            case LESS -> KeyRelation.LESS;
            case LESS_OR_EQUAL -> KeyRelation.NOT_GREATER;
            case NOT_EQUAL -> {
                report(origin, "START KEY IS NOT EQUAL does not name a position");
                yield null;
            }
        };
    }

    /**
     * {@code INVALID KEY} と {@code NOT INVALID KEY} (要件 FR-103)。
     *
     * @param keyed その文が鍵で引く形かどうか。そうでなければ書けない
     */
    private Statement.KeyCheck keyCheckOf(CobolParser.InvalidKeyPhraseContext onInvalid,
                                          CobolParser.NotInvalidKeyPhraseContext otherwise,
                                          FileDescription file, boolean keyed, Origin origin) {
        if (onInvalid == null && otherwise == null) {
            return null;
        }
        if (!keyed) {
            // 鍵で引かない文に INVALID KEY を書いても、通ることがない
            report(origin, "INVALID KEY is not allowed here; " + file.name()
                    + " is not accessed by a key");
            return null;
        }
        return new Statement.KeyCheck(
                onInvalid == null ? List.of() : listOf(onInvalid.statement()),
                otherwise == null ? List.of() : listOf(otherwise.statement()));
    }

    /** レコード名から、その {@code FD} 配下のレコード記述を引く。 */
    private DataItem recordOf(String text, String verb, Origin origin) {
        String name = text.toUpperCase(Locale.ROOT);
        for (FileDescription candidate : files.values()) {
            for (DataItem one : candidate.records()) {
                if (name.equals(one.name())) {
                    return one;
                }
            }
        }
        report(origin, verb + " names an item that is not a record of any FD: " + name);
        return null;
    }

    /** {@code READ ... INTO} の転記。送り出すのはレコード領域そのものである。 */
    private Statement.Move areaMove(FileDescription file, CobolParser.IdentifierContext target,
                                    Origin origin) {
        DataReference source = new DataReference(file.area(), List.of(), null, origin);
        DataReference into = resolver.resolve(target);
        if (into == null) {
            return null;
        }
        Statement.Move.Target checked =
                checkMove(new Operand.Reference(source), into, origin);
        return checked == null
                ? null
                : new Statement.Move(new Operand.Reference(source), List.of(checked), false, origin);
    }

    /** {@code WRITE ... FROM} の転記。受け取るのはレコード記述そのものである。 */
    private Statement.Move recordMove(DataItem record, CobolParser.IdentifierContext source,
                                      Origin origin) {
        DataReference from = resolver.resolve(source);
        if (from == null) {
            return null;
        }
        DataReference into = new DataReference(record, List.of(), null, origin);
        Statement.Move.Target checked = checkMove(new Operand.Reference(from), into, origin);
        return checked == null
                ? null
                : new Statement.Move(new Operand.Reference(from), List.of(checked), false, origin);
    }

    // ---- 整列と合併 ----

    /** {@code SORT} を組み立てる (要件 FR-120)。 */
    private Statement sortOf(CobolParser.SortStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        FileDescription work = sortWorkOf(context.IDENTIFIER().getText(), origin);
        if (work == null) {
            return null;
        }
        List<Statement.Sort.SortKeySpec> keys = keysOf(context.sortKeyClause(), work, origin);
        if (keys == null) {
            return null;
        }
        List<FileDescription> using = List.of();
        Statement.Sort.Procedure input = null;
        if (context.sortInput().sortUsing() != null) {
            using = inputFilesOf(context.sortInput().sortUsing(), origin);
            if (using == null) {
                return null;
            }
        } else {
            input = procedureOf(context.sortInput().paragraphName());
        }
        return sorted(work, keys, using, input, context.sortOutput(), false, origin);
    }

    /** {@code MERGE} を組み立てる (要件 FR-121)。入口はファイルに限られる。 */
    private Statement mergeOf(CobolParser.MergeStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        FileDescription work = sortWorkOf(context.IDENTIFIER().getText(), origin);
        if (work == null) {
            return null;
        }
        List<Statement.Sort.SortKeySpec> keys = keysOf(context.sortKeyClause(), work, origin);
        List<FileDescription> using = inputFilesOf(context.sortUsing(), origin);
        if (keys == null || using == null) {
            return null;
        }
        if (using.size() < 2) {
            // 合併するものが 1 つなら、合併ではなく整列である
            report(origin, "MERGE needs at least two USING files");
            return null;
        }
        return sorted(work, keys, using, null, context.sortOutput(), true, origin);
    }

    private Statement sorted(FileDescription work, List<Statement.Sort.SortKeySpec> keys,
                             List<FileDescription> using, Statement.Sort.Procedure input,
                             CobolParser.SortOutputContext output, boolean merge, Origin origin) {
        List<FileDescription> giving = List.of();
        Statement.Sort.Procedure procedure = null;
        if (output.GIVING() != null) {
            giving = outputFilesOf(output, origin);
            if (giving == null) {
                return null;
            }
        } else {
            procedure = procedureOf(output.paragraphName());
        }
        return new Statement.Sort(work, keys, using, input, giving, procedure, merge, origin);
    }

    /**
     * 鍵の並び (要件 FR-120)。
     *
     * <p>鍵は<b>整列作業ファイルのレコードの中</b>になければならない。ほかの場所にある項目を
     * 鍵と言われても、並べ替える相手のどこを見ればよいのか決まらない。
     */
    private List<Statement.Sort.SortKeySpec> keysOf(
            List<CobolParser.SortKeyClauseContext> clauses, FileDescription work, Origin origin) {
        List<Statement.Sort.SortKeySpec> keys = new ArrayList<>();
        for (CobolParser.SortKeyClauseContext clause : clauses) {
            boolean ascending = clause.DESCENDING() == null;
            for (CobolParser.IdentifierContext name : clause.identifier()) {
                DataReference key = resolver.resolve(name);
                if (key == null) {
                    return null;
                }
                if (!work.records().contains(key.item().record())) {
                    report(origin, "a sort key must be inside the record of " + work.name()
                            + ": " + key.item().name());
                    return null;
                }
                if (key.constantOffset().isEmpty() || key.constantLength().isEmpty()) {
                    report(origin, "a sort key must have a fixed position and length");
                    return null;
                }
                keys.add(new Statement.Sort.SortKeySpec(key, ascending));
            }
        }
        return keys;
    }

    private List<FileDescription> inputFilesOf(CobolParser.SortUsingContext context,
                                               Origin origin) {
        return dataFilesOf(context.IDENTIFIER(), origin);
    }

    private List<FileDescription> outputFilesOf(CobolParser.SortOutputContext context,
                                                Origin origin) {
        return dataFilesOf(context.IDENTIFIER(), origin);
    }

    /** {@code USING} と {@code GIVING} に並べるのは、整列作業ファイルではない普通のファイルである。 */
    private List<FileDescription> dataFilesOf(
            List<org.antlr.v4.runtime.tree.TerminalNode> names, Origin origin) {
        List<FileDescription> out = new ArrayList<>();
        for (org.antlr.v4.runtime.tree.TerminalNode name : names) {
            FileDescription file = fileOf(name.getText(), origin);
            if (file == null) {
                return null;
            }
            if (file.sort()) {
                report(origin, "USING and GIVING name data files, not the sort work: "
                        + file.name());
                return null;
            }
            out.add(file);
        }
        return out;
    }

    private static Statement.Sort.Procedure procedureOf(
            List<CobolParser.ParagraphNameContext> names) {
        String from = names.get(0).getText().toUpperCase(Locale.ROOT);
        String through = names.size() > 1
                ? names.get(1).getText().toUpperCase(Locale.ROOT)
                : null;
        return new Statement.Sort.Procedure(from, through);
    }

    /** {@code RELEASE} を組み立てる (要件 FR-120)。 */
    private Statement releaseOf(CobolParser.ReleaseStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        DataItem record = recordOf(context.IDENTIFIER().getText(), "RELEASE", origin);
        if (record == null) {
            return null;
        }
        FileDescription work = files.get(record.fileName());
        if (!work.sort()) {
            report(origin, "RELEASE names a record of a sort-merge file (SD): "
                    + record.name());
            return null;
        }
        Statement.Move from = null;
        if (context.identifier() != null) {
            from = recordMove(record, context.identifier(), origin);
            if (from == null) {
                return null;
            }
        }
        return new Statement.Release(work, record, from, origin);
    }

    /** {@code RETURN} を組み立てる (要件 FR-120)。 */
    private Statement returnOf(CobolParser.ReturnStatementContext context) {
        Origin origin = ReferenceResolver.originOf(context);
        FileDescription work = sortWorkOf(context.IDENTIFIER().getText(), origin);
        if (work == null) {
            return null;
        }
        if (context.atEndPhrase() == null) {
            // 整列の出口はいつか尽きる。尽きたときの行き先を書かずに済ませられない
            report(origin, "RETURN requires an AT END phrase");
            return null;
        }
        Statement.Move into = null;
        if (context.identifier() != null) {
            into = areaMove(work, context.identifier(), origin);
            if (into == null) {
                return null;
            }
        }
        return new Statement.Return(work, into, bodyOf(context.atEndPhrase().branchBody()),
                context.notAtEndPhrase() == null
                        ? List.of()
                        : listOf(context.notAtEndPhrase().statement()),
                origin);
    }

    /**
     * 入出力文が名指すファイルを引く。
     *
     * <p>整列作業ファイルは<b>開くことも閉じることもない</b>。データセットではなく作業場所で
     * あり、{@code SORT} の間だけ存在する。
     */
    private FileDescription dataFileOf(String name, String verb, Origin origin) {
        FileDescription file = fileOf(name, origin);
        if (file == null) {
            return null;
        }
        if (file.sort()) {
            report(origin, verb + " cannot be used on a sort-merge file (SD): " + file.name());
            return null;
        }
        return file;
    }

    /** 整列作業ファイルを引く。 */
    private FileDescription sortWorkOf(String name, Origin origin) {
        FileDescription work = fileOf(name, origin);
        if (work == null) {
            return null;
        }
        if (!work.sort()) {
            report(origin, "not a sort-merge file (SD): " + work.name());
            return null;
        }
        return work;
    }

    /** ファイル名を引く。 */
    private FileDescription fileOf(String name, Origin origin) {
        FileDescription file = files.get(name.toUpperCase(Locale.ROOT));
        if (file == null) {
            report(origin, "file is not declared in the FILE-CONTROL paragraph: " + name);
        }
        return file;
    }

    private void report(Origin origin, String message) {
        diagnostics.add(new Diagnostic(origin, message));
    }
}
