package dev.cobolonjava.cics;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;

/**
 * file control (暫定判断 P-131、P-136、P-147)。region の構成が持ち、task どうしで分け合う。
 *
 * <p>返す RESP / RESP2 は CICS TS の公開文書で数を確かめたものだけである。数の option を書かなかったことは
 * {@link #ABSENT} で表す。
 */
public interface CicsFilePort {

    /** KEYLENGTH や TOKEN などの数の option を書かなかったことを表す値。 */
    int ABSENT = Integer.MIN_VALUE;

    /** RIDFLD の形。 */
    enum Addressing {
        /** KSDS の鍵。BDAM の file では {@link #BLOCK} と読む */
        KEY,
        /** RRDS の 4 byte の相対レコード番号 */
        RRN,
        /** 4 byte の RBA */
        RBA,
        /** 8 byte の RBA */
        XRBA,
        /** BDAM の 4 byte の相対 block 番号 (0 起点) */
        BLOCK,
        /** BDAM の相対 block 番号と record の鍵 */
        DEBKEY,
        /** BDAM の相対 block 番号と、block の中の 4 byte の相対 record 番号 (0 起点) */
        DEBREC
    }

    /**
     * 読みの option。
     *
     * @param noSuspend  他の task が持つ record を待たず RECORDBUSY にする
     * @param consistent CONSISTENT / REPEATABLE。他の task が更新のために持つ record を放すまで待つ
     * @param token      UPDATE の record を token で持ち、token を返す
     */
    record Access(boolean noSuspend, boolean consistent, boolean token) {
        public static final Access DEFAULT = new Access(false, false, false);
    }

    /** 応答だけを返す命令の結果。 */
    record Result(int response, int response2) {
    }

    /**
     * 読んだ record。NORMAL でなければ data と id は null。id は命令の RIDFLD の形の識別
     * (KSDS の鍵、RRDS の相対レコード番号、RBA / XRBA、BDAM の block の参照)。token は TOKEN で持ったときの値。
     */
    record Found(int response, int response2, byte[] data, byte[] id, int token) {
        public Found(int response, int response2, byte[] data, byte[] id) {
            this(response, response2, data, id, 0);
        }
    }

    /** DELETE の結果。count は消した record の数 (NUMREC)。 */
    record Deleted(int response, int response2, int count) {
    }

    /**
     * WRITE。ridfld は addressing の形。ESDS では RBA / XRBA を書き込み先に決め、結果の id に返す。
     * MASSINSERT は普通の WRITE と同じに直ちに書く (設計 85 §5.3)。
     */
    Found write(CicsTaskId task, String file, byte[] ridfld, Addressing addressing, byte[] record, boolean massInsert);

    /** READ。maxWait は他の task が record を更新のために持っているときに待てる長さ (null なら限りなく待つ)。 */
    Found read(CicsTaskId task, String file, byte[] ridfld, Addressing addressing, int keyLength, boolean generic,
               boolean gteq, boolean update, Access access, Duration maxWait);

    /** REWRITE。token が {@link #ABSENT} でなければ、その token で持つ record を書き換える。 */
    Result rewrite(CicsTaskId task, String file, byte[] record, int token);

    /** DELETE。ridfld が null なら直前の READ UPDATE (token があればその token) の record を消す。 */
    Deleted delete(CicsTaskId task, String file, byte[] ridfld, Addressing addressing, int keyLength, boolean generic,
                   int token, boolean noSuspend, Duration maxWait);

    Result unlock(CicsTaskId task, String file, int token);

    Result startBrowse(CicsTaskId task, String file, int reqid, byte[] ridfld, Addressing addressing, int keyLength,
                       boolean generic, boolean equal);

    /** READNEXT。update なら読んだ record を token で持つ。 */
    Found readNext(CicsTaskId task, String file, int reqid, byte[] ridfld, Addressing addressing, int keyLength,
                   boolean update, Access access, Duration maxWait);

    Found readPrevious(CicsTaskId task, String file, int reqid, byte[] ridfld, Addressing addressing, int keyLength,
                       boolean update, Access access, Duration maxWait);

    Result endBrowse(CicsTaskId task, String file, int reqid);

    Result resetBrowse(CicsTaskId task, String file, int reqid, byte[] ridfld, Addressing addressing, int keyLength,
                       boolean generic, boolean equal);

    /** SYNCPOINT で、更新のために持つ record を返し、browse を終える。 */
    void releaseUnitOfWork(CicsTaskId task);

    /** task の終わりに、持っている record と browse をすべて返す。 */
    void releaseTask(CicsTaskId task);

    /** KSDS の鍵の長さ。定義が無いか KSDS でなければ空。 */
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
            public Found write(CicsTaskId task, String file, byte[] ridfld, Addressing addressing, byte[] record,
                               boolean massInsert) {
                return notFound;
            }

            @Override
            public Found read(CicsTaskId task, String file, byte[] ridfld, Addressing addressing, int keyLength,
                              boolean generic, boolean gteq, boolean update, Access access, Duration maxWait) {
                return notFound;
            }

            @Override
            public Result rewrite(CicsTaskId task, String file, byte[] record, int token) {
                return missing;
            }

            @Override
            public Deleted delete(CicsTaskId task, String file, byte[] ridfld, Addressing addressing, int keyLength,
                                  boolean generic, int token, boolean noSuspend, Duration maxWait) {
                return new Deleted(CicsResponseCode.FILENOTFOUND, 1, 0);
            }

            @Override
            public Result unlock(CicsTaskId task, String file, int token) {
                return missing;
            }

            @Override
            public Result startBrowse(CicsTaskId task, String file, int reqid, byte[] ridfld, Addressing addressing,
                                      int keyLength, boolean generic, boolean equal) {
                return missing;
            }

            @Override
            public Found readNext(CicsTaskId task, String file, int reqid, byte[] ridfld, Addressing addressing,
                                  int keyLength, boolean update, Access access, Duration maxWait) {
                return notFound;
            }

            @Override
            public Found readPrevious(CicsTaskId task, String file, int reqid, byte[] ridfld, Addressing addressing,
                                      int keyLength, boolean update, Access access, Duration maxWait) {
                return notFound;
            }

            @Override
            public Result endBrowse(CicsTaskId task, String file, int reqid) {
                return missing;
            }

            @Override
            public Result resetBrowse(CicsTaskId task, String file, int reqid, byte[] ridfld, Addressing addressing,
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

    /** 定義した file を、バッチと同じ索引編成・相対レコード編成・順編成のデータセットとして持つ。 */
    static CicsFilePort dataSets(List<CicsFileDefinition> definitions) {
        return new CicsFileControl(definitions);
    }
}
