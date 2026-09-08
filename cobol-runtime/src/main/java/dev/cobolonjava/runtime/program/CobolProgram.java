package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.abend.StorageMap;
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
     * <p>{@code STOP RUN} と {@code GOBACK} はどちらもここで受け止める。
     * 主プログラムでは<b>どちらも実行の終わり</b>だからである。副プログラムとして
     * 呼ばれたときだけ 2 つの違いが表に出る ({@link Ops#call})。
     */
    default Storage runFresh(ProgramContext context, DataView... arguments) {
        Storage storage = Storage.wrap(initialStorage());
        context.enter(name(), storage, storageMap(), this);
        try {
            run(storage, context, arguments);
        } catch (ProgramStop | ProgramReturn end) {
            // 実行が終わっただけであり、誤りではない
        }
        // 異常終了で抜けたときは積まれたまま残す。覚え書きが中身を見る (要件 FR-142)
        context.leave();
        return storage;
    }

    /**
     * プログラム名 (要件 FR-142)。
     *
     * <p>生成クラスの名前がそのままプログラム名である。異常終了の覚え書きに書く。
     */
    default String name() {
        return getClass().getSimpleName();
    }

    /**
     * 作業場所の割り付け (要件 FR-142)。
     *
     * <p>どのバイトがどの項目かを知っているのは翻訳の側である。生成クラスがこれを返し、
     * 異常終了の覚え書きが<b>項目名と値</b>を書けるようにする。手で書いたプログラムは
     * 空のままでよい。
     */
    default StorageMap storageMap() {
        return StorageMap.EMPTY;
    }

    /** {@code EXTERNAL} を書いた 01 レベルを 1 つも持たないプログラム。 */
    ExternalRegion[] NO_EXTERNAL_REGIONS = new ExternalRegion[0];

    /**
     * {@code EXTERNAL} を書いた 01 レベルの領域 (要件 FR-014)。
     *
     * <p>{@code EXTERNAL} と書いた 01 レベルの領域は<b>実行単位で 1 つ</b>である。
     * 同じ名前で書いたどのプログラムからも同じ中身が見える。
     *
     * <p>生成コードは項目をふつうに自分の記憶域へ割り付ける。実行単位の写しと
     * 突き合わせるのは<b>プログラムの境目</b>だけである — 入るとき、抜けるとき、
     * そして {@code CALL} の前後である。1 度に動くプログラムは 1 つなので、
     * これで「実体が 1 つある」のと見分けが付かない。
     *
     * @return 01 レベルごとの名前と、自分の記憶域での位置
     */
    default ExternalRegion[] externalRegions() {
        return NO_EXTERNAL_REGIONS;
    }

    /**
     * {@code EXTERNAL} の領域 1 個。
     *
     * @param name   データ名。実行単位でこの名前が同じものは同じ領域である
     * @param offset このプログラムの記憶域での位置
     * @param length バイト長
     */
    record ExternalRegion(String name, int offset, int length) {
    }

    /** 出力を端末へ出して実行する。 */
    default Storage runFresh() {
        return runFresh(ProgramContext.standard());
    }

    /**
     * 主プログラムとして実行し、{@code RETURN-CODE} を返す (要件 FR-084)。
     *
     * <p>生成クラスの {@code main} がこれを呼び、返った値をプロセスの終了コードにする。
     */
    default int runMain() {
        ProgramContext context = ProgramContext.standard();
        runFresh(context);
        return context.returnCode();
    }
}
