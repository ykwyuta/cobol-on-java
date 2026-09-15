package dev.cobolonjava.spring.boot4.cics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.cobolonjava.cics.CicsResponseCode;
import dev.cobolonjava.cics.CicsTransientDataPort;
import dev.cobolonjava.cics.CicsTransientDataQueueDefinition;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** 一時データのキューの JDBC の置き場 (設計 83 §8、暫定判断 P-144)。 */
@Tag("V1")
class JdbcCicsTransientDataTest {

    private static final List<CicsTransientDataQueueDefinition> QUEUES =
            List.of(new CicsTransientDataQueueDefinition("CSMT", 6));

    /** 同じ DataSource に向けた 2 つのキュー。2 つの JVM に見立てる。 */
    private JdbcCicsTransientData first;
    private JdbcCicsTransientData second;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:td-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new ResourceDatabasePopulator(new ClassPathResource(JdbcConversationStore.SCHEMA)).execute(dataSource);
        first = new JdbcCicsTransientData(dataSource, new JdbcTransactionManager(dataSource), QUEUES);
        second = new JdbcCicsTransientData(dataSource, new JdbcTransactionManager(dataSource), QUEUES);
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static String read(CicsTransientDataPort queue) {
        CicsTransientDataPort.Read read = queue.read("CSMT");
        return read.response() == CicsResponseCode.NORMAL ? new String(read.data(), StandardCharsets.US_ASCII) : null;
    }

    @Test
    @DisplayName("2つのJVMが書いたrecordを先に書いた順に取り出し、空はQZERO、定義の無いキューはQIDERR、長すぎればLENGERR")
    void sharesQueueAcrossJvmsInWriteOrder() {
        assertEquals(CicsResponseCode.NORMAL, first.write("CSMT", text("one")).response());
        assertEquals(CicsResponseCode.NORMAL, second.write("CSMT", text("second")).response());
        assertEquals(CicsResponseCode.LENGERR, first.write("CSMT", text("toolong")).response());
        assertEquals(CicsResponseCode.QIDERR, first.write("NOPE", text("one")).response());

        assertEquals("one", read(second));
        assertEquals("second", read(first));
        assertEquals(CicsResponseCode.QZERO, first.read("CSMT").response());

        second.write("CSMT", text("again"));
        assertEquals(CicsResponseCode.NORMAL, first.delete("CSMT").response());
        assertEquals(CicsResponseCode.QZERO, second.read("CSMT").response());
        assertEquals(CicsResponseCode.QIDERR, second.delete("NOPE").response());
    }

    @Test
    @DisplayName("2つのJVMの4つのtaskが同時に読んでも、どのrecordも1度だけ取り出す")
    void readsEachRecordOnceUnderConcurrency() throws Exception {
        List<String> written = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String value = String.format("R%03d", i);
            written.add(value);
            (i % 2 == 0 ? first : second).write("CSMT", text(value));
        }
        ConcurrentLinkedQueue<String> taken = new ConcurrentLinkedQueue<>();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> readers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                CicsTransientDataPort queue = i % 2 == 0 ? first : second;
                readers.add(executor.submit(() -> {
                    go.await();
                    for (String value = read(queue); value != null; value = read(queue)) {
                        taken.add(value);
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> reader : readers) {
                reader.get();
            }
        } finally {
            executor.shutdownNow();
        }
        List<String> sorted = new ArrayList<>(taken);
        sorted.sort(null);
        assertEquals(written, sorted);
    }
}
