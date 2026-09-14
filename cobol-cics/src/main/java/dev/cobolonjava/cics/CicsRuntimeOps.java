package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.program.ProgramTargetTransfer;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.Objects;
import java.util.Optional;

/** 生成コードがCICS commandへ使う低レベルruntime操作。 */
public final class CicsRuntimeOps {

    /** condition handlerへ移らず、次のCOBOL文へ進む。 */
    public static final int NO_CONDITION_TRANSFER = -1;
    private static final int HANDLE_CONDITION_FUNCTION = 0x0204;
    private static final int ASSIGN_FUNCTION = 0x0208;
    private static final int IGNORE_CONDITION_FUNCTION = 0x020A;
    private static final int PUSH_HANDLE_FUNCTION = 0x020C;
    private static final int POP_HANDLE_FUNCTION = 0x020E;
    private static final int LINK_FUNCTION = 0x0E02;
    private static final int XCTL_FUNCTION = 0x0E04;
    private static final int RETURN_FUNCTION = 0x0E08;
    private static final int HANDLE_ABEND_FUNCTION = 0x0E0E;
    private static final int SYNCPOINT_FUNCTION = 0x1602;

    private CicsRuntimeOps() {
    }

    public static void link(ProgramContext context, String program, DataView commarea) {
        link(context, program, commarea, false);
    }

    public static void link(
            ProgramContext context, String program, DataView commarea,
            boolean suppressDefaultHandling) {
        requireNoLegacyTransfer(linkCondition(
                context, program, commarea, suppressDefaultHandling), "LINK");
    }

    /** LINKを実行し、condition handlerへ移る場合はその段落番号を返す。 */
    public static int linkCondition(
            ProgramContext context, String program, DataView commarea,
            boolean suppressDefaultHandling) {
        CicsCommandOutcome outcome;
        try {
            outcome = execute(context,
                    new LinkCommand(ProgramId.of(program), payload(commarea)));
        } catch (ProgramTargetTransfer transfer) {
            return Ops.resumeTransfer(context, transfer);
        }
        int target = conditionTarget(context, outcome, suppressDefaultHandling, "LINK");
        if (target != NO_CONDITION_TRANSFER
                || outcome.responseCode() != CicsResponseCode.NORMAL) {
            return target;
        }
        ContinueControl control = requireControl(outcome, ContinueControl.class, "LINK");
        copyBack(commarea, control.payload(), "LINK");
        return NO_CONDITION_TRANSFER;
    }

    public static void xctl(ProgramContext context, String program, DataView commarea) {
        xctl(context, program, commarea, false);
    }

    public static void xctl(
            ProgramContext context, String program, DataView commarea,
            boolean suppressDefaultHandling) {
        requireNoLegacyTransfer(xctlCondition(
                context, program, commarea, suppressDefaultHandling), "XCTL");
    }

    /** XCTLを実行し、condition handlerへ移る場合はその段落番号を返す。 */
    public static int xctlCondition(
            ProgramContext context, String program, DataView commarea,
            boolean suppressDefaultHandling) {
        CicsCommandOutcome outcome = execute(context,
                new XctlCommand(ProgramId.of(program), payload(commarea)));
        int target = conditionTarget(context, outcome, suppressDefaultHandling, "XCTL");
        if (target != NO_CONDITION_TRANSFER
                || outcome.responseCode() != CicsResponseCode.NORMAL) {
            return target;
        }
        throw new CicsProgramTransfer(
                requireControl(outcome, TransferControl.class, "XCTL"));
    }

    public static void returnTask(
            ProgramContext context, String nextTransaction, DataView commarea) {
        returnTask(context, nextTransaction, commarea, false);
    }

    public static void returnTask(
            ProgramContext context, String nextTransaction, DataView commarea,
            boolean suppressDefaultHandling) {
        requireNoLegacyTransfer(returnTaskCondition(
                context, nextTransaction, commarea, suppressDefaultHandling), "RETURN");
    }

    /** RETURNを実行し、condition handlerへ移る場合はその段落番号を返す。 */
    public static int returnTaskCondition(
            ProgramContext context, String nextTransaction, DataView commarea,
            boolean suppressDefaultHandling) {
        return returnTaskCondition(context, nextTransaction, commarea, false,
                suppressDefaultHandling);
    }

