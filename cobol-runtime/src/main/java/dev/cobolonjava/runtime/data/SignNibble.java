package dev.cobolonjava.runtime.data;

/**
 * パック10進数およびゾーン10進数の符号ニブル (要件 FR-031)。
 *
 * <p>z/Architecture では、正号として {@code A} {@code C} {@code E} {@code F} が、
 * 負号として {@code B} {@code D} が受理される。生成時の優先符号は正が {@code C}、負が {@code D} である。
 * 符号なし項目には {@code F} を用いる。
 *
 * <p>読み取り時に広い符号を受理し書き込み時に優先符号を生成する、というこの非対称性は、
 * ホストのバイト列をそのまま読み込む際の互換性 (要件 FR-110) に直結する。
 */
public final class SignNibble {

    /** 生成時の正号。 */
    public static final int PREFERRED_PLUS = 0xC;
    /** 生成時の負号。 */
    public static final int PREFERRED_MINUS = 0xD;
    /** 符号なし項目に用いる正号。 */
    public static final int UNSIGNED_PLUS = 0xF;

    private SignNibble() {
    }

    /** 符号ニブルとして妥当かどうか (0x0〜0x9 は符号ニブルではない)。 */
    public static boolean isValid(int nibble) {
        return nibble >= 0xA && nibble <= 0xF;
    }

    /**
     * 符号ニブルを符号へ変換する。
     *
     * @return 正なら {@code +1}、負なら {@code -1}
     * @throws IllegalArgumentException 符号ニブルとして妥当でない場合
     */
    public static int toSign(int nibble) {
        return switch (nibble) {
            case 0xB, 0xD -> -1;
            case 0xA, 0xC, 0xE, 0xF -> 1;
            default -> throw new IllegalArgumentException(
                    String.format("not a valid sign nibble: 0x%X", nibble));
        };
    }
}
