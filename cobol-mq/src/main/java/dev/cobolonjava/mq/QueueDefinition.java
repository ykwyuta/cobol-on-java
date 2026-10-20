package dev.cobolonjava.mq;

import java.util.Objects;

/**
 * 局所待ち行列の定義 (MQSC の {@code DEFINE QLOCAL} にあたる)。
 *
 * <p>既定値は z/OS の待ち行列管理者の既定 (SYSTEM.DEFAULT.LOCAL.QUEUE) の公開文書の値に寄せた: MAXDEPTH 999999999、
 * MAXMSGL 4194304、DEFPRTY 0、DEFPSIST NO。実機の既定値の定義を変えている環境では違う (暫定判断 P-251)。
 *
 * @param name               待ち行列の名前 (48 文字まで)
 * @param description        記述 (64 文字まで)
 * @param maxDepth           置ける電文の数
 * @param maxMessageLength   電文の長さの上限
 * @param defaultPriority    MQPRI_PRIORITY_AS_Q_DEF で置かれた電文の優先度
 * @param defaultPersistence MQPER_PERSISTENCE_AS_Q_DEF で置かれた電文の永続性
 * @param getInhibited       GET を禁じる
 * @param putInhibited       PUT を禁じる
 * @param trigger            起動の定義 (TRIGGER、TRIGTYPE、INITQ、PROCESS など)。起動しない待ち行列なら null
 */
public record QueueDefinition(String name, String description, int maxDepth, int maxMessageLength,
                              int defaultPriority, int defaultPersistence,
                              boolean getInhibited, boolean putInhibited, TriggerDefinition trigger) {

    public QueueDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        if (name.isBlank() || name.length() > Structure.NAME_LENGTH) {
            throw new IllegalArgumentException("a queue name is 1 to 48 characters: " + name);
        }
        if (description.length() > 64) {
            throw new IllegalArgumentException("a queue description is up to 64 characters");
        }
        if (defaultPriority < 0 || defaultPriority > 9) {
            throw new IllegalArgumentException("DEFPRTY is 0 to 9");
        }
    }

    /** 既定値の局所待ち行列。 */
    public static QueueDefinition local(String name) {
        return new QueueDefinition(name, "", 999_999_999, 4_194_304, 0, MqConstants.MQPER_NOT_PERSISTENT, false, false,
                null);
    }

    public QueueDefinition withMaxDepth(int value) {
        return new QueueDefinition(name, description, value, maxMessageLength, defaultPriority, defaultPersistence,
                getInhibited, putInhibited, trigger);
    }

    public QueueDefinition withMaxMessageLength(int value) {
        return new QueueDefinition(name, description, maxDepth, value, defaultPriority, defaultPersistence,
                getInhibited, putInhibited, trigger);
    }

    public QueueDefinition withDescription(String value) {
        return new QueueDefinition(name, value, maxDepth, maxMessageLength, defaultPriority, defaultPersistence,
                getInhibited, putInhibited, trigger);
    }

    public QueueDefinition withDefaultPersistence(int value) {
        return new QueueDefinition(name, description, maxDepth, maxMessageLength, defaultPriority, value,
                getInhibited, putInhibited, trigger);
    }

    /** 起動の定義を付ける (MQSC の {@code TRIGGER TRIGTYPE(...) INITQ(...) PROCESS(...)})。 */
    public QueueDefinition withTrigger(TriggerDefinition value) {
        return new QueueDefinition(name, description, maxDepth, maxMessageLength, defaultPriority, defaultPersistence,
                getInhibited, putInhibited, value);
    }

    public QueueDefinition withInhibited(boolean get, boolean put) {
        return new QueueDefinition(name, description, maxDepth, maxMessageLength, defaultPriority, defaultPersistence,
                get, put, trigger);
    }
}
