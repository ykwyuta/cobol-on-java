package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 生成programがtaskのCicsGatewayへ到達するためのsession service。 */
public final class CicsExecution {

    private final CicsTaskContext task;
    private final int commareaLength;
    private final CicsEnvironment environment;
    private CicsGateway gateway;
    private CicsEib eib;
    private CodePage eibCodePage;
    /** HANDLE ABEND exitからASSIGN ABCODEで参照する現在のabend code。 */
    private CicsAbendCode currentAbendCode;
    /** taskのrootから現在のEXEC CICS LINK levelまでのHANDLE処置。 */
    private final Deque<HandleLevel> handleLevels = new ArrayDeque<>();

    /** conditionを無視して次の文へ進むことを表す内部値。段落番号は0以上である。 */
    static final int IGNORE_CONDITION = -1;
    /** 明示的にCICS既定処置を選び、generalized ERRORへfallbackしない内部値。 */
    static final int DEFAULT_CONDITION = -2;

    /** handler段落を登録したprogram入口と、そのprogram内の段落番号。 */
    record ConditionHandler(Object owner, int target) {
    }

    /** HANDLE ABEND LABELの所有program、段落番号、有効状態。 */
    private record AbendHandler(Object owner, int target, boolean active) {
        private AbendHandler deactivate() {
            return new AbendHandler(owner, target, false);
        }

        private AbendHandler activate() {
            return new AbendHandler(owner, target, true);
        }
    }

    /** PUSH HANDLEで一括退避する、現時点で対応済みのHANDLE状態。 */
    private record HandleSnapshot(
            Map<Integer, ConditionHandler> conditions, AbendHandler abend) {
    }

    /** 一つのCICS LINK levelに属する現在値とPUSH HANDLEの退避値。 */
    private static final class HandleLevel {
        /**
         * このlevelでCICSが起動したprogramの名前 (ASSIGN PROGRAM)。
         *
         * <p>COBOLのCALLで呼んだ副programはCICSから見えないので、ここを変えない。
         */
        private String programName;
        private final Map<Integer, ConditionHandler> handlers = new HashMap<>();
        private AbendHandler abendHandler;
        private final Deque<HandleSnapshot> savedHandlers = new ArrayDeque<>();
    }

    public CicsExecution(CicsTaskContext task, int commareaLength) {
        this(task, commareaLength, CicsEnvironment.unconfigured());
    }

