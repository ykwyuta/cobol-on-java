package dev.cobolonjava.runtime.file;

import dev.cobolonjava.runtime.abend.AbendCause;
import dev.cobolonjava.runtime.abend.AbendCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * データセットそのものを読み書きできなかった (要件 FR-141)。
 *
 * <p>ファイル状態コードで表せる誤りとは<b>層が違う</b>。{@code 35} や {@code 30} は
 * 「この入出力文が失敗した」であり、プログラムは {@code FILE STATUS} で受け止められる。
 * こちらは置き場そのものへ届かなかったということであり、受け止めようがない。
 * ホストで装置の誤りが {@code S001} になるのと同じ位置付けである。
 *
 * <p>{@link UncheckedIOException} のままにしてあるのは、元の入出力例外を捨てないためである。
 */
public final class DataSetIoException extends UncheckedIOException implements AbendCause {

    private static final long serialVersionUID = 1L;

    public DataSetIoException(String what, Path path, IOException cause) {
        super("cannot " + what + " " + path, cause);
    }

    @Override
    public AbendCode abendCode() {
        return AbendCode.S001;
    }
}
