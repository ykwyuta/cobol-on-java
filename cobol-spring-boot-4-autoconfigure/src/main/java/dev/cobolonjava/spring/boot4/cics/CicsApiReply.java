package dev.cobolonjava.spring.boot4.cics;

import java.util.List;
import java.util.Map;

/**
 * JSON の入口が返す task の結果 (設計 77 §4.2、暫定判断 P-135)。
 *
 * @param commarea     task が返した COMMAREA (base64)
 * @param conversation 会話が続くなら、次の要求で送る会話の ID・版・次の TRANSID。終わったら null
 * @param screen       task が端末へ送った画面。送っていなければ null
 */
public record CicsApiReply(
        String taskId,
        String transactionId,
        String commarea,
        Map<String, String> containers,
        Conversation conversation,
        Screen screen) {

    public record Conversation(String id, long version, String nextTransaction) {
    }

    /**
     * 画面。{@code type} は {@code map} か {@code text}。
     *
     * @param cursorOffset map の画面の cursor 位置。決まっていなければ -1
     */
    public record Screen(
            String type,
            String mapset,
            String map,
            Integer rows,
            Integer columns,
            Integer cursorOffset,
            boolean alarm,
            boolean keyboardRestored,
            String text,
            List<Field> fields) {
    }

    /**
     * map の field 1 回分。
     *
     * @param row    属性 byte の行 (1 起点)
     * @param column 属性 byte の桁 (1 起点)
     * @param data   field の文字。DRK の field では null で、値を返さない
     */
    public record Field(
            String name,
            int occurrence,
            int row,
            int column,
            int length,
            List<String> attributes,
            String color,
            String highlight,
            String data,
            boolean modified) {
    }
}
