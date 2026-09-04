package dev.cobolonjava.runtime.file;

/**
 * 鍵で引けるデータセット (要件 FR-101)。
 *
 * <p>相対編成と索引編成に共通するのは、<b>直前に読んだレコードを消せる</b>ことである。
 * 順編成にはそれがない。並びの途中を抜くと、あとのレコードがすべて動いてしまうからである。
 *
 * <p>鍵そのものの形は編成で違う。相対編成は番号 ({@code int})、索引編成はレコードの中の
 * バイト列である。したがって鍵を取る操作はここには置かず、それぞれの側に持たせている。
 */
public sealed interface KeyedDataSet extends DataSet permits RelativeDataSet, IndexedDataSet {

    /** 直前に読んだレコードを消す。読んでいなければ {@code 43} である。 */
    String delete();
}
