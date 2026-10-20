package dev.cobolonjava.mq;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.util.Arrays;

/**
 * 開始待ち行列から読んだ起動電文 (MQTM 1 版) 1 つ。{@link #bytes} はプログラムが {@code RETRIEVE INTO(MQTM)} で
 * 受ける形 (z/OS の形、684 byte) のままである。
 *
 * @param transactionId 起こす transaction。ApplId の先頭の語の 4 文字まで (CICS の trigger monitor の読み。
 *                      Zeus d2f1cfc の {@code crates/zeus-mq/src/monitor.rs} の {@code transaction} と同じ)
 * @param queueName     起動のもとになった待ち行列 (QName)
 * @param bytes         MQTM の byte 列
 */
public record TriggerMessage(String transactionId, String queueName, byte[] bytes) {

    /** MQTM 1 版の長さ。 */
    public static final int LENGTH = 684;
    static final int Q_NAME = 8;
    static final int APPL_ID = 172;

    public TriggerMessage {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * MQTM を読む。目印 'TM  ' と長さが合わなければ断る。開始待ち行列に起動電文でないものが置かれたら、
     * それらしい transaction を起こすより止まるほうがよい。
     */
    static TriggerMessage parse(CodePage codePage, byte[] mqtm) {
        if (mqtm.length < LENGTH || !codePage.decode(Arrays.copyOf(mqtm, 4)).equals("TM  ")) {
            throw new IllegalArgumentException("not a trigger message (MQTM)");
        }
        String applId = codePage.decode(Arrays.copyOfRange(mqtm, APPL_ID, APPL_ID + 256)).strip();
        String first = applId.isEmpty() ? "" : applId.split("\\s+")[0];
        String transaction = first.length() > 4 ? first.substring(0, 4) : first;
        String queue = Structure.stripTrailingBlanks(
                codePage.decode(Arrays.copyOfRange(mqtm, Q_NAME, Q_NAME + Structure.NAME_LENGTH)));
        return new TriggerMessage(transaction, queue, Arrays.copyOf(mqtm, LENGTH));
    }
}
