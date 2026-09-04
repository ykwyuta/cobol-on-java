package dev.cobolonjava.runtime.file;

import java.io.IOException;
import java.io.UncheckedIOException;
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
public final class SequentialDataSet {

    private final Path path;
    private final DataSetAttributes attributes;

    private OpenMode mode;
    private List<byte[]> records;
    private int position;
    private boolean atEnd;

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

    /** 開いているかどうか。 */
    public boolean isOpen() {
        return mode != null;
    }

    /**
     * 開く (要件 FR-102)。
     *
     * @return ファイル状態コード
     */
    public String open(OpenMode requested) {
        if (mode != null) {
            return FileStatus.ALREADY_OPEN;
        }
        if (requested == OpenMode.INPUT && !Files.isReadable(path)) {
            return FileStatus.NOT_FOUND;
        }
        try {
            records = requested == OpenMode.OUTPUT || !Files.isReadable(path)
                    ? new ArrayList<>()
                    : split(Files.readAllBytes(path));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
        mode = requested;
        // EXTEND は末尾から書き足す。ほかは先頭から
        position = requested == OpenMode.EXTEND ? records.size() : 0;
        atEnd = false;
        return FileStatus.OK;
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
        if (position >= records.size()) {
            atEnd = true;
            return FileStatus.AT_END;
        }
        byte[] record = records.get(position++);
        int length = Math.min(record.length, into.length);
        System.arraycopy(record, 0, into, 0, length);
        Arrays.fill(into, length, into.length, attributes.codePage().space());
        return record.length == into.length ? FileStatus.OK : FileStatus.LENGTH_MISMATCH;
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
        records.add(from.clone());
        position = records.size();
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
                throw new UncheckedIOException("cannot write " + path, e);
            }
        }
        mode = null;
        records = null;
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

    private List<byte[]> splitFixed(byte[] bytes) {
        List<byte[]> out = new ArrayList<>();
        int length = attributes.recordLength();
        for (int at = 0; at < bytes.length; at += length) {
            out.add(Arrays.copyOfRange(bytes, at, Math.min(at + length, bytes.length)));
        }
        return out;
    }

    /** 可変長は 4 バイトの RDW が先頭に付く。最初の 2 バイトが RDW を含む長さである。 */
    private static List<byte[]> splitVariable(byte[] bytes) {
        List<byte[]> out = new ArrayList<>();
        int at = 0;
        while (at + 4 <= bytes.length) {
            int length = ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
            if (length < 4 || at + length > bytes.length) {
                break;
            }
            out.add(Arrays.copyOfRange(bytes, at + 4, at + length));
            at += length;
        }
        return out;
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
