package dev.cobolonjava.runtime.procedure;

/** 明示的な外部形式PERFORMへ挿入する、JUnit非依存の差し替え境界。 */
@FunctionalInterface
public interface ProcedureHook {

    ProcedureHook NOOP = invocation -> ProcedureDecision.PROCEED;

    ProcedureDecision before(ProcedureInvocation invocation);

    default void after(ProcedureInvocation invocation, ProcedureOutcome outcome) {
    }
}
