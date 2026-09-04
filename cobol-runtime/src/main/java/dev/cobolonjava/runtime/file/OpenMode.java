package dev.cobolonjava.runtime.file;

/** {@code OPEN} の開き方 (要件 FR-102)。 */
public enum OpenMode {

    /** 読むだけ。 */
    INPUT,

    /** 書くだけ。すでにあれば空にする。 */
    OUTPUT,

    /** 読み書き。 */
    IO,

    /** 書き足す。すでにあれば末尾から続ける。 */
    EXTEND;

    /** このモードで読めるか。 */
    public boolean canRead() {
        return this == INPUT || this == IO;
    }

    /** このモードで書けるか。 */
    public boolean canWrite() {
        return this != INPUT;
    }
}
