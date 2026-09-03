package dev.cobolonjava.oracle.cases;

/**
 * パック10進項目の値の分類 (要件 NFR-041 の境界値)。
 *
 * <p>境界値は全列挙する。桁数の境界、値の境界 (0、±1、最大値)、
 * および符号ニブルの全パターン (優先符号 {@code C} / {@code D}、
 * 符号なしの {@code F}、受理される {@code A} / {@code B}) を含む。
 */
public enum OperandClass {

    /** 正のゼロ。 */
    ZERO_PLUS(Value.ZERO, 0xC),
    /** 負のゼロ。ホストのバイト列として実在する。 */
    ZERO_MINUS(Value.ZERO, 0xD),
    /** +1。 */
    ONE_PLUS(Value.ONE, 0xC),
    /** -1。 */
    ONE_MINUS(Value.ONE, 0xD),
    /** 全桁 9 の正数。項目に入る最大値。 */
    MAX_PLUS(Value.MAX, 0xC),
    /** 全桁 9 の負数。 */
    MAX_MINUS(Value.MAX, 0xD),
    /** 最大値より 1 小さい正数。 */
    MAX_MINUS_ONE_PLUS(Value.MAX_MINUS_ONE, 0xC),
    /** 桁ごとに異なる数字を持つ中間的な値。桁の取り違えを検出する。 */
    MIXED_PLUS(Value.MIXED, 0xC),
    /** 符号なし項目の正号 {@code F}。 */
    ONE_SIGN_F(Value.ONE, 0xF),
    /** 受理される正号 {@code A}。 */
    ONE_SIGN_A(Value.ONE, 0xA),
    /** 受理される負号 {@code B}。 */
    ONE_SIGN_B(Value.ONE, 0xB);

    private enum Value {
        ZERO, ONE, MAX, MAX_MINUS_ONE, MIXED
    }

    private final Value value;
    private final int signNibble;

    OperandClass(Value value, int signNibble) {
        this.value = value;
        this.signNibble = signNibble;
    }

    /**
     * この分類に対応するパック10進のバイト列を作る。
     *
     * @param byteLength 項目のバイト長。数字ニブルの数は {@code 2 * byteLength - 1} になる
     */
    public byte[] bytes(int byteLength) {
        int digits = byteLength * 2 - 1;
        String s = switch (value) {
            case ZERO -> "0".repeat(digits);
            case ONE -> "0".repeat(digits - 1) + "1";
            case MAX -> "9".repeat(digits);
            case MAX_MINUS_ONE -> digits == 1 ? "8" : "9".repeat(digits - 1) + "8";
            case MIXED -> mixed(digits);
        };
        return pack(s, signNibble);
    }

    /** {@code 1234512345...} のように桁ごとに異なる数字を並べる。 */
    private static String mixed(int digits) {
        StringBuilder sb = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) {
            sb.append((char) ('1' + (i % 9)));
        }
        return sb.toString();
    }

    private static byte[] pack(String digits, int signNibble) {
        int byteLength = digits.length() / 2 + 1;
        byte[] out = new byte[byteLength];
        for (int i = 0; i < digits.length(); i++) {
            int nibble = digits.charAt(i) - '0';
            int index = i / 2;
            if (i % 2 == 0) {
                out[index] |= (byte) (nibble << 4);
            } else {
                out[index] |= (byte) nibble;
            }
        }
        out[byteLength - 1] |= (byte) signNibble;
        return out;
    }
}
