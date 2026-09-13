package dev.cobolonjava.runtime.interop;

import dev.cobolonjava.runtime.storage.Storage;
import java.util.Objects;

/** Java からの一回の COBOL 呼び出し結果。 */
public record CobolCallResult(
        ProgramId programId,
        Termination termination,
        int returnCode,
        Storage workingStorage) {

    public CobolCallResult {
        Objects.requireNonNull(programId, "programId");
        Objects.requireNonNull(termination, "termination");
        Objects.requireNonNull(workingStorage, "workingStorage");
    }
}
