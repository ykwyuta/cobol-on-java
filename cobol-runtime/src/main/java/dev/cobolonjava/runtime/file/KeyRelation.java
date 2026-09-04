package dev.cobolonjava.runtime.file;

/**
 * {@code START} の {@code KEY IS} に書く関係 (要件 FR-101)。
 *
 * <p>{@code START} は<b>読まずに位置だけを決める</b>。指定した鍵との関係を満たす
 * 最初のレコードへ位置を合わせ、そのあとの順次読み出しがそこから始まる。
 */
public enum KeyRelation {

    /** 等しい。 */
    EQUAL,

    /** 大きい。 */
    GREATER,

    /** 小さくない (以上)。 */
    NOT_LESS,

    /** 小さい。 */
    LESS,

    /** 大きくない (以下)。 */
    NOT_GREATER;

    /**
     * 比較の結果がこの関係を満たすか。
     *
     * @param comparison レコードの鍵と指定した鍵を比べた符号
     */
    public boolean holds(int comparison) {
        return switch (this) {
            case EQUAL -> comparison == 0;
            case GREATER -> comparison > 0;
            case NOT_LESS -> comparison >= 0;
            case LESS -> comparison < 0;
            case NOT_GREATER -> comparison <= 0;
        };
    }

    /** 手前から探すか。小さいほうを探す関係では、うしろから探すことになる。 */
    public boolean searchesForward() {
        return this != LESS && this != NOT_GREATER;
    }
}
