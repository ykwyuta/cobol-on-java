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
     * 実ファイル。{@code DSN=} にあたる。
     *
     * @param disposition {@code DISP=}。ステップの前と後の両方を決める
     */
    record DataSet(Path path, Disposition disposition) implements DdTarget {

        /** 処置を書かない割当。宣言的形式はこちらを使う。 */
        public DataSet(Path path) {
            this(path, Disposition.UNSPECIFIED);
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
