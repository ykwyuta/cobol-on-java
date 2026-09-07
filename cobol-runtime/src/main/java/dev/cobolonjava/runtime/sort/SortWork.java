package dev.cobolonjava.runtime.sort;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.verb.Compare;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 整列作業ファイル (要件 FR-120, FR-121)。
 *
 * <p>{@code SD} が表すのは<b>データセットではなく作業場所</b>である。開くことも閉じることも
 * なく、{@code SORT} の間だけ存在する。渡されたレコードを溜め、並べ替え、順に返す。
 *
 * <h2>並べ替えは安定でなければならない</h2>
 * <p>鍵が等しいレコードの順は、<b>入れた順のまま</b>でなければならない。
 * {@code WITH DUPLICATES IN ORDER} が求めるのはこれであり、指定がなくても崩す理由がない。
 * {@link List#sort} は安定なので、そのまま使える。
 *
 * <h2>合併も同じ道を通る</h2>
 * <p>{@code MERGE} は整列済みの入力を突き合わせる。すべてを溜めてから安定に並べ替えれば、
 * 鍵が等しいところは<b>先に読んだファイルのものが先</b>になる。これは合併の規則そのもので
 * あり、結果は同じになる。
 */
public final class SortWork {

    private final List<SortKey> keys;
    private final CodePage codePage;
    private final List<byte[]> records = new ArrayList<>();
    /** 次に返すレコード。 */
    private int position;

    public SortWork(List<SortKey> keys, CodePage codePage) {
        this.keys = List.copyOf(keys);
        this.codePage = codePage;
    }

    /** 溜めたレコードの数。 */
    public int size() {
        return records.size();
    }

    /** レコードを 1 つ渡す ({@code RELEASE})。 */
    public void release(byte[] record) {
        records.add(record.clone());
    }

    /** 並べ替える。安定であり、鍵が等しいレコードは入れた順のまま残る。 */
    public void sort() {
        records.sort(this::compare);
        position = 0;
    }

    /**
     * 次のレコードを返す ({@code RETURN})。
     *
     * @param into 受け取る領域。足りなければ空白で埋め、あふれれば切り捨てる
     * @return 返すものがなければ {@code false}
     */
    public boolean next(byte[] into) {
        if (position >= records.size()) {
            return false;
        }
        byte[] record = records.get(position++);
        int length = Math.min(record.length, into.length);
        System.arraycopy(record, 0, into, 0, length);
        Arrays.fill(into, length, into.length, codePage.space());
        return true;
    }

    /** すべてのレコードを、並べ替えた順に返す。 */
    public List<byte[]> all() {
        return List.copyOf(records);
    }

    private int compare(byte[] left, byte[] right) {
        for (SortKey key : keys) {
            int order = key.value() != null || key.numeric() != null
                    ? compareNumeric(left, right, key)
                    : Compare.alphanumeric(slice(left, key), slice(right, key), codePage);
            if (order != 0) {
                return key.ascending() ? order : -order;
            }
        }
        // 鍵が等しければ順を変えない。安定であることが規則である
        return 0;
    }

    private int compareNumeric(byte[] left, byte[] right, SortKey key) {
        return number(left, key).compareTo(number(right, key));
    }

    /** 鍵の場所を数として読む。項目で言い表せない形は {@link SortValue} が読む。 */
    private Decimal number(byte[] record, SortKey key) {
        byte[] bytes = slice(record, key);
        return key.value() != null ? key.value().read(bytes) : key.numeric().decode(bytes);
    }

    private static byte[] slice(byte[] record, SortKey key) {
        int from = Math.min(key.offset(), record.length);
        int to = Math.min(key.offset() + key.length(), record.length);
        return Arrays.copyOfRange(record, from, to);
    }
}
