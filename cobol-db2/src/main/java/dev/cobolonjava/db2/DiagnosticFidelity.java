package dev.cobolonjava.db2;

/** JDBC観測値から各SQLCA fieldをどの確度で供給したか。 */
public enum DiagnosticFidelity {
    EXACT,
    DERIVED,
    UNAVAILABLE
}
