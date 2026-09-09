package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.file.DataSetCatalog;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
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
 * {@code SORT} と {@code MERGE} を翻訳して実行する
 * (要件 FR-120, FR-121、設計 80 の第 4 段)。
 *
 * <p>やることは<b>溜めて、並べ替えて、配る</b>の 3 つである。入口と出口はファイルか手続きの
 * どちらかであり、組み合わせは 4 通りある。
 */
@Tag("V1")
class SortGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(SortGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String source(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append("       ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static String run(Path directory, String source) {
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, source);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            program.runFresh(ProgramContext.capturing(sink)
                    .withCatalog(new DataSetCatalog(directory)));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
    }

    private static List<String> diagnostics(String source) {
        return CobolCompiler.standard().compile(FILE, source).diagnostics().stream()
                .map(Object::toString).toList();
    }

    private static byte[] ebcdic(String text) {
        return CodePages.DEFAULT.encode(text);
    }

    private static byte[] bytesOf(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path path, byte[] bytes) {
        try {
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 5 バイトのレコードを持つ入力ファイルを置く。 */
    private static void seed(Path directory, String ddName, String content) {
        write(directory.resolve(ddName), ebcdic(content));
        write(directory.resolve(ddName + ".meta"),
                "recfm=F\nlrecl=5\ncodepage=IBM-1047\n".getBytes(StandardCharsets.UTF_8));
    }

    /** 整列作業ファイルと入出力ファイルの宣言。 */
    private static String[] declaration() {
        return new String[] {
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. SORTER.",
            "ENVIRONMENT DIVISION.",
            "INPUT-OUTPUT SECTION.",
            "FILE-CONTROL.",
            "    SELECT WORK-FILE ASSIGN TO SORTWK.",
            "    SELECT IN-FILE ASSIGN TO INDD.",
            "    SELECT IN2-FILE ASSIGN TO IN2DD.",
            "    SELECT OUT-FILE ASSIGN TO OUTDD.",
            "DATA DIVISION.",
            "FILE SECTION.",
            "SD  WORK-FILE.",
            "01  WORK-REC.",
            "    05  WK-KEY  PIC X(2).",
            "    05  WK-REST PIC XXX.",
            "FD  IN-FILE.",
            "01  IN-REC  PIC X(5).",
            "FD  IN2-FILE.",
            "01  IN2-REC PIC X(5).",
            "FD  OUT-FILE.",
            "01  OUT-REC PIC X(5).",
            "WORKING-STORAGE SECTION.",
            "01  WS-DONE  PIC X VALUE 'N'.",
            "01  WS-COUNT PIC 9(3) VALUE 0.",
            "PROCEDURE DIVISION.",
        };
    }

    private static String program(String... procedure) {
        String[] head = declaration();
        String[] all = new String[head.length + procedure.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(procedure, 0, all, head.length, procedure.length);
        return source(all);
    }

    @Test
    @DisplayName("USING と GIVING で並べ替える (FR-120)")
    void sortingFromFileToFile(@TempDir Path directory) {
        seed(directory, "INDD", "CCxxxAAyyyBBzzz");
        run(directory, program(
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    SORT WORK-FILE",
                "        ON ASCENDING KEY WK-KEY",
                "        USING IN-FILE",
                "        GIVING OUT-FILE.",
                "    STOP RUN."));
        assertArrayEquals(ebcdic("AAyyyBBzzzCCxxx"), bytesOf(directory.resolve("OUTDD")));
    }

    @Test
    @DisplayName("GIVING を 2 つ書けば、どちらにも全部入る (FR-120)")
    void everyGivingFileGetsAllTheRecords(@TempDir Path directory) {
        // 1 つ目で読み切ったままにすると、2 つ目が空になる (ST147A がこの形)
        seed(directory, "INDD", "CCxxxAAyyyBBzzz");
        run(directory, program(
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    SORT WORK-FILE",
                "        ON ASCENDING KEY WK-KEY",
                "        USING IN-FILE",
                "        GIVING OUT-FILE IN2-FILE.",
                "    STOP RUN."));
        assertArrayEquals(ebcdic("AAyyyBBzzzCCxxx"), bytesOf(directory.resolve("OUTDD")));
        assertArrayEquals(ebcdic("AAyyyBBzzzCCxxx"), bytesOf(directory.resolve("IN2DD")));
    }

    @Test
    @DisplayName("降順の鍵で並べ替える (FR-120)")
    void sortingDescending(@TempDir Path directory) {
        seed(directory, "INDD", "AAyyyCCxxxBBzzz");
        run(directory, program(
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    SORT WORK-FILE",
                "        ON DESCENDING KEY WK-KEY",
                "        USING IN-FILE",
                "        GIVING OUT-FILE.",
                "    STOP RUN."));
        assertArrayEquals(ebcdic("CCxxxBBzzzAAyyy"), bytesOf(directory.resolve("OUTDD")));
    }

    @Test
    @DisplayName("入力手続きが RELEASE でレコードを渡す (FR-120)")
    void anInputProcedureReleasesRecords(@TempDir Path directory) {
        run(directory, program(
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    SORT WORK-FILE",
                "        ON ASCENDING KEY WK-KEY",
                "        INPUT PROCEDURE IS FEED-SECT",
                "        GIVING OUT-FILE.",
                "    STOP RUN.",
                "FEED-SECT SECTION.",
                "FEED-PARA.",
                "    MOVE 'CCone' TO WORK-REC.",
                "    RELEASE WORK-REC.",
                "    MOVE 'AAtwo' TO WORK-REC.",
                "    RELEASE WORK-REC.",
                "    MOVE 'BBthr' TO WORK-REC.",
                "    RELEASE WORK-REC."));
        assertArrayEquals(ebcdic("AAtwoBBthrCCone"), bytesOf(directory.resolve("OUTDD")));
    }

    @Test
    @DisplayName("出力手続きが RETURN でレコードを受け取る (FR-120)")
    void anOutputProcedureReturnsRecords(@TempDir Path directory) {
        seed(directory, "INDD", "CCxxxAAyyyBBzzz");
        assertEquals("AAyyy|BBzzz|CCxxx|003|", run(directory, program(
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    SORT WORK-FILE",
                "        ON ASCENDING KEY WK-KEY",
                "        USING IN-FILE",
                "        OUTPUT PROCEDURE IS DRAIN-SECT.",
                "    DISPLAY WS-COUNT.",
                "    STOP RUN.",
                "DRAIN-SECT SECTION.",
                "DRAIN-PARA.",
                "    PERFORM UNTIL WS-DONE = 'Y'",
                "        RETURN WORK-FILE",
                "            AT END MOVE 'Y' TO WS-DONE",
                "            NOT AT END",
                "                DISPLAY WORK-REC",
                "                ADD 1 TO WS-COUNT",
                "        END-RETURN",
                "    END-PERFORM.")));
    }

    @Test
    @DisplayName("入力手続きと出力手続きを両方書ける (FR-120)")
    void bothProceduresMayBeWritten(@TempDir Path directory) {
        assertEquals("AA|BB|CC|", run(directory, program(
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    SORT WORK-FILE",
                "        ON ASCENDING KEY WK-KEY",
                "        INPUT PROCEDURE IS FEED-SECT",
                "        OUTPUT PROCEDURE IS DRAIN-SECT.",
                "    STOP RUN.",
                "FEED-SECT SECTION.",
                "FEED-PARA.",
                "    MOVE 'CCone' TO WORK-REC.",
                "    RELEASE WORK-REC.",
                "    MOVE 'AAtwo' TO WORK-REC.",
                "    RELEASE WORK-REC.",
                "    MOVE 'BBthr' TO WORK-REC.",
                "    RELEASE WORK-REC.",
                "DRAIN-SECT SECTION.",
                "DRAIN-PARA.",
                "    PERFORM UNTIL WS-DONE = 'Y'",
                "        RETURN WORK-FILE",
                "            AT END MOVE 'Y' TO WS-DONE",
                "            NOT AT END DISPLAY WK-KEY",
                "        END-RETURN",
                "    END-PERFORM.")));
    }

    @Test
    @DisplayName("鍵が等しければ入れた順のまま残る (FR-120)")
    void equalKeysKeepTheirOrder(@TempDir Path directory) {
        seed(directory, "INDD", "AAoneBBtwoAAthr");
        run(directory, program(
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    SORT WORK-FILE",
                "        ON ASCENDING KEY WK-KEY",
                "        USING IN-FILE",
                "        GIVING OUT-FILE.",
                "    STOP RUN."));
        assertArrayEquals(ebcdic("AAoneAAthrBBtwo"), bytesOf(directory.resolve("OUTDD")));
    }

    @Test
    @DisplayName("MERGE は 2 つの入力を突き合わせる (FR-121)")
    void mergingTwoFiles(@TempDir Path directory) {
        seed(directory, "INDD", "AAoneCConeEEone");
        seed(directory, "IN2DD", "BBtwoDDtwo");
        run(directory, program(
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    MERGE WORK-FILE",
                "        ON ASCENDING KEY WK-KEY",
                "        USING IN-FILE IN2-FILE",
                "        GIVING OUT-FILE.",
                "    STOP RUN."));
        assertArrayEquals(ebcdic("AAoneBBtwoCConeDDtwoEEone"),
                bytesOf(directory.resolve("OUTDD")));
    }

    @Test
    @DisplayName("MERGE で鍵が等しければ先のファイルが先に来る (FR-121)")
    void mergingKeepsTheFileOrderForEqualKeys(@TempDir Path directory) {
        seed(directory, "INDD", "AAoneCCone");
        seed(directory, "IN2DD", "AAtwoBBtwo");
        run(directory, program(
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    MERGE WORK-FILE",
                "        ON ASCENDING KEY WK-KEY",
                "        USING IN-FILE IN2-FILE",
                "        GIVING OUT-FILE.",
                "    STOP RUN."));
        assertArrayEquals(ebcdic("AAoneAAtwoBBtwoCCone"),
                bytesOf(directory.resolve("OUTDD")));
    }

    @Test
    @DisplayName("RELEASE ... FROM と RETURN ... INTO は転記である (FR-120)")
    void fromAndIntoAreMoves(@TempDir Path directory) {
        assertEquals("AAtwo|CCone|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MOVING.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT WORK-FILE ASSIGN TO SORTWK.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "SD  WORK-FILE.",
                "01  WORK-REC.",
                "    05  WK-KEY  PIC X(2).",
                "    05  WK-REST PIC XXX.",
                "WORKING-STORAGE SECTION.",
                "01  WS-LINE PIC X(5).",
                "01  WS-DONE PIC X VALUE 'N'.",
                "PROCEDURE DIVISION.",
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    SORT WORK-FILE",
                "        ON ASCENDING KEY WK-KEY",
                "        INPUT PROCEDURE IS FEED-SECT",
                "        OUTPUT PROCEDURE IS DRAIN-SECT.",
                "    STOP RUN.",
                "FEED-SECT SECTION.",
                "FEED-PARA.",
                "    MOVE 'CCone' TO WS-LINE.",
                "    RELEASE WORK-REC FROM WS-LINE.",
                "    MOVE 'AAtwo' TO WS-LINE.",
                "    RELEASE WORK-REC FROM WS-LINE.",
                "DRAIN-SECT SECTION.",
                "DRAIN-PARA.",
                "    PERFORM UNTIL WS-DONE = 'Y'",
                "        RETURN WORK-FILE INTO WS-LINE",
                "            AT END MOVE 'Y' TO WS-DONE",
                "            NOT AT END DISPLAY WS-LINE",
                "        END-RETURN",
                "    END-PERFORM.")));
    }

    @Test
    @DisplayName("数値の鍵は値として比べる (FR-120)")
    void numericKeysCompareByValue(@TempDir Path directory) {
        assertEquals("009|010|100|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. NUMSORT.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT WORK-FILE ASSIGN TO SORTWK.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "SD  WORK-FILE.",
                "01  WORK-REC.",
                "    05  WK-NUM PIC 9(3).",
                "WORKING-STORAGE SECTION.",
                "01  WS-DONE PIC X VALUE 'N'.",
                "PROCEDURE DIVISION.",
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    SORT WORK-FILE",
                "        ON ASCENDING KEY WK-NUM",
                "        INPUT PROCEDURE IS FEED-SECT",
                "        OUTPUT PROCEDURE IS DRAIN-SECT.",
                "    STOP RUN.",
                "FEED-SECT SECTION.",
                "FEED-PARA.",
                "    MOVE 10 TO WK-NUM.",
                "    RELEASE WORK-REC.",
                "    MOVE 100 TO WK-NUM.",
                "    RELEASE WORK-REC.",
                "    MOVE 9 TO WK-NUM.",
                "    RELEASE WORK-REC.",
                "DRAIN-SECT SECTION.",
                "DRAIN-PARA.",
                "    PERFORM UNTIL WS-DONE = 'Y'",
                "        RETURN WORK-FILE",
                "            AT END MOVE 'Y' TO WS-DONE",
                "            NOT AT END DISPLAY WK-NUM",
                "        END-RETURN",
                "    END-PERFORM.")));
    }

    @Test
    @DisplayName("鍵は書いた順に効く (FR-120)")
    void keysApplyInOrder(@TempDir Path directory) {
        seed(directory, "INDD", "AA3ooAA1ooAA2oo");
        run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. TWOKEY.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT WORK-FILE ASSIGN TO SORTWK.",
                "    SELECT IN-FILE ASSIGN TO INDD.",
                "    SELECT OUT-FILE ASSIGN TO OUTDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "SD  WORK-FILE.",
                "01  WORK-REC.",
                "    05  WK-KEY  PIC X(2).",
                "    05  WK-SEQ  PIC 9.",
                "    05  WK-REST PIC XX.",
                "FD  IN-FILE.",
                "01  IN-REC  PIC X(5).",
                "FD  OUT-FILE.",
                "01  OUT-REC PIC X(5).",
                "PROCEDURE DIVISION.",
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    SORT WORK-FILE",
                "        ON ASCENDING KEY WK-KEY",
                "        ON DESCENDING KEY WK-SEQ",
                "        USING IN-FILE",
                "        GIVING OUT-FILE.",
                "    STOP RUN."));
        assertArrayEquals(ebcdic("AA3ooAA2ooAA1oo"), bytesOf(directory.resolve("OUTDD")));
    }

    // ---- 組み合わせの検査 ----

    @Test
    @DisplayName("SORT の相手は SD でなければならない (FR-120)")
    void sortNeedsASortWorkFile() {
        assertTrue(diagnostics(program(
                "MAIN-SECT SECTION.",
                "    SORT IN-FILE ON ASCENDING KEY WK-KEY",
                "        USING IN-FILE GIVING OUT-FILE.",
                "    STOP RUN.")).toString().contains("not a sort-merge file (SD)"));
    }

    @Test
    @DisplayName("整列作業ファイルは開けない (FR-120)")
    void aSortWorkFileCannotBeOpened() {
        assertTrue(diagnostics(program(
                "MAIN-SECT SECTION.",
                "    OPEN INPUT WORK-FILE.",
                "    STOP RUN.")).toString()
                .contains("OPEN cannot be used on a sort-merge file"));
    }

    @Test
    @DisplayName("整列作業ファイルへは WRITE できない (FR-120)")
    void aSortWorkFileTakesReleaseNotWrite() {
        assertTrue(diagnostics(program(
                "MAIN-SECT SECTION.",
                "    WRITE WORK-REC.",
                "    STOP RUN.")).toString()
                .contains("WRITE cannot be used on a sort-merge file (SD); use RELEASE"));
    }

    @Test
    @DisplayName("鍵は整列作業ファイルのレコードの中になければならない (FR-120)")
    void aKeyMustLiveInsideTheSortRecord() {
        assertTrue(diagnostics(program(
                "MAIN-SECT SECTION.",
                "    SORT WORK-FILE ON ASCENDING KEY WS-COUNT",
                "        USING IN-FILE GIVING OUT-FILE.",
                "    STOP RUN.")).toString()
                .contains("a sort key must be inside the record of WORK-FILE"));
    }

    @Test
    @DisplayName("RETURN には AT END が要る (FR-120)")
    void returnNeedsAnAtEndPhrase() {
        assertTrue(diagnostics(program(
                "MAIN-SECT SECTION.",
                "    RETURN WORK-FILE.",
                "    STOP RUN.")).toString().contains("RETURN requires an AT END phrase"));
    }

    @Test
    @DisplayName("MERGE には 2 つ以上の入力が要る (FR-121)")
    void mergeNeedsTwoInputs() {
        assertTrue(diagnostics(program(
                "MAIN-SECT SECTION.",
                "    MERGE WORK-FILE ON ASCENDING KEY WK-KEY",
                "        USING IN-FILE GIVING OUT-FILE.",
                "    STOP RUN.")).toString().contains("MERGE needs at least two USING files"));
    }

    @Test
    @DisplayName("USING と GIVING に整列作業ファイルは書けない (FR-120)")
    void usingAndGivingNameDataFiles() {
        assertTrue(diagnostics(program(
                "MAIN-SECT SECTION.",
                "    SORT WORK-FILE ON ASCENDING KEY WK-KEY",
                "        USING WORK-FILE GIVING OUT-FILE.",
                "    STOP RUN.")).toString()
                .contains("USING and GIVING name data files"));
    }

    @Test
    @DisplayName("整列作業ファイルにファイル状態は書けない (FR-120)")
    void aSortWorkFileTakesOnlyAssign() {
        assertTrue(diagnostics(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. BADSD.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT WORK-FILE ASSIGN TO SORTWK",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "SD  WORK-FILE.",
                "01  WORK-REC PIC X(5).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "PROCEDURE DIVISION.",
                "    STOP RUN.")).toString()
                .contains("takes only ASSIGN"));
    }
}
