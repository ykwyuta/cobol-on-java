package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.interop.CobolCallResult;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import dev.cobolonjava.runtime.interop.Termination;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.Objects;
import java.util.Optional;

/** 翻訳済みCOBOLを一つのsessionで起動しXCTLを反復するproduction program port。 */
public final class CobolCicsTaskProgram implements CicsTaskProgramPort {

    private final CobolRuntime runtime;
    private final int maxTransfers;
    private final CicsEnvironment environment;

    /** 例外を投げない後始末。 */
    private interface Release extends AutoCloseable {
        @Override
        void close();
    }

    public CobolCicsTaskProgram(CobolRuntime runtime, int maxTransfers) {
        this(runtime, maxTransfers, CicsEnvironment.unconfigured());
    }

    public CobolCicsTaskProgram(CobolRuntime runtime, int maxTransfers, CicsEnvironment environment) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        if (maxTransfers <= 0) {
            throw new IllegalArgumentException("maxTransfers must be positive");
        }
        this.maxTransfers = maxTransfers;
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public TaskCompletion execute(
            CicsTransactionDefinition definition,
            CicsPayload input,
            CicsTaskContext task,
            SyncpointPort syncpoints) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(syncpoints, "syncpoints");
        if (!task.transactionId().equals(definition.transId())) {
            throw new IllegalArgumentException(
                    "task TRANSID disagrees with transaction definition");
        }
        definition.validate(input);
        CicsExecution execution = new CicsExecution(task, input.commareaLength(), environment);
        execution.limitTo(task.startedAt().plus(definition.taskTimeout()));
        if (input.containerCount() > 0) {
            // 起動要求はchannelの名前を運ばないので、名前の分からない現在のchannelにする
            execution.openCurrentChannel(null, input.containers());
        }
        RuntimeServices services = RuntimeServices.builder()
                .service(CicsExecution.class, execution)
                .build();
        // 資源は session より先に返す。task がどう終わっても (ABEND や例外でも) 持ち越さない
        try (CobolSession session = runtime.openSession(services);
             Release ignored = () -> environment.enqueues().releaseTask(task.taskId())) {
            execution.bind(new DefaultCicsGateway(task, definition, session, syncpoints));
            ProgramId program = definition.initialProgram();
            CicsPayload payload = input;
            int transfers = 0;
            while (true) {
                Storage commarea = Storage.copyOf(payload.commarea());
                execution.startProgram(program.value());
                try {
                    CobolCallResult result = commarea.size() == 0
                            ? session.runMain(program.value())
                            : session.runMain(program.value(), commarea.whole());
                    if (result.termination() == Termination.STOP_RUN) {
                        throw new CicsTaskStateException(
                                "CICS program ended the execution unit with STOP RUN: "
                                        + program.value());
                    }
                    CicsPayload returned = new CicsPayload(commarea.array(),
                            execution.currentChannelContainers().orElse(payload.containers()));
                    // PUT CONTAINERで増えた分も、入力と同じ上限で断る
                    definition.validate(returned);
                    return new TaskCompletion(Optional.empty(), returned)
                            .withScreen(execution.terminalScreen());
                } catch (CicsProgramTransfer transfer) {
                    if (transfer.control() instanceof TaskCompletion completion) {
                        return completion.withScreen(execution.terminalScreen());
                    }
                    TransferControl control = (TransferControl) transfer.control();
                    if (++transfers > maxTransfers) {
                        throw new CicsTaskStateException(
                                "CICS XCTL transfer limit exceeded: " + maxTransfers);
                    }
                    program = control.target();
                    payload = control.payload();
                }
            }
        }
    }
}