    /** RETURN [IMMEDIATE] を実行し、condition handlerへ移る場合はその段落番号を返す。 */
    public static int returnTaskCondition(
            ProgramContext context, String nextTransaction, DataView commarea,
            boolean immediate, boolean suppressDefaultHandling) {
        ReturnCommand command = nextTransaction == null
                ? new ReturnCommand(java.util.Optional.empty(), payload(commarea))
                : new ReturnCommand(java.util.Optional.of(TransId.of(nextTransaction)),
                        payload(commarea), immediate);
        CicsCommandOutcome outcome = execute(context, command);
        int target = conditionTarget(context, outcome, suppressDefaultHandling, "RETURN");
        if (target != NO_CONDITION_TRANSFER
                || outcome.responseCode() != CicsResponseCode.NORMAL) {
            return target;
        }
        throw new CicsProgramTransfer(
                requireControl(outcome, TaskCompletion.class, "RETURN"));
    }

    public static void syncpoint(ProgramContext context, boolean rollback) {
        syncpoint(context, rollback, false);
    }

    public static void syncpoint(
            ProgramContext context, boolean rollback, boolean suppressDefaultHandling) {
        requireNoLegacyTransfer(syncpointCondition(
                context, rollback, suppressDefaultHandling), "SYNCPOINT");
    }

    /** SYNCPOINTを実行し、condition handlerへ移る場合はその段落番号を返す。 */
    public static int syncpointCondition(
            ProgramContext context, boolean rollback, boolean suppressDefaultHandling) {
        SyncpointAction action = rollback ? SyncpointAction.ROLLBACK : SyncpointAction.COMMIT;
        CicsCommandOutcome outcome = execute(context, new SyncpointCommand(action));
        int target = conditionTarget(context, outcome, suppressDefaultHandling, "SYNCPOINT");
        if (target != NO_CONDITION_TRANSFER
                || outcome.responseCode() != CicsResponseCode.NORMAL) {
            return target;
        }
        requireControl(outcome, SyncpointCompletion.class, "SYNCPOINT");
        return NO_CONDITION_TRANSFER;
    }

