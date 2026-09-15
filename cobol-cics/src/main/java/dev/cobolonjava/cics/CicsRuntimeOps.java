package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.program.ProgramTargetTransfer;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 生成コードがCICS commandへ使う低レベルruntime操作。 */
public final class CicsRuntimeOps {

    /** condition handlerへ移らず、次のCOBOL文へ進む。 */
    public static final int NO_CONDITION_TRANSFER = -1;
    private static final int HANDLE_CONDITION_FUNCTION = 0x0204;
    private static final int ASSIGN_FUNCTION = 0x0208;
    private static final int IGNORE_CONDITION_FUNCTION = 0x020A;
    private static final int PUSH_HANDLE_FUNCTION = 0x020C;
    private static final int POP_HANDLE_FUNCTION = 0x020E;
    /** 間隔制御群のfunction code。実機のEIBFNとは突き合わせていない (暫定判断 P-115)。 */
    private static final int ASKTIME_FUNCTION = 0x1002;
    /** BIF DEEDIT。CICS TS 5.6「Function codes of EXEC CICS commands」の表による。 */
    private static final int BIF_DEEDIT_FUNCTION = 0x2002;
    /** GET / PUT CONTAINER (CHANNEL)。実機の EIBFN とは突き合わせていない (暫定判断 P-125)。 */
    private static final int GET_CONTAINER_FUNCTION = 0x3414;
    private static final int PUT_CONTAINER_FUNCTION = 0x3416;
    /** ENQ / DEQ。実機の EIBFN とは突き合わせていない (暫定判断 P-128)。 */
    private static final int ENQ_FUNCTION = 0x1204;
    private static final int DEQ_FUNCTION = 0x1206;
    /** INQUIRE / SET TERMINAL (SPI)。CICS TS 5.6「Function codes of EXEC CICS commands」の表による。 */
    private static final int INQUIRE_TERMINAL_FUNCTION = 0x5202;
    private static final int SET_TERMINAL_FUNCTION = 0x5204;
    /** file control の命令の種類 (暫定判断 P-136)。生成コードが渡す。 */
    public static final int FILE_READ = 0;
    public static final int FILE_WRITE = 1;
    public static final int FILE_REWRITE = 2;
    public static final int FILE_DELETE = 3;
    public static final int FILE_UNLOCK = 4;
    public static final int FILE_STARTBR = 5;
    public static final int FILE_READNEXT = 6;
    public static final int FILE_READPREV = 7;
    public static final int FILE_ENDBR = 8;
    public static final int FILE_RESETBR = 9;
    /** file control の値を持たない option の印。 */
    public static final int FILE_GENERIC = 1;
    public static final int FILE_GTEQ = 2;
    public static final int FILE_EQUAL = 4;
    public static final int FILE_RRN = 8;
    public static final int FILE_UPDATE = 16;
    /** 種類の番号の順の命令名。 */
    public static final java.util.List<String> FILE_COMMANDS = java.util.List.of(
            "READ", "WRITE", "REWRITE", "DELETE", "UNLOCK", "STARTBR", "READNEXT", "READPREV", "ENDBR", "RESETBR");
    /** 種類の番号の順の function code。CICS TS 5.6「Function codes of EXEC CICS commands」の表による。 */
    private static final int[] FILE_FUNCTIONS = {
        0x0602, 0x0604, 0x0606, 0x0608, 0x060A, 0x060C, 0x060E, 0x0610, 0x0612, 0x0614};
    /** 一時記憶・一時データの命令の種類 (暫定判断 P-137)。生成コードが渡す。 */
    public static final int QUEUE_WRITEQ_TS = 0;
    public static final int QUEUE_READQ_TS = 1;
    public static final int QUEUE_DELETEQ_TS = 2;
    public static final int QUEUE_WRITEQ_TD = 3;
    public static final int QUEUE_READQ_TD = 4;
    public static final int QUEUE_DELETEQ_TD = 5;
    /** キューの命令の値を持たない option の印。 */
    public static final int QUEUE_REWRITE = 1;
    public static final int QUEUE_NEXT = 2;
    /** 種類の番号の順の命令名。 */
    public static final java.util.List<String> QUEUE_COMMANDS = java.util.List.of(
            "WRITEQ TS", "READQ TS", "DELETEQ TS", "WRITEQ TD", "READQ TD", "DELETEQ TD");
    /** 種類の番号の順の function code。CICS TS 5.6「Function codes of EXEC CICS commands」の表による。 */
    private static final int[] QUEUE_FUNCTIONS = {0x0A02, 0x0A04, 0x0A06, 0x0802, 0x0804, 0x0806};
    /** 間隔制御の命令の種類 (暫定判断 P-138)。生成コードが渡す。 */
    public static final int INTERVAL_START = 0;
    public static final int INTERVAL_RETRIEVE = 1;
    public static final int INTERVAL_CANCEL = 2;
    public static final java.util.List<String> INTERVAL_COMMANDS = java.util.List.of("START", "RETRIEVE", "CANCEL");
    /** START の満了の書き方。INTERVAL は書かなければ INTERVAL(0) と同じ。 */
    public static final int START_INTERVAL = 0;
    public static final int START_TIME = 1;
    public static final int START_AFTER = 2;
    public static final int START_AT = 3;
    /** START / RETRIEVE / CANCEL。CICS TS 5.6「Function codes of EXEC CICS commands」の表による。 */
    private static final int START_FUNCTION = 0x1008;
    private static final int RETRIEVE_FUNCTION = 0x100A;
    private static final int CANCEL_FUNCTION = 0x100C;
    /** これより前までの時刻を指す START は直ちに始まる。「Expiration times」の頁による。 */
    private static final java.time.Duration START_PAST_WINDOW = java.time.Duration.ofHours(6);
    /** 非同期 API の命令の種類 (暫定判断 P-140)。生成コードが渡す。 */
    public static final int ASYNC_RUN = 0;
    public static final int ASYNC_FETCH_ANY = 1;
    public static final int ASYNC_FETCH_CHILD = 2;
    public static final int ASYNC_FREE_CHILD = 3;
    public static final java.util.List<String> ASYNC_COMMANDS = java.util.List.of(
            "RUN TRANSID", "FETCH ANY", "FETCH CHILD", "FREE CHILD");
    /** 種類の番号の順の function code。CICS TS 5.6「Function codes of EXEC CICS commands」の表による。 */
    private static final int[] ASYNC_FUNCTIONS = {0x343E, 0x3444, 0x3442, 0x3446};
    /** INQUIRE ASSOCIATION (SPI)。CICS TS 5.6「Function codes of EXEC CICS commands」の表による。 */
    private static final int INQUIRE_ASSOCIATION_FUNCTION = 0xC402;
    private static final java.util.regex.Pattern CONTAINER_NAME =
            java.util.regex.Pattern.compile("[A-Z0-9_-]{1,16}");
    private static final int DELAY_FUNCTION = 0x1004;
    /** BMS 群の function code。実機の EIBFN とは突き合わせていない (暫定判断 P-118)。 */
    private static final int RECEIVE_MAP_FUNCTION = 0x1802;
    private static final int SEND_MAP_FUNCTION = 0x1804;
    private static final int SEND_TEXT_FUNCTION = 0x1806;
    private static final int SEND_CONTROL_FUNCTION = 0x1812;
    /** CICS TS 5.6「Function codes of EXEC CICS commands」の表で X'4A04'。X'104A' と取り違えていた。 */
    private static final int FORMATTIME_FUNCTION = 0x4A04;
    private static final int LINK_FUNCTION = 0x0E02;
    private static final int XCTL_FUNCTION = 0x0E04;
    private static final int RETURN_FUNCTION = 0x0E08;
    private static final int HANDLE_ABEND_FUNCTION = 0x0E0E;
    private static final int SYNCPOINT_FUNCTION = 0x1602;

    private CicsRuntimeOps() {
    }

    public static void link(ProgramContext context, String program, DataView commarea) {
        link(context, program, commarea, false);
    }

    public static void link(
            ProgramContext context, String program, DataView commarea,
            boolean suppressDefaultHandling) {
        requireNoLegacyTransfer(linkCondition(
                context, program, commarea, suppressDefaultHandling), "LINK");
    }

    /** LINKを実行し、condition handlerへ移る場合はその段落番号を返す。 */
    public static int linkCondition(
            ProgramContext context, String program, DataView commarea,
            boolean suppressDefaultHandling) {
        CicsCommandOutcome outcome;
        try {
            outcome = execute(context,
                    new LinkCommand(ProgramId.of(program), payload(commarea)));
        } catch (ProgramTargetTransfer transfer) {
            return Ops.resumeTransfer(context, transfer);
        }
        int target = conditionTarget(context, outcome, suppressDefaultHandling, "LINK");
        if (target != NO_CONDITION_TRANSFER
                || outcome.responseCode() != CicsResponseCode.NORMAL) {
            return target;
        }
        ContinueControl control = requireControl(outcome, ContinueControl.class, "LINK");
        copyBack(commarea, control.payload(), "LINK");
        return NO_CONDITION_TRANSFER;
    }

    /** PROGRAM(データ名) のLINK。名前はデータ域のbyte列から実行時に決まる。 */
    public static int linkCondition(
            ProgramContext context, byte[] program, DataView commarea,
            boolean suppressDefaultHandling) {
        return linkCondition(context, dynamicProgram(context, program, "LINK"), commarea,
                suppressDefaultHandling);
    }

    /** PROGRAM(データ名) のXCTL。 */
    public static int xctlCondition(
            ProgramContext context, byte[] program, DataView commarea,
            boolean suppressDefaultHandling) {
        return xctlCondition(context, dynamicProgram(context, program, "XCTL"), commarea,
                suppressDefaultHandling);
    }

