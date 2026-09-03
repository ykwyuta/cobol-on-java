package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.source.Origin;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;

/**
 * 解決済みのデータ参照 (要件 FR-024, FR-026)。
 *
 * <p>一意名 1 個、すなわち<b>どの項目のどのバイトを指すか</b>を表す。
 * 添字と部分参照が定数だけで書かれていれば、位置と長さは翻訳時に決まる。
 * 変数が混ざれば実行時に決まるため、ここでは式として保つ。
 *
 * @param item       指している項目
 * @param subscripts 添字。外側の表から順に並ぶ
 * @param refMod     部分参照。指定がなければ {@code null}
 * @param origin     ソース上の位置
 */
public record DataReference(DataItem item, List<Subscript> subscripts, RefMod refMod,
                            Origin origin) {

    public DataReference {
        subscripts = List.copyOf(subscripts);
    }

    /** 添字 1 個。定数か、実行時に値が決まる別の参照。 */
    public sealed interface Subscript {

        /** 定数の添字。 */
        record Constant(int value) implements Subscript {
        }

        /** データ項目で指定した添字。値は実行時に決まる。 */
        record Variable(DataReference reference) implements Subscript {
        }
    }

    /**
     * 部分参照 {@code 項目(開始:長さ)}。
     *
     * @param leftmost 開始位置 (1 起点)
     * @param length   長さ。省略時は {@code null} で、項目の終わりまでを指す
     */
    public record RefMod(Subscript leftmost, Subscript length) {
    }

    /** この参照が指す項目を含む、外側からの表の並び。添字はこの順に対応する。 */
    public static List<DataItem> tableChain(DataItem item) {
        List<DataItem> tables = new ArrayList<>();
        for (DataItem current = item; current != null; current = current.parent()) {
            if (current.isTable()) {
                tables.add(current);
            }
        }
        Collections.reverse(tables);
        return tables;
    }

    /** この参照が要求する添字の個数。 */
    public int requiredSubscripts() {
        return tableChain(item).size();
    }

    /**
     * 所属する 01 レベルの先頭からのバイト位置。
     * 添字か部分参照に変数が混ざっていれば空を返す。
     */
    public OptionalInt constantOffset() {
        List<DataItem> tables = tableChain(item);
        if (tables.size() != subscripts.size()) {
            return OptionalInt.empty();
        }
        int offset = item.offset();
        for (int i = 0; i < tables.size(); i++) {
            if (!(subscripts.get(i) instanceof Subscript.Constant constant)) {
                return OptionalInt.empty();
            }
            offset += (constant.value() - 1) * tables.get(i).length();
        }
        if (refMod != null) {
            if (!(refMod.leftmost() instanceof Subscript.Constant leftmost)) {
                return OptionalInt.empty();
            }
            offset += leftmost.value() - 1;
        }
        return OptionalInt.of(offset);
    }

    /** プログラムの記憶域の先頭からのバイト位置。変数が混ざれば空を返す。 */
    public OptionalInt absoluteOffset() {
        OptionalInt relative = constantOffset();
        return relative.isEmpty()
                ? relative
                : OptionalInt.of(item.record().base() + relative.getAsInt());
    }

    /** 参照する長さ。部分参照があればその長さになる。変数が混ざれば空を返す。 */
    public OptionalInt constantLength() {
        if (refMod == null) {
            return OptionalInt.of(item.length());
        }
        if (refMod.length() == null) {
            // 長さの省略は「項目の終わりまで」である
            if (refMod.leftmost() instanceof Subscript.Constant leftmost) {
                return OptionalInt.of(item.length() - (leftmost.value() - 1));
            }
            return OptionalInt.empty();
        }
        return refMod.length() instanceof Subscript.Constant length
                ? OptionalInt.of(length.value())
                : OptionalInt.empty();
    }
}
