package dev.cobolonjava.compiler.semantic;

/**
 * データ部の節 (要件 FR-010, FR-027)。
 *
 * <p>どの節に書かれたかで<b>記憶域がどこにあるか</b>が変わる。作業場所節の項目は
 * プログラム自身の記憶域を占める。連絡節の項目は<b>記憶域を持たない</b>。
 * 呼ぶ側から渡された領域への窓であり、実体は呼ぶ側にある。
 */
public enum DataSection {

    /** {@code WORKING-STORAGE SECTION}。プログラム自身の記憶域。 */
    WORKING_STORAGE,

    /** {@code LOCAL-STORAGE SECTION}。呼び出しのたびに作り直される記憶域。 */
    LOCAL_STORAGE,

    /**
     * {@code FILE SECTION}。{@code FD} 配下のレコード領域。
     *
     * <p>ホストでは入出力バッファであり、作業場所とは別の場所にある。ここではまだ
     * <b>作業場所の一部として</b>割り付けている (暫定判断 P-037)。観測できる違いは
     * 開いていないファイルのレコード領域を触ったときだけであり、いまは出さない。
     *
     * <p>1 つの {@code FD} に複数のレコード記述があれば、それらは<b>重なる</b>。
     * どれも同じ 1 つのバッファに別の切り方で名前を付けたものである。
     */
    FILE,

    /** {@code LINKAGE SECTION}。記憶域を持たず、渡された領域への窓である。 */
    LINKAGE,

    /**
     * 特殊レジスタ。データ部には書かれない。
     *
     * <p>{@code RETURN-CODE} は<b>実行の全体で 1 つ</b>であり、呼ぶ側と呼ばれる側が同じものを
     * 見る。したがってプログラムごとの記憶域には置けない。実行時の入口が持つ置き場を指す。
     */
    SPECIAL_REGISTER,

    /** CICS taskが持つ読み取り専用のEXEC interface block。 */
    CICS_EIB
}
