package dev.cobolonjava.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.item.NumericItem;
import dev.cobolonjava.runtime.item.Usage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 整列ユーティリティ (要件 FR-137)。
 *
 * <p>並べ替えそのものは COBOL の {@code SORT} 動詞と同じ仕掛けを使う。ここで確かめるのは
 * <b>制御文が鍵へ正しく翻訳されるか</b>と、通り道の順である。
 */
@Tag("V1")
class SortUtilityTest {

    @TempDir
    Path directory;

    private ByteArrayOutputStream sink;

    private JobRunner.Result run(String... cards) {
        sink = new ByteArrayOutputStream();
        dev.cobolonjava.job.jcl.Jcl.Result parsed = dev.cobolonjava.job.jcl.Jcl.read(
                String.join("\n", cards), directory);
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());
        return JobRunner.at(directory.resolve("work"), SortUtilityTest.class.getClassLoader(), sink)
                .withBase(directory)
                .run(parsed.job());
    }

    private String output() {
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
    }

    private void write(String name, String content, int length) {
        write(name, CodePages.DEFAULT.encode(content), "recfm=F\nlrecl=" + length + "\n");
    }

    private void write(String name, byte[] bytes, String meta) {
        try {
            Files.write(directory.resolve(name), bytes);
            Files.write(directory.resolve(name + ".meta"),
                    (meta + "codepage=IBM-1047\n").getBytes(StandardCharsets.UTF_8));
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

    private byte[] bytes(String name) {
        try {
            return Files.readAllBytes(directory.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> sidecar(String name) {
        try {
            return Files.readAllLines(directory.resolve(name + ".meta"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code SORTIN}/{@code SORTOUT}/{@code SYSIN} を結んだ、いつもの 1 ステップ。 */
    private JobRunner.Result sort(String... control) {
        String[] cards = new String[6 + control.length];
        cards[0] = "//J        JOB  (ACCT)";
        cards[1] = "//STEP1    EXEC PGM=SORT";
        cards[2] = "//SORTIN   DD   DSN=IN.DAT,DISP=SHR";
        cards[3] = "//SORTOUT  DD   DSN=OUT.DAT,DISP=(NEW,CATLG)";
        cards[4] = "//SYSOUT   DD   SYSOUT=*";
        cards[5] = "//SYSIN    DD   *";
        System.arraycopy(control, 0, cards, 6, control.length);
        return run(cards);
    }

    // ---- 並べ替え ----

    @Test
    @DisplayName("SORT FIELDS は文字の鍵で並べ替える (FR-137)")
    void sortsOnACharacterKey() {
        write("IN.DAT", "CCC02AAA01BBB03", 5);

        JobRunner.Result result = sort("  SORT FIELDS=(1,3,CH,A)");

        assertEquals(0, result.returnCode());
        assertEquals("AAA01BBB03CCC02", read("OUT.DAT"));
        assertEquals(List.of("recfm=F", "lrecl=5", "codepage=IBM-1047"), sidecar("OUT.DAT"));
        assertTrue(output().contains("ICE054I 0 RECORDS - IN: 3, OUT: 3"), output());
    }

    @Test
    @DisplayName("D を書けば降順になる (FR-137)")
    void sortsDescending() {
        write("IN.DAT", "CCC02AAA01BBB03", 5);

        assertEquals(0, sort("  SORT FIELDS=(1,3,CH,D)").returnCode());
        assertEquals("CCC02BBB03AAA01", read("OUT.DAT"));
    }

    @Test
    @DisplayName("ZD の鍵は値として比べる (FR-137)")
    void sortsOnAZonedKey() {
        // バイトで比べれば 010 < 9 だが、値としては 9 < 10 である
        write("IN.DAT", "X010Y009Z100", 4);

        assertEquals(0, sort("  SORT FIELDS=(2,3,ZD,A)").returnCode());
        assertEquals("Y009X010Z100", read("OUT.DAT"));
    }

    @Test
    @DisplayName("PD の鍵は値として比べる (FR-137)")
    void sortsOnAPackedKey() {
        NumericItem item = NumericItem.of("S9(5)", Usage.COMP_3);
        byte[] data = new byte[12];
        int at = 0;
        for (int value : new int[] {300, 20, 100}) {
            data[at++] = CodePages.DEFAULT.ch('A');
            System.arraycopy(item.encode(Decimal.of(value, 0)), 0, data, at, 3);
            at += 3;
        }
        write("IN.DAT", data, "recfm=F\nlrecl=4\n");

        assertEquals(0, sort("  SORT FIELDS=(2,3,PD,A)").returnCode());

        byte[] out = bytes("OUT.DAT");
        assertEquals(20, item.decode(java.util.Arrays.copyOfRange(out, 1, 4)).toBigDecimal()
                .intValue());
        assertEquals(100, item.decode(java.util.Arrays.copyOfRange(out, 5, 8)).toBigDecimal()
                .intValue());
        assertEquals(300, item.decode(java.util.Arrays.copyOfRange(out, 9, 12)).toBigDecimal()
                .intValue());
    }

    @Test
    @DisplayName("鍵が等しければ入れた順のまま残る (FR-137)")
    void keepsEqualKeysInOrder() {
        write("IN.DAT", "A03A01A02", 3);

        assertEquals(0, sort("  SORT FIELDS=(1,1,CH,A)").returnCode());
        assertEquals("A03A01A02", read("OUT.DAT"));
    }

    @Test
    @DisplayName("鍵は 2 つ以上書ける (FR-137)")
    void sortsOnSeveralKeys() {
        write("IN.DAT", "B2A2B1A1", 2);

        assertEquals(0, sort("  SORT FIELDS=(1,1,CH,A,2,1,CH,D)").returnCode());
        assertEquals("A2A1B2B1", read("OUT.DAT"));
    }

    @Test
    @DisplayName("FIELDS=COPY は並べ替えずに写す (FR-137)")
    void copiesWithoutSorting() {
        write("IN.DAT", "CCCAAABBB", 3);

        assertEquals(0, sort("  SORT FIELDS=COPY").returnCode());
        assertEquals("CCCAAABBB", read("OUT.DAT"));
    }

    // ---- ふるい分け ----

    @Test
    @DisplayName("INCLUDE COND は残すレコードを選ぶ (FR-137)")
    void includeKeepsMatchingRecords() {
        write("IN.DAT", "AX1BY2AZ3", 3);

        assertEquals(0, sort(
                "  SORT FIELDS=(3,1,CH,A)",
                "  INCLUDE COND=(1,1,CH,EQ,C'A')").returnCode());
        assertEquals("AX1AZ3", read("OUT.DAT"));
    }

    @Test
    @DisplayName("OMIT COND は落とすレコードを選ぶ (FR-137)")
    void omitDropsMatchingRecords() {
        write("IN.DAT", "AX1BY2AZ3", 3);

        assertEquals(0, sort(
                "  SORT FIELDS=(3,1,CH,A)",
                "  OMIT COND=(1,1,CH,EQ,C'A')").returnCode());
        assertEquals("BY2", read("OUT.DAT"));
    }

    @Test
    @DisplayName("COND は AND と OR でつなげる。AND のほうが強い (FR-137)")
    void conditionsCombine() {
        write("IN.DAT", "A1XA2YB1ZB2W", 3);

        // A かつ 2、または B かつ 1
        assertEquals(0, sort(
                "  SORT FIELDS=COPY",
                "  INCLUDE COND=(1,1,CH,EQ,C'A',AND,2,1,ZD,EQ,2,OR,",
                "               1,1,CH,EQ,C'B',AND,2,1,ZD,EQ,1)").returnCode());
        assertEquals("A2YB1Z", read("OUT.DAT"));
    }

    @Test
    @DisplayName("COND は数として比べられる (FR-137)")
    void conditionsCompareNumbers() {
        write("IN.DAT", "A009B010C100", 4);

        assertEquals(0, sort(
                "  SORT FIELDS=COPY",
                "  INCLUDE COND=(2,3,ZD,GE,10)").returnCode());
        assertEquals("B010C100", read("OUT.DAT"));
    }

    @Test
    @DisplayName("COND は場所どうしを比べられる (FR-137)")
    void conditionsCompareFields() {
        write("IN.DAT", "AAABAB", 3);

        assertEquals(0, sort(
                "  SORT FIELDS=COPY",
                "  INCLUDE COND=(1,1,CH,EQ,2,1,CH)").returnCode());
        assertEquals("AAA", read("OUT.DAT"));
    }

    // ---- SUM ----

    @Test
    @DisplayName("SUM FIELDS は鍵が等しいレコードをまとめて足す (FR-137)")
    void sumAddsEqualKeys() {
        write("IN.DAT", "A010B005A020", 4);

        assertEquals(0, sort(
                "  SORT FIELDS=(1,1,CH,A)",
                "  SUM FIELDS=(2,3,ZD)").returnCode());

        // 足した結果には符号が付く。ZD の符号は最下位桁の上位 4 ビットにあり、
        // 正なら X'C' である。読んだときは F でも、書くときは C になる
        byte[] out = bytes("OUT.DAT");
        assertEquals("A03", CodePages.DEFAULT.decode(java.util.Arrays.copyOfRange(out, 0, 3)));
        assertEquals(0xC0, out[3] & 0xFF);
        assertEquals("B005", CodePages.DEFAULT.decode(java.util.Arrays.copyOfRange(out, 4, 8)));
        assertEquals(8, out.length);
    }

    @Test
    @DisplayName("SUM FIELDS=NONE は重なりを落とす (FR-137)")
    void sumNoneDropsDuplicates() {
        write("IN.DAT", "A010B005A020", 4);

        assertEquals(0, sort(
                "  SORT FIELDS=(1,1,CH,A)",
                "  SUM FIELDS=NONE").returnCode());
        assertEquals("A010B005", read("OUT.DAT"));
    }

    // ---- OUTREC ----

    @Test
    @DisplayName("OUTREC はレコードを組み直す (FR-137)")
    void outrecRebuildsTheRecord() {
        write("IN.DAT", "AB12CD34", 4);

        assertEquals(0, sort(
                "  SORT FIELDS=COPY",
                "  OUTREC FIELDS=(3,2,C'-',1,2)").returnCode());
        assertEquals("12-AB34-CD", read("OUT.DAT"));
        assertEquals(List.of("recfm=F", "lrecl=5", "codepage=IBM-1047"), sidecar("OUT.DAT"));
    }

    @Test
    @DisplayName("OUTREC は空白と桁位置を置ける (FR-137)")
    void outrecPadsAndPositions() {
        write("IN.DAT", "AB", 2);

        assertEquals(0, sort(
                "  SORT FIELDS=COPY",
                "  OUTREC FIELDS=(1,1,2X,10:,2,1)").returnCode());
        assertEquals("A    " + "    B", read("OUT.DAT"));
    }

    // ---- MERGE ----

    @Test
    @DisplayName("MERGE は SORTINnn を突き合わせる (FR-137)")
    void mergesSeveralInputs() {
        write("ONE.DAT", "A1C3", 2);
        write("TWO.DAT", "B2D4", 2);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=SORT",
                "//SORTIN01 DD   DSN=ONE.DAT,DISP=SHR",
                "//SORTIN02 DD   DSN=TWO.DAT,DISP=SHR",
                "//SORTOUT  DD   DSN=OUT.DAT,DISP=(NEW,CATLG)",
                "//SYSOUT   DD   SYSOUT=*",
                "//SYSIN    DD   *",
                "  MERGE FIELDS=(1,1,CH,A)");

        assertEquals(0, result.returnCode());
        assertEquals("A1B2C3D4", read("OUT.DAT"));
        assertTrue(output().contains("BLOCKSET MERGE"), output());
    }

    // ---- 誤り ----

    @Test
    @DisplayName("SORT も MERGE もなければ失敗する (FR-137)")
    void needsASortStatement() {
        write("IN.DAT", "AB", 2);

        JobRunner.Result result = sort("  INCLUDE COND=(1,1,CH,EQ,C'A')");

        assertEquals(16, result.returnCode());
        assertTrue(output().contains("NO SORT OR MERGE STATEMENT"), output());
    }

    @Test
    @DisplayName("入力がなければ失敗する (FR-137)")
    void needsItsInput() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=SORT",
                "//SORTIN   DD   DSN=NOSUCH.DAT,DISP=SHR",
                "//SORTOUT  DD   DSN=OUT.DAT,DISP=(NEW,CATLG)",
                "//SYSOUT   DD   SYSOUT=*",
                "//SYSIN    DD   *",
                "  SORT FIELDS=COPY");

        assertEquals(16, result.returnCode());
        assertTrue(output().contains("NO INPUT DATA SET"), output());
    }

    @Test
    @DisplayName("知らない制御文は報告する (FR-137)")
    void unknownStatementsAreReported() {
        write("IN.DAT", "AB", 2);

        JobRunner.Result result = sort(
                "  SORT FIELDS=COPY",
                "  INREC FIELDS=(1,2)");

        assertEquals(16, result.returnCode());
        assertTrue(output().contains("NOT SUPPORTED YET: INREC"), output());
    }

    @Test
    @DisplayName("ICETOOL はまだ対応していないことを言う (FR-137, 暫定判断 P-047)")
    void icetoolIsNotSupportedYet() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=ICETOOL",
                "//SYSOUT   DD   SYSOUT=*",
                "//TOOLIN   DD   *",
                "  COPY FROM(IN) TO(OUT)");

        assertEquals(16, result.returnCode());
        assertTrue(output().contains("ICETOOL IS NOT SUPPORTED YET"), output());
    }
}
