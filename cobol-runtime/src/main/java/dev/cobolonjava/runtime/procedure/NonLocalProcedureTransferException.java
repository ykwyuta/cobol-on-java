package dev.cobolonjava.runtime.procedure;

/** SECTION単独実行では元の意味を保てない制御移動を事前に拒否した。 */
public final class NonLocalProcedureTransferException extends IllegalStateException {

    private final ProcedureId procedureId;

    public NonLocalProcedureTransferException(ProcedureId procedureId, String reason) {
        super(procedureId + " cannot be invoked directly: " + reason);
        this.procedureId = procedureId;
    }

    public ProcedureId procedureId() {
        return procedureId;
    }
}
