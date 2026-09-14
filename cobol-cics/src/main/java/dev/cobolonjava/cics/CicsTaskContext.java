package dev.cobolonjava.cics;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 一つの同期要求に固定される、framework非依存のtask文脈。
 *
 * @param taskNumber EIBTASKNへ出すtask番号。hostの領域ごとの連番に当たるものは
 *                   adapterだけが知っている。{@link #taskId()}から作らない
 * @param hostZone   EIBDATE / EIBTIMEを出す地方時。hostの領域が動いていた時間帯は
 *                   構成でしか分からないので、JVMの既定から推測しない
 */
public record CicsTaskContext(
        CicsTaskId taskId,
        TransId transactionId,
        String owner,
        Instant startedAt,
        OptionalInt taskNumber,
        Optional<ZoneId> hostZone) {

    /** EIBTASKNの桁数 (PL4に入る7桁)。 */
    public static final int MAX_TASK_NUMBER = 9_999_999;

    public CicsTaskContext {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(transactionId, "transactionId");
        owner = requireText(owner, "owner");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(taskNumber, "taskNumber");
        Objects.requireNonNull(hostZone, "hostZone");
        if (taskNumber.isPresent()
                && (taskNumber.getAsInt() < 1 || taskNumber.getAsInt() > MAX_TASK_NUMBER)) {
            throw new IllegalArgumentException(
                    "task number must be 1 to " + MAX_TASK_NUMBER + ": " + taskNumber.getAsInt());
        }
    }

    /** task番号と地方時を持たない文脈。EIBTASKN / EIBDATE / EIBTIMEは設定しない。 */
    public CicsTaskContext(CicsTaskId taskId, TransId transactionId, String owner, Instant startedAt) {
        this(taskId, transactionId, owner, startedAt, OptionalInt.empty(), Optional.empty());
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
