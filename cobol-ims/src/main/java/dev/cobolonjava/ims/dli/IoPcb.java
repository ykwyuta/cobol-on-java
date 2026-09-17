package dev.cobolonjava.ims.dli;

import dev.cobolonjava.ims.store.CheckpointStore;
import dev.cobolonjava.ims.store.MessageInbox;
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
    /** 検査点 ID の長さ。 */
    private static final int CHECKPOINT_ID = 8;

    /** まだ持たない電文の呼び出し。知らない機能コード (AD) と分けて、止めて知らせる。 */
    private static final Set<String> NOT_YET = Set.of(
            "CHNG", "ROLL", "ROLS", "SETS", "SETU", "INQY", "LOG", "CMD", "GCMD", "AUTH",
            "INIT", "ICAL", "APSB", "DPSB");

    /** 記号 CHKP が退避できる域の数 (公開仕様の上限)。 */
    private static final int MAX_AREAS = 7;

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
    /** 処理済みを覚える口 (P-163)。無ければ冪等化しない。 */
    private MessageInbox inbox;
    /** 記号 CHKP の置き場 (P-164)。無ければ記号 CHKP と XRST を断る。 */
    private CheckpointStore checkpoints;
    /** この領域の PSB の名前。検査点はこの名前で分ける。 */
    private String psb = "";
    /** 領域の {@code CKPTID=} (PARM)。XRST の作業域が空白のときに使う。 */
    private String restartId;
    /** この同期点までに処理した電文の ID。確定のときに書く。 */
    private final List<String> processed = new ArrayList<>();

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
        if (!rest.isEmpty() && !function.equals("CHKP") && !function.equals("XRST")) {
            throw new DliCallException("a MOD name or SSA on an I/O PCB call is not supported yet: " + function);
        }
        String status = switch (function) {
            case "GU" -> getUnique(required(io, function));
            case "GN" -> getNext(required(io, function));
            case "ISRT" -> insert(required(io, function));
            case "PURG" -> purge(io);
            case "CHKP" -> checkpoint("CHKP", io, rest);
            case "SYNC" -> checkpoint("SYNC", null, List.of());
            case "XRST" -> restart(required(io, "XRST"), rest);
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

    void inbox(MessageInbox value) {
        inbox = value;
    }

    void checkpoints(CheckpointStore store, String psbName, String restart) {
        checkpoints = store;
        psb = psbName;
        restartId = restart;
    }

    /**
     * 同期点の直前。この同期点までに処理した電文を、業務の更新と同じトランザクションで書く口へ渡す (P-163)。
     */
    void recordProcessed() {
        if (inbox != null) {
            processed.forEach(inbox::record);
        }
        processed.clear();
    }

    /** 巻き戻し。処理済みとして書かないので、電文は再配信されてもう一度処理される。 */
    void forgetProcessed() {
        processed.clear();
    }

    /**
     * CHKP と SYNC。データベースを確定し、位置を捨てる (P-157)。長さと域の対を書いた記号 CHKP は、
     * その域を検査点として置き場へ残す (P-164)。
     *
     * <p>メッセージを処理する領域の CHKP は次の電文を取り出す働きも持つが、それはまだ持たないので止める。
     */
    private String checkpoint(String function, DataView io, List<DataView> areas) {
        if (queue != null) {
            throw new DliCallException(function + " in a message processing region is not supported yet;"
                    + " the GU on the I/O PCB is the sync point there");
        }
        if (!areas.isEmpty()) {
            if (checkpoints == null) {
                throw new DliCallException("the symbolic CHKP needs a store that can keep checkpoints;"
                        + " the data set store needs DD IMSCKPT");
            }
            if (areas.size() % 2 != 0 || areas.size() / 2 > MAX_AREAS) {
                throw new DliCallException("the symbolic CHKP takes up to " + MAX_AREAS
                        + " pairs of a length and an area, but " + areas.size() + " parameters follow the id");
            }
            List<byte[]> saved = new ArrayList<>();
            for (int i = 0; i < areas.size(); i += 2) {
                saved.add(area(areas.get(i), areas.get(i + 1), "CHKP"));
            }
            checkpoints.record(psb, checkpointId(io), saved);
        }
        syncPoint.commit();
        return StatusCode.OK;
    }

    /**
     * XRST。作業域に検査点 ID があればそれ、空白なら領域の {@code CKPTID=} で再始動する。どちらも無ければ
     * 通常の開始であり、域はそのままにして作業域を空白で返す (P-164)。
     *
     * <p>再始動では、記号 CHKP が退避した域をそのまま書き戻し、長さの欄にも書いた長さを返す。実機と同じく
     * GSAM 以外のデータセットの位置は戻さない。読み直す位置は、資産が退避した値で決める。
     */
    private String restart(DataView io, List<DataView> areas) {
        if (io.length() < CHECKPOINT_ID) {
            throw new DliCallException("XRST requires a work area of at least " + CHECKPOINT_ID + " bytes");
        }
        String requested = codePage.decode(io.subView(0, CHECKPOINT_ID).toByteArray()).strip();
        String id = requested.isEmpty() ? restartId : requested;
        if (id == null || id.isBlank()) {
            io.subView(0, CHECKPOINT_ID).setBytes(codePage.encode(" ".repeat(CHECKPOINT_ID)));
            return StatusCode.OK;
        }
        if (checkpoints == null) {
            throw new DliCallException("XRST from checkpoint " + id
                    + " needs a store that can keep checkpoints; the data set store needs DD IMSCKPT");
        }
        List<byte[]> saved = checkpoints.load(psb, id);
        if (saved == null) {
            throw new DliCallException("checkpoint " + id + " of PSB " + psb + " is not in the store");
        }
        if (areas.size() % 2 != 0 || areas.size() / 2 != saved.size()) {
            throw new DliCallException("XRST was given " + (areas.size() / 2) + " areas, but checkpoint " + id
                    + " saved " + saved.size());
        }
        for (int i = 0; i < saved.size(); i++) {
            DataView length = areas.get(2 * i);
            DataView area = areas.get(2 * i + 1);
            byte[] value = saved.get(i);
            if (length.length() < 4 || area.length() < value.length) {
                throw new DliCallException("the area " + (i + 1) + " of XRST is shorter than the "
                        + value.length + " bytes saved in checkpoint " + id);
            }
            area.subView(0, value.length).setBytes(value);
            integer(length, value.length);
        }
        io.subView(0, CHECKPOINT_ID).setBytes(codePage.encode(pad(id)));
        return StatusCode.OK;
    }

    /** 検査点 ID。書かれていなければ空白 8 文字である。 */
    private String checkpointId(DataView io) {
        if (io == null || io.length() < CHECKPOINT_ID) {
            throw new DliCallException("the symbolic CHKP requires an 8 byte checkpoint id");
        }
        return codePage.decode(io.subView(0, CHECKPOINT_ID).toByteArray()).strip();
    }

    /** 長さの欄と域の対から、退避する値を取る。 */
    private static byte[] area(DataView length, DataView area, String function) {
        if (length.length() < 4) {
            throw new DliCallException("the length of an area of " + function + " is a 4 byte binary field");
        }
        byte[] bytes = length.subView(0, 4).toByteArray();
        int value = ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16) | ((bytes[2] & 0xFF) << 8)
                | (bytes[3] & 0xFF);
        if (value <= 0 || value > area.length()) {
            throw new DliCallException("the length " + value + " of an area of " + function
                    + " is outside the area (" + area.length() + " bytes)");
        }
        return area.subView(0, value).toByteArray();
    }

    private static String pad(String id) {
        return id.length() >= CHECKPOINT_ID ? id.substring(0, CHECKPOINT_ID)
                : id + " ".repeat(CHECKPOINT_ID - id.length());
    }

    private static void integer(DataView view, int value) {
        view.set(0, (byte) (value >>> 24));
        view.set(1, (byte) (value >>> 16));
        view.set(2, (byte) (value >>> 8));
        view.set(3, (byte) value);
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
        // 処理済みの電文が再配信されていれば、業務を動かさずに捨てて次を読む (ADR-0014 の冪等化、P-163)
        do {
            current = queue.next();
            nextSegment = 0;
        } while (current != null && !current.id().isBlank() && inbox != null && inbox.seen(current.id()));
        if (current == null) {
            return StatusCode.QC;
        }
        if (!current.id().isBlank() && inbox != null) {
            processed.add(current.id());
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
