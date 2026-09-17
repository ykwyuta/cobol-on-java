package dev.cobolonjava.verify.pli;

import dev.cobolonjava.verify.corpus.Reasons;
import java.util.List;

/** PL/I コーパス 1 本を翻訳または実行した結末。 */
public record PliCaseOutcome(String name, String group, Status status,
                             List<String> diagnostics, String failure) {

    public enum Status {
        /** 期待出力が無いので翻訳まで通った。 */
        COMPILED,
        /** 参照処理系から採取した期待出力と一致した。 */
        PASSED,
        /** 診断を出して翻訳を断った。 */
        REJECTED,
        /** 実行できたが参照処理系の出力と違った。 */
        WRONG_OUTPUT,
        /** 診断を経ずに壊れたか、時間切れになった。 */
        CRASHED
    }

    public PliCaseOutcome {
        diagnostics = List.copyOf(diagnostics);
    }

    static PliCaseOutcome compiled(String name, String group) {
        return new PliCaseOutcome(name, group, Status.COMPILED, List.of(), null);
    }

    static PliCaseOutcome passed(String name, String group) {
        return new PliCaseOutcome(name, group, Status.PASSED, List.of(), null);
    }

    static PliCaseOutcome rejected(String name, String group, List<String> diagnostics) {
        return new PliCaseOutcome(name, group, Status.REJECTED, diagnostics, null);
    }

    static PliCaseOutcome wrongOutput(String name, String group) {
        return new PliCaseOutcome(name, group, Status.WRONG_OUTPUT, List.of(),
                "output differs from the reference implementation");
    }

    static PliCaseOutcome crashed(String name, String group, Throwable failure) {
        String detail = failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        return new PliCaseOutcome(name, group, Status.CRASHED, List.of(), detail);
    }

    static PliCaseOutcome timedOut(String name, String group, long seconds) {
        return new PliCaseOutcome(name, group, Status.CRASHED, List.of(),
                "the PL/I case did not finish within " + seconds + " seconds");
    }

    public String reason() {
        return switch (status) {
            case COMPILED, PASSED -> "";
            case REJECTED -> diagnostics.isEmpty() ? "(no diagnostic)"
                    : Reasons.normalized(diagnostics.get(0));
            case WRONG_OUTPUT, CRASHED -> Reasons.normalized(failure);
        };
    }
}
