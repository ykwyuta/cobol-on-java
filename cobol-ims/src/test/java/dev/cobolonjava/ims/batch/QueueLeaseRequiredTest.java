package dev.cobolonjava.ims.batch;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dli.InMemoryMessageQueue;
import dev.cobolonjava.ims.dli.InputMessage;
import dev.cobolonjava.ims.dli.MessageQueue;
import dev.cobolonjava.ims.dli.OutputMessage;
import dev.cobolonjava.ims.store.DatabaseStore;
import dev.cobolonjava.ims.store.QueueLease;
import dev.cobolonjava.ims.store.QueueLeaseException;
import java.util.Collection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 借用を持てない置き場で、ブローカのキューを読む領域を起こすかどうか (暫定判断 P-167)。 */
@Tag("V1")
class QueueLeaseRequiredTest {

    /** 借用を持たない置き場 (データセットの置き場と同じ立場)。 */
    private static final DatabaseStore WITHOUT_LEASE = new DatabaseStore() {
        @Override
        public HierarchicalDatabase open(DatabaseDefinition dbd) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void commit(Collection<HierarchicalDatabase> databases) {
        }

        @Override
        public void close() {
        }
    };

    /** 取引コードを名乗るキュー (ブローカの立場)。 */
    private static final MessageQueue NAMED = new MessageQueue() {
        @Override
        public String transactionCode() {
            return "IBLOGIN1";
        }

        @Override
        public InputMessage next() {
            return null;
        }

        @Override
        public void send(OutputMessage message) {
        }
    };

    @AfterEach
    void clearProperty() {
        System.clearProperty(ImsProgramRunner.LEASE_REQUIRED);
    }

    @Test
    @DisplayName("取引コードを名乗るキューなのに借用を持てない置き場なら、既定では起こさない")
    void anUnprotectedRegionIsRefusedByDefault() {
        QueueLeaseException refused = assertThrows(QueueLeaseException.class,
                () -> ImsProgramRunner.acquireLease(WITHOUT_LEASE, NAMED));

        assertTrue(refused.getMessage().contains("IBLOGIN1"), refused.getMessage());
        // 何をすればよいかを言う
        assertTrue(refused.getMessage().contains("cobol.ims.jdbc.url"), refused.getMessage());
        assertTrue(refused.getMessage().contains(ImsProgramRunner.LEASE_REQUIRED), refused.getMessage());
    }

    @Test
    @DisplayName("1 領域しか動かさないと分かっているなら、設定で降りられる")
    void theLeaseCanBeWaived() {
        System.setProperty(ImsProgramRunner.LEASE_REQUIRED, "false");

        try (QueueLease.Held held = ImsProgramRunner.acquireLease(WITHOUT_LEASE, NAMED)) {
            assertNotNull(held);
        }
    }

    @Test
    @DisplayName("バッチと、1 つの JVM の中のキューは、そもそも二重にならないので断らない")
    void regionsThatCannotBeStartedTwiceAreNotRefused() {
        // バッチ (キューが無い)
        try (QueueLease.Held batch = ImsProgramRunner.acquireLease(WITHOUT_LEASE, null)) {
            assertNotNull(batch);
        }
        // 取引コードを名乗らないキュー (1 つの JVM の中)
        try (QueueLease.Held memory = ImsProgramRunner.acquireLease(WITHOUT_LEASE, new InMemoryMessageQueue())) {
            assertNotNull(memory);
        }
    }
}
