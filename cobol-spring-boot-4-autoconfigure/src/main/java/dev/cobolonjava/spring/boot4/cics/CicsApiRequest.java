package dev.cobolonjava.spring.boot4.cics;

import java.util.List;
import java.util.Map;

/**
 * JSON の入口が受ける要求 (設計 77 §4.2、暫定判断 P-135)。
 *
 * <p>byte 列は base64 の文字列で受け、中立層へ渡す前に byte 列へ確定する。Jackson の型や注釈は持ち込まない。
 *
 * @param commarea       新しい task へ渡す COMMAREA (base64)。会話を続ける要求では書かない
 * @param containers     新しい task へ渡す container (名前と base64)。会話を続ける要求では書かない
 * @param conversation   続ける会話。書かなければ新しい task
 * @param idempotencyKey 要求の相関 key。書かなければ server が作る
 * @param terminal       端末入力 (AID、cursor、変更した field)。書かなければ端末入力なし
 */
public record CicsApiRequest(
        String commarea,
        Map<String, String> containers,
        Conversation conversation,
        String idempotencyKey,
        Terminal terminal) {

    /** 続ける会話の ID と、client が最後に受けた版。 */
    public record Conversation(String id, Long version) {
    }

    /** 端末入力。 */
    public record Terminal(String aid, Integer cursor, List<Field> fields) {
    }

    /** 変更した field 1 回分。 */
    public record Field(String name, Integer occurrence, String value) {
    }
}
