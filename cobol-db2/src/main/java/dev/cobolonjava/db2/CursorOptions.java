package dev.cobolonjava.db2;

import java.util.Objects;

/** JDBC型を含まないcursor holdabilityと更新能力。 */
public record CursorOptions(
        String cursorName,
        boolean withHold,
        CursorHoldStrategy holdStrategy,
        boolean scrollable,
        boolean sensitive,
        boolean forUpdate,
        boolean returnsLobLocator) {

    private static final CursorOptions NONE = new CursorOptions(
            "<none>", false, CursorHoldStrategy.NOT_HELD,
            false, false, false, false);

    public CursorOptions {
        if (cursorName == null || cursorName.isBlank()) {
            throw new IllegalArgumentException("cursorName must not be blank");
        }
        Objects.requireNonNull(holdStrategy, "holdStrategy");
        if (!withHold && holdStrategy != CursorHoldStrategy.NOT_HELD) {
            throw new IllegalArgumentException("a non-held cursor must use NOT_HELD");
        }
        if (withHold && holdStrategy == CursorHoldStrategy.NOT_HELD) {
            throw new IllegalArgumentException("a WITH HOLD cursor requires an explicit strategy");
        }
        if (holdStrategy == CursorHoldStrategy.PORTABLE_SPOOL
                && (scrollable || sensitive || forUpdate || returnsLobLocator)) {
            throw new IllegalArgumentException(
                    "PORTABLE_SPOOL cannot preserve scroll/sensitive/update/LOB cursor semantics");
        }
    }

    public static CursorOptions none() {
        return NONE;
    }
}
