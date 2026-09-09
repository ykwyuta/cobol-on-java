package dev.cobolonjava.db2;

/** JDBC adapterがSQLCA変換前に保存する値非包含の診断。 */
public record SqlDiagnostic(
        int vendorCode,
        String sqlState,
        String phase,
        String messageId) {

    public SqlDiagnostic {
        if (sqlState == null || !sqlState.matches("[0-9A-Z]{5}")) {
            throw new IllegalArgumentException("SQLSTATE must be five uppercase characters");
        }
        if (phase == null || phase.isBlank() || messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("diagnostic phase and messageId are required");
        }
    }
}
