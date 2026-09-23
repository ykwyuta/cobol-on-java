package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.Origin;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * 国字 ({@code PIC N}、{@code N'..'}、{@code NATIONAL-OF}) を扱えない文で使っていないか確かめる。
 *
 * <p>国字を扱えるのは {@code MOVE}、{@code DISPLAY}、{@code INITIALIZE}、{@code CALL} (バイトの
 * まま渡す)、関係条件、{@code NATIONAL-OF} と {@code DISPLAY-OF} だけである。ほかの文
 * ({@code STRING}、{@code INSPECT}、{@code ACCEPT}、{@code SORT} のキーなど) は、国字の
 * バイトを英数字のバイトとして扱ってしまう。<b>UTF-16 を EBCDIC として読み書きしても、翻訳も
 * 実行も通る</b>。だから、文ごとに国字を扱うようにするまでは、ここで断る。
 *
 * <p>国字項目の部分参照も断る。国字の部分参照の位置と長さは文字 (2 バイト) で数えるが、
 * 番地の計算はバイトで数えている。
 */
public final class NationalGuard {

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private NationalGuard() {
    }

    /** 文の並びを確かめる。 */
    public static List<Diagnostic> check(Iterable<? extends Statement> statements) {
        NationalGuard guard = new NationalGuard();
        for (Statement statement : statements) {
            guard.statement(statement);
        }
        return guard.diagnostics;
    }

    private void statement(Statement statement) {
        Origin origin = originOf(statement, null);
        if (statement instanceof Statement.Move
                || statement instanceof Statement.Display
                || statement instanceof Statement.Initialize
                || statement instanceof Statement.Call) {
            components(statement, origin, true);
            return;
        }
        if (statement instanceof Statement.If branch) {
            condition(branch.condition(), origin);
            branch.onTrue().forEach(this::statement);
            branch.onFalse().forEach(this::statement);
            return;
        }
        components(statement, origin, false);
    }

    private void condition(Condition condition, Origin origin) {
        if (condition instanceof Condition.Relation) {
            // 関係条件は国字どうし、国字と英数字を比べられる (どの文の中の条件でも同じ道を通る)
            components(condition, origin, true);
        } else if (condition instanceof Condition.Not not) {
            condition(not.inner(), origin);
        } else if (condition instanceof Condition.And and) {
            condition(and.left(), origin);
            condition(and.right(), origin);
        } else if (condition instanceof Condition.Or or) {
            condition(or.left(), origin);
            condition(or.right(), origin);
        } else {
            components(condition, origin, false);
        }
    }

    /**
     * 値の中の参照を調べる。
     *
     * @param allowed 国字を扱える文の中か
     */
    private void walk(Object value, Origin origin, boolean allowed) {
        if (value == null) {
            return;
        }
        if (value instanceof Statement nested) {
            // 文の中の文 (条件文の中身など) は、その文の規則で調べる
            statement(nested);
            return;
        }
        if (value instanceof Condition condition) {
            condition(condition, origin);
            return;
        }
        if (value instanceof DataReference reference) {
            reference(reference, origin, allowed);
            return;
        }
        if (value instanceof Operand.Literal literal) {
            if (!allowed && literal.value() instanceof LiteralValue.National) {
                refuse(origin, "a national literal");
            }
            return;
        }
        if (value instanceof Operand.Function function) {
            // 国字を引数に取れる関数。LENGTH は文字の数を返す
            boolean converts = function.intrinsic() == Intrinsic.NATIONAL_OF
                    || function.intrinsic() == Intrinsic.DISPLAY_OF
                    || function.intrinsic() == Intrinsic.LENGTH;
            if (!allowed && function.returns() == Intrinsic.Result.NATIONAL) {
                refuse(origin, "FUNCTION " + function.intrinsic().spelling());
            }
            for (Expression argument : function.arguments()) {
                walk(argument, origin, converts || allowed);
            }
            return;
        }
        if (value instanceof Iterable<?> values) {
            for (Object one : values) {
                walk(one, origin, allowed);
            }
            return;
        }
        components(value, origin, allowed);
    }

    /** 記録の欄を 1 つずつ調べる。 */
    private void components(Object value, Origin origin, boolean allowed) {
        Class<?> type = value.getClass();
        if (!type.isRecord() || !belongsToSemantics(type)) {
            return;
        }
        Origin here = originOf(value, origin);
        for (RecordComponent component : type.getRecordComponents()) {
            try {
                walk(component.getAccessor().invoke(value), here, allowed);
            } catch (ReflectiveOperationException impossible) {
                throw new IllegalStateException("cannot inspect " + type.getName(), impossible);
            }
        }
    }

    private void reference(DataReference reference, Origin origin, boolean allowed) {
        if (DataCategory.of(reference.item()) != DataCategory.NATIONAL) {
            return;
        }
        if (reference.refMod() != null) {
            refuse(origin, "reference modification of a national item");
        } else if (!allowed) {
            refuse(origin, "the national item "
                    + (reference.item().name() == null ? "FILLER" : reference.item().name()));
        }
    }

    private void refuse(Origin origin, String what) {
        diagnostics.add(new Diagnostic(origin, what + " cannot be used in this statement yet:"
                + " national data is supported in MOVE, DISPLAY, INITIALIZE, CALL, relation"
                + " conditions, NATIONAL-OF and DISPLAY-OF"));
    }

    private static Origin originOf(Object value, Origin fallback) {
        if (!value.getClass().isRecord()) {
            return fallback;
        }
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            if (component.getType() == Origin.class) {
                try {
                    Origin origin = (Origin) component.getAccessor().invoke(value);
                    return origin == null ? fallback : origin;
                } catch (ReflectiveOperationException impossible) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static boolean belongsToSemantics(Class<?> type) {
        return type.getPackageName().equals(NationalGuard.class.getPackageName());
    }
}
