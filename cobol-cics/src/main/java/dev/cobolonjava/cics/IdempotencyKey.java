package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.regex.Pattern;

/** 二重送信結果の相関に使う、ログへ出せる形式の不透明key。 */
public record IdempotencyKey(String value) {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.:-]{8,128}");

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
        if (!SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException("idempotency key has an unsupported format");
        }
    }
}
