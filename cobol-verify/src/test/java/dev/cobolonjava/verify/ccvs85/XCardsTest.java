package dev.cobolonjava.verify.ccvs85;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 差し込み札 (要件 NFR-040)。
 */
@Tag("V1")
class XCardsTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("同梱の札には装置の名前と印字の結び付けが入っている (NFR-040)")
    void theDefaultsCoverWhatEveryProgramNeeds() {
        // 082 / 083 / 055 / 084 はほぼ全部の検査プログラムが使う
        XCards cards = XCards.defaults();

        assertEquals("COBOL-ON-JAVA", cards.text(82));
        assertEquals("COBOL-ON-JAVA", cards.text(83));
        assertEquals("PRINT-FILE", cards.text(55));
        assertEquals("OMITTED", cards.text(84));
    }

    @Test
    @DisplayName("用意していない番号は null である (NFR-040)")
    void anUnknownCardIsNotInvented() {
        // 配布物に出てこない番号。適当な値を返すと、処理系の失敗を道具が作ることになる
        assertNull(XCards.defaults().text(999));
    }

    @Test
    @DisplayName("照合順序の札は 51 文字で、引用符を含まず、ドル記号を 2 度持つ (NFR-040)")
    void theCollatingCardsMatchWhatTheSuiteRequires() {
        // 文字の顔ぶれはこちらで選べない。ST137A が同じ 51 文字を定数で持っていて、
        // 並べ替えた結果と突き合わせる。引用符は定数に書けないので、代わりに
        // ドル記号をもう 1 つ使う決まりである (配布物の X-63 の注記)。
        // 選び違えると<b>道具が処理系の失敗を作る</b> (ST137A SRT-TEST-003)
        XCards cards = XCards.defaults();
        String ascending = unquote(cards.text(63));
        String descending = unquote(cards.text(64));

        assertEquals(51, ascending.length(), ascending);
        assertEquals(51, descending.length(), descending);
        assertEquals(2, ascending.chars().filter(c -> c == '$').count(), ascending);
        assertEquals(0, ascending.chars().filter(c -> c == '"' || c == '\'').count(), ascending);
        assertEquals(ascending, new StringBuilder(descending).reverse().toString());
    }

    /** 札は引用符ごと書いてある。中身だけを取り出す。 */
    private static String unquote(String card) {
        return card.substring(1, card.length() - 1);
    }

    @Test
    @DisplayName("外から差し替えられる (NFR-040)")
    void aFileCanOverrideTheDefaults() throws IOException {
        Path file = directory.resolve("mine.properties");
        Files.writeString(file, "082 = MY-MACHINE\n014 = MY-FILE\n", StandardCharsets.UTF_8);

        XCards cards = XCards.defaults().and(file);

        assertEquals("MY-MACHINE", cards.text(82));
        assertEquals("MY-FILE", cards.text(14));
        // 差し替えなかったものは残る
        assertEquals("OMITTED", cards.text(84));
    }
}
