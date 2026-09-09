package dev.cobolonjava.cics;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** 外部入力から安全に解決できる、正規化済みCICSトランザクションID。 */
public record TransId(String value) {

    private static final Pattern SUPPORTED = Pattern.compile("[A-Z0-9$#@]{1,4}");

    public TransId {
        Objects.requireNonNull(value, "value");
        value = value.strip().toUpperCase(Locale.ROOT);
        if (!SUPPORTED.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "TRANSID must contain 1 to 4 supported CICS name characters");
        }
    }

    public static TransId of(String value) {
        return new TransId(value);
    }
}
