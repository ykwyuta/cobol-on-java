package dev.cobolonjava.ims.dli;

import static dev.cobolonjava.ims.gen.Cards.card;
import static dev.cobolonjava.ims.gen.Cards.deck;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.db.Segment;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.DbdParser;
import dev.cobolonjava.ims.psb.PsbParser;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 同期点 (I/O PCB への GU、CHKP、SYNC、ROLB) と巻き戻し (設計 78 §3.5、暫定判断 P-157)。 */
@Tag("V1")
class SyncPointTest {

    private static final CodePage EBCDIC = CodePages.DEFAULT;

    private static final DatabaseDefinition DBD = DbdParser.parse(deck(
            card("         DBD   NAME=BANKDB,ACCESS=HIDAM"),
            card("         SEGM  NAME=CUST,PARENT=0,BYTES=4"),
            card("         FIELD NAME=(CUSTNO,SEQ,U),BYTES=4,START=1"),
            card("         SEGM  NAME=ACCT,PARENT=CUST,BYTES=4"),
            card("         FIELD NAME=(ACCTNO,SEQ,U),BYTES=4,START=1"),
            card("         DBDGEN")));

    private final HierarchicalDatabase database = new HierarchicalDatabase(DBD);

    /** I/O PCB と DB PCB を 1 つずつ持つ領域。 */
    private final class Region {

        final ImsRegion region;
        final DataView ioPcb;
        final DataView dbPcb;
        final Storage io = Storage.allocate(16);

        Region(MessageQueue queue) {
            region = new ImsRegion(PsbParser.parse(deck(
                    card("         PCB   TYPE=DB,DBDNAME=BANKDB,PROCOPT=A,KEYLEN=8"),
                    card("         SENSEG NAME=CUST,PARENT=0"),
                    card("         SENSEG NAME=ACCT,PARENT=CUST"),
                    card("         PSBGEN PSBNAME=BANKPSB,LANG=COBOL,CMPAT=YES"))),
                    List.of(database), EBCDIC, true, queue, Clock.systemUTC());
            ioPcb = region.programArguments()[0];
            dbPcb = region.programArguments()[1];
        }

        String call(String function, DataView pcb, DataView area, String... ssas) {
            List<DataView> arguments = new ArrayList<>();
            arguments.add(text(function + " ".repeat(4 - function.length())));
            arguments.add(pcb);
            if (area != null) {
                arguments.add(area);
            }
            for (String ssa : ssas) {
                arguments.add(text(ssa));
            }
            region.call(arguments);
            return EBCDIC.decode(pcb.subView(10, 2).toByteArray());
        }

        String insert(String segment, String value) {
            return call("ISRT", dbPcb, text(value), segment + " ".repeat(8 - segment.length()) + " ");
        }

        String get(String function, String... ssas) {
            return call(function, dbPcb, io.whole(), ssas);
        }

        String read() {
            return EBCDIC.decode(io.view(0, 4).toByteArray());
        }
    }

    private static DataView text(String value) {
        return Storage.wrap(EBCDIC.encode(value)).whole();
    }

    private List<String> contents() {
        return database.hierarchicalOrder().stream().map(Segment::data).map(EBCDIC::decode).toList();
    }

    @Test
    @DisplayName("CHKP は位置を捨てる。CHKP を挟んだ GN はデータベースの先頭から始まり、GNP は GP になる (設計 78 §3.5)")
    void checkpointDiscardsThePosition() {
        Region batch = new Region(null);
        batch.insert("CUST", "0001");
        batch.insert("ACCT", "A001");
        batch.insert("CUST", "0002");
        batch.insert("CUST", "0003");

        assertEquals("  ", batch.get("GU", "CUST    (CUSTNO  EQ0001)"));
        assertEquals("  ", batch.get("GN", "CUST     "));
        assertEquals("0002", batch.read());
        assertEquals("  ", batch.call("CHKP", batch.ioPcb, text("CHKP0001")));

        // 位置を捨てなければ 0003 を返すところである
        assertEquals("  ", batch.get("GN", "CUST     "));
        assertEquals("0001", batch.read());
        assertEquals("  ", batch.get("GU", "CUST    (CUSTNO  EQ0001)"));
        batch.call("SYNC", batch.ioPcb, null);
        assertEquals("GP", batch.get("GNP"));
    }

