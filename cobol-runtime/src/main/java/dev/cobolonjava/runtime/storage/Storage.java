package dev.cobolonjava.runtime.storage;

import java.util.Arrays;
import java.util.Objects;

/**
 * COBOL プログラムのデータ領域を表す連続したバイト列。
 *
 * <p>要件 FR-020 / ARC-2 に従い、すべてのデータ項目はこの上の {@link DataView} として表現される。
 * データ項目そのものを Java のオブジェクトへ写像しないのは、{@code REDEFINES}・集団項目の移送・
 * 部分参照といった COBOL の中核的な意味論が「同一バイト範囲への複数のビュー」を前提にしているためである。
 */
public final class Storage {

    private final byte[] bytes;

    private Storage(byte[] bytes) {
        this.bytes = bytes;
    }

    /** 指定サイズの領域を確保する。内容はすべて {@code 0x00} で初期化される。 */
    public static Storage allocate(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative: " + size);
        }
        return new Storage(new byte[size]);
    }

    /** 既存のバイト配列をそのまま領域として用いる。配列はコピーされない。 */
    public static Storage wrap(byte[] bytes) {
        return new Storage(Objects.requireNonNull(bytes, "bytes"));
    }

    /** 既存のバイト配列の内容をコピーした新しい領域を作る。 */
    public static Storage copyOf(byte[] bytes) {
        return new Storage(Arrays.copyOf(Objects.requireNonNull(bytes, "bytes"), bytes.length));
    }

    public int size() {
        return bytes.length;
    }

    /** 内部配列への直接参照を返す。呼び出し側による変更は領域に反映される。 */
    public byte[] array() {
        return bytes;
    }

    /**
     * この領域上のビューを作る。
     *
     * @param offset 開始位置 (0 起点)
     * @param length バイト長
     */
    public DataView view(int offset, int length) {
        return new DataView(this, offset, length);
    }

    /** 領域全体を覆うビューを返す。 */
    public DataView whole() {
        return new DataView(this, 0, bytes.length);
    }
}
