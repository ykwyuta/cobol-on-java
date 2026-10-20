package dev.cobolonjava.mq;

import static dev.cobolonjava.mq.MqConstants.*;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.interop.JavaCallContext;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.storage.DataView;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * MQI の呼び出し ({@code CALL 'MQOPEN' USING ...}) を、{@link MqQueueManager} の上で動かす (設計 88 §4)。
 *
 * <p>{@link #register} で program catalog に {@code MQCONN} などを Java のプログラムとして置く。IMS の
 * {@code CBLTDLI} を {@code ImsRegion.register} が置くのと同じ形である。引数は z/OS の形の記憶域のまま受け、
 * 完了コードと理由コードを最後の 2 つの引数へ書く。
 *
 * <p>読み方の多くは Zeus (d2f1cfc の {@code crates/zeus-mq/src/mqi.rs}) から移した: 引数の並び、構造体の目印と版の
 * 確かめ方、{@code MQHC_DEF_HCONN} を暗黙の接続とする扱い。Zeus は IBM MQ の client library を呼ぶので、
 * 待ち行列の振る舞い (優先度の順、同期点、待ち、切り詰め) はここで公開文書から書いた (暫定判断 P-251〜P-253)。
 *
 * <p><b>持たない選択は断る</b>。publish/subscribe、browse、分配リスト、文脈の受け渡し (PASS / SET)、電文のグループ、
 * 報告電文などを指定されたら {@link UnsupportedOperationException} を投げる。それらしい理由コードを返すと、資産は
 * 「MQ がそう言った」として別の経路へ進んでしまうからである。
 */
public final class Mqi {

    /** 置く呼び出しの名前。 */
    public static final List<String> CALLS = List.of("MQCONN", "MQCONNX", "MQDISC", "MQOPEN", "MQCLOSE",
            "MQPUT", "MQPUT1", "MQGET", "MQINQ", "MQCMIT", "MQBACK");

    private static final int KNOWN_OPEN_OPTIONS = 0xffff;
    private static final int SUPPORTED_OPEN_OPTIONS = MQOO_INPUT_AS_Q_DEF | MQOO_INPUT_SHARED | MQOO_INPUT_EXCLUSIVE
            | MQOO_OUTPUT | MQOO_INQUIRE | MQOO_SAVE_ALL_CONTEXT | MQOO_FAIL_IF_QUIESCING
            | MQOO_BIND_ON_OPEN | MQOO_BIND_NOT_FIXED;
    /** MQOO_PASS_ALL_CONTEXT は「この handle で PASS の PUT をしてよい」という許しにすぎないので、開くことは受ける。 */
    private static final int PERMISSION_OPEN_OPTIONS = MQOO_PASS_IDENTITY_CONTEXT | MQOO_PASS_ALL_CONTEXT;

    private static final int SUPPORTED_PUT_OPTIONS = MQPMO_SYNCPOINT | MQPMO_NO_SYNCPOINT | MQPMO_DEFAULT_CONTEXT
            | MQPMO_NEW_MSG_ID | MQPMO_NEW_CORREL_ID | MQPMO_FAIL_IF_QUIESCING | MQPMO_NO_CONTEXT;
    private static final int KNOWN_PUT_OPTIONS = 0xffff;

    private static final int SUPPORTED_GET_OPTIONS = MQGMO_WAIT | MQGMO_SYNCPOINT | MQGMO_NO_SYNCPOINT
            | MQGMO_ACCEPT_TRUNCATED_MSG | MQGMO_SYNCPOINT_IF_PERSISTENT | MQGMO_FAIL_IF_QUIESCING | MQGMO_CONVERT;
    private static final int KNOWN_GET_OPTIONS = 0x7ffff;

    private final MqQueueManager manager;

    private Mqi(MqQueueManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    /** MQI の呼び出しを program catalog に置く。 */
    public static ProgramCatalog.Builder register(ProgramCatalog.Builder builder, MqQueueManager manager) {
        Mqi mqi = new Mqi(manager);
        return builder
                .javaProgram("MQCONN", () -> mqi::mqconn)
                .javaProgram("MQCONNX", () -> mqi::mqconnx)
                .javaProgram("MQDISC", () -> mqi::mqdisc)
                .javaProgram("MQOPEN", () -> mqi::mqopen)
                .javaProgram("MQCLOSE", () -> mqi::mqclose)
                .javaProgram("MQPUT", () -> mqi::mqput)
                .javaProgram("MQPUT1", () -> mqi::mqput1)
                .javaProgram("MQGET", () -> mqi::mqget)
                .javaProgram("MQINQ", () -> mqi::mqinq)
                .javaProgram("MQCMIT", () -> mqi::mqcmit)
                .javaProgram("MQBACK", () -> mqi::mqback);
    }

    /** 1 回の呼び出しの引数。完了コードと理由コードの置き場を覚えておく。 */
    private final class Call {

        final JavaCallContext context;
        final List<DataView> args;
        final String name;
        final int completion;

        Call(JavaCallContext context, List<DataView> args, String name, int count) {
            if (args.size() != count) {
                throw new IllegalArgumentException(name + " takes " + count + " parameters, but " + args.size()
                        + " were passed");
            }
            if (!context.codePage().name().equals(manager.codePage().name())) {
                throw new IllegalStateException(name + ": the program's code page " + context.codePage().name()
                        + " differs from the queue manager's " + manager.codePage().name());
            }
            this.context = context;
            this.args = args;
            this.name = name;
            this.completion = count - 2;
        }

        int integer(int i) {
            DataView v = arg(i, 4);
            return Structure.readInteger(v, 0);
        }

        void setInteger(int i, int value) {
            Structure.writeInteger(arg(i, 4), 0, value);
        }

        DataView arg(int i, int length) {
            DataView v = args.get(i);
            if (v.length() < length) {
                throw new IllegalArgumentException(name + " parameter " + (i + 1) + " is " + v.length()
                        + " bytes, shorter than " + length);
            }
            return v;
        }

        CodePage codePage() {
            return manager.codePage();
        }

        void done(int cc, int rc) {
            setInteger(completion, cc);
            setInteger(completion + 1, rc);
        }

        void ok() {
            done(MQCC_OK, MQRC_NONE);
        }

        void failed(int rc) {
            done(MQCC_FAILED, rc);
        }

        /** 第 i 引数の接続。無ければ MQRC_HCONN_ERROR を書いて null。 */
        MqConnection connection(int i) {
            MqConnection c = manager.connection(context.runUnit(), integer(i));
            if (c == null) {
                failed(MQRC_HCONN_ERROR);
            } else {
                context.enlist(c.resource);
            }
            return c;
        }
    }

    // ---- 接続 ----------------------------------------------------------------

    /** MQCONN (QMgrName, Hconn, CompCode, Reason)。 */
    void mqconn(JavaCallContext context, List<DataView> args) {
        connect(new Call(context, args, "MQCONN", 4), 1);
    }

    /**
     * MQCONNX (QMgrName, ConnectOpts, Hconn, CompCode, Reason)。接続の選択 (MQCNO) は見ない: client の経路も
     * 共有の選択も、region の中の待ち行列管理者には無いからである (暫定判断 P-252)。
     */
    void mqconnx(JavaCallContext context, List<DataView> args) {
        connect(new Call(context, args, "MQCONNX", 5), 2);
    }

    private void connect(Call call, int hconnArg) {
        String qmgr = Structure.stripTrailingBlanks(call.codePage().decode(
                call.arg(0, Structure.NAME_LENGTH).subView(0, Structure.NAME_LENGTH).toByteArray()));
        manager.lock.lock();
        try {
            if (!qmgr.isEmpty() && !qmgr.equals(manager.name())) {
                call.setInteger(hconnArg, MQHC_UNUSABLE_HCONN);
                call.failed(MQRC_Q_MGR_NAME_ERROR);
                return;
            }
            MqConnection c = manager.connection(call.context.runUnit(), MQHC_DEF_HCONN);
            call.context.enlist(c.resource);
            call.setInteger(hconnArg, c.handle());
            if (c.explicit) {
                call.done(MQCC_WARNING, MQRC_ALREADY_CONNECTED);
            } else {
                c.explicit = true;
                call.ok();
            }
        } finally {
            manager.lock.unlock();
        }
    }

    /**
     * MQDISC (Hconn, CompCode, Reason)。作業単位を確約し、開いた handle を閉じる。z/OS の batch で MQDISC が
     * 確約するか取り消すかは環境 (RRS の有無) で違う。ここでは確約とした (暫定判断 P-252)。
     */
    void mqdisc(JavaCallContext context, List<DataView> args) {
        Call call = new Call(context, args, "MQDISC", 3);
        manager.lock.lock();
        try {
            MqConnection c = call.connection(0);
            if (c == null) {
                return;
            }
            c.end(true);
            manager.forget(c);
            context.delist(c.resource);
            call.setInteger(0, MQHC_UNUSABLE_HCONN);
            call.ok();
        } finally {
            manager.lock.unlock();
        }
    }

    /** MQCMIT (Hconn, CompCode, Reason)。 */
    void mqcmit(JavaCallContext context, List<DataView> args) {
        Call call = new Call(context, args, "MQCMIT", 3);
        manager.lock.lock();
        try {
            MqConnection c = call.connection(0);
            if (c != null) {
                c.commit();
                call.ok();
            }
        } finally {
            manager.lock.unlock();
        }
    }

    /** MQBACK (Hconn, CompCode, Reason)。 */
    void mqback(JavaCallContext context, List<DataView> args) {
        Call call = new Call(context, args, "MQBACK", 3);
        manager.lock.lock();
        try {
            MqConnection c = call.connection(0);
            if (c != null) {
                c.backout();
                call.ok();
            }
        } finally {
            manager.lock.unlock();
        }
    }

    // ---- 開く・閉じる --------------------------------------------------------

    /** MQOPEN (Hconn, ObjDesc, Options, Hobj, CompCode, Reason)。 */
    void mqopen(JavaCallContext context, List<DataView> args) {
        Call call = new Call(context, args, "MQOPEN", 6);
        manager.lock.lock();
        try {
            call.setInteger(3, MQHO_UNUSABLE_HOBJ);
            MqConnection c = call.connection(0);
            if (c == null) {
                return;
            }
            Structure od = Structure.of(Structure.Kind.OD, args.get(1), call.codePage());
            if (od == null) {
                call.failed(MQRC_OD_ERROR);
                return;
            }
            int options = call.integer(2);
            int rc = checkOpenOptions(options);
            if (rc != MQRC_NONE) {
                call.failed(rc);
                return;
            }
            LocalQueue q = resolve(call, od);
            if (q == null) {
                return;
            }
            boolean input = (options & (MQOO_INPUT_AS_Q_DEF | MQOO_INPUT_SHARED | MQOO_INPUT_EXCLUSIVE)) != 0;
            if (input && (q.exclusive || ((options & MQOO_INPUT_EXCLUSIVE) != 0 && q.openInput > 0))) {
                call.failed(MQRC_OBJECT_IN_USE);
                return;
            }
            MqConnection.Handle h = c.open(q, options);
            call.setInteger(3, h.hobj());
            call.ok();
        } finally {
            manager.lock.unlock();
        }
    }

    /**
     * MQOPEN の選択を確かめる。誤りなら理由コード。入力の選択を 2 つ以上、または何も開かない選択は
     * MQRC_OPTIONS_ERROR (公開文書の MQOPEN の Options の説明)。
     */
    private static int checkOpenOptions(int options) {
        if ((options & ~KNOWN_OPEN_OPTIONS) != 0) {
            return MQRC_OPTIONS_ERROR;
        }
        int inputs = Integer.bitCount(options & (MQOO_INPUT_AS_Q_DEF | MQOO_INPUT_SHARED | MQOO_INPUT_EXCLUSIVE));
        if (inputs > 1) {
            return MQRC_OPTIONS_ERROR;
        }
        if ((options & (MQOO_INPUT_AS_Q_DEF | MQOO_INPUT_SHARED | MQOO_INPUT_EXCLUSIVE | MQOO_BROWSE | MQOO_OUTPUT
                | MQOO_INQUIRE | MQOO_SET)) == 0) {
            return MQRC_OPTIONS_ERROR;
        }
        int unsupported = options & ~(SUPPORTED_OPEN_OPTIONS | PERMISSION_OPEN_OPTIONS);
        if (unsupported != 0) {
            throw new UnsupportedOperationException("MQOPEN options are not supported yet: " + unsupported);
        }
        return MQRC_NONE;
    }

    /** 対象記述子の待ち行列。無ければ理由コードを書いて null。 */
    private LocalQueue resolve(Call call, Structure od) {
        if (od.integer(Structure.OD_OBJECT_TYPE) != MQOT_Q) {
            throw new UnsupportedOperationException(call.name + ": only queues (MQOT_Q) are supported yet, not "
                    + "object type " + od.integer(Structure.OD_OBJECT_TYPE));
        }
        if (od.version() >= 2 && od.integer(Structure.OD_RECS_PRESENT) != 0) {
            throw new UnsupportedOperationException(call.name + ": distribution lists are not supported yet");
        }
        String qmgr = od.text(Structure.OD_OBJECT_Q_MGR_NAME, Structure.NAME_LENGTH);
        if (!qmgr.isEmpty() && !qmgr.equals(manager.name())) {
            // 送信先の待ち行列管理者 (transmission queue) を持たないので、ほかの名前は知らない管理者である
            call.failed(MQRC_UNKNOWN_REMOTE_Q_MGR);
            return null;
        }
        String name = od.text(Structure.OD_OBJECT_NAME, Structure.NAME_LENGTH);
        LocalQueue q = manager.lookup(name);
        if (q == null) {
            call.failed(MQRC_UNKNOWN_OBJECT_NAME);
            return null;
        }
        if (od.version() >= 3) {
            od.setText(Structure.OD_RESOLVED_Q_NAME, Structure.NAME_LENGTH, q.name());
            od.setText(Structure.OD_RESOLVED_Q_MGR_NAME, Structure.NAME_LENGTH, manager.name());
        }
        return q;
    }

    /** MQCLOSE (Hconn, Hobj, Options, CompCode, Reason)。 */
    void mqclose(JavaCallContext context, List<DataView> args) {
        Call call = new Call(context, args, "MQCLOSE", 5);
        manager.lock.lock();
        try {
            MqConnection c = call.connection(0);
            if (c == null) {
                return;
            }
            MqConnection.Handle h = c.handle(call.integer(1));
            if (h == null) {
                call.failed(MQRC_HOBJ_ERROR);
                return;
            }
            int options = call.integer(2);
            if (options == MQCO_DELETE || options == MQCO_DELETE_PURGE) {
                // 定義済みの局所待ち行列は動的な待ち行列ではないので、消す選択は通らない
                call.failed(MQRC_OPTION_NOT_VALID_FOR_TYPE);
                return;
            }
            if (options != MQCO_NONE) {
                call.failed(MQRC_OPTIONS_ERROR);
                return;
            }
            c.close(h);
            call.setInteger(1, MQHO_UNUSABLE_HOBJ);
            call.ok();
        } finally {
            manager.lock.unlock();
        }
    }

    // ---- 置く ----------------------------------------------------------------

    /** MQPUT (Hconn, Hobj, MsgDesc, PutMsgOpts, BufferLength, Buffer, CompCode, Reason)。 */
    void mqput(JavaCallContext context, List<DataView> args) {
        Call call = new Call(context, args, "MQPUT", 8);
        manager.lock.lock();
        try {
            MqConnection c = call.connection(0);
            if (c == null) {
                return;
            }
            MqConnection.Handle h = c.handle(call.integer(1));
            if (h == null) {
                call.failed(MQRC_HOBJ_ERROR);
                return;
            }
            if (!h.has(MQOO_OUTPUT)) {
                call.failed(MQRC_NOT_OPEN_FOR_OUTPUT);
                return;
            }
            put(call, c, h.queue(), 2);
        } finally {
            manager.lock.unlock();
        }
    }

    /** MQPUT1 (Hconn, ObjDesc, MsgDesc, PutMsgOpts, BufferLength, Buffer, CompCode, Reason)。 */
    void mqput1(JavaCallContext context, List<DataView> args) {
        Call call = new Call(context, args, "MQPUT1", 8);
        manager.lock.lock();
        try {
            MqConnection c = call.connection(0);
            if (c == null) {
                return;
            }
            Structure od = Structure.of(Structure.Kind.OD, args.get(1), call.codePage());
            if (od == null) {
                call.failed(MQRC_OD_ERROR);
                return;
            }
            LocalQueue q = resolve(call, od);
            if (q == null) {
                return;
            }
            put(call, c, q, 2);
        } finally {
            manager.lock.unlock();
        }
    }

    /**
     * MQPUT と MQPUT1 の共通の部分。{@code md} は電文記述子の引数の位置で、PMO、長さ、中身がそれに続く。
     *
     * <p>確かめる順は公開文書の理由コードの説明から決めた: 構造体、選択、長さ、記述子の値、待ち行列の状態。
     * どの誤りでも電文は置かない。
     */
    private void put(Call call, MqConnection c, LocalQueue q, int md) {
        Structure mdesc = Structure.of(Structure.Kind.MD, call.args.get(md), call.codePage());
        if (mdesc == null) {
            call.failed(MQRC_MD_ERROR);
            return;
        }
        Structure pmo = Structure.of(Structure.Kind.PMO, call.args.get(md + 1), call.codePage());
        if (pmo == null) {
            call.failed(MQRC_PMO_ERROR);
            return;
        }
        int options = pmo.integer(Structure.PMO_OPTIONS);
        if ((options & ~KNOWN_PUT_OPTIONS) != 0
                || ((options & MQPMO_SYNCPOINT) != 0 && (options & MQPMO_NO_SYNCPOINT) != 0)
                || Integer.bitCount(options & (MQPMO_DEFAULT_CONTEXT | MQPMO_NO_CONTEXT | MQPMO_PASS_IDENTITY_CONTEXT
                | MQPMO_PASS_ALL_CONTEXT | MQPMO_SET_IDENTITY_CONTEXT | MQPMO_SET_ALL_CONTEXT)) > 1) {
            call.failed(MQRC_OPTIONS_ERROR);
            return;
        }
        int unsupported = options & ~SUPPORTED_PUT_OPTIONS;
        if (unsupported != 0) {
            throw new UnsupportedOperationException(call.name + " options are not supported yet: " + unsupported);
        }
        if (pmo.version() >= 2 && pmo.integer(Structure.PMO_RECS_PRESENT) != 0) {
            throw new UnsupportedOperationException(call.name + ": put message records are not supported yet");
        }
        int length = call.integer(md + 2);
        if (length < 0) {
            call.failed(MQRC_BUFFER_LENGTH_ERROR);
            return;
        }
        byte[] data = call.arg(md + 3, length).subView(0, length).toByteArray();
        int rc = checkDescriptor(call, mdesc);
        if (rc != MQRC_NONE) {
            call.failed(rc);
            return;
        }
        if (q.putInhibited) {
            call.failed(MQRC_PUT_INHIBITED);
            return;
        }
        if (length > q.definition.maxMessageLength()) {
            call.failed(MQRC_MSG_TOO_BIG_FOR_Q);
            return;
        }
        q.expire(manager.now());
        if (q.depth() >= q.definition.maxDepth()) {
            call.failed(MQRC_Q_FULL);
            return;
        }

        // 記述子の出力の欄: MsgId、CorrelId、文脈 (公開文書の MQPUT の MsgDesc の説明)
        if ((options & MQPMO_NEW_MSG_ID) != 0
                || MqQueueManager.isZero(mdesc.bytes(Structure.MD_MSG_ID, Structure.ID_LENGTH), 0,
                Structure.ID_LENGTH)) {
            mdesc.setBytes(Structure.MD_MSG_ID, manager.newMessageId());
        }
        if ((options & MQPMO_NEW_CORREL_ID) != 0) {
            mdesc.setBytes(Structure.MD_CORREL_ID, manager.newMessageId());
        }
        context(mdesc, (options & MQPMO_NO_CONTEXT) != 0);

        byte[] descriptor = MqMessage.defaultDescriptor(call.codePage());
        byte[] given = mdesc.bytes(0, mdesc.version() == 1 ? 324 : Structure.MD_LENGTH);
        System.arraycopy(given, 0, descriptor, 0, given.length);
        // 記憶する記述子は 2 版の形にする。1 版で置かれた電文を 2 版で取ったときに後ろの欄が既定値になる
        Structure.writeInteger(descriptor, 4, 2);
        LocalQueue.Entry entry = manager.entry(q, new MqMessage(descriptor, data));
        c.put(q, entry, inSyncpoint(options, MQPMO_NO_SYNCPOINT));

        pmo.setText(Structure.PMO_RESOLVED_Q_NAME, Structure.NAME_LENGTH, q.name());
        pmo.setText(Structure.PMO_RESOLVED_Q_MGR_NAME, Structure.NAME_LENGTH, manager.name());
        call.ok();
    }

    /** 置く電文記述子の値を確かめる。 */
    private static int checkDescriptor(Call call, Structure md) {
        int report = md.integer(Structure.MD_REPORT);
        if ((report & ~(MQRO_PASS_MSG_ID | MQRO_PASS_CORREL_ID)) != 0) {
            throw new UnsupportedOperationException(call.name + ": report messages are not supported yet (Report "
                    + report + ")");
        }
        int expiry = md.integer(Structure.MD_EXPIRY);
        if (expiry <= 0 && expiry != MQEI_UNLIMITED) {
            return MQRC_EXPIRY_ERROR;
        }
        int priority = md.integer(Structure.MD_PRIORITY);
        if (priority < MQPRI_PRIORITY_AS_Q_DEF || priority > 9) {
            return MQRC_PRIORITY_ERROR;
        }
        int persistence = md.integer(Structure.MD_PERSISTENCE);
        if (persistence < MQPER_NOT_PERSISTENT || persistence > MQPER_PERSISTENCE_AS_Q_DEF) {
            return MQRC_PERSISTENCE_ERROR;
        }
        if (md.version() >= 2 && md.integer(Structure.MD_MSG_FLAGS) != 0) {
            throw new UnsupportedOperationException(call.name + ": message groups and segments are not supported yet");
        }
        return MQRC_NONE;
    }

    /**
     * 文脈の欄を置く。既定の文脈では PutApplType と PutDate / PutTime を置き、利用者の名前・応用の名前・
     * 会計の欄は空白 (または 0) にする。実機は task の利用者 ID や program 名を置くが、region がそれを
     * 持たないので空けておく (暫定判断 P-253)。MQPMO_NO_CONTEXT ではどれも空ける。
     */
    private void context(Structure md, boolean none) {
        CodePage cp = manager.codePage();
        md.setText(Structure.MD_USER_IDENTIFIER, 12, "");
        md.setBytes(Structure.MD_ACCOUNTING_TOKEN, new byte[32]);
        md.setText(Structure.MD_APPL_IDENTITY_DATA, 32, "");
        md.setText(Structure.MD_PUT_APPL_NAME, 28, "");
        md.setText(Structure.MD_APPL_ORIGIN_DATA, 4, "");
        if (none) {
            md.setInteger(Structure.MD_PUT_APPL_TYPE, MQAT_NO_CONTEXT);
            md.setText(Structure.MD_PUT_DATE, 8, "");
            md.setText(Structure.MD_PUT_TIME, 8, "");
            return;
        }
        md.setInteger(Structure.MD_PUT_APPL_TYPE, manager.applicationType());
        byte[] times = MqMessage.defaultDescriptor(cp);
        manager.setPutTime(times);
        md.setBytes(Structure.MD_PUT_DATE, Arrays.copyOfRange(times, Structure.MD_PUT_DATE, Structure.MD_PUT_DATE + 16));
    }

    /**
     * 同期点の下か。どちらも指定しなければ z/OS では同期点の下である (公開文書の MQPMO_SYNCPOINT と MQGMO_SYNCPOINT の
     * 説明。ほかの環境では逆なので、移した資産でここを当てにしていれば振る舞いが変わる)。
     */
    private static boolean inSyncpoint(int options, int noSyncpoint) {
        return (options & noSyncpoint) == 0;
    }

    // ---- 取る ----------------------------------------------------------------

    /** MQGET (Hconn, Hobj, MsgDesc, GetMsgOpts, BufferLength, Buffer, DataLength, CompCode, Reason)。 */
    void mqget(JavaCallContext context, List<DataView> args) {
        Call call = new Call(context, args, "MQGET", 9);
        manager.lock.lock();
        try {
            MqConnection c = call.connection(0);
            if (c == null) {
                return;
            }
            MqConnection.Handle h = c.handle(call.integer(1));
            if (h == null) {
                call.failed(MQRC_HOBJ_ERROR);
                return;
            }
            Structure md = Structure.of(Structure.Kind.MD, args.get(2), call.codePage());
            if (md == null) {
                call.failed(MQRC_MD_ERROR);
                return;
            }
            Structure gmo = Structure.of(Structure.Kind.GMO, args.get(3), call.codePage());
            if (gmo == null) {
                call.failed(MQRC_GMO_ERROR);
                return;
            }
            int options = gmo.integer(Structure.GMO_OPTIONS);
            if ((options & ~KNOWN_GET_OPTIONS) != 0
                    || Integer.bitCount(options & (MQGMO_SYNCPOINT | MQGMO_NO_SYNCPOINT
                    | MQGMO_SYNCPOINT_IF_PERSISTENT)) > 1) {
                call.failed(MQRC_OPTIONS_ERROR);
                return;
            }
            int unsupported = options & ~SUPPORTED_GET_OPTIONS;
            if (unsupported != 0) {
                throw new UnsupportedOperationException("MQGET options are not supported yet: " + unsupported);
            }
            if (!h.input()) {
                call.failed(MQRC_NOT_OPEN_FOR_INPUT);
                return;
            }
            int wait = gmo.integer(Structure.GMO_WAIT_INTERVAL);
            if ((options & MQGMO_WAIT) != 0 && wait < MQWI_UNLIMITED) {
                call.failed(MQRC_WAIT_INTERVAL_ERROR);
                return;
            }
            int length = call.integer(4);
            if (length < 0) {
                call.failed(MQRC_BUFFER_LENGTH_ERROR);
                return;
            }
            DataView buffer = call.arg(5, length);
            int match = gmo.version() >= 2 ? gmo.integer(Structure.GMO_MATCH_OPTIONS)
                    : MQMO_MATCH_MSG_ID | MQMO_MATCH_CORREL_ID;
            if ((match & ~(MQMO_MATCH_MSG_ID | MQMO_MATCH_CORREL_ID)) != 0) {
                if ((match & ~0x3f) != 0) {
                    call.failed(MQRC_MATCH_OPTIONS_ERROR);
                    return;
                }
                throw new UnsupportedOperationException("MQGET match options are not supported yet: " + match);
            }
            // MQMI_NONE / MQCI_NONE (全部 0) は「問わない」
            byte[] msgId = md.bytes(Structure.MD_MSG_ID, Structure.ID_LENGTH);
            byte[] correlId = md.bytes(Structure.MD_CORREL_ID, Structure.ID_LENGTH);
            byte[] wantMsgId = (match & MQMO_MATCH_MSG_ID) != 0 && !MqQueueManager.isZero(msgId, 0, 24) ? msgId : null;
            byte[] wantCorrelId = (match & MQMO_MATCH_CORREL_ID) != 0 && !MqQueueManager.isZero(correlId, 0, 24)
                    ? correlId : null;
            int requestedCcsid = md.integer(Structure.MD_CCSID);

            LocalQueue q = h.queue();
            if (q.getInhibited) {
                call.failed(MQRC_GET_INHIBITED);
                return;
            }
            LocalQueue.Entry entry = await(q, wantMsgId, wantCorrelId,
                    (options & MQGMO_WAIT) == 0 ? 0 : wait);
            if (entry == null) {
                call.failed(q.getInhibited ? MQRC_GET_INHIBITED : MQRC_NO_MSG_AVAILABLE);
                return;
            }
            MqMessage message = entry.message();
            if ((options & MQGMO_CONVERT) != 0) {
                int wanted = requestedCcsid == MQCCSI_Q_MGR ? manager.ccsid() : requestedCcsid;
                if (wanted != message.codedCharSetId()) {
                    throw new UnsupportedOperationException("MQGET: converting message data from CCSID "
                            + message.codedCharSetId() + " to " + wanted + " is not supported yet");
                }
            }
            int dataLength = message.length();
            boolean truncated = dataLength > length;
            boolean accept = (options & MQGMO_ACCEPT_TRUNCATED_MSG) != 0;
            if (!truncated || accept) {
                boolean syncpoint = (options & MQGMO_SYNCPOINT_IF_PERSISTENT) != 0
                        ? message.persistence() == MQPER_PERSISTENT
                        : inSyncpoint(options, MQGMO_NO_SYNCPOINT);
                c.take(q, entry, syncpoint);
            }
            // 切り詰めで取らなかったときも、記述子と入るだけの中身は返す (公開文書の MQRC_TRUNCATED_MSG_FAILED)
            byte[] d = remainingExpiry(entry);
            md.setBytes(0, Arrays.copyOf(d, md.version() == 1 ? 324 : Structure.MD_LENGTH));
            md.setInteger(4, md.version());
            byte[] data = message.data();
            int n = Math.min(dataLength, length);
            System.arraycopy(data, 0, buffer.storage().array(), buffer.offset(), n);
            call.setInteger(6, dataLength);
            gmo.setText(Structure.GMO_RESOLVED_Q_NAME, Structure.NAME_LENGTH, q.name());
            if (gmo.version() >= 2) {
                // グループにも区分にも入っていない電文 (MQGS_NOT_IN_GROUP、MQSS_NOT_A_SEGMENT、MQSEG_INHIBITED)
                gmo.setText(Structure.GMO_GROUP_STATUS, 3, "   ");
            }
            if (gmo.version() >= 3) {
                gmo.setInteger(Structure.GMO_RETURNED_LENGTH, n);
            }
            if (!truncated) {
                call.ok();
            } else {
                call.done(MQCC_WARNING, accept ? MQRC_TRUNCATED_MSG_ACCEPTED : MQRC_TRUNCATED_MSG_FAILED);
            }
        } finally {
            manager.lock.unlock();
        }
    }

    /**
     * 渡せる電文を待つ。{@code waitMillis} が 0 なら待たない、MQWI_UNLIMITED なら限りなく待つ。
     *
     * <p>待つ時間は壁時計で数える。待ち行列管理者の時計 (試験で止められる) ではない。止めた時計では
     * 時間切れにならないからである。
     */
    private LocalQueue.Entry await(LocalQueue q, byte[] msgId, byte[] correlId, int waitMillis) {
        long remaining = waitMillis == MQWI_UNLIMITED ? Long.MAX_VALUE : MqQueueManager.toNanos(waitMillis);
        while (true) {
            q.expire(manager.now());
            LocalQueue.Entry e = q.getInhibited ? null : q.select(msgId, correlId);
            if (e != null || remaining <= 0 || q.getInhibited) {
                return e;
            }
            try {
                remaining = manager.await(remaining);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /** 返す記述子。Expiry は残りの時間 (1/10 秒、切り上げ) に置き換える (公開文書の MQMD の Expiry の説明)。 */
    private byte[] remainingExpiry(LocalQueue.Entry entry) {
        byte[] d = entry.message().descriptor();
        if (entry.expiresAt() != null) {
            Instant now = manager.now();
            long millis = Math.max(0, Duration.between(now, entry.expiresAt()).toMillis());
            Structure.writeInteger(d, Structure.MD_EXPIRY, (int) Math.max(1, (millis + 99) / 100));
        }
        return d;
    }

    // ---- 属性 ----------------------------------------------------------------

    /**
     * MQINQ (Hconn, Hobj, SelectorCount, Selectors, IntAttrCount, IntAttrs, CharAttrLength, CharAttrs, CompCode,
     * Reason)。待ち行列の属性のうち、深さ・上限・禁止・開いている数・名前・記述を返す。ほかの選択子は断る。
     */
    void mqinq(JavaCallContext context, List<DataView> args) {
        Call call = new Call(context, args, "MQINQ", 10);
        manager.lock.lock();
        try {
            MqConnection c = call.connection(0);
            if (c == null) {
                return;
            }
            MqConnection.Handle h = c.handle(call.integer(1));
            if (h == null) {
                call.failed(MQRC_HOBJ_ERROR);
                return;
            }
            if (!h.has(MQOO_INQUIRE)) {
                call.failed(MQRC_NOT_OPEN_FOR_INQUIRE);
                return;
            }
            int selectorCount = call.integer(2);
            if (selectorCount < 0 || selectorCount > 256) {
                call.failed(MQRC_SELECTOR_COUNT_ERROR);
                return;
            }
            int intCount = call.integer(4);
            if (intCount < 0) {
                call.failed(MQRC_INT_ATTR_COUNT_ERROR);
                return;
            }
            int charLength = call.integer(6);
            if (charLength < 0) {
                call.failed(MQRC_CHAR_ATTR_LENGTH_ERROR);
                return;
            }
            DataView selectors = call.arg(3, selectorCount * 4);
            DataView ints = call.arg(5, intCount * 4);
            DataView chars = call.arg(7, charLength);
            LocalQueue q = h.queue();
            q.expire(manager.now());
            int intAt = 0;
            int charAt = 0;
            int warning = MQRC_NONE;
            for (int i = 0; i < selectorCount; i++) {
                int selector = Structure.readInteger(selectors, i * 4);
                if (selector >= 2001 && selector <= 4000) {
                    String value;
                    int width;
                    if (selector == MQCA_Q_NAME) {
                        value = q.name();
                        width = Structure.NAME_LENGTH;
                    } else if (selector == MQCA_Q_DESC) {
                        value = q.definition.description();
                        width = 64;
                    } else {
                        throw new UnsupportedOperationException("MQINQ selector " + selector + " is not supported yet");
                    }
                    if (charAt + width > charLength) {
                        warning = MQRC_CHAR_ATTRS_TOO_SHORT;
                        continue;
                    }
                    byte[] text = Structure.padded(call.codePage(), value, width);
                    System.arraycopy(text, 0, chars.storage().array(), chars.offset() + charAt, width);
                    charAt += width;
                } else if (selector >= 1 && selector <= 2000) {
                    int value = switch (selector) {
                        case MQIA_CURRENT_Q_DEPTH -> q.depth();
                        case MQIA_MAX_Q_DEPTH -> q.definition.maxDepth();
                        case MQIA_MAX_MSG_LENGTH -> q.definition.maxMessageLength();
                        case MQIA_Q_TYPE -> MQQT_LOCAL;
                        case MQIA_INHIBIT_GET -> q.getInhibited ? MQQA_GET_INHIBITED : MQQA_GET_ALLOWED;
                        case MQIA_INHIBIT_PUT -> q.putInhibited ? MQQA_PUT_INHIBITED : MQQA_PUT_ALLOWED;
                        case MQIA_OPEN_INPUT_COUNT -> q.openInput;
                        case MQIA_OPEN_OUTPUT_COUNT -> q.openOutput;
                        default -> throw new UnsupportedOperationException(
                                "MQINQ selector " + selector + " is not supported yet");
                    };
                    if (intAt >= intCount) {
                        warning = MQRC_INT_ATTR_COUNT_TOO_SMALL;
                        continue;
                    }
                    Structure.writeInteger(ints, intAt * 4, value);
                    intAt++;
                } else {
                    call.failed(MQRC_SELECTOR_ERROR);
                    return;
                }
            }
            if (warning == MQRC_NONE) {
                call.ok();
            } else {
                call.done(MQCC_WARNING, warning);
            }
        } finally {
            manager.lock.unlock();
        }
    }
}
