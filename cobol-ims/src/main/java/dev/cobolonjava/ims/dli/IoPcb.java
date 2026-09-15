package dev.cobolonjava.ims.dli;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * I/O PCB と、電文の呼び出し (設計 78 §4、暫定判断 P-156)。
 *
 * <h2>マスク</h2>
 * <pre>
 *  0 論理端末名 (8)   8 予約 (2)    10 状態コード (2)   12 日付 (4、パック 0CYYDDDF)
 * 16 時刻 (4、パック HHMMSSt F)     20 入力の順序番号 (4)   24 MOD 名 (8)
 * 32 利用者 ID (8)   40 グループ名 (8)
 * </pre>
 *
 * <h2>呼び出し</h2>
 * <p>{@code GU} は前の電文の応答を送り出してから次の電文を取り出し、1 つ目のセグメントを LL / ZZ を付けて渡す。
 * キューが空なら {@code QC}。{@code GN} は同じ電文の次のセグメントを渡し、無ければ {@code QD}。{@code ISRT} は
 * I/O 域の LL の長さのセグメントを応答に積む。{@code PURG} は応答の電文を区切る。
 */
final class IoPcb {

    static final int TERMINAL = 0;
    static final int STATUS = 10;
    static final int DATE = 12;
    static final int TIME = 16;
    static final int SEQUENCE = 20;
    static final int MODULE = 24;
    static final int USER = 32;
    static final int GROUP = 40;

    /** まだ持たない電文の呼び出し。知らない機能コード (AD) と分けて、止めて知らせる。 */
    private static final Set<String> NOT_YET = Set.of(
            "CHNG", "ROLL", "ROLS", "SETS", "SETU", "INQY", "LOG", "CMD", "GCMD", "AUTH",
            "XRST", "INIT", "ICAL", "APSB", "DPSB");

    /** 領域の同期点。データベースの確定と巻き戻しは領域が受け持つ。 */
    interface SyncPoint {

        void commit();

        void rollback();
    }

    private final Storage mask;
    private final MessageQueue queue;
    private final CodePage codePage;
    private final Clock clock;
    private final SyncPoint syncPoint;

    private InputMessage current;
    private int nextSegment;
    private List<byte[]> output = new ArrayList<>();
    private int sequence;

    IoPcb(Storage mask, MessageQueue queue, CodePage codePage, Clock clock, SyncPoint syncPoint) {
        this.mask = mask;
        this.queue = queue;
        this.codePage = codePage;
        this.clock = clock;
        this.syncPoint = syncPoint;
        mask.whole().fill((byte) 0);
        text(TERMINAL, 8, "");
        text(STATUS, 2, "");
        text(MODULE, 8, "");
        text(USER, 8, "");
        text(GROUP, 8, "");
    }

    String call(String function, DataView io, List<DataView> rest) {
        if (NOT_YET.contains(function)) {
            throw new DliCallException("DL/I function " + function
                    + " on the I/O PCB is not supported yet (design 78 section 4)");
        }
        if (function.equals("CHKP") && !rest.isEmpty()) {
            throw new DliCallException("the symbolic CHKP (saving areas for XRST) is not supported"
                    + " (provisional P-110)");
        }
        if (!rest.isEmpty()) {
            throw new DliCallException("a MOD name or SSA on an I/O PCB call is not supported yet: " + function);
        }
        String status = switch (function) {
            case "GU" -> getUnique(required(io, function));
            case "GN" -> getNext(required(io, function));
            case "ISRT" -> insert(required(io, function));
            case "PURG" -> purge(io);
            case "CHKP", "SYNC" -> checkpoint(function);
            case "ROLB" -> backout(io);
            default -> StatusCode.AD;
        };
        text(STATUS, 2, status);
        return status;
    }

    /** プログラムが戻ったあと。正常なら積んだ応答を送り、異常終了なら捨てる (P-156)。データベースは領域が受け持つ。 */
    void finish(boolean normal) {
        if (normal) {
            complete();
        }
        output = new ArrayList<>();
        current = null;
    }

    /**
     * 基本形の CHKP と SYNC。データベースを確定し、位置を捨てる (P-157)。
     *
     * <p>メッセージを処理する領域の CHKP は次の電文を取り出す働きも持つが、それはまだ持たないので止める。
     */
    private String checkpoint(String function) {
        if (queue != null) {
            throw new DliCallException(function + " in a message processing region is not supported yet;"
                    + " the GU on the I/O PCB is the sync point there");
        }
        syncPoint.commit();
        return StatusCode.OK;
    }

