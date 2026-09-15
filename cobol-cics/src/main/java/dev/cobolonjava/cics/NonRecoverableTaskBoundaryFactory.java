package dev.cobolonjava.cics;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 回復可能な資源を持たない task の境界 (暫定判断 P-134)。
 *
 * <p>業務 UOW を持たないので、確定するのは会話の変更だけである。SYNCPOINT は確定する資源が無いので何もしない。
 * Db2 など回復可能な資源を使う transaction にはこの境界を使わず、UOW を持つ adapter の境界を使う。
 */
public final class NonRecoverableTaskBoundaryFactory implements CicsTaskBoundaryFactory {

    private final ConversationStorePort conversations;
    private final Optional<CicsOutcomeStorePort> outcomes;

    public NonRecoverableTaskBoundaryFactory(ConversationStorePort conversations) {
        this(conversations, Optional.empty());
    }

    /** 冪等キーの結果も覚える境界 (暫定判断 P-142)。 */
    public NonRecoverableTaskBoundaryFactory(ConversationStorePort conversations, CicsOutcomeStorePort outcomes) {
        this(conversations, Optional.of(outcomes));
    }

    private NonRecoverableTaskBoundaryFactory(ConversationStorePort conversations,
                                              Optional<CicsOutcomeStorePort> outcomes) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
    }

    @Override
    public CicsTaskBoundary open(CicsTaskContext task, CicsTransactionDefinition definition) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(definition, "definition");
        return new CicsTaskBoundary() {
            @Override
            public void commit(ConversationMutation mutation, Instant now) {
                ConversationMutationResult result = switch (Objects.requireNonNull(mutation, "mutation")) {
                    case ConversationMutation.None ignored -> null;
                    case ConversationMutation.Create create -> conversations.create(create.initial(), now);
                    case ConversationMutation.Save save -> conversations.save(save.lease(), save.next(), now);
                    case ConversationMutation.Complete complete -> conversations.complete(complete.lease(), now);
                };
                if (result != null && result != ConversationMutationResult.CREATED
                        && result != ConversationMutationResult.SAVED
                        && result != ConversationMutationResult.COMPLETED) {
                    // 会話の変更だけが確定の対象なので、失敗すれば何も確定していない
                    throw new CicsTaskCommitException("conversation mutation failed: " + result,
                            CommitFailureState.NOT_COMMITTED, null);
                }
            }

            @Override
            public void commit(TaskCommit commit, Instant now) {
                if (commit.outcome().isPresent() && outcomes.isEmpty()) {
                    throw new CicsTaskCommitException("this task boundary has no idempotent outcome store",
                            CommitFailureState.NOT_COMMITTED, null);
                }
                commit(commit.conversation(), now);
                commit.outcome().ifPresent(outcome -> {
                    try {
                        outcomes.orElseThrow().record(outcome.owner(), outcome.key(), outcome.reply(),
                                outcome.retainUntil(), now);
                    } catch (RuntimeException failure) {
                        // 会話の変更はもう確定しているので、結果を覚えられなければ確定の状態は分からない
                        throw new CicsTaskCommitException("idempotent outcome could not be recorded",
                                CommitFailureState.UNKNOWN, failure);
                    }
                });
            }

            @Override
            public void abort(Optional<ConversationLease> lease, Throwable failure, Instant now) {
                lease.ifPresent(value -> conversations.release(value, now));
            }

            @Override
            public void syncpoint(SyncpointAction action, CicsTaskContext context) {
                // 回復可能な資源が無いので、COMMIT も ROLLBACK も確定・取消の対象を持たない
            }

            @Override
            public void close() {
            }
        };
    }
}
