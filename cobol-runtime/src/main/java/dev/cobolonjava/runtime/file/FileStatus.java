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
    /**
     * 巻を指す {@code CLOSE} を、巻を持たない媒体のファイルに対して実行した。
     *
     * <p>{@code CLOSE ... REEL} / {@code UNIT} / {@code NO REWIND} は磁気テープの
     * 巻送りを指す。ディスク上のデータセットには巻がないので、規格は<b>成功だが
     * 巻の操作は行われなかった</b>ことをこのコードで伝えるよう定めている
     * (85 規格 VII-38, 4.2.4(3)F)。先頭が {@code 0} なので誤りではない。
     */
    public static final String NON_REEL = "07";
    /** ファイルの終わり。 */
    public static final String AT_END = "10";
    /**
     * 読めたレコードの相対レコード番号が、{@code RELATIVE KEY} の項目に収まらない。
     *
     * <p>順次読みでは読んでみるまで番号が決まらない。決まった番号の桁が項目より多ければ、
     * 番号を返せないので<b>読めなかったことにする</b> (85 規格 VII-3 1.3.4 2B)。
     * {@code PIC 99} の鍵で 100 本目を読んだときがこれである (RL117A REL-TEST-3)。
     */
    public static final String KEY_TOO_LARGE = "14";
    /** 鍵の順序が昇順でない ({@code ACCESS SEQUENTIAL} の {@code WRITE})。 */
    public static final String KEY_SEQUENCE = "21";
    /** 同じ鍵のレコードがすでにある。 */
    public static final String DUPLICATE_KEY = "22";
    /** その鍵のレコードがない。 */
    public static final String NO_RECORD = "23";
    /** 書ける範囲の外である (相対レコード番号が大きすぎる、領域が足りない)。 */
    public static final String BOUNDARY = "24";
    /** 開こうとしたファイルがない。 */
    public static final String NOT_FOUND = "35";
    /** 開き方が編成に合わない。 */
    public static final String OPEN_CONFLICT = "37";
    /** {@code CLOSE ... WITH LOCK} で閉じたファイルを、もう一度開こうとした。 */
    public static final String CLOSED_WITH_LOCK = "38";
    /** すでに開いている。 */
    public static final String ALREADY_OPEN = "41";
    /** 開いていない。 */
    public static final String NOT_OPEN = "42";
    /** 読んでいないのに書き換えようとした。 */
    public static final String NO_CURRENT_RECORD = "43";
    /**
     * レコードの長さが合わない。
     *
     * <p>2 つの場合がある。{@code REWRITE} で読んだものと長さが違うときと、
     * {@code RECORD IS VARYING} のファイルへ宣言の範囲の外の長さを書こうとしたときで
     * ある。どちらも<b>書かずに</b>このコードを立てる。
     */
    public static final String RECORD_LENGTH_RANGE = "44";

    /** 書き換えようとしたレコードの長さが読んだものと違う。 */
    public static final String REWRITE_LENGTH = RECORD_LENGTH_RANGE;
    /** 読める位置にない。 */
    public static final String NOT_READABLE = "46";
    /** 読み取りが許されていない開き方である。 */
    public static final String READ_NOT_ALLOWED = "47";
    /** 書き込みが許されていない開き方である。 */
    public static final String WRITE_NOT_ALLOWED = "48";
    /** 書き換えが許されていない開き方である。{@code REWRITE} は {@code I-O} だけである。 */
    public static final String REWRITE_NOT_ALLOWED = "49";
    /**
     * 回復できない入出力の誤り。
     *
     * <p>装置の誤り、およびデータセットの形が記述と合っていないことである。固定長なのに
     * 長さがレコード長で割り切れない、可変長なのに {@code RDW} がつながらない、といった
     * <b>そこで読むのをやめるほかない</b>状態がこれにあたる。
     */
    public static final String IO_ERROR = "30";

    /**
     * 順編成で書ける範囲を越えた。
     *
     * <p>ジョブが割り当てた領域を使い切ったということである。鍵で引く編成の {@code 24} に
     * あたるものが、順編成ではこれになる。
     */
    public static final String NO_SPACE = "34";

    /** 成功したかどうか。先頭が {@code 0} なら成功か軽微な注意である。 */
    public static boolean succeeded(String status) {
        return status.charAt(0) == '0';
    }

    /**
     * 鍵に関する誤りかどうか (要件 FR-103)。
     *
     * <p>先頭が {@code 2} のものが<b>無効鍵条件</b>である。{@code INVALID KEY} を書いて
     * あればそこへ分岐する。順編成の {@code AT END} にあたるものが、鍵で引く編成では
     * これになる。
     */
    public static boolean invalidKey(String status) {
        return status.charAt(0) == '2';
    }
}
