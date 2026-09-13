package dev.cobolonjava.db2;

import dev.cobolonjava.runtime.data.BinaryDecimal;
import dev.cobolonjava.runtime.data.NumProcMode;
import dev.cobolonjava.runtime.data.PackedDecimal;
import dev.cobolonjava.runtime.data.TruncMode;
import dev.cobolonjava.runtime.data.ZonedDecimal;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.Objects;

/** COBOL bytesとJDBCへ渡せるString / BigDecimalを相互変換する中立codec。 */
public final class SqlValueCodec {

    /** 出力をstorageへ反映する前に検証・符号化した値。 */
    public record EncodedOutput(byte[] valueBytes, byte[] indicatorBytes) {
        public EncodedOutput {
            valueBytes = valueBytes == null ? null : valueBytes.clone();
            indicatorBytes = indicatorBytes == null ? null : indicatorBytes.clone();
        }

        @Override
        public byte[] valueBytes() {
            return valueBytes == null ? null : valueBytes.clone();
        }

        @Override
        public byte[] indicatorBytes() {
            return indicatorBytes == null ? null : indicatorBytes.clone();
        }
    }

    public Object toJdbcValue(SqlHostVariable variable) {
        Objects.requireNonNull(variable, "variable");
        if (!variable.mode().isInput()) {
            throw new IllegalArgumentException("output-only host variable cannot be an input");
        }
        if (isNull(variable)) {
            return null;
        }
        byte[] bytes = variable.value().toByteArray();
        return switch (variable.descriptor()) {
            case SqlValueDescriptor.FixedCharacter text -> text.codePage().decode(bytes);
            case SqlValueDescriptor.PackedDecimal packed ->
                    decodePacked(bytes, packed).toBigDecimal();
            case SqlValueDescriptor.ZonedDecimal zoned -> ZonedDecimal.decode(
                    bytes, zoned.scale(), zoned.signPosition(), zoned.codePage(),
                    NumProcMode.NOPFD).toBigDecimal();
            case SqlValueDescriptor.BinaryInteger binary ->
                    BinaryDecimal.decode(bytes, binary.scale()).toBigDecimal();
        };
    }

    public EncodedOutput encodeOutput(SqlHostVariable variable, Object jdbcValue) {
        Objects.requireNonNull(variable, "variable");
        if (!variable.mode().isOutput()) {
            throw new IllegalArgumentException("input-only host variable cannot receive output");
        }
        if (jdbcValue == null) {
            if (variable.nullIndicator() == null) {
                throw new IllegalStateException(
                        "SQL NULL cannot be assigned without a null indicator");
            }
            return new EncodedOutput(null, indicatorBytes(-1));
        }
        byte[] encoded = switch (variable.descriptor()) {
            case SqlValueDescriptor.FixedCharacter text -> encodeText(
                    variable.value().length(), text, jdbcValue);
            case SqlValueDescriptor.PackedDecimal packed -> encodePacked(packed, jdbcValue);
            case SqlValueDescriptor.ZonedDecimal zoned -> encodeZoned(zoned, jdbcValue);
            case SqlValueDescriptor.BinaryInteger binary -> encodeBinary(binary, jdbcValue);
        };
        return new EncodedOutput(encoded,
                variable.nullIndicator() == null ? null : indicatorBytes(0));
    }

    /** 全出力のencode成功後にだけ呼び、COBOL storageへ反映する。 */
    public void applyOutput(SqlHostVariable variable, EncodedOutput encoded) {
        Objects.requireNonNull(variable, "variable");
        Objects.requireNonNull(encoded, "encoded");
        byte[] valueBytes = encoded.valueBytes();
        byte[] indicatorBytes = encoded.indicatorBytes();
        if (valueBytes != null && valueBytes.length != variable.value().length()) {
            throw new IllegalArgumentException("encoded SQL value length does not match target");
        }
        if (indicatorBytes != null && (variable.nullIndicator() == null
                || indicatorBytes.length != variable.nullIndicator().length())) {
            throw new IllegalArgumentException("encoded SQL indicator does not match target");
        }
        if (variable.nullIndicator() != null && indicatorBytes == null) {
            throw new IllegalArgumentException("encoded SQL output omitted its indicator");
        }
        if (indicatorBytes != null) {
            boolean nullValue = BinaryDecimal.decode(indicatorBytes, 0).signum() < 0;
            if (nullValue != (valueBytes == null)) {
                throw new IllegalArgumentException(
                        "encoded SQL value and null indicator disagree");
            }
        } else if (valueBytes == null) {
            throw new IllegalArgumentException("encoded SQL output has neither value nor indicator");
        }
        if (valueBytes != null) {
            variable.value().setBytes(valueBytes);
        }
        if (indicatorBytes != null) {
            variable.nullIndicator().setBytes(indicatorBytes);
        }
    }

