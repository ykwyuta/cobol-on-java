package dev.cobolonjava.runtime.data;

/**
 * ゾーン10進数における符号の持ち方 (要件 FR-031)。
 * PICTURE の {@code S} と {@code SIGN IS} 句の組み合わせで決まる。
 */
public enum SignPosition {

    /** PICTURE に {@code S} がない。符号を持たず、格納される値は常に絶対値となる。 */
    UNSIGNED(false),

    /** 既定。最終バイトのゾーンニブルに符号を持つ。 */
    TRAILING(false),

    /** {@code SIGN IS LEADING}。先頭バイトのゾーンニブルに符号を持つ。 */
    LEADING(false),

    /** {@code SIGN IS TRAILING SEPARATE}。末尾に符号専用の 1 バイトを持つ。 */
    TRAILING_SEPARATE(true),

    /** {@code SIGN IS LEADING SEPARATE}。先頭に符号専用の 1 バイトを持つ。 */
    LEADING_SEPARATE(true);

    private final boolean separate;

    SignPosition(boolean separate) {
        this.separate = separate;
    }

    /** 符号が数字とは別の 1 バイトを占めるかどうか。項目のバイト長に影響する。 */
    public boolean isSeparate() {
        return separate;
    }

    public boolean isSigned() {
        return this != UNSIGNED;
    }

    /** 符号が先頭側にあるかどうか。 */
    public boolean isLeading() {
        return this == LEADING || this == LEADING_SEPARATE;
    }
}
