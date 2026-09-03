package dev.cobolonjava.compiler.source;

import java.util.Optional;

/**
 * {@code COPY} が指すコピー句を解決する (要件 FR-090)。
 *
 * <p>探索の仕組みを差し替えられるようにしているのは、ホストのライブラリが
 * 区分データセットのメンバであり (要件 FR-113)、移行先ではディレクトリにも
 * オブジェクトストレージにもなりうるためである。
 */
@FunctionalInterface
public interface CopyBookResolver {

    /**
     * コピー句を解決する。
     *
     * @param textName    {@code COPY} が指す名前
     * @param libraryName {@code OF} / {@code IN} で指定されたライブラリ名。指定がなければ {@code null}
     */
    Optional<CopyBook> resolve(String textName, String libraryName);
}
