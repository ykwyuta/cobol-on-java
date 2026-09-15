package dev.cobolonjava.cics;

import dev.cobolonjava.cics.bms.BmsScreenSnapshot;
import dev.cobolonjava.cics.bms.BmsTerminalInput;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * 一つの同期要求に固定される、framework非依存のtask文脈。
 *
 * @param taskNumber    EIBTASKNへ出すtask番号。hostの領域ごとの連番に当たるものは
 *                      adapterだけが知っている。{@link #taskId()}から作らない
 * @param hostZone      EIBDATE / EIBTIMEを出す地方時。hostの領域が動いていた時間帯は
 *                      構成でしか分からないので、JVMの既定から推測しない
 * @param terminalInput 要求が運んだ端末入力。EIBAID / EIBCPOSNとRECEIVE MAPが読む
 * @param screen        直前のtaskが送った画面。RECEIVE MAPが入力と照合する
 * @param terminalId    taskを起こした端末の名前 (EIBTRMID)。adapterが決める。端末が無ければ空
 * @param userId        taskを起こした利用者のCICS user ID (8文字まで)。{@link #owner()}とは別に
 *                      adapterが認証から決める。分からなければ空
 * @param start         taskを起こしたSTARTが渡したもの。RETRIEVEが読む。STARTで起きたtaskでなければ空
 */
public record CicsTaskContext(
        CicsTaskId taskId,
        TransId transactionId,
        String owner,
        Instant startedAt,
        OptionalInt taskNumber,
        Optional<ZoneId> hostZone,
        Optional<BmsTerminalInput> terminalInput,
        Optional<BmsScreenSnapshot> screen,
        Optional<String> terminalId,
        Optional<String> userId,
        Optional<CicsStartData> start) {

    /** EIBTASKNの桁数 (PL4に入る7桁)。 */
    public static final int MAX_TASK_NUMBER = 9_999_999;
    /** 端末の名前は1〜4文字の英大文字・数字・国別文字とする。 */
    private static final Pattern TERMINAL_ID = Pattern.compile("[A-Z0-9@#$]{1,4}");
    /** CICSのuser IDは1〜8文字の英大文字・数字・国別文字とする。 */
    private static final Pattern USER_ID = Pattern.compile("[A-Z0-9@#$]{1,8}");

    public CicsTaskContext {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(transactionId, "transactionId");
        owner = requireText(owner, "owner");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(taskNumber, "taskNumber");
        Objects.requireNonNull(hostZone, "hostZone");
        Objects.requireNonNull(terminalInput, "terminalInput");
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(start, "start");
        if (taskNumber.isPresent()
                && (taskNumber.getAsInt() < 1 || taskNumber.getAsInt() > MAX_TASK_NUMBER)) {
            throw new IllegalArgumentException(
                    "task number must be 1 to " + MAX_TASK_NUMBER + ": " + taskNumber.getAsInt());
        }
        terminalId.ifPresent(value -> {
            if (!TERMINAL_ID.matcher(value).matches()) {
                throw new IllegalArgumentException("terminal ID has an unsupported format: " + value);
            }
        });
        userId.ifPresent(value -> {
            if (!USER_ID.matcher(value).matches()) {
                throw new IllegalArgumentException("user ID has an unsupported format: " + value);
            }
        });
    }

    /** STARTで起きたのでない文脈。 */
    public CicsTaskContext(CicsTaskId taskId, TransId transactionId, String owner, Instant startedAt,
                           OptionalInt taskNumber, Optional<ZoneId> hostZone,
                           Optional<BmsTerminalInput> terminalInput, Optional<BmsScreenSnapshot> screen,
                           Optional<String> terminalId, Optional<String> userId) {
        this(taskId, transactionId, owner, startedAt, taskNumber, hostZone, terminalInput, screen, terminalId,
                userId, Optional.empty());
    }

    /** 端末を持たない文脈。 */
    public CicsTaskContext(CicsTaskId taskId, TransId transactionId, String owner, Instant startedAt,
                           OptionalInt taskNumber, Optional<ZoneId> hostZone) {
        this(taskId, transactionId, owner, startedAt, taskNumber, hostZone,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** task番号と地方時を持たない文脈。EIBTASKN / EIBDATE / EIBTIMEは設定しない。 */
    public CicsTaskContext(CicsTaskId taskId, TransId transactionId, String owner, Instant startedAt) {
        this(taskId, transactionId, owner, startedAt, OptionalInt.empty(), Optional.empty());
    }

    /** 端末入力と直前の画面を持たせた文脈。 */
    public CicsTaskContext withTerminal(
            Optional<BmsTerminalInput> input, Optional<BmsScreenSnapshot> previousScreen) {
        return new CicsTaskContext(taskId, transactionId, owner, startedAt, taskNumber, hostZone,
                input, previousScreen, terminalId, userId, start);
    }

    /** 端末の名前を持たせた文脈。 */
    public CicsTaskContext withTerminalId(Optional<String> value) {
        return new CicsTaskContext(taskId, transactionId, owner, startedAt, taskNumber, hostZone,
                terminalInput, screen, value, userId, start);
    }

    /** CICSのuser IDを持たせた文脈。 */
    public CicsTaskContext withUserId(Optional<String> value) {
        return new CicsTaskContext(taskId, transactionId, owner, startedAt, taskNumber, hostZone,
                terminalInput, screen, terminalId, value, start);
    }

    /** taskを起こしたSTARTのデータを持たせた文脈。 */
    public CicsTaskContext withStart(Optional<CicsStartData> value) {
        return new CicsTaskContext(taskId, transactionId, owner, startedAt, taskNumber, hostZone,
                terminalInput, screen, terminalId, userId, value);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        value = value.strip();
        if (value.isEmpty() || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be non-empty and contain no controls");
        }
        return value;
    }
}
