package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** ログとport間の相関に使う不透明なtask ID。 */
public record CicsTaskId(String value) {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    public CicsTaskId {
        Objects.requireNonNull(value, "value");
        if (!SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException("task ID has an unsupported format");
        }
    }

    public static CicsTaskId create() {
        return new CicsTaskId(UUID.randomUUID().toString());
    }
}
