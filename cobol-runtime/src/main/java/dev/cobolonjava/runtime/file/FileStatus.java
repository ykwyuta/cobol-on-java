package dev.cobolonjava.runtime.file;

/**
 * ファイル状態コード (要件 FR-103)。
 *
 * <p>入出力文のたびに 2 バイトのコードが立つ。<b>数値ではなく 2 文字</b>である。
 * 拡張コードに数字でないものがあるためであり、{@code 9x} の形も取りうる。
 */
public final class FileStatus {

    private FileStatus() {
    }

    /** 成功。 */
    public static final String OK = "00";
    /** 省略可能なファイルがなかったので作った。 */
    public static final String OPTIONAL_CREATED = "05";
    /** レコード長が記述と合わない。 */
    public static final String LENGTH_MISMATCH = "04";
    /** ファイルの終わり。 */
    public static final String AT_END = "10";
    /** 開こうとしたファイルがない。 */
    public static final String NOT_FOUND = "35";
    /** 開き方が編成に合わない。 */
    public static final String OPEN_CONFLICT = "37";
    /** すでに開いている。 */
    public static final String ALREADY_OPEN = "41";
    /** 開いていない。 */
    public static final String NOT_OPEN = "42";
    /** 読んでいないのに書き換えようとした。 */
    public static final String NO_CURRENT_RECORD = "43";
    /** 書き換えようとしたレコードの長さが読んだものと違う。 */
    public static final String REWRITE_LENGTH = "44";
    /** 読める位置にない。 */
    public static final String NOT_READABLE = "46";
    /** 読み取りが許されていない開き方である。 */
    public static final String READ_NOT_ALLOWED = "47";
    /** 書き込みが許されていない開き方である。 */
    public static final String WRITE_NOT_ALLOWED = "48";
    /** 書き換えが許されていない開き方である。{@code REWRITE} は {@code I-O} だけである。 */
    public static final String REWRITE_NOT_ALLOWED = "49";
    /** 入出力の誤り。 */
    public static final String IO_ERROR = "30";

    /** 成功したかどうか。先頭が {@code 0} なら成功か軽微な注意である。 */
    public static boolean succeeded(String status) {
        return status.charAt(0) == '0';
    }
}
