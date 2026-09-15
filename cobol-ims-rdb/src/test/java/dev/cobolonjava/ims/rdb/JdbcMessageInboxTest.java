package dev.cobolonjava.ims.rdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.DbdParser;
import dev.cobolonjava.ims.store.DatabaseConflictException;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 処理済みの電文を、業務の更新と同じトランザクションで書く (ADR-0014 の決定 2、暫定判断 P-163)。 */
@Tag("V1")
class JdbcMessageInboxTest {

    private static final CodePage EBCDIC = CodePages.DEFAULT;

    private static final DatabaseDefinition DBD = DbdParser.parse(String.join("\n",
            card("         DBD   NAME=BANKDB,ACCESS=HDAM"),
            card("         SEGM  NAME=CUST,PARENT=0,BYTES=4"),
            card("         FIELD NAME=(CUSTNO,SEQ,U),BYTES=4,START=1"),
            card("         DBDGEN")) + "\n");

    private final String url = "jdbc:h2:mem:inbox-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    private static String card(String text) {
        return text + " ".repeat(72 - text.length());
    }

    private JdbcDatabaseStore store() throws SQLException {
        return new JdbcDatabaseStore(DriverManager.getConnection(url, "sa", ""));
    }

    private int inboxRows() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM IMS_MESSAGE_INBOX");
             ResultSet result = count.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    @Test
    @DisplayName("処理済みの電文は確定で書かれ、置き場を開き直しても覚えている")
    void processedMessagesAreRecordedWithTheUpdate() throws SQLException {
        try (JdbcDatabaseStore store = store()) {
            HierarchicalDatabase database = store.open(DBD);
            assertFalse(store.inbox().seen("M1"));

            database.insert(null, DBD.root(), EBCDIC.encode("0001"));
            store.inbox().record("M1");
            store.commit(List.of(database));

            assertTrue(store.inbox().seen("M1"));
        }
        assertEquals(1, inboxRows());
        try (JdbcDatabaseStore store = store()) {
            assertTrue(store.inbox().seen("M1"));
            assertFalse(store.inbox().seen("M2"));
            assertEquals(1, store.open(DBD).roots().size());
        }
    }

    @Test
    @DisplayName("確定が競合で失敗すれば、業務の更新も処理済みも書かれない")
    void aFailedCommitRecordsNothing() throws SQLException {
        try (JdbcDatabaseStore first = store(); JdbcDatabaseStore second = store()) {
            HierarchicalDatabase one = first.open(DBD);
            HierarchicalDatabase two = second.open(DBD);

            one.insert(null, DBD.root(), EBCDIC.encode("0001"));
            first.commit(List.of(one));

            // second は同じ根を読んだときの版のまま更新しようとする
            two.insert(null, DBD.root(), EBCDIC.encode("0001"));
            second.inbox().record("M9");
            assertThrows(DatabaseConflictException.class, () -> second.commit(List.of(two)));

            assertFalse(second.inbox().seen("M9"));
        }
        assertEquals(0, inboxRows());
    }
}
