package dev.cobolonjava.runtime.file;

/**
 * レコード様式 (要件 FR-110)。
 *
 * <p>データセットは<b>バイト列でしかない</b>。どこでレコードが切れるかは、バイト列の中には
 * 書かれていない。ホストではデータセットのラベルが持っている情報であり、こちらでは
 * サイドカーに持つ。
 */
public enum RecordFormat {

    /** 固定長 (F/FB)。レコード長ずつ切る。 */
    FIXED,

    /** 可変長 (V/VB)。4 バイトの RDW が先頭に付き、その最初の 2 バイトが長さである。 */
    VARIABLE,

    /** 行順。改行までが 1 レコードであり、書くときは末尾の空白を落とす。 */
    LINE;

    /** サイドカーの綴りから読む。 */
    public static RecordFormat of(String text) {
        return switch (text.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "F", "FB", "FIXED" -> FIXED;
            case "V", "VB", "VARIABLE" -> VARIABLE;
            case "LINE", "LS" -> LINE;
            default -> throw new IllegalArgumentException("unknown record format: " + text);
        };
    }
}
