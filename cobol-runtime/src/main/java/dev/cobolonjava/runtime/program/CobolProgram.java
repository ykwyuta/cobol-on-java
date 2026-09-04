package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;

/**
 * 翻訳された COBOL プログラム 1 個 (方針 ARC-3, ARC-7)。
 *
 * <p>生成されたクラスはこれを実装する。<b>意味論はランタイムが持つ</b>ため、
 * 生成コードがやるのは記憶域の位置を決めて {@link Ops} を呼ぶことだけである。
 * これにより、コード生成の誤りと意味論の誤りを切り分けられる。
 */
public interface CobolProgram {

    /** 引数のない呼び出し。 */
    DataView[] NO_ARGUMENTS = new DataView[0];

    /** 作業場所の初期イメージ。{@code VALUE} 句から翻訳時に決まる。 */
    byte[] initialStorage();

    /**
     * 手続き部を実行する。
     *
     * <p>連絡節の項目は<b>記憶域を持たない</b>。{@code PROCEDURE DIVISION USING} に
     * 並べた順で {@code arguments} の要素に対応し、実体は呼ぶ側にある。
     * 参照渡しであるから、書き換えは呼ぶ側から即座に見える。
     *
     * @param context   {@code DISPLAY} の行き先など、外へ触れるための入口
     * @param arguments 呼ぶ側から渡された領域。{@code USING} の順に並ぶ
     */
    void run(Storage storage, ProgramContext context, DataView[] arguments);

    /** 引数を取らない実行。 */
    default void run(Storage storage, ProgramContext context) {
        run(storage, context, NO_ARGUMENTS);
    }

    /**
     * 初期イメージから記憶域を作って実行する。
     *
     * <p>{@code STOP RUN} と {@code GOBACK} はここで受け止める。
     */
    default Storage runFresh(ProgramContext context, DataView... arguments) {
        Storage storage = Storage.wrap(initialStorage());
        try {
            run(storage, context, arguments);
        } catch (ProgramStop stop) {
            // 実行が終わっただけであり、誤りではない
        }
        return storage;
    }

    /** 出力を端末へ出して実行する。 */
    default Storage runFresh() {
        return runFresh(ProgramContext.standard());
    }
}
