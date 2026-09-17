package dev.cobolonjava.ims.dbd;

/**
 * DBD の {@code FIELD TYPE=}。SSA の値と比べるときの比べ方を決める。
 *
 * <p>{@code C} と {@code X} はバイトの並びで比べる。数の型は数として比べる。
 */
public enum FieldType {
    /** 文字 (既定)。バイトの並びで比べる。 */
    C,
    /** 16 進。バイトの並びで比べる。 */
    X,
    /** パック 10 進。 */
    P,
    /** ゾーン 10 進。 */
    Z,
    /** 符号付きの全語 2 進 (4 byte)。 */
    F,
    /** 符号付きの半語 2 進 (2 byte)。 */
    H
}
