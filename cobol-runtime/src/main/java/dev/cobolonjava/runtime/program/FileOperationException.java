package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.abend.AbendCause;
import dev.cobolonjava.runtime.abend.AbendCode;
import dev.cobolonjava.runtime.file.FileStatus;

/**
 * {@code FILE STATUS} を書いていないファイルで異常が起きた (要件 FR-104, FR-141)。
 *
 * <p>黙って続けると<b>読めていないデータで処理が進む</b>。参照実装もメッセージを出して
 * 異常終了する。
 *
 * <h2>コードを決めるのはファイル状態コードである</h2>
 * <p>ここだけは<b>条件の中に対応表がある</b>。ほかの異常終了では例外の型そのものが条件を
 * 表すが、ファイルの異常は「どの入出力文が」ではなく「何が起きたか」がファイル状態コードに
 * 入っているためである。表を外に置くと、状態コードを足したときに直し忘れる。
 */
public final class FileOperationException extends RuntimeException implements AbendCause {

    private static final long serialVersionUID = 1L;

    private final String status;

    /**
     * ファイル状態コードから異常終了コードを決める。
     *
     * <p>装置の誤り ({@code 30}) はホストの {@code S001}、書ける範囲を越えたこと
     * ({@code 34}) は出力データセットを広げられなかったということで {@code S037} である。
     * それ以外は言語環境が検出した条件として {@code U4038} になる。開き方の誤りや
     * 無効鍵は装置の誤りではなく、<b>プログラムの誤り</b>だからである。
     */
    @Override
    public AbendCode abendCode() {
        return switch (status) {
            case FileStatus.IO_ERROR -> AbendCode.S001;
            case FileStatus.NO_SPACE -> AbendCode.S037;
            default -> AbendCode.U4038;
        };
    }

    /** そのファイルで立ったファイル状態コード。 */
    public String status() {
        return status;
    }

    public FileOperationException(String name, String status) {
        super("file " + name + " failed with status " + status);
        this.status = status;
    }
}
