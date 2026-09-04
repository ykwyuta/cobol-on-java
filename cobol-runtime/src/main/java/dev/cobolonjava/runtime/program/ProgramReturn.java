package dev.cobolonjava.runtime.program;

/**
 * {@code GOBACK} と手続き部の終わりによる<b>呼んだ側への復帰</b> (要件 FR-067)。
 *
 * <p>{@link ProgramStop} との違いは<b>どこまで抜けるか</b>である。{@code STOP RUN} は
 * 実行そのものを終える。{@code GOBACK} は 1 つ上へ戻るだけであり、副プログラムから
 * 投げれば呼んだ側が受け止めて続きを実行する。主プログラムなら実行の終わりになる。
 *
 * <p>段落が別々のメソッドとして生成されるため、単に戻るだけでは 1 つ上の段落へ
 * 制御が返ってしまう。これで一気に抜ける。誤りではない。
 */
public final class ProgramReturn extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ProgramReturn() {
        // 積みの記録は要らない。誤りではないためである
        super(null, null, false, false);
    }
}
