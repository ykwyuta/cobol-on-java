package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 報告書作成機能 (要件 FR-214)。
 *
 * <p>数え札の動きは<b>NIST CCVS85 の RW101A から RW104A が決めている</b>。
 * {@code INITIATE} のあと {@code LINE-COUNTER} は 0、{@code PAGE-COUNTER} は 1 になり、
 * {@code GENERATE} のあと {@code LINE-COUNTER} は<b>その行を置いた行番号</b>に等しい。
 *
 * <p>紙の上の姿も見る。数え札だけ合っていて紙が違う、ということが起こりうるからである。
 * 改頁は 1 バイトの改頁文字だけの行で表す (暫定判断 P-063)。
 */
@Tag("V1")
class ReportWriterTest {

    private static final String FILE = "MAIN.cbl";

    @TempDir
    Path directory;

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(ReportWriterTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String program(List<String> report, String... procedure) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. REPORTER.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT RPT-FILE ASSIGN TO RPTDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  RPT-FILE",
                "    REPORT IS SALES-REPORT.",
                "WORKING-STORAGE SECTION.",
                "01  WS-N     PIC 9(4).",
                "01  WS-TEXT  PIC X(6) VALUE 'ITEM-A'.",
                "REPORT SECTION.")) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : report) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : List.of(
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    OPEN OUTPUT RPT-FILE.")) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : procedure) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : List.of(
                "    CLOSE RPT-FILE",
                "    STOP RUN.")) {
            FixedFormatSource.append(sb, line);
        }
        return sb.toString();
    }

    private String run(String source) {
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

    /** この試験の報告書の行の幅。{@code COLUMN 1} に {@code PIC X(6)} を置いている。 */
    private static final int WIDTH = 6;

    /**
     * 書き出した紙。行の切れ目を {@code |}、改頁を {@code ^} で表す。
     *
     * <p>データセットは決まった長さのレコードの並びで、行の区切り文字を持たない。
     * だから幅で切る。中身はホストの符号系なので、読むときも同じ符号系で読む。
     * 改頁は改頁文字だけのレコードである (暫定判断 P-063)。
     */
    private String paper() {
        try {
            byte[] bytes = Files.readAllBytes(directory.resolve("RPTDD"));
            StringBuilder out = new StringBuilder();
            for (int at = 0; at < bytes.length; at += WIDTH) {
                byte[] record = new byte[Math.min(WIDTH, bytes.length - at)];
                System.arraycopy(bytes, at, record, 0, record.length);
                out.append(CodePages.DEFAULT.decode(record).replace("\f", "^").stripTrailing())
                        .append('|');
            }
            return out.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final List<String> ONE_DETAIL = List.of(
            "RD  SALES-REPORT",
            "    PAGE LIMIT IS 4 LINES.",
            "01  SALES-LINE",
            "    TYPE IS DETAIL.",
            "    03  LINE NUMBER IS PLUS 1",
            "        COLUMN NUMBER IS 1",
            "        PIC X(6)",
            "        SOURCE IS WS-TEXT.");

    @Test
    @DisplayName("INITIATE のあと LINE-COUNTER は 0、PAGE-COUNTER は 1 (FR-214)")
    void initiateResetsTheCounters() {
        // RW101A INIT-TEST-01 / 02 がこの 2 つだけを確かめている
        assertEquals("0000|0001|", run(program(ONE_DETAIL,
                "    INITIATE SALES-REPORT",
                "    MOVE LINE-COUNTER TO WS-N DISPLAY WS-N",
                "    MOVE PAGE-COUNTER TO WS-N DISPLAY WS-N",
                "    TERMINATE SALES-REPORT.")));
    }

    @Test
    @DisplayName("GENERATE のあと LINE-COUNTER はその行を置いた行番号 (FR-214)")
    void lineCounterFollowsThePresentedLine() {
        // RW101A GENER-DETAIL-LINE が「LINE-COUNTER = 置いた行番号」を数え上げている
        assertEquals("0001|0002|0003|", run(program(ONE_DETAIL,
                "    INITIATE SALES-REPORT",
                "    GENERATE SALES-LINE",
                "    MOVE LINE-COUNTER TO WS-N DISPLAY WS-N",
                "    GENERATE SALES-LINE",
                "    MOVE LINE-COUNTER TO WS-N DISPLAY WS-N",
                "    GENERATE SALES-LINE",
                "    MOVE LINE-COUNTER TO WS-N DISPLAY WS-N",
                "    TERMINATE SALES-REPORT.")));
    }

    @Test
    @DisplayName("紙の上には 1 行に 1 本ずつ並ぶ (FR-214)")
    void theLinesArePresentedOnPaper() {
        run(program(ONE_DETAIL,
                "    INITIATE SALES-REPORT",
                "    GENERATE SALES-LINE",
                "    GENERATE SALES-LINE",
                "    TERMINATE SALES-REPORT."));
        assertEquals("ITEM-A|ITEM-A|", paper());
    }

    @Test
    @DisplayName("LAST DETAIL を越えると頁を改め、PAGE-COUNTER が 1 増える (FR-214)")
    void passingTheLastDetailTurnsThePage() {
        // 紙は 4 行。5 本目は次の頁の 1 行目に来る
        assertEquals("0004|0001|0001|0002|", run(program(ONE_DETAIL,
                "    INITIATE SALES-REPORT",
                "    GENERATE SALES-LINE",
                "    GENERATE SALES-LINE",
                "    GENERATE SALES-LINE",
                "    GENERATE SALES-LINE",
                "    MOVE LINE-COUNTER TO WS-N DISPLAY WS-N",
                "    MOVE PAGE-COUNTER TO WS-N DISPLAY WS-N",
                "    GENERATE SALES-LINE",
                "    MOVE LINE-COUNTER TO WS-N DISPLAY WS-N",
                "    MOVE PAGE-COUNTER TO WS-N DISPLAY WS-N",
                "    TERMINATE SALES-REPORT.")));
        // 改頁のあとに空行は出ない。改頁の行のすぐ次が 5 本目である
        assertEquals("ITEM-A|ITEM-A|ITEM-A|ITEM-A|^|ITEM-A|", paper());
    }

    private static final List<String> HEADED = List.of(
            "RD  SALES-REPORT",
            "    PAGE LIMIT 6 LINES",
            "    HEADING 1",
            "    FIRST DETAIL 3",
            "    LAST DETAIL 5.",
            "01  SALES-HEAD",
            "    LINE NUMBER IS 1",
            "    TYPE IS PAGE HEADING.",
            "    03  COLUMN 1 PIC X(6) VALUE 'HEADER'.",
            "01  SALES-LINE",
            "    LINE NUMBER IS PLUS 1",
            "    TYPE IS DETAIL.",
            "    03  COLUMN 1 PIC X(6) SOURCE IS WS-TEXT.");

    @Test
    @DisplayName("最初の GENERATE が頁の見出しを先に置く (FR-214)")
    void theFirstGenerateAlsoPresentsThePageHeading() {
        // 見出しは 1 行目、本文は FIRST DETAIL の 3 行目から始まる
        assertEquals("0001|0003|", run(program(HEADED,
                "    INITIATE SALES-REPORT",
                "    MOVE LINE-COUNTER TO WS-N",
                "    GENERATE SALES-LINE",
                "    MOVE 1 TO WS-N DISPLAY WS-N",
                "    MOVE LINE-COUNTER TO WS-N DISPLAY WS-N",
                "    TERMINATE SALES-REPORT.")));
        assertEquals("HEADER||ITEM-A|", paper());
    }

    @Test
    @DisplayName("頁を改めるたびに見出しを置き直す (FR-214)")
    void thePageHeadingIsPresentedOnEveryPage() {
        run(program(HEADED,
                "    INITIATE SALES-REPORT",
                "    GENERATE SALES-LINE",
                "    GENERATE SALES-LINE",
                "    GENERATE SALES-LINE",
                "    GENERATE SALES-LINE",
                "    TERMINATE SALES-REPORT."));
        // 3・4・5 行目まで置いて頁を改め、次の頁は見出しと 3 行目
        assertEquals("HEADER||ITEM-A|ITEM-A|ITEM-A|^|HEADER||ITEM-A|", paper());
    }

    @Test
    @DisplayName("制御の切れ目を伴う報告書は、書けないと断る (FR-214)")
    void controlBreaksAreRefused() {
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, program(List.of(
                "RD  SALES-REPORT",
                "    CONTROLS ARE FINAL",
                "    PAGE LIMIT IS 4 LINES.",
                "01  SALES-LINE",
                "    TYPE IS DETAIL.",
                "    03  LINE PLUS 1 COLUMN 1 PIC X(6) SOURCE IS WS-TEXT."),
                "    INITIATE SALES-REPORT",
                "    TERMINATE SALES-REPORT."));
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("CONTROL"),
                result.diagnostics().toString());
    }
}
