package dev.cobolonjava.runtime.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 順編成のデータセット (要件 FR-100, FR-102, FR-110)。
 *
 * <p>中身は<b>生バイト</b>である。どこでレコードが切れるかは
 * {@link DataSetAttributes} が決める。バイト列の中には書かれていない。
 *
 * <h2>状態を持つのは開き方と位置だけ</h2>
 * <p>入出力文の意味論は「いま開いているか」「どこまで読んだか」で決まる。
 * 開いていないのに読めば {@code 42}、終わりまで読んだあとにまた読めば {@code 46} である。
 * <b>誤りの種類が状態から決まる</b>ため、状態はここが持つ。
 *
 * <h2>いちどに読み込む</h2>
 * <p>いまの段では、開いたときにファイル全体を読み、閉じるときに書き出す。順に読む使い方では
 * 差が出ず、レコードの切り出しに集中できる。大きなデータセットを流す形は、
 * 相対編成と索引編成を実装する段で改める (暫定判断 P-038)。
 */
public final class SequentialDataSet implements DataSet {

    private final Path path;
    /** 開くたびにサイドカーから読み直す。バイト列を切り分けた属性が正だからである。 */
    private DataSetAttributes attributes;

    private OpenMode mode;
    private List<byte[]> records;
    private int position;
    private boolean atEnd;
    /**
     * 直前に読んだレコードの番号。{@code REWRITE} が書き換える相手である。
     *
     * <p>{@code -1} は「いま指しているレコードがない」ことを表す。読む前、書いたあと、
     * 書き換えたあとがそれにあたる。
     */
    private int current = -1;
    private int lastLength;
    /**
     * 形が壊れている位置。{@code -1} は壊れていないことを表す。
     *
     * <p>開いたときにバイト列を切り分けると、途中で<b>それ以上切れない</b>ことがある。
     * 固定長で長さが割り切れない、可変長で {@code RDW} がつながらない、という形である。
     * 壊れた場所まで読み進めたときに初めて誤りにするのは、そこまでのレコードは
     * 実際に読めているからである。ホストも同じで、装置の誤りは読んだ時点で立つ。
     */
    private int damagedAt = -1;
    /** ジョブが割り当てた領域の大きさ (バイト)。{@code 0} は限りがないことを表す。 */
    private long limit;

    public SequentialDataSet(Path path, DataSetAttributes attributes) {
        this.path = path;
        this.attributes = attributes;
    }

    /** 属性をサイドカーから読んで開く用意をする。 */
    public static SequentialDataSet at(Path path) {
        return new SequentialDataSet(path, DataSetAttributes.read(path));
    }

    /**
     * 属性をサイドカーから読んで開く用意をする。サイドカーがなければ宣言に拠る。
     *
     * @param declared プログラムが {@code SELECT} と {@code FD} に書いた様式
     */
    public static SequentialDataSet at(Path path, DataSetAttributes declared) {
        return new SequentialDataSet(path, DataSetAttributes.read(path, declared));
    }

    public DataSetAttributes attributes() {
        return attributes;
    }

    /**
     * 書ける大きさに限りを設ける (要件 FR-141)。
     *
     * <p>JCL の {@code SPACE=} である。ホストでは<b>あらかじめ場所を取ってから書く</b>ので、
     * 取った分を使い切れば書けなくなる。二次割当があれば伸ばせるが、無ければそこで終わる。
     * 限りを設けないと、実機では止まるジョブがここでは通ってしまう。
     *
     * @param bytes 書ける大きさ。{@code 0} なら限りなし
     */
    public void limit(long bytes) {
        this.limit = bytes;
    }

    /** 開いているかどうか。 */
    public boolean isOpen() {
        return mode != null;
    }

    /** いまの開き方。開いていなければ {@code null}。 */
    @Override
    public OpenMode mode() {
        return mode;
    }

    /**
     * 開く (要件 FR-102)。
     *
     * @return ファイル状態コード
     */
    public String open(OpenMode requested) {
        return open(requested, false);
    }

