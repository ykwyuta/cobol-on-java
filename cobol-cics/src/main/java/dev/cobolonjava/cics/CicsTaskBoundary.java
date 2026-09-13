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

    void abort(Optional<ConversationLease> lease, Throwable failure, Instant now);

    @Override
    void close();
}
