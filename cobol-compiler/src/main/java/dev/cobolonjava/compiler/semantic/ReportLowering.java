package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.Origin;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 報告書の 3 つの文を、普通の文の並びへ落とす (要件 FR-214)。
 *
 * <p>報告書作成機能に専用の実行時機構は持たない。要件 C-4 が言う「プリプロセッサ方式」を
 * 意味解析の段でやっている。落とす先は {@code MOVE} と {@code COMPUTE} と {@code IF} と
 * {@code WRITE} だけであり、どれも外の基準で確かめてある。
 *
 * <h2>行を置く場所の決め方</h2>
 * <p>{@code LINE-COUNTER} は<b>いま頁の何行目まで書いたか</b>を持つ。次の行を置く場所は
 * {@code RW-TGT$} に決め、送る行数を {@code RW-ADV$} に入れて {@code WRITE} する。
 *
 * <ul>
 *   <li>{@code LINE n} — 頁の n 行目。すでに n 行目を過ぎていたら頁を改める</li>
 *   <li>{@code LINE PLUS n} — いまの位置から n 行下。本文の集団は
 *       {@code FIRST DETAIL} より上には置かず、{@code LAST DETAIL} を越えるなら頁を改める</li>
 *   <li>{@code LINE NEXT PAGE} — かならず頁を改める</li>
 * </ul>
 *
 * <h2>改頁は行送りの数に負の値で載せる</h2>
 * <p>{@code WRITE ... AFTER ADVANCING n LINES} の {@code n} を負にすると
 * 「改頁してから {@code -n} 行送る」を表す。既存の {@code ADVANCING PAGE} は
 * {@code -1} であり、そのまま「改頁して 1 行目へ」の意味になる。<b>改頁と行送りを
 * 1 回の書き込みで表せる</b>ので、頁の先頭に余計な空行が出ない。
 */
final class ReportLowering {

    private final ReferenceResolver resolver;
    private final List<Diagnostic> diagnostics;

    ReportLowering(ReferenceResolver resolver, List<Diagnostic> diagnostics) {
        this.resolver = resolver;
        this.diagnostics = diagnostics;
    }

    /**
     * {@code INITIATE}。
     *
     * <p>規格は「{@code LINE-COUNTER} を 0 に、{@code PAGE-COUNTER} を 1 にする」と
     * 決めている (RW101A の INIT-TEST-01 / 02 がそこだけを確かめている)。
     */
    Statement initiate(ReportDescription report, Origin origin) {
        ReportDescription.Registers r = report.registers();
        List<Statement> body = new ArrayList<>();
        body.add(store(r.lineCounter(), 0, origin));
        body.add(store(r.pageCounter(), 1, origin));
        body.add(store(r.started(), 0, origin));
        body.add(store(r.eject(), 0, origin));
        return new Statement.Sequence(body, origin);
    }

    /**
     * {@code GENERATE}。
     *
     * <p>最初の 1 回だけ、報告書の見出しと頁の見出しを先に置く。
     */
    Statement generate(ReportDescription report, ReportGroup group,
                       FileDescription file, Origin origin) {
        ReportDescription.Registers r = report.registers();
        List<Statement> first = new ArrayList<>();
        first.add(store(r.started(), 1, origin));
        first.addAll(present(report, ReportGroup.Type.REPORT_HEADING, file, origin));
        first.addAll(present(report, ReportGroup.Type.PAGE_HEADING, file, origin));

        List<Statement> body = new ArrayList<>();
        body.add(new Statement.If(equals(r.started(), 0, origin), first, List.of(), origin));
        body.addAll(presentGroup(report, group, file, true, origin));
        return new Statement.Sequence(body, origin);
    }

    /**
     * {@code TERMINATE}。
     *
     * <p>{@code GENERATE} が一度も動いていなければ、置くものは何も無い。
     */
    Statement terminate(ReportDescription report, FileDescription file, Origin origin) {
        List<Statement> ending = new ArrayList<>();
        ending.addAll(present(report, ReportGroup.Type.PAGE_FOOTING, file, origin));
        ending.addAll(present(report, ReportGroup.Type.REPORT_FOOTING, file, origin));
        ending.add(store(report.registers().started(), 0, origin));
        return new Statement.If(equals(report.registers().started(), 1, origin),
                ending, List.of(), origin);
    }

    // ---- 集団を置く ----

