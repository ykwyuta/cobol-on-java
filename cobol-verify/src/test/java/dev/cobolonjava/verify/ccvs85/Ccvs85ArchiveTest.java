package dev.cobolonjava.verify.ccvs85;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CCVS85 の配布物を切り分ける (要件 NFR-040)。
 *
 * <p>本物の配布物は 28 MB あり同梱しない。ここで確かめるのは<b>切り方の決まり</b>で
 * あって、中身ではない。
 */
@Tag("V1")
class Ccvs85ArchiveTest {

    private static Ccvs85Archive read(String... lines) {
        try (BufferedReader reader = new BufferedReader(
                new StringReader(String.join("\n", lines)))) {
            return Ccvs85Archive.read(reader);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("区切りの行で部品へ切る (NFR-040)")
    void headersCutTheArchiveIntoMembers() {
        Ccvs85Archive archive = read(
                "CCVS85  VERSION 4.0",
                "*HEADER,COBOL,NC101A",
                "000100 IDENTIFICATION DIVISION.",
                "*END-OF,NC101A",
                "*HEADER,CLBRY,K1FDA",
                "000100 01  A PIC X.",
                "*END-OF,K1FDA");

        assertEquals(2, archive.members().size());
        assertEquals(Ccvs85Archive.Kind.COBOL, archive.members().get(0).kind());
        assertEquals("NC101A", archive.members().get(0).name());
        assertEquals(Ccvs85Archive.Kind.COPYBOOK, archive.members().get(1).kind());
    }

    @Test
    @DisplayName("区切りの外にある行は捨てる (NFR-040)")
    void theTitleLineIsNotPartOfAnyMember() {
        Ccvs85Archive archive = read(
                "CCVS85  VERSION 4.0",
                "*HEADER,COBOL,NC101A",
                "000100 IDENTIFICATION DIVISION.",
                "*END-OF,NC101A");

        assertEquals(List.of("000100 IDENTIFICATION DIVISION."),
                archive.members().get(0).lines());
    }

    @Test
    @DisplayName("DATA* も種別として読む (NFR-040)")
    void dataMembersAreRecognised() {
        Ccvs85Archive archive = read(
                "*HEADER,DATA*,NC109M",
                "AAA",
                "*END-OF,NC109M");

        assertEquals(Ccvs85Archive.Kind.DATA, archive.members().get(0).kind());
    }

    @Test
    @DisplayName("END-OF-POP は終わりの区切りではない (NFR-040)")
    void aSimilarSpellingIsNotAnEndMarker() {
        // *END-OF-POP は配布物の末尾に 1 つだけある別の印である。
        // 頭が同じだからといって部品を閉じてしまうと、最後の 1 本が切れる
        Ccvs85Archive archive = read(
                "*HEADER,COBOL,NC101A",
                "000100 IDENTIFICATION DIVISION.",
                "*END-OF-POP",
                "000200 PROGRAM-ID. NC101A.",
                "*END-OF,NC101A");

        assertEquals(1, archive.members().size());
        assertEquals(3, archive.members().get(0).lines().size());
    }

    @Test
    @DisplayName("終わりの区切りが無くても読めたところまでは部品にする (NFR-040)")
    void anUnclosedMemberIsStillKept() {
        Ccvs85Archive archive = read(
                "*HEADER,COBOL,NC101A",
                "000100 IDENTIFICATION DIVISION.");

        assertEquals(1, archive.members().size());
    }

    @Test
    @DisplayName("モジュールは名前の頭 2 文字である (NFR-040)")
    void theModuleIsTheFirstTwoLetters() {
        Ccvs85Archive archive = read(
                "*HEADER,COBOL,NC101A",
                "000100 A",
                "*END-OF,NC101A",
                "*HEADER,COBOL,SQ102A",
                "000100 B",
                "*END-OF,SQ102A");

        assertEquals("NC", archive.programs().get(0).module());
        assertEquals("SQ", archive.programs().get(1).module());
        assertTrue(archive.programs().get(0).text().endsWith("\n"));
    }
}
