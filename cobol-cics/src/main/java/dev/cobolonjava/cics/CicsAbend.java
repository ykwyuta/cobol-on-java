package dev.cobolonjava.cics;

import java.util.Objects;

/** EXEC CICS ABENDによる、task単位の構造化異常終了。 */
public final class CicsAbend extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final CicsTaskId taskId;
    private final CicsAbendCode code;
    private final boolean cancelHandlers;
    private final boolean dumpRequested;

    public CicsAbend(CicsTaskId taskId, AbendCommand command) {
        super("CICS task " + Objects.requireNonNull(taskId, "taskId").value()
                + " abended with " + Objects.requireNonNull(command, "command")
                        .effectiveCode().value());
        this.taskId = taskId;
        this.code = command.effectiveCode();
        this.cancelHandlers = command.cancelHandlers();
        this.dumpRequested = command.dumpRequested();
    }

    public CicsTaskId taskId() {
        return taskId;
    }

    public CicsAbendCode code() {
        return code;
    }

    public boolean cancelHandlers() {
        return cancelHandlers;
    }

    public boolean dumpRequested() {
        return dumpRequested;
    }
}
