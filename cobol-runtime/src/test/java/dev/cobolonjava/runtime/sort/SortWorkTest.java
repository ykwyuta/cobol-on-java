package dev.cobolonjava.runtime.sort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.item.NumericItem;
import dev.cobolonjava.runtime.item.Usage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 整列作業ファイル (要件 FR-120, FR-121)。
 *
 * <p>並べ替えは<b>安定でなければならない</b>。鍵が等しいレコードの順は入れた順のままである。
 */
@Tag("V1")
class SortWorkTest {

    private static byte[] bytes(String text) {
        return CodePages.DEFAULT.encode(text);
    }

    private static String decode(byte[] value) {
        return CodePages.DEFAULT.decode(value);
    }

    private static SortWork work(SortKey... keys) {
        return new SortWork(List.of(keys), CodePages.DEFAULT);
    }

    /** 並べ替えたあとの中身を、順に取り出して並べる。 */
    private static List<String> drain(SortWork work, int length) {
        List<String> out = new ArrayList<>();
        byte[] record = new byte[length];
        while (work.next(record)) {
            out.add(decode(record));
        }
        return out;
    }

    @Test
    @DisplayName("英数字の鍵は照合順序で並ぶ (FR-120)")
    void alphanumericKeysUseTheCollatingSequence() {
        SortWork work = work(SortKey.alphanumeric(0, 3, true));
        work.release(bytes("CCCx"));
        work.release(bytes("AAAy"));
        work.release(bytes("BBBz"));
        work.sort();

        assertEquals(List.of("AAAy", "BBBz", "CCCx"), drain(work, 4));
    }

    @Test
    @DisplayName("降順の鍵は逆に並ぶ (FR-120)")
    void descendingKeysReverseTheOrder() {
        SortWork work = work(new SortKey(0, 3, false, null));
        work.release(bytes("AAA"));
        work.release(bytes("CCC"));
        work.release(bytes("BBB"));
        work.sort();

        assertEquals(List.of("CCC", "BBB", "AAA"), drain(work, 3));
    }

    @Test
    @DisplayName("鍵が等しければ入れた順のまま残る (FR-120)")
    void equalKeysKeepTheirOrder() {
        SortWork work = work(SortKey.alphanumeric(0, 1, true));
        work.release(bytes("Aone"));
        work.release(bytes("Btwo"));
        work.release(bytes("Athr"));
        work.release(bytes("Afou"));
        work.sort();

        assertEquals(List.of("Aone", "Athr", "Afou", "Btwo"), drain(work, 4));
    }

    @Test
    @DisplayName("鍵は書いた順に効く (FR-120)")
    void keysApplyInOrder() {
        SortWork work = work(SortKey.alphanumeric(0, 1, true),
                new SortKey(1, 1, false, null));
        work.release(bytes("A1"));
        work.release(bytes("A3"));
        work.release(bytes("B2"));
        work.release(bytes("A2"));
        work.sort();

        assertEquals(List.of("A3", "A2", "A1", "B2"), drain(work, 2));
    }

    @Test
    @DisplayName("数値の鍵は値として比べる (FR-120)")
    void numericKeysCompareByValue() {
        // 010 と 9 は、バイトでは 010 が小さいが、値としては 9 が小さい
        NumericItem item = NumericItem.of("9(3)", Usage.DISPLAY);
        SortWork work = work(new SortKey(0, 3, true, item));
        work.release(bytes("010"));
        work.release(bytes("009"));
        work.release(bytes("100"));
        work.sort();

        assertEquals(List.of("009", "010", "100"), drain(work, 3));
    }

    @Test
    @DisplayName("返すものがなくなれば false になる (FR-120)")
    void drainingEndsWithFalse() {
        SortWork work = work(SortKey.alphanumeric(0, 1, true));
        work.release(bytes("A"));
        work.sort();

        byte[] record = new byte[1];
        assertTrue(work.next(record));
        assertFalse(work.next(record));
    }

    @Test
    @DisplayName("受取領域が長ければ空白で埋める (FR-120)")
    void aLongerAreaIsPaddedWithSpaces() {
        SortWork work = work(SortKey.alphanumeric(0, 1, true));
        work.release(bytes("AB"));
        work.sort();

        byte[] record = new byte[5];
        assertTrue(work.next(record));
        assertEquals("AB   ", decode(record));
    }

    @Test
    @DisplayName("合併は先に入れたファイルのものを先に返す (FR-121)")
    void mergingKeepsTheFileOrderForEqualKeys() {
        // MERGE は整列済みの入力を突き合わせる。すべて溜めてから安定に並べ替えれば同じ結果になる
        SortWork work = work(SortKey.alphanumeric(0, 1, true));
        work.release(bytes("A1"));
        work.release(bytes("C1"));
        work.release(bytes("A2"));
        work.release(bytes("B2"));
        work.sort();

        assertEquals(List.of("A1", "A2", "B2", "C1"), drain(work, 2));
    }

    @Test
    @DisplayName("溜めたレコードの数を数えられる (FR-120)")
    void theCountIsAvailable() {
        SortWork work = work(SortKey.alphanumeric(0, 1, true));
        assertEquals(0, work.size());
        work.release(bytes("A"));
        work.release(bytes("B"));
        assertEquals(2, work.size());
    }
}
