package dev.cobolonjava.ims.dli;

import static dev.cobolonjava.ims.gen.Cards.card;
import static dev.cobolonjava.ims.gen.Cards.deck;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.db.Segment;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.DbdParser;
import dev.cobolonjava.ims.psb.PsbParser;
import dev.cobolonjava.ims.store.MessageInbox;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 再配信された電文を捨てる冪等化 (ADR-0014 の決定 2、暫定判断 P-163)。
 *
 * <p>処理済みを覚える口をメモリに置いて、I/O PCB の GU が再配信を読み飛ばすことを見る。
 */
@Tag("V1")
class MessageInboxTest {

    private static final CodePage EBCDIC = CodePages.DEFAULT;

    private static final DatabaseDefinition DBD = DbdParser.parse(deck(
            card("         DBD   NAME=BANKDB,ACCESS=HDAM"),
            card("         SEGM  NAME=CUST,PARENT=0,BYTES=4"),
            card("         FIELD NAME=(CUSTNO,SEQ,U),BYTES=4,START=1"),
            card("         DBDGEN")));

    /** 確定したときだけ覚える、メモリの inbox。 */
    private static final class Remembered implements MessageInbox {

        private final Set<String> committed = new HashSet<>();
        private final List<String> pending = new ArrayList<>();

        @Override
        public boolean seen(String messageId) {
            return committed.contains(messageId);
        }

        @Override
        public void record(String messageId) {
            pending.add(messageId);
        }

        /** 置き場の確定にあたる。 */
        void commit() {
            committed.addAll(pending);
            pending.clear();
        }
    }

    private final HierarchicalDatabase database = new HierarchicalDatabase(DBD);
    private final Remembered inbox = new Remembered();
    private final InMemoryMessageQueue queue = new InMemoryMessageQueue();
    private final ImsRegion region = new ImsRegion(PsbParser.parse(deck(
            card("         PCB   TYPE=DB,DBDNAME=BANKDB,PROCOPT=A,KEYLEN=4"),
            card("         SENSEG NAME=CUST,PARENT=0"),
            card("         PSBGEN PSBNAME=BANKPSB,LANG=COBOL"))),
            List.of(database), EBCDIC, true, queue, Clock.systemUTC())
            .onCommit(databases -> inbox.commit())
            .withInbox(inbox);
    private final DataView ioPcb = region.programArguments()[0];
    private final DataView dbPcb = region.programArguments()[1];
    private final Storage io = Storage.allocate(32);

    private String call(String function, DataView pcb, DataView area, String... ssas) {
        List<DataView> arguments = new ArrayList<>();
        arguments.add(Storage.wrap(EBCDIC.encode(function + " ".repeat(4 - function.length()))).whole());
        arguments.add(pcb);
        if (area != null) {
            arguments.add(area);
        }
        for (String ssa : ssas) {
            arguments.add(Storage.wrap(EBCDIC.encode(ssa)).whole());
        }
        region.call(arguments);
        return EBCDIC.decode(pcb.subView(10, 2).toByteArray());
    }

    /** 電文 1 通を処理する: GU で受け、その中身を根として入れる。 */
    private String handleOne() {
        String status = call("GU", ioPcb, io.whole());
        if (!status.equals("  ")) {
            return status;
        }
        byte[] key = io.view(4, 4).toByteArray();
        call("ISRT", dbPcb, Storage.wrap(key).whole(), "CUST     ");
        return status;
    }

    private static InputMessage message(String id, String key) {
        return new InputMessage(id, "LTERM001", List.of(CodePages.DEFAULT.encode(key)));
    }

    private List<String> customers() {
        return database.hierarchicalOrder().stream().map(Segment::data).map(EBCDIC::decode).toList();
    }

    @Test
    @DisplayName("確定した電文が再配信されても、業務を動かさずに読み飛ばす")
    void aRedeliveredMessageIsSkipped() {
        queue.offer(message("M1", "0001"));
        assertEquals("  ", handleOne());
        // 次の GU が同期点である。ここで M1 が処理済みとして確定する
        queue.offer(message("M1", "0001")).offer(message("M2", "0002"));
        assertEquals("  ", handleOne());

        // 再配信の M1 は読み飛ばされ、M2 が渡る
        assertEquals("0002", EBCDIC.decode(io.view(4, 4).toByteArray()));
        assertEquals(List.of("0001", "0002"), customers());
        assertEquals("QC", call("GU", ioPcb, io.whole()));
    }

    @Test
    @DisplayName("巻き戻した電文は処理済みにならないので、再配信されたらもう一度処理する")
    void aRolledBackMessageIsProcessedAgain() {
        queue.offer(message("M1", "0001"));
        assertEquals("  ", handleOne());

        region.rollback();

        assertEquals(List.of(), customers());
        queue.offer(message("M1", "0001"));
        assertEquals("  ", handleOne());
        assertEquals("0001", EBCDIC.decode(io.view(4, 4).toByteArray()));
        assertEquals(List.of("0001"), customers());
    }

    @Test
    @DisplayName("ID を持たない電文は見分けられないので、冪等化しない")
    void messagesWithoutAnIdAreNotDeduplicated() {
        queue.offer(new InputMessage("LTERM001", List.of(EBCDIC.encode("0001"))));
        assertEquals("  ", handleOne());
        queue.offer(new InputMessage("LTERM001", List.of(EBCDIC.encode("0001"))));

        assertEquals("  ", call("GU", ioPcb, io.whole()));
        assertEquals("0001", EBCDIC.decode(io.view(4, 4).toByteArray()));
    }
}