    public CicsExecution(CicsTaskContext task, int commareaLength, CicsEnvironment environment) {
        this.task = Objects.requireNonNull(task, "task");
        if (commareaLength < 0 || commareaLength > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "commareaLength must fit EIBCALEN: " + commareaLength);
        }
        this.commareaLength = commareaLength;
        this.environment = Objects.requireNonNull(environment, "environment");
        handleLevels.push(new HandleLevel());
    }

    /** regionの構成。 */
    public CicsEnvironment environment() {
        return environment;
    }

    /** このexecutionが属するtask。 */
    public CicsTaskContext task() {
        return task;
    }

    /** 初期programまたはXCTL先が、現在のLINK levelのprogramになる。 */
    public synchronized void startProgram(String programName) {
        currentHandleLevel().programName = Objects.requireNonNull(programName, "programName");
    }

    /** 現在のLINK levelでCICSが起動したprogramの名前。起動記録が無ければ失敗する。 */
    public synchronized String currentProgram() {
        String name = currentHandleLevel().programName;
        if (name == null) {
            throw new CicsTaskStateException("no CICS program is recorded for the current LINK level");
        }
        return name;
    }

    public synchronized void bind(CicsGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        if (this.gateway != null) {
            throw new CicsTaskStateException("CICS execution is already bound");
        }
        this.gateway = gateway;
    }

    public CicsCommandOutcome execute(CicsCommand command) {
        Objects.requireNonNull(command, "command");
        CicsGateway current;
        synchronized (this) {
            current = gateway;
        }
        if (current == null) {
            throw new CicsTaskStateException("CICS execution is not bound to a gateway");
        }
        boolean link = command instanceof LinkCommand;
        if (link) {
            synchronized (this) {
                HandleLevel level = new HandleLevel();
                level.programName = ((LinkCommand) command).target().value();
                handleLevels.push(level);
            }
        }
        try {
            return Objects.requireNonNull(current.execute(command, task), "CICS command outcome");
        } finally {
            if (link) {
                synchronized (this) {
                    handleLevels.pop();
                }
            }
        }
    }

    /** 実行時code pageで初期化した、task内で一つのEIBを返す。 */
    public synchronized CicsEib eib(CodePage codePage) {
        Objects.requireNonNull(codePage, "codePage");
        if (eib == null) {
            eib = new CicsEib(task, commareaLength, codePage);
            eibCodePage = codePage;
        } else if (!eibCodePage.name().equals(codePage.name())) {
            throw new CicsTaskStateException("CICS EIB code page changed within a task");
        }
        return eib;
    }

    /** 現在のCICS LINK levelへcondition handler段落を登録する。 */
    public synchronized void handleCondition(Object owner, int responseCode, int target) {
        Objects.requireNonNull(owner, "owner");
        if (responseCode == CicsResponseCode.NORMAL) {
            throw new IllegalArgumentException("NORMAL cannot have a condition handler");
        }
        if (target < 0) {
            throw new IllegalArgumentException("condition handler target must be non-negative");
        }
        currentHandleLevel().handlers.put(
                responseCode, new ConditionHandler(owner, target));
    }

    /** 指定conditionをIGNOREへ変更する。 */
    public synchronized void ignoreCondition(int responseCode) {
        if (responseCode == CicsResponseCode.NORMAL) {
            throw new IllegalArgumentException("NORMAL cannot be ignored");
        }
        currentHandleLevel().handlers.put(responseCode,
                new ConditionHandler(null, IGNORE_CONDITION));
    }

    /** 指定conditionをCICS既定処置へ戻す。 */
    public synchronized void resetCondition(int responseCode) {
        if (responseCode == CicsResponseCode.NORMAL) {
            throw new IllegalArgumentException("NORMAL cannot select default handling");
        }
        currentHandleLevel().handlers.put(responseCode,
                new ConditionHandler(null, DEFAULT_CONDITION));
    }

    /** 現在のLINK levelへCOBOL LABEL形式のabend exitを登録する。 */
    public synchronized void handleAbend(Object owner, int target) {
        Objects.requireNonNull(owner, "owner");
        if (target < 0) {
            throw new IllegalArgumentException("abend handler target must be non-negative");
        }
        currentHandleLevel().abendHandler = new AbendHandler(owner, target, true);
    }

    /** 現在のLINK levelで直前に登録したabend exitを無効化する。 */
    public synchronized void cancelAbendHandler() {
        HandleLevel current = currentHandleLevel();
        if (current.abendHandler != null) {
            current.abendHandler = current.abendHandler.deactivate();
        }
    }

    /** 現在のLINK levelで直前に登録したabend exitを再有効化する。 */
    public synchronized void resetAbendHandler() {
        HandleLevel current = currentHandleLevel();
        if (current.abendHandler != null) {
            current.abendHandler = current.abendHandler.activate();
        }
    }

    /** ABEND CANCEL用にtask内の全levelでabend exitを無効化する。 */
    public synchronized void cancelAllAbendHandlers() {
        for (HandleLevel level : handleLevels) {
            if (level.abendHandler != null) {
                level.abendHandler = level.abendHandler.deactivate();
            }
        }
    }

    /**
     * 現levelから上位levelへ最初の有効なabend exitを探し、再入防止のため選択時に無効化する。
     */
    public synchronized Optional<ConditionHandler> takeAbendHandler() {
        for (HandleLevel level : handleLevels) {
            AbendHandler handler = level.abendHandler;
            if (handler != null && handler.active()) {
                level.abendHandler = handler.deactivate();
                return Optional.of(new ConditionHandler(handler.owner(), handler.target()));
            }
        }
        return Optional.empty();
    }

    /** abend exitへ制御を渡す前に、原因codeをtask-local状態へ記録する。 */
    synchronized void recordAbend(CicsAbendCode code) {
        currentAbendCode = Objects.requireNonNull(code, "code");
    }

    /** 現在のabend code。abendがまだ発生していなければ空である。 */
    synchronized Optional<CicsAbendCode> currentAbendCode() {
        return Optional.ofNullable(currentAbendCode);
    }

    /** 現在のLINK levelのcondition処置一式をLIFO stackへ退避し、その効果を一時停止する。 */
    public synchronized void pushHandle() {
        HandleLevel current = currentHandleLevel();
        current.savedHandlers.push(new HandleSnapshot(
                new HashMap<>(current.handlers), current.abendHandler));
        current.handlers.clear();
        current.abendHandler = null;
    }

    /** 最後のPUSH HANDLE時点のcondition処置一式を復元する。 */
    public synchronized void popHandle() {
        HandleLevel current = currentHandleLevel();
        if (current.savedHandlers.isEmpty()) {
            throw new CicsTaskStateException(
                    "POP HANDLE has no matching PUSH HANDLE in the current LINK level");
        }
        current.handlers.clear();
        HandleSnapshot restored = current.savedHandlers.pop();
        current.handlers.putAll(restored.conditions());
        current.abendHandler = restored.abend();
    }

    /**
     * 指定conditionの処置を返す。明示処置がなければgeneralized ERRORを探し、
     * どちらもなければ空（CICS既定処置）を返す。
     */
    public synchronized Optional<ConditionHandler> conditionHandler(int responseCode) {
        Map<Integer, ConditionHandler> handlers = currentHandleLevel().handlers;
        ConditionHandler exact = handlers.get(responseCode);
        return Optional.ofNullable(exact != null
                ? exact : handlers.get(CicsResponseCode.ERROR_HANDLER_KEY));
    }

    private HandleLevel currentHandleLevel() {
        HandleLevel current = handleLevels.peek();
        if (current == null) {
            throw new CicsTaskStateException("CICS condition level stack is empty");
        }
        return current;
    }
}
