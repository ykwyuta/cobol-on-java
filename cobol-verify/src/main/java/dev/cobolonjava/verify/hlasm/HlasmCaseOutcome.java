package dev.cobolonjava.verify.hlasm;

import dev.cobolonjava.verify.corpus.Reasons;
import java.util.List;

/**
 * HLASM コーパス 1 本を組み立て、または実行した結末。
 *
 * <p>{@code objectChecked} は、この 1 本に機械語の期待値 ({@code .obj}) があって実際に
 * 比べたかどうかである。これを持たないと、期待値の無い本まで「機械語が一致した」に
 * 数えてしまい、組み立ての一致率が実際より高く出る。
 *
 * <p>状態が 6 つあるのは、HLASM では<b>測る対象が 2 段になる</b>からである (設計 27 §3)。
 * 組み立て (原文 → 機械語) と実行 (その機械語を動かした結果) を同じ数に混ぜると、
 * 誤った機械語を Hercules も自分のインタプリタも同じように実行して一致し、
 * 測定が緑のまま嘘をつく。{@link Status#WRONG_OBJECT} を
 * {@link Status#WRONG_OUTPUT} と分けているのはそのためである。
 */
public record HlasmCaseOutcome(String name, String group, Status status,
                               boolean objectChecked, List<String> diagnostics, String failure) {

    public enum Status {
        /** 期待値のない原文を組み立てられた。 */
        ASSEMBLED,
        /** 期待した実行結果と一致した。 */
        PASSED,
        /** 位置付き診断を出して組み立てを断った。 */
        REJECTED,
        /** 組み立てた機械語が期待値と違った。<b>実行まで行かずにここで止める。</b> */
        WRONG_OBJECT,
        /** 機械語は合っていたが、実行の結果が違った。 */
        WRONG_OUTPUT,
        /** 診断を経ずに壊れたか、時間切れになった。 */
        CRASHED
    }

    public HlasmCaseOutcome {
        diagnostics = List.copyOf(diagnostics);
    }

    static HlasmCaseOutcome assembled(String name, String group, boolean objectChecked) {
        return new HlasmCaseOutcome(name, group, Status.ASSEMBLED, objectChecked, List.of(), null);
    }

    static HlasmCaseOutcome passed(String name, String group, boolean objectChecked) {
        return new HlasmCaseOutcome(name, group, Status.PASSED, objectChecked, List.of(), null);
    }

    static HlasmCaseOutcome rejected(String name, String group, List<String> diagnostics) {
        return new HlasmCaseOutcome(name, group, Status.REJECTED, false, diagnostics, null);
    }

    static HlasmCaseOutcome wrongObject(String name, String group, String expected, String actual) {
        return new HlasmCaseOutcome(name, group, Status.WRONG_OBJECT, true, List.of(),
                "the object code differs: expected " + expected + " but assembled " + actual);
    }

    static HlasmCaseOutcome wrongOutput(String name, String group, boolean objectChecked) {
        return new HlasmCaseOutcome(name, group, Status.WRONG_OUTPUT, objectChecked, List.of(),
                "the execution result differs from the expected one");
    }

    static HlasmCaseOutcome crashed(String name, String group, Throwable failure) {
        String detail = failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        return new HlasmCaseOutcome(name, group, Status.CRASHED, false, List.of(), detail);
    }

    static HlasmCaseOutcome timedOut(String name, String group, long seconds) {
        return new HlasmCaseOutcome(name, group, Status.CRASHED, false, List.of(),
                "the HLASM case did not finish within " + seconds + " seconds");
    }

    public String reason() {
        return switch (status) {
            case ASSEMBLED, PASSED -> "";
            case REJECTED -> diagnostics.isEmpty() ? "(no diagnostic)"
                    : Reasons.normalized(diagnostics.get(0));
            case WRONG_OBJECT, WRONG_OUTPUT, CRASHED -> Reasons.normalized(failure);
        };
    }
}
