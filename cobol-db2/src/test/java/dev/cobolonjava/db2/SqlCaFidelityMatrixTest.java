package dev.cobolonjava.db2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** SQLCAの未取得fieldを推測で埋めない契約。 */
@Tag("V1")
class SqlCaFidelityMatrixTest {

    @Test
    @DisplayName("基本結果はSQLCODEとSQLSTATEだけをexact、row countだけをderivedとする")
    void basicOutcomeClassifiesEveryField() {
        SqlOutcome outcome = SqlOutcome.success(12);

        assertEquals(SqlCaField.values().length, outcome.fidelity().fields().size());
        assertEquals(DiagnosticFidelity.EXACT,
                outcome.fidelity().fidelity(SqlCaField.SQLCODE));
        assertEquals(DiagnosticFidelity.EXACT,
                outcome.fidelity().fidelity(SqlCaField.SQLSTATE));
        assertEquals(DiagnosticFidelity.DERIVED,
                outcome.fidelity().fidelity(SqlCaField.SQLERRD_3));
        assertEquals(DiagnosticFidelity.UNAVAILABLE,
                outcome.fidelity().fidelity(SqlCaField.SQLERRMC));
        assertEquals(DiagnosticFidelity.UNAVAILABLE,
                outcome.fidelity().fidelity(SqlCaField.SQLWARN_0));
    }

    @Test
    @DisplayName("一つでもfield分類が欠ける不完全なmatrixを拒否する")
    void rejectsIncompleteMatrix() {
        assertThrows(IllegalArgumentException.class,
                () -> new SqlCaFidelityMatrix(Map.of(
                        SqlCaField.SQLCODE, DiagnosticFidelity.EXACT)));
    }

    @Test
    @DisplayName("入力mapを書き換えてもmatrixは変わらない")
    void isDefensivelyCopied() {
        EnumMap<SqlCaField, DiagnosticFidelity> values =
                new EnumMap<>(SqlCaField.class);
        for (SqlCaField field : SqlCaField.values()) {
            values.put(field, DiagnosticFidelity.UNAVAILABLE);
        }
        SqlCaFidelityMatrix matrix = new SqlCaFidelityMatrix(values);
        values.put(SqlCaField.SQLCODE, DiagnosticFidelity.EXACT);

        assertEquals(DiagnosticFidelity.UNAVAILABLE,
                matrix.fidelity(SqlCaField.SQLCODE));
        assertThrows(UnsupportedOperationException.class,
                () -> matrix.fields().put(SqlCaField.SQLCODE, DiagnosticFidelity.EXACT));
    }
}
