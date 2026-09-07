package dev.cobolonjava.job;

import java.nio.file.Path;
import java.util.List;

/**
 * DD 名が指す先 (要件 FR-130, FR-133)。
 *
 * <p>ホストでは JCL の {@code DD} 文が、プログラムの中の DD 名と実際のデータセットを
 * 結び付ける。<b>プログラムは場所を知らない</b>のがその要点であり、同じプログラムを
 * 別のデータへ向けられる。
 */
public sealed interface DdTarget {

    /**
     * 名前で指したデータセット。{@code DSN=} にあたる。
     *
     * <p>場所ではなく<b>名前</b>を持つのが要点である。どのボリュームにあるかを引くのは
     * 目録の仕事であり (要件 FR-131)、ジョブの記述はそれを知らない。名前と場所を
     * 同じものにすると、目録に載せる・外すという操作が表せなくなる。
     *
     * @param member      区分データセットのメンバ名 (要件 FR-113)。{@code DSN=ライブラリ(メンバ)}
     *                    と書いたときのメンバである。書かなければ {@code null}
     * @param serial      {@code VOL=SER=} に書いたボリューム通し番号。書けば<b>目録を通さず</b>
     *                    置き場を直に見る。目録に載っていないデータセットへ届く唯一の手である。
     *                    書かなければ {@code null}
     * @param disposition {@code DISP=}。ステップの前と後の両方を決める
     */
    record DataSet(String name, String member, String serial, Disposition disposition)
            implements DdTarget {

        /** 処置を書かない割当。宣言的形式はこちらを使う。 */
        public DataSet(String name) {
            this(name, null, null, Disposition.UNSPECIFIED);
        }

        /** 順編成のデータセットを、目録から引いて使う割当。 */
        public DataSet(String name, Disposition disposition) {
            this(name, null, null, disposition);
        }

        /** 区分データセットのメンバを指しているか (要件 FR-113)。 */
        public boolean partitioned() {
            return member != null;
        }
    }

    /**
     * 一時データセット。JCL の {@code DSN=&&名前} にあたる。
     *
     * <p>ジョブの間だけ存在し、<b>終われば消える</b>。ステップの間で受け渡す作業ファイルが
     * これであり、実資産のバッチではいちばんよく使われる形である。
     *
     * <p>場所を持たないのが要点である。置き場はジョブ実行が決めるので、同じジョブを
     * 同時に何本流しても<b>互いの作業ファイルを踏まない</b>。
     *
     * @param name 名前。ジョブの中でこの名前が同じものを指す
     */
    record Temporary(String name, Disposition disposition) implements DdTarget {

        /** 処置を書かない割当。 */
        public Temporary(String name) {
            this(name, Disposition.of(Disposition.Status.NEW));
        }
    }

    /**
     * 標準出力。{@code SYSOUT=} にあたる。
     *
     * <p>ホストでは書いたものがスプールへ溜まる。ここでは実行の標準出力へ流す。
     */
    record Sysout() implements DdTarget {
    }

    /**
     * 空。{@code DUMMY} にあたる。
     *
     * <p>読めば即座に終わりであり、書いたものは捨てられる。ステップを飛ばさずに
     * <b>入出力だけを無効にする</b>ための指定である。
     */
    record Dummy() implements DdTarget {
    }

    /**
     * ジョブの記述に埋め込んだデータ。{@code SYSIN DD *} にあたる。
     *
     * <p>中身は<b>すでにコードページで符号化されたバイト列</b>である。行の区切りも
     * そのコードページのものになる。
     */
    record Inline(byte[] data) implements DdTarget {

        public Inline {
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    /**
     * 連結。同じ DD 名に複数のデータセットを並べたものである。
     *
     * <p>読むときは<b>並べた順に continue して 1 つのファイルに見える</b>。
     */
    record Concatenation(List<DdTarget> parts) implements DdTarget {

        public Concatenation {
            parts = List.copyOf(parts);
        }
    }
}
