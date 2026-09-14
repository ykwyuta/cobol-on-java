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

    public NonRecoverableTaskBoundaryFactory(ConversationStorePort conversations) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
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