    /**
     * ROLB。最後の同期点までデータベースを戻し、積みかけの応答を捨てる。I/O 域を渡せば、処理中の入力の電文の
     * 1 つ目のセグメントを渡し直す (P-157)。
     */
    private String backout(DataView io) {
        output = new ArrayList<>();
        syncPoint.rollback();
        if (io != null && current != null) {
            nextSegment = 0;
            return deliver(io);
        }
        return StatusCode.OK;
    }

    private String getUnique(DataView io) {
        requireQueue("GU");
        // 次の電文を取り出すところが同期点である。前の電文の更新と応答をここで確定する
        complete();
        syncPoint.commit();
        current = queue.next();
        nextSegment = 0;
        if (current == null) {
            return StatusCode.QC;
        }
        sequence++;
        text(TERMINAL, 8, current.logicalTerminal());
        stamp();
        integer(SEQUENCE, sequence);
        return deliver(io);
    }

    private String getNext(DataView io) {
        requireQueue("GN");
        if (current == null || nextSegment >= current.segments().size()) {
            return StatusCode.QD;
        }
        return deliver(io);
    }

    private String insert(DataView io) {
        requireQueue("ISRT");
        if (current == null) {
            throw new DliCallException("ISRT to the I/O PCB needs an input message, but there is none");
        }
        int length = io.length() < 2 ? -1 : ((io.get(0) & 0xFF) << 8) | (io.get(1) & 0xFF);
        if (length < 4 || length > io.length()) {
            throw new DliCallException("the LL " + length + " of the output segment is outside the I/O area ("
                    + io.length() + " bytes)");
        }
        output.add(io.subView(4, length - 4).toByteArray());
        return StatusCode.OK;
    }

    private String purge(DataView io) {
        requireQueue("PURG");
        if (current == null) {
            throw new DliCallException("PURG on the I/O PCB needs an input message, but there is none");
        }
        complete();
        return io == null ? StatusCode.OK : insert(io);
    }

    private String deliver(DataView io) {
        byte[] body = current.segments().get(nextSegment++);
        int length = body.length + 4;
        if (length > 0xFFFF) {
            throw new DliCallException("a message segment is longer than 65535 bytes");
        }
        if (io.length() < length) {
            throw new DliCallException("the I/O area (" + io.length() + " bytes) is shorter than the message segment ("
                    + length + " bytes)");
        }
        io.set(0, (byte) (length >>> 8));
        io.set(1, (byte) length);
        io.set(2, (byte) 0);
        io.set(3, (byte) 0);
        io.subView(4, body.length).setBytes(body);
        return StatusCode.OK;
    }

    /** 積んだ応答を 1 つの電文として送る。 */
    private void complete() {
        if (current != null && !output.isEmpty()) {
            queue.send(new OutputMessage(current.logicalTerminal(), output));
        }
        output = new ArrayList<>();
    }

    private void requireQueue(String function) {
        if (queue == null) {
            throw new DliCallException(function + " on the I/O PCB needs a message queue;"
                    + " a DLI batch region has none");
        }
    }

    private static DataView required(DataView io, String function) {
        if (io == null) {
            throw new DliCallException(function + " requires an I/O area");
        }
        return io;
    }

    /** 日付はパックの 0CYYDDDF (C は 19xx が 0、20xx が 1)、時刻は HHMMSSt と符号 F (P-156)。 */
    private void stamp() {
        LocalDateTime now = LocalDateTime.now(clock);
        packed(DATE, String.format("0%d%02d%03d", now.getYear() >= 2000 ? 1 : 0, now.getYear() % 100,
                now.getDayOfYear()));
        packed(TIME, String.format("%02d%02d%02d%d", now.getHour(), now.getMinute(), now.getSecond(),
                now.getNano() / 100_000_000));
    }

    private void packed(int offset, String sevenDigits) {
        String nibbles = sevenDigits + "F";
        DataView view = mask.view(offset, 4);
        for (int i = 0; i < 4; i++) {
            view.set(i, (byte) ((Character.digit(nibbles.charAt(2 * i), 16) << 4)
                    | Character.digit(nibbles.charAt(2 * i + 1), 16)));
        }
    }

    private void text(int offset, int length, String value) {
        String padded = value.length() >= length ? value.substring(0, length)
                : value + " ".repeat(length - value.length());
        mask.view(offset, length).setBytes(codePage.encode(padded));
    }

    private void integer(int offset, int value) {
        DataView view = mask.view(offset, 4);
        view.set(0, (byte) (value >>> 24));
        view.set(1, (byte) (value >>> 16));
        view.set(2, (byte) (value >>> 8));
        view.set(3, (byte) value);
    }
}
