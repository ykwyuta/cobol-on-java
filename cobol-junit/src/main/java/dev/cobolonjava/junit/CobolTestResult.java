package dev.cobolonjava.junit;

import dev.cobolonjava.runtime.interop.CobolCallResult;
import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.interop.Termination;
import dev.cobolonjava.runtime.storage.Storage;

/** 一回のテスト実行結果と、その時点までに捕捉した出力。 */
public record CobolTestResult(
        ProgramId programId,
        Termination termination,
        int returnCode,
        Storage workingStorage,
        String output) {

    static CobolTestResult from(CobolCallResult result, String output) {
        return new CobolTestResult(result.programId(), result.termination(), result.returnCode(),
                result.workingStorage(), output);
    }
}
