package dev.cobolonjava.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePages;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code ICETOOL} (要件 FR-137)。
 *
 * <p>整列そのものは持たず、{@code SORT} を呼び出す。足しているのは「同じ入力を何度も通す」
 * 「重なりを見つける」「数える」という、整列の周りの仕事である。
 */
@Tag("V1")
class IcetoolTest {

    @TempDir
    Path directory;

    private ByteArrayOutputStream sink;

    private JobRunner.Result run(String... cards) {
        sink = new ByteArrayOutputStream();
        dev.cobolonjava.job.jcl.Jcl.Result parsed = dev.cobolonjava.job.jcl.Jcl.read(
                String.join("\n", cards), directory);
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());
        return JobRunner.at(directory.resolve("work"), IcetoolTest.class.getClassLoader(), sink)
                .withBase(directory)
                .run(parsed.job());
    }

    private String output() {
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
    }

    private void write(String name, String content, int length) {
        try {
            Files.write(directory.resolve(name), CodePages.DEFAULT.encode(content));
            Files.write(directory.resolve(name + ".meta"),
                    ("recfm=F\nlrecl=" + length + "\ncodepage=IBM-1047\n")
                            .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String read(String name) {
        try {
            return CodePages.DEFAULT.decode(Files.readAllBytes(directory.resolve(name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code TOOLIN} を与える 1 ステップ。 */
    private JobRunner.Result tool(String[] dds, String... toolin) {
        String[] cards = new String[3 + dds.length + toolin.length];
        cards[0] = "//J        JOB  (ACCT)";
        cards[1] = "//STEP1    EXEC PGM=ICETOOL";
        System.arraycopy(dds, 0, cards, 2, dds.length);
        cards[2 + dds.length] = "//TOOLIN   DD   *";
        System.arraycopy(toolin, 0, cards, 3 + dds.length, toolin.length);
        return run(cards);
    }

    // ---- 写し ----

    @Test
    @DisplayName("COPY は FROM を TO へ写す (FR-137)")
    void copyMovesTheDataSet() {
        write("IN.DAT", "AAAAABBBBB", 5);

        JobRunner.Result result = tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR",
            "//OUT      DD   DSN=OUT.DAT,DISP=(NEW,CATLG)"},
                "  COPY FROM(IN) TO(OUT)");

        assertEquals(0, result.returnCode());
        assertEquals("AAAAABBBBB", read("OUT.DAT"));
    }

    @Test
    @DisplayName("TO は複数書ける (FR-137)")
    void copyCanNameSeveralOutputs() {
        write("IN.DAT", "AAAAA", 5);

        JobRunner.Result result = tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR",
            "//ONE      DD   DSN=ONE.DAT,DISP=(NEW,CATLG)",
            "//TWO      DD   DSN=TWO.DAT,DISP=(NEW,CATLG)"},
                "  COPY FROM(IN) TO(ONE,TWO)");

        assertEquals(0, result.returnCode());
        assertEquals("AAAAA", read("ONE.DAT"));
        assertEquals("AAAAA", read("TWO.DAT"));
    }

    // ---- 整列は SORT へ委ねる ----

    @Test
    @DisplayName("SORT は USING の制御文で SORT を呼ぶ (FR-137)")
    void sortDelegatesToTheSortUtility() {
        write("IN.DAT", "CCCAAABBB", 3);

        JobRunner.Result result = tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR",
            "//OUT      DD   DSN=OUT.DAT,DISP=(NEW,CATLG)",
            "//CTL1CNTL DD   *",
            "  SORT FIELDS=(1,3,CH,A)",
            "/*"},
                "  SORT FROM(IN) TO(OUT) USING(CTL1)");

        assertEquals(0, result.returnCode());
        assertEquals("AAABBBCCC", read("OUT.DAT"));
    }

    @Test
    @DisplayName("COPY も USING の制御文を通せる (FR-137)")
    void copyCanUseControlStatements() {
        write("IN.DAT", "A1B2A3", 2);

        JobRunner.Result result = tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR",
            "//OUT      DD   DSN=OUT.DAT,DISP=(NEW,CATLG)",
            "//CTL1CNTL DD   *",
            "  SORT FIELDS=COPY",
            "  INCLUDE COND=(1,1,CH,EQ,C'A')",
            "/*"},
                "  COPY FROM(IN) TO(OUT) USING(CTL1)");

        assertEquals(0, result.returnCode());
        assertEquals("A1A3", read("OUT.DAT"));
    }

    @Test
    @DisplayName("USING を書かない SORT は誤りである (FR-137)")
    void sortNeedsUsing() {
        write("IN.DAT", "AAA", 3);

        JobRunner.Result result = tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR",
            "//OUT      DD   DSN=OUT.DAT,DISP=(NEW,CATLG)"},
                "  SORT FROM(IN) TO(OUT)");

        assertEquals(12, result.returnCode());
        assertTrue(output().contains("SORT NEEDS USING"), output());
    }

    // ---- 数える ----

    @Test
    @DisplayName("COUNT は件数を出す (FR-137)")
    void countReportsTheNumberOfRecords() {
        write("IN.DAT", "AAABBBCCC", 3);

        JobRunner.Result result = tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR"},
                "  COUNT FROM(IN)");

        assertEquals(0, result.returnCode());
        assertTrue(output().contains("RECORD COUNT: 3"), output());
    }

    @Test
    @DisplayName("EMPTY は空でなければ復帰コードを立てる (FR-137)")
    void emptyFailsWhenThereAreRecords() {
        write("IN.DAT", "AAA", 3);

        JobRunner.Result result = tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR"},
                "  COUNT FROM(IN) EMPTY");

        assertEquals(12, result.returnCode());
        assertTrue(output().contains("NOT AS EXPECTED"), output());
    }

    @Test
    @DisplayName("HIGHER は件数の下限を言う (FR-137)")
    void higherComparesTheCount() {
        write("IN.DAT", "AAABBB", 3);

        assertEquals(0, tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR"},
                "  COUNT FROM(IN) HIGHER(1)").returnCode());
        assertEquals(12, tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR"},
                "  COUNT FROM(IN) HIGHER(5)").returnCode());
    }

    // ---- 選ぶ ----

    @Test
    @DisplayName("SELECT NODUPS は 1 件しかないものを取る (FR-137)")
    void selectNodupsTakesTheSingletons() {
        write("IN.DAT", "A1A2B1C1C2", 2);

        JobRunner.Result result = tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR",
            "//OUT      DD   DSN=OUT.DAT,DISP=(NEW,CATLG)"},
                "  SELECT FROM(IN) TO(OUT) ON(1,1,CH) NODUPS");

        assertEquals(0, result.returnCode());
        assertEquals("B1", read("OUT.DAT"));
    }

    @Test
    @DisplayName("SELECT ALLDUPS は重なっているものすべてを取る (FR-137)")
    void selectAlldupsTakesTheDuplicates() {
        write("IN.DAT", "A1A2B1C1C2", 2);

        JobRunner.Result result = tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR",
            "//OUT      DD   DSN=OUT.DAT,DISP=(NEW,CATLG)"},
                "  SELECT FROM(IN) TO(OUT) ON(1,1,CH) ALLDUPS");

        assertEquals(0, result.returnCode());
        assertEquals("A1A2C1C2", read("OUT.DAT"));
    }

    @Test
    @DisplayName("SELECT FIRST は各組の先頭を取る (FR-137)")
    void selectFirstTakesTheHeadOfEachGroup() {
        write("IN.DAT", "A1A2B1C1C2", 2);

        JobRunner.Result result = tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR",
            "//OUT      DD   DSN=OUT.DAT,DISP=(NEW,CATLG)"},
                "  SELECT FROM(IN) TO(OUT) ON(1,1,CH) FIRST");

        assertEquals(0, result.returnCode());
        assertEquals("A1B1C1", read("OUT.DAT"));
    }

    @Test
    @DisplayName("SELECT HIGHER は組の大きさで選ぶ (FR-137)")
    void selectHigherComparesTheGroupSize() {
        write("IN.DAT", "A1A2A3B1", 2);

        JobRunner.Result result = tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR",
            "//OUT      DD   DSN=OUT.DAT,DISP=(NEW,CATLG)"},
                "  SELECT FROM(IN) TO(OUT) ON(1,1,CH) HIGHER(2)");

        assertEquals(0, result.returnCode());
        assertEquals("A1A2A3", read("OUT.DAT"));
    }

    @Test
    @DisplayName("選び方を書かない SELECT は誤りである (FR-137)")
    void selectNeedsAWayToChoose() {
        write("IN.DAT", "A1", 2);

        JobRunner.Result result = tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR",
            "//OUT      DD   DSN=OUT.DAT,DISP=(NEW,CATLG)"},
                "  SELECT FROM(IN) TO(OUT) ON(1,1,CH)");

        assertEquals(12, result.returnCode());
        assertTrue(output().contains("NEEDS A WAY TO CHOOSE"), output());
    }

    // ---- 見せる ----

    @Test
    @DisplayName("DISPLAY は場所の値を並べる (FR-137)")
    void displayListsTheFields() {
        write("IN.DAT", "SMITH   00300JONES   00100", 13);

        JobRunner.Result result = tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR",
            "//LIST     DD   DSN=LIST.TXT,DISP=(NEW,CATLG)"},
                "  DISPLAY FROM(IN) LIST(LIST) TITLE('SALES') -",
                "    ON(1,8,CH) ON(9,5,ZD)");

        assertEquals(0, result.returnCode());
        String listing = read("LIST.TXT");
        assertTrue(listing.contains("SALES"), listing);
        assertTrue(listing.contains("SMITH     300"), listing);
        assertTrue(listing.contains("JONES     100"), listing);
    }

    @Test
    @DisplayName("OCCUR は値ごとの件数を並べる (FR-137)")
    void occurCountsEachValue() {
        write("IN.DAT", "A1B2A3A4", 2);

        JobRunner.Result result = tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR",
            "//LIST     DD   DSN=LIST.TXT,DISP=(NEW,CATLG)"},
                "  OCCUR FROM(IN) LIST(LIST) ON(1,1,CH)");

        assertEquals(0, result.returnCode());
        String listing = read("LIST.TXT");
        assertTrue(listing.contains("A  3"), listing);
        assertTrue(listing.contains("B  1"), listing);
    }

    // ---- MODE ----

    @Test
    @DisplayName("既定では失敗した操作のところで打ち切る (FR-137)")
    void theSequenceStopsOnAFailure() {
        write("IN.DAT", "AAA", 3);

        JobRunner.Result result = tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR",
            "//OUT      DD   DSN=OUT.DAT,DISP=(NEW,CATLG)"},
                "  COUNT FROM(IN) EMPTY",
                "  COPY FROM(IN) TO(OUT)");

        assertEquals(12, result.returnCode());
        assertTrue(output().contains("SEQUENCE STOPPED"), output());
        // 打ち切ったので写しは動いていない。割当てで作った空のままである
        assertEquals("", read("OUT.DAT"));
    }

    @Test
    @DisplayName("MODE CONTINUE なら最後まで通す (FR-137)")
    void continueRunsTheRest() {
        write("IN.DAT", "AAA", 3);

        JobRunner.Result result = tool(new String[] {
            "//IN       DD   DSN=IN.DAT,DISP=SHR",
            "//OUT      DD   DSN=OUT.DAT,DISP=(NEW,CATLG)"},
                "  MODE CONTINUE",
                "  COUNT FROM(IN) EMPTY",
                "  COPY FROM(IN) TO(OUT)");

        assertEquals(12, result.returnCode());
        assertFalse(output().contains("SEQUENCE STOPPED"), output());
        assertEquals("AAA", read("OUT.DAT"));
    }

    @Test
    @DisplayName("知らない操作は報告する (FR-137, 暫定判断 P-047)")
    void unknownOperatorsAreReported() {
        JobRunner.Result result = tool(new String[0], "  SPLICE FROM(IN) TO(OUT)");

        assertEquals(12, result.returnCode());
        assertTrue(output().contains("NOT SUPPORTED YET: SPLICE"), output());
    }
}
