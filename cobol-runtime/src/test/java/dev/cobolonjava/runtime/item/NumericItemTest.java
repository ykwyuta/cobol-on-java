package dev.cobolonjava.runtime.item;

import static dev.cobolonjava.runtime.TestSupport.assertHex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class NumericItemTest {

    @Test
    @DisplayName("USAGE ごとのバイト長 (FR-031)")
    void byteLengths() {
        assertEquals(5, NumericItem.of("S9(5)", Usage.DISPLAY).byteLength());
        assertEquals(6, NumericItem.of("S9(5)", Usage.DISPLAY)
                .withSignPosition(SignPosition.TRAILING_SEPARATE).byteLength());
        assertEquals(3, NumericItem.of("S9(5)", Usage.COMP_3).byteLength());
        assertEquals(4, NumericItem.of("S9(5)", Usage.COMP).byteLength());
    }

    @Test
    @DisplayName("同じ値が USAGE ごとに異なるバイト列になる (FR-031)")
    void sameValueDifferentRepresentations() {
        Decimal v = Decimal.parse("-123.45");
        assertHex("F1F2F3F4D5", NumericItem.of("S9(3)V99", Usage.DISPLAY).encode(v));
        assertHex("12345D", NumericItem.of("S9(3)V99", Usage.COMP_3).encode(v));
        assertHex("FFFFCFC7", NumericItem.of("S9(3)V99", Usage.COMP).encode(v));
    }

    @Test
    @DisplayName("COMP-5 は桁数による切り捨てを行わない (FR-031)")
    void comp5UsesFullStorageRange() {
        Decimal v = Decimal.parse("32000");
        assertHex("07D0", NumericItem.of("S9(4)", Usage.COMP).encode(v));
        assertHex("7D00", NumericItem.of("S9(4)", Usage.COMP_5).encode(v));
    }

    @Test
    @DisplayName("SIZE ERROR の判定と、判定しない場合の上位桁切り捨て (FR-043)")
    void sizeError() {
        NumericItem item = NumericItem.of("S9(3)V99", Usage.COMP_3);
        assertTrue(item.fits(Decimal.parse("999.99")));
        assertFalse(item.fits(Decimal.parse("1000.00")));
        // ON SIZE ERROR を指定しない場合、上位桁は黙って切り捨てられる
        assertHex("00000C", item.encode(Decimal.parse("1000.00")));
    }

    @Test
    @DisplayName("Storage 上のビューへ格納し読み戻せる (FR-020)")
    void storeAndLoadThroughView() {
        Storage storage = Storage.allocate(16);
        NumericItem item = NumericItem.of("S9(5)", Usage.COMP_3);
        DataView view = storage.view(4, item.byteLength());

        item.store(view, Decimal.parse("-12345"));
        assertHex("12345D", view.toByteArray());
        assertEquals(0, Decimal.parse("-12345").compareTo(item.load(view)));

        // 項目の外側は書き換わっていない
        assertHex("00000000", storage.view(0, 4).toByteArray());
    }

    @Test
    @DisplayName("符号を持たない項目には絶対値が入る")
    void anUnsignedItemStoresTheAbsoluteValue() {
        // 規格がそう決めている。符号を残すと、負の値を移したあと負のまま読み戻される
        // (NC105A の MOVE-TEST-F1-114「MOVE TO COMP (ABS)」)
        for (Usage usage : Usage.values()) {
            if (usage.isFloatingPoint() || usage == Usage.NATIONAL) {
                continue;
            }
            NumericItem item = NumericItem.of("9(5)V99", usage);
            Decimal stored = item.decode(item.encode(Decimal.parse("-707.17")));
            assertEquals(0, Decimal.parse("707.17").compareTo(stored),
                    "sign was kept for " + usage);
        }
    }

    @Test
    @DisplayName("符号を持つ項目では符号がそのまま残る")
    void aSignedItemKeepsItsSign() {
        for (Usage usage : Usage.values()) {
            if (usage.isFloatingPoint() || usage == Usage.NATIONAL) {
                continue;
            }
            NumericItem item = NumericItem.of("S9(5)V99", usage);
            Decimal stored = item.decode(item.encode(Decimal.parse("-707.17")));
            assertEquals(0, Decimal.parse("-707.17").compareTo(stored),
                    "sign was lost for " + usage);
        }
    }

    @Test
    @DisplayName("すべての USAGE で符号化と復号が往復する")
    void roundTripAcrossUsages() {
        for (Usage usage : Usage.values()) {
            if (usage.isFloatingPoint() || usage == Usage.NATIONAL) {
                // 浮動小数点項目は PICTURE を持たないため NumericItem の対象外
                continue;
            }
            NumericItem item = NumericItem.of("S9(3)V99", usage);
            for (String v : new String[] {"0", "1.23", "-1.23", "999.99", "-999.99"}) {
                Decimal original = Decimal.parse(v);
                Decimal decoded = item.decode(item.encode(original));
                assertEquals(0, original.compareTo(decoded),
                        "round trip failed for " + v + " with " + usage);
            }
        }
    }
}
