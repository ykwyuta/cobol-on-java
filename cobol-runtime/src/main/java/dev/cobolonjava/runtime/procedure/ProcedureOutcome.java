package dev.cobolonjava.runtime.procedure;

/** 手続き境界を抜けた経路。 */
public enum ProcedureOutcome {
    REAL_RETURN,
    MOCK_RETURN,
    GOBACK,
    STOP_RUN,
    ABEND,
    THREW
}
