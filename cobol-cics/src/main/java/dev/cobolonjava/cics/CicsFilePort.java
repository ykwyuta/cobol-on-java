package dev.cobolonjava.cics;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;

/**
 * file control (暫定判断 P-131、P-136)。region の構成が持ち、task どうしで分け合う。
 *
 * <p>返す RESP / RESP2 は CICS TS の公開文書で数を確かめたものだけである。数の option を書かなかったことは
 * {@link #ABSENT} で表す。
 */
public interface CicsFilePort {

    /** KEYLENGTH などの数の option を書かなかったことを表す値。 */
    int ABSENT = Integer.MIN_VALUE;

    /** 応答だけを返す命令の結果。 */
    record Result(int response, int response2) {
    }

    /** 読んだ record。NORMAL でなければ data と id は null。id は KSDS の鍵か、RRDS の 4 byte の相対レコード番号。 */
    record Found(int response, int response2, byte[] data, byte[] id) {
    }

    /** DELETE の結果。count は消した record の数 (NUMREC)。 */
    record Deleted(int response, int response2, int count) {
    }

    /** WRITE。ridfld は KSDS なら鍵、RRDS なら 4 byte の相対レコード番号。 */
    Result write(CicsTaskId task, String file, byte[] ridfld, boolean rrn, byte[] record);

    /** READ。maxWait は他の task が record を更新のために持っているときに待てる長さ (null なら限りなく待つ)。 */
    Found read(CicsTaskId task, String file, byte[] ridfld, boolean rrn, int keyLength, boolean generic,
               boolean gteq, boolean update, Duration maxWait);

    Result rewrite(CicsTaskId task, String file, byte[] record);

    /** DELETE。ridfld が null なら直前の READ UPDATE の record を消す。 */
    Deleted delete(CicsTaskId task, String file, byte[] ridfld, boolean rrn, int keyLength, boolean generic,
                   Duration maxWait);

    Result unlock(CicsTaskId task, String file);

    Result startBrowse(CicsTaskId task, String file, int reqid, byte[] ridfld, boolean rrn, int keyLength,
                       boolean generic, boolean equal);

    Found readNext(CicsTaskId task, String file, int reqid, byte[] ridfld, boolean rrn, int keyLength);

    Found readPrevious(CicsTaskId task, String file, int reqid, byte[] ridfld, boolean rrn, int keyLength);

    Result endBrowse(CicsTaskId task, String file, int reqid);

    Result resetBrowse(CicsTaskId task, String file, int reqid, byte[] ridfld, boolean rrn, int keyLength,
                       boolean generic, boolean equal);

    /** SYNCPOINT で、更新のために持つ record を返し、browse を終える。 */
    void releaseUnitOfWork(CicsTaskId task);

    /** task の終わりに、持っている record と browse をすべて返す。 */
    void releaseTask(CicsTaskId task);

    /** KSDS の鍵の長さ。定義が無いか RRDS なら空。 */
    default OptionalInt keyLengthOf(String file) {
        return OptionalInt.empty();
    }

    /** 可変長の定義か。 */
    default boolean variableLength(String file) {
        return false;
    }

    /** file を 1 つも定義していない region。どの名前も FILENOTFOUND (RESP2 1) になる。 */
    static CicsFilePort none() {
        Result missing = new Result(CicsResponseCode.FILENOTFOUND, 1);
        Found notFound = new Found(CicsResponseCode.FILENOTFOUND, 1, null, null);
        return new CicsFilePort() {
            @Override
            public Result write(CicsTaskId task, String file, byte[] ridfld, boolean rrn, byte[] record) {
                return missing;
            }

            @Override
            public Found read(CicsTaskId task, String file, byte[] ridfld, boolean rrn, int keyLength,
                              boolean generic, boolean gteq, boolean update, Duration maxWait) {
                return notFound;
            }

            @Override
            public Result rewrite(CicsTaskId task, String file, byte[] record) {
                return missing;
            }

            @Override
            public Deleted delete(CicsTaskId task, String file, byte[] ridfld, boolean rrn, int keyLength,
                                  boolean generic, Duration maxWait) {
                return new Deleted(CicsResponseCode.FILENOTFOUND, 1, 0);
            }

            @Override
            public Result unlock(CicsTaskId task, String file) {
                return missing;
            }

            @Override
            public Result startBrowse(CicsTaskId task, String file, int reqid, byte[] ridfld, boolean rrn,
                                      int keyLength, boolean generic, boolean equal) {
                return missing;
            }

            @Override
            public Found readNext(CicsTaskId task, String file, int reqid, byte[] ridfld, boolean rrn, int keyLength) {
                return notFound;
            }

            @Override
            public Found readPrevious(CicsTaskId task, String file, int reqid, byte[] ridfld, boolean rrn,
                                      int keyLength) {
                return notFound;
            }

            @Override
            public Result endBrowse(CicsTaskId task, String file, int reqid) {
                return missing;
            }

            @Override
            public Result resetBrowse(CicsTaskId task, String file, int reqid, byte[] ridfld, boolean rrn,
                                      int keyLength, boolean generic, boolean equal) {
                return missing;
            }

            @Override
            public void releaseUnitOfWork(CicsTaskId task) {
            }

            @Override
            public void releaseTask(CicsTaskId task) {
            }
        };
    }

    /** 定義した file を、バッチと同じ索引編成・相対レコード編成のデータセットとして持つ。 */
    static CicsFilePort dataSets(List<CicsFileDefinition> definitions) {
        return new CicsFileControl(definitions);
    }
}
