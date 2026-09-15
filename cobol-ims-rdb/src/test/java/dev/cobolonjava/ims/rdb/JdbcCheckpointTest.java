package dev.cobolonjava.ims.rdb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

/** 記号 CHKP が退避した域を、業務の更新と同じ確定で書く (暫定判断 P-164)。 */
@Tag("V1")
class JdbcCheckpointTest {

    private static final CodePage EBCDIC = CodePages.DEFAULT;

    private static final DatabaseDefinition DBD = DbdParser.parse(String.join("\n",
            card("         DBD   NAME=BANKDB,ACCESS=HDAM"),
            card("         SEGM  NAME=CUST,PARENT=0,BYTES=4"),
            card("         FIELD NAME=(CUSTNO,SEQ,U),BYTES=4,START=1"),
            card("         DBDGEN")) + "\n");

    private final String url = "jdbc:h2:mem:checkpoint-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    private static String card(String text) {
        return text + " ".repeat(72 - text.length());
    }

    private JdbcDatabaseStore store() throws SQLException {
        return new JdbcDatabaseStore(DriverManager.getConnection(url, "sa", ""));
    }

    private int checkpointRows() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM IMS_CHECKPOINT");
             ResultSet result = count.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    @Test
    @DisplayName("検査点は確定で書かれ、置き場を開き直しても読める。確定の前は読めない")
    void checkpointsAreWrittenWithTheUpdate() throws SQLException {
        try (JdbcDatabaseStore store = store()) {
            HierarchicalDatabase database = store.open(DBD);
            database.insert(null, DBD.root(), EBCDIC.encode("0001"));
            store.checkpoints().record("BANKPSB", "CHKP0001",
                    List.of(EBCDIC.encode("00000042"), EBCDIC.encode("0001")));

            // 確定の前は、表にまだ無い
            assertEquals(0, checkpointRows());

            store.commit(List.of(database));
            assertEquals(2, checkpointRows());
        }
        try (JdbcDatabaseStore store = store()) {
            List<byte[]> areas = store.checkpoints().load("BANKPSB", "CHKP0001");
            assertEquals(2, areas.size());
            assertArrayEquals(EBCDIC.encode("00000042"), areas.get(0));
            assertArrayEquals(EBCDIC.encode("0001"), areas.get(1));
            assertNull(store.checkpoints().load("BANKPSB", "CHKP0009"));
            assertNull(store.checkpoints().load("OTHERPSB", "CHKP0001"));
        }
    }

    @Test
    @DisplayName("確定が競合で失敗すれば、業務の更新も検査点も書かれない")
    void aFailedCommitWritesNoCheckpoint() throws SQLException {
        try (JdbcDatabaseStore first = store(); JdbcDatabaseStore second = store()) {
            HierarchicalDatabase one = first.open(DBD);
            HierarchicalDatabase two = second.open(DBD);

            one.insert(null, DBD.root(), EBCDIC.encode("0001"));
            first.commit(List.of(one));

            two.insert(null, DBD.root(), EBCDIC.encode("0001"));
            second.checkpoints().record("BANKPSB", "CHKP0002", List.of(EBCDIC.encode("99999999")));
            assertThrows(DatabaseConflictException.class, () -> second.commit(List.of(two)));
        }
        assertEquals(0, checkpointRows());
        try (JdbcDatabaseStore store = store()) {
            assertNull(store.checkpoints().load("BANKPSB", "CHKP0002"));
        }
    }

    @Test
    @DisplayName("同じ ID の検査点は置き換わる。域の数が減っても前の域は残らない")
    void theSameIdIsReplaced() throws SQLException {
        try (JdbcDatabaseStore store = store()) {
            HierarchicalDatabase database = store.open(DBD);
            database.insert(null, DBD.root(), EBCDIC.encode("0001"));
            store.checkpoints().record("BANKPSB", "CHKP0001",
                    List.of(EBCDIC.encode("00000042"), EBCDIC.encode("0001")));
            store.commit(List.of(database));

            database.insert(null, DBD.root(), EBCDIC.encode("0002"));
            store.checkpoints().record("BANKPSB", "CHKP0001", List.of(EBCDIC.encode("00000099")));
            store.commit(List.of(database));

            List<byte[]> areas = store.checkpoints().load("BANKPSB", "CHKP0001");
            assertEquals(1, areas.size());
            assertArrayEquals(EBCDIC.encode("00000099"), areas.get(0));
        }
        assertEquals(1, checkpointRows());
    }
}
