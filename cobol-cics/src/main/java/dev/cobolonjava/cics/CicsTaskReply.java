package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.Optional;

/** transport adapterがHTTP応答またはBMS viewへ変換する正常task結果。 */
public record CicsTaskReply(
        CicsTaskId taskId,
        TransId transactionId,
        CicsPayload payload,
        Optional<ConversationEnvelope> nextConversation) {

    public CicsTaskReply {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(nextConversation, "nextConversation");
    }
}
