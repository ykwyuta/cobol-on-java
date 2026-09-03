package dev.cobolonjava.compiler.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * データ部の記憶域の割り付け (要件 FR-020)。
 *
 * <p>01 レベル (と独立項目 77) を根とする木の並びである。
 * 各項目は所属する根からのバイト位置と長さを持つ。
 */
public final class DataLayout {

    private final List<DataItem> records;
    private final int totalLength;

    DataLayout(List<DataItem> records, int totalLength) {
        this.records = List.copyOf(records);
        this.totalLength = totalLength;
    }

    /** プログラムの記憶域の全体の長さ。 */
    public int totalLength() {
        return totalLength;
    }

    /** 01 レベルと独立項目の並び。 */
    public List<DataItem> records() {
        return records;
    }

    /** すべての項目を、書かれた順に並べたもの。 */
    public List<DataItem> all() {
        List<DataItem> out = new ArrayList<>();
        for (DataItem record : records) {
            collect(record, out);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 名前で項目を探す。
     *
     * <p>COBOL は<b>同じ名前を複数の場所に置ける</b> (修飾して区別する)。したがって
     * 見つかったものをすべて返す。1 個に絞る責任は呼び出し側にある。
     */
    public List<DataItem> findAll(String name) {
        String key = name.toUpperCase(Locale.ROOT);
        List<DataItem> found = new ArrayList<>();
        for (DataItem item : all()) {
            if (key.equals(item.name())) {
                found.add(item);
            }
        }
        return Collections.unmodifiableList(found);
    }

    private static void collect(DataItem item, List<DataItem> out) {
        out.add(item);
        for (DataItem child : item.children()) {
            collect(child, out);
        }
    }
}
