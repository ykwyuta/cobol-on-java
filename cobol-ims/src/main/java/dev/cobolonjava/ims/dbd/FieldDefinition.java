package dev.cobolonjava.ims.dbd;

/**
 * DBD の {@code FIELD} 文 1 つ。
 *
 * @param name     フィールド名 (大文字)
 * @param start    セグメントの中の開始位置 (1 起点。DBD に書いた {@code START=} のまま)
 * @param bytes    長さ
 * @param type     {@code TYPE=}
 * @param sequence 順序フィールド ({@code NAME=(名前,SEQ,...)}) か
 * @param unique   順序フィールドのキーが重ならないか ({@code U})。重なってよければ ({@code M}) 偽
 */
public record FieldDefinition(String name, int start, int bytes, FieldType type, boolean sequence,
                              boolean unique) {

    /** セグメントの先頭からの位置 (0 起点)。 */
    public int offset() {
        return start - 1;
    }
}
