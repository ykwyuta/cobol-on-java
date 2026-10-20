package dev.cobolonjava.mq;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * region の中の待ち行列管理者 (設計 88 §3)。局所待ち行列を記憶域に持ち、MQI の呼び出し ({@link Mqi}) がここへ来る。
 *
 * <p>電文は JVM の記憶域にだけあり、region を止めれば消える。永続性 (MQPER_PERSISTENT) は記述子に残すが、
 * 再始動をまたいで電文を残すことはしない (暫定判断 P-251)。将来 JMS のブローカへ写すときは、IMS の電文を
 * JMS へ写す {@code cobol-ims-jms} の形に倣い、この型の待ち行列の部分を差し替える。
 *
 * <p>接続は実行単位ごとに 1 つ持つ ({@link #connection})。接続は実行単位の作業単位に加わり
 * ({@code RecoverableResources})、CICS の SYNCPOINT で確約・取り消しされ、実行単位の終わり (session を閉じるとき) に
 * 正常なら確約、異常なら取り消しで終わる (設計 88 §4.3、暫定判断 P-252、P-254)。
 */
public final class MqQueueManager {

    private static final Pattern CCSID_IN_NAME = Pattern.compile("IBM-0*(\\d+)");

    private final String name;
    private final CodePage codePage;
    private final int ccsid;
    private final Clock clock;
    private final int applicationType;

    final ReentrantLock lock = new ReentrantLock();
    /** 電文が見えるようになったとき (PUT の確約、取り消しで戻った電文) に起こす。待っている MQGET が見る。 */
    final Condition arrived = lock.newCondition();
    private final Map<String, LocalQueue> queues = new LinkedHashMap<>();
    private final Map<String, ProcessDefinition> processes = new LinkedHashMap<>();
    private final Map<Object, MqConnection> connections = new WeakHashMap<>();
    private long sequence;
    private int nextConnection = 1;
    private long nextMessageId;

    /**
     * @param name            待ち行列管理者の名前 (48 文字まで。MsgId の頭には先頭 4 文字を使う)
     * @param codePage        プログラムの code page。名前・記述子の文字はこれで読み書きする
     * @param ccsid           MQCCSI_Q_MGR が指す CCSID
     * @param clock           PutDate / PutTime と有効期限が見る時計 (UTC として読む)
     * @param applicationType PutApplType に置く値 (MQAT_ZOS、MQAT_CICS など)
     */
    public MqQueueManager(String name, CodePage codePage, int ccsid, Clock clock, int applicationType) {
        this.name = Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > Structure.NAME_LENGTH) {
            throw new IllegalArgumentException("a queue manager name is 1 to 48 characters: " + name);
        }
        this.codePage = Objects.requireNonNull(codePage, "codePage");
        this.ccsid = ccsid;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.applicationType = applicationType;
    }

    /**
     * code page の名前 ({@code IBM-1047} など) から CCSID を引き、システムの時計で動く待ち行列管理者。
     * 名前から CCSID が読めない code page は断る。違う CCSID を黙って名乗るより、呼ぶ側に決めさせる。
     */
    public static MqQueueManager of(String name, CodePage codePage) {
        Matcher m = CCSID_IN_NAME.matcher(codePage.name());
        if (!m.matches()) {
            throw new IllegalArgumentException("the CCSID of code page " + codePage.name()
                    + " is not known; pass it explicitly");
        }
        return new MqQueueManager(name, codePage, Integer.parseInt(m.group(1)), Clock.systemUTC(),
                MqConstants.MQAT_ZOS);
    }

    public String name() {
        return name;
    }

    public CodePage codePage() {
        return codePage;
    }

    public int ccsid() {
        return ccsid;
    }

    int applicationType() {
        return applicationType;
    }

    Instant now() {
        return clock.instant();
    }

    /** 局所待ち行列を定義する。同じ名前を 2 度定義すると断る。 */
    public MqQueueManager define(QueueDefinition definition) {
        lock.lock();
        try {
            if (queues.putIfAbsent(definition.name(), new LocalQueue(definition)) != null) {
                throw new IllegalArgumentException("queue is already defined: " + definition.name());
            }
            return this;
        } finally {
            lock.unlock();
        }
    }

    /** 処理を定義する。同じ名前を 2 度定義すると断る。 */
    public MqQueueManager define(ProcessDefinition definition) {
        lock.lock();
        try {
            if (processes.putIfAbsent(definition.name(), definition) != null) {
                throw new IllegalArgumentException("process is already defined: " + definition.name());
            }
            return this;
        } finally {
            lock.unlock();
        }
    }

    /** TRIGGER / NOTRIGGER (MQSC の {@code ALTER QLOCAL TRIGGER})。DEPTH の起動で切れたものを戻すのに使う。 */
    public void setTriggerControl(String queueName, boolean on) {
        lock.lock();
        try {
            LocalQueue q = require(queueName);
            if (on && q.definition.trigger() == null) {
                throw new IllegalArgumentException("queue has no trigger definition: " + queueName);
            }
            q.triggerControl = on;
        } finally {
            lock.unlock();
        }
    }

    public MqQueueManager defineLocalQueue(String queueName) {
        return define(QueueDefinition.local(queueName));
    }

    /** GET / PUT を禁じる・許す (MQSC の {@code ALTER QLOCAL GET(DISABLED)} にあたる)。 */
    public void setInhibited(String queueName, boolean get, boolean put) {
        lock.lock();
        try {
            LocalQueue q = require(queueName);
            q.getInhibited = get;
            q.putInhibited = put;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Java の側から電文を置く。作業単位の外で、すぐに見える。MsgId が MQMI_NONE なら振り、PutDate / PutTime を置く。
     * 試験や、MQ の外から要求を流し込む入口が使う。
     */
    public MqMessage put(String queueName, MqMessage message) {
        lock.lock();
        try {
            LocalQueue q = require(queueName);
            byte[] d = message.descriptor();
            if (isZero(d, Structure.MD_MSG_ID, Structure.ID_LENGTH)) {
                System.arraycopy(newMessageId(), 0, d, Structure.MD_MSG_ID, Structure.ID_LENGTH);
            }
            setPutTime(d);
            LocalQueue.Entry entry = entry(q, new MqMessage(d, message.data()));
            arrive(q, entry);
            return entry.message();
        } finally {
            lock.unlock();
        }
    }

    /** 見えている電文 (確約済みで期限の切れていないもの) を、渡す順に並べた写し。 */
    public List<MqMessage> messages(String queueName) {
        lock.lock();
        try {
            LocalQueue q = require(queueName);
            q.expire(now());
            List<LocalQueue.Entry> entries = new ArrayList<>(q.visible);
            entries.sort((a, b) -> a.priority() != b.priority()
                    ? Integer.compare(b.priority(), a.priority()) : Long.compare(a.sequence(), b.sequence()));
            return entries.stream().map(LocalQueue.Entry::message).toList();
        } finally {
            lock.unlock();
        }
    }

    /** 深さ (MQIA_CURRENT_Q_DEPTH)。確約されていない電文も数える。 */
    public int depth(String queueName) {
        lock.lock();
        try {
            LocalQueue q = require(queueName);
            q.expire(now());
            return q.depth();
        } finally {
            lock.unlock();
        }
    }

    /**
     * いま開いている接続をすべて終える: 作業単位を確約するか取り消し、handle を閉じる。実行単位の終わりは session が
     * 閉じるときに済む (設計 88 §4.3) ので、これは region を止めるときに持ち主が呼ぶ。
     */
    public void endConnections(boolean commit) {
        lock.lock();
        try {
            for (MqConnection c : new ArrayList<>(connections.values())) {
                c.end(commit);
            }
            connections.clear();
        } finally {
            lock.unlock();
        }
    }

    // ---- 接続 ----------------------------------------------------------------

    /**
     * 実行単位の接続。{@code hconn} が MQHC_DEF_HCONN (0) なら、無ければ作る (CICS の下の暗黙の接続と同じ形、
     * 暫定判断 P-252)。それ以外は、その実行単位が MQCONN で得た値でなければ null (MQRC_HCONN_ERROR)。
     */
    MqConnection connection(Object runUnit, int hconn) {
        MqConnection c = connections.get(runUnit);
        if (hconn == MqConstants.MQHC_DEF_HCONN) {
            if (c == null) {
                c = new MqConnection(this, nextConnection++);
                connections.put(runUnit, c);
            }
            return c;
        }
        return c != null && c.handle() == hconn ? c : null;
    }

    /** 終えた接続を表から外す。次の呼び出しは新しい接続になる。 */
    void forget(MqConnection connection) {
        connections.values().removeIf(c -> c == connection);
    }

    // ---- 待ち行列 ------------------------------------------------------------

    LocalQueue lookup(String queueName) {
        return queues.get(queueName);
    }

    private LocalQueue require(String queueName) {
        LocalQueue q = queues.get(queueName);
        if (q == null) {
            throw new IllegalArgumentException("queue is not defined: " + queueName);
        }
        return q;
    }

    /**
     * 待ち行列に置く形にする。優先度と永続性の「待ち行列の定義に従う」を定義の値に置き換え、CCSID の
     * MQCCSI_Q_MGR を待ち行列管理者の CCSID に置き換える。公開文書のとおり、MQGET はこの置き換えた値を返す。
     */
    LocalQueue.Entry entry(LocalQueue q, MqMessage message) {
        byte[] d = message.descriptor();
        if (Structure.readInteger(d, Structure.MD_PRIORITY) == MqConstants.MQPRI_PRIORITY_AS_Q_DEF) {
            Structure.writeInteger(d, Structure.MD_PRIORITY, q.definition.defaultPriority());
        }
        if (Structure.readInteger(d, Structure.MD_PERSISTENCE) == MqConstants.MQPER_PERSISTENCE_AS_Q_DEF) {
            Structure.writeInteger(d, Structure.MD_PERSISTENCE, q.definition.defaultPersistence());
        }
        if (Structure.readInteger(d, Structure.MD_CCSID) == MqConstants.MQCCSI_Q_MGR) {
            Structure.writeInteger(d, Structure.MD_CCSID, ccsid);
        }
        int expiry = Structure.readInteger(d, Structure.MD_EXPIRY);
        Instant expiresAt = expiry == MqConstants.MQEI_UNLIMITED ? null
                : now().plusMillis(expiry * 100L);
        return new LocalQueue.Entry(new MqMessage(d, message.data()), sequence++,
                Structure.readInteger(d, Structure.MD_PRIORITY), expiresAt);
    }

    /**
     * 新しい MsgId (24 byte)。'CSQ ' と待ち行列管理者の名前の先頭 4 文字に、時刻と通し番号を続ける。
     *
     * <p>z/OS の MsgId も 'CSQ ' と待ち行列管理者の名前で始まるが、後ろの 16 byte の作り方は確かめていない。
     * 一意であることだけを約束し、中身を解く資産は無いものとする (暫定判断 P-253)。
     */
    byte[] newMessageId() {
        ByteBuffer id = ByteBuffer.allocate(Structure.ID_LENGTH);
        id.put(Structure.padded(codePage, "CSQ", 4));
        String head = name.length() > 4 ? name.substring(0, 4) : name;
        id.put(Structure.padded(codePage, head, 4));
        id.putLong(now().toEpochMilli());
        id.putLong(nextMessageId++);
        return id.array();
    }

    /** PutDate (YYYYMMDD) と PutTime (HHMMSSTH) を UTC で置く。公開文書のとおり GMT の時刻である。 */
    void setPutTime(byte[] descriptor) {
        java.time.ZonedDateTime t = now().atZone(java.time.ZoneOffset.UTC);
        String date = String.format("%04d%02d%02d", t.getYear(), t.getMonthValue(), t.getDayOfMonth());
        String time = String.format("%02d%02d%02d%02d", t.getHour(), t.getMinute(), t.getSecond(),
                t.getNano() / 10_000_000);
        System.arraycopy(codePage.encode(date), 0, descriptor, Structure.MD_PUT_DATE, 8);
        System.arraycopy(codePage.encode(time), 0, descriptor, Structure.MD_PUT_TIME, 8);
    }

    /**
     * 電文が見えるようになった: 待ち行列に入れ、起動の条件を見て、待っている MQGET を起こす。錠を持って呼ぶ。
     * 同期点の外の PUT、作業単位の確約、Java の側の {@link #put} がここを通る。MQBACK で戻った電文は通らない
     * (戻しでは起動しない。暫定判断 P-255)。
     */
    void arrive(LocalQueue q, LocalQueue.Entry entry) {
        TriggerDefinition t = q.definition.trigger();
        int before = t == null ? 0 : q.count(t.messagePriority());
        q.insert(entry);
        if (t != null) {
            trigger(q, t, entry, before);
        }
        arrived.signalAll();
    }

    /**
     * 起動の条件 (公開文書「Conditions for a trigger event」の読み、暫定判断 P-255) を満たせば、開始待ち行列へ
     * 起動電文 (MQTM 1 版) を置く。
     *
     * <ul>
     * <li>TRIGGER が入っていて、GET が禁じられていない
     * <li>電文の優先度が TRIGMPRI 以上
     * <li>FIRST は数えた電文が 0 から 1 に、DEPTH は TRIGDPTH に達した。どちらも待ち行列を入力で開いている
     *     ものが無い (OPPROCS が 0)。EVERY は毎回
     * <li>開始待ち行列があり、入力で開いているもの (trigger monitor) があり、PUT が禁じられていない
     * <li>処理の定義がある
     * </ul>
     * DEPTH で起動したら TRIGGER を切る。条件を満たさないときは何も置かない (実機は一部の場合に console へ
     * 知らせを出すが、ここでは出さない)。
     */
    private void trigger(LocalQueue q, TriggerDefinition t, LocalQueue.Entry entry, int before) {
        if (!q.triggerControl || q.getInhibited || entry.priority() < t.messagePriority()) {
            return;
        }
        boolean fire = switch (t.type()) {
            case FIRST -> before == 0 && q.openInput == 0;
            case EVERY -> true;
            case DEPTH -> before + 1 >= t.depth() && q.openInput == 0;
        };
        if (!fire) {
            return;
        }
        LocalQueue init = queues.get(t.initiationQueue());
        ProcessDefinition process = processes.get(t.processName());
        if (init == null || init == q || init.openInput == 0 || init.putInhibited || process == null) {
            return;
        }
        if (t.type() == TriggerDefinition.Type.DEPTH) {
            q.triggerControl = false;
        }
        byte[] d = MqMessage.defaultDescriptor(codePage);
        Structure.writeInteger(d, 4, 1);
        System.arraycopy(Structure.padded(codePage, "MQTRIG", 8), 0, d, Structure.MD_FORMAT, 8);
        Structure.writeInteger(d, Structure.MD_PERSISTENCE, MqConstants.MQPER_NOT_PERSISTENT);
        System.arraycopy(newMessageId(), 0, d, Structure.MD_MSG_ID, Structure.ID_LENGTH);
        setPutTime(d);
        LocalQueue.Entry message = entry(init, new MqMessage(d, triggerMessage(q, t, process)));
        init.insert(message);
    }

    /** 起動電文 MQTM 1 版 (684 byte)。欄の並びは公開文書の MQTM の説明と写し句 CMQTMV (P-250) による。 */
    private byte[] triggerMessage(LocalQueue q, TriggerDefinition t, ProcessDefinition process) {
        ByteBuffer tm = ByteBuffer.allocate(TriggerMessage.LENGTH);
        tm.put(Structure.padded(codePage, "TM", 4));
        tm.putInt(1);
        tm.put(Structure.padded(codePage, q.name(), Structure.NAME_LENGTH));
        tm.put(Structure.padded(codePage, process.name(), Structure.NAME_LENGTH));
        tm.put(Structure.padded(codePage, t.data(), 64));
        tm.putInt(process.applicationType());
        tm.put(Structure.padded(codePage, process.applicationId(), 256));
        tm.put(Structure.padded(codePage, process.environmentData(), 128));
        tm.put(Structure.padded(codePage, process.userData(), 128));
        return tm.array();
    }

    /** 待つ。錠を持って呼ぶ。待てる時間が残っていれば true。 */
    long await(long nanos) throws InterruptedException {
        return arrived.awaitNanos(nanos);
    }

    static boolean isZero(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    static long toNanos(int milliseconds) {
        return TimeUnit.MILLISECONDS.toNanos(milliseconds);
    }
}
