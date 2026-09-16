package dev.cobolonjava.ims.rdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.ims.store.DatabaseStoreException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 処理済みの電文の ID の刈り取り (暫定判断 P-163)。 */
@Tag("V1")
class JdbcInboxRetentionTest {

    private final String url = "jdbc:h2:mem:retention-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    @AfterEach
    void clearRetention() {
        System.clearProperty(JdbcDatabaseStore.INBOX_RETENTION_DAYS);
    }

    private JdbcDatabaseStore store() throws SQLException {
        return new JdbcDatabaseStore(DriverManager.getConnection(url, "sa", ""));
    }

    /** 覚えた ID の日付を過去へずらす。ブローカが長く持っていた電文を作れないので、表の側で古くする。 */
    private void backdate(String messageId, Duration age) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE IMS_MESSAGE_INBOX SET RECORDED_AT = ? WHERE MESSAGE_ID = ?")) {
            update.setTimestamp(1, Timestamp.from(Instant.now().minus(age)));
            update.setString(2, messageId);
            assertEquals(1, update.executeUpdate());
        }
    }

    private int rows() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM IMS_MESSAGE_INBOX");
             ResultSet result = count.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private void record(String... messageIds) throws SQLException {
        try (JdbcDatabaseStore store = store()) {
            for (String messageId : messageIds) {
                store.inbox().record(messageId);
            }
            store.commit(List.of());
        }
    }

    @Test
    @DisplayName("保持期間を過ぎた ID は、置き場を開くときに落ちる。新しい ID は残る")
    void oldIdsArePrunedWhenTheStoreOpens() throws SQLException {
        record("ID:old", "ID:new");
        backdate("ID:old", Duration.ofDays(8));
        assertEquals(2, rows());

        try (JdbcDatabaseStore store = store()) {
            // 既定は 7 日である
            assertFalse(store.inbox().seen("ID:old"));
            assertTrue(store.inbox().seen("ID:new"));
        }
        assertEquals(1, rows());
    }

    @Test
    @DisplayName("保持期間は設定で変えられる。0 以下なら刈らない")
    void theRetentionIsConfigured() throws SQLException {
        record("ID:old");
        backdate("ID:old", Duration.ofDays(8));

        // 刈らない設定では、古い ID もそのまま残る
        System.setProperty(JdbcDatabaseStore.INBOX_RETENTION_DAYS, "0");
        try (JdbcDatabaseStore store = store()) {
            assertTrue(store.inbox().seen("ID:old"));
        }
        assertEquals(1, rows());

        // 短くすれば落ちる
        System.setProperty(JdbcDatabaseStore.INBOX_RETENTION_DAYS, "1");
        try (JdbcDatabaseStore store = store()) {
            assertFalse(store.inbox().seen("ID:old"));
        }
        assertEquals(0, rows());
    }

    @Test
    @DisplayName("日数でない設定は断る。黙って既定へ落とさない")
    void anUnreadableRetentionIsRefused() {
        System.setProperty(JdbcDatabaseStore.INBOX_RETENTION_DAYS, "a week");
        DatabaseStoreException refused = assertThrows(DatabaseStoreException.class, this::store);
        assertTrue(refused.getMessage().contains("a week"), refused.getMessage());
    }
}
