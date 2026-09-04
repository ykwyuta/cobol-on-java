package dev.cobolonjava.runtime.file;

/**
 * 開かれたデータセット (要件 FR-100, FR-102)。
 *
 * <p>編成が違えば<b>レコードの探し方が違う</b>。番号で引く操作も鍵で引く操作も、
 * それを持つ編成にしかない。したがってここに置くのは、どの編成にもある操作だけである。
 *
 * <p>どの編成かは翻訳時に分かっている。生成コードは編成に合った入口を呼ぶので、
 * 実行時に編成で分岐することはない。
 */
public sealed interface DataSet permits SequentialDataSet, RelativeDataSet {

    /** レコードの切れ目とコードページ。 */
    DataSetAttributes attributes();

    /** 開いているかどうか。 */
    boolean isOpen();

    /** 直前に読み書きしたレコードの長さ。 */
    int lastLength();

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
