package dev.cobolonjava.junit;

import dev.cobolonjava.runtime.procedure.ProcedureId;
import dev.cobolonjava.runtime.procedure.ProcedureOutcome;
import java.util.List;

/** SECTION境界で固定した作業場所・LINKAGE引数の前後像。 */
public record SectionInvocation(
        long sequence,
        ProcedureId procedureId,
        byte[] workingStorageBefore,
        byte[] workingStorageAfter,
        List<byte[]> argumentsBefore,
        List<byte[]> argumentsAfter,
        ProcedureOutcome outcome) {

    public SectionInvocation {
        workingStorageBefore = workingStorageBefore.clone();
        workingStorageAfter = workingStorageAfter.clone();
        argumentsBefore = copy(argumentsBefore);
        argumentsAfter = copy(argumentsAfter);
    }

    @Override
    public byte[] workingStorageBefore() {
        return workingStorageBefore.clone();
    }

    @Override
    public byte[] workingStorageAfter() {
        return workingStorageAfter.clone();
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
