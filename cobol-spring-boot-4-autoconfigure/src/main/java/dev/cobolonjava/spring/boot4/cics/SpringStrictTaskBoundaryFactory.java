package dev.cobolonjava.spring.boot4.cics;

import dev.cobolonjava.cics.CicsTaskBoundary;
import dev.cobolonjava.cics.CicsTaskBoundaryFactory;
import dev.cobolonjava.cics.CicsTaskCommitException;
import dev.cobolonjava.cics.CicsTaskContext;
import dev.cobolonjava.cics.CicsTaskServices;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.cics.CommitFailureState;
import dev.cobolonjava.cics.ConversationLease;
import dev.cobolonjava.cics.ConversationMutation;
import dev.cobolonjava.cics.ConversationMutationResult;
import dev.cobolonjava.cics.SyncpointAction;
import dev.cobolonjava.cics.TaskCommit;
import dev.cobolonjava.db2.Db2Execution;
import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.Db2ProfileMismatchException;
import dev.cobolonjava.db2.Db2TaskRuntime;
import dev.cobolonjava.db2.RollbackReason;
import dev.cobolonjava.db2.SqlExecutorPort;
import dev.cobolonjava.db2.UnitOfWorkOptions;
import dev.cobolonjava.db2.UnitOfWorkPort;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import dev.cobolonjava.spring.boot4.db2.SpringManagedSqlExecutor;
import dev.cobolonjava.spring.boot4.db2.SpringManagedUnitOfWorkPort;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.transaction.UnexpectedRollbackException;

/**
 * STRICT の task 境界 (設計 77 §4.6・§5.4、暫定判断 P-143)。
 *
 * <p>task ごとに SPRING_MANAGED の Db2 の UOW ({@link Db2TaskRuntime}) を持ち、生成 COBOL の EXEC SQL が同じ UOW へ
 * 届くよう {@link Db2Execution} を session に見せる。SYNCPOINT はその UOW を commit / rollback する。
 * task の終わりには、同じ UOW の中で会話の表と冪等キーの結果を更新してから commit するので、業務の更新と
 * 次の会話が一緒に確定するか、どちらも確定しない。
 */
public final class SpringStrictTaskBoundaryFactory implements CicsTaskBoundaryFactory {

    private final Supplier<UnitOfWorkPort> unitsOfWork;
    private final SqlExecutorPort sqlExecutor;
    private final JdbcConversationStore store;

    /**
     * @param unitsOfWork task ごとに新しい UOW port を返す (SPRING_MANAGED の port は prototype の bean)
     */
    public SpringStrictTaskBoundaryFactory(Supplier<UnitOfWorkPort> unitsOfWork, SqlExecutorPort sqlExecutor,
                                           JdbcConversationStore store) {
        this.unitsOfWork = Objects.requireNonNull(unitsOfWork, "unitsOfWork");
        this.sqlExecutor = Objects.requireNonNull(sqlExecutor, "sqlExecutor");
        this.store = Objects.requireNonNull(store, "store");
        if (sqlExecutor.profile() != Db2ExecutionProfile.SPRING_MANAGED) {
            throw new Db2ProfileMismatchException("STRICT conversation store requires the SPRING_MANAGED Db2 profile");
        }
        if (sqlExecutor instanceof SpringManagedSqlExecutor spring && spring.dataSource() != store.dataSource()) {
            throw new Db2ProfileMismatchException(
                    "STRICT conversation store and COBOL Db2 SQL must use the identical DataSource instance");
        }
    }

    @Override
    public CicsTaskBoundary open(CicsTaskContext task, CicsTransactionDefinition definition) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(definition, "definition");
        UnitOfWorkPort port = Objects.requireNonNull(unitsOfWork.get(), "UOW port");
        if (port instanceof SpringManagedUnitOfWorkPort spring && spring.dataSource() != store.dataSource()) {
            port.close();
            throw new Db2ProfileMismatchException(
                    "STRICT conversation store and the Db2 UOW must use the identical DataSource instance");
        }
        Db2TaskRuntime runtime = new Db2TaskRuntime(new UnitOfWorkOptions(Db2ExecutionProfile.SPRING_MANAGED,
                definition.taskTimeout(), false, false), port, sqlExecutor);
        return new Boundary(runtime);
    }

    private final class Boundary implements CicsTaskBoundary, CicsTaskServices {

        private final Db2TaskRuntime runtime;
        private final Db2Execution execution;
        private boolean finished;

        private Boundary(Db2TaskRuntime runtime) {
            this.runtime = runtime;
            this.execution = new Db2Execution(runtime);
        }

        @Override
        public void contribute(RuntimeServices.Builder services) {
            services.service(Db2Execution.class, execution);
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
                runtime.inUnitOfWork(() -> {
                    ConversationMutationResult result = switch (commit.conversation()) {
                        case ConversationMutation.None ignored -> null;
                        case ConversationMutation.Create create -> store.create(create.initial(), now);
                        case ConversationMutation.Save save -> store.save(save.lease(), save.next(), now);
                        case ConversationMutation.Complete complete -> store.complete(complete.lease(), now);
                    };
                    if (result != null && result != ConversationMutationResult.CREATED
                            && result != ConversationMutationResult.SAVED
                            && result != ConversationMutationResult.COMPLETED) {
                        throw new CicsTaskCommitException("conversation mutation failed: " + result,
                                CommitFailureState.NOT_COMMITTED, null);
                    }
                    commit.outcome().ifPresent(outcome -> store.record(outcome.owner(), outcome.key(),
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
                // commit の途中で失敗した。業務の更新と会話が確定したかは分からない
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
}