    /** 現在のCICS LINK levelへcondition handler段落を登録する。 */
    public static void handleCondition(ProgramContext context, int responseCode, int target) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        execution(required).handleCondition(
                required.currentInvocationToken(), responseCode, target);
        completeLocalCommand(required, HANDLE_CONDITION_FUNCTION);
    }

    /** 現在のCICS LINK levelで指定conditionを無視する。 */
    public static void ignoreCondition(ProgramContext context, int responseCode) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        execution(required).ignoreCondition(responseCode);
        completeLocalCommand(required, IGNORE_CONDITION_FUNCTION);
    }

    /** 現在のCICS LINK levelで指定conditionをCICS既定処置へ戻す。 */
    public static void resetCondition(ProgramContext context, int responseCode) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        execution(required).resetCondition(responseCode);
        completeLocalCommand(required, HANDLE_CONDITION_FUNCTION);
    }

    /** 現在のCICS LINK levelのcondition処置一式を退避して一時停止する。 */
    public static void pushHandle(ProgramContext context) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        execution(required).pushHandle();
        completeLocalCommand(required, PUSH_HANDLE_FUNCTION);
    }

    /** 現在のCICS LINK levelで最後に退避したcondition処置一式を復元する。 */
    public static void popHandle(ProgramContext context) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        execution(required).popHandle();
        completeLocalCommand(required, POP_HANDLE_FUNCTION);
    }

    /** 現在のCICS LINK levelへCOBOL LABEL形式のabend exitを登録する。 */
    public static void handleAbend(ProgramContext context, int target) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        execution(required).handleAbend(required.currentInvocationToken(), target);
        completeLocalCommand(required, HANDLE_ABEND_FUNCTION);
    }

    /** 現在のCICS LINK levelのabend exitを無効化する。 */
    public static void cancelAbendHandler(ProgramContext context) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        execution(required).cancelAbendHandler();
        completeLocalCommand(required, HANDLE_ABEND_FUNCTION);
    }

    /** 現在のCICS LINK levelのabend exitを再有効化する。 */
    public static void resetAbendHandler(ProgramContext context) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        execution(required).resetAbendHandler();
        completeLocalCommand(required, HANDLE_ABEND_FUNCTION);
    }

    public static void abend(
            ProgramContext context, String code, boolean cancelHandlers, boolean noDump) {
        requireNoLegacyTransfer(
                abendCondition(context, code, cancelHandlers, noDump), "ABEND");
    }

    /** ABENDを実行し、LABEL形式のabend exitへ移る場合はその段落番号を返す。 */
    public static int abendCondition(
            ProgramContext context, String code, boolean cancelHandlers, boolean noDump) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        AbendCommand command = code == null
                ? AbendCommand.unspecified(cancelHandlers)
                : new AbendCommand(Optional.of(CicsAbendCode.of(code)),
                        cancelHandlers, noDump);
        CicsExecution execution = execution(required);
        if (cancelHandlers) {
            execution.cancelAllAbendHandlers();
        }
        try {
            execution.execute(command);
        } catch (CicsAbend failure) {
            execution.recordAbend(failure.code());
            if (cancelHandlers) {
                throw failure;
            }
            Optional<CicsExecution.ConditionHandler> handler = execution.takeAbendHandler();
            if (handler.isEmpty()) {
                throw failure;
            }
            CicsExecution.ConditionHandler selected = handler.orElseThrow();
            if (selected.owner() != required.currentInvocationToken()) {
                throw new ProgramTargetTransfer(
                        selected.owner(), selected.target(), "CICS abend transfer");
            }
            return selected.target();
        }
        throw new CicsTaskStateException("ABEND command returned without terminating the task");
    }

    /** 現在のabend codeを4文字で返す。abend未発生時はCICS互換の空白4文字である。 */
    public static void assignAbcode(ProgramContext context, DataView target) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        DataView receiver = Objects.requireNonNull(target, "target");
        if (receiver.length() != 4) {
            throw new IllegalArgumentException("ASSIGN ABCODE target must be exactly 4 bytes");
        }
        CicsExecution execution = execution(required);
        String code = execution.currentAbendCode()
                .map(CicsAbendCode::value)
                .orElse("");
        receiver.setBytes(required.codePage().encode((code + "    ").substring(0, 4)));
        completeLocalCommand(required, ASSIGN_FUNCTION);
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
        execution.eib(required.codePage()).completeCommand(
                functionCode(command), outcome.responseCode(), outcome.responseCode2());
        return outcome;
    }

    private static void completeLocalCommand(ProgramContext context, int functionCode) {
        execution(context).eib(context.codePage()).completeCommand(
                functionCode, CicsResponseCode.NORMAL, 0);
    }

    private static int functionCode(CicsCommand command) {
        return switch (command) {
            case LinkCommand ignored -> LINK_FUNCTION;
            case XctlCommand ignored -> XCTL_FUNCTION;
            case ReturnCommand ignored -> RETURN_FUNCTION;
            case SyncpointCommand ignored -> SYNCPOINT_FUNCTION;
            case AbendCommand ignored -> throw new IllegalArgumentException(
                    "ABEND does not complete normally and must not update the EIB");
        };
    }

    private static CicsExecution execution(ProgramContext context) {
        return Objects.requireNonNull(context, "context").service(CicsExecution.class);
    }

    private static int conditionTarget(
            ProgramContext context, CicsCommandOutcome outcome,
            boolean suppressDefaultHandling, String command) {
        if (outcome.responseCode() == CicsResponseCode.NORMAL) {
            return NO_CONDITION_TRANSFER;
        }
        if (suppressDefaultHandling) {
            return NO_CONDITION_TRANSFER;
        }
        CicsExecution execution = execution(context);
        Optional<CicsExecution.ConditionHandler> handler =
                execution.conditionHandler(outcome.responseCode());
        if (handler.isPresent()) {
            CicsExecution.ConditionHandler selected = handler.orElseThrow();
            if (selected.target() == CicsExecution.IGNORE_CONDITION) {
                return NO_CONDITION_TRANSFER;
            }
            if (selected.target() == CicsExecution.DEFAULT_CONDITION) {
                throw defaultCondition(command, outcome);
            }
            if (selected.owner() != context.currentInvocationToken()) {
                throw new ProgramTargetTransfer(
                        selected.owner(), selected.target(), "CICS condition transfer");
            }
            return selected.target();
        }
        throw defaultCondition(command, outcome);
    }

    private static CicsTaskStateException defaultCondition(
            String command, CicsCommandOutcome outcome) {
        return new CicsTaskStateException(
                command + " failed with RESP=" + outcome.responseCode()
                        + " RESP2=" + outcome.responseCode2());
    }

    private static void requireNoLegacyTransfer(int target, String command) {
        if (target >= 0) {
            throw new CicsTaskStateException(command
                    + " selected a condition handler through the legacy runtime API");
        }
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
        if (outcome.responseCode() != CicsResponseCode.NORMAL) {
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
