package dev.cobolonjava.mq;

import java.util.Objects;

/**
 * 待ち行列の起動の定義 (MQSC の {@code TRIGGER TRIGTYPE TRIGDPTH TRIGMPRI TRIGDATA INITQ PROCESS})。
 *
 * <p>起動の条件は IBM MQ の公開文書「Conditions for a trigger event」の読みによる (設計 88 §4.5、暫定判断 P-255)。
 *
 * @param type            FIRST、EVERY、DEPTH
 * @param depth           DEPTH のときの電文の数 (TRIGDPTH)
 * @param messagePriority この優先度以上の電文だけを数える (TRIGMPRI)
 * @param data            起動電文の TriggerData に入れる (TRIGDATA、64 文字まで)
 * @param initiationQueue 起動電文を置く開始待ち行列 (INITQ)
 * @param processName     起動電文の ApplId などを取る処理の定義 (PROCESS)
 */
public record TriggerDefinition(Type type, int depth, int messagePriority, String data, String initiationQueue,
                                String processName) {

    /** TRIGTYPE。 */
    public enum Type {
        /** 数えた電文が 0 から 1 になったとき。 */
        FIRST,
        /** 電文が届くたび。 */
        EVERY,
        /** 数えた電文が TRIGDPTH に達したとき。起動すると TRIGGER を切る。 */
        DEPTH
    }

    public TriggerDefinition {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(initiationQueue, "initiationQueue");
        Objects.requireNonNull(processName, "processName");
        if (data.length() > 64) {
            throw new IllegalArgumentException("TRIGDATA is up to 64 characters");
        }
        if (depth < 1) {
            throw new IllegalArgumentException("TRIGDPTH is 1 or more");
        }
        if (messagePriority < 0 || messagePriority > 9) {
            throw new IllegalArgumentException("TRIGMPRI is 0 to 9");
        }
    }

    /** {@code type} で、TRIGDPTH 1、TRIGMPRI 0、TRIGDATA 空白。 */
    public static TriggerDefinition of(Type type, String initiationQueue, String processName) {
        return new TriggerDefinition(type, 1, 0, "", initiationQueue, processName);
    }

    public TriggerDefinition withDepth(int value) {
        return new TriggerDefinition(type, value, messagePriority, data, initiationQueue, processName);
    }

    public TriggerDefinition withData(String value) {
        return new TriggerDefinition(type, depth, messagePriority, value, initiationQueue, processName);
    }
}
