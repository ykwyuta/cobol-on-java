package dev.cobolonjava.runtime.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 索引編成のデータセット (要件 FR-100, FR-101, FR-102)。
 *
 * <p>レコードを<b>中身の一部で引く</b>。鍵はレコードの中にあり、住所ではなく持ち物である。
 * ホストの VSAM KSDS にあたる。
 *
 * <h2>索引は持たずに組み直す</h2>
 * <p>鍵がレコードの中にある以上、<b>索引はデータから導ける</b>。したがってファイルに持つのは
 * レコードだけであり、開いたときに組み直す。索引を別に保存すると、本体と食い違う余地ができる。
 *
 * <p>鍵の場所はファイルではなく<b>プログラムが決める</b>。`RECORD KEY` に書いた項目の位置と
 * 長さであり、開くときに渡される。
 *
 * <h2>並びは主鍵の順である</h2>
 * <p>書き出すときは主鍵の順に並べる。移行してきた KSDS の抽出も鍵順になっているので、
 * 読み書きを往復しても並びが変わらない。
 */
public final class IndexedDataSet implements KeyedDataSet {

    /**
     * 鍵の場所。
     *
     * @param offset     レコードの先頭からの位置
     * @param length     長さ
     * @param duplicates 同じ値を許すか。主鍵では常に {@code false}
     */
    public record Key(int offset, int length, boolean duplicates) {
    }

    /**
     * 鍵の値。並べ替えのために順序を持つ。
     *
     * <p>比べるのは<b>符号なしのバイト</b>である。EBCDIC の照合順序はバイトの値そのもので
     * あり、コードページを通した解釈を挟む必要がない。
     */
    record ByteKey(byte[] bytes) implements Comparable<ByteKey> {

