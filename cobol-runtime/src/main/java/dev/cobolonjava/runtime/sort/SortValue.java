package dev.cobolonjava.runtime.sort;

import dev.cobolonjava.runtime.decimal.Decimal;

/**
 * 鍵の場所を数として読む道 (要件 FR-120, FR-137)。
 *
 * <p>{@link SortKey} はふつう {@code PICTURE} と {@code USAGE} で読み方を言う。COBOL の
 * {@code SORT} が並べるのは<b>データ項目</b>だからである。
 *
 * <p>ところが整列の道具には、項目で言い表せない形がある。{@code DFSORT} の {@code UFF} は
 * 「数字だけを拾って残りは読み飛ばす」形であり、どんな {@code PICTURE} にも当たらない。
 * それでも<b>比べ方は 1 か所に置く</b>ほうがよいので、読み方だけを差し替えられるように
 * してある。
 */
@FunctionalInterface
public interface SortValue {

    /** 切り出したバイト列を数として読む。読めなければ 0 とみなすかは実装が決める。 */
    Decimal read(byte[] bytes);
}
