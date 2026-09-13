package dev.cobolonjava.junit;

import dev.cobolonjava.runtime.interop.ProgramId;
import java.util.List;

/** Mock 呼び出し境界で固定した、変更前後のバイトスナップショット。 */
public record ProgramInvocation(
        long sequence,
        ProgramId programId,
        List<byte[]> argumentsBefore,
        List<byte[]> argumentsAfter) {

    public ProgramInvocation {
        argumentsBefore = copy(argumentsBefore);
        argumentsAfter = copy(argumentsAfter);
    }

    @Override
    public List<byte[]> argumentsBefore() {
        return copy(argumentsBefore);
    }

    @Override
    public List<byte[]> argumentsAfter() {
        return copy(argumentsAfter);
    }

    private static List<byte[]> copy(List<byte[]> values) {
        return values.stream().map(byte[]::clone).toList();
    }
}
