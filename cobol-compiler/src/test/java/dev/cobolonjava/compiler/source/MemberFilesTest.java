package dev.cobolonjava.compiler.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 写し句をメンバー名として引く。大文字と小文字を区別しない。 */
class MemberFilesTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("COPY LGCMAREA は小文字のファイル lgcmarea.cpy を引く (GenApp の置き方)")
    void findsLowerCaseFiles() throws IOException {
        Files.writeString(directory.resolve("lgcmarea.cpy"), "       01 A PIC X.\n");

        CopyBook book = new DirectoryCopyBookResolver(directory).resolve("LGCMAREA", null).orElseThrow();

        assertEquals("lgcmarea.cpy", book.fileName());
    }

    @Test
    @DisplayName("DCLGEN の出力を置く .dcl も引く (CardDemo の置き方)")
    void findsDclgenMembers() throws IOException {
        Files.writeString(directory.resolve("DCLTRTYP.dcl"), "       01 A PIC X.\n");

        assertTrue(new DirectoryCopyBookResolver(directory).resolve("DCLTRTYP", null).isPresent());
    }

    @Test
    @DisplayName("拡張子の順が優先の順のまま: 名前そのもの、.cpy、.cbl、.dcl")
    void keepsSuffixOrder() throws IOException {
        Files.writeString(directory.resolve("item.dcl"), "dcl");
        Files.writeString(directory.resolve("ITEM.CPY"), "cpy");

        assertEquals("ITEM.CPY",
                new DirectoryCopyBookResolver(directory).resolve("ITEM", null).orElseThrow().fileName());
    }

    @Test
    @DisplayName("大文字と小文字だけが違うファイルが 2 つあれば、どちらかを黙って選ばずに断る")
    void refusesFilesThatDifferOnlyInCase() throws IOException {
        Files.writeString(directory.resolve("ITEM.cpy"), "upper");
        Files.writeString(directory.resolve("item.cpy"), "lower");
        try (Stream<Path> files = Files.list(directory)) {
            // 大文字と小文字を区別しないファイル体系 (Windows、macOS の既定) では 2 つ目が 1 つ目を上書きする
            assumeTrue(files.count() == 2, "the file system is case-insensitive");
        }

        SourceFormatException refused = assertThrows(SourceFormatException.class,
                () -> new DirectoryCopyBookResolver(directory).resolve("ITEM", null));
        assertTrue(refused.getMessage().contains("[ITEM.cpy, item.cpy]"), refused.getMessage());
    }

    @Test
    @DisplayName("BMS の mapset も大文字と小文字を区別せずに引く (GenApp の ssmap.bms)")
    void findsLowerCaseMapsets() throws IOException {
        Files.writeString(directory.resolve("ssmap.bms"), String.join("\n",
                String.format("%-71s*", "SSMAP    DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,STORAGE=AUTO,"),
                "               TIOAPFX=YES",
                "SSMAPC1  DFHMDI SIZE=(24,80)",
                "         DFHMDF POS=(1,1),LENGTH=5,INITIAL='TITLE',ATTRB=PROT",
                "         DFHMSD TYPE=FINAL",
                "         END") + "\n");

        assertTrue(new BmsCopyBookResolver(directory).resolve("SSMAP", null).isPresent());
    }

    @Test
    @DisplayName("置き場が無ければ見つからない (例外にしない)")
    void missingDirectoryFindsNothing() {
        assertTrue(new DirectoryCopyBookResolver(directory.resolve("absent")).resolve("X", null).isEmpty());
    }
}