    /**
     * 開く (要件 FR-102, FR-103)。
     *
     * <p>{@code OUTPUT} 以外は<b>ファイルがあることを前提とする</b>。ないなら {@code 35} で
     * ある。{@code SELECT OPTIONAL} と書いてあるときだけ、空のファイルとして作って
     * {@code 05} を返す。黙って空のファイルを作ると、入力を取り違えたジョブが
     * 「0 件処理した」と言って正常終了してしまう。
     *
     * @param optional {@code SELECT OPTIONAL} と書かれているか
     * @return ファイル状態コード
     */
    public String open(OpenMode requested, boolean optional) {
        if (mode != null) {
            return FileStatus.ALREADY_OPEN;
        }
        boolean missing = !Files.isReadable(path);
        if (missing && requested != OpenMode.OUTPUT && !optional) {
            return FileStatus.NOT_FOUND;
        }
        damagedAt = -1;
        try {
            records = requested == OpenMode.OUTPUT || missing
                    ? new ArrayList<>()
                    : split(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new DataSetIoException("read", path, e);
        }
        mode = requested;
        // EXTEND は末尾から書き足す。ほかは先頭から
        position = requested == OpenMode.EXTEND ? records.size() : 0;
        atEnd = false;
        current = -1;
        lastLength = 0;
        return missing && requested != OpenMode.OUTPUT
                ? FileStatus.OPTIONAL_CREATED
                : FileStatus.OK;
    }

    /**
     * 1 レコード読む (要件 FR-102)。
     *
     * @param into 読み込む先。足りなければ空白で埋め、あふれれば切り捨てる
     * @return ファイル状態コード
     */
    public String read(byte[] into) {
        if (mode == null) {
            return FileStatus.NOT_OPEN;
        }
        if (!mode.canRead()) {
            return FileStatus.READ_NOT_ALLOWED;
        }
        if (atEnd) {
            // 終わりまで読んだあとにまた読むのは、位置が定まっていない
            return FileStatus.NOT_READABLE;
        }
        if (position == damagedAt) {
            // 切り分けが途中で行き詰まった場所である。ここから先は読めない
            return FileStatus.IO_ERROR;
        }
        if (position >= records.size()) {
            atEnd = true;
            return FileStatus.AT_END;
        }
        byte[] record = records.get(position++);
        current = position - 1;
        int length = Math.min(record.length, into.length);
        System.arraycopy(record, 0, into, 0, length);
        lastLength = length;
        if (attributes.format() == RecordFormat.VARIABLE) {
            // 可変長では受取領域の余りに触らない。規格上そこの中身は決まっていない
            return record.length > into.length ? FileStatus.LENGTH_MISMATCH : FileStatus.OK;
        }
        Arrays.fill(into, length, into.length, attributes.codePage().space());
        return record.length == into.length ? FileStatus.OK : FileStatus.LENGTH_MISMATCH;
    }

    /**
     * 直前に読み書きしたレコードの長さ。
     *
     * <p>可変長では<b>長さそのものがデータである</b>。{@code DEPENDING ON} の項目へ返すために
     * 要る (要件 FR-106)。
     */
    public int lastLength() {
        return lastLength;
    }

    /**
     * 1 レコード書く (要件 FR-102)。
     *
     * @return ファイル状態コード
     */
    public String write(byte[] from) {
        if (mode == null) {
            return FileStatus.NOT_OPEN;
        }
        if (!mode.canWrite()) {
            return FileStatus.WRITE_NOT_ALLOWED;
        }
        if (limit > 0 && written() + sizeOf(from) > limit) {
            return FileStatus.NO_SPACE;
        }
        records.add(from.clone());
        position = records.size();
        current = -1;
        lastLength = from.length;
        return FileStatus.OK;
    }

    /**
     * 直前に読んだレコードを書き換える (要件 FR-102)。
     *
     * <p>順編成の {@code REWRITE} は<b>読んだ直後にしか書けない</b>。書き換える相手は
     * 「いま指しているレコード」であり、読まなければ何も指していないからである。
     * 開き方も {@code I-O} に限る。読みながら書き戻す使い方だけが意味を持つ。
     *
     * @return ファイル状態コード
     */
    public String rewrite(byte[] from) {
        if (mode == null) {
            return FileStatus.NOT_OPEN;
        }
        if (mode != OpenMode.IO) {
            return FileStatus.REWRITE_NOT_ALLOWED;
        }
        if (current < 0) {
            return FileStatus.NO_CURRENT_RECORD;
        }
        if (attributes.format() == RecordFormat.FIXED
                && from.length != records.get(current).length) {
            // 固定長では長さを変えられない。あとのレコードの位置がずれてしまう
            return FileStatus.REWRITE_LENGTH;
        }
        records.set(current, from.clone());
        lastLength = from.length;
        current = -1;
        return FileStatus.OK;
    }

    /**
     * 閉じる (要件 FR-102)。書いていれば、ここでファイルへ流し込む。
     *
     * @return ファイル状態コード
     */
    public String close() {
        if (mode == null) {
            return FileStatus.NOT_OPEN;
        }
        if (mode.canWrite()) {
            try {
                Files.write(path, join(records), StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                attributes.write(path);
            } catch (IOException e) {
                throw new DataSetIoException("write", path, e);
            }
        }
        mode = null;
        records = null;
        current = -1;
        return FileStatus.OK;
    }

    // ---- レコードの切り出し ----

    /** バイト列をレコードへ切る。切り方は様式で決まる。 */
    private List<byte[]> split(byte[] bytes) {
        return switch (attributes.format()) {
            case FIXED -> splitFixed(bytes);
            case VARIABLE -> splitVariable(bytes);
            case LINE -> splitLines(bytes);
        };
    }

    /**
     * 固定長は<b>長さで割り切れなければならない</b>。
     *
     * <p>半端が残るのは、書いている途中で落ちたか、レコード長の違うデータセットを
     * 取り違えたということである。半端をそのまま短いレコードとして渡すと、
     * <b>読めていないデータで処理が進む</b>。切れるところまでを読めるものとし、
     * その先を壊れた場所として覚える。
     */
    private List<byte[]> splitFixed(byte[] bytes) {
        List<byte[]> out = new ArrayList<>();
        int length = attributes.recordLength();
        int at = 0;
        while (at + length <= bytes.length) {
            out.add(Arrays.copyOfRange(bytes, at, at + length));
            at += length;
        }
        if (at < bytes.length) {
            damagedAt = out.size();
        }
        return out;
    }

    /**
     * 可変長は 4 バイトの RDW が先頭に付く。最初の 2 バイトが RDW を含む長さである。
     *
     * <p>長さが 4 に満たない、残りより長い、という RDW はつながらない。半端なバイトが
     * 残るのも同じで、いずれもそこから先は切り分けられない。
     */
    private List<byte[]> splitVariable(byte[] bytes) {
        List<byte[]> out = new ArrayList<>();
        int at = 0;
        while (at < bytes.length) {
            if (at + 4 > bytes.length) {
                damagedAt = out.size();
                break;
            }
            int length = ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
            if (length < 4 || at + length > bytes.length) {
                damagedAt = out.size();
                break;
            }
            out.add(Arrays.copyOfRange(bytes, at + 4, at + length));
            at += length;
        }
        return out;
    }

    // ---- 領域の勘定 ----

    /** いま書き出したとしたら何バイトになるか。 */
    private long written() {
        long total = 0;
        for (byte[] record : records) {
            total += sizeOf(record);
        }
        return total;
    }

    /**
     * レコード 1 つが占める大きさ。
     *
     * <p>様式によって、レコードの中身のほかに付くものが違う。可変長なら {@code RDW} の
     * 4 バイト、行順なら区切りの 1 バイトである。固定長は中身の長さによらず
     * <b>つねにレコード長</b>を占める。
     */
    private long sizeOf(byte[] record) {
        return switch (attributes.format()) {
            case FIXED -> attributes.recordLength();
            case VARIABLE -> record.length + 4L;
            case LINE -> record.length + 1L;
        };
    }

    /** 行順は改行までが 1 レコードである。改行はコードページのものを使う。 */
    private List<byte[]> splitLines(byte[] bytes) {
        byte newline = newline();
        List<byte[]> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == newline) {
                out.add(Arrays.copyOfRange(bytes, start, i));
                start = i + 1;
            }
        }
        if (start < bytes.length) {
            // 最後の改行がなければ、残りも 1 レコードである
            out.add(Arrays.copyOfRange(bytes, start, bytes.length));
        }
        return out;
    }

