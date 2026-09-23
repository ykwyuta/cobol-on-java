package dev.cobolonjava.ims.dli;

import static dev.cobolonjava.ims.gen.Cards.card;
import static dev.cobolonjava.ims.gen.Cards.deck;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.dbd.DbdParser;
import dev.cobolonjava.ims.psb.PsbParser;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** I/O PCB の電文の呼び出し (設計 78 §4、暫定判断 P-156)。実機と突き合わせていない (P-099)。 */
@Tag("V1")
class IoPcbTest {

    private static final CodePage EBCDIC = CodePages.DEFAULT;
    /** 2026 年 9 月 16 日 (年の 259 日目) 13:05:07.8。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T13:05:07.800Z"), ZoneOffset.UTC);

    private final InMemoryMessageQueue queue = new InMemoryMessageQueue();
    private final ImsRegion region = new ImsRegion(
            PsbParser.parse(deck(
                    card("         PCB   TYPE=DB,DBDNAME=BANKDB,PROCOPT=A,KEYLEN=4"),
                    card("         SENSEG NAME=CUST,PARENT=0"),
                    card("         PSBGEN PSBNAME=BANKPSB,LANG=COBOL"))),
            List.of(new HierarchicalDatabase(DbdParser.parse(deck(
                    card("         DBD   NAME=BANKDB,ACCESS=HDAM"),
                    card("         SEGM  NAME=CUST,PARENT=0,BYTES=4"),
                    card("         FIELD NAME=(CUSTNO,SEQ,U),BYTES=4,START=1"),
                    card("         DBDGEN"))))),
            EBCDIC, true, queue, CLOCK);
    private final DataView ioPcb = region.programArguments()[0];
    private final Storage io = Storage.allocate(32);

    private String call(String function, DataView area) {
        List<DataView> arguments = new ArrayList<>();
        arguments.add(Storage.wrap(EBCDIC.encode(function + " ".repeat(4 - function.length()))).whole());
        arguments.add(ioPcb);
        if (area != null) {
            arguments.add(area);
        }
        region.call(arguments);
        return EBCDIC.decode(ioPcb.subView(IoPcb.STATUS, 2).toByteArray());
    }

    private static InputMessage message(String terminal, String... segments) {
        return new InputMessage(terminal, List.of(segments).stream().map(EBCDIC::encode).toList());
    }

    /** LL / ZZ を付けた出力のセグメント。 */
    private static DataView output(String text) {
        byte[] body = EBCDIC.encode(text);
        Storage storage = Storage.allocate(body.length + 4);
        storage.view(0, 2).setBytes(new byte[] {0, (byte) (body.length + 4)});
        storage.view(4, body.length).setBytes(body);
        return storage.whole();
    }

    private String received() {
        int length = ByteBuffer.wrap(io.view(0, 2).toByteArray()).getShort();
        return EBCDIC.decode(io.view(4, length - 4).toByteArray());
    }

    @Test
    @DisplayName("GU は LL / ZZ を付けて 1 つ目のセグメントを渡し、端末名・日付・時刻・順序番号を置く。GN は次のセグメント、尽きれば QD")
    void getUniqueDeliversTheFirstSegment() {
        queue.offer(message("LTERM001", "CUSTINQ 0001", "SECOND"));

        assertEquals("  ", call("GU", io.whole()));
        assertEquals("CUSTINQ 0001", received());
        assertEquals(16, ByteBuffer.wrap(io.view(0, 2).toByteArray()).getShort());
        assertEquals("LTERM001", EBCDIC.decode(ioPcb.subView(IoPcb.TERMINAL, 8).toByteArray()));
        assertEquals("0126259F", HexFormat.of().withUpperCase().formatHex(ioPcb.subView(IoPcb.DATE, 4).toByteArray()));
        assertEquals("1305078F", HexFormat.of().withUpperCase().formatHex(ioPcb.subView(IoPcb.TIME, 4).toByteArray()));
        assertEquals(1, ByteBuffer.wrap(ioPcb.subView(IoPcb.SEQUENCE, 4).toByteArray()).getInt());

        assertEquals("  ", call("GN", io.whole()));
        assertEquals("SECOND", received());
        assertEquals("QD", call("GN", io.whole()));
    }

