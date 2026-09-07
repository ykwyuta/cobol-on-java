package dev.cobolonjava.job;

/**
 * DD 割当 1 個 (要件 FR-130, FR-141)。
 *
 * @param name             プログラムが {@code ASSIGN TO} に書いた DD 名
 * @param target           実際の行き先
 * @param space            書ける大きさ (バイト)。{@code 0} なら限りなし
 * @param directoryBlocks  ディレクトリブロックの数。{@code 0} なら区分データセットではない
 */
public record DdAssignment(String name, DdTarget target, long space, int directoryBlocks) {

    /** 限りのない割当。大きさを言わない記述はこちらである。 */
    public static final long UNLIMITED = 0L;

    /**
     * 大きさを書かない割当。
     *
     * <p>大きさは<b>行き先とは別の話</b>である。同じデータセットでも、割り当てた場所を
     * どれだけ取ったかはジョブが決める。だから {@link DdTarget} ではなくここに持つ。
     */
    public DdAssignment(String name, DdTarget target) {
        this(name, target, UNLIMITED, 0);
    }

    /** ディレクトリブロックを言わない割当。順編成のデータセットはこちらである。 */
    public DdAssignment(String name, DdTarget target, long space) {
        this(name, target, space, 0);
    }

    /**
     * 新しく作るなら区分データセットになるか (要件 FR-113)。
     *
     * <p>ホストで新しいデータセットが区分になるのは、{@code SPACE=} の 3 つ目の数
     * (ディレクトリブロック) を書いたときである。メンバを名指せば当然そうだが、
     * <b>メンバを言わずにライブラリを作る</b>ことがあり ({@code IEBCOPY} の写し先が
     * これである)、そのときに拠れるのはこの数しかない。
     */
    public boolean partitioned() {
        return directoryBlocks > 0
                || (target instanceof DdTarget.DataSet dataSet && dataSet.partitioned());
    }
}
