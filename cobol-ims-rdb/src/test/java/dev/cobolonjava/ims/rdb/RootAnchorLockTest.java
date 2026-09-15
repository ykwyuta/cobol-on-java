package dev.cobolonjava.ims.rdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.db.Segment;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.DbdParser;
import dev.cobolonjava.ims.dli.ImsRegion;
import dev.cobolonjava.ims.psb.PsbParser;
import dev.cobolonjava.ims.store.DatabaseConflictException;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 2 つの領域が同じ置き場を使うときの、ルートアンカーロックと版による競合の検出 (ADR-0015、暫定判断 P-161)。
 *
 * <p>H2 のメモリの上の DB に、接続を 2 つ開いて確かめる。
 */
@Tag("V1")
class RootAnchorLockTest {

    private static final CodePage EBCDIC = CodePages.DEFAULT;

    private static final DatabaseDefinition DBD = DbdParser.parse(String.join("\n",
            card("         DBD   NAME=BANKDB,ACCESS=HIDAM"),
            card("         SEGM  NAME=CUST,PARENT=0,BYTES=10"),
            card("         FIELD NAME=(CUSTNO,SEQ,U),BYTES=4,START=1"),
            card("         SEGM  NAME=ACCT,PARENT=CUST,BYTES=4"),
            card("         FIELD NAME=(ACCTNO,SEQ,U),BYTES=4,START=1"),
            card("         DBDGEN")) + "\n");

    private final String url = "jdbc:h2:mem:lock-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    private static String card(String text) {
        return text + " ".repeat(72 - text.length());
    }

    private JdbcDatabaseStore store() throws SQLException {
        Connection connection = DriverManager.getConnection(url, "sa", "");
        return new JdbcDatabaseStore(connection);
    }

    private static byte[] text(String value) {
        return EBCDIC.encode(value);
    }

    private static String city(HierarchicalDatabase database, String customer) {
        for (Segment root : database.roots()) {
            String data = EBCDIC.decode(root.data());
            if (data.startsWith(customer)) {
                return data.substring(4).strip();
            }
        }
        return null;
    }

    /** 根を 2 つ持つ置き場を用意する。 */
    private void seed() throws SQLException {
        try (JdbcDatabaseStore store = store()) {
            HierarchicalDatabase database = store.open(DBD);
            database.insert(null, DBD.root(), text("0001OSAKA "));
            database.insert(null, DBD.root(), text("0002TOKYO "));
            store.commit(List.of(database));
        }
    }

    @Test
    @DisplayName("読んだあとにほかの領域が同じ根を確定していれば、上書きせずに競合として止める")
    void aStaleUpdateOfTheSameRootIsAConflict() throws SQLException {
        seed();
        try (JdbcDatabaseStore first = store(); JdbcDatabaseStore second = store()) {
            HierarchicalDatabase one = first.open(DBD);
            HierarchicalDatabase two = second.open(DBD);

            one.replace(one.roots().get(0), text("0001KOBE  "));
            first.commit(List.of(one));
            two.replace(two.roots().get(0), text("0001NARA  "));

            DatabaseConflictException conflict = assertThrows(DatabaseConflictException.class,
                    () -> second.commit(List.of(two)));
            assertTrue(conflict.getMessage().contains("after this region read it"), conflict.getMessage());
        }
        try (JdbcDatabaseStore store = store()) {
            assertEquals("KOBE", city(store.open(DBD), "0001"));
        }
    }

    @Test
    @DisplayName("同じ新しい根を 2 つの領域が入れたら、あとの確定は競合である (ファントム)")
    void aConcurrentInsertOfTheSameNewRootIsAConflict() throws SQLException {
        seed();
        try (JdbcDatabaseStore first = store(); JdbcDatabaseStore second = store()) {
            HierarchicalDatabase one = first.open(DBD);
            HierarchicalDatabase two = second.open(DBD);

            one.insert(null, DBD.root(), text("0003KYOTO "));
            two.insert(null, DBD.root(), text("0003NAGOYA"));
            first.commit(List.of(one));

            assertThrows(DatabaseConflictException.class, () -> second.commit(List.of(two)));
        }
        try (JdbcDatabaseStore store = store()) {
            assertEquals("KYOTO", city(store.open(DBD), "0003"));
        }
    }

    @Test
    @DisplayName("別の根の確定は競合せず、同期点のあとでほかの領域の確定を読み直す")
    void differentRootsCommitAndRefreshEachOther() throws SQLException {
        seed();
        try (JdbcDatabaseStore first = store(); JdbcDatabaseStore second = store()) {
            HierarchicalDatabase one = first.open(DBD);
            HierarchicalDatabase two = second.open(DBD);

            one.replace(one.roots().get(0), text("0001KOBE  "));
            one.insert(one.roots().get(0), DBD.segment("ACCT"), text("A001"));
            first.commit(List.of(one));
            one.clearChanges();
            two.replace(two.roots().get(1), text("0002NARA  "));
            second.commit(List.of(two));
            two.clearChanges();

            // second は 0001 の確定を読み直している。子も含めて差し替わる
            assertEquals("KOBE", city(two, "0001"));
            assertEquals(1, two.roots().get(0).children("ACCT").size());
            // first はまだ同期点を迎えていないので、0002 の確定を見ない
            assertEquals("TOKYO", city(one, "0002"));
            first.commit(List.of(one));
            assertEquals("NARA", city(one, "0002"));

            // 読み直したあとは、同じ根をさらに更新しても競合しない
            two.replace(two.roots().get(0), text("0001SAKAI "));
            second.commit(List.of(two));
        }
        try (JdbcDatabaseStore store = store()) {
            HierarchicalDatabase read = store.open(DBD);
            assertEquals("SAKAI", city(read, "0001"));
            assertEquals("NARA", city(read, "0002"));
        }
    }

    @Test
    @DisplayName("競合した領域は最後の同期点まで戻り、置き場には先の確定だけが残る")
    void aConflictingRegionRollsBack() throws SQLException {
        seed();
        String psb = String.join("\n",
                card("         PCB   TYPE=DB,DBDNAME=BANKDB,PROCOPT=A,KEYLEN=8"),
                card("         SENSEG NAME=CUST,PARENT=0"),
                card("         SENSEG NAME=ACCT,PARENT=CUST"),
                card("         PSBGEN PSBNAME=BANKPSB,LANG=COBOL")) + "\n";
        try (JdbcDatabaseStore first = store(); JdbcDatabaseStore second = store()) {
            HierarchicalDatabase one = first.open(DBD);
            HierarchicalDatabase two = second.open(DBD);
            ImsRegion regionOne = new ImsRegion(PsbParser.parse(psb), List.of(one), EBCDIC).onCommit(first::commit);
            ImsRegion regionTwo = new ImsRegion(PsbParser.parse(psb), List.of(two), EBCDIC).onCommit(second::commit);

            one.insert(one.roots().get(1), DBD.segment("ACCT"), text("A002"));
            regionOne.commit();
            two.insert(two.roots().get(1), DBD.segment("ACCT"), text("A009"));
            assertThrows(DatabaseConflictException.class, regionTwo::commit);
            regionTwo.rollback();

            assertEquals(0, two.roots().get(1).children("ACCT").size());
        }
        try (JdbcDatabaseStore store = store()) {
            HierarchicalDatabase read = store.open(DBD);
            assertEquals(List.of("A002"), read.roots().get(1).children("ACCT").stream()
                    .map(segment -> EBCDIC.decode(segment.data())).toList());
        }
    }
}
