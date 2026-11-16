package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.storage.Storage;

/**
 * 特殊レジスタの置き場 (要件 FR-084, FR-085)。
 *
 * <p>{@code RETURN-CODE} は<b>実行の全体で 1 つ</b>である。呼ぶ側と呼ばれる側が同じものを見る。
 * したがってプログラムごとの記憶域には置けない。ここへ置き、{@link ProgramContext} が
 * 実行のあいだ持ち回る。
 *
 * <p>位置はコンパイラとランタイムの<b>両方が知っている必要がある</b>ため、
 * ここ 1 か所に置く。別々に持つとずれる。
 */
public final class SpecialRegisterArea {

    private SpecialRegisterArea() {
    }

    /** 置き場の大きさ。特殊レジスタを足すときはここを広げる。 */
    public static final int SIZE = 40;

    /** {@code RETURN-CODE} の位置。 */
    public static final int RETURN_CODE_OFFSET = 0;

    /** {@code RETURN-CODE} の PICTURE。参照実装は 2 進の半語である。 */
    public static final String RETURN_CODE_PICTURE = "S9(4)";

    /** {@code TALLY} の位置。PIC 9(5) COMP の 4 byte である。 */
    public static final int TALLY_OFFSET = 4;
    public static final int SORT_RETURN_OFFSET = 8;
    public static final int SORT_CORE_SIZE_OFFSET = 12;
    public static final int SORT_FILE_SIZE_OFFSET = 16;
    public static final int SORT_MODE_SIZE_OFFSET = 20;
    public static final int SORT_CONTROL_OFFSET = 24;
    public static final int SORT_MESSAGE_OFFSET = 32;

    static Storage create() {
        Storage storage = Storage.allocate(SIZE);
        storage.view(SORT_CONTROL_OFFSET, 8).setBytes(CodePages.DEFAULT.encode("IGZSRTCD"));
        storage.view(SORT_MESSAGE_OFFSET, 8).setBytes(CodePages.DEFAULT.encode("SYSOUT  "));
        return storage;
    }
}
