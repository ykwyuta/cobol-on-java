package dev.cobolonjava.ims.dli;

import static dev.cobolonjava.ims.gen.Cards.card;
import static dev.cobolonjava.ims.gen.Cards.deck;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.db.Segment;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.DbdParser;
import dev.cobolonjava.ims.psb.PsbParser;
import dev.cobolonjava.ims.store.CheckpointStore;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            this(queue, null, null);
        }

        Region(MessageQueue queue, CheckpointStore checkpoints, String restartId) {
            region = new ImsRegion(PsbParser.parse(deck(
                    card("         PCB   TYPE=DB,DBDNAME=BANKDB,PROCOPT=A,KEYLEN=8"),
                    card("         SENSEG NAME=CUST,PARENT=0"),
                    card("         SENSEG NAME=ACCT,PARENT=CUST"),
                    card("         PSBGEN PSBNAME=BANKPSB,LANG=COBOL,CMPAT=YES"))),
                    List.of(database), EBCDIC, true, queue, Clock.systemUTC())
                    .withCheckpoints(checkpoints, restartId);
            ioPcb = region.programArguments()[0];
            dbPcb = region.programArguments()[1];
        }

        String call(String function, DataView pcb, DataView area, String... ssas) {
            List<DataView> rest = new ArrayList<>();
            for (String ssa : ssas) {
                rest.add(text(ssa));
            }
            return callViews(function, pcb, area, rest);
        }

        /** SSA ではなく、長さと域の対を渡す呼び出し (記号 CHKP と XRST)。 */
        String callViews(String function, DataView pcb, DataView area, List<DataView> rest) {
            List<DataView> arguments = new ArrayList<>();
            arguments.add(text(function + " ".repeat(4 - function.length())));
            arguments.add(pcb);
            if (area != null) {
                arguments.add(area);
            }
            arguments.addAll(rest);
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
    @DisplayName("検査点の置き場が無ければ記号 CHKP と XRST を断る。メッセージを処理する領域の CHKP も止める")
    void unsupportedCheckpointsStop() {
        Region batch = new Region(null);
        assertThrows(DliCallException.class,
                () -> batch.callViews("CHKP", batch.ioPcb, text("CHKP0001"), areas(text("AREA"))));
        assertThrows(DliCallException.class,
                () -> batch.callViews("XRST", batch.ioPcb, work("CHKP0001").whole(), areas(text("AREA"))));
        Region online = new Region(new InMemoryMessageQueue());
        assertThrows(DliCallException.class, () -> online.call("CHKP", online.ioPcb, text("CHKP0001")));
    }

    @Test
    @DisplayName("記号 CHKP が退避した域を XRST が書き戻す。CHKP のあとに域を書き換えても、戻るのは退避した値である")
    void symbolicCheckpointSavesTheAreasThatRestartRestores() {
        Map<String, List<byte[]>> saved = new LinkedHashMap<>();
        CheckpointStore store = store(saved);
        Region batch = new Region(null, store, null);
        Storage counter = Storage.wrap(EBCDIC.encode("00000042"));
        Storage lastKey = Storage.wrap(EBCDIC.encode("0002"));

        batch.insert("CUST", "0001");
        batch.insert("CUST", "0002");
        assertEquals("  ", batch.callViews("CHKP", batch.ioPcb, text("CHKP0001"),
                areas(counter.whole(), lastKey.whole())));

        // 検査点のあとの書き換えは、退避した値を変えない (写しを取っているか)
        counter.whole().setBytes(EBCDIC.encode("99999999"));
        lastKey.whole().setBytes(EBCDIC.encode("9999"));

        // 再始動した後続のステップ。域は初期値のままである
        Region restarted = new Region(null, store, null);
        Storage restoredCounter = Storage.wrap(EBCDIC.encode("        "));
        Storage restoredKey = Storage.wrap(EBCDIC.encode("    "));
        Storage work = work("CHKP0001");
        Storage counterLength = Storage.allocate(4);
        Storage keyLength = Storage.allocate(4);

        assertEquals("  ", restarted.callViews("XRST", restarted.ioPcb, work.whole(),
                List.of(counterLength.whole(), restoredCounter.whole(),
                        keyLength.whole(), restoredKey.whole())));

        assertEquals("00000042", EBCDIC.decode(restoredCounter.whole().toByteArray()));
        assertEquals("0002", EBCDIC.decode(restoredKey.whole().toByteArray()));
        // 長さの欄にも、退避したときの長さが返る
        assertEquals(8, counterLength.whole().get(3));
        assertEquals(4, keyLength.whole().get(3));
        // 作業域には、どの検査点から始めたかが返る
        assertEquals("CHKP0001", EBCDIC.decode(work.whole().toByteArray()));
    }

    @Test
    @DisplayName("作業域が空白で CKPTID= も無ければ通常の開始である。域は書き換えず、作業域は空白で返る")
    void restartWithoutACheckpointIsANormalStart() {
        CheckpointStore store = store(new LinkedHashMap<>());
        Region batch = new Region(null, store, null);
        Storage area = Storage.wrap(EBCDIC.encode("KEEP0000"));
        Storage length = Storage.allocate(4);
        Storage work = work("        ");

        assertEquals("  ", batch.callViews("XRST", batch.ioPcb, work.whole(),
                List.of(length.whole(), area.whole())));

        assertEquals("KEEP0000", EBCDIC.decode(area.whole().toByteArray()));
        assertEquals(0, length.whole().get(3));
        assertEquals("        ", EBCDIC.decode(work.whole().toByteArray()));
    }

    @Test
    @DisplayName("作業域が空白なら、領域の CKPTID= の検査点から再始動する")
    void aBlankWorkAreaTakesTheCheckpointFromTheRegionParameter() {
        Map<String, List<byte[]>> saved = new LinkedHashMap<>();
        CheckpointStore store = store(saved);
        Region batch = new Region(null, store, null);
        Storage counter = Storage.wrap(EBCDIC.encode("00000007"));
        batch.callViews("CHKP", batch.ioPcb, text("CHKP0009"), areas(counter.whole()));

        Region restarted = new Region(null, store, "CHKP0009");
        Storage restored = Storage.wrap(EBCDIC.encode("        "));
        Storage length = Storage.allocate(4);
        Storage work = work("        ");

        assertEquals("  ", restarted.callViews("XRST", restarted.ioPcb, work.whole(),
                List.of(length.whole(), restored.whole())));

        assertEquals("00000007", EBCDIC.decode(restored.whole().toByteArray()));
        assertEquals("CHKP0009", EBCDIC.decode(work.whole().toByteArray()));
    }

    @Test
    @DisplayName("置き場に無い検査点からの再始動と、退避した数に合わない XRST は断る")
    void anUnknownCheckpointStops() {
        Map<String, List<byte[]>> saved = new LinkedHashMap<>();
        CheckpointStore store = store(saved);
        Region batch = new Region(null, store, null);
        Storage counter = Storage.wrap(EBCDIC.encode("00000007"));
        batch.callViews("CHKP", batch.ioPcb, text("CHKP0001"), areas(counter.whole()));

        Region restarted = new Region(null, store, null);
        Storage area = Storage.allocate(8);
        Storage length = Storage.allocate(4);
        DliCallException unknown = assertThrows(DliCallException.class,
                () -> restarted.callViews("XRST", restarted.ioPcb, work("NOSUCH99").whole(),
                        List.of(length.whole(), area.whole())));
        assertTrue(unknown.getMessage().contains("NOSUCH99"), unknown.getMessage());

        // 退避したのは 1 つなのに 2 つ渡した
        Storage second = Storage.allocate(8);
        Storage secondLength = Storage.allocate(4);
        assertThrows(DliCallException.class,
                () -> restarted.callViews("XRST", restarted.ioPcb, work("CHKP0001").whole(),
                        List.of(length.whole(), area.whole(), secondLength.whole(), second.whole())));
    }

    /** 長さの欄と域の対を並べる。長さは 4 byte の 2 進である。 */
    private static List<DataView> areas(DataView... views) {
        List<DataView> out = new ArrayList<>();
        for (DataView view : views) {
            Storage length = Storage.allocate(4);
            length.view(0, 4).setBytes(new byte[] {0, 0, (byte) (view.length() >>> 8), (byte) view.length()});
            out.add(length.whole());
            out.add(view);
        }
        return out;
    }

    /** XRST の作業域 (8 byte の検査点 ID)。 */
    private static Storage work(String id) {
        return Storage.wrap(EBCDIC.encode(id));
    }

    /** 検査点を覚えるだけの置き場。RDB の置き場が確定で書くところを、この試験では即座に書く。 */
    private static CheckpointStore store(Map<String, List<byte[]>> saved) {
        return new CheckpointStore() {
            @Override
            public void record(String psb, String checkpointId, List<byte[]> areas) {
                saved.put(psb + "/" + checkpointId, areas);
            }

            @Override
            public List<byte[]> load(String psb, String checkpointId) {
                return saved.get(psb + "/" + checkpointId);
            }
        };
    }
}
