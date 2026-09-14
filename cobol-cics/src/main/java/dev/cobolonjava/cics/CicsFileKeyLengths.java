package dev.cobolonjava.cics;

import java.util.OptionalInt;

/**
 * file 定義の鍵の長さを答えられる {@link CicsFilePort}。
 *
 * <p>{@code KEYLENGTH} を省いた {@code WRITE} は、RIDFLD の域の先頭から定義の鍵の長さぶんを鍵にする。
 */
public interface CicsFileKeyLengths {

    OptionalInt keyLengthOf(String file);

    default java.util.Optional<Integer> keyLength(String file) {
        OptionalInt value = keyLengthOf(file);
        return value.isPresent() ? java.util.Optional.of(value.getAsInt()) : java.util.Optional.empty();
    }
}
