package dev.cobolonjava.db2;

import dev.cobolonjava.runtime.storage.DataView;
import java.util.List;
import java.util.Objects;

/** COBOL storage上のhost variableを順序付きで渡す低レベルbinding。 */
public record SqlBindings(List<DataView> values) {

    public static final SqlBindings NONE = new SqlBindings(List.of());

    public SqlBindings {
        values = List.copyOf(values);
        values.forEach(value -> Objects.requireNonNull(value, "binding value"));
    }
}
