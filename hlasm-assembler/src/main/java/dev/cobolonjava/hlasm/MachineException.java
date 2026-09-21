package dev.cobolonjava.hlasm;

/**
 * プログラム割込み。
 *
 * <p>実機では新 PSW へ切り替わるが、ここでは例外として上げて実行単位の異常終了に写す。
 * 割込みコードは z/Architecture Principles of Operation のものである。
 * COBOL 資産が見慣れた {@code S0C7} などの完了コードは、このコードから決まる。
 */
public final class MachineException extends RuntimeException {

    /** 演算例外。実行できない命令。 */
    public static final int OPERATION = 0x01;
    /** 特権演算例外。 */
    public static final int PRIVILEGED = 0x02;
    /** 実行例外。{@code EX} の対象が {@code EX} だった。 */
    public static final int EXECUTE = 0x03;
    /** 仕様例外。奇数のレジスタ、境界違反など。 */
    public static final int SPECIFICATION = 0x06;
    /** データ例外。10 進数として読めないバイト列。{@code S0C7} の元である。 */
    public static final int DATA = 0x07;
    /** 10 進オーバーフロー例外。 */
    public static final int DECIMAL_OVERFLOW = 0x0A;
    /** 10 進除算例外。 */
    public static final int DECIMAL_DIVIDE = 0x0B;
    /** 固定小数点除算例外。 */
    public static final int FIXED_DIVIDE = 0x09;
    /** アドレッシング例外。存在しない記憶域。 */
    public static final int ADDRESSING = 0x05;

    private final int code;
    private int instructionAddress = -1;

    public MachineException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** 割込みが起きた命令の番地。分かっていなければ -1。 */
    public int instructionAddress() {
        return instructionAddress;
    }

    void at(int address) {
        if (instructionAddress < 0) {
            instructionAddress = address;
        }
    }

    /** 実機の完了コードに対応する綴り ({@code S0C7} など)。 */
    public String systemCompletionCode() {
        return String.format("S0C%X", code & 0xF);
    }

    @Override
    public String getMessage() {
        return systemCompletionCode() + " " + super.getMessage()
                + (instructionAddress < 0 ? ""
                        : String.format(" at %08X", instructionAddress));
    }
}
