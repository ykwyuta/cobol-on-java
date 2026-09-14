package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.program.ProgramTargetTransfer;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
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
    private static final int DELAY_FUNCTION = 0x1004;
    /** BMS 群の function code。実機の EIBFN とは突き合わせていない (暫定判断 P-118)。 */
    private static final int RECEIVE_MAP_FUNCTION = 0x1802;
    private static final int SEND_MAP_FUNCTION = 0x1804;
    private static final int SEND_TEXT_FUNCTION = 0x1806;
    private static final int SEND_CONTROL_FUNCTION = 0x1812;
    private static final int FORMATTIME_FUNCTION = 0x104A;
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
        return NO_CONDITION_TRANSFER;
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
            boolean suppressDefaultHandling) {
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
        dev.cobolonjava.cics.bms.BmsTerminalInput input = execution.task().terminalInput()
                .orElseThrow(() -> new CicsTaskStateException(
                        "RECEIVE MAP requires terminal input carried by the request;"
                                + " a conversational RECEIVE cannot wait within one task"));
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
