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
 * 相対編成のデータセット (要件 FR-100, FR-101, FR-102)。
 *
 * <p>レコードを<b>番号で引く</b>。1 起点の相対レコード番号 (RRN) が住所であり、
 * 中身とは関わりがない。ホストの VSAM RRDS にあたる。
 *
 * <h2>スロットは詰めない</h2>
 * <p>番号が住所である以上、消しても<b>あとのレコードは動かない</b>。3 番を消しても 4 番は
 * 4 番のままである。したがって消したところは<b>空きスロット</b>として残る。
 *
 * <h2>空きスロットはバイト列から分からない</h2>
 * <p>消したスロットと空白だけのレコードは同じバイトになる。VSAM は制御情報として持っており、
 * レコードのバイト列の外にある。ここではサイドカーに持つ (暫定判断 P-039)。データ本体は
 * 移行したままの固定長スロットの並びである。
 */
public final class RelativeDataSet {

    private final Path path;
    private final DataSetAttributes attributes;

    private OpenMode mode;
    /** スロットの並び。{@code null} が空きスロットである。 */
    private List<byte[]> slots;
    /** 次に順次読む位置 (0 起点)。 */
    private int position;
    private boolean atEnd;
    /** 直前に読んだスロット (0 起点)。{@code -1} は指していない。 */
    private int current = -1;
    private int lastLength;

    public RelativeDataSet(Path path, DataSetAttributes attributes) {
        this.path = path;
        this.attributes = attributes;
    }

    /** 属性をサイドカーから読んで開く用意をする。サイドカーがなければ宣言に拠る。 */
    public static RelativeDataSet at(Path path, DataSetAttributes declared) {
        return new RelativeDataSet(path, DataSetAttributes.read(path, declared));
    }

    public DataSetAttributes attributes() {
        return attributes;
    }

    public boolean isOpen() {
        return mode != null;
    }

    /** 直前に読み書きしたレコードの長さ。 */
    public int lastLength() {
        return lastLength;
    }

    /**
     * 直前に読んだレコードの相対レコード番号 (1 起点)。
     *
     * <p>順次読みでは<b>読んでみるまで番号が決まらない</b>。空きスロットを飛ばすためである。
     * {@code RELATIVE KEY} の項目へ返す値がこれである。
     */
    public int currentNumber() {
        return current + 1;
    }

    // ---- 開く・閉じる ----

    /** 開く (要件 FR-102, FR-103)。 */
    public String open(OpenMode requested, boolean optional) {
        if (mode != null) {
            return FileStatus.ALREADY_OPEN;
        }
        boolean missing = !Files.isReadable(path);
        if (missing && requested != OpenMode.OUTPUT && !optional) {
            return FileStatus.NOT_FOUND;
        }
        slots = requested == OpenMode.OUTPUT || missing ? new ArrayList<>() : load();
        mode = requested;
        position = requested == OpenMode.EXTEND ? slots.size() : 0;
        atEnd = false;
        current = -1;
        lastLength = 0;
        return missing && requested != OpenMode.OUTPUT
                ? FileStatus.OPTIONAL_CREATED
                : FileStatus.OK;
    }

    /** 閉じる。書いていれば、ここでファイルとサイドカーへ流し込む。 */
    public String close() {
        if (mode == null) {
            return FileStatus.NOT_OPEN;
        }
        if (mode.canWrite()) {
            save();
        }
        mode = null;
        slots = null;
        current = -1;
        return FileStatus.OK;
    }

    private List<byte[]> load() {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
        int length = attributes.recordLength();
        List<byte[]> out = new ArrayList<>();
        for (int at = 0; at < bytes.length; at += length) {
            out.add(Arrays.copyOfRange(bytes, at, Math.min(at + length, bytes.length)));
        }
        for (int slot : attributes.emptySlots()) {
            if (slot >= 1 && slot <= out.size()) {
                out.set(slot - 1, null);
            }
        }
        return out;
    }

    private void save() {
        int length = attributes.recordLength();
        byte[] out = new byte[slots.size() * length];
        Arrays.fill(out, attributes.codePage().space());
        List<Integer> empty = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            byte[] record = slots.get(i);
            if (record == null) {
                // 空きスロットも場所は占める。番号が住所だからである
                empty.add(i + 1);
                continue;
            }
            System.arraycopy(record, 0, out, i * length, Math.min(record.length, length));
        }
        try {
            Files.write(path, out, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            attributes.withEmptySlots(empty).write(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + path, e);
        }
    }

    // ---- 順次 ----

    /**
     * 次のレコードを読む (要件 FR-102)。
     *
     * <p>空きスロットは<b>飛ばす</b>。番号の穴は読み出しには見えない。
     */
    public String read(byte[] into) {
        String checked = readable();
        if (checked != null) {
            return checked;
        }
        while (position < slots.size() && slots.get(position) == null) {
            position++;
        }
        if (position >= slots.size()) {
            atEnd = true;
            return FileStatus.AT_END;
        }
        return take(position++, into);
    }

    /** 番号で読む (要件 FR-101)。 */
    public String readAt(int number, byte[] into) {
        String checked = readable();
        if (checked != null) {
            return checked;
        }
        int slot = number - 1;
        if (slot < 0 || slot >= slots.size() || slots.get(slot) == null) {
            return FileStatus.NO_RECORD;
        }
        // 番号で読んだあとの順次読みは、その次から続く
        position = slot + 1;
        atEnd = false;
        return take(slot, into);
    }

