package dev.cobolonjava.ims.rdb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.db.Segment;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.DbdParser;
import dev.cobolonjava.ims.dli.ImsRegion;
import dev.cobolonjava.ims.psb.PsbParser;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** RDB の置き場 (設計 78 §3.2、ADR-0013、暫定判断 P-160)。H2 のメモリの上の DB で確かめる。 */
@Tag("V1")
class JdbcDatabaseStoreTest {

    private static final CodePage EBCDIC = CodePages.DEFAULT;

    /** CUST (キー 4) の下に、キーが重なって FIRST の ACCT と、キーを持たない可変長の MEMO。 */
    private static final DatabaseDefinition DBD = DbdParser.parse(String.join("\n",
            card("         DBD   NAME=BANKDB,ACCESS=HIDAM"),
            card("         SEGM  NAME=CUST,PARENT=0,BYTES=4"),
            card("         FIELD NAME=(CUSTNO,SEQ,U),BYTES=4,START=1"),
            card("         SEGM  NAME=ACCT,PARENT=CUST,BYTES=5,RULES=(,FIRST)"),
            card("         FIELD NAME=(ACCTNO,SEQ,M),BYTES=4,START=1"),
            card("         SEGM  NAME=MEMO,PARENT=CUST,BYTES=(20,4)"),
            card("         DBDGEN")) + "\n");

