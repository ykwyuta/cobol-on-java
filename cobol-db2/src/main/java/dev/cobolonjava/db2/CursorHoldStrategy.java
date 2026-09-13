package dev.cobolonjava.db2;

/** WITH HOLD cursorに明示する互換戦略。 */
public enum CursorHoldStrategy {
    NOT_HELD,
    REJECT_UNVERIFIED,
    PORTABLE_SPOOL,
    DB2_DRIVER_MANAGED_HOLD
}