    private String readable() {
        if (mode == null) {
            return FileStatus.NOT_OPEN;
        }
        if (!mode.canRead()) {
            return FileStatus.READ_NOT_ALLOWED;
        }
        if (atEnd) {
            return FileStatus.NOT_READABLE;
        }
        return null;
    }

    private String take(int slot, byte[] into) {
        byte[] record = slots.get(slot);
        current = slot;
        int length = Math.min(record.length, into.length);
        System.arraycopy(record, 0, into, 0, length);
        Arrays.fill(into, length, into.length, attributes.codePage().space());
        lastLength = length;
        return record.length == into.length ? FileStatus.OK : FileStatus.LENGTH_MISMATCH;
    }

    /**
     * 位置だけを決める (要件 FR-101)。
     *
     * <p>読まない。指定した関係を満たす最初のスロットへ合わせ、そのあとの順次読みが
     * そこから始まる。満たすスロットがなければ {@code 23} である。
     */
    public String start(int number, KeyRelation relation) {
        String checked = readable();
        if (checked != null && !FileStatus.NOT_READABLE.equals(checked)) {
            return checked;
        }
        Integer found = null;
        if (relation.searchesForward()) {
            for (int slot = 0; slot < slots.size(); slot++) {
                if (slots.get(slot) != null && relation.holds(Integer.compare(slot + 1, number))) {
                    found = slot;
                    break;
                }
            }
        } else {
            // 小さいほうを探す関係では、条件を満たす最後のスロットが位置になる
            for (int slot = slots.size() - 1; slot >= 0; slot--) {
                if (slots.get(slot) != null && relation.holds(Integer.compare(slot + 1, number))) {
                    found = slot;
                    break;
                }
            }
        }
        if (found == null) {
            return FileStatus.NO_RECORD;
        }
        position = found;
        atEnd = false;
        current = -1;
        return FileStatus.OK;
    }

    // ---- 書く ----

    /**
     * 次のスロットへ書く (要件 FR-102)。{@code ACCESS SEQUENTIAL} の {@code WRITE} である。
     *
     * <p>順に埋めていく。使用中のスロットへ当たれば {@code 22} である。
     */
    public String write(byte[] from) {
        String checked = writable();
        if (checked != null) {
            return checked;
        }
        return put(position++, from, false);
    }

    /** 番号を指定して書く (要件 FR-101)。すでに使われていれば {@code 22} である。 */
    public String writeAt(int number, byte[] from) {
        String checked = writable();
        if (checked != null) {
            return checked;
        }
        if (number < 1) {
            return FileStatus.BOUNDARY;
        }
        return put(number - 1, from, false);
    }

    private String writable() {
        if (mode == null) {
            return FileStatus.NOT_OPEN;
        }
        return mode.canWrite() ? null : FileStatus.WRITE_NOT_ALLOWED;
    }

    private String put(int slot, byte[] from, boolean replacing) {
        while (slots.size() <= slot) {
            slots.add(null);
        }
        if (!replacing && slots.get(slot) != null) {
            return FileStatus.DUPLICATE_KEY;
        }
        slots.set(slot, from.clone());
        lastLength = from.length;
        current = -1;
        return FileStatus.OK;
    }

    /** 直前に読んだレコードを書き換える (要件 FR-102)。 */
    public String rewrite(byte[] from) {
        String checked = changeable();
        if (checked != null) {
            return checked;
        }
        if (current < 0) {
            return FileStatus.NO_CURRENT_RECORD;
        }
        return put(current, from, true);
    }

    /** 番号を指定して書き換える (要件 FR-101)。そこが空きなら {@code 23} である。 */
    public String rewriteAt(int number, byte[] from) {
        String checked = changeable();
        if (checked != null) {
            return checked;
        }
        int slot = number - 1;
        if (slot < 0 || slot >= slots.size() || slots.get(slot) == null) {
            return FileStatus.NO_RECORD;
        }
        return put(slot, from, true);
    }

    /** 直前に読んだレコードを消す (要件 FR-102)。 */
    public String delete() {
        String checked = changeable();
        if (checked != null) {
            return checked;
        }
        if (current < 0) {
            return FileStatus.NO_CURRENT_RECORD;
        }
        slots.set(current, null);
        current = -1;
        return FileStatus.OK;
    }

    /** 番号を指定して消す (要件 FR-101)。そこが空きなら {@code 23} である。 */
    public String deleteAt(int number) {
        String checked = changeable();
        if (checked != null) {
            return checked;
        }
        int slot = number - 1;
        if (slot < 0 || slot >= slots.size() || slots.get(slot) == null) {
            return FileStatus.NO_RECORD;
        }
        slots.set(slot, null);
        current = -1;
        return FileStatus.OK;
    }

    /** 書き換えと削除は {@code I-O} だけである。読みながら直す使い方しか意味を持たない。 */
    private String changeable() {
        if (mode == null) {
            return FileStatus.NOT_OPEN;
        }
        return mode == OpenMode.IO ? null : FileStatus.REWRITE_NOT_ALLOWED;
    }
}
