package dev.cobolonjava.runtime.program;

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
    public static final int SIZE = 8;

    /** {@code RETURN-CODE} の位置。 */
    public static final int RETURN_CODE_OFFSET = 0;

    /** {@code RETURN-CODE} の PICTURE。参照実装は 2 進の半語である。 */
    public static final String RETURN_CODE_PICTURE = "S9(4)";
}
