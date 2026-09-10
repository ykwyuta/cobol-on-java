package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.interop.CobolCallResult;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.Termination;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.Objects;

/** 同じ同期threadとCobolSessionでCICS制御commandを解釈する標準gateway。 */
public final class DefaultCicsGateway implements CicsGateway {

    public static final int NORMAL_RESPONSE = 0;

    private final CicsTaskContext task;
    private final CicsTransactionDefinition definition;
    private final CobolSession session;
    private final SyncpointPort syncpoints;
    private final Thread owner;

    public DefaultCicsGateway(
            CicsTaskContext task,
            CicsTransactionDefinition definition,
            CobolSession session,
            SyncpointPort syncpoints) {
        this.task = Objects.requireNonNull(task, "task");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.session = Objects.requireNonNull(session, "session");
        this.syncpoints = Objects.requireNonNull(syncpoints, "syncpoints");
        if (!task.transactionId().equals(definition.transId())) {
            throw new IllegalArgumentException("task TRANSID disagrees with transaction definition");
        }
        this.owner = Thread.currentThread();
    }

    @Override
    public CicsCommandOutcome execute(CicsCommand command, CicsTaskContext currentTask) {
        Objects.requireNonNull(command, "command");
        requireOwner(currentTask);
        return switch (command) {
            case LinkCommand link -> executeLink(link);
            case XctlCommand xctl -> {
                definition.validate(xctl.payload());
                yield normal(new TransferControl(xctl.target(), xctl.payload()));
            }
            case ReturnCommand returned -> {
                definition.validate(returned.payload());
                yield normal(new TaskCompletion(
                        returned.nextTransaction(), returned.payload()));
            }
            case SyncpointCommand syncpoint -> {
                syncpoints.syncpoint(syncpoint.action(), task);
                yield normal(new SyncpointCompletion(syncpoint.action()));
            }
            case AbendCommand abend -> throw new CicsAbend(task.taskId(), abend);
        };
    }

    private CicsCommandOutcome executeLink(LinkCommand link) {
        definition.validate(link.payload());
        Storage commarea = Storage.copyOf(link.payload().commarea());
        CobolCallResult result;
        if (commarea.size() == 0) {
            result = session.call(link.target().value());
        } else {
            DataView argument = commarea.whole();
            result = session.call(link.target().value(), argument);
        }
        if (result.termination() == Termination.STOP_RUN) {
            throw new CicsTaskStateException(
                    "LINK target ended the execution unit with STOP RUN: "
                            + link.target().value());
        }
        CicsPayload returned = new CicsPayload(commarea.array(), link.payload().containers());
        return normal(new ContinueControl(returned));
    }

    private void requireOwner(CicsTaskContext currentTask) {
        Objects.requireNonNull(currentTask, "currentTask");
        if (!task.equals(currentTask)) {
            throw new CicsTaskStateException("CICS gateway belongs to another task");
        }
        if (Thread.currentThread() != owner) {
            throw new CicsTaskStateException(
                    "CICS task belongs to thread " + owner.getName());
        }
    }

    private static CicsCommandOutcome normal(CicsControl control) {
        return new CicsCommandOutcome(NORMAL_RESPONSE, 0, control);
    }
}
