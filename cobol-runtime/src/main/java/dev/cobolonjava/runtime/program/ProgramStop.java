package dev.cobolonjava.runtime.program;

/**
 * {@code STOP RUN} と {@code GOBACK} による実行の終わり (要件 FR-061)。
 *
 * <p>誤りではない。段落が別々のメソッドとして生成されるため、単に戻るだけでは
 * 呼び出し元へ制御が返ってしまう。これで一気に抜ける。
 */
public final class ProgramStop extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ProgramStop() {
        // 積みの記録は要らない。誤りではないためである
        super(null, null, false, false);
    }
}
