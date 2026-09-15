package dev.cobolonjava.spring.boot4.cics;

import dev.cobolonjava.cics.CicsTaskBoundary;
import dev.cobolonjava.cics.CicsTaskCommitException;
import dev.cobolonjava.cics.CicsTaskConnection;
import dev.cobolonjava.cics.CicsTaskContext;
import dev.cobolonjava.cics.CicsTaskServices;
import dev.cobolonjava.cics.CommitFailureState;
import dev.cobolonjava.cics.ConversationLease;
import dev.cobolonjava.cics.ConversationMutation;
import dev.cobolonjava.cics.ConversationMutationResult;
import dev.cobolonjava.cics.SyncpointAction;
import dev.cobolonjava.cics.TaskCommit;
import dev.cobolonjava.db2.Db2Execution;
import dev.cobolonjava.db2.Db2TaskRuntime;
import dev.cobolonjava.db2.RollbackReason;
import dev.cobolonjava.db2.UnitOfWork;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.transaction.UnexpectedRollbackException;

/**
 * STRICT の task 境界の本体。profile ごとの違いは、task の UOW からどの connection で会話の表を更新するか
 * ({@code writes}) だけである (設計 77 §4.6・§5.4、暫定判断 P-143)。
 *
 * <p>task の Db2 の UOW ({@link Db2TaskRuntime}) を {@link Db2Execution} として COBOL の session に見せる。SYNCPOINT は
 * その UOW を commit / rollback する。task の終わりには、同じ UOW の中で会話の表と冪等キーの結果を更新してから
 * commit するので、業務の更新と次の会話が一緒に確定するか、どちらも確定しない。
 */
final class StrictTaskBoundary implements CicsTaskBoundary, CicsTaskServices {

    private final Db2TaskRuntime runtime;
    private final Db2Execution execution;
    private final Function<UnitOfWork, JdbcConversationStore.TaskWrites> writes;
    private final JdbcConversationStore store;
    private final Function<UnitOfWork, Connection> connections;
    private final Consumer<Connection> release;
    private boolean finished;

    StrictTaskBoundary(Db2TaskRuntime runtime, Function<UnitOfWork, JdbcConversationStore.TaskWrites> writes,
                       JdbcConversationStore store) {
        this(runtime, writes, store, null, connection -> { });
    }

    /**
     * @param connections task の UOW の connection。null なら回復可能な一時データのキューに connection を見せない
     * @param release     connections で得た connection を返す (UOW の connection は閉じない)
     */
    StrictTaskBoundary(Db2TaskRuntime runtime, Function<UnitOfWork, JdbcConversationStore.TaskWrites> writes,
                       JdbcConversationStore store, Function<UnitOfWork, Connection> connections,
                       Consumer<Connection> release) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.execution = new Db2Execution(runtime);
        this.writes = Objects.requireNonNull(writes, "writes");
        this.store = Objects.requireNonNull(store, "store");
        this.connections = connections;
        this.release = Objects.requireNonNull(release, "release");
    }

    @Override
    public void contribute(RuntimeServices.Builder services) {
        services.service(Db2Execution.class, execution);
        if (connections != null) {
            // 回復可能な一時データのキューは、業務の SQL と同じ UOW で更新する (設計 85 §7.2、P-148)
            services.service(CicsTaskConnection.class, new CicsTaskConnection() {
                @Override
                public <R, T> T withResource(Class<R> type, Function<R, T> action) {
                    if (type != Connection.class) {
                        throw new IllegalArgumentException("the STRICT task boundary offers only java.sql.Connection: "
                                + type.getName());
                    }
                    AtomicReference<T> result = new AtomicReference<>();
                    runtime.withUnitOfWork(unit -> {
                        Connection connection = connections.apply(unit);
                        try {
                            result.set(action.apply(type.cast(connection)));
                        } finally {
                            release.accept(connection);
                        }
                    });
                    return result.get();
                }
            });
        }
    }

    @Override
    public void bind(CobolSession session) {
        execution.bind(session);
    }

    @Override
    public void syncpoint(SyncpointAction action, CicsTaskContext context) {
        if (action == SyncpointAction.COMMIT) {
            runtime.commit();
        } else {
            runtime.rollback(new RollbackReason(RollbackReason.Kind.EXPLICIT, "SYNCPOINT-ROLLBACK"));
        }
    }

    @Override
    public void commit(ConversationMutation conversation, Instant now) {
        commit(new TaskCommit(conversation, Optional.empty()), now);
    }

    @Override
    public void commit(TaskCommit commit, Instant now) {
        Objects.requireNonNull(commit, "commit");
        try {
            runtime.withUnitOfWork(unit -> {
                JdbcConversationStore.TaskWrites target = writes.apply(unit);
                ConversationMutationResult result = switch (commit.conversation()) {
                    case ConversationMutation.None ignored -> null;
                    case ConversationMutation.Create create -> target.create(create.initial(), now);
                    case ConversationMutation.Save save -> target.save(save.lease(), save.next(), now);
                    case ConversationMutation.Complete complete -> target.complete(complete.lease(), now);
                };
                if (result != null && result != ConversationMutationResult.CREATED
                        && result != ConversationMutationResult.SAVED
                        && result != ConversationMutationResult.COMPLETED) {
                    throw new CicsTaskCommitException("conversation mutation failed: " + result,
                            CommitFailureState.NOT_COMMITTED, null);
                }
                commit.outcome().ifPresent(outcome -> target.record(outcome.owner(), outcome.key(),
                        outcome.reply(), outcome.retainUntil(), now));
            });
        } catch (RuntimeException failure) {
            // commit の前に失敗したので、業務の更新も会話も確定していない
            rollbackQuietly(failure);
            if (failure instanceof CicsTaskCommitException commitFailure
                    && commitFailure.state() == CommitFailureState.NOT_COMMITTED) {
                throw commitFailure;
            }
            throw new CicsTaskCommitException("STRICT commit failed before the unit of work committed",
                    CommitFailureState.NOT_COMMITTED, failure);
        }
        finished = true;
        try {
            runtime.complete();
        } catch (UnexpectedRollbackException rolledBack) {
            throw new CicsTaskCommitException("the unit of work was rolled back at commit",
                    CommitFailureState.NOT_COMMITTED, rolledBack);
        } catch (RuntimeException unknown) {
            // commit の途中か、commit のあとの資源の解放で失敗した。業務の更新と会話が確定したかは分からない
            throw new CicsTaskCommitException("the unit of work commit failed with an unknown outcome",
                    CommitFailureState.UNKNOWN, unknown);
        }
    }

    @Override
    public void abort(Optional<ConversationLease> lease, Throwable failure, Instant now) {
        if (!finished) {
            finished = true;
            runtime.abort(new RollbackReason(RollbackReason.Kind.CICS_ABEND, "TASK-ABORT"));
        }
        lease.ifPresent(value -> store.release(value, now));
    }

    @Override
    public void close() {
        if (!finished) {
            finished = true;
            runtime.close();
        }
    }

    private void rollbackQuietly(Throwable failure) {
        finished = true;
        try {
            runtime.abort(new RollbackReason(RollbackReason.Kind.SQL_FAILURE, "STRICT-COMMIT"));
        } catch (RuntimeException | Error rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
