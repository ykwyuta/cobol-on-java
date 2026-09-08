package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.parser.CobolParser;
import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.parser.OriginToken;
import dev.cobolonjava.compiler.source.Origin;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

/**
 * 一意名を記憶域の割り付けへ結び付ける (要件 FR-024, FR-026)。
 *
 * <h2>同じ名前は複数あってよい</h2>
 * <p>COBOL は同じ名前を複数の場所に置ける。区別は {@code OF} / {@code IN} による修飾で行う。
 * <b>修飾は「直上」ではなく「外側のどこか」でよい</b> — 途中のレベルを飛ばして書ける。
 * したがって候補ごとに祖先をたどり、修飾子が<b>外へ向かう順で</b>現れるかを見る。
 *
 * <p>絞り込んで 1 個にならなければ誤りとする。どれか 1 個を選ぶと、
 * <b>書いた人の意図と違う項目を黙って使う</b>ことになる。
 *
 * <h2>添字は表の数だけ要る</h2>
 * <p>{@code OCCURS} を持つ項目とその外側の表の数だけ添字が要る。回数が 1 でも要る。
 * 個数が合わなければ誤りとする。定数の添字は範囲も検査する。
 */
public final class ReferenceResolver {

    private final DataLayout layout;
    private final List<Diagnostic> diagnostics;

    public ReferenceResolver(DataLayout layout, List<Diagnostic> diagnostics) {
        this.layout = layout;
        this.diagnostics = diagnostics;
    }

    /**
     * 一意名を解決する。解決できなければ誤りを記録して {@code null} を返す。
     */
    public DataReference resolve(CobolParser.IdentifierContext context) {
        return resolve(context, false);
    }

    /**
     * 一意名を解決する。
     *
     * @param allowAll {@code ALL} と書いた添字を許すか。組み込み関数の引数だけである
     */
    public DataReference resolve(CobolParser.IdentifierContext context, boolean allowAll) {
        Origin origin = originOf(context);
        DataItem item = resolveName(context.qualifiedDataName(), origin);
        if (item == null) {
            return null;
        }

        List<DataReference.Subscript> subscripts = subscriptsOf(context, origin);
        if (subscripts == null || !checkSubscripts(item, subscripts, origin)) {
            return null;
        }
        if (!allowAll && subscripts.contains(new DataReference.Subscript.All())) {
            report(origin, "ALL may be written as a subscript only in an intrinsic"
                    + " function argument");
            return null;
        }

        DataReference.RefMod refMod = null;
        if (context.referenceModifier() != null) {
            refMod = resolveRefMod(context.referenceModifier(), origin);
            if (refMod == null) {
                return null;
            }
        }

        DataReference reference = new DataReference(item, subscripts, refMod, origin);
        checkReferenceModification(reference, origin);
        return reference;
    }

    /**
     * 名前で引けない項目への参照を、書かれた添字と合わせて作る。
     *
     * <p>条件名 (88 レベル) が使う。条件名そのものは記憶域を持たないので
     * {@link #resolveName} では引けないが、<b>添字は条件名のほうに書かれる</b>。
     * {@code IF CN1 (1)} の {@code (1)} は親の表への添字である。
     *
     * @return 添字の数や範囲が合わなければ {@code null}
     */
    public DataReference resolveAs(DataItem item, CobolParser.IdentifierContext context) {
        Origin origin = originOf(context);
        List<DataReference.Subscript> subscripts = subscriptsOf(context, origin);
        if (subscripts == null || !checkSubscripts(item, subscripts, origin)) {
            return null;
        }
        return new DataReference(item, subscripts, null, origin);
    }

    /**
     * 書かれた添字を解く。
     *
     * @return 1 つでも解けなければ {@code null}
     */
    private List<DataReference.Subscript> subscriptsOf(CobolParser.IdentifierContext context,
                                                       Origin origin) {
        List<DataReference.Subscript> subscripts = new ArrayList<>();
        if (context.subscripts() != null) {
            for (CobolParser.SubscriptContext subscript : context.subscripts().subscript()) {
                DataReference.Subscript resolved = resolveSubscript(subscript, origin);
                if (resolved == null) {
                    return null;
                }
                subscripts.add(resolved);
            }
        }
        return subscripts;
    }

    /** 修飾された名前から項目 1 個を決める。 */
    public DataItem resolveName(CobolParser.QualifiedDataNameContext context, Origin origin) {
        List<String> names = new ArrayList<>();
        for (CobolParser.DataNameContext name : context.dataName()) {
            names.add(name.getText().toUpperCase(Locale.ROOT));
        }
        String target = names.get(0);
        List<String> qualifiers = names.subList(1, names.size());

        List<DataItem> found = new ArrayList<>();
        for (DataItem candidate : layout.findAll(target)) {
            if (qualifiersMatch(candidate, qualifiers)) {
                found.add(candidate);
            }
        }
        if (found.isEmpty() && qualifiers.isEmpty()) {
            // 指標名と特殊レジスタはデータ項目ではないので、名前で項目を探す道では見つからない。
            // 探すのは最後である。同じ名前をデータ部に書いていれば、そちらが勝つ
            DataItem index = layout.findIndex(target);
            if (index != null) {
                return index;
            }
            DataItem register = layout.findRegister(target);
            if (register != null) {
                return register;
            }
        }
        if (found.isEmpty()) {
            report(origin, layout.findAll(target).isEmpty()
                    ? "undefined data item: " + target
                    : "no " + target + " is contained in " + String.join(" of ", qualifiers));
            return null;
        }
        if (found.size() > 1) {
            // どれか 1 個を選ぶと、書いた人の意図と違う項目を黙って使うことになる
            report(origin, target + " is ambiguous; qualify it with OF or IN");
            return null;
        }
        return found.get(0);
    }

