package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
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
 * 論理頁 ({@code LINAGE}) と {@code LINAGE-COUNTER} (要件 FR-113)。
 *
 * <p>数え方は<b>NIST CCVS85 の SQ201M が決めている</b>。適合性の検査スイートは規格の
 * 要求を実行できる形で書いたものであり、そこから読み取った規則をここに写してある。
 * 実機で確かめたものではないが、外の基準ではある。
 */
@Tag("V1")
class LinageGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(LinageGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    /** 頁の形と手続きを差し替えて 1 本のプログラムにする。 */
    private static String program(List<String> linage, String... procedure) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. PAGES.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT PRINT-FILE ASSIGN TO PRTDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  PRINT-FILE")) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : linage) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : List.of(
                "01  PRINT-REC PIC X(4).",
                "WORKING-STORAGE SECTION.",
                "01  WS-N  PIC 9(4).",
                "01  WS-EOP PIC 9 VALUE 0.",
                "01  WS-PAGE PIC 9(3) VALUE 3.",
                "01  WS-FOOT PIC 9(3) VALUE 3.",
                "01  WS-TOP  PIC 9(3) VALUE 1.",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    MOVE 'LINE' TO PRINT-REC",
                "    OPEN OUTPUT PRINT-FILE.")) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : procedure) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : List.of(
                "    CLOSE PRINT-FILE",
                "    STOP RUN.")) {
            FixedFormatSource.append(sb, line);
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
            throw new AssertionError("cannot load the generated program", e);
        }
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
    }

    private static int lines(Path directory) {
        try {
            // 固定長 4 バイトで書いているので、長さを割れば行数になる
            return (int) (Files.size(directory.resolve("PRTDD")) / 4);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final List<String> PAGE_OF_FIVE = List.of(
            "    LINAGE IS 5 LINES",
            "        WITH FOOTING AT 4",
            "        LINES AT TOP 2",
            "        LINES AT BOTTOM 3.");

    @Test
    @DisplayName("開いた直後の LINAGE-COUNTER は 1 である (FR-113)")
    void theCounterIsOneRightAfterOpen() {
        // SQ201M WRT-TEST-01 が決めている (85 規格 VII-5 1.3.8)。
        // まだ 1 行も置いていないが 0 ではない。紙は本文の 1 行目にある
        assertEquals("0001|0001|", run(directory,
                program(PAGE_OF_FIVE,
                        "    MOVE LINAGE-COUNTER TO WS-N DISPLAY WS-N",
                        "    WRITE PRINT-REC",
                        "    MOVE LINAGE-COUNTER TO WS-N DISPLAY WS-N.")));
    }

    @Test
    @DisplayName("開いた直後の 1 は「頁を送った直後」とは違う (FR-113)")
    void theInitialOneDoesNotCountAsALineAlreadyPlaced() {
        // 数だけでは見分けられない。頁を送った直後なら 1 行置いてあるので
        // ADVANCING PAGE がもう 1 枚送るが、開いた直後は送らない。
        // 上の余白 1 行 + 本文 1 行 = 2 行しか出ない
        run(directory, program(List.of(
                "    LINAGE IS 5 LINES",
                "        LINES AT TOP 1",
                "        LINES AT BOTTOM 1."),
                "    WRITE PRINT-REC AFTER ADVANCING PAGE."));
        assertEquals(2, lines(directory));
    }

    @Test
    @DisplayName("行送りを書かない WRITE は 1 行進む (FR-113)")
    void aWriteWithoutAdvancingTakesOneLine() {
        // SQ201M WRT-TEST-004 が決めている
        assertEquals("0001|0002|", run(directory,
                program(PAGE_OF_FIVE,
                        "    WRITE PRINT-REC",
                        "    MOVE LINAGE-COUNTER TO WS-N DISPLAY WS-N",
                        "    WRITE PRINT-REC",
                        "    MOVE LINAGE-COUNTER TO WS-N DISPLAY WS-N.")));
    }

    @Test
    @DisplayName("BEFORE でも AFTER でも同じ行数だけ進む (FR-113)")
    void beforeAndAfterAdvanceTheCounterAlike() {
        // SQ201M WRT-TEST-005 は BEFORE ADVANCING 5 で 5 進むことを見ている
        assertEquals("0002|0004|", run(directory,
                program(PAGE_OF_FIVE,
                        "    WRITE PRINT-REC AFTER ADVANCING 2 LINES",
                        "    MOVE LINAGE-COUNTER TO WS-N DISPLAY WS-N",
                        "    WRITE PRINT-REC BEFORE ADVANCING 2 LINES",
                        "    MOVE LINAGE-COUNTER TO WS-N DISPLAY WS-N.")));
    }

    @Test
    @DisplayName("ADVANCING PAGE のあと LINAGE-COUNTER は 1 である (FR-113)")
    void thePageAdvanceLeavesTheCounterAtOne() {
        // SQ201M WRT-TEST-002 が決めている
        assertEquals("0003|0001|", run(directory,
                program(PAGE_OF_FIVE,
                        "    WRITE PRINT-REC AFTER ADVANCING 3 LINES",
                        "    MOVE LINAGE-COUNTER TO WS-N DISPLAY WS-N",
                        "    WRITE PRINT-REC AFTER ADVANCING PAGE",
                        "    MOVE LINAGE-COUNTER TO WS-N DISPLAY WS-N.")));
    }

    @Test
    @DisplayName("本文をはみ出す書き込みは次の頁の 1 行目へ回る (FR-113)")
    void aWriteThatOverflowsTheBodyGoesToTheNextPage() {
        // SQ201M WRT-TEST-003 が決めている。本文 5 行なので 6 行目は次の頁である
        assertEquals("0005|0001|", run(directory,
                program(PAGE_OF_FIVE,
                        "    PERFORM 5 TIMES WRITE PRINT-REC END-PERFORM",
                        "    MOVE LINAGE-COUNTER TO WS-N DISPLAY WS-N",
                        "    WRITE PRINT-REC",
                        "    MOVE LINAGE-COUNTER TO WS-N DISPLAY WS-N.")));
    }

    @Test
    @DisplayName("脚注の行に達した WRITE が AT END-OF-PAGE を通す (FR-113)")
    void reachingTheFootingRaisesEndOfPage() {
        // 脚注は 4 行目である。3 行目までは NOT AT END-OF-PAGE を通る
        assertEquals("0|0|0|1|", run(directory,
                program(PAGE_OF_FIVE,
                        "    PERFORM 4 TIMES",
                        "        WRITE PRINT-REC",
                        "            AT END-OF-PAGE MOVE 1 TO WS-EOP",
                        "            NOT AT END-OF-PAGE MOVE 0 TO WS-EOP",
                        "        END-WRITE",
                        "        DISPLAY WS-EOP",
                        "    END-PERFORM.")));
    }

    @Test
    @DisplayName("頁の形は項目で書ける。開くたびに読み直す (FR-113)")
    void thePageShapeMayBeGivenByDataItems() {
        // 項目で書かれた形は<b>開くたびに読み直す</b>決まりである。
        // ここを「数で書け」と断っていた
        assertEquals("0003|0001|", run(directory, program(
                List.of("    LINAGE IS WS-PAGE LINES",
                        "        WITH FOOTING AT WS-FOOT",
                        "        LINES AT TOP WS-TOP."),
                "    WRITE PRINT-REC AFTER ADVANCING 3 LINES",
                "    MOVE LINAGE-COUNTER TO WS-N DISPLAY WS-N",
                "    WRITE PRINT-REC",
                "    MOVE LINAGE-COUNTER TO WS-N DISPLAY WS-N.")));
    }

    @Test
    @DisplayName("余白は LINAGE-COUNTER に入らないが、紙には出る (FR-113)")
    void theMarginsAreWrittenButNotCounted() {
        // 上 2 行 + 本文 5 行 + 下 3 行 + 次の頁の上 2 行 + 1 行目 = 13 行
        run(directory, program(PAGE_OF_FIVE,
                "    PERFORM 6 TIMES WRITE PRINT-REC END-PERFORM."));

        assertEquals(13, lines(directory));
    }

    @TempDir
    Path directory;
}
