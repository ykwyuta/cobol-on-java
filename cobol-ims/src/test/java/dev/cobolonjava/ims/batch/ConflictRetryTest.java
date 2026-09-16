package dev.cobolonjava.ims.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.ims.store.DatabaseConflictException;
import dev.cobolonjava.runtime.abend.Abend;
import dev.cobolonjava.runtime.abend.AbendCode;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 競合したときの領域のやり直し (暫定判断 P-168、P-107)。 */
@Tag("V1")
class ConflictRetryTest {

    @AfterEach
    void clearLimit() {
        System.clearProperty(ConflictRetry.LIMIT);
    }

    @Test
    @DisplayName("競合したらやり直し、通ったところで止める")
    void aConflictIsRetriedUntilItPasses() {
        AtomicInteger attempts = new AtomicInteger();

        int returnCode = ConflictRetry.run(true, () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new DatabaseConflictException("another region committed root 0001 first");
            }
            return 4;
        });

        assertEquals(4, returnCode);
        assertEquals(3, attempts.get());
    }

    @Test
    @DisplayName("回数を使い切れば U0777 で落ちる。最後の競合を原因に残す")
    void exhaustedRetriesAbendWithU0777() {
        System.setProperty(ConflictRetry.LIMIT, "2");
        AtomicInteger attempts = new AtomicInteger();
        DatabaseConflictException conflict = new DatabaseConflictException("root 0001 again");

        ConflictRetriesExhaustedException exhausted = assertThrows(ConflictRetriesExhaustedException.class,
                () -> ConflictRetry.run(true, () -> {
                    attempts.incrementAndGet();
                    throw conflict;
                }));

        // 最初の 1 回と、やり直し 2 回
        assertEquals(3, attempts.get());
        assertEquals(AbendCode.U0777, exhausted.abendCode());
        assertEquals(AbendCode.U0777, Abend.codeOf(exhausted));
        assertSame(conflict, exhausted.getCause());
        assertTrue(exhausted.getMessage().contains("2"), exhausted.getMessage());
    }

    @Test
    @DisplayName("バッチはやり直さない。頭から流し直すと確定済みの分を二重に入れるからである")
    void aBatchRegionIsNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        DatabaseConflictException conflict = new DatabaseConflictException("root 0001");

        DatabaseConflictException thrown = assertThrows(DatabaseConflictException.class,
                () -> ConflictRetry.run(false, () -> {
                    attempts.incrementAndGet();
                    throw conflict;
                }));

        assertSame(conflict, thrown);
        assertEquals(1, attempts.get());
    }

    @Test
    @DisplayName("競合でない失敗はやり直さない。包まれた競合は見つける")
    void onlyConflictsAreRetried() {
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException other = assertThrows(IllegalStateException.class,
                () -> ConflictRetry.run(true, () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("something else");
                }));
        assertEquals("something else", other.getMessage());
        assertEquals(1, attempts.get());

        // 生成したプログラムの中から上がるときは包まれていることがある
        AtomicInteger wrapped = new AtomicInteger();
        int returnCode = ConflictRetry.run(true, () -> {
            if (wrapped.incrementAndGet() == 1) {
                throw new IllegalStateException("in the program",
                        new DatabaseConflictException("root 0001"));
            }
            return 0;
        });
        assertEquals(0, returnCode);
        assertEquals(2, wrapped.get());
    }

    @Test
    @DisplayName("回数は設定で変えられる。数でなければ断る")
    void theLimitIsConfigured() {
        assertEquals(3, ConflictRetry.limit());

        System.setProperty(ConflictRetry.LIMIT, "0");
        assertEquals(0, ConflictRetry.limit());

        System.setProperty(ConflictRetry.LIMIT, "three");
        ImsBatchException refused = assertThrows(ImsBatchException.class, ConflictRetry::limit);
        assertTrue(refused.getMessage().contains("three"), refused.getMessage());
    }
}