    /** 修飾子が、外へ向かう順に祖先として現れるか。途中のレベルは飛ばしてよい。 */
    private static boolean qualifiersMatch(DataItem item, List<String> qualifiers) {
        DataItem current = item.parent();
        for (String qualifier : qualifiers) {
            while (current != null && !qualifier.equals(current.name())) {
                current = current.parent();
            }
            if (current == null) {
                return false;
            }
            current = current.parent();
        }
        return true;
    }

    private DataReference.Subscript resolveSubscript(CobolParser.SubscriptContext context,
                                                     Origin origin) {
        if (context.ALL() != null) {
            return new DataReference.Subscript.All();
        }
        if (context.NUMBER() != null) {
            try {
                return new DataReference.Subscript.Constant(
                        Integer.parseInt(context.NUMBER().getText()));
            } catch (NumberFormatException e) {
                report(origin, "a subscript must be an integer: " + context.NUMBER().getText());
                return null;
            }
        }
        DataItem item = resolveName(context.qualifiedDataName(), origin);
        if (item == null) {
            return null;
        }
        Integer offset = offsetOf(context.relativeOffset(), origin);
        if (offset == null) {
            return null;
        }
        return new DataReference.Subscript.Variable(
                new DataReference(item, List.of(), null, origin), offset);
    }

    /**
     * 相対指定のずれを読む (要件 FR-025)。
     *
     * @return 書かれていなければ 0。読めなければ {@code null}
     */
    private Integer offsetOf(CobolParser.RelativeOffsetContext context, Origin origin) {
        if (context == null) {
            return 0;
        }
        try {
            int magnitude = Integer.parseInt(context.NUMBER().getText());
            return context.MINUS_SIGN() != null ? -magnitude : magnitude;
        } catch (NumberFormatException e) {
            report(origin, "a relative subscript must be an integer: " + context.getText());
            return null;
        }
    }

    private boolean checkSubscripts(DataItem item, List<DataReference.Subscript> subscripts,
                                    Origin origin) {
        List<DataItem> tables = DataReference.tableChain(item);
        if (tables.size() != subscripts.size()) {
            report(origin, describe(item) + " requires " + tables.size()
                    + " subscript(s) but " + subscripts.size() + " were given");
            return false;
        }
        for (int i = 0; i < tables.size(); i++) {
            if (subscripts.get(i) instanceof DataReference.Subscript.Constant constant
                    && (constant.value() < 1 || constant.value() > tables.get(i).occurs())) {
                report(origin, "subscript " + constant.value() + " is outside 1.."
                        + tables.get(i).occurs() + " for " + describe(tables.get(i)));
                return false;
            }
        }
        return true;
    }

    private DataReference.RefMod resolveRefMod(CobolParser.ReferenceModifierContext context,
                                               Origin origin) {
        DataReference.Subscript leftmost = resolveSubscript(context.subscript(0), origin);
        if (leftmost == null) {
            return null;
        }
        DataReference.Subscript length = null;
        if (context.subscript().size() > 1) {
            length = resolveSubscript(context.subscript(1), origin);
            if (length == null) {
                return null;
            }
        }
        return new DataReference.RefMod(leftmost, length);
    }

    /** 部分参照が項目の外へはみ出していないか。定数で書かれているときだけ判定できる。 */
    private void checkReferenceModification(DataReference reference, Origin origin) {
        if (reference.refMod() == null) {
            return;
        }
        if (!(reference.refMod().leftmost() instanceof DataReference.Subscript.Constant leftmost)) {
            return;
        }
        int size = reference.item().length();
        if (leftmost.value() < 1 || leftmost.value() > size) {
            report(origin, "reference modification starts at " + leftmost.value()
                    + " which is outside 1.." + size + " for " + describe(reference.item()));
            return;
        }
        if (reference.refMod().length()
                instanceof DataReference.Subscript.Constant length
                && (length.value() < 1 || leftmost.value() + length.value() - 1 > size)) {
            report(origin, "reference modification of length " + length.value()
                    + " from " + leftmost.value() + " runs past the end of "
                    + describe(reference.item()));
        }
    }

    private static String describe(DataItem item) {
        return item.name() == null ? "FILLER" : item.name();
    }

    private void report(Origin origin, String message) {
        diagnostics.add(new Diagnostic(origin, message));
    }

    static Origin originOf(ParserRuleContext context) {
        Token token = context.getStart();
        return token instanceof OriginToken origin ? origin.origin() : null;
    }
}
