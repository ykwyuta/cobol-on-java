package dev.cobolonjava.db2;

import java.util.List;
import java.util.Objects;

/** 型・方向・null indicatorを伴うhost variableをSQL記載順に渡すbinding。 */
public record SqlBindings(List<SqlHostVariable> values) {

    public static final SqlBindings NONE = new SqlBindings(List.of());

    public SqlBindings {
        values = List.copyOf(values);
        values.forEach(value -> Objects.requireNonNull(value, "binding value"));
    }
}
