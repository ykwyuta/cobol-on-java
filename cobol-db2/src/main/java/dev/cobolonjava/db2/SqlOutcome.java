package dev.cobolonjava.db2;

import java.util.List;

/** SQLCODE、SQLSTATE、件数、warning chainを失わない中立実行結果。 */
public record SqlOutcome(
        int sqlCode,
        String sqlState,
        long rowCount,
        List<SqlDiagnostic> diagnostics,
        boolean databaseRolledBackUnitOfWork,
        SqlCaFidelityMatrix fidelity) {

    public SqlOutcome {
        if (sqlState == null || !sqlState.matches("[0-9A-Z]{5}")) {
            throw new IllegalArgumentException("SQLSTATE must be five uppercase characters");
        }
        if (rowCount < -1) {
            throw new IllegalArgumentException("rowCount must be -1 (unavailable) or non-negative");
        }
        diagnostics = List.copyOf(diagnostics);
        if (fidelity == null) {
            throw new IllegalArgumentException("SQLCA fidelity matrix is required");
        }
    }

    /** 初期adapter／fake向け。SQLCODE/SQLSTATEと任意のrow count以外を未提供にする。 */
    public SqlOutcome(int sqlCode, String sqlState, long rowCount,
                      List<SqlDiagnostic> diagnostics,
                      boolean databaseRolledBackUnitOfWork) {
        this(sqlCode, sqlState, rowCount, diagnostics, databaseRolledBackUnitOfWork,
                SqlCaFidelityMatrix.basic(rowCount >= 0));
    }

    public static SqlOutcome success(long rowCount) {
        return new SqlOutcome(0, "00000", rowCount, List.of(), false,
                SqlCaFidelityMatrix.basic(rowCount >= 0));
    }
}