        @Override
        public int compareTo(ByteKey other) {
            return Arrays.compareUnsigned(bytes, other.bytes);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ByteKey key && Arrays.equals(bytes, key.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    private final Path path;
    private DataSetAttributes attributes;
    private final Key primary;
    private final List<Key> alternates;

    private OpenMode mode;
    /** 主鍵からレコードを引く。並びは主鍵の順である。 */
    private TreeMap<ByteKey, byte[]> records;
    /** 副鍵ごとの索引。副鍵から主鍵の並びを引く。同じ副鍵のレコードは書いた順に並ぶ。 */
    private List<TreeMap<ByteKey, List<ByteKey>>> indexes;

    /** いま順に読んでいる索引。{@code 0} が主鍵、{@code 1} 以降が副鍵である。 */
    private int active;
    /** 主鍵順で次に読むもの。{@code null} は終わりである。 */
    private ByteKey nextPrimary;
    /** 副鍵順で次に読む鍵と、その中で何番目か。 */
    private ByteKey nextAlternate;
    private int nextWithin;
    /** 直前に読んだレコードの主鍵。{@code null} は指していない。 */
    private ByteKey current;
    private boolean atEnd;
    private int lastLength;
    /** 順アクセスの書き込みで、直前に書いた主鍵。昇順の検査に使う。 */
    private ByteKey lastWritten;
    /** ジョブが割り当てで決めたこと (要件 FR-113, FR-141)。 */
    private final DataSetAllocation allocation = new DataSetAllocation();

    public IndexedDataSet(Path path, DataSetAttributes attributes, Key primary,
                          List<Key> alternates) {
        this.path = path;
        this.attributes = attributes;
        this.primary = primary;
        this.alternates = List.copyOf(alternates);
    }

    /** 属性をサイドカーから読んで開く用意をする。サイドカーがなければ宣言に拠る。 */
    public static IndexedDataSet at(Path path, DataSetAttributes declared, Key primary,
                                    List<Key> alternates) {
        return new IndexedDataSet(path, DataSetAttributes.read(path, declared), primary,
                alternates);
    }

    @Override
    public DataSetAttributes attributes() {
        return attributes;
    }

    @Override
    public void limit(long bytes) {
        allocation.limit(bytes);
    }

    @Override
    public void member(boolean value) {
        allocation.member(value);
    }

    @Override
    public boolean isOpen() {
        return mode != null;
    }

    /** いまの開き方。開いていなければ {@code null}。 */
    @Override
    public OpenMode mode() {
        return mode;
    }

    @Override
    public int lastLength() {
        return lastLength;
    }

    // ---- 開く・閉じる ----

    @Override
    public String open(OpenMode requested, boolean optional) {
        if (mode != null) {
            return FileStatus.ALREADY_OPEN;
        }
        String refused = allocation.opening(path, requested, optional, attributes.codePage());
        if (refused != null) {
            return refused;
        }
        boolean missing = !Files.isReadable(path);
        attributes = DataSetAttributes.read(path, attributes);
        records = new TreeMap<>();
        if (requested != OpenMode.OUTPUT && !missing) {
            RecordFraming.Framed framed = RecordFraming.split(readAll(), attributes, false);
            if (framed.damaged()) {
                // 読む順序が鍵の順である以上、どこまで読めるとは言えない (要件 FR-141)
                records = null;
                return FileStatus.IO_ERROR;
            }
            for (byte[] record : framed.records()) {
                records.put(keyOf(record, primary), record);
            }
        }
        rebuildIndexes();
        mode = requested;
        active = 0;
        rewind();
        current = null;
        lastWritten = null;
        lastLength = 0;
        return missing && requested != OpenMode.OUTPUT
                ? FileStatus.OPTIONAL_CREATED
                : FileStatus.OK;
    }

    @Override
    public String close() {
        if (mode == null) {
            return FileStatus.NOT_OPEN;
        }
        if (mode.canWrite()) {
            save();
        }
        mode = null;
        records = null;
        indexes = null;
        current = null;
        return FileStatus.OK;
    }

    private byte[] readAll() {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    private void save() {
        List<byte[]> all = new ArrayList<>(records.values());
        try {
            Files.write(path, join(all), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            attributes.write(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + path, e);
        }
    }

    /**
     * 索引はデータから導ける。<b>開いたときだけ</b>組み直す。
     *
     * <p>ファイルはレコードだけを主鍵の順に持っているので、組み直した索引では
     * 同じ副鍵のレコードが主鍵の順に並ぶ。開いたあとの並びは、書いた順・書き換えた順で
     * 動く (暫定判断 P-087)。
     */
    private void rebuildIndexes() {
        indexes = new ArrayList<>();
        for (Key alternate : alternates) {
            TreeMap<ByteKey, List<ByteKey>> index = new TreeMap<>();
            for (Map.Entry<ByteKey, byte[]> entry : records.entrySet()) {
                index.computeIfAbsent(keyOf(entry.getValue(), alternate), k -> new ArrayList<>())
                        .add(entry.getKey());
            }
            indexes.add(index);
        }
    }

    /**
     * 索引にレコードを 1 本足す。同じ副鍵の並びの<b>末尾</b>に付く。
     *
     * <p>{@code WITH DUPLICATES} の副鍵で同じ値のレコードを順に読むと、
     * <b>索引に入った順</b>に返る。並び全体を組み直してしまうと主鍵の順に戻ってしまい、
     * あとから入ったレコードが先に返る (IX215A START-TEST-GF-09)。
     */
    private void indexInsert(ByteKey key, byte[] record) {
        for (int i = 0; i < alternates.size(); i++) {
            indexes.get(i)
                    .computeIfAbsent(keyOf(record, alternates.get(i)), k -> new ArrayList<>())
                    .add(key);
        }
    }

    /** 索引からレコードを 1 本外す。 */
    private void indexRemove(ByteKey key, byte[] record) {
        for (int i = 0; i < alternates.size(); i++) {
            removeFromChain(indexes.get(i), keyOf(record, alternates.get(i)), key);
        }
    }

    /**
     * 書き換えを索引へ映す。
     *
     * <p>値が変わらなかった副鍵は<b>並びを動かさない</b>。変わった副鍵では、元の並びから
     * 外して新しい並びの末尾へ付ける。ホストの副索引も、鍵が変わった項目だけを
     * 入れ替える。
     */
    private void indexUpdate(ByteKey key, byte[] before, byte[] after) {
        for (int i = 0; i < alternates.size(); i++) {
            Key alternate = alternates.get(i);
            ByteKey was = keyOf(before, alternate);
            ByteKey now = keyOf(after, alternate);
            if (was.equals(now)) {
                continue;
            }
            TreeMap<ByteKey, List<ByteKey>> index = indexes.get(i);
            removeFromChain(index, was, key);
            index.computeIfAbsent(now, k -> new ArrayList<>()).add(key);
        }
    }

    private static void removeFromChain(TreeMap<ByteKey, List<ByteKey>> index, ByteKey value,
                                        ByteKey key) {
        List<ByteKey> keys = index.get(value);
        if (keys == null) {
            return;
        }
        keys.remove(key);
        if (keys.isEmpty()) {
            index.remove(value);
        }
    }

    private static ByteKey keyOf(byte[] record, Key key) {
        int from = Math.min(key.offset(), record.length);
        int to = Math.min(key.offset() + key.length(), record.length);
        return new ByteKey(Arrays.copyOfRange(record, from, to));
    }

    private void rewind() {
        nextPrimary = records.isEmpty() ? null : records.firstKey();
        nextAlternate = indexes.isEmpty() || indexes.get(0).isEmpty()
                ? null
                : indexes.get(0).firstKey();
        nextWithin = 0;
        atEnd = false;
    }

    // ---- レコードの切り出し ----

    private byte[] join(List<byte[]> all) {
        if (attributes.format() == RecordFormat.VARIABLE) {
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
        int length = attributes.recordLength();
        byte[] out = new byte[all.size() * length];
        Arrays.fill(out, attributes.codePage().space());
        for (int i = 0; i < all.size(); i++) {
            byte[] record = all.get(i);
            System.arraycopy(record, 0, out, i * length, Math.min(record.length, length));
        }
        return out;
    }

    // ---- 読む ----

    @Override
    public String read(byte[] into) {
        String checked = readable();
        if (checked != null) {
            return checked;
        }
        ByteKey key = active == 0 ? takeNextPrimary() : takeNextAlternate();
        if (key == null) {
            atEnd = true;
            current = null;
            return FileStatus.AT_END;
        }
        return take(key, into);
    }

    private ByteKey takeNextPrimary() {
        ByteKey key = nextPrimary;
        if (key != null) {
            nextPrimary = records.higherKey(key);
        }
        return key;
    }

    private ByteKey takeNextAlternate() {
        TreeMap<ByteKey, List<ByteKey>> index = indexes.get(active - 1);
        while (nextAlternate != null) {
            List<ByteKey> keys = index.get(nextAlternate);
            if (keys != null && nextWithin < keys.size()) {
                return keys.get(nextWithin++);
            }
            // 同じ副鍵のレコードを読み切ったら次の副鍵へ移る
            nextAlternate = index.higherKey(nextAlternate);
            nextWithin = 0;
        }
        return null;
    }

    /**
     * 鍵で読む (要件 FR-101)。
     *
     * @param keyIndex {@code 0} が主鍵、{@code 1} 以降が {@code ALTERNATE RECORD KEY} の並び順
     */
    public String readKey(int keyIndex, byte[] key, byte[] into) {
        String checked = readable();
        if (checked != null && !FileStatus.NOT_READABLE.equals(checked)) {
            return checked;
        }
        ByteKey wanted = new ByteKey(key.clone());
        ByteKey found;
        if (keyIndex == 0) {
            found = records.containsKey(wanted) ? wanted : null;
            if (found != null) {
                nextPrimary = records.higherKey(found);
            }
        } else {
            TreeMap<ByteKey, List<ByteKey>> index = indexes.get(keyIndex - 1);
            List<ByteKey> keys = index.get(wanted);
            found = keys == null || keys.isEmpty() ? null : keys.get(0);
            if (found != null) {
                nextAlternate = wanted;
                nextWithin = 1;
            }
        }
        if (found == null) {
            current = null;
            return FileStatus.NO_RECORD;
        }
        // 鍵で読んだあとの順次読みは、その索引の続きから始まる
        active = keyIndex;
        atEnd = false;
        return take(found, into);
    }

    /**
     * 読める状態か。読めないなら状態コードを返す。
     *
     * <p>読めなかったら、直前に読んだレコードは<b>もう現在のものではない</b>。
     * 規格は {@code REWRITE} と {@code DELETE} の前の入出力文が「成功した
     * {@code READ}」であることを求めている (85 規格 VII-51, 4.6.4(5))。
     */
    private String readable() {
        if (mode == null) {
            current = null;
            return FileStatus.NOT_OPEN;
        }
        if (!mode.canRead()) {
            current = null;
            return FileStatus.READ_NOT_ALLOWED;
        }
        if (atEnd) {
            current = null;
            return FileStatus.NOT_READABLE;
        }
        return null;
    }

    private String take(ByteKey key, byte[] into) {
        byte[] record = records.get(key);
        current = key;
        int length = Math.min(record.length, into.length);
        System.arraycopy(record, 0, into, 0, length);
        lastLength = length;
        if (attributes.format() == RecordFormat.VARIABLE) {
            return record.length > into.length ? FileStatus.LENGTH_MISMATCH : FileStatus.OK;
        }
        Arrays.fill(into, length, into.length, attributes.codePage().space());
        return record.length == into.length ? FileStatus.OK : FileStatus.LENGTH_MISMATCH;
    }

    /** 位置だけを決める (要件 FR-101)。 */
    public String start(int keyIndex, byte[] key, KeyRelation relation) {
        String checked = readable();
        if (checked != null && !FileStatus.NOT_READABLE.equals(checked)) {
            return checked;
        }
        int declared = keyIndex == 0 ? primary.length() : alternates.get(keyIndex - 1).length();
        if (key.length > declared) {
            return FileStatus.NO_RECORD;
        }
        if (keyIndex == 0) {
            ByteKey found = locate(records.navigableKeySet(), key, declared, relation);
            if (found == null) {
                return FileStatus.NO_RECORD;
            }
            nextPrimary = found;
        } else {
            TreeMap<ByteKey, List<ByteKey>> index = indexes.get(keyIndex - 1);
            ByteKey found = locate(index.navigableKeySet(), key, declared, relation);
            if (found == null) {
                return FileStatus.NO_RECORD;
            }
            nextAlternate = found;
            nextWithin = 0;
        }
        active = keyIndex;
        atEnd = false;
        current = null;
        return FileStatus.OK;
    }

    /**
     * 関係を満たす鍵 (要件 FR-101)。
     *
     * <h2>短い鍵は「先頭が一致するもの」を指す</h2>
     * <p>{@code START} に書く項目は、鍵と<b>同じ位置から始まって短くてよい</b>。
     * 規格がそう決めている。短く書けば「先頭 n 文字が一致するレコード」を指す
     * (総称鍵)。値そのものではなく<b>範囲</b>を指すことになる。
     *
     * <p>範囲は、書かれた値の後ろを {@code 0x00} で埋めた下端と {@code 0xFF} で埋めた
     * 上端で表せる。あとは端から探すだけで、木の性質をそのまま使える。
     * 端を作らずに 1 件ずつ先頭を比べると、鍵の多いファイルで遅くなる。
     *
     * @param wanted   書かれた値。宣言した鍵より短くてよい
     * @param declared 宣言した鍵の長さ
     */
    private static ByteKey locate(java.util.NavigableSet<ByteKey> keys, byte[] wanted,
                                  int declared, KeyRelation relation) {
        ByteKey low = new ByteKey(padded(wanted, declared, (byte) 0x00));
        ByteKey high = new ByteKey(padded(wanted, declared, (byte) 0xFF));
        return switch (relation) {
            case EQUAL -> {
                ByteKey found = keys.ceiling(low);
                yield found != null && found.compareTo(high) <= 0 ? found : null;
            }
            case GREATER -> keys.higher(high);
            case NOT_LESS -> keys.ceiling(low);
            case LESS -> keys.lower(low);
            case NOT_GREATER -> keys.floor(high);
        };
    }

    /** 書かれた値の後ろを埋めて、宣言した鍵の長さに合わせる。 */
    private static byte[] padded(byte[] wanted, int length, byte filler) {
        byte[] out = new byte[length];
        System.arraycopy(wanted, 0, out, 0, Math.min(wanted.length, length));
        java.util.Arrays.fill(out, Math.min(wanted.length, length), length, filler);
        return out;
    }

    // ---- 書く ----

    /**
     * 順アクセスで書く (要件 FR-101, FR-102)。
     *
     * <p>順アクセスの書き込みは<b>主鍵の昇順でなければならない</b>。索引を作りながら
     * 書き出していく形だからである。順序が崩れれば {@code 21} である。
     */
    @Override
    public String write(byte[] from) {
        String checked = writable();
        if (checked != null) {
            return checked;
        }
        ByteKey key = keyOf(from, primary);
        if (lastWritten != null && key.compareTo(lastWritten) <= 0) {
            return FileStatus.KEY_SEQUENCE;
        }
        String status = insert(key, from);
        if (FileStatus.succeeded(status)) {
            lastWritten = key;
        }
        return status;
    }

    /** 乱アクセスで書く (要件 FR-101)。順序は問わない。 */
    public String writeKey(byte[] from) {
        String checked = writable();
        if (checked != null) {
            return checked;
        }
        return insert(keyOf(from, primary), from);
    }

    private String insert(ByteKey key, byte[] from) {
        if (records.containsKey(key)) {
            return FileStatus.DUPLICATE_KEY;
        }
        if (allocation.exceeded(occupied(), sizeOf(from))) {
            // 順編成の 34 にあたるものが、鍵で引く編成では 24 である (要件 FR-141)
            return FileStatus.BOUNDARY;
        }
        String conflict = alternateConflict(key, from);
        if (conflict != null) {
            return conflict;
        }
        records.put(key, from.clone());
        indexInsert(key, records.get(key));
        lastLength = from.length;
        current = null;
        return FileStatus.OK;
    }

    /** いま書き出したとしたら何バイトになるか (要件 FR-141)。 */
    private long occupied() {
        long total = 0;
        for (byte[] record : records.values()) {
            total += sizeOf(record);
        }
        return total;
    }

    /** レコード 1 つが占める大きさ。可変長なら {@code RDW} の 4 バイトが付く。 */
    private long sizeOf(byte[] record) {
        return attributes.format() == RecordFormat.VARIABLE
                ? record.length + 4L
                : attributes.recordLength();
    }

    /** {@code WITH DUPLICATES} を書いていない副鍵は、同じ値を 2 つ持てない。 */
    private String alternateConflict(ByteKey primaryKey, byte[] record) {
        for (int i = 0; i < alternates.size(); i++) {
            Key alternate = alternates.get(i);
            if (alternate.duplicates()) {
                continue;
            }
            List<ByteKey> keys = indexes.get(i).get(keyOf(record, alternate));
            if (keys == null || keys.isEmpty()) {
                continue;
            }
            if (keys.size() > 1 || !keys.get(0).equals(primaryKey)) {
                return FileStatus.DUPLICATE_KEY;
            }
        }
        return null;
    }

    /**
     * 直前に読んだレコードを書き換える (要件 FR-102)。
     *
     * <p>主鍵を変えることはできない。書き換えではなく<b>別のレコード</b>になってしまう。
     */
    @Override
    public String rewrite(byte[] from) {
        String checked = changeable();
        if (checked != null) {
            return checked;
        }
        if (current == null) {
            return FileStatus.NO_CURRENT_RECORD;
        }
        if (!keyOf(from, primary).equals(current)) {
            return FileStatus.KEY_SEQUENCE;
        }
        return replace(current, from);
    }

    /** 鍵で引いて書き換える (要件 FR-101)。レコードの中の主鍵が相手を決める。 */
    public String rewriteKey(byte[] from) {
        String checked = changeable();
        if (checked != null) {
            return checked;
        }
        ByteKey key = keyOf(from, primary);
        return records.containsKey(key) ? replace(key, from) : FileStatus.NO_RECORD;
    }

    private String replace(ByteKey key, byte[] from) {
        // 副鍵のぶつかりは<b>書き換える前に</b>見る。索引を組み直して確かめてから戻すと、
        // 同じ副鍵の並びが主鍵の順に戻ってしまう
        String conflict = alternateConflict(key, from);
        if (conflict != null) {
            return conflict;
        }
        byte[] previous = records.put(key, from.clone());
        indexUpdate(key, previous, records.get(key));
        lastLength = from.length;
        current = null;
        return FileStatus.OK;
    }

    @Override
    public String delete() {
        String checked = changeable();
        if (checked != null) {
            return checked;
        }
        if (current == null) {
            return FileStatus.NO_CURRENT_RECORD;
        }
        remove(current);
        return FileStatus.OK;
    }

    /** 鍵で引いて消す (要件 FR-101)。 */
    public String deleteKey(byte[] key) {
        String checked = changeable();
        if (checked != null) {
            return checked;
        }
        ByteKey wanted = new ByteKey(key.clone());
        if (!records.containsKey(wanted)) {
            return FileStatus.NO_RECORD;
        }
        remove(wanted);
        return FileStatus.OK;
    }

    private void remove(ByteKey key) {
        byte[] record = records.remove(key);
        if (record != null) {
            indexRemove(key, record);
        }
        current = null;
    }

    private String writable() {
        if (mode == null) {
            return FileStatus.NOT_OPEN;
        }
        return mode.canWrite() ? null : FileStatus.WRITE_NOT_ALLOWED;
    }

    private String changeable() {
        if (mode == null) {
            return FileStatus.NOT_OPEN;
        }
        return mode == OpenMode.IO ? null : FileStatus.REWRITE_NOT_ALLOWED;
    }
}
