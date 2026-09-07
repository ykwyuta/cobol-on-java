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
        // 入出力のモジュールが使うファイルの札はまだ書いていない。
        // 適当な値を返すと、処理系の失敗を道具が作ることになる
        assertNull(XCards.defaults().text(14));
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
