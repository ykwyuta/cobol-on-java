package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.Optional;

/**
 * transport adapterがHTTP応答またはBMS viewへ変換する正常task結果。
 *
 * @param immediateNext 次の会話taskを端末入力を待たずに始めるべきか。始めるのはadapterの責務であり、
 *                      coordinatorは同じ要求の中で次のprogramを起動しない
 */
public record CicsTaskReply(
        CicsTaskId taskId,
        TransId transactionId,
        CicsPayload payload,
        Optional<ConversationEnvelope> nextConversation,
        boolean immediateNext) {

    public CicsTaskReply {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(nextConversation, "nextConversation");
        if (immediateNext && nextConversation.isEmpty()) {
            throw new IllegalArgumentException("an immediate reply requires a next conversation");
        }
    }

    public CicsTaskReply(CicsTaskId taskId, TransId transactionId, CicsPayload payload,
                         Optional<ConversationEnvelope> nextConversation) {
        this(taskId, transactionId, payload, nextConversation, false);
    }
}
