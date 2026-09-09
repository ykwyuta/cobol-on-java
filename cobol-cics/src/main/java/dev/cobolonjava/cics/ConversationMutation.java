package dev.cobolonjava.cics;

import java.util.Objects;

/** task正常終了時にUOW境界と一緒に確定すべき会話変更。 */
public sealed interface ConversationMutation
        permits ConversationMutation.None, ConversationMutation.Create,
                ConversationMutation.Save, ConversationMutation.Complete {

    record None() implements ConversationMutation {
    }

    record Create(ConversationEnvelope initial) implements ConversationMutation {
        public Create {
            Objects.requireNonNull(initial, "initial");
        }
    }

    record Save(ConversationLease lease, ConversationEnvelope next)
            implements ConversationMutation {
        public Save {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(next, "next");
        }
    }

    record Complete(ConversationLease lease) implements ConversationMutation {
        public Complete {
            Objects.requireNonNull(lease, "lease");
        }
    }
}
