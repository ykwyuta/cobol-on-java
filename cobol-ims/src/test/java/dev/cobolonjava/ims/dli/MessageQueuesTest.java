package dev.cobolonjava.ims.dli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePages;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 電文のキューの差し込み (暫定判断 P-165)。 */
@Tag("V1")
class MessageQueuesTest {

    @Test
    @DisplayName("差し込みが構成されていなければ null を返す。呼ぶ側はメモリのキューを使う")
    void noProviderMeansNoQueue() {
        // cobol-ims だけの classpath には差し込みが無い (JMS は cobol-ims-jms が置く)
        assertNull(MessageQueues.open("IBLOGIN1"));
    }

    @Test
    @DisplayName("メモリのキューは入力を積める。中立の口では、積めないキューは false を返す")
    void onlyQueuesThatCanTakeInputSaySo() {
        InputMessage message = new InputMessage("LTERM001",
                List.of(CodePages.DEFAULT.encode("IBLOGIN1")));

        InMemoryMessageQueue memory = new InMemoryMessageQueue();
        assertTrue(memory.enqueue(message));
        assertTrue(memory.next() != null);

        // 既定の実装は積む手立てを持たない
        MessageQueue readOnly = new MessageQueue() {
            @Override
            public InputMessage next() {
                return null;
            }

            @Override
            public void send(OutputMessage out) {
            }
        };
        assertFalse(readOnly.enqueue(message));
    }
}
