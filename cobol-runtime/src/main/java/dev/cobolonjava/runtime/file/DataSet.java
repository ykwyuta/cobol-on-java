package dev.cobolonjava.runtime.file;

import dev.cobolonjava.runtime.abend.AbendCode;

/**
 * 開かれたデータセット (要件 FR-100, FR-102)。
 *
 * <p>編成が違えば<b>レコードの探し方が違う</b>。番号で引く操作も鍵で引く操作も、
 * それを持つ編成にしかない。したがってここに置くのは、どの編成にもある操作だけである。
 *
 * <p>どの編成かは翻訳時に分かっている。生成コードは編成に合った入口を呼ぶので、
 * 実行時に編成で分岐することはない。
 */
public sealed interface DataSet permits SequentialDataSet, KeyedDataSet {

    /** レコードの切れ目とコードページ。 */
    DataSetAttributes attributes();

    /** 開いているかどうか。 */
    boolean isOpen();

    /** いまの開き方。開いていなければ {@code null}。 */
    OpenMode mode();

    /** 直前に読み書きしたレコードの長さ。 */
    int lastLength();

    /**
     * 書ける大きさに限りを設ける (要件 FR-141)。
     *
     * <p>JCL の {@code SPACE=} である。<b>ジョブが決めてプログラムは知らない</b>ので、
     * 宣言からは来ない。編成によらず同じことなので、ここに置く。
     *
     * @param bytes 書ける大きさ。{@code 0} なら限りなし
     */
    void limit(long bytes);

    /**
     * 区分データセットのメンバであると告げる (要件 FR-113)。
     *
     * <p>これも割当てだけが知っている。パスを見ても分からない — メンバはディレクトリの下の
     * ファイルだが、順編成のデータセットも置き場の下のファイルだからである。
     */
    void member(boolean value);

    /** 二次割当を書いたと告げる。領域を使い切ったときの異常終了コードが変わる (暫定判断 P-052)。 */
    default void secondary(boolean value) {
    }

    /**
     * 領域を使い切ったときの異常終了コード。順編成だけが {@code x37} で止まる。鍵で引く編成は
     * 状態 {@code 24} であり、ここへは来ない。
     */
    default AbendCode spaceAbend() {
        return AbendCode.S037;
    }

    /**
     * 開く。
     *
     * @param optional {@code SELECT OPTIONAL} と書かれているか
     */
    String open(OpenMode requested, boolean optional);

    /** 次のレコードを読む。 */
    String read(byte[] into);

    /** レコードを書く。 */
    String write(byte[] from);

    /** 直前に読んだレコードを書き換える。 */
    String rewrite(byte[] from);

    /** 閉じる。書いていれば、ここでファイルへ流し込む。 */
    String close();
}
