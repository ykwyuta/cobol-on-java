package dev.cobolonjava.cics;

import java.time.Instant;
import java.util.Optional;

/** UOWと会話変更を選択profileの整合性規則で確定するtask-scoped境界。 */
public interface CicsTaskBoundary extends SyncpointPort, AutoCloseable {

    /**
     * 会話変更と業務UOWをadapterの整合性profileに従って確定する。
     * 失敗時は必ず{@link CicsTaskCommitException}でNOT_COMMITTEDかUNKNOWNを明示する。
     */
    void commit(ConversationMutation conversation, Instant now);

    /**
     * 会話の変更と、再送に返すために覚える task の結果を確定する (暫定判断 P-142)。
     *
     * <p>結果を覚えられない境界の既定は、覚えるものがあれば何も確定せずに失敗させる。黙って落とすと、
     * 同じ冪等キーの再送が task をもう一度動かしてしまう。
     */
    default void commit(TaskCommit commit, Instant now) {
        if (commit.outcome().isPresent()) {
            throw new CicsTaskCommitException("this task boundary cannot record idempotent outcomes",
                    CommitFailureState.NOT_COMMITTED, null);
        }
        commit(commit.conversation(), now);
    }

    void abort(Optional<ConversationLease> lease, Throwable failure, Instant now);

    @Override
    void close();
}
