package dev.cobolonjava.runtime.item;

import static dev.cobolonjava.runtime.TestSupport.assertHex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class FloatingItemTest {

    @Test
    @DisplayName("COMP-1 は 4 バイト、COMP-2 は 8 バイト (FR-032)")
    void byteLengths() {
        assertEquals(4, FloatingItem.comp1().byteLength());
        assertEquals(8, FloatingItem.comp2().byteLength());
    }

    @Test
    @DisplayName("値を IBM 16 進浮動小数点として格納する (FR-032)")
    void encodesAsHexadecimalFloatingPoint() {
        assertHex("41100000", FloatingItem.comp1().encode(BigDecimal.ONE));
        assertHex("4110000000000000", FloatingItem.comp2().encode(BigDecimal.ONE));
    }

    @Test
    @DisplayName("Storage 上のビューへ格納し読み戻せる (FR-020)")
    void storeAndLoadThroughView() {
        Storage storage = Storage.allocate(16);
        FloatingItem item = FloatingItem.comp2();
        DataView view = storage.view(4, item.byteLength());

        item.store(view, new BigDecimal("1234.5"));
        assertEquals(0, new BigDecimal("1234.5").compareTo(item.load(view)));
        assertHex("00000000", storage.view(0, 4).toByteArray(), "項目の外側は書き換わっていない");
    }

    @Test
    @DisplayName("浮動小数点項目は PICTURE を持たないため NumericItem では扱えない (FR-032)")
    void numericItemRejectsFloatingPointUsage() {
        // PICTURE を前提とした記述子に無理に載せると意味論が濁るため、明示的に拒否する
        assertThrows(IllegalArgumentException.class,
                () -> NumericItem.of("S9(5)", Usage.COMP_1));
        assertThrows(IllegalArgumentException.class,
                () -> NumericItem.of("S9(5)", Usage.COMP_2));
    }

    @Test
    @DisplayName("COMP-2 は double では保持できない精度を往復できる (FR-032)")
    void comp2ExceedsDoublePrecision() {
        FloatingItem item = FloatingItem.comp2();
        BigDecimal value = item.decode(dev.cobolonjava.runtime.TestSupport.bytes("41FFFFFFFFFFFFFF"));
        assertHex("41FFFFFFFFFFFFFF", item.encode(value));
    }
}
