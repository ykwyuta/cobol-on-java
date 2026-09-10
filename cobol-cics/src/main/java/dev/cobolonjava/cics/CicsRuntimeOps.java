package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.Objects;
import java.util.Optional;

/** 生成コードがCICS commandへ使う低レベルruntime操作。 */
public final class CicsRuntimeOps {

    private CicsRuntimeOps() {
    }

    public static void link(ProgramContext context, String program, DataView commarea) {
        CicsCommandOutcome outcome = execute(context,
                new LinkCommand(ProgramId.of(program), payload(commarea)));
        ContinueControl control = requireControl(outcome, ContinueControl.class, "LINK");
        copyBack(commarea, control.payload(), "LINK");
    }

    public static void xctl(ProgramContext context, String program, DataView commarea) {
        CicsCommandOutcome outcome = execute(context,
                new XctlCommand(ProgramId.of(program), payload(commarea)));
        throw new CicsProgramTransfer(
                requireControl(outcome, TransferControl.class, "XCTL"));
    }

    public static void returnTask(
            ProgramContext context, String nextTransaction, DataView commarea) {
        ReturnCommand command = nextTransaction == null
                ? new ReturnCommand(java.util.Optional.empty(), payload(commarea))
                : ReturnCommand.next(TransId.of(nextTransaction), payload(commarea));
        CicsCommandOutcome outcome = execute(context, command);
        throw new CicsProgramTransfer(
                requireControl(outcome, TaskCompletion.class, "RETURN"));
    }

    public static void syncpoint(ProgramContext context, boolean rollback) {
        SyncpointAction action = rollback ? SyncpointAction.ROLLBACK : SyncpointAction.COMMIT;
        CicsCommandOutcome outcome = execute(context, new SyncpointCommand(action));
        requireControl(outcome, SyncpointCompletion.class, "SYNCPOINT");
    }

    public static void abend(
            ProgramContext context, String code, boolean cancelHandlers, boolean noDump) {
        AbendCommand command = code == null
                ? AbendCommand.unspecified(cancelHandlers)
                : new AbendCommand(Optional.of(CicsAbendCode.of(code)),
                        cancelHandlers, noDump);
        execution(context).execute(command);
        throw new CicsTaskStateException("ABEND command returned without terminating the task");
    }

    /** 生成コードが暗黙EIB項目を参照するためのtask-local storage。 */
    public static Storage eibStorage(ProgramContext context) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        return execution(required).eib(required.codePage()).storage();
    }

    private static CicsCommandOutcome execute(ProgramContext context, CicsCommand command) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        CicsExecution execution = execution(required);
        CicsCommandOutcome outcome = execution.execute(command);
        execution.eib(required.codePage()).updateResponse(
                outcome.responseCode(), outcome.responseCode2());
        return outcome;
    }

    private static CicsExecution execution(ProgramContext context) {
        return Objects.requireNonNull(context, "context").service(CicsExecution.class);
    }

    private static CicsPayload payload(DataView commarea) {
        return commarea == null
                ? CicsPayload.empty()
                : CicsPayload.ofCommarea(commarea.toByteArray());
    }

    private static void copyBack(DataView target, CicsPayload returned, String command) {
        byte[] bytes = returned.commarea();
        if (target == null) {
            if (bytes.length != 0) {
                throw new CicsTaskStateException(
                        command + " returned a COMMAREA where none was supplied");
            }
            return;
        }
        if (bytes.length != target.length()) {
            throw new CicsTaskStateException(
                    command + " changed COMMAREA length from " + target.length()
                            + " to " + bytes.length);
        }
        target.setBytes(bytes);
    }

    private static <T extends CicsControl> T requireControl(
            CicsCommandOutcome outcome, Class<T> type, String command) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome.responseCode() != DefaultCicsGateway.NORMAL_RESPONSE) {
            throw new CicsTaskStateException(
                    command + " failed with RESP=" + outcome.responseCode()
                            + " RESP2=" + outcome.responseCode2());
        }
        if (!type.isInstance(outcome.control())) {
            throw new CicsTaskStateException(
                    command + " returned unexpected control "
                            + outcome.control().getClass().getSimpleName());
        }
        return type.cast(outcome.control());
    }
}
