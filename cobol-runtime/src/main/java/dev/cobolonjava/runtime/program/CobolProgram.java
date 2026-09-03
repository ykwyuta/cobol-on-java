package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.storage.Storage;

/**
 * 翻訳された COBOL プログラム 1 個 (方針 ARC-3, ARC-7)。
 *
 * <p>生成されたクラスはこれを実装する。<b>意味論はランタイムが持つ</b>ため、
 * 生成コードがやるのは記憶域の位置を決めて {@link Ops} を呼ぶことだけである。
 * これにより、コード生成の誤りと意味論の誤りを切り分けられる。
 */
public interface CobolProgram {

    /** 作業場所の初期イメージ。{@code VALUE} 句から翻訳時に決まる。 */
    byte[] initialStorage();

    /** 手続き部を実行する。 */
    void run(Storage storage);

    /** 初期イメージから記憶域を作って実行する。 */
    default Storage runFresh() {
        Storage storage = Storage.wrap(initialStorage());
        run(storage);
        return storage;
    }
}
