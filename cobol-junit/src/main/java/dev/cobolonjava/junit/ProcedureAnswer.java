package dev.cobolonjava.junit;

import dev.cobolonjava.runtime.procedure.ProcedureInvocation;

/** SECTION Mockへ渡すJava実装。 */
@FunctionalInterface
public interface ProcedureAnswer {

    void answer(ProcedureInvocation invocation) throws Exception;
}
