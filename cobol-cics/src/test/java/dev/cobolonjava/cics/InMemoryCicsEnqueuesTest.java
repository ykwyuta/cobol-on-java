package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** ENQ / DEQ の資源の排他。 */
@Tag("V1")
class InMemoryCicsEnqueuesTest {

    private static final CicsTaskId A = new CicsTaskId("task_a");
    private static final CicsTaskId B = new CicsTaskId("task_b");
    private static final byte[] RESOURCE = "NAMEDCOUNTER0001".getBytes(StandardCharsets.US_ASCII);

    private final CicsEnqueuePort enqueues = CicsEnqueuePort.inMemory();

    @Test
    @DisplayName("他のtaskが持つ資源は、待たなければENQBUSY、待てば返されたときに得る")
    void waitsForTheHolder() throws Exception {
        assertTrue(enqueues.enqueue(A, RESOURCE, false, true, null));
        assertTrue(enqueues.enqueue(A, RESOURCE, false, true, null), "同じtaskは重ねて得る");
        assertFalse(enqueues.enqueue(B, RESOURCE, false, false, null));

        CompletableFuture<Boolean> waiting = CompletableFuture.supplyAsync(
                () -> enqueues.enqueue(B, RESOURCE, false, true, Duration.ofSeconds(5)));
        enqueues.dequeue(A, RESOURCE, false);
        assertFalse(waiting.isDone() && waiting.get(), "1 回返しただけではまだ持っている");
        enqueues.dequeue(A, RESOURCE, false);

        assertTrue(waiting.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("SYNCPOINTはUOWの資源だけを、taskの終わりはすべてを返す。待ちは期限を越えない")
    void releasesByScope() {
        byte[] other = "OTHERRESOURCE".getBytes(StandardCharsets.US_ASCII);
        enqueues.enqueue(A, RESOURCE, false, true, null);
        enqueues.enqueue(A, other, true, true, null);

        enqueues.releaseUnitOfWork(A);
        assertTrue(enqueues.enqueue(B, RESOURCE, false, false, null));
        assertFalse(enqueues.enqueue(B, other, false, false, null));
        assertThrows(CicsTaskStateException.class,
                () -> enqueues.enqueue(B, other, false, true, Duration.ofMillis(20)));

        enqueues.releaseTask(A);
        assertTrue(enqueues.enqueue(B, other, false, false, null));
    }
}
