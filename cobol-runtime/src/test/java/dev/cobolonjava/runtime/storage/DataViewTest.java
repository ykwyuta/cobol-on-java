package dev.cobolonjava.runtime.storage;

import static dev.cobolonjava.runtime.TestSupport.assertHex;
import static dev.cobolonjava.runtime.TestSupport.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class DataViewTest {

    @Test
    @DisplayName("REDEFINES: 同一範囲の 2 つのビューは同じバイト列を共有する (FR-021)")
    void redefinesSharesBytes() {
        Storage storage = Storage.allocate(8);
        DataView a = storage.view(0, 4);
        DataView b = storage.view(0, 4);

        a.setBytes(bytes("F1F2F3F4"));
        assertHex("F1F2F3F4", b.toByteArray());

        b.set(0, (byte) 0x5A);
        assertEquals((byte) 0x5A, a.get(0));
    }

    @Test
    @DisplayName("集団項目は配下の基本項目のバイト範囲そのものである (FR-020)")
    void groupItemIsTheByteRange() {
        Storage storage = Storage.allocate(6);
        DataView group = storage.view(0, 6);
        DataView first = storage.view(0, 3);
        DataView second = storage.view(3, 3);

        first.setBytes(bytes("C1C2C3"));
        second.setBytes(bytes("F1F2F3"));

        assertHex("C1C2C3F1F2F3", group.toByteArray());
    }

    @Test
    @DisplayName("部分参照は元のビュー上の部分ビューになる (FR-026)")
    void referenceModification() {
        Storage storage = Storage.allocate(10);
        DataView item = storage.view(2, 6);
        item.setBytes(bytes("C1C2C3C4C5C6"));

        DataView sub = item.subView(2, 3);
        assertHex("C3C4C5", sub.toByteArray());

        sub.fill((byte) 0x40);
        assertHex("C1C2404040C6", item.toByteArray());
    }

    @Test
    @DisplayName("ビューの範囲外アクセスは検出される")
    void outOfRange() {
        Storage storage = Storage.allocate(4);
        assertThrows(IndexOutOfBoundsException.class, () -> storage.view(2, 4));
        DataView v = storage.view(0, 4);
        assertThrows(IndexOutOfBoundsException.class, () -> v.get(4));
        assertThrows(IndexOutOfBoundsException.class, () -> v.subView(2, 3));
    }
}