    private List<Statement> present(ReportDescription report, ReportGroup.Type type,
                                    FileDescription file, Origin origin) {
        ReportGroup group = report.groupOfType(type);
        return group == null ? List.of() : presentGroup(report, group, file, false, origin);
    }

    /**
     * 集団 1 個を置く。
     *
     * @param mayBreak 頁を改めてよいか。見出しと脚注は頁に固定されるので改めない
     */
    private List<Statement> presentGroup(ReportDescription report, ReportGroup group,
                                         FileDescription file, boolean mayBreak, Origin origin) {
        List<Statement> body = new ArrayList<>();
        for (ReportGroup.ReportLine line : group.lines()) {
            body.addAll(presentLine(report, line, file, mayBreak, origin));
        }
        return body;
    }

    private List<Statement> presentLine(ReportDescription report, ReportGroup.ReportLine line,
                                        FileDescription file, boolean mayBreak, Origin origin) {
        ReportDescription.Registers r = report.registers();
        List<Statement> body = new ArrayList<>(place(report, line, file, mayBreak, origin));

        // 送る行数。改頁が要るなら負の値にして、1 回の書き込みで済ませる
        body.add(compute(r.advance(),
                minus(value(r.target()), value(r.lineCounter())), origin));
        body.add(new Statement.If(equals(r.eject(), 1, origin),
                List.of(compute(r.advance(), negated(value(r.target())), origin),
                        store(r.eject(), 0, origin)),
                List.of(), origin));

        DataItem record = itemNamed(line.recordName(), origin);
        if (record == null) {
            return body;
        }
        body.add(blank(record, origin));
        for (ReportGroup.ReportField field : line.fields()) {
            Statement move = fill(field, origin);
            if (move != null) {
                body.add(move);
            }
        }
        body.add(new Statement.Write(file, record, null, null,
                new Statement.Advancing(null, reference(r.advance(), origin), false, false),
                null, origin));
        body.add(compute(r.lineCounter(), value(r.target()), origin));
        return body;
    }

    /** 置く行を {@code RW-TGT$} に決める。要るなら頁を改める。 */
    private List<Statement> place(ReportDescription report, ReportGroup.ReportLine line,
                                  FileDescription file, boolean mayBreak, Origin origin) {
        ReportDescription.Registers r = report.registers();
        ReportDescription.PageShape page = report.page();
        ReportGroup.Placement placement = line.placement();
        List<Statement> body = new ArrayList<>();
        switch (placement.kind()) {
            case NEXT_PAGE -> {
                body.addAll(breakPage(report, file, origin));
                body.add(store(r.target(), page.firstDetail(), origin));
            }
            case ABSOLUTE -> {
                if (mayBreak) {
                    // その行をすでに書いてしまっているなら、次の頁の同じ行へ
                    body.add(new Statement.If(
                            relation(value(r.lineCounter()),
                                    Condition.Comparison.GREATER_OR_EQUAL,
                                    constant(placement.n()), origin),
                            breakPage(report, file, origin), List.of(), origin));
                }
                body.add(store(r.target(), placement.n(), origin));
            }
            case RELATIVE -> {
                body.add(compute(r.target(),
                        plus(value(r.lineCounter()), constant(placement.n())), origin));
                if (mayBreak) {
                    // 本文は FIRST DETAIL より上には置かない
                    body.add(new Statement.If(
                            relation(value(r.target()), Condition.Comparison.LESS,
                                    constant(page.firstDetail()), origin),
                            List.of(store(r.target(), page.firstDetail(), origin)),
                            List.of(), origin));
                    // LAST DETAIL を越えるなら頁を改め、本文の先頭から置き直す
                    List<Statement> turn = new ArrayList<>(breakPage(report, file, origin));
                    turn.add(compute(r.target(),
                            plus(value(r.lineCounter()), constant(placement.n())), origin));
                    turn.add(new Statement.If(
                            relation(value(r.target()), Condition.Comparison.LESS,
                                    constant(page.firstDetail()), origin),
                            List.of(store(r.target(), page.firstDetail(), origin)),
                            List.of(), origin));
                    body.add(new Statement.If(
                            relation(value(r.target()), Condition.Comparison.GREATER,
                                    constant(page.lastDetail()), origin),
                            turn, List.of(), origin));
                }
            }
            default -> throw new IllegalStateException("unknown placement: " + placement.kind());
        }
        return body;
    }

