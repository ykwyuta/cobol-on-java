package dev.cobolonjava.db2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.data.BinaryDecimal;
import dev.cobolonjava.runtime.data.NumProcMode;
import dev.cobolonjava.runtime.data.PackedDecimal;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.data.TruncMode;
import dev.cobolonjava.runtime.data.ZonedDecimal;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class SqlValueCodecTest {

    private final SqlValueCodec codec = new SqlValueCodec();

    @Test
    @DisplayName("固定長EBCDICと3種のCOBOL数値をJDBC値へ変換する")
    void decodesCobolStorageForJdbc() {
        assertEquals("AB  ", codec.toJdbcValue(SqlHostVariable.input(
                view(CodePages.IBM_1047.encode("AB  ")),
                new SqlValueDescriptor.FixedCharacter(CodePages.IBM_1047), null)));
        assertEquals(new BigDecimal("123.45"), codec.toJdbcValue(SqlHostVariable.input(
                view(PackedDecimal.encode(Decimal.parse("123.45"), 5, 2, true)),
                new SqlValueDescriptor.PackedDecimal(5, 2, true), null)));
        assertEquals(new BigDecimal("-12.3"), codec.toJdbcValue(SqlHostVariable.input(
                view(ZonedDecimal.encode(Decimal.parse("-12.3"), 3, 1,
                        SignPosition.TRAILING, CodePages.IBM_1047)),
                new SqlValueDescriptor.ZonedDecimal(3, 1, SignPosition.TRAILING,
                        CodePages.IBM_1047), null)));
        assertEquals(new BigDecimal("42"), codec.toJdbcValue(SqlHostVariable.input(
                view(BinaryDecimal.encode(Decimal.of(42, 0), 4, 0, TruncMode.BIN)),
                new SqlValueDescriptor.BinaryInteger(4, 0, TruncMode.BIN), null)));
    }

    @Test
    @DisplayName("負のnull indicatorはvalue bytesを読まずJDBC nullにする")
    void decodesNullIndicator() {
        DataView invalidPacked = view(new byte[] {(byte) 0xFA, 0x0A});
        DataView indicator = view(BinaryDecimal.encode(
                Decimal.of(-1, 0), 4, 0, TruncMode.BIN));

        assertNull(codec.toJdbcValue(SqlHostVariable.input(invalidPacked,
                new SqlValueDescriptor.PackedDecimal(3, 0, true), indicator)));
    }

    @Test
    @DisplayName("SQL出力をCOBOL表現へ正確に符号化してindicatorをzeroにする")
    void encodesJdbcOutputExactly() {
        DataView value = Storage.allocate(PackedDecimal.byteLength(5)).whole();
        DataView indicator = Storage.allocate(2).whole();
        SqlHostVariable variable = SqlHostVariable.output(value,
                new SqlValueDescriptor.PackedDecimal(5, 2, true), indicator);

        codec.applyOutput(variable,
                codec.encodeOutput(variable, new BigDecimal("123.45")));

        assertEquals(Decimal.parse("123.45"), PackedDecimal.decode(
                value.toByteArray(), 2, NumProcMode.NOPFD));
        assertEquals(0, BinaryDecimal.decode(indicator.toByteArray(), 0).signum());

        DataView zonedValue = Storage.allocate(3).whole();
        SqlHostVariable zoned = SqlHostVariable.output(zonedValue,
                new SqlValueDescriptor.ZonedDecimal(3, 1, SignPosition.TRAILING,
                        CodePages.IBM_1047), null);
        codec.applyOutput(zoned, codec.encodeOutput(zoned, new BigDecimal("-12.3")));
        assertEquals(Decimal.parse("-12.3"), ZonedDecimal.decode(zonedValue.toByteArray(),
                1, SignPosition.TRAILING, CodePages.IBM_1047, NumProcMode.NOPFD));

        DataView binaryValue = Storage.allocate(2).whole();
        SqlHostVariable binary = SqlHostVariable.output(binaryValue,
                new SqlValueDescriptor.BinaryInteger(4, 0, TruncMode.BIN), null);
        codec.applyOutput(binary, codec.encodeOutput(binary, 42));
        assertEquals(Decimal.of(42, 0), BinaryDecimal.decode(binaryValue.toByteArray(), 0));
    }

    @Test
    @DisplayName("SQL NULLでは値を変更せずindicatorだけを-1にする")
    void appliesNullWithoutChangingValue() {
        DataView value = view(CodePages.IBM_1047.encode("KEEP"));
        DataView indicator = Storage.allocate(2).whole();
        SqlHostVariable variable = SqlHostVariable.output(value,
                new SqlValueDescriptor.FixedCharacter(CodePages.IBM_1047), indicator);

        codec.applyOutput(variable, codec.encodeOutput(variable, null));

        assertArrayEquals(CodePages.IBM_1047.encode("KEEP"), value.toByteArray());
        assertEquals(-1, BinaryDecimal.decode(indicator.toByteArray(), 0).signum());
    }

    @Test
    @DisplayName("桁あふれ、丸め、indicatorなしNULLを黙って受け入れない")
    void rejectsLossyOutputs() {
        SqlHostVariable numeric = SqlHostVariable.output(
                Storage.allocate(PackedDecimal.byteLength(3)).whole(),
                new SqlValueDescriptor.PackedDecimal(3, 1, true), null);
        SqlHostVariable text = SqlHostVariable.output(Storage.allocate(2).whole(),
                new SqlValueDescriptor.FixedCharacter(CodePages.IBM_1047), null);

        assertThrows(ArithmeticException.class,
                () -> codec.encodeOutput(numeric, new BigDecimal("123.4")));
        assertThrows(ArithmeticException.class,
                () -> codec.encodeOutput(numeric, new BigDecimal("1.23")));
        assertThrows(ArithmeticException.class,
                () -> codec.encodeOutput(text, "ABC"));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encodeOutput(text, "😀"));
        assertThrows(IllegalStateException.class,
                () -> codec.encodeOutput(text, null));
    }

    @Test
    @DisplayName("記述子とCOBOL storage長の不一致を構築時に拒否する")
    void validatesDescriptorStorageShape() {
        assertThrows(IllegalArgumentException.class, () -> SqlHostVariable.input(
                Storage.allocate(2).whole(),
                new SqlValueDescriptor.PackedDecimal(5, 0, true), null));
        assertThrows(IllegalArgumentException.class, () -> new SqlHostVariable(
                Storage.allocate(1).whole(), SqlBindingMode.INPUT,
                new SqlValueDescriptor.FixedCharacter(CodePages.IBM_1047),
                Storage.allocate(4).whole()));
    }

    private static DataView view(byte[] bytes) {
        return Storage.copyOf(bytes).whole();
    }
}
