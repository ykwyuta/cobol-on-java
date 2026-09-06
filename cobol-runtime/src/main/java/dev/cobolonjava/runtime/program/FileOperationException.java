package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.abend.AbendCause;
import dev.cobolonjava.runtime.abend.AbendCode;

/**
 * {@code FILE STATUS} を書いていないファイルで異常が起きた (要件 FR-104)。
 *
 * <p>黙って続けると<b>読めていないデータで処理が進む</b>。参照実装もメッセージを出して
 * 異常終了する。
 */
public final class FileOperationException extends RuntimeException implements AbendCause {

    private static final long serialVersionUID = 1L;

    /**
     * 受け止め手のないファイルの異常は、言語環境が検出する条件である。
     *
     * <p>{@code S013} のような番号はデータセットの割当てが食い違ったときのもので、
     * ここへは来ない。割当ての食い違いはジョブ実行の段で止まる。
     */
    @Override
    public AbendCode abendCode() {
        return AbendCode.U4038;
    }

    public FileOperationException(String name, String status) {
        super("file " + name + " failed with status " + status);
    }
}
