package dev.cobolonjava.runtime.procedure;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.List;
import java.util.Objects;

/** SECTION hookへ渡す、現在の同期実行フレーム。 */
public final class ProcedureInvocation {

    private final long sequence;
    private final ProcedureId procedureId;
    private final String callerProcedure;
    private final String sourceFile;
    private final int sourceLine;
    private final Storage workingStorage;
    private final List<DataView> arguments;
    private final ProgramContext context;

    public ProcedureInvocation(long sequence, ProcedureId procedureId, String callerProcedure,
                               String sourceFile, int sourceLine, Storage workingStorage,
                               List<DataView> arguments, ProgramContext context) {
        this.sequence = sequence;
        this.procedureId = Objects.requireNonNull(procedureId, "procedureId");
        this.callerProcedure = callerProcedure;
        this.sourceFile = sourceFile;
        this.sourceLine = sourceLine;
        this.workingStorage = Objects.requireNonNull(workingStorage, "workingStorage");
        this.arguments = List.copyOf(arguments);
        this.context = Objects.requireNonNull(context, "context");
    }

    public long sequence() {
        return sequence;
    }

    public ProcedureId procedureId() {
        return procedureId;
    }

    public String callerProcedure() {
        return callerProcedure;
    }

    public String sourceFile() {
        return sourceFile;
    }

    public int sourceLine() {
        return sourceLine;
    }

    public Storage workingStorage() {
        return workingStorage;
    }

    public List<DataView> arguments() {
        return arguments;
    }

    public CodePage codePage() {
        return context.codePage();
    }

    public int returnCode() {
        return context.returnCode();
    }

    public void setReturnCode(int value) {
        context.setReturnCode(value);
    }
}
