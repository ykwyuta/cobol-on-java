package dev.cobolonjava.oracle.run;

/**
 * Hercules の 1 回の実行結果。
 *
 * @param log          コンソール出力の全文
 * @param storage      読み出された記憶域
 * @param waitPswAddress 停止時の待機 PSW の命令アドレス。正常終了なら 0
 */
public record HerculesResult(String log, StorageDump storage, long waitPswAddress) {

    /**
     * プログラム割込みが起きたかどうか。
     *
     * <p>データ例外 (COBOL から見た {@code S0C7} の元) など、割込みが起きた場合は
     * 割込み新 PSW によって命令アドレス {@code 0xDEAD} の待機状態へ入る。
     */
    public boolean programCheck() {
        return waitPswAddress == dev.cobolonjava.oracle.script.HerculesCase.PROGRAM_CHECK_MARKER;
    }

    /** 正常に終了したかどうか。 */
    public boolean completed() {
        return waitPswAddress == 0;
    }

    public byte[] at(int address, int length) {
        return storage.at(address, length);
    }
}
