package dev.cobolonjava.oracle.cases;

/**
 * 合成ジェネレータが生成する 1 件の 2 項 10 進演算のテストケース。
 *
 * @param byteLength 項目のバイト長。数字ニブルの数は {@code 2 * byteLength - 1}
 * @param left       第 1 オペランド (結果の格納先でもある) の分類
 * @param right      第 2 オペランドの分類
 * @param operation  演算
 */
public record DecimalCase(int byteLength, OperandClass left, OperandClass right,
                          DecimalOperation operation) {

    public byte[] leftBytes() {
        return left.bytes(byteLength);
    }

    public byte[] rightBytes() {
        return right.bytes(byteLength);
    }

    /** 数字ニブルの数。 */
    public int digits() {
        return byteLength * 2 - 1;
    }

    @Override
    public String toString() {
        return String.format("%s len=%d %s %s", operation, byteLength, left, right);
    }
}