    private final String url = "jdbc:h2:mem:ims-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    private static String card(String text) {
        return text + " ".repeat(72 - text.length());
    }

    private JdbcDatabaseStore store() throws SQLException {
        return new JdbcDatabaseStore(DriverManager.getConnection(url, "sa", ""));
    }

    private static byte[] text(String value) {
        return EBCDIC.encode(value);
    }

    /** LL を付けた可変長の値。 */
    private static byte[] memo(String value) {
        byte[] body = text(value);
        byte[] data = new byte[body.length + 2];
        data[1] = (byte) data.length;
        System.arraycopy(body, 0, data, 2, body.length);
        return data;
    }

    private static List<String> contents(HierarchicalDatabase database) {
        List<String> out = new ArrayList<>();
        for (Segment segment : database.hierarchicalOrder()) {
            out.add(segment.definition().name() + ":" + HexFormat.of().formatHex(segment.data()));
        }
        return out;
    }

    @Test
    @DisplayName("書いて読み戻した木は、FIRST の兄弟の並び、可変長の値、上位ビットの立ったキーの順まで同じである")
    void aCommittedDatabaseReadsBackUnchanged() throws SQLException {
        HierarchicalDatabase database = new HierarchicalDatabase(DBD);
        // EBCDIC の数字 (X'F0'〜) のキーと、X'01' で始まるキー。符号付きで比べると順が逆になる
        Segment high = database.insert(null, DBD.root(), text("0002"));
        Segment low = database.insert(null, DBD.root(), new byte[] {1, 2, 3, 4});
        database.insert(high, DBD.segment("ACCT"), text("A0011"));
        database.insert(high, DBD.segment("ACCT"), text("A0012"));
        database.insert(high, DBD.segment("MEMO"), memo("HELLO"));
        database.insert(low, DBD.segment("MEMO"), memo("LOW"));

        try (JdbcDatabaseStore store = store()) {
            store.commit(List.of(database));
        }
        HierarchicalDatabase read;
        try (JdbcDatabaseStore store = store()) {
            read = store.open(DBD);
        }

        assertEquals(contents(database), contents(read));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, read.roots().get(0).key());
        assertEquals("A0012", EBCDIC.decode(read.roots().get(1).children("ACCT").get(0).data()));
    }

    @Test
    @DisplayName("行は階層の道と親の道を持ち、可変長の値は LL を外して長さを SEG_LEN に置く")
    void rowsCarryTheHierarchyPath() throws SQLException {
        HierarchicalDatabase database = new HierarchicalDatabase(DBD);
        Segment root = database.insert(null, DBD.root(), text("0001"));
        database.insert(root, DBD.segment("ACCT"), text("A0011"));
        database.insert(root, DBD.segment("ACCT"), text("A0022"));
        database.insert(root, DBD.segment("MEMO"), memo("HELLO"));
        try (JdbcDatabaseStore store = store()) {
            store.commit(List.of(database));
        }

        List<String> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement select = connection.prepareStatement(
                     "SELECT HIERARCHY_PATH, PARENT_PATH, SEG_NAME, SEG_LEN FROM IMS_SEGMENT_STORE"
                             + " ORDER BY HIERARCHY_PATH");
             ResultSet result = select.executeQuery()) {
            while (result.next()) {
                rows.add(result.getString(1) + "|" + result.getString(2) + "|" + result.getString(3) + "|"
                        + result.getInt(4));
            }
        }

        assertEquals(List.of("/||CUST|4", "/01-000000|/|ACCT|5", "/01-000001|/|ACCT|5", "/02-000000|/|MEMO|5"),
                rows);
    }

    @Test
    @DisplayName("同期点では変わった根の行だけを書き直し、ほかの根の行には触らない")
    void onlyChangedRootsAreRewritten() throws SQLException {
        HierarchicalDatabase database = new HierarchicalDatabase(DBD);
        Segment first = database.insert(null, DBD.root(), text("0001"));
        database.insert(null, DBD.root(), text("0002"));
        try (JdbcDatabaseStore store = store()) {
            store.commit(List.of(database));
        }
        database.clearChanges();
        // 0002 の行を置き場の上で書き換えておく。書き直されれば元に戻ってしまう
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE IMS_SEGMENT_STORE SET SEG_DATA = ? WHERE SEG_NAME = 'CUST' AND ROOT_KEY_RAW = ?")) {
            update.setBytes(1, text("0009"));
            update.setBytes(2, text("0002"));
            assertEquals(1, update.executeUpdate());
        }

        database.insert(first, DBD.segment("ACCT"), text("A0011"));
        try (JdbcDatabaseStore store = store()) {
            store.commit(List.of(database));
        }

        try (JdbcDatabaseStore store = store()) {
            HierarchicalDatabase read = store.open(DBD);
            assertEquals(1, read.roots().get(0).children("ACCT").size());
            // 0002 の根の行は書き直されていないので、置き場の上で書き換えた値のままである
            assertEquals("0009", EBCDIC.decode(read.roots().get(1).data()));
        }
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM IMS_ROOT_INDEX");
             ResultSet result = count.executeQuery()) {
            assertTrue(result.next());
            assertEquals(2, result.getInt(1));
        }
    }

    @Test
    @DisplayName("キーが重なる根は ROOT_SEQ で並びを保つ")
    void duplicateRootKeysKeepTheirOrder() throws SQLException {
        DatabaseDefinition accounts = DbdParser.parse(String.join("\n",
                card("         DBD   NAME=CUSTACCS,ACCESS=HDAM"),
                card("         SEGM  NAME=CUSTACCS,PARENT=0,BYTES=8"),
                card("         FIELD NAME=(CUSTID,SEQ,M),BYTES=4,START=1"),
                card("         DBDGEN")) + "\n");
        HierarchicalDatabase database = new HierarchicalDatabase(accounts);
        database.insert(null, accounts.root(), text("0001A001"));
        database.insert(null, accounts.root(), text("0001A002"));
        database.insert(null, accounts.root(), text("0001A003"));

        try (JdbcDatabaseStore store = store()) {
            store.commit(List.of(database));
        }
        try (JdbcDatabaseStore store = store()) {
            assertEquals(contents(database), contents(store.open(accounts)));
        }
    }

    @Test
    @DisplayName("領域の同期点で確定し、ROLB で戻した変更は置き場に届かない")
    void theRegionCommitsAtSyncPoints() throws SQLException {
        HierarchicalDatabase database;
        try (JdbcDatabaseStore store = store()) {
            database = store.open(DBD);
            ImsRegion region = new ImsRegion(PsbParser.parse(String.join("\n",
                    card("         PCB   TYPE=DB,DBDNAME=BANKDB,PROCOPT=A,KEYLEN=8"),
                    card("         SENSEG NAME=CUST,PARENT=0"),
                    card("         PSBGEN PSBNAME=BANKPSB,LANG=COBOL")) + "\n"),
                    List.of(database), EBCDIC).onCommit(store::commit);

            database.insert(null, DBD.root(), text("0001"));
            region.commit();
            database.insert(null, DBD.root(), text("0002"));
            region.rollback();
            region.commit();
        }

        try (JdbcDatabaseStore store = store()) {
            assertEquals(List.of("CUST:" + HexFormat.of().formatHex(text("0001"))), contents(store.open(DBD)));
        }
    }
}
