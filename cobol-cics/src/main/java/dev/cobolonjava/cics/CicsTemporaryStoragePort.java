package dev.cobolonjava.cics;

/**
 * 一時記憶 (temporary storage) のキュー (暫定判断 P-137)。region の構成が持ち、task どうしで分け合う。
 *
 * <p>キューの名前は 16 byte (QNAME の長さ) に空白を詰めた byte 列で表す。QUEUE の 8 byte の名前は、後ろに
 * 空白を足した 16 byte の名前と同じキューを指すものとする。キューは回復不能で、SYNCPOINT ROLLBACK は戻さない。
 *
 * <p>返す条件の RESP2 は、WRITEQ TS / READQ TS / DELETEQ TS の頁が値を示さないので 0 とする。
 */
public interface CicsTemporaryStoragePort {

    /** キューの名前の長さ (QNAME)。 */
    int NAME_LENGTH = 16;
    /** 1 つの item の最大の長さ。WRITEQ TS の頁による。 */
    int MAX_ITEM_LENGTH = 32763;
    /** 1 つのキューの item の最大の数。WRITEQ TS の頁による。 */
    int MAX_ITEMS = 32767;

    record Result(int response, int response2) {
    }

    /** 書いた結果。item は書いた item の番号。 */
    record Written(int response, int response2, int item) {
    }

    /** 読んだ結果。NORMAL でなければ data は null。numberOfItems は読んだときのキューの item の数。 */
    record Read(int response, int response2, byte[] data, int numberOfItems) {
    }

    /** 新しい item を足す。キューが無ければ作る。 */
    Written write(byte[] name, byte[] data);

    /** 番号の item を書き換える (WRITEQ TS REWRITE)。 */
    Result rewrite(byte[] name, int item, byte[] data);

    /**
     * item を読む。
     *
     * @param next true なら、キューで直前に読まれた item の次 (READQ TS NEXT)。false なら item の番号で読む
     */
    Read read(byte[] name, int item, boolean next);

    Result delete(byte[] name);

    /** 1 つの JVM の中で task どうしが分け合うキュー。 */
    static CicsTemporaryStoragePort inMemory() {
        return new InMemoryCicsTemporaryStorage();
    }
}