    /**
     * 頁を改める。
     *
     * <p>脚注を置き、数え札を進め、見出しを置く。脚注も見出しも頁に固定された行なので、
     * ここから<b>また頁を改めることは無い</b>。だから入れ子にならない。
     */
    private List<Statement> breakPage(ReportDescription report, FileDescription file,
                                      Origin origin) {
        ReportDescription.Registers r = report.registers();
        List<Statement> body = new ArrayList<>();
        body.addAll(present(report, ReportGroup.Type.PAGE_FOOTING, file, origin));
        body.add(compute(r.pageCounter(), plus(value(r.pageCounter()), constant(1)), origin));
        body.add(store(r.lineCounter(), 0, origin));
        body.add(store(r.eject(), 1, origin));
        body.addAll(present(report, ReportGroup.Type.PAGE_HEADING, file, origin));
        return body;
    }

    // ---- 行の中身 ----

    /** 行の姿を空白で埋める。書かれていない桁は空白でなければならない。 */
    private Statement blank(DataItem record, Origin origin) {
        Operand spaces = new Operand.Literal(
                new LiteralValue.Figure(LiteralValue.FigurativeConstant.SPACE));
        DataReference target = new DataReference(record, List.of(), null, origin);
        return new Statement.Move(spaces,
                List.of(new Statement.Move.Target(target, MoveRules.Kind.ALPHANUMERIC)),
                false, origin);
    }

    /**
     * 欄へ値を入れる。
     *
     * <p>行の姿はファイル節のレコード領域なので、{@code VALUE} では初期化されない
     * (書くたびに空白で埋め直してもいる)。定数の欄も、置くたびに入れる。
     */
    private Statement fill(ReportGroup.ReportField field, Origin origin) {
        DataItem slot = itemNamed(field.slotName(), field.origin());
        if (slot == null) {
            return null;
        }
        DataReference target = new DataReference(slot, List.of(), null, field.origin());
        DataCategory receiver = DataCategory.of(target);
        Operand source;
        DataCategory sender;
        if (field.source() != null) {
            DataReference read = resolver.resolve(field.source());
            if (read == null) {
                return null;
            }
            source = new Operand.Reference(read);
            sender = DataCategory.of(read);
        } else {
            source = new Operand.Literal(field.value());
            sender = DataCategory.of(field.value(),
                    receiver.isNumeric() || receiver == DataCategory.NUMERIC_EDITED);
        }
        MoveRules.Kind kind = MoveRules.kindOf(sender, receiver);
        return new Statement.Move(source,
                List.of(new Statement.Move.Target(target, kind)), false, field.origin());
    }

    // ---- 組み立ての小物 ----

    private DataItem itemNamed(String name, Origin origin) {
        DataReference reference = resolver.resolveName(name, origin);
        return reference == null ? null : reference.item();
    }

    private static DataReference reference(DataItem item, Origin origin) {
        return new DataReference(item, List.of(), null, origin);
    }

    private static Expression value(DataItem item) {
        return new Expression.Value(
                new Operand.Reference(new DataReference(item, List.of(), null, null)));
    }

    private static Expression constant(int n) {
        return new Expression.Value(new Operand.Literal(
                new LiteralValue.Number(Decimal.of(n, 0))));
    }

    private static Expression plus(Expression left, Expression right) {
        return new Expression.Binary(Expression.Operator.ADD, left, right);
    }

    private static Expression minus(Expression left, Expression right) {
        return new Expression.Binary(Expression.Operator.SUBTRACT, left, right);
    }

    private static Expression negated(Expression inner) {
        return new Expression.Negate(inner);
    }

    private static Statement store(DataItem item, int n, Origin origin) {
        return compute(item, constant(n), origin);
    }

    private static Statement compute(DataItem item, Expression value, Origin origin) {
        return new Statement.Compute(value,
                List.of(new Statement.Arithmetic.Target(reference(item, origin), false)),
                null, origin);
    }

    private static Condition equals(DataItem item, int n, Origin origin) {
        return relation(value(item), Condition.Comparison.EQUAL, constant(n), origin);
    }

    private static Condition relation(Expression left, Condition.Comparison comparison,
                                      Expression right, Origin origin) {
        return new Condition.Relation(left, comparison, right, true, origin);
    }
}
