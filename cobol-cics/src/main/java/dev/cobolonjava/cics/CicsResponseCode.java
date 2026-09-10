package dev.cobolonjava.cics;

import java.util.Locale;
import java.util.Objects;

/** 初期対応するCICS conditionのDFHRESP数値。 */
public final class CicsResponseCode {

    public static final int NORMAL = 0;
    public static final int PGMIDERR = 27;

    private CicsResponseCode() {
    }

    /** 現在保証するcondition名をCICS translatorのDFHRESP値へ変換する。 */
    public static int forCondition(String conditionName) {
        String normalized = Objects.requireNonNull(conditionName, "conditionName")
                .strip().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "NORMAL" -> NORMAL;
            case "PGMIDERR" -> PGMIDERR;
            default -> throw new IllegalArgumentException(
                    "unsupported CICS condition name: " + normalized);
        };
    }
}
