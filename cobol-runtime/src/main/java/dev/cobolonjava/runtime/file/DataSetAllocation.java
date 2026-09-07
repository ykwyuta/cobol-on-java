package dev.cobolonjava.runtime.file;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 割当てがデータセットへ渡すこと (要件 FR-113, FR-141)。
 *
 * <p>プログラムには見えないことである。取った領域の大きさも、指しているのが区分データセットの
 * メンバかどうかも、<b>ジョブが決めてプログラムは知らない</b>。それでも入出力文の結果は
 * 変わるので、データセットの側へ渡さなければならない。
 *
 * <h2>編成が違っても同じ検査である</h2>
 * <p>「割り当てた領域を使い切ったら書けない」「無いメンバは開けない」「区分データセットそのものは
 * 開けない」の 3 つは、レコードの探し方とは関わりがない。順編成にだけ実装しておくと、
 * 相対編成のジョブを流したときにだけ実機と食い違う — <b>どの編成かで実機との合い方が変わる</b>
 * というのがいちばん困る形である。したがってここに 1 つ置き、3 つの編成から使う。
 *
 * <p>越えたときに立つコードだけは編成で違う。順編成は {@code 34}、鍵で引く編成は {@code 24}
 * である。それは呼ぶ側が決める。
 */
public final class DataSetAllocation {

    /** ジョブが割り当てた領域の大きさ (バイト)。{@code 0} は限りがないことを表す。 */
    private long limit;
    /** 区分データセットのメンバを指しているか。 */
    private boolean member;

    /**
     * 書ける大きさに限りを設ける (要件 FR-141)。
     *
     * <p>JCL の {@code SPACE=} である。ホストでは<b>あらかじめ場所を取ってから書く</b>ので、
     * 取った分を使い切れば書けなくなる。限りを設けないと、実機では止まるジョブが
     * ここでは通ってしまう。
     *
     * @param bytes 書ける大きさ。{@code 0} なら限りなし
     */
    void limit(long bytes) {
        this.limit = bytes;
    }

    /**
     * 区分データセットのメンバであると告げる (要件 FR-113)。
     *
     * <p>これを知っているのは割当てだけである。パスを見ても分からない — メンバはディレクトリの
     * 下のファイルだが、順編成のデータセットも置き場の下のファイルだからである。
     */
    void member(boolean value) {
        this.member = value;
    }

    /**
     * 開く前の検査 (要件 FR-113)。
     *
     * <p>開けないことには 2 通りある。<b>受け止め手のあるもの</b>は状態コードで返し、
     * <b>ないもの</b>は {@link DataSetOpenException} で止める。無いファイルは前者
     * ({@code 35}) だが、無いメンバは後者である — 割当てが通っている以上「データセットが無い」
     * ではなく、綴りを間違えたメンバ名で 0 件処理して正常終了されては困る。
     *
     * @param optional {@code SELECT OPTIONAL} と書かれているか
     * @return 開けないなら状態コード、進めてよいなら {@code null}
     */
    String opening(Path path, OpenMode requested, boolean optional) {
        return opening(path, requested, optional, member);
    }

    /**
     * 開く前の検査。割当ての覚えを外から渡す形である。
     *
     * <p>ジョブのユーティリティが要る。あれらはデータセットを開かずに生バイトを読むが、
     * <b>開けないものは読めない</b>ことに変わりはない。同じ検査を通さないと、無いメンバを
     * 空として写して正常終了してしまう (暫定判断 P-053)。
     *
     * @param member 区分データセットのメンバを指しているか
     */
    public static String opening(Path path, OpenMode requested, boolean optional,
                                 boolean member) {
        if (Files.isDirectory(path)) {
            // 区分データセットそのものである。どのメンバを読むのか決まっていない
            throw new DataSetOpenException("a partitioned data set is opened by member", path);
        }
        if (Files.isReadable(path)) {
            return null;
        }
        // 作りにいく開き方では、無いことは差し支えない。
        // プログラムが SELECT OPTIONAL と書いているときも同じである (暫定判断 P-057)
        if (requested == OpenMode.OUTPUT || optional) {
            return null;
        }
        if (member && requested != OpenMode.EXTEND) {
            // データセットはある。無いのはメンバであり、受け止め手はない
            throw new DataSetOpenException("member not found", path);
        }
        return FileStatus.NOT_FOUND;
    }

    /**
     * 書き足したら限りを越えるか (要件 FR-141)。
     *
     * @param occupied いま占めている大きさ
     * @param adding   書き足す大きさ
     */
    boolean exceeded(long occupied, long adding) {
        return limit > 0 && occupied + adding > limit;
    }
}