    /** レコードをバイト列へ戻す。 */
    private byte[] join(List<byte[]> all) {
        return switch (attributes.format()) {
            case FIXED -> joinFixed(all);
            case VARIABLE -> joinVariable(all);
            case LINE -> joinLines(all);
        };
    }

    private byte[] joinFixed(List<byte[]> all) {
        int length = attributes.recordLength();
        byte[] out = new byte[all.size() * length];
        Arrays.fill(out, attributes.codePage().space());
        for (int i = 0; i < all.size(); i++) {
            byte[] record = all.get(i);
            System.arraycopy(record, 0, out, i * length, Math.min(record.length, length));
        }
        return out;
    }

    private static byte[] joinVariable(List<byte[]> all) {
        int total = 0;
        for (byte[] record : all) {
            total += record.length + 4;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] record : all) {
            int length = record.length + 4;
            out[at] = (byte) (length >> 8);
            out[at + 1] = (byte) length;
            System.arraycopy(record, 0, out, at + 4, record.length);
            at += length;
        }
        return out;
    }

    /** 行順で書くときは<b>末尾の空白を落とす</b>。 */
    private byte[] joinLines(List<byte[]> all) {
        byte newline = newline();
        byte space = attributes.codePage().space();
        int total = 0;
        List<byte[]> trimmed = new ArrayList<>();
        for (byte[] record : all) {
            int end = record.length;
            while (end > 0 && record[end - 1] == space) {
                end--;
            }
            byte[] one = Arrays.copyOf(record, end);
            trimmed.add(one);
            total += end + 1;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] record : trimmed) {
            System.arraycopy(record, 0, out, at, record.length);
            at += record.length;
            out[at++] = newline;
        }
        return out;
    }

    /**
     * 区切りの改行。
     *
     * <p>コードページのものを使う。EBCDIC なら {@code 0x15} であり、{@code 0x0A} ではない。
     * 生バイトで持つと決めた以上、区切りもそのコードページのものになる。
     */
    private byte newline() {
        return attributes.codePage().encode("\n")[0];
    }
}
