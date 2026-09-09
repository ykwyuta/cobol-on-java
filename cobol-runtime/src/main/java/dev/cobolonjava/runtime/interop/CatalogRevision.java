package dev.cobolonjava.runtime.interop;

import java.util.Objects;

/** 一つの実行単位が固定して参照する不変プログラムカタログの版。 */
public record CatalogRevision(String value) {

    public CatalogRevision {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("catalog revision must not be blank");
        }
    }
}