    @Test
    @DisplayName("ROLB は最後の同期点までの ISRT / REPL / DLET を逆順に戻す")
    void backoutReversesTheChangesSinceTheSyncPoint() {
        Region batch = new Region(null);
        batch.insert("CUST", "0001");
        batch.insert("ACCT", "A001");
        batch.insert("CUST", "0002");
        batch.call("CHKP", batch.ioPcb, text("CHKP0001"));

        batch.insert("CUST", "0003");
        assertEquals("  ", batch.get("GHU", "CUST    (CUSTNO  EQ0001)"));
        assertEquals("  ", batch.call("DLET", batch.dbPcb, batch.io.whole()));
        assertEquals("  ", batch.get("GHU", "CUST    (CUSTNO  EQ0002)"));
        assertEquals("DA", batch.call("REPL", batch.dbPcb, text("0009")));
        assertEquals(List.of("0002", "0003"), contents());

        assertEquals("  ", batch.call("ROLB", batch.ioPcb, null));

        assertEquals(List.of("0001", "A001", "0002"), contents());
        assertEquals("  ", batch.get("GU", "ACCT    (ACCTNO  EQA001)"));
    }

    @Test
    @DisplayName("I/O PCB への GU が同期点である。異常終了は最後の電文の更新と応答だけを捨てる")
    void eachMessageIsItsOwnUnitOfWork() {
        InMemoryMessageQueue queue = new InMemoryMessageQueue()
                .offer(new InputMessage("LTERM001", List.of(EBCDIC.encode("ADD 0001"))))
                .offer(new InputMessage("LTERM002", List.of(EBCDIC.encode("ADD 0002"))));
        Region online = new Region(queue);
        Storage input = Storage.allocate(16);
        Storage reply = Storage.wrap(new byte[] {0, 6, 0, 0, (byte) 0xD6, (byte) 0xD2});

        assertEquals("  ", online.call("GU", online.ioPcb, input.whole()));
        online.insert("CUST", "0001");
        online.call("ISRT", online.ioPcb, reply.whole());
        assertEquals("  ", online.call("GU", online.ioPcb, input.whole()));
        online.insert("CUST", "0002");
        online.call("ISRT", online.ioPcb, reply.whole());

        online.region.finish(false);

        assertEquals(List.of("0001"), contents());
        assertEquals(List.of("LTERM001"), queue.sent().stream().map(OutputMessage::destination).toList());
    }

    @Test
    @DisplayName("ROLB に I/O 域を渡すと、処理中の電文の 1 つ目を渡し直し、積みかけの応答を捨てる")
    void backoutRedeliversTheInputMessage() {
        InMemoryMessageQueue queue = new InMemoryMessageQueue()
                .offer(new InputMessage("LTERM001", List.of(EBCDIC.encode("ADD 0001"))));
        Region online = new Region(queue);
        Storage input = Storage.allocate(16);

        online.call("GU", online.ioPcb, input.whole());
        online.insert("CUST", "0001");
        online.call("ISRT", online.ioPcb, Storage.wrap(new byte[] {0, 5, 0, 0, (byte) 0xC1}).whole());
        input.whole().fill((byte) 0);

        assertEquals("  ", online.call("ROLB", online.ioPcb, input.whole()));
        assertEquals("ADD 0001", EBCDIC.decode(input.view(4, 8).toByteArray()));
        online.region.finish(true);

        assertEquals(List.of(), contents());
        assertEquals(List.of(), queue.sent());
    }

    @Test
    @DisplayName("記号 CHKP と、メッセージを処理する領域の CHKP は止める")
    void unsupportedCheckpointsStop() {
        Region batch = new Region(null);
        assertThrows(DliCallException.class,
                () -> batch.call("CHKP", batch.ioPcb, text("CHKP0001"), "AREA"));
        Region online = new Region(new InMemoryMessageQueue());
        assertThrows(DliCallException.class, () -> online.call("CHKP", online.ioPcb, text("CHKP0001")));
    }
}
