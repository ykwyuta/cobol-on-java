package dev.cobolonjava.ims.batch;

import static dev.cobolonjava.ims.gen.Cards.card;
import static dev.cobolonjava.ims.gen.Cards.deck;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.db.Segment;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.DbdParser;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** データベースをデータセットに置く形と、DFSRRC00 の PARM (暫定判断 P-155)。 */
@Tag("V1")
class DatabaseFileTest {

    private static final CodePage EBCDIC = CodePages.DEFAULT;

    private static final DatabaseDefinition BANK = DbdParser.parse(deck(
            card("         DBD   NAME=BANKDB,ACCESS=HDAM"),
            card("         SEGM  NAME=CUST,PARENT=0,BYTES=4"),
            card("         FIELD NAME=(CUSTNO,SEQ,U),BYTES=4,START=1"),
            card("         SEGM  NAME=ACCT,PARENT=CUST,BYTES=5,RULES=(,FIRST)"),
            card("         FIELD NAME=(ACCTNO,SEQ,M),BYTES=4,START=1"),
            card("         DBDGEN")));

    @TempDir
    Path directory;

    @Test
    @DisplayName("書いて読み戻した階層は、FIRST で逆に並んだ兄弟も含めて同じ並びである")
    void aWrittenDatabaseReadsBackInTheSameOrder() {
        HierarchicalDatabase database = new HierarchicalDatabase(BANK);
        Segment first = database.insert(null, BANK.root(), EBCDIC.encode("0002"));
        database.insert(first, BANK.segment("ACCT"), EBCDIC.encode("A0011"));
        database.insert(first, BANK.segment("ACCT"), EBCDIC.encode("A0012"));
        database.insert(null, BANK.root(), EBCDIC.encode("0001"));
        DatabaseFile file = new DatabaseFile(directory.resolve("BANK.DB"), "BANKDD", EBCDIC);

        file.write(database);
        HierarchicalDatabase read = file.read(BANK);

        assertEquals(List.of("0001", "0002", "A0012", "A0011"), texts(read));
        assertEquals(texts(database), texts(read));
    }

    @Test
    @DisplayName("無いデータセットは空のデータベースであり、壊れたものは読まない")
    void missingIsEmptyAndDamagedIsRefused() throws IOException {
        DatabaseFile file = new DatabaseFile(directory.resolve("BANK.DB"), "BANKDD", EBCDIC);
        assertTrue(file.read(BANK).roots().isEmpty());

        Files.write(directory.resolve("BANK.DB"), new byte[] {0, 12, 0, 1});
        ImsBatchException damaged = assertThrows(ImsBatchException.class, () -> file.read(BANK));
        assertTrue(damaged.getMessage().contains("DD BANKDD is damaged"), damaged.getMessage());
    }

    @Test
    @DisplayName("PARM は領域の種類、プログラム、PSB、CKPTID= を読み、PSB を省けばプログラムの名前にする。電文を読む領域は断る")
    void regionParameters() {
        RegionParameters full = RegionParameters.of(EBCDIC, parm("DLI,LOADCUST,IBLOAD,,,,"));
        assertEquals(new RegionParameters("DLI", "LOADCUST", "IBLOAD", null), full);
        assertEquals("REPORT", RegionParameters.of(EBCDIC, parm("DBB,REPORT")).psb());
        assertTrue(RegionParameters.of(EBCDIC, parm("BMP,REPORT,REPORT,,")).ioPcb());

        // 再始動する検査点 (P-164)。書かなければ通常の開始である
        assertNull(full.restartId());
        assertEquals("CHKP0003",
                RegionParameters.of(EBCDIC, parm("DLI,LOADCUST,IBLOAD,,,,,CKPTID=CHKP0003")).restartId());

        ImsBatchException online = assertThrows(ImsBatchException.class,
                () -> RegionParameters.of(EBCDIC, parm("MSG,IBACSUM,IBACSUM")));
        assertTrue(online.getMessage().contains("IMS TM"), online.getMessage());
        ImsBatchException reading = assertThrows(ImsBatchException.class,
                () -> RegionParameters.of(EBCDIC, parm("BMP,IBACSUM,IBACSUM,IBACSUM")));
        assertTrue(reading.getMessage().contains("IN=IBACSUM"), reading.getMessage());
    }

    private static dev.cobolonjava.runtime.storage.DataView[] parm(String text) {
        byte[] bytes = EBCDIC.encode(text);
        Storage storage = Storage.allocate(bytes.length + 2);
        storage.view(0, 2).setBytes(new byte[] {(byte) (bytes.length >> 8), (byte) bytes.length});
        storage.view(2, bytes.length).setBytes(bytes);
        return new dev.cobolonjava.runtime.storage.DataView[] {storage.whole()};
    }

    private static List<String> texts(HierarchicalDatabase database) {
        return database.hierarchicalOrder().stream()
                .map(segment -> EBCDIC.decode(segment.data()).substring(0, 4)
                        + (segment.level() == 2 ? EBCDIC.decode(segment.data()).substring(4) : ""))
                .toList();
    }
}
