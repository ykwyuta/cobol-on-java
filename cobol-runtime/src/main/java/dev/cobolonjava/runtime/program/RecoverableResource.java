package dev.cobolonjava.runtime.program;

/**
 * 実行単位の作業単位に加わる資源 (MQ の接続など、設計 88 §4.3)。
 *
 * <p>runtime は資源の中身を知らない。同期点 (CICS の {@code SYNCPOINT}) で {@link #commit} か {@link #backout} を、
 * 実行単位の終わりで {@link #end} を呼ぶだけである。CICS や MQ の型を runtime へ持ち込まないために、この形にした。
 */
public interface RecoverableResource {

    /** 同期点: 作業単位の変更を確定する。 */
    void commit();

    /** 同期点の取り消し: 作業単位の変更を捨てる。 */
    void backout();

    /**
     * 実行単位の終わり。正常に終われば確定、異常なら取り消し (z/OS の実行単位の終わりの暗黙の同期点)。
     * 開いたものを閉じる資源はここで閉じる。
     */
    default void end(boolean commit) {
        if (commit) {
            commit();
        } else {
            backout();
        }
    }
}
