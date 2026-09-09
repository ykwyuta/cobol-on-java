package dev.cobolonjava.runtime.procedure;

import java.util.Objects;

/** beforeとafterで同じhook・invocationを使うための一回限りの境界値。 */
public record ProcedureBoundary(
        ProcedureHook hook,
        ProcedureInvocation invocation,
        ProcedureDecision decision) {

    public ProcedureBoundary {
        Objects.requireNonNull(hook, "hook");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(decision, "decision");
    }
}
