package dev.cobolonjava.db2;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 一つのSQL結果について全SQLCA fieldの証拠レベルを明示する不変行列。 */
public record SqlCaFidelityMatrix(Map<SqlCaField, DiagnosticFidelity> fields) {

    public SqlCaFidelityMatrix {
        Objects.requireNonNull(fields, "fields");
        EnumMap<SqlCaField, DiagnosticFidelity> copy = new EnumMap<>(SqlCaField.class);
        copy.putAll(fields);
        if (copy.size() != SqlCaField.values().length) {
            throw new IllegalArgumentException(
                    "SQLCA fidelity must classify every field exactly once");
        }
        copy.forEach((field, fidelity) -> Objects.requireNonNull(
                fidelity, "fidelity for " + field));
        fields = Map.copyOf(copy);
    }

    public DiagnosticFidelity fidelity(SqlCaField field) {
        return fields.get(Objects.requireNonNull(field, "field"));
    }

    /** SQLCODE/SQLSTATEだけが直接分かり、row countだけを導出できる初期動的SQL結果。 */
    public static SqlCaFidelityMatrix basic(boolean rowCountAvailable) {
        EnumMap<SqlCaField, DiagnosticFidelity> fields = new EnumMap<>(SqlCaField.class);
        for (SqlCaField field : SqlCaField.values()) {
            fields.put(field, DiagnosticFidelity.UNAVAILABLE);
        }
        fields.put(SqlCaField.SQLCODE, DiagnosticFidelity.EXACT);
        fields.put(SqlCaField.SQLSTATE, DiagnosticFidelity.EXACT);
        if (rowCountAvailable) {
            fields.put(SqlCaField.SQLERRD_3, DiagnosticFidelity.DERIVED);
        }
        return new SqlCaFidelityMatrix(fields);
    }
}
