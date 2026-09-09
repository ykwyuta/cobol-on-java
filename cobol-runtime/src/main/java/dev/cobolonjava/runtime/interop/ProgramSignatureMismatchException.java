package dev.cobolonjava.runtime.interop;

/** 呼び先を動かす前に検出したプログラムABIの不一致。 */
public final class ProgramSignatureMismatchException extends IllegalArgumentException {

    private final ProgramId programId;

    public ProgramSignatureMismatchException(ProgramId programId, String detail) {
        super("program signature mismatch for " + programId.value() + ": " + detail);
        this.programId = programId;
    }

    public ProgramId programId() {
        return programId;
    }
}
