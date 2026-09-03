package dev.cobolonjava.runtime.storage;

import java.util.Arrays;
import java.util.Objects;

/**
 * {@link Storage} 上の連続したバイト範囲に対するビュー。COBOL のデータ項目 1 個に対応する。
 *
 * <p>集団項目と基本項目の区別はビューの側にはない。集団項目は「配下の基本項目が占める
 * バイト範囲そのもの」であり、集団項目への操作が英数字項目として無変換にバイト単位で作用するという
 * COBOL の規則 (要件 FR-020) は、この表現から自然に導かれる。
 *
 * <p>{@code REDEFINES} は同一範囲を指す 2 つ目のビューを作ることで表現される。
 * 両者は同じ {@link Storage} を参照するため、一方への書き込みは他方から即座に観測される (要件 FR-021)。
 */
public final class DataView {

    private final Storage storage;
    private final int offset;
    private final int length;

    DataView(Storage storage, int offset, int length) {
        Objects.requireNonNull(storage, "storage");
        if (offset < 0 || length < 0 || offset + length > storage.size()) {
            throw new IndexOutOfBoundsException(
                    "view [" + offset + ", " + (offset + length) + ") exceeds storage of size " + storage.size());
        }
        this.storage = storage;
        this.offset = offset;
        this.length = length;
    }

    public int offset() {
        return offset;
    }

    public int length() {
        return length;
    }

    public Storage storage() {
        return storage;
    }

    public byte get(int index) {
        checkIndex(index);
        return storage.array()[offset + index];
    }

    public void set(int index, byte value) {
        checkIndex(index);
        storage.array()[offset + index] = value;
    }

    /** この範囲のバイト列をコピーして返す。 */
    public byte[] toByteArray() {
        return Arrays.copyOfRange(storage.array(), offset, offset + length);
    }

    /**
     * バイト列を書き込む。{@code src} の長さはこのビューの長さと一致していなければならない。
     * 長さの異なる転記は分類ごとの規則 (詰め方向・充填文字・切り捨て) が異なるため、
     * ここではなく {@code verb} パッケージの責務とする。
     */
    public void setBytes(byte[] src) {
        Objects.requireNonNull(src, "src");
        if (src.length != length) {
            throw new IllegalArgumentException(
                    "length mismatch: view=" + length + ", src=" + src.length);
        }
        System.arraycopy(src, 0, storage.array(), offset, length);
    }

    /** この範囲を指定バイトで埋める。 */
    public void fill(byte value) {
        Arrays.fill(storage.array(), offset, offset + length, value);
    }

    /** 同じ長さの他のビューから内容をコピーする。範囲が重なっていても正しく動作する。 */
    public void copyFrom(DataView src) {
        Objects.requireNonNull(src, "src");
        if (src.length != length) {
            throw new IllegalArgumentException(
                    "length mismatch: dst=" + length + ", src=" + src.length);
        }
        System.arraycopy(src.storage.array(), src.offset, storage.array(), offset, length);
    }

    /**
     * このビューの中の部分ビューを作る。COBOL の部分参照 {@code 項目(開始:長さ)} に対応する。
     *
     * @param relativeOffset このビューの先頭からの相対位置 (0 起点)
     */
    public DataView subView(int relativeOffset, int subLength) {
        if (relativeOffset < 0 || subLength < 0 || relativeOffset + subLength > length) {
            throw new IndexOutOfBoundsException(
                    "sub view [" + relativeOffset + ", " + (relativeOffset + subLength)
                            + ") exceeds view of length " + length);
        }
        return new DataView(storage, offset + relativeOffset, subLength);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("index " + index + " out of view of length " + length);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DataView[").append(offset).append("..")
                .append(offset + length).append(") 0x");
        for (byte b : toByteArray()) {
            sb.append(String.format("%02X", b));
        }
        return sb.append(']').toString();
    }
}