    /**
     * データ域の名前を program 名にする (設計 79 §4)。
     *
     * <p>落とすのは末尾の空白だけである。先頭の空白や許可されない文字を推測で直すと、
     * 別の program を起動しうる。名前として正しくなければ PGMIDERR ではなく失敗させる。
     * PGMIDERR は「正しい名前だが登録が無い」ことを表すからである。
     */
    static String dynamicProgram(ProgramContext context, byte[] name, String command) {
        String text = Objects.requireNonNull(context, "context").codePage()
                .decode(Objects.requireNonNull(name, "name"));
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') {
            end--;
        }
        String candidate = text.substring(0, end);
        try {
            if (candidate.isEmpty() || candidate.indexOf(' ') >= 0) {
                throw new IllegalArgumentException("blank or padded name");
            }
            return ProgramId.of(candidate).value();
        } catch (IllegalArgumentException invalid) {
            throw new CicsTaskStateException(
                    command + " PROGRAM data area does not contain a valid program name");
        }
    }

    public static void xctl(ProgramContext context, String program, DataView commarea) {
        xctl(context, program, commarea, false);
    }

    public static void xctl(
            ProgramContext context, String program, DataView commarea,
            boolean suppressDefaultHandling) {
        requireNoLegacyTransfer(xctlCondition(
                context, program, commarea, suppressDefaultHandling), "XCTL");
    }

    /** XCTLを実行し、condition handlerへ移る場合はその段落番号を返す。 */
    public static int xctlCondition(
            ProgramContext context, String program, DataView commarea,
            boolean suppressDefaultHandling) {
        CicsCommandOutcome outcome = execute(context,
                new XctlCommand(ProgramId.of(program), payload(commarea)));
        int target = conditionTarget(context, outcome, suppressDefaultHandling, "XCTL");
        if (target != NO_CONDITION_TRANSFER
                || outcome.responseCode() != CicsResponseCode.NORMAL) {
            return target;
        }
        throw new CicsProgramTransfer(
                requireControl(outcome, TransferControl.class, "XCTL"));
    }

    public static void returnTask(
            ProgramContext context, String nextTransaction, DataView commarea) {
        returnTask(context, nextTransaction, commarea, false);
    }

    public static void returnTask(
            ProgramContext context, String nextTransaction, DataView commarea,
            boolean suppressDefaultHandling) {
        requireNoLegacyTransfer(returnTaskCondition(
                context, nextTransaction, commarea, suppressDefaultHandling), "RETURN");
    }

    /** RETURNを実行し、condition handlerへ移る場合はその段落番号を返す。 */
    public static int returnTaskCondition(
            ProgramContext context, String nextTransaction, DataView commarea,
            boolean suppressDefaultHandling) {
        return returnTaskCondition(context, nextTransaction, commarea, false,
                suppressDefaultHandling);
    }

    /** RETURN [IMMEDIATE] を実行し、condition handlerへ移る場合はその段落番号を返す。 */
    public static int returnTaskCondition(
            ProgramContext context, String nextTransaction, DataView commarea,
            boolean immediate, boolean suppressDefaultHandling) {
        ReturnCommand command = nextTransaction == null
                ? new ReturnCommand(java.util.Optional.empty(), payload(commarea))
                : new ReturnCommand(java.util.Optional.of(TransId.of(nextTransaction)),
                        payload(commarea), immediate);
        CicsCommandOutcome outcome = execute(context, command);
        int target = conditionTarget(context, outcome, suppressDefaultHandling, "RETURN");
        if (target != NO_CONDITION_TRANSFER
                || outcome.responseCode() != CicsResponseCode.NORMAL) {
            return target;
        }
        throw new CicsProgramTransfer(
                requireControl(outcome, TaskCompletion.class, "RETURN"));
    }

    public static void syncpoint(ProgramContext context, boolean rollback) {
        syncpoint(context, rollback, false);
    }

    public static void syncpoint(
            ProgramContext context, boolean rollback, boolean suppressDefaultHandling) {
        requireNoLegacyTransfer(syncpointCondition(
                context, rollback, suppressDefaultHandling), "SYNCPOINT");
    }

    /** SYNCPOINTを実行し、condition handlerへ移る場合はその段落番号を返す。 */
    public static int syncpointCondition(
            ProgramContext context, boolean rollback, boolean suppressDefaultHandling) {
        SyncpointAction action = rollback ? SyncpointAction.ROLLBACK : SyncpointAction.COMMIT;
        CicsCommandOutcome outcome = execute(context, new SyncpointCommand(action));
        int target = conditionTarget(context, outcome, suppressDefaultHandling, "SYNCPOINT");
        if (target != NO_CONDITION_TRANSFER
                || outcome.responseCode() != CicsResponseCode.NORMAL) {
            return target;
        }
        requireControl(outcome, SyncpointCompletion.class, "SYNCPOINT");
        // UOW の間だけ持つ ENQ の資源は、SYNCPOINT (ROLLBACK を含む) で返す。READ UPDATE で得た record と
        // browse も SYNCPOINT で終わる (暫定判断 P-136)
        CicsExecution execution = execution(context);
        execution.environment().enqueues().releaseUnitOfWork(execution.task().taskId());
        execution.environment().files().releaseUnitOfWork(execution.task().taskId());
        // PROTECT の START は同期点で登録し、ROLLBACK なら取り消す (暫定判断 P-141)
        if (rollback) {
            execution.discardProtectedStarts();
        } else {
            execution.takeProtectedStarts().forEach(Runnable::run);
        }
        return NO_CONDITION_TRANSFER;
    }

    /**
     * ENQ RESOURCE(域) LENGTH(n) (暫定判断 P-128)。
     *
     * <p>他の task が持っていれば、{@code NOSUSPEND} が無いかぎり task の期限まで待つ。期限を越えるなら
     * 失敗させる。{@code NOSUSPEND} なら ENQBUSY。
     */
    public static int enqueueCondition(ProgramContext context, byte[] resource,
            boolean noSuspend, boolean taskScope, boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        CicsExecution execution = execution(required);
        java.time.Duration maxWait = execution.deadline()
                .map(deadline -> java.time.Duration.between(execution.environment().clock().instant(), deadline))
                .orElse(null);
        boolean held = execution.environment().enqueues().enqueue(
                execution.task().taskId(), resource, taskScope, !noSuspend, maxWait);
        return containerOutcome(required, ENQ_FUNCTION,
                held ? CicsResponseCode.NORMAL : CicsResponseCode.ENQBUSY, 0,
                suppressDefaultHandling, "ENQ");
    }

    /**
     * INQUIRE TERMINAL(名前) UCTRANST(域) (暫定判断 P-130)。
     *
     * <p>扱う端末は task を起こした端末だけである。ほかの端末の定義はこの処理系に無く、
     * TERMIDERR を返すと「その端末は無い」と推測したことになるので失敗させる。
     */
    public static int inquireTerminalCondition(ProgramContext context, String terminalLiteral,
            byte[] terminalData, DataView uctranst, boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        String terminal = ownTerminal(required, terminalLiteral, terminalData, "INQUIRE TERMINAL");
        setFullword(Objects.requireNonNull(uctranst, "uctranst"),
                execution(required).environment().terminals().uppercaseTranslation(terminal));
        completeLocalCommand(required, INQUIRE_TERMINAL_FUNCTION);
        return NO_CONDITION_TRANSFER;
    }

    /** SET TERMINAL(名前) UCTRANST(域)。UCTRAN / NOUCTRAN / TRANIDONLY 以外の値は失敗させる。 */
    public static int setTerminalCondition(ProgramContext context, String terminalLiteral,
            byte[] terminalData, DataView uctranst, boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        String terminal = ownTerminal(required, terminalLiteral, terminalData, "SET TERMINAL");
        int value = fullword(Objects.requireNonNull(uctranst, "uctranst"));
        if (!CicsCvda.isUppercaseTranslation(value)) {
            // 実機は INVREQ (RESP2 43) である。INVREQ の数値を確かめていないので条件にせず失敗させる
            throw new CicsTaskStateException("SET TERMINAL UCTRANST has an invalid CVDA value: " + value);
        }
        execution(required).environment().terminals().setUppercaseTranslation(terminal, value);
        completeLocalCommand(required, SET_TERMINAL_FUNCTION);
        return NO_CONDITION_TRANSFER;
    }

    private static String ownTerminal(ProgramContext context, String literal, byte[] data, String command) {
        String named = (literal != null ? literal : context.codePage().decode(data)).stripTrailing();
        String own = execution(context).task().terminalId()
                .orElseThrow(() -> new CicsTaskStateException(command + " requires a task started from a terminal"));
        if (!own.equals(named)) {
            throw new CicsTaskStateException(command + " supports only the task's own terminal: '" + named + "'");
        }
        return own;
    }

    /**
     * file control の命令 (暫定判断 P-131、P-136)。
     *
     * <p>数の option は、データ名なら域 (半語の 2 進)、定数なら literal で渡る。どちらも無ければ域は null、
     * literal は負である。受取域の無い option は null で渡る。
     *
     * <h2>読んだ record の移し方</h2>
     * <p>LENGTH を書かなければ INTO の域の長さを最大の長さとする (COBOL の翻訳系が INTO の長さを補う)。
     * 可変長の record が長ければ切り詰めて LENGERR (RESP2 11)、短ければ record の長さだけを移し、
     * 残りの byte は変えない (文書は「予測できない」とする)。固定長の record を違う長さで読む形は
     * LENGERR (RESP2 13) だが、そのとき域へ何を移すかを確かめていないので失敗させる。
     */
    public static int fileCommandCondition(ProgramContext context, int kind, String fileLiteral, byte[] fileData,
            DataView data, DataView lengthArea, int lengthLiteral, DataView ridfld, DataView keyLengthArea,
            int keyLengthLiteral, DataView reqidArea, int reqidLiteral, DataView numrec, int flags,
            boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        String command = FILE_COMMANDS.get(kind);
        String file = (fileLiteral != null ? fileLiteral : required.codePage().decode(fileData)).stripTrailing();
        String label = command + " FILE(" + file + ")";
        CicsExecution execution = execution(required);
        CicsFilePort files = execution.environment().files();
        CicsTaskId task = execution.task().taskId();
        int keyLength = keyLengthArea != null ? halfword(keyLengthArea)
                : keyLengthLiteral >= 0 ? keyLengthLiteral : CicsFilePort.ABSENT;
        int reqid = reqidArea != null ? halfword(reqidArea) : Math.max(0, reqidLiteral);
        boolean generic = (flags & FILE_GENERIC) != 0;
        boolean gteq = (flags & FILE_GTEQ) != 0;
        boolean equal = (flags & FILE_EQUAL) != 0;
        boolean rrn = (flags & FILE_RRN) != 0;
        boolean update = (flags & FILE_UPDATE) != 0;
        CicsFilePort.Result result;
        switch (kind) {
            case FILE_READ, FILE_READNEXT, FILE_READPREV -> {
                int max = lengthArea != null ? halfword(lengthArea) : data.length();
                if (max < 0 || max > data.length()) {
                    throw new CicsTaskStateException(label + " LENGTH " + max + " does not fit the INTO area of "
                            + data.length() + " bytes");
                }
                byte[] id = ridfld.toByteArray();
                CicsFilePort.Found found = switch (kind) {
                    case FILE_READ -> files.read(task, file, id, rrn, keyLength, generic, gteq, update,
                            maxWait(execution));
                    case FILE_READNEXT -> files.readNext(task, file, reqid, id, rrn, keyLength);
                    default -> files.readPrevious(task, file, reqid, id, rrn, keyLength);
                };
                result = new CicsFilePort.Result(found.response(), found.response2());
                if (found.response() == CicsResponseCode.NORMAL) {
                    byte[] record = found.data();
                    if (!files.variableLength(file) && record.length != max) {
                        throw new CicsTaskStateException(label + " reads a fixed-length record of " + record.length
                                + " bytes with LENGTH " + max + "; what LENGERR moves is not verified");
                    }
                    if (record.length > max && update) {
                        throw new CicsTaskStateException(label + " UPDATE truncated the record;"
                                + " whether the record stays held is not verified");
                    }
                    int moved = Math.min(record.length, max);
                    data.subView(0, moved).setBytes(java.util.Arrays.copyOf(record, moved));
                    if (lengthArea != null) {
                        setHalfword(lengthArea, record.length);
                    }
                    if (kind != FILE_READ || generic || gteq) {
                        // 総称・GTEQ の READ と browse は、見つけた record の完全な識別を RIDFLD へ返す
                        if (found.id().length > ridfld.length()) {
                            throw new CicsTaskStateException(label + " RIDFLD is shorter than the record identifier");
                        }
                        ridfld.subView(0, found.id().length).setBytes(found.id());
                    }
                    if (record.length > max) {
                        // LENGERR (RESP2 11): record が LENGTH より長く、切り詰めた
                        result = new CicsFilePort.Result(CicsResponseCode.LENGERR, 11);
                    }
                }
            }
            case FILE_WRITE, FILE_REWRITE -> {
                int length = lengthArea != null ? halfword(lengthArea)
                        : lengthLiteral >= 0 ? lengthLiteral : data.length();
                if (length < 0 || length > data.length()) {
                    throw new CicsTaskStateException(label + " LENGTH " + length + " exceeds the FROM area of "
                            + data.length() + " bytes");
                }
                byte[] record = data.subView(0, length).toByteArray();
                if (kind == FILE_REWRITE) {
                    result = files.rewrite(task, file, record);
                } else {
                    byte[] id = ridfld.toByteArray();
                    if (!rrn) {
                        int effective = keyLength == CicsFilePort.ABSENT
                                ? files.keyLengthOf(file).orElse(id.length) : keyLength;
                        if (effective < 0 || effective > id.length) {
                            throw new CicsTaskStateException(label + " KEYLENGTH exceeds the RIDFLD area");
                        }
                        id = java.util.Arrays.copyOf(id, effective);
                    }
                    result = files.write(task, file, id, rrn, record);
                }
            }
            case FILE_DELETE -> {
                CicsFilePort.Deleted deleted = files.delete(task, file, ridfld == null ? null : ridfld.toByteArray(),
                        rrn, keyLength, generic, maxWait(execution));
                result = new CicsFilePort.Result(deleted.response(), deleted.response2());
                if (deleted.response() == CicsResponseCode.NORMAL && numrec != null) {
                    setHalfword(numrec, deleted.count());
                }
            }
            case FILE_UNLOCK -> result = files.unlock(task, file);
            case FILE_STARTBR -> result = files.startBrowse(task, file, reqid, ridfld.toByteArray(), rrn, keyLength,
                    generic, equal);
            case FILE_RESETBR -> result = files.resetBrowse(task, file, reqid, ridfld.toByteArray(), rrn, keyLength,
                    generic, equal);
            case FILE_ENDBR -> result = files.endBrowse(task, file, reqid);
            default -> throw new IllegalArgumentException("unknown file control command: " + kind);
        }
        execution.eib(required.codePage()).setDataset(file, required.codePage());
        return containerOutcome(required, FILE_FUNCTIONS[kind], result.response(), result.response2(),
                suppressDefaultHandling, command + " FILE");
    }

    /**
     * 一時記憶・一時データの命令 (暫定判断 P-137)。
     *
     * <p>名前は定数か、データ域の byte 列で渡る。nameLength は QUEUE (TS) なら 8、QNAME なら 16、TD なら 4。
     * 数の option は、データ名なら域 (半語の 2 進)、定数なら literal で渡り、どちらも無ければ域は null、literal は負である。
     *
     * <p>読んだデータの移し方は file control と同じである。LENGTH を書かなければ INTO の長さを最大とし、
     * 長いデータは切り詰めて LENGERR、LENGTH の域には本来の長さを置く。短いデータは長さだけを移し、INTO の残りは変えない。
     */
    public static int queueCommandCondition(ProgramContext context, int kind, String nameLiteral, byte[] nameData,
            int nameLength, DataView data, DataView lengthArea, int lengthLiteral, DataView itemArea, int itemLiteral,
            DataView numItems, int flags, boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        String command = QUEUE_COMMANDS.get(kind);
        CicsExecution execution = execution(required);
        CicsEnvironment environment = execution.environment();
        int response;
        int response2 = 0;
        if (kind == QUEUE_WRITEQ_TS || kind == QUEUE_WRITEQ_TD) {
            int length = lengthArea != null ? halfword(lengthArea) : lengthLiteral >= 0 ? lengthLiteral : data.length();
            if (length > data.length() && length <= CicsTemporaryStoragePort.MAX_ITEM_LENGTH) {
                throw new CicsTaskStateException(command + " LENGTH " + length + " exceeds the FROM area of "
                        + data.length() + " bytes");
            }
            byte[] bytes = length < 1 || length > data.length() ? new byte[0] : data.subView(0, length).toByteArray();
            if (kind == QUEUE_WRITEQ_TD) {
                CicsTransientDataPort.Result result = environment.transientData()
                        .write(transientDataName(required, nameLiteral, nameData), bytes);
                response = result.response();
                response2 = result.response2();
            } else {
                byte[] name = temporaryStorageName(required, nameLiteral, nameData, nameLength, command);
                if (name == null) {
                    // INVREQ: キューの名前がすべて binary zero
                    response = CicsResponseCode.INVREQ;
                } else if ((flags & QUEUE_REWRITE) != 0) {
                    int item = itemArea != null ? halfword(itemArea) : itemLiteral;
                    CicsTemporaryStoragePort.Result result = environment.temporaryStorage().rewrite(name, item, bytes);
                    response = result.response();
                    response2 = result.response2();
                } else {
                    CicsTemporaryStoragePort.Written written = environment.temporaryStorage().write(name, bytes);
                    response = written.response();
                    response2 = written.response2();
                    if (written.response() == CicsResponseCode.NORMAL && itemArea != null) {
                        // REWRITE の無い ITEM は出力であり、書いた item の番号を返す
                        setHalfword(itemArea, written.item());
                    }
                }
            }
        } else if (kind == QUEUE_READQ_TS || kind == QUEUE_READQ_TD) {
            // READQ の頁: LENGTH が負なら 0 とみなす
            int max = Math.max(0, lengthArea != null ? halfword(lengthArea) : data.length());
            if (max > data.length()) {
                throw new CicsTaskStateException(command + " LENGTH " + max + " does not fit the INTO area of "
                        + data.length() + " bytes");
            }
            byte[] record;
            int items = -1;
            if (kind == QUEUE_READQ_TD) {
                CicsTransientDataPort.Read read = environment.transientData()
                        .read(transientDataName(required, nameLiteral, nameData));
                response = read.response();
                response2 = read.response2();
                record = read.data();
            } else {
                byte[] name = temporaryStorageName(required, nameLiteral, nameData, nameLength, command);
                if (name == null) {
                    response = CicsResponseCode.INVREQ;
                    record = null;
                } else {
                    boolean next = (flags & QUEUE_NEXT) != 0;
                    int item = next ? 0 : itemArea != null ? halfword(itemArea) : itemLiteral;
                    CicsTemporaryStoragePort.Read read = environment.temporaryStorage().read(name, item, next);
                    response = read.response();
                    response2 = read.response2();
                    record = read.data();
                    items = read.numberOfItems();
                }
            }
            if (response == CicsResponseCode.NORMAL) {
                int moved = Math.min(record.length, max);
                data.subView(0, moved).setBytes(java.util.Arrays.copyOf(record, moved));
                if (lengthArea != null) {
                    setHalfword(lengthArea, record.length);
                }
                if (record.length > max) {
                    // LENGERR: データが LENGTH より長く、切り詰めた。頁は RESP2 を示さない
                    response = CicsResponseCode.LENGERR;
                } else if (numItems != null && items >= 0) {
                    setHalfword(numItems, items);
                }
            }
        } else if (kind == QUEUE_DELETEQ_TS) {
            byte[] name = temporaryStorageName(required, nameLiteral, nameData, nameLength, command);
            if (name == null) {
                response = CicsResponseCode.INVREQ;
            } else {
                CicsTemporaryStoragePort.Result result = environment.temporaryStorage().delete(name);
                response = result.response();
                response2 = result.response2();
            }
        } else if (kind == QUEUE_DELETEQ_TD) {
            CicsTransientDataPort.Result result = environment.transientData()
                    .delete(transientDataName(required, nameLiteral, nameData));
            response = result.response();
            response2 = result.response2();
        } else {
            throw new IllegalArgumentException("unknown queue command: " + kind);
        }
        return containerOutcome(required, QUEUE_FUNCTIONS[kind], response, response2, suppressDefaultHandling, command);
    }

    /**
     * 一時記憶のキューの 16 byte の名前。すべて binary zero なら null (INVREQ)。
     *
     * <p>X'FA'〜X'FF'、{@code **}、{@code $$}、{@code DF} で始まる名前は CICS が使うので書けないと頁にあるが、
     * 書いたときの条件は示されていないので失敗させる。
     */
    private static byte[] temporaryStorageName(ProgramContext context, String literal, byte[] data, int nameLength,
            String command) {
        byte[] given = literal != null ? context.codePage().encode(literal) : data;
        if (given.length > nameLength || nameLength > CicsTemporaryStoragePort.NAME_LENGTH) {
            throw new CicsTaskStateException(command + " queue name exceeds " + nameLength + " bytes");
        }
        boolean zero = true;
        for (byte value : given) {
            zero &= value == 0;
        }
        if (zero && literal == null) {
            return null;
        }
        String text = context.codePage().decode(given);
        if ((given.length > 0 && (given[0] & 0xFF) >= 0xFA)
                || text.startsWith("**") || text.startsWith("$$") || text.startsWith("DF")) {
            throw new CicsTaskStateException(command + " queue name is reserved for CICS: '" + text.stripTrailing() + "'");
        }
        byte[] name = new byte[CicsTemporaryStoragePort.NAME_LENGTH];
        java.util.Arrays.fill(name, context.codePage().space());
        System.arraycopy(given, 0, name, 0, given.length);
        return name;
    }

    private static String transientDataName(ProgramContext context, String literal, byte[] data) {
        return (literal != null ? literal : context.codePage().decode(data)).stripTrailing();
    }

    /**
     * START (暫定判断 P-138)。
     *
     * <p>名前の option は、定数ならその文字列、データ名なら域の byte 列で渡る。書かなければどちらも null。
     * 満了は timing が決める。INTERVAL / TIME は hhmmss を、AFTER / AT は HOURS / MINUTES / SECONDS を使う。
     *
     * <h2>満了の時刻</h2>
     * <p>INTERVAL と AFTER は今からの間隔である。TIME と AT は task の地方時の今日のその時刻で、hh が 23 を越えれば
     * 翌日以降を指す。6 時間前までの時刻なら直ちに始める (「Expiration times」の頁)。それより前の時刻は、
     * 時刻として読んで翌日のその時刻とする。頁はこの場合を明示していない。
     */
    public static int startCondition(ProgramContext context, String transactionLiteral, byte[] transactionData,
            int timing, dev.cobolonjava.runtime.decimal.Decimal hhmmss, dev.cobolonjava.runtime.decimal.Decimal hours,
            dev.cobolonjava.runtime.decimal.Decimal minutes, dev.cobolonjava.runtime.decimal.Decimal seconds,
            DataView from, DataView lengthArea, int lengthLiteral, String requestLiteral, byte[] requestData,
            String returnTransactionLiteral, byte[] returnTransactionData, String returnTerminalLiteral,
            byte[] returnTerminalData, String queueLiteral, byte[] queueData, boolean suppressDefaultHandling) {
        return startCondition(context, transactionLiteral, transactionData, timing, hhmmss, hours, minutes, seconds,
                from, lengthArea, lengthLiteral, requestLiteral, requestData, returnTransactionLiteral,
                returnTransactionData, returnTerminalLiteral, returnTerminalData, queueLiteral, queueData, false,
                suppressDefaultHandling);
    }

    /**
     * PROTECT を書けるSTART。
     *
     * <p>PROTECT の START は、命令の時点で登録できるか (TRANSIDERR、IOERR) だけを確かめて task に預け、同期点で登録する。
     * SYNCPOINT なら直ちに、task の終わりなら暗黙の同期点が commit したあとに登録する。ROLLBACK、ABEND、commit の失敗では
     * 取り消す (START の頁: 出した task が同期点を取るまで始まらず、それより前に ABEND すれば取り消される)。
     */
    public static int startCondition(ProgramContext context, String transactionLiteral, byte[] transactionData,
            int timing, dev.cobolonjava.runtime.decimal.Decimal hhmmss, dev.cobolonjava.runtime.decimal.Decimal hours,
            dev.cobolonjava.runtime.decimal.Decimal minutes, dev.cobolonjava.runtime.decimal.Decimal seconds,
            DataView from, DataView lengthArea, int lengthLiteral, String requestLiteral, byte[] requestData,
            String returnTransactionLiteral, byte[] returnTransactionData, String returnTerminalLiteral,
            byte[] returnTerminalData, String queueLiteral, byte[] queueData, boolean protect,
            boolean suppressDefaultHandling) {
        return startCondition(context, transactionLiteral, transactionData, timing, hhmmss, hours, minutes, seconds,
                from, lengthArea, lengthLiteral, requestLiteral, requestData, returnTransactionLiteral,
                returnTransactionData, returnTerminalLiteral, returnTerminalData, queueLiteral, queueData, null, null,
                protect, suppressDefaultHandling);
    }

    /**
     * TERMID を書ける START (設計 83 §5)。
     *
     * <p>端末があるか (TERMIDERR) は START の port が確かめる。端末の登録を持たない port は TERMID を断る。
     */
    public static int startCondition(ProgramContext context, String transactionLiteral, byte[] transactionData,
            int timing, dev.cobolonjava.runtime.decimal.Decimal hhmmss, dev.cobolonjava.runtime.decimal.Decimal hours,
            dev.cobolonjava.runtime.decimal.Decimal minutes, dev.cobolonjava.runtime.decimal.Decimal seconds,
            DataView from, DataView lengthArea, int lengthLiteral, String requestLiteral, byte[] requestData,
            String returnTransactionLiteral, byte[] returnTransactionData, String returnTerminalLiteral,
            byte[] returnTerminalData, String queueLiteral, byte[] queueData, String terminalLiteral,
            byte[] terminalData, boolean protect, boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        CicsExecution execution = execution(required);
        CicsTaskContext task = execution.task();
        String transaction = intervalName(required, transactionLiteral, transactionData);
        TransId transId;
        try {
            transId = TransId.of(transaction);
        } catch (IllegalArgumentException invalid) {
            throw new CicsTaskStateException("START TRANSID has an unsupported format: '" + transaction + "'");
        }
        long delay = startSeconds(timing, hhmmss, hours, minutes, seconds);
        if (delay < 0) {
            // INVREQ (RESP2 4 / 5 / 6): 時・分・秒の値が範囲の外
            return containerOutcome(required, START_FUNCTION, CicsResponseCode.INVREQ, (int) -delay,
                    suppressDefaultHandling, "START");
        }
        java.time.Instant now = execution.environment().clock().instant();
        java.time.Instant expiration;
        if (timing == START_INTERVAL || timing == START_AFTER) {
            expiration = now.plusSeconds(delay);
        } else {
            java.time.ZoneId zone = task.hostZone().orElseThrow(() -> new CicsTaskStateException(
                    "START TIME / AT requires the host time zone of the task"));
            java.time.LocalDate today = now.atZone(zone).toLocalDate();
            java.time.Instant target = today.atStartOfDay(zone).plusSeconds(delay).toInstant();
            if (target.isAfter(now)) {
                expiration = target;
            } else if (java.time.Duration.between(target, now).compareTo(START_PAST_WINDOW) <= 0) {
                expiration = now;
            } else {
                expiration = today.plusDays(1).atStartOfDay(zone).plusSeconds(delay).toInstant();
            }
        }
        byte[] bytes = null;
        if (from != null) {
            int length = lengthArea != null ? halfword(lengthArea) : lengthLiteral >= 0 ? lengthLiteral : from.length();
            if (length <= 0) {
                // LENGERR: LENGTH が 0 以下。頁は RESP2 を示さない
                return containerOutcome(required, START_FUNCTION, CicsResponseCode.LENGERR, 0,
                        suppressDefaultHandling, "START");
            }
            if (length > from.length()) {
                throw new CicsTaskStateException("START LENGTH " + length + " exceeds the FROM area of "
                        + from.length() + " bytes");
            }
            bytes = from.subView(0, length).toByteArray();
        }
        CicsStartPort starts = execution.environment().starts();
        boolean generated = requestLiteral == null && requestData == null;
        String requestId = generated ? starts.newRequestId() : intervalName(required, requestLiteral, requestData);
        if (requestId.isEmpty()) {
            throw new CicsTaskStateException("START REQID must not be blank");
        }
        Optional<String> terminal = optionalName(required, terminalLiteral, terminalData);
        if (terminal.isPresent() && terminal.orElseThrow().isEmpty()) {
            throw new CicsTaskStateException("START TERMID must not be blank");
        }
        CicsStartData data = new CicsStartData(requestId, transId, bytes,
                optionalName(required, returnTransactionLiteral, returnTransactionData),
                optionalName(required, returnTerminalLiteral, returnTerminalData),
                optionalName(required, queueLiteral, queueData), task.owner(), task.userId(), terminal);
        CicsStartPort.Result result;
        if (protect) {
            if (execution.hasProtectedStart(requestId)) {
                if (data.data().isEmpty()) {
                    throw new CicsTaskStateException("START REQID(" + requestId + ") is already pending;"
                            + " the condition for a START without FROM is not documented");
                }
                // IOERR: FROM を持つ START の REQID が既にある
                result = new CicsStartPort.Result(CicsResponseCode.IOERR, 0);
            } else {
                result = starts.check(data);
            }
            if (result.response() == CicsResponseCode.NORMAL) {
                execution.addProtectedStart(expiration, data);
            }
        } else {
            result = starts.start(expiration, data);
        }
        if (generated && result.response() == CicsResponseCode.NORMAL) {
            // REQID を書かなければ、CICS が作った名前を EIBREQID に置く (START の頁)
            execution.eib(required.codePage()).setRequestId(requestId, required.codePage());
        }
        return containerOutcome(required, START_FUNCTION, result.response(), result.response2(),
                suppressDefaultHandling, "START");
    }

    /**
     * RETRIEVE (暫定判断 P-138)。START で起きた task だけが読める。受取域の無い option は null で渡る。
     *
     * <p>2 度目の RETRIEVE は ENDDATA、START が書かなかった option を求めれば ENVDEFERR (FROM の無い START への INTO を含む)。
     * ENVDEFERR ではデータを読んだことにしない。
     */
    public static int retrieveCondition(ProgramContext context, DataView into, DataView lengthArea,
            DataView returnTransaction, DataView returnTerminal, DataView queue, boolean suppressDefaultHandling) {
        return retrieveCondition(context, into, lengthArea, returnTransaction, returnTerminal, queue, false,
                suppressDefaultHandling);
    }

    /**
     * WAIT を書ける RETRIEVE (設計 83 §5)。
     *
     * <p>WAIT は、満了したデータを読み尽くしていれば、同じ端末と TRANSID の次の START が満了するまで task の期限まで待つ
     * (RETRIEVE の頁)。端末の無い START の task は 1 つの START のデータしか持たず、待っても届くデータが無いので断る。
     */
    public static int retrieveCondition(ProgramContext context, DataView into, DataView lengthArea,
            DataView returnTransaction, DataView returnTerminal, DataView queue, boolean wait,
            boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        CicsExecution execution = execution(required);
        CicsStartData started = execution.task().start().orElseThrow(() -> new CicsTaskStateException(
                "RETRIEVE is supported only in a task started by START; the condition otherwise is not verified"));
        // 端末へ出す task は、同じ端末と TRANSID の満了した START を満了の順に読む (RETRIEVE の頁)
        CicsStartData start = started.sequence().stream().filter(item -> !item.retrieved()).findFirst()
                .orElse(null);
        if (start == null && wait) {
            start = awaitStart(execution, started);
        }
        if (start == null) {
            start = started;
        }
        int response = CicsResponseCode.NORMAL;
        if (start.retrieved()) {
            // ENDDATA: この task の START のデータはもう残っていない
            response = CicsResponseCode.ENDDATA;
        } else if ((into != null && start.data().isEmpty())
                || (returnTransaction != null && start.returnTransaction().isEmpty())
                || (returnTerminal != null && start.returnTerminal().isEmpty())
                || (queue != null && start.queue().isEmpty())) {
            // ENVDEFERR: START が書かなかった option を RETRIEVE が求めた
            response = CicsResponseCode.ENVDEFERR;
        } else {
            start.markRetrieved();
            if (into != null) {
                byte[] data = start.data().orElseThrow();
                // RETRIEVE の頁: LENGTH が 0 以下なら 0 とみなす
                int max = Math.max(0, lengthArea != null ? halfword(lengthArea) : into.length());
                if (max > into.length()) {
                    throw new CicsTaskStateException("RETRIEVE LENGTH " + max + " does not fit the INTO area of "
                            + into.length() + " bytes");
                }
                int moved = Math.min(data.length, max);
                into.subView(0, moved).setBytes(java.util.Arrays.copyOf(data, moved));
                if (lengthArea != null) {
                    setHalfword(lengthArea, data.length);
                }
                if (data.length > max) {
                    // LENGERR: データが LENGTH より長く、切り詰めた
                    response = CicsResponseCode.LENGERR;
                }
            }
            putPadded(required, returnTransaction, start.returnTransaction(), "RTRANSID");
            putPadded(required, returnTerminal, start.returnTerminal(), "RTERMID");
            putPadded(required, queue, start.queue(), "QUEUE");
        }
        return containerOutcome(required, RETRIEVE_FUNCTION, response, 0, suppressDefaultHandling, "RETRIEVE");
    }

    /** RETRIEVE WAIT が次の START を探す間隔。 */
    private static final long RETRIEVE_WAIT_POLL_MILLIS = 200;

    /** 次に満了した START を START の port から受け取るまで、task の期限まで待つ。 */
    private static CicsStartData awaitStart(CicsExecution execution, CicsStartData started) {
        if (started.terminalId().isEmpty()) {
            throw new CicsTaskStateException("RETRIEVE WAIT is supported only in a task started by START TERMID;"
                    + " a task started without a terminal has no later START to wait for");
        }
        java.time.Duration maxWait = execution.deadline()
                .map(deadline -> java.time.Duration.between(execution.environment().clock().instant(), deadline))
                .orElse(null);
        long deadline = maxWait == null ? Long.MAX_VALUE : System.nanoTime() + Math.max(0, maxWait.toNanos());
        while (true) {
            java.util.List<CicsStartData> later = execution.environment().starts().retrieveMore(started);
            if (!later.isEmpty()) {
                started.append(later);
                return later.get(0);
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new CicsTaskStateException("RETRIEVE WAIT exceeded the task deadline");
            }
            try {
                Thread.sleep(Math.max(1, Math.min(RETRIEVE_WAIT_POLL_MILLIS,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CicsTaskStateException("RETRIEVE WAIT was interrupted");
            }
        }
    }

    /** CANCEL REQID(名前) (暫定判断 P-138)。未満了の START を取り消す。 */
    public static int cancelCondition(ProgramContext context, String requestLiteral, byte[] requestData,
            boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        String requestId = intervalName(required, requestLiteral, requestData);
        CicsExecution execution = execution(required);
        // 同期点を待つ PROTECT の START は、まだ始まっていないので取り消せる
        CicsStartPort.Result result = execution.cancelProtectedStart(requestId)
                ? new CicsStartPort.Result(CicsResponseCode.NORMAL, 0)
                : execution.environment().starts().cancel(requestId);
        return containerOutcome(required, CANCEL_FUNCTION, result.response(), result.response2(),
                suppressDefaultHandling, "CANCEL");
    }

    /**
     * START の満了までの秒。値が範囲の外なら、INVREQ の RESP2 (4 時、5 分、6 秒) を負にして返す。
     *
     * <p>AFTER / AT で単位を 1 つだけ書けば、その単位で上限まで数える (MINUTES 5999、SECONDS 359999)。
     */
    private static long startSeconds(int timing, dev.cobolonjava.runtime.decimal.Decimal hhmmss,
            dev.cobolonjava.runtime.decimal.Decimal hours, dev.cobolonjava.runtime.decimal.Decimal minutes,
            dev.cobolonjava.runtime.decimal.Decimal seconds) {
        if (timing == START_INTERVAL || timing == START_TIME) {
            long value = hhmmss == null ? 0 : startValue(hhmmss, timing == START_TIME ? "TIME" : "INTERVAL");
            long h = value / 10_000;
            long m = value / 100 % 100;
            long s = value % 100;
            return h > 99 ? -4 : m > 59 ? -5 : s > 59 ? -6 : h * 3600 + m * 60 + s;
        }
        if (timing != START_AFTER && timing != START_AT) {
            throw new IllegalArgumentException("unknown START timing: " + timing);
        }
        int units = (hours == null ? 0 : 1) + (minutes == null ? 0 : 1) + (seconds == null ? 0 : 1);
        if (units == 0) {
            throw new IllegalArgumentException("START AFTER / AT requires HOURS, MINUTES or SECONDS");
        }
        boolean single = units == 1;
        long h = hours == null ? 0 : startValue(hours, "HOURS");
        long m = minutes == null ? 0 : startValue(minutes, "MINUTES");
        long s = seconds == null ? 0 : startValue(seconds, "SECONDS");
        if (h > 99) {
            return -4;
        }
        if (m > (single ? 5_999 : 59)) {
            return -5;
        }
        if (s > (single ? 359_999 : 59)) {
            return -6;
        }
        return h * 3600 + m * 60 + s;
    }

    private static long startValue(dev.cobolonjava.runtime.decimal.Decimal value, String option) {
        long parsed;
        try {
            parsed = value.toBigDecimal().longValueExact();
        } catch (ArithmeticException notInteger) {
            throw new CicsTaskStateException("START " + option + " is not an integer");
        }
        if (parsed < 0) {
            throw new CicsTaskStateException("START " + option + " is negative: " + parsed);
        }
        return parsed;
    }

    private static String intervalName(ProgramContext context, String literal, byte[] data) {
        return (literal != null ? literal : context.codePage().decode(data)).stripTrailing();
    }

    private static Optional<String> optionalName(ProgramContext context, String literal, byte[] data) {
        return literal == null && data == null ? Optional.empty() : Optional.of(intervalName(context, literal, data));
    }

    /** 名前を受取域へ、空白を詰めて置く。受取域が無ければ何もしない。 */
    private static void putPadded(ProgramContext context, DataView area, Optional<String> value, String option) {
        if (area == null) {
            return;
        }
        byte[] encoded = context.codePage().encode(value.orElseThrow());
        if (encoded.length > area.length()) {
            throw new CicsTaskStateException("RETRIEVE " + option + " value does not fit the receiving area");
        }
        byte[] padded = new byte[area.length()];
        java.util.Arrays.fill(padded, context.codePage().space());
        System.arraycopy(encoded, 0, padded, 0, encoded.length);
        area.setBytes(padded);
    }

    /**
     * 非同期 API の RUN TRANSID / FETCH ANY / FETCH CHILD / FREE CHILD (暫定判断 P-140)。
     *
     * <p>RUN の CHANNEL は定数か域の byte 列で、FETCH の CHANNEL は受取域で渡る。token は RUN と FETCH ANY では受取域、
     * FETCH CHILD と FREE CHILD では入力の域である。受取域の無い option は null。TIMEOUT は、データ名なら域、定数なら literal
     * で、どちらも無ければ literal は負 (待ちの限り無し)。
     *
     * <p>FETCH は子の reply channel を親の task の channel として名前をつけて置き、その名前を CHANNEL の域へ返す。
     * 子が channel を持たなければ空白を返す。
     */
    public static int asyncCommandCondition(ProgramContext context, int kind, String transactionLiteral,
            byte[] transactionData, String channelLiteral, byte[] channelData, DataView channelOut, DataView token,
            DataView completionStatus, DataView abendCode, int timeoutLiteral, DataView timeoutArea, boolean noSuspend,
            boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        String command = ASYNC_COMMANDS.get(kind);
        CicsExecution execution = execution(required);
        CicsAsyncPort async = execution.environment().async();
        CicsTaskContext task = execution.task();
        int response;
        int response2;
        if (kind == ASYNC_RUN) {
            String transaction = intervalName(required, transactionLiteral, transactionData);
            TransId transId;
            try {
                transId = TransId.of(transaction);
            } catch (IllegalArgumentException invalid) {
                throw new CicsTaskStateException("RUN TRANSID has an unsupported format: '" + transaction + "'");
            }
            String channelName = channelLiteral == null && channelData == null
                    ? null : containerName(required, channelLiteral, channelData, "CHANNEL");
            Map<String, byte[]> containers = new java.util.LinkedHashMap<>();
            if (channelName != null) {
                Map<String, byte[]> channel = execution.channel(channelName, false).orElseThrow(() ->
                        new CicsTaskStateException("RUN TRANSID CHANNEL(" + channelName
                                + ") does not exist; the condition is not documented"));
                // 子は RUN を出した時点の container の写しを受け取る (RUN TRANSID の頁)
                synchronized (execution) {
                    channel.forEach((name, value) -> containers.put(name, value.clone()));
                }
            }
            CicsAsyncPort.Run run = async.run(task.taskId(), new CicsAsyncChild(transId,
                    Optional.ofNullable(channelName), containers, task.owner(), task.userId()));
            response = run.response();
            response2 = run.response2();
            if (response == CicsResponseCode.NORMAL) {
                token.setBytes(run.token());
            }
        } else if (kind == ASYNC_FETCH_ANY || kind == ASYNC_FETCH_CHILD) {
            long timeout = timeoutArea != null ? fullwordValue(timeoutArea, command + " TIMEOUT")
                    : Math.max(0, timeoutLiteral);
            CicsAsyncPort.Fetched fetched = async.fetch(task.taskId(),
                    kind == ASYNC_FETCH_CHILD ? token.toByteArray() : null, noSuspend, timeout, maxWait(execution));
            response = fetched.response();
            response2 = fetched.response2();
            if (response == CicsResponseCode.NORMAL) {
                if (kind == ASYNC_FETCH_ANY) {
                    token.setBytes(fetched.token());
                }
                if (completionStatus != null) {
                    setFullword(completionStatus, fetched.completionStatus());
                }
                if (abendCode != null) {
                    putSpacePadded(required, abendCode, fetched.abendCode(), command + " ABCODE");
                }
                if (channelOut != null) {
                    String name = "";
                    if (fetched.replyChannel().isPresent()) {
                        name = execution.nextReplyChannelName();
                        execution.putChannel(name, fetched.replyChannel().orElseThrow());
                    }
                    putSpacePadded(required, channelOut, name, command + " CHANNEL");
                }
            }
        } else if (kind == ASYNC_FREE_CHILD) {
            CicsAsyncPort.Result result = async.free(task.taskId(), token.toByteArray());
            response = result.response();
            response2 = result.response2();
        } else {
            throw new IllegalArgumentException("unknown asynchronous API command: " + kind);
        }
        return containerOutcome(required, ASYNC_FUNCTIONS[kind], response, response2, suppressDefaultHandling, command);
    }

    private static long fullwordValue(DataView view, String option) {
        byte[] bytes = view.toByteArray();
        if (bytes.length != Integer.BYTES) {
            throw new CicsTaskStateException(option + " must be a fullword binary data area");
        }
        return java.nio.ByteBuffer.wrap(bytes).getInt();
    }

    /** 値を受取域へ空白を詰めて置く。 */
    private static void putSpacePadded(ProgramContext context, DataView area, String value, String option) {
        byte[] encoded = context.codePage().encode(value);
        if (encoded.length > area.length()) {
            throw new CicsTaskStateException(option + " value does not fit the receiving area");
        }
        byte[] padded = new byte[area.length()];
        java.util.Arrays.fill(padded, context.codePage().space());
        System.arraycopy(encoded, 0, padded, 0, encoded.length);
        area.setBytes(padded);
    }

    /** 他の task が持つ資源を待てる長さ。task の期限が無ければ null (限りなく待つ)。 */
    private static java.time.Duration maxWait(CicsExecution execution) {
        return execution.deadline()
                .map(deadline -> java.time.Duration.between(execution.environment().clock().instant(), deadline))
                .orElse(null);
    }

    /** 半語の 2 進の域の値 (符号つき)。 */
    private static int halfword(DataView view) {
        byte[] bytes = view.toByteArray();
        if (bytes.length != Short.BYTES) {
            throw new CicsTaskStateException("a halfword binary data area must be 2 bytes: " + bytes.length);
        }
        return (short) (((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF));
    }

    private static void setHalfword(DataView view, int value) {
        if (view.length() != Short.BYTES || value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
            throw new CicsTaskStateException("value " + value + " does not fit a halfword binary data area");
        }
        view.setBytes(new byte[] {(byte) (value >>> 8), (byte) value});
    }

    /**
     * INQUIRE ASSOCIATION(EIBTASKN) の origin data (暫定判断 P-132)。
     *
     * <p>端末から起きた task 自身の association を返す。書かれた受取域だけを埋め、値の出どころ
     * (region の APPLID、network ID、task の user ID、端末) が無ければ空白と推測せず失敗させる。
     * 受取域が無い option は null で渡る。
     */
    public static int inquireAssociationCondition(ProgramContext context, DataView applid, DataView userid,
            DataView facilityName, DataView networkId, DataView facilityType, boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        CicsExecution execution = execution(required);
        CicsTaskContext task = execution.task();
        if (applid != null) {
            putOriginText(required, applid, execution.environment().applid()
                    .orElseThrow(() -> new CicsTaskStateException("INQUIRE ASSOCIATION ODAPPLID requires a configured APPLID")));
        }
        if (userid != null) {
            putOriginText(required, userid, task.userId()
                    .orElseThrow(() -> new CicsTaskStateException("INQUIRE ASSOCIATION ODUSERID requires the task user ID")));
        }
        if (facilityName != null || facilityType != null) {
            String terminal = task.terminalId().orElseThrow(() -> new CicsTaskStateException(
                    "INQUIRE ASSOCIATION origin facility is known only for tasks started from a terminal"));
            if (facilityName != null) {
                putOriginText(required, facilityName, terminal);
            }
            if (facilityType != null) {
                setFullword(facilityType, CicsCvda.TERMINAL);
            }
        }
        if (networkId != null) {
            putOriginText(required, networkId, execution.environment().networkId()
                    .orElseThrow(() -> new CicsTaskStateException("INQUIRE ASSOCIATION ODNETWORKID requires a configured network ID")));
        }
        completeLocalCommand(required, INQUIRE_ASSOCIATION_FUNCTION);
        return NO_CONDITION_TRANSFER;
    }

    /** origin data の 8 文字の値を、空白を詰めて置く。 */
    private static void putOriginText(ProgramContext context, DataView area, String value) {
        if (area.length() != 8 || value.length() > 8) {
            throw new CicsTaskStateException("INQUIRE ASSOCIATION origin value must fit an 8-byte area: " + value);
        }
        area.setBytes(context.codePage().encode(value + " ".repeat(8 - value.length())));
    }

    /** DEQ RESOURCE(域) LENGTH(n)。持っていない資源を返しても NORMAL とする。 */
    public static int dequeueCondition(ProgramContext context, byte[] resource,
            boolean taskScope, boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        CicsExecution execution = execution(required);
        execution.environment().enqueues().dequeue(execution.task().taskId(), resource, taskScope);
        return containerOutcome(required, DEQ_FUNCTION, CicsResponseCode.NORMAL, 0,
                suppressDefaultHandling, "DEQ");
    }

    /** 現在のCICS LINK levelへcondition handler段落を登録する。 */
    public static void handleCondition(ProgramContext context, int responseCode, int target) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        execution(required).handleCondition(
                required.currentInvocationToken(), responseCode, target);
        completeLocalCommand(required, HANDLE_CONDITION_FUNCTION);
    }

    /** 現在のCICS LINK levelで指定conditionを無視する。 */
    public static void ignoreCondition(ProgramContext context, int responseCode) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        execution(required).ignoreCondition(responseCode);
        completeLocalCommand(required, IGNORE_CONDITION_FUNCTION);
    }

    /** 現在のCICS LINK levelで指定conditionをCICS既定処置へ戻す。 */
    public static void resetCondition(ProgramContext context, int responseCode) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        execution(required).resetCondition(responseCode);
        completeLocalCommand(required, HANDLE_CONDITION_FUNCTION);
    }

    /** 現在のCICS LINK levelのcondition処置一式を退避して一時停止する。 */
    public static void pushHandle(ProgramContext context) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        execution(required).pushHandle();
        completeLocalCommand(required, PUSH_HANDLE_FUNCTION);
    }

    /** 現在のCICS LINK levelで最後に退避したcondition処置一式を復元する。 */
    public static void popHandle(ProgramContext context) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        execution(required).popHandle();
        completeLocalCommand(required, POP_HANDLE_FUNCTION);
    }

    /** 現在のCICS LINK levelへCOBOL LABEL形式のabend exitを登録する。 */
    public static void handleAbend(ProgramContext context, int target) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        execution(required).handleAbend(required.currentInvocationToken(), target);
        completeLocalCommand(required, HANDLE_ABEND_FUNCTION);
    }

    /** 現在のCICS LINK levelのabend exitを無効化する。 */
    public static void cancelAbendHandler(ProgramContext context) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        execution(required).cancelAbendHandler();
        completeLocalCommand(required, HANDLE_ABEND_FUNCTION);
    }

    /** 現在のCICS LINK levelのabend exitを再有効化する。 */
    public static void resetAbendHandler(ProgramContext context) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        execution(required).resetAbendHandler();
        completeLocalCommand(required, HANDLE_ABEND_FUNCTION);
    }

    public static void abend(
            ProgramContext context, String code, boolean cancelHandlers, boolean noDump) {
        requireNoLegacyTransfer(
                abendCondition(context, code, cancelHandlers, noDump), "ABEND");
    }

    /**
     * GET CONTAINER ... INTO (設計 79 §9)。
     *
     * <p>FLENGTH は入口で受取域の長さ、出口でcontainerのデータの長さである。データが受取域より長ければ
     * 入る分だけ写して LENGERR。受取域の残りは書き換えない。変換 (INTOCCSID等) はしない。
     */
    public static int getContainerCondition(
            ProgramContext context, String nameLiteral, byte[] nameData,
            String channelLiteral, byte[] channelData,
            DataView into, DataView flength, boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        DataView area = Objects.requireNonNull(into, "into");
        String name = containerName(required, nameLiteral, nameData, "CONTAINER");
        String channelName = channelData == null && channelLiteral == null
                ? null : containerName(required, channelLiteral, channelData, "CHANNEL");
        int limit = area.length();
        if (flength != null) {
            limit = fullword(flength);
            if (limit < 0 || limit > area.length()) {
                throw new CicsTaskStateException("GET CONTAINER FLENGTH " + limit
                        + " does not fit the INTO area of " + area.length() + " bytes");
            }
        }
        CicsExecution execution = execution(required);
        Optional<Map<String, byte[]>> channel = execution.channel(channelName, false);
        int response = CicsResponseCode.NORMAL;
        int response2 = 0;
        if (channel.isEmpty()) {
            response = CicsResponseCode.CHANNELERR;
            response2 = 2;
        } else {
            byte[] data;
            synchronized (execution) {
                data = channel.orElseThrow().get(name);
            }
            if (data == null) {
                response = CicsResponseCode.CONTAINERERR;
                response2 = 10;
            } else {
                int copied = Math.min(limit, data.length);
                area.subView(0, copied).setBytes(java.util.Arrays.copyOf(data, copied));
                if (flength != null) {
                    setFullword(flength, data.length);
                }
                if (data.length > limit) {
                    response = CicsResponseCode.LENGERR;
                    response2 = 11;
                }
            }
        }
        return containerOutcome(required, GET_CONTAINER_FUNCTION, response, response2,
                suppressDefaultHandling, "GET CONTAINER");
    }

    /** PUT CONTAINER ... FROM (設計 79 §9)。channelが無ければ作る。DATATYPE は BIT だけを扱う。 */
    public static int putContainerCondition(
            ProgramContext context, String nameLiteral, byte[] nameData,
            String channelLiteral, byte[] channelData,
            DataView from, DataView flength, int flengthLiteral, boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        DataView area = Objects.requireNonNull(from, "from");
        String name = containerName(required, nameLiteral, nameData, "CONTAINER");
        String channelName = channelData == null && channelLiteral == null
                ? null : containerName(required, channelLiteral, channelData, "CHANNEL");
        int length = flength != null ? fullword(flength)
                : flengthLiteral >= 0 ? flengthLiteral : area.length();
        if (length < 0 || length > area.length()) {
            // 域の外を読むことになる長さは、実機では記憶域の内容しだいになる。推測せず断る
            throw new CicsTaskStateException("PUT CONTAINER FLENGTH " + length
                    + " does not fit the FROM area of " + area.length() + " bytes");
        }
        CicsExecution execution = execution(required);
        Map<String, byte[]> channel = execution.channel(channelName, true).orElseThrow();
        byte[] data = area.subView(0, length).toByteArray();
        synchronized (execution) {
            channel.put(name, data);
        }
        return containerOutcome(required, PUT_CONTAINER_FUNCTION, CicsResponseCode.NORMAL, 0,
                suppressDefaultHandling, "PUT CONTAINER");
    }

    private static int containerOutcome(ProgramContext context, int function, int response,
            int response2, boolean suppressDefaultHandling, String command) {
        execution(context).eib(context.codePage()).completeCommand(function, response, response2);
        return conditionTarget(context,
                new CicsCommandOutcome(response, response2, new ContinueControl(CicsPayload.empty())),
                suppressDefaultHandling, command);
    }

    /** channel / container の名前。末尾の空白だけを落とし、大文字小文字は変えない。 */
    private static String containerName(
            ProgramContext context, String literal, byte[] data, String option) {
        String text = literal != null ? literal : context.codePage().decode(data);
        String name = text.stripTrailing();
        if (!CONTAINER_NAME.matcher(name).matches()) {
            // 実機が許す文字はもっと広いが、突き合わせていない文字は通さない (暫定判断 P-125)
            throw new CicsTaskStateException(option + " name is not supported: '" + text + "'");
        }
        return name;
    }

    private static int fullword(DataView view) {
        byte[] bytes = view.toByteArray();
        if (bytes.length != Integer.BYTES) {
            throw new CicsTaskStateException("FLENGTH must be a 4-byte binary integer");
        }
        return java.nio.ByteBuffer.wrap(bytes).getInt();
    }

    private static void setFullword(DataView view, int value) {
        view.setBytes(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    /** ABCODE(データ名) の ABEND。4 byte の域を実行時 code page で読み、末尾の空白を落とす。 */
    public static int abendCondition(
            ProgramContext context, byte[] code, boolean cancelHandlers, boolean noDump) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        String text = required.codePage().decode(Objects.requireNonNull(code, "code")).stripTrailing();
        if (text.isEmpty()) {
            // 空白の域を「コード無し」と読むと別の終わり方になる。推測せず失敗させる
            throw new CicsTaskStateException("ABEND ABCODE data area is blank");
        }
        return abendCondition(required, text, cancelHandlers, noDump);
    }

    /**
     * BIF DEEDIT。数字以外の文字を除き、残った数字を右へ詰めて左を 0 で埋める。
     *
     * <p>公開仕様の記述による (V1)。域が負号 {@code -} または {@code CR} で終われば、右端の byte に
     * 負のゾーン ({@code X'D'}) を置く。ゾーンは EBCDIC の数字の形なので、それ以外の code page では
     * 推測せず失敗させる (暫定判断 P-124)。
     */
    public static void deedit(ProgramContext context, DataView field) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        DataView target = Objects.requireNonNull(field, "field");
        dev.cobolonjava.runtime.codepage.CodePage codePage = required.codePage();
        String text = codePage.decode(target.toByteArray());
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        String trimmed = text.stripTrailing();
        boolean negative = trimmed.endsWith("-") || trimmed.endsWith("CR");
        int length = target.length();
        String kept = digits.length() > length
                ? digits.substring(digits.length() - length) : digits.toString();
        byte[] out = codePage.encode("0".repeat(length - kept.length()) + kept);
        if (negative && length > 0) {
            if ((codePage.digit(0) & 0xF0) != 0xF0) {
                throw new CicsTaskStateException("BIF DEEDIT sign handling requires an EBCDIC code page");
            }
            out[length - 1] = (byte) ((out[length - 1] & 0x0F) | 0xD0);
        }
        target.setBytes(out);
        completeLocalCommand(required, BIF_DEEDIT_FUNCTION);
    }

    /** ABENDを実行し、LABEL形式のabend exitへ移る場合はその段落番号を返す。 */
    public static int abendCondition(
            ProgramContext context, String code, boolean cancelHandlers, boolean noDump) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        AbendCommand command = code == null
                ? AbendCommand.unspecified(cancelHandlers)
                : new AbendCommand(Optional.of(CicsAbendCode.of(code)),
                        cancelHandlers, noDump);
        CicsExecution execution = execution(required);
        if (cancelHandlers) {
            execution.cancelAllAbendHandlers();
        }
        try {
            execution.execute(command);
        } catch (CicsAbend failure) {
            execution.recordAbend(failure.code());
            if (cancelHandlers) {
                throw failure;
            }
            Optional<CicsExecution.ConditionHandler> handler = execution.takeAbendHandler();
            if (handler.isEmpty()) {
                throw failure;
            }
            CicsExecution.ConditionHandler selected = handler.orElseThrow();
            if (selected.owner() != required.currentInvocationToken()) {
                throw new ProgramTargetTransfer(
                        selected.owner(), selected.target(), "CICS abend transfer");
            }
            return selected.target();
        }
        throw new CicsTaskStateException("ABEND command returned without terminating the task");
    }

    /** 現在のabend codeを4文字で返す。abend未発生時はCICS互換の空白4文字である。 */
    public static void assignAbcode(ProgramContext context, DataView target) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        DataView receiver = Objects.requireNonNull(target, "target");
        if (receiver.length() != 4) {
            throw new IllegalArgumentException("ASSIGN ABCODE target must be exactly 4 bytes");
        }
        CicsExecution execution = execution(required);
        String code = execution.currentAbendCode()
                .map(CicsAbendCode::value)
                .orElse("");
        receiver.setBytes(required.codePage().encode((code + "    ").substring(0, 4)));
        completeLocalCommand(required, ASSIGN_FUNCTION);
    }

    /** ABSTIMEの起点。1900年1月1日0時 (地方時) からのミリ秒である (設計 79 §6.1)。 */
    private static final java.time.LocalDateTime ABSTIME_EPOCH =
            java.time.LocalDateTime.of(1900, 1, 1, 0, 0);
    /** ABSTIMEの受取域 PACKED-DECIMAL(15) の長さ。 */
    private static final int ABSTIME_LENGTH = 8;

    /**
     * ASKTIME。時計を読み、EIBDATE / EIBTIMEを更新し、指定があればABSTIMEを置く。
     *
     * <p>地方時はtaskのhostZoneで決める。構成されていなければJVMの既定から推測せず失敗する。
     *
     * @param abstime 受取域。ABSTIMEを書かなければ null
     */
    public static void askTime(ProgramContext context, DataView abstime) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        CicsExecution execution = execution(required);
        java.time.ZoneId zone = execution.task().hostZone().orElseThrow(() ->
                new CicsTaskStateException("ASKTIME requires a configured host time zone"));
        java.time.LocalDateTime local = execution.environment().clock().instant()
                .atZone(zone).toLocalDateTime();
        if (abstime != null) {
            if (abstime.length() != ABSTIME_LENGTH) {
                throw new IllegalArgumentException("ASKTIME ABSTIME target must be exactly 8 bytes");
            }
            long millis = java.time.Duration.between(ABSTIME_EPOCH, local).toMillis();
            abstime.setBytes(packed(millis, ABSTIME_LENGTH));
        }
        execution.eib(required.codePage()).setDateTime(local);
        completeLocalCommand(required, ASKTIME_FUNCTION);
    }

    /**
     * FORMATTIME の初期subset (設計 79 §6.2)。
     *
     * <p>書くのは区切りの有無で決まる文字数だけであり、受取域の残りは変えない (暫定判断 P-115)。
     *
     * @param dateOrder {@code 0}=DDMMYYYY、{@code 1}=YYYYMMDD、{@code 2}=MMDDYYYY。日付を書かなければ無視する
     */
    public static void formatTime(
            ProgramContext context, dev.cobolonjava.runtime.decimal.Decimal abstime,
            int dateOrder, DataView date, String dateSeparator,
            DataView time, String timeSeparator) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        long millis;
        try {
            millis = Objects.requireNonNull(abstime, "abstime").toBigDecimal().longValueExact();
        } catch (ArithmeticException notInteger) {
            throw new CicsTaskStateException("FORMATTIME ABSTIME is not an integer");
        }
        if (millis < 0) {
            throw new CicsTaskStateException("FORMATTIME ABSTIME is negative");
        }
        java.time.LocalDateTime local = ABSTIME_EPOCH.plus(java.time.Duration.ofMillis(millis));
        if (date != null) {
            String sep = dateSeparator == null ? "" : dateSeparator;
            String dd = two(local.getDayOfMonth());
            String mm = two(local.getMonthValue());
            String yyyy = String.format("%04d", local.getYear());
            String text = switch (dateOrder) {
                case 0 -> dd + sep + mm + sep + yyyy;
                case 1 -> yyyy + sep + mm + sep + dd;
                case 2 -> mm + sep + dd + sep + yyyy;
                default -> throw new IllegalArgumentException("unknown FORMATTIME date order");
            };
            writePrefix(required, date, text, "FORMATTIME date");
        }
        if (time != null) {
            String sep = timeSeparator == null ? "" : timeSeparator;
            writePrefix(required, time, two(local.getHour()) + sep + two(local.getMinute())
                    + sep + two(local.getSecond()), "FORMATTIME TIME");
        }
        completeLocalCommand(required, FORMATTIME_FUNCTION);
    }

    /** SEND 命令の option を 1 つの int へ畳んだ bit。生成コードの引数を増やさないためである。 */
    public static final int SEND_ERASE = 1;
    public static final int SEND_MAPONLY = 2;
    public static final int SEND_DATAONLY = 4;
    public static final int SEND_FREEKB = 8;
    public static final int SEND_ALARM = 16;
    public static final int SEND_FRSET = 32;
    /** CURSOR を書かなかった。 */
    public static final int CURSOR_NONE = -1;
    /** 値を持たない CURSOR (記号 cursor)。 */
    public static final int CURSOR_SYMBOLIC = -2;
    /** SEND TEXT が置ける画面の大きさ。端末 profile を持つまで 24x80 に限る。 */
    private static final int TEXT_SCREEN_SIZE = 24 * 80;

    /**
     * SEND MAP (設計 79 §8.3)。
     *
     * @param from 記号マップ。MAPONLY なら null
     */
    public static int sendMapCondition(
            ProgramContext context, String mapsetName, String mapName, DataView from,
            int flags, int cursor, boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        CicsExecution execution = execution(required);
        dev.cobolonjava.cics.bms.BmsMapsetCatalog catalog = execution.environment().mapsets()
                .orElseThrow(() -> new CicsTaskStateException(
                        "SEND MAP requires a configured BMS mapset catalog"));
        dev.cobolonjava.cics.bms.BmsModel.Mapset mapset = catalog.mapset(mapsetName)
                .orElseThrow(() -> new CicsTaskStateException("mapset is not defined: " + mapsetName));
        dev.cobolonjava.cics.bms.BmsModel.Map map = mapset.map(mapName)
                .orElseThrow(() -> new CicsTaskStateException(
                        "map " + mapName + " is not defined in mapset " + mapsetName));
        boolean erase = (flags & SEND_ERASE) != 0;
        boolean dataOnly = (flags & SEND_DATAONLY) != 0;
        Optional<CicsTerminalScreen> shown = execution.terminalScreen();
        if (shown.isPresent() && shown.get() instanceof CicsTerminalScreen.TextScreen
                && !erase) {
            throw new CicsTaskStateException("SEND MAP over a text screen requires ERASE");
        }
        Optional<dev.cobolonjava.cics.bms.BmsScreenSnapshot> current = shown
                .filter(CicsTerminalScreen.MapScreen.class::isInstance)
                .map(screen -> ((CicsTerminalScreen.MapScreen) screen).snapshot());
        dev.cobolonjava.cics.bms.BmsScreenComposer.SendOptions options =
                new dev.cobolonjava.cics.bms.BmsScreenComposer.SendOptions(
                        erase, (flags & SEND_MAPONLY) != 0, dataOnly,
                        (flags & SEND_FREEKB) != 0, (flags & SEND_ALARM) != 0,
                        (flags & SEND_FRSET) != 0,
                        cursor >= 0 ? java.util.OptionalInt.of(cursor) : java.util.OptionalInt.empty(),
                        cursor == CURSOR_SYMBOLIC);
        dev.cobolonjava.cics.bms.BmsScreenSnapshot snapshot;
        try {
            snapshot = dev.cobolonjava.cics.bms.BmsScreenComposer.send(mapset, map, current,
                    from == null ? null : from.toByteArray(), options, required.codePage());
        } catch (IllegalStateException | IllegalArgumentException invalid) {
            throw new CicsTaskStateException("SEND MAP " + mapName + ": " + invalid.getMessage());
        }
        execution.showScreen(new CicsTerminalScreen.MapScreen(snapshot));
        completeLocalCommand(required, SEND_MAP_FUNCTION);
        return NO_CONDITION_TRANSFER;
    }

    /**
     * RECEIVE MAP (設計 79 §8.2, §8.4)。
     *
     * <p>入力は要求が運んだ端末入力、照合する画面は直前の task が会話へ残した画面である。
     * どちらも無ければ、同じ task の中で入力を待つ会話型の RECEIVE であり、同期 HTTP の task では
     * 表せないので失敗させる。送られた field が無ければ MAPFAIL。
     */
    public static int receiveMapCondition(
            ProgramContext context, String mapsetName, String mapName, DataView into,
            boolean asis, boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        CicsExecution execution = execution(required);
        dev.cobolonjava.cics.bms.BmsMapsetCatalog catalog = execution.environment().mapsets()
                .orElseThrow(() -> new CicsTaskStateException(
                        "RECEIVE MAP requires a configured BMS mapset catalog"));
        dev.cobolonjava.cics.bms.BmsModel.Mapset mapset = catalog.mapset(mapsetName)
                .orElseThrow(() -> new CicsTaskStateException("mapset is not defined: " + mapsetName));
        dev.cobolonjava.cics.bms.BmsModel.Map map = mapset.map(mapName)
                .orElseThrow(() -> new CicsTaskStateException(
                        "map " + mapName + " is not defined in mapset " + mapsetName));
        dev.cobolonjava.cics.bms.BmsTerminalInput carried = execution.task().terminalInput()
                .orElseThrow(() -> new CicsTaskStateException(
                        "RECEIVE MAP requires terminal input carried by the request;"
                                + " a conversational RECEIVE cannot wait within one task"));
        // 端末が大文字変換 (UCTRAN) なら、map への入力も大文字にしてから読む。TRANIDONLY は transaction ID だけ
        boolean uppercase = !asis && execution.task().terminalId()
                .map(terminal -> execution.environment().terminals().uppercaseTranslation(terminal) == CicsCvda.UCTRAN)
                .orElse(false);
        dev.cobolonjava.cics.bms.BmsTerminalInput input = uppercase ? carried.uppercased() : carried;
        dev.cobolonjava.cics.bms.BmsScreenSnapshot screen = execution.task().screen()
                .orElseThrow(() -> new CicsTaskStateException(
                        "RECEIVE MAP requires the screen sent by the previous task"));
        dev.cobolonjava.cics.bms.BmsInputDecoder.Result result;
        try {
            result = dev.cobolonjava.cics.bms.BmsInputDecoder.receive(
                    mapset, map, screen, input, required.codePage());
        } catch (IllegalStateException | IllegalArgumentException invalid) {
            throw new CicsTaskStateException("RECEIVE MAP " + mapName + ": " + invalid.getMessage());
        }
        if (result instanceof dev.cobolonjava.cics.bms.BmsInputDecoder.MapFail) {
            CicsCommandOutcome outcome = new CicsCommandOutcome(
                    CicsResponseCode.MAPFAIL, 0, new ContinueControl(CicsPayload.empty()));
            execution.eib(required.codePage()).completeCommand(
                    RECEIVE_MAP_FUNCTION, CicsResponseCode.MAPFAIL, 0);
            return conditionTarget(required, outcome, suppressDefaultHandling, "RECEIVE MAP");
        }
        dev.cobolonjava.cics.bms.BmsInputDecoder.Received received =
                (dev.cobolonjava.cics.bms.BmsInputDecoder.Received) result;
        byte[] symbolic = received.symbolic();
        if (Objects.requireNonNull(into, "into").length() != symbolic.length) {
            throw new CicsTaskStateException("RECEIVE MAP " + mapName + " INTO length " + into.length()
                    + " does not match the map (" + symbolic.length + ")");
        }
        into.setBytes(symbolic);
        execution.showScreen(new CicsTerminalScreen.MapScreen(received.screen()));
        completeLocalCommand(required, RECEIVE_MAP_FUNCTION);
        return NO_CONDITION_TRANSFER;
    }

    /** SEND TEXT (設計 79 §8.5)。初期は 1 画面に収まる文字だけを受ける。 */
    public static int sendTextCondition(
            ProgramContext context, DataView from, int flags, boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        CicsExecution execution = execution(required);
        if (Objects.requireNonNull(from, "from").length() > TEXT_SCREEN_SIZE) {
            throw new CicsTaskStateException("SEND TEXT longer than one 24x80 screen is not supported yet");
        }
        if (execution.terminalScreen().isPresent() && (flags & SEND_ERASE) == 0) {
            throw new CicsTaskStateException("SEND TEXT over an existing screen requires ERASE");
        }
        String text = required.codePage().decode(from.toByteArray()).replace(' ', ' ');
        execution.showScreen(new CicsTerminalScreen.TextScreen(text,
                (flags & SEND_FREEKB) != 0, (flags & SEND_ALARM) != 0));
        completeLocalCommand(required, SEND_TEXT_FUNCTION);
        return NO_CONDITION_TRANSFER;
    }

    /** SEND CONTROL (設計 79 §8.5)。ERASE は画面を空にし、それ以外は画面の内容を変えない。 */
    public static int sendControlCondition(
            ProgramContext context, int flags, int cursor, boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        CicsExecution execution = execution(required);
        boolean freeKeyboard = (flags & SEND_FREEKB) != 0;
        boolean alarm = (flags & SEND_ALARM) != 0;
        Optional<CicsTerminalScreen> shown = execution.terminalScreen();
        CicsTerminalScreen next;
        if ((flags & SEND_ERASE) != 0 || shown.isEmpty()) {
            if (cursor >= 0) {
                throw new CicsTaskStateException("SEND CONTROL CURSOR requires a map on the screen");
            }
            next = new CicsTerminalScreen.TextScreen("", freeKeyboard, alarm);
        } else if (shown.get() instanceof CicsTerminalScreen.MapScreen mapScreen) {
            try {
                next = new CicsTerminalScreen.MapScreen(
                        dev.cobolonjava.cics.bms.BmsScreenComposer.control(mapScreen.snapshot(),
                                freeKeyboard, alarm, (flags & SEND_FRSET) != 0,
                                cursor >= 0 ? java.util.OptionalInt.of(cursor)
                                        : java.util.OptionalInt.empty()));
            } catch (IllegalStateException invalid) {
                throw new CicsTaskStateException("SEND CONTROL: " + invalid.getMessage());
            }
        } else {
            CicsTerminalScreen.TextScreen text = (CicsTerminalScreen.TextScreen) shown.get();
            if (cursor >= 0) {
                throw new CicsTaskStateException("SEND CONTROL CURSOR requires a map on the screen");
            }
            next = new CicsTerminalScreen.TextScreen(text.text(),
                    text.keyboardRestored() || freeKeyboard, text.alarm() || alarm);
        }
        execution.showScreen(next);
        completeLocalCommand(required, SEND_CONTROL_FUNCTION);
        return NO_CONDITION_TRANSFER;
    }

    /**
     * DELAY (設計 79 §7)。
     *
     * <p>{@code FOR} の各単位または {@code INTERVAL(hhmmss)} を受ける。書かなかった単位は null。
     * 範囲外は RESP2 を推測せず失敗させる。task の期限を越える待ちは始めない。
     *
     * @return condition handler へ移るなら段落番号。DELAY は現状 NORMAL だけを返す
     */
    public static int delayCondition(
            ProgramContext context,
            dev.cobolonjava.runtime.decimal.Decimal hours,
            dev.cobolonjava.runtime.decimal.Decimal minutes,
            dev.cobolonjava.runtime.decimal.Decimal seconds,
            dev.cobolonjava.runtime.decimal.Decimal millis,
            dev.cobolonjava.runtime.decimal.Decimal interval,
            boolean suppressDefaultHandling) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        java.time.Duration duration = delayDuration(hours, minutes, seconds, millis, interval);
        CicsExecution execution = execution(required);
        java.time.Instant now = execution.environment().clock().instant();
        execution.deadline().ifPresent(deadline -> {
            if (now.plus(duration).isAfter(deadline)) {
                throw new CicsTaskStateException("DELAY of " + duration
                        + " would exceed the task deadline");
            }
        });
        execution.environment().interval().delay(duration);
        completeLocalCommand(required, DELAY_FUNCTION);
        return NO_CONDITION_TRANSFER;
    }

    static java.time.Duration delayDuration(
            dev.cobolonjava.runtime.decimal.Decimal hours,
            dev.cobolonjava.runtime.decimal.Decimal minutes,
            dev.cobolonjava.runtime.decimal.Decimal seconds,
            dev.cobolonjava.runtime.decimal.Decimal millis,
            dev.cobolonjava.runtime.decimal.Decimal interval) {
        if (interval != null) {
            if (hours != null || minutes != null || seconds != null || millis != null) {
                throw new IllegalArgumentException("DELAY INTERVAL cannot be combined with FOR");
            }
            long hhmmss = delayValue(interval, 995_959, "INTERVAL");
            long mm = hhmmss / 100 % 100;
            long ss = hhmmss % 100;
            if (mm > 59 || ss > 59) {
                throw new CicsTaskStateException("DELAY INTERVAL is not a valid hhmmss value");
            }
            return java.time.Duration.ofHours(hhmmss / 10_000).plusMinutes(mm).plusSeconds(ss);
        }
        int units = (hours == null ? 0 : 1) + (minutes == null ? 0 : 1)
                + (seconds == null ? 0 : 1) + (millis == null ? 0 : 1);
        if (units == 0) {
            throw new IllegalArgumentException("DELAY requires FOR or INTERVAL");
        }
        // 1つの単位だけを書けば上限までその単位で数え、複数書けば下位の単位は桁上がり前に限る
        boolean single = units == 1;
        long h = hours == null ? 0 : delayValue(hours, 99, "HOURS");
        long m = minutes == null ? 0 : delayValue(minutes, single ? 5_999 : 59, "MINUTES");
        long s = seconds == null ? 0 : delayValue(seconds, single ? 359_999 : 59, "SECONDS");
        long ms = millis == null ? 0 : delayValue(millis, single ? 359_999_999 : 999, "MILLISECS");
        return java.time.Duration.ofHours(h).plusMinutes(m).plusSeconds(s).plusMillis(ms);
    }

    private static long delayValue(
            dev.cobolonjava.runtime.decimal.Decimal value, long max, String option) {
        long parsed;
        try {
            parsed = value.toBigDecimal().longValueExact();
        } catch (ArithmeticException notInteger) {
            throw new CicsTaskStateException("DELAY " + option + " is not an integer");
        }
        if (parsed < 0 || parsed > max) {
            throw new CicsTaskStateException("DELAY " + option + " is out of range: " + parsed);
        }
        return parsed;
    }

    private static String two(int value) {
        return String.format("%02d", value);
    }

    private static void writePrefix(
            ProgramContext context, DataView target, String text, String option) {
        if (target.length() < text.length()) {
            throw new IllegalArgumentException(
                    option + " target is shorter than " + text.length() + " bytes");
        }
        target.subView(0, text.length()).setBytes(context.codePage().encode(text));
    }

    /** 0以上の整数を、符号の半byteをCとしたpacked decimalにする。 */
    private static byte[] packed(long value, int length) {
        int digits = length * 2 - 1;
        String text = Long.toString(value);
        if (value < 0 || text.length() > digits) {
            throw new CicsTaskStateException("value does not fit PL" + length + ": " + value);
        }
        String padded = "0".repeat(digits - text.length()) + text;
        byte[] out = new byte[length];
        for (int k = 0; k < length; k++) {
            int high = padded.charAt(k * 2) - '0';
            int low = k * 2 + 1 < digits ? padded.charAt(k * 2 + 1) - '0' : 0xC;
            out[k] = (byte) (high << 4 | low);
        }
        return out;
    }

    /**
     * regionのAPPLIDを8文字で返す (設計 79 §5)。
     *
     * <p>構成されていなければ推測した名前を返さず失敗する。
     */
    public static void assignApplid(ProgramContext context, DataView target) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        String applid = execution(required).environment().applid()
                .orElseThrow(() -> new CicsTaskStateException(
                        "ASSIGN APPLID requires a configured APPLID"));
        assignText(required, target, applid, 8, "ASSIGN APPLID");
    }

    /** 現在のLINK levelでCICSが起動したprogramの名前を8文字で返す (設計 79 §5)。 */
    public static void assignProgram(ProgramContext context, DataView target) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        assignText(required, target, execution(required).currentProgram(), 8, "ASSIGN PROGRAM");
    }

    private static void assignText(
            ProgramContext context, DataView target, String value, int length, String command) {
        DataView receiver = Objects.requireNonNull(target, "target");
        if (receiver.length() != length) {
            throw new IllegalArgumentException(
                    command + " target must be exactly " + length + " bytes");
        }
        if (value.length() > length) {
            throw new CicsTaskStateException(command + " value is longer than " + length);
        }
        receiver.setBytes(context.codePage().encode(
                value + " ".repeat(length - value.length())));
        completeLocalCommand(context, ASSIGN_FUNCTION);
    }

    /** 生成コードが暗黙EIB項目を参照するためのtask-local storage。 */
    public static Storage eibStorage(ProgramContext context) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        return execution(required).eib(required.codePage()).storage();
    }

    private static CicsCommandOutcome execute(ProgramContext context, CicsCommand command) {
        ProgramContext required = Objects.requireNonNull(context, "context");
        CicsExecution execution = execution(required);
        CicsCommandOutcome outcome = execution.execute(command);
        execution.eib(required.codePage()).completeCommand(
                functionCode(command), outcome.responseCode(), outcome.responseCode2());
        return outcome;
    }

    private static void completeLocalCommand(ProgramContext context, int functionCode) {
        execution(context).eib(context.codePage()).completeCommand(
                functionCode, CicsResponseCode.NORMAL, 0);
    }

    private static int functionCode(CicsCommand command) {
        return switch (command) {
            case LinkCommand ignored -> LINK_FUNCTION;
            case XctlCommand ignored -> XCTL_FUNCTION;
            case ReturnCommand ignored -> RETURN_FUNCTION;
            case SyncpointCommand ignored -> SYNCPOINT_FUNCTION;
            case AbendCommand ignored -> throw new IllegalArgumentException(
                    "ABEND does not complete normally and must not update the EIB");
        };
    }

    private static CicsExecution execution(ProgramContext context) {
        return Objects.requireNonNull(context, "context").service(CicsExecution.class);
    }

    private static int conditionTarget(
            ProgramContext context, CicsCommandOutcome outcome,
            boolean suppressDefaultHandling, String command) {
        if (outcome.responseCode() == CicsResponseCode.NORMAL) {
            return NO_CONDITION_TRANSFER;
        }
        if (suppressDefaultHandling) {
            return NO_CONDITION_TRANSFER;
        }
        CicsExecution execution = execution(context);
        Optional<CicsExecution.ConditionHandler> handler =
                execution.conditionHandler(outcome.responseCode());
        if (handler.isPresent()) {
            CicsExecution.ConditionHandler selected = handler.orElseThrow();
            if (selected.target() == CicsExecution.IGNORE_CONDITION) {
                return NO_CONDITION_TRANSFER;
            }
            if (selected.target() == CicsExecution.DEFAULT_CONDITION) {
                throw defaultCondition(command, outcome);
            }
            if (selected.owner() != context.currentInvocationToken()) {
                throw new ProgramTargetTransfer(
                        selected.owner(), selected.target(), "CICS condition transfer");
            }
            return selected.target();
        }
        throw defaultCondition(command, outcome);
    }

    private static CicsTaskStateException defaultCondition(
            String command, CicsCommandOutcome outcome) {
        return new CicsTaskStateException(
                command + " failed with RESP=" + outcome.responseCode()
                        + " RESP2=" + outcome.responseCode2());
    }

    private static void requireNoLegacyTransfer(int target, String command) {
        if (target >= 0) {
            throw new CicsTaskStateException(command
                    + " selected a condition handler through the legacy runtime API");
        }
    }

    private static CicsPayload payload(DataView commarea) {
        return commarea == null
                ? CicsPayload.empty()
                : CicsPayload.ofCommarea(commarea.toByteArray());
    }

    private static void copyBack(DataView target, CicsPayload returned, String command) {
        byte[] bytes = returned.commarea();
        if (target == null) {
            if (bytes.length != 0) {
                throw new CicsTaskStateException(
                        command + " returned a COMMAREA where none was supplied");
            }
            return;
        }
        if (bytes.length != target.length()) {
            throw new CicsTaskStateException(
                    command + " changed COMMAREA length from " + target.length()
                            + " to " + bytes.length);
        }
        target.setBytes(bytes);
    }

    private static <T extends CicsControl> T requireControl(
            CicsCommandOutcome outcome, Class<T> type, String command) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome.responseCode() != CicsResponseCode.NORMAL) {
            throw new CicsTaskStateException(
                    command + " failed with RESP=" + outcome.responseCode()
                            + " RESP2=" + outcome.responseCode2());
        }
        if (!type.isInstance(outcome.control())) {
            throw new CicsTaskStateException(
                    command + " returned unexpected control "
                            + outcome.control().getClass().getSimpleName());
        }
        return type.cast(outcome.control());
    }
}
