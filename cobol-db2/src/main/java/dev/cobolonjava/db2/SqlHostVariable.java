package dev.cobolonjava.db2;

import dev.cobolonjava.runtime.data.BinaryDecimal;
import dev.cobolonjava.runtime.data.PackedDecimal;
import dev.cobolonjava.runtime.data.ZonedDecimal;
import dev.cobolonjava.runtime.storage.DataView;
import java.util.Objects;

/** COBOL storage、物理型、方向、任意の2byte null indicatorを結ぶhost variable。 */
public record SqlHostVariable(
        DataView value,
        SqlBindingMode mode,
        SqlValueDescriptor descriptor,
        DataView nullIndicator) {

    public SqlHostVariable {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(descriptor, "descriptor");
        if (value.length() == 0) {
            throw new IllegalArgumentException("SQL host variable must not be empty");
        }
        if (nullIndicator != null && nullIndicator.length() != 2) {
            throw new IllegalArgumentException("SQL null indicator must occupy 2 bytes");
        }
        int expected = expectedLength(descriptor, value.length());
        if (value.length() != expected) {
            throw new IllegalArgumentException(
                    "SQL host variable length is " + value.length()
                            + ", expected " + expected + " for " + descriptor);
        }
    }

    public static SqlHostVariable input(
            DataView value, SqlValueDescriptor descriptor, DataView nullIndicator) {
        return new SqlHostVariable(value, SqlBindingMode.INPUT, descriptor, nullIndicator);
    }

    public static SqlHostVariable output(
            DataView value, SqlValueDescriptor descriptor, DataView nullIndicator) {
        return new SqlHostVariable(value, SqlBindingMode.OUTPUT, descriptor, nullIndicator);
    }

    private static int expectedLength(SqlValueDescriptor descriptor, int actual) {
        return switch (descriptor) {
            case SqlValueDescriptor.FixedCharacter ignored -> actual;
            case SqlValueDescriptor.PackedDecimal packed ->
                    PackedDecimal.byteLength(packed.digits());
            case SqlValueDescriptor.ZonedDecimal zoned ->
                    ZonedDecimal.byteLength(zoned.digits(), zoned.signPosition());
            case SqlValueDescriptor.BinaryInteger binary ->
                    BinaryDecimal.byteLength(binary.digits());
        };
    }
}
