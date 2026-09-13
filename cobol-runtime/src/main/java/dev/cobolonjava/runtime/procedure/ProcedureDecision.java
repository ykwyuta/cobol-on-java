package dev.cobolonjava.runtime.procedure;

/** 明示的PERFORMの実体を動かすか、hookの結果だけで復帰するか。 */
public enum ProcedureDecision {
    PROCEED,
    RETURN
}
