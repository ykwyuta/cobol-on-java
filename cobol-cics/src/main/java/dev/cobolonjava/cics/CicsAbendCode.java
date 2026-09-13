package dev.cobolonjava.cics;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** ログとrollback理由へ安全に渡せる、正規化済みCICS transaction abend code。 */
public record CicsAbendCode(String value) {

    private static final Pattern SUPPORTED = Pattern.compile("[A-Z0-9$#@?]{1,4}");
    private static final CicsAbendCode UNSPECIFIED = new CicsAbendCode("????");

    public CicsAbendCode {
        Objects.requireNonNull(value, "value");
        value = value.strip().toUpperCase(Locale.ROOT);
        if (!SUPPORTED.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "ABCODE must contain 1 to 4 supported CICS code characters");
        }
    }

    public static CicsAbendCode of(String value) {
        return new CicsAbendCode(value);
    }

    public static CicsAbendCode unspecified() {
        return UNSPECIFIED;
    }
}
