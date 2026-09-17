package dev.cobolonjava.ims.store;

import dev.cobolonjava.runtime.program.ProgramContext;

/**
 * 置き場を別のモジュールから差し込む口 ({@link java.util.ServiceLoader} で引く)。
 *
 * <p>{@code cobol-ims} は JDBC の型を持たない (設計 78 §2.2)。RDB の置き場は {@code cobol-ims-rdb} が差し込む。
 */
public interface DatabaseStoreProvider {

    /**
     * 置き場を開く。
     *
     * @return この差し込みを使う構成になっていなければ {@code null}
     */
    DatabaseStore open(ProgramContext context);
}
