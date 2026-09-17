package dev.cobolonjava.ims.rdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.ims.store.DatabaseConflictException;
import dev.cobolonjava.ims.store.QueueLease;
import dev.cobolonjava.ims.store.QueueLeaseException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 取引コードのキューを読む領域を 1 つに限る借用 (暫定判断 P-167、P-105 の解消条件)。 */
@Tag("V1")
class JdbcQueueLeaseTest {

    private static final String EXPIRY = "cobol.ims.queue.lease-seconds";

    private final String url = "jdbc:h2:mem:lease-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    @AfterEach
    void clearExpiry() {
        System.clearProperty(EXPIRY);
    }

    private JdbcDatabaseStore store() throws SQLException {
        return new JdbcDatabaseStore(DriverManager.getConnection(url, "sa", ""));
    }

    private int leaseRows() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM IMS_QUEUE_LEASE");
             ResultSet result = count.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    @Test
    @DisplayName("同じ取引コードを 2 つ目の領域は借りられない。返せば借りられる")
    void aSecondRegionIsRefused() throws SQLException {
        try (JdbcDatabaseStore first = store(); JdbcDatabaseStore second = store()) {
            QueueLease.Held held = first.queueLease().acquire("IBLOGIN1", "region-A");

            QueueLeaseException refused = assertThrows(QueueLeaseException.class,
                    () -> second.queueLease().acquire("IBLOGIN1", "region-B"));
            assertTrue(refused.getMessage().contains("region-A"), refused.getMessage());
            assertTrue(refused.getMessage().contains("IBLOGIN1"), refused.getMessage());

            held.close();
            assertEquals(0, leaseRows());

            try (QueueLease.Held after = second.queueLease().acquire("IBLOGIN1", "region-B")) {
                assertEquals(1, leaseRows());
                assertTrue(after != null);
            }
        }
        assertEquals(0, leaseRows());
    }

    @Test
    @DisplayName("取引コードが違えば同時に借りられる")
    void differentTransactionCodesDoNotCollide() throws SQLException {
        try (JdbcDatabaseStore first = store(); JdbcDatabaseStore second = store();
             QueueLease.Held one = first.queueLease().acquire("IBLOGIN1", "region-A");
             QueueLease.Held two = second.queueLease().acquire("IBACSUM", "region-B")) {
            assertTrue(one != two);
            assertEquals(2, leaseRows());
        }
        assertEquals(0, leaseRows());
    }

    @Test
    @DisplayName("心拍が古ければ引き継げる。引き継がれた領域は同期点で競合として止まる")
    void aStaleLeaseIsTakenOverAndTheOldRegionStops() throws SQLException {
        try (JdbcDatabaseStore first = store(); JdbcDatabaseStore second = store()) {
            first.queueLease().acquire("IBLOGIN1", "region-A");

            // 落ちた領域の借用を誰も返せないので、古くなれば引き継げなければならない
            System.setProperty(EXPIRY, "0");
            second.queueLease().acquire("IBLOGIN1", "region-B");
            assertEquals(1, leaseRows());

            // 引き継がれた側は、次の同期点で気づいて止まる (黙って読み続けない)
            DatabaseConflictException taken = assertThrows(DatabaseConflictException.class,
                    () -> first.commit(List.of()));
            assertTrue(taken.getMessage().contains("IBLOGIN1"), taken.getMessage());
        }
    }
}
