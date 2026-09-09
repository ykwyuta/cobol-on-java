package dev.cobolonjava.verify.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 置き場に並んだ資産を読む (要件 NFR-042)。
 */
@Tag("V1")
class SourceDirectoryTest {

    @TempDir
    Path directory;

    private void write(String path, String text) throws IOException {
        Path file = directory.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static List<String> names(List<CorpusRunner.Source> sources) {
        return sources.stream().map(CorpusRunner.Source::name).sorted().toList();
    }

    @Test
    @DisplayName("COBOL の原文だけを拾う (NFR-042)")
    void onlyCobolSourcesAreRead() throws IOException {
        write("acme-payroll/MAIN.cbl", "000100 A");
        write("acme-payroll/REC.cpy", "000100 01 A PIC X.");
        write("acme-payroll/README.md", "not cobol");
        write("other/JOB.cob", "000100 B");

        List<CorpusRunner.Source> sources = SourceDirectory.read(directory);

        assertEquals(List.of("JOB.cob", "MAIN.cbl", "REC.cpy"), names(sources));
    }

    @Test
    @DisplayName("直下のディレクトリ名が区分になる (NFR-042)")
    void theTopDirectoryNameIsTheGroup() throws IOException {
        // 取得スクリプトは 持ち主-資産/ という形で並べる。どの資産がどれだけ通ったかを
        // 数えるのに、この区分を使う
        write("acme-payroll/src/MAIN.cbl", "000100 A");
        write("other/JOB.cob", "000100 B");

        List<CorpusRunner.Source> sources = SourceDirectory.read(directory);

        assertEquals(List.of("acme-payroll", "other"),
                sources.stream().map(CorpusRunner.Source::group).sorted().toList());
    }

    @Test
    @DisplayName("置き場の直下のファイルは (root) になる (NFR-042)")
    void aFileAtTheTopHasNoOwner() throws IOException {
        Files.writeString(directory.resolve("LOOSE.cbl"), "000100 A", StandardCharsets.UTF_8);

        List<CorpusRunner.Source> sources = SourceDirectory.read(directory);

        assertEquals("(root)", sources.get(0).group());
    }

    @Test
    @DisplayName("読めないバイトがあっても止まらない (NFR-042)")
    void oddBytesDoNotStopTheReading() throws IOException {
        Path file = directory.resolve("owner/ODD.cbl");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {(byte) 0xC3, (byte) 0x28, 'A'});

        List<CorpusRunner.Source> sources = SourceDirectory.read(directory);

        // 資産は UTF-8 のこともラテン 1 のこともある。文字の選び分けは別の話であり、
        // ここで止まっては網羅率が測れない
        assertEquals(1, sources.size());
        assertEquals(3, sources.get(0).text().length());
        assertTrue(sources.get(0).text().endsWith("A"));
    }

    @Test
    @DisplayName("何も無い置き場は空の束になる (NFR-042)")
    void anEmptyDirectoryIsNotAnError() {
        assertEquals(List.of(), SourceDirectory.read(directory));
    }
}