    private static boolean isNull(SqlHostVariable variable) {
        if (variable.nullIndicator() == null) {
            return false;
        }
        return BinaryDecimal.decode(variable.nullIndicator().toByteArray(), 0).signum() < 0;
    }

    private static byte[] encodeText(
            int length, SqlValueDescriptor.FixedCharacter descriptor, Object value) {
        if (!(value instanceof CharSequence characters)) {
            throw new IllegalArgumentException(
                    "fixed character SQL output requires CharSequence, got "
                            + value.getClass().getName());
        }
        byte[] bytes = encodeExact(descriptor, characters);
        if (bytes.length > length) {
            throw new ArithmeticException(
                    "SQL character output exceeds COBOL field: " + bytes.length + " > " + length);
        }
        byte[] padded = Arrays.copyOf(bytes, length);
        Arrays.fill(padded, bytes.length, length, descriptor.codePage().space());
        return padded;
    }

    private static byte[] encodeExact(
            SqlValueDescriptor.FixedCharacter descriptor, CharSequence characters) {
        try {
            ByteBuffer encoded = descriptor.codePage().charset().newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(characters));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException(
                    "SQL character output is not representable in "
                            + descriptor.codePage().name(), failure);
        }
    }

    private static byte[] encodePacked(
            SqlValueDescriptor.PackedDecimal descriptor, Object value) {
        Decimal decimal = exactDecimal(value, descriptor.scale());
        byte[] bytes = PackedDecimal.encode(
                decimal, descriptor.digits(), descriptor.scale(), descriptor.signed());
        requireRoundTrip(decimal, PackedDecimal.decode(
                bytes, descriptor.scale(), NumProcMode.NOPFD), descriptor);
        return bytes;
    }

    private static Decimal decodePacked(
            byte[] bytes, SqlValueDescriptor.PackedDecimal descriptor) {
        Decimal decimal = PackedDecimal.decode(bytes, descriptor.scale(), NumProcMode.NOPFD);
        if (!decimal.fitsInDigits(descriptor.digits(), descriptor.scale())) {
            throw new IllegalArgumentException(
                    "packed SQL host variable contains digits outside its descriptor");
        }
        return decimal;
    }

    private static byte[] encodeZoned(
            SqlValueDescriptor.ZonedDecimal descriptor, Object value) {
        Decimal decimal = exactDecimal(value, descriptor.scale());
        byte[] bytes = ZonedDecimal.encode(decimal, descriptor.digits(), descriptor.scale(),
                descriptor.signPosition(), descriptor.codePage());
        requireRoundTrip(decimal, ZonedDecimal.decode(bytes, descriptor.scale(),
                descriptor.signPosition(), descriptor.codePage(), NumProcMode.NOPFD), descriptor);
        return bytes;
    }

    private static byte[] encodeBinary(
            SqlValueDescriptor.BinaryInteger descriptor, Object value) {
        Decimal decimal = exactDecimal(value, descriptor.scale());
        byte[] bytes = BinaryDecimal.encode(decimal, descriptor.digits(), descriptor.scale(),
                descriptor.truncMode());
        requireRoundTrip(decimal, BinaryDecimal.decode(bytes, descriptor.scale()), descriptor);
        return bytes;
    }

    private static Decimal exactDecimal(Object value, int scale) {
        BigDecimal decimal;
        if (value instanceof BigDecimal bigDecimal) {
            decimal = bigDecimal;
        } else if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            decimal = BigDecimal.valueOf(((Number) value).longValue());
        } else {
            throw new IllegalArgumentException(
                    "numeric SQL output requires BigDecimal or integral Number, got "
                            + value.getClass().getName());
        }
        BigDecimal exact = decimal.setScale(scale, RoundingMode.UNNECESSARY);
        return Decimal.of(exact.unscaledValue(), scale);
    }

    private static void requireRoundTrip(
            Decimal expected, Decimal decoded, SqlValueDescriptor descriptor) {
        if (expected.toBigDecimal().compareTo(decoded.toBigDecimal()) != 0) {
            throw new ArithmeticException(
                    "SQL numeric output does not fit COBOL host variable " + descriptor);
        }
    }

    private static byte[] indicatorBytes(int value) {
        return BinaryDecimal.encode(Decimal.of(value, 0), 4, 0, TruncMode.BIN);
    }
}