    @Test
    @DisplayName("PLITDLI の電文は長さの欄が 4 byte (LLLL)。値は COBOL の LL と同じ「本文 + 4」で、本文は 6 byte 目から")
    void pliMessageSegmentsHaveAFullwordLength() {
        queue.offer(message("LTERM001", "IBLOGIN 16918    PASSWORD"));
        Storage area = Storage.allocate(40);

        assertEquals("  ", pliCall("GU", area.whole()));
        // IMS Application Programming: 本文 12 byte なら LLLL は 24 (= 4 + 2 + 8 + 12 - 2)
        assertEquals(25 + 4, ByteBuffer.wrap(area.view(0, 4).toByteArray()).getInt());
        assertEquals(0, ByteBuffer.wrap(area.view(4, 2).toByteArray()).getShort());
        assertEquals("IBLOGIN 16918    PASSWORD", EBCDIC.decode(area.view(6, 25).toByteArray()));

        // 出力: LLLL = 本文 + 4。IBLOGIN は SIZE(OUTPUT_AREA) - 2 と書く
        byte[] body = EBCDIC.encode("LOGIN SUCCESSFUL");
        Storage reply = Storage.allocate(6 + body.length);
        reply.view(0, 4).setBytes(ByteBuffer.allocate(4).putInt(body.length + 4).array());
        reply.view(6, body.length).setBytes(body);
        assertEquals("  ", pliCall("ISRT", reply.whole()));
        assertEquals("QC", pliCall("GU", area.whole()));

        assertEquals(List.of("LOGIN SUCCESSFUL"), texts(queue.sent().get(0)));
    }

    @Test
    @DisplayName("PLITDLI の LLLL を 2 byte の LL と読まない。上位の 2 byte が 0 の LL として断っていた")
    void aPliLengthIsNotReadAsAHalfword() {
        queue.offer(message("LTERM001", "IBLOGIN"));
        assertEquals("  ", pliCall("GU", Storage.allocate(40).whole()));
        byte[] body = EBCDIC.encode("OK");
        Storage reply = Storage.allocate(6 + body.length);
        reply.view(0, 4).setBytes(ByteBuffer.allocate(4).putInt(body.length + 4).array());
        reply.view(6, body.length).setBytes(body);

        assertThrows(DliCallException.class, () -> call("ISRT", reply.whole()));
        assertEquals("  ", pliCall("ISRT", reply.whole()));
    }

    private String pliCall(String function, DataView area) {
        List<DataView> arguments = new ArrayList<>();
        arguments.add(Storage.wrap(EBCDIC.encode(function + " ".repeat(4 - function.length()))).whole());
        arguments.add(ioPcb);
        arguments.add(area);
        region.call(arguments, true);
        return EBCDIC.decode(ioPcb.subView(IoPcb.STATUS, 2).toByteArray());
    }

    @Test
    @DisplayName("ISRT した応答は次の GU で 1 つの電文として送られ、キューが空なら QC を返す")
    void repliesAreSentAtTheNextGetUnique() {
        queue.offer(message("LTERM001", "CUSTINQ 0001")).offer(message("LTERM002", "CUSTINQ 0002"));

        assertEquals("  ", call("GU", io.whole()));
        assertEquals("  ", call("ISRT", output("HELLO")));
        assertEquals("  ", call("ISRT", output("WORLD")));
        assertEquals(List.of(), queue.sent());

        assertEquals("  ", call("GU", io.whole()));
        assertEquals("  ", call("ISRT", output("PART1")));
        assertEquals("  ", call("PURG", output("PART2")));
        assertEquals("QC", call("GU", io.whole()));

        List<OutputMessage> sent = queue.sent();
        assertEquals(3, sent.size());
        assertEquals("LTERM001", sent.get(0).destination());
        assertEquals(List.of("HELLO", "WORLD"), texts(sent.get(0)));
        assertEquals(List.of("PART1"), texts(sent.get(1)));
        assertEquals("LTERM002", sent.get(2).destination());
        assertEquals(List.of("PART2"), texts(sent.get(2)));
    }

    @Test
    @DisplayName("異常終了したプログラムの積みかけの応答は送らない")
    void anAbendDiscardsThePendingReply() {
        queue.offer(message("LTERM001", "CUSTINQ 0001"));
        call("GU", io.whole());
        call("ISRT", output("NEVER"));

        region.finish(false);

        assertEquals(List.of(), queue.sent());
    }

    @Test
    @DisplayName("入力の電文が無いときの ISRT、まだ持たない呼び出し、I/O 域より長いセグメントは止める")
    void unsupportedMessageCallsStop() {
        assertThrows(DliCallException.class, () -> call("ISRT", output("X")));
        assertThrows(DliCallException.class, () -> call("CHNG", io.whole()));
        queue.offer(message("LTERM001", "A".repeat(40)));
        assertThrows(DliCallException.class, () -> call("GU", io.whole()));
    }

    private static List<String> texts(OutputMessage message) {
        return message.segments().stream().map(EBCDIC::decode).toList();
    }
}
