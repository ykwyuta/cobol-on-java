package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.file.DataSetCatalog;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.FileOperationException;
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
 * ファイル入出力を翻訳して実行し、<b>ファイルに残ったバイト</b>を確かめる
 * (要件 FR-100, FR-102, FR-103, FR-104)。
 *
 * <p>確かめるのは表示された文字ではなくバイト列である。ホストのデータセットは
 * EBCDIC の生バイトであり、そこが合っていなければ L3 互換にならない。
 */
@Tag("V1")
class FileIoGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(FileIoGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    /** 行を固定形式の A 領域から書き始めた 1 本のソースにする。 */
    private static String source(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append("       ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static CobolProgram compile(String source) {
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, source);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            return (CobolProgram) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot load the generated program", e);
        }
    }

    /** 翻訳して実行し、{@code DISPLAY} が出した文字を返す。改行は {@code |} に置き換える。 */
    private static String run(Path directory, String source) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        compile(source).runFresh(ProgramContext.capturing(sink)
                .withCatalog(new DataSetCatalog(directory)));
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
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

    private static final String WRITER = source(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. WRITER.",
            "ENVIRONMENT DIVISION.",
            "INPUT-OUTPUT SECTION.",
            "FILE-CONTROL.",
            "    SELECT OUT-FILE ASSIGN TO OUTDD",
            "        FILE STATUS IS WS-STATUS.",
            "DATA DIVISION.",
            "FILE SECTION.",
            "FD  OUT-FILE.",
            "01  OUT-REC.",
            "    05  OUT-NAME PIC X(5).",
            "    05  OUT-NUM  PIC 9(3).",
            "WORKING-STORAGE SECTION.",
            "01  WS-STATUS PIC XX.",
            "PROCEDURE DIVISION.",
            "    OPEN OUTPUT OUT-FILE.",
            "    DISPLAY WS-STATUS.",
            "    MOVE 'ALPHA' TO OUT-NAME.",
            "    MOVE 123 TO OUT-NUM.",
            "    WRITE OUT-REC.",
            "    MOVE 'BETA' TO OUT-NAME.",
            "    MOVE 7 TO OUT-NUM.",
            "    WRITE OUT-REC.",
            "    CLOSE OUT-FILE.",
            "    DISPLAY WS-STATUS.",
            "    STOP RUN.");

    @Test
    @DisplayName("CLOSE WITH LOCK で閉じたファイルは二度と開けない (FR-102)")
    void aFileClosedWithLockCannotBeOpenedAgain(@TempDir Path directory) {
        // 錠を掛けたら、開き直そうとすると状態コード 38 が立つ
        assertEquals("00|38|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. LOCKER.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT OUT-FILE ASSIGN TO OUTDD",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  OUT-FILE.",
                "01  OUT-REC PIC X(8).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "PROCEDURE DIVISION.",
                "    OPEN OUTPUT OUT-FILE.",
                "    DISPLAY WS-STATUS.",
                "    CLOSE OUT-FILE WITH LOCK.",
                "    OPEN INPUT OUT-FILE.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("OPEN ... NO REWIND は開くが、巻が無いので 07 が立つ (FR-102)")
    void openWithNoRewindReportsSevenO7(@TempDir Path directory) {
        // 巻き戻さないという指示は、巻を持たない媒体では行いようがない。開けたことは
        // 変わらないので 07 だけが違う。閉じるほうはふつうの CLOSE なので 00 である
        assertEquals("07|00|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. TAPEOPEN.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT OUT-FILE ASSIGN TO OUTDD",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  OUT-FILE.",
                "01  OUT-REC PIC X(8).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "PROCEDURE DIVISION.",
                "    OPEN OUTPUT OUT-FILE WITH NO REWIND.",
                "    DISPLAY WS-STATUS.",
                "    CLOSE OUT-FILE.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("OPEN INPUT ... REVERSED は断る (FR-102)")
    void openReversedIsRejected() {
        // 逆から読むという指示である。黙って順に読めば違う答えを返す。巻の扱いのように
        // 「何も起きなかった」で済む話ではないので、近いことをするより断るのが正しい
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. BACKWARD.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IN-FILE ASSIGN TO INDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IN-FILE.",
                "01  IN-REC PIC X(8).",
                "PROCEDURE DIVISION.",
                "    OPEN INPUT IN-FILE REVERSED.",
                "    STOP RUN."));

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("REVERSED"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("開けなかった OPEN ... NO REWIND は、07 ではなく誤りを伝える (FR-102, FR-103)")
    void openWithNoRewindKeepsTheFailureStatus(@TempDir Path directory) {
        // 07 に置き換えるのは成功したときだけである。無いファイルを読もうとしたことの
        // ほうが、巻き戻さなかったことより伝えるべきことである
        assertEquals("35|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. NOFILE.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IN-FILE ASSIGN TO INDD",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IN-FILE.",
                "01  IN-REC PIC X(8).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "PROCEDURE DIVISION.",
                "    OPEN INPUT IN-FILE WITH NO REWIND.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("CLOSE ... NO REWIND は閉じるが、巻が無いので 07 が立つ (FR-102)")
    void closeWithNoRewindClosesTheFileAndReportsSevenO7(@TempDir Path directory) {
        // 巻き戻さないという指示は、巻を持たない媒体では行いようがない。規格は
        // <b>閉じたが巻の操作はしていない</b>ことを 07 で伝えるよう定めている。
        // 07 は成功の側なので、錠は掛からず開き直せる
        assertEquals("07|00|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. NOREWIND.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT OUT-FILE ASSIGN TO OUTDD",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  OUT-FILE.",
                "01  OUT-REC PIC X(8).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "PROCEDURE DIVISION.",
                "    OPEN OUTPUT OUT-FILE.",
                "    CLOSE OUT-FILE WITH NO REWIND.",
                "    DISPLAY WS-STATUS.",
                "    OPEN INPUT OUT-FILE.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("CLOSE ... UNIT はファイルを閉じない。書き足しが続けられる (FR-102)")
    void closeUnitLeavesTheFileOpen(@TempDir Path directory) {
        // REEL / UNIT は「次の巻へ移れ」であって「閉じよ」ではない。巻を持たない媒体では
        // 移る先がないので何も起きず、07 だけが立つ。ファイルは<b>開いたまま</b>である。
        // CCVS85 の SQ124A はここを見ている — CLOSE UNIT のあとに 2 本目を書き足す
        assertEquals("07|00|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. CLOSEUNI.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT OUT-FILE ASSIGN TO OUTDD",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  OUT-FILE.",
                "01  OUT-REC PIC X(8).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "PROCEDURE DIVISION.",
                "    OPEN OUTPUT OUT-FILE.",
                "    MOVE \"ALPHA123\" TO OUT-REC.",
                "    WRITE OUT-REC.",
                "    CLOSE OUT-FILE UNIT.",
                "    DISPLAY WS-STATUS.",
                "    MOVE \"BETA 007\" TO OUT-REC.",
                "    WRITE OUT-REC.",
                "    CLOSE OUT-FILE.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
        // CLOSE UNIT が本当に閉じていなければ、2 本目は 1 本目と同じファイルに続く
        assertArrayEquals(ebcdic("ALPHA123BETA 007"), bytesOf(directory.resolve("OUTDD")));
    }

    @Test
    @DisplayName("CLOSE ... REEL は読む位置も動かさない (FR-102)")
    void closeReelDoesNotRepositionTheFile(@TempDir Path directory) {
        // 巻を送ったのなら、次に読むのは次の巻の先頭である。巻が無いのだから位置も
        // 動かない。OPEN INPUT の直後に CLOSE REEL しても、1 本目から読める
        assertEquals("00|07|ALPHA123|BETA 007|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. CLOSEREE.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT OUT-FILE ASSIGN TO OUTDD",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  OUT-FILE.",
                "01  OUT-REC PIC X(8).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "PROCEDURE DIVISION.",
                "    OPEN OUTPUT OUT-FILE.",
                "    MOVE \"ALPHA123\" TO OUT-REC.",
                "    WRITE OUT-REC.",
                "    MOVE \"BETA 007\" TO OUT-REC.",
                "    WRITE OUT-REC.",
                "    CLOSE OUT-FILE.",
                "    OPEN INPUT OUT-FILE.",
                "    DISPLAY WS-STATUS.",
                "    CLOSE OUT-FILE REEL.",
                "    DISPLAY WS-STATUS.",
                "    READ OUT-FILE AT END DISPLAY \"END\" END-READ.",
                "    DISPLAY OUT-REC.",
                "    READ OUT-FILE AT END DISPLAY \"END\" END-READ.",
                "    DISPLAY OUT-REC.",
                "    CLOSE OUT-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("開いていないファイルへの CLOSE ... UNIT は 42 になる (FR-102, FR-103)")
    void closeUnitOnAClosedFileIsNotOpen(@TempDir Path directory) {
        // 閉じないのだから成功、とはいかない。巻を送る相手が開いていない
        assertEquals("42|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. UNITSHUT.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT OUT-FILE ASSIGN TO OUTDD",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  OUT-FILE.",
                "01  OUT-REC PIC X(8).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "PROCEDURE DIVISION.",
                "    OPEN OUTPUT OUT-FILE.",
                "    CLOSE OUT-FILE.",
                "    CLOSE OUT-FILE UNIT.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("WRITE したレコードは固定長で並ぶ (FR-102, FR-110)")
    void writtenRecordsAreFixedLength(@TempDir Path directory) {
        assertEquals("00|00|", run(directory, WRITER));
        assertArrayEquals(ebcdic("ALPHA123BETA 007"),
                bytesOf(directory.resolve("OUTDD")));
    }

    @Test
    @DisplayName("書いたファイルには属性のサイドカーが残る (FR-110)")
    void theSidecarRecordsTheAttributes(@TempDir Path directory) {
        run(directory, WRITER);
        try {
            assertEquals(List.of("recfm=F", "lrecl=8", "codepage=IBM-1047"),
                    Files.readAllLines(directory.resolve("OUTDD.meta")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final String READER = source(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. READER.",
            "ENVIRONMENT DIVISION.",
            "INPUT-OUTPUT SECTION.",
            "FILE-CONTROL.",
            "    SELECT IN-FILE ASSIGN TO INDD",
            "        FILE STATUS IS WS-STATUS.",
            "DATA DIVISION.",
            "FILE SECTION.",
            "FD  IN-FILE.",
            "01  IN-REC.",
            "    05  IN-NAME PIC X(5).",
            "    05  IN-NUM  PIC 9(3).",
            "WORKING-STORAGE SECTION.",
            "01  WS-STATUS PIC XX.",
            "01  WS-DONE   PIC X VALUE 'N'.",
            "01  WS-TOTAL  PIC 9(5) VALUE 0.",
            "PROCEDURE DIVISION.",
            "    OPEN INPUT IN-FILE.",
            "    PERFORM UNTIL WS-DONE = 'Y'",
            "        READ IN-FILE",
            "            AT END MOVE 'Y' TO WS-DONE",
            "            NOT AT END",
            "                DISPLAY IN-NAME",
            "                ADD IN-NUM TO WS-TOTAL",
            "        END-READ",
            "    END-PERFORM.",
            "    CLOSE IN-FILE.",
            "    DISPLAY WS-TOTAL.",
            "    DISPLAY WS-STATUS.",
            "    STOP RUN.");

    @Test
    @DisplayName("READ は AT END までレコードを順に返す (FR-102, FR-103)")
    void readingRunsToTheEnd(@TempDir Path directory) {
        write(directory.resolve("INDD"), ebcdic("ALPHA123BETA 007"));
        write(directory.resolve("INDD.meta"),
                "recfm=F\nlrecl=8\ncodepage=IBM-1047\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("ALPHA|BETA |00130|00|", run(directory, READER));
    }

    @Test
    @DisplayName("書いたファイルはそのまま読み直せる (FR-102, FR-110)")
    void whatWasWrittenCanBeReadBack(@TempDir Path directory) {
        run(directory, WRITER);
        Files.exists(directory.resolve("OUTDD"));
        write(directory.resolve("INDD"), bytesOf(directory.resolve("OUTDD")));
        write(directory.resolve("INDD.meta"), bytesOf(directory.resolve("OUTDD.meta")));
        assertEquals("ALPHA|BETA |00130|00|", run(directory, READER));
    }

    @Test
    @DisplayName("ないファイルを OPEN INPUT すると 35 が返る (FR-103)")
    void openingAMissingFileReportsThirtyFive(@TempDir Path directory) {
        assertEquals("35|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MISSING.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IN-FILE ASSIGN TO NOSUCH",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IN-FILE.",
                "01  IN-REC PIC X(8).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "PROCEDURE DIVISION.",
                "    OPEN INPUT IN-FILE.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("FILE STATUS を書いていなければ異常で止まる (FR-104)")
    void withoutFileStatusAFailureStopsTheRun(@TempDir Path directory) {
        String program = source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. UNCHECKED.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IN-FILE ASSIGN TO NOSUCH.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IN-FILE.",
                "01  IN-REC PIC X(8).",
                "PROCEDURE DIVISION.",
                "    OPEN INPUT IN-FILE.",
                "    STOP RUN.");
        FileOperationException failure = assertThrows(FileOperationException.class,
                () -> run(directory, program));
        assertTrue(failure.getMessage().contains("35"), failure.getMessage());
    }

    @Test
    @DisplayName("READ INTO と WRITE FROM は領域をまたぐ転記である (FR-102)")
    void intoAndFromAreMovesAcrossTheRecordArea(@TempDir Path directory) {
        assertEquals("00|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. COPYING.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT OUT-FILE ASSIGN TO OUTDD",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  OUT-FILE.",
                "01  OUT-REC PIC X(6).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "01  WS-LINE   PIC X(6) VALUE 'HELLO!'.",
                "PROCEDURE DIVISION.",
                "    OPEN OUTPUT OUT-FILE.",
                "    WRITE OUT-REC FROM WS-LINE.",
                "    CLOSE OUT-FILE.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
        assertArrayEquals(ebcdic("HELLO!"), bytesOf(directory.resolve("OUTDD")));
    }

    @Test
    @DisplayName("行順編成の区切りはコードページの改行である (FR-102, FR-110)")
    void lineSequentialUsesTheCodePageNewline(@TempDir Path directory) {
        run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. LINESEQ.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT OUT-FILE ASSIGN TO LINEDD",
                "        ORGANIZATION IS LINE SEQUENTIAL.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  OUT-FILE.",
                "01  OUT-REC PIC X(6).",
                "PROCEDURE DIVISION.",
                "    OPEN OUTPUT OUT-FILE.",
                "    MOVE 'ONE' TO OUT-REC.",
                "    WRITE OUT-REC.",
                "    MOVE 'TWO' TO OUT-REC.",
                "    WRITE OUT-REC.",
                "    CLOSE OUT-FILE.",
                "    STOP RUN."));
        // 行順は末尾の空白を落とし、IBM-1047 の改行 0x15 で区切る
        byte[] expected = {
            ebcdic("ONE")[0], ebcdic("ONE")[1], ebcdic("ONE")[2], 0x15,
            ebcdic("TWO")[0], ebcdic("TWO")[1], ebcdic("TWO")[2], 0x15,
        };
        assertArrayEquals(expected, bytesOf(directory.resolve("LINEDD")));
    }

    @Test
    @DisplayName("SELECT のないファイル記述は誤りである (FR-100)")
    void anFdWithoutASelectIsAnError() {
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. LONELY.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IN-FILE.",
                "01  IN-REC PIC X(8).",
                "PROCEDURE DIVISION.",
                "    STOP RUN."));
        assertTrue(result.diagnostics().toString().contains("no SELECT for IN-FILE"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("FD のないファイルの指定は誤りである (FR-100)")
    void aSelectWithoutAnFdIsAnError() {
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. LONELY.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IN-FILE ASSIGN TO INDD.",
                "DATA DIVISION.",
                "PROCEDURE DIVISION.",
                "    STOP RUN."));
        assertTrue(result.diagnostics().toString().contains("no FD for IN-FILE"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("1 つの FD の複数のレコード記述は同じ領域に重なる (FR-100)")
    void severalRecordDescriptionsShareOneArea(@TempDir Path directory) {
        assertEquals("ABCDEF|ABC|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. OVERLAY.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT OUT-FILE ASSIGN TO OUTDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  OUT-FILE.",
                "01  LONG-REC  PIC X(6).",
                "01  SHORT-REC PIC X(3).",
                "PROCEDURE DIVISION.",
                "    MOVE 'ABCDEF' TO LONG-REC.",
                "    DISPLAY LONG-REC.",
                "    DISPLAY SHORT-REC.",
                "    STOP RUN.")));
    }

    // ---- 行送り (要件 FR-102、暫定判断 P-063) ----

    /** 印字するファイルへ 3 本書く。行送りの書き方だけを差し替える。 */
    private static String printer(String... writes) {
        String[] head = {
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. PRINTER.",
            "ENVIRONMENT DIVISION.",
            "INPUT-OUTPUT SECTION.",
            "FILE-CONTROL.",
            "    SELECT PRINT-FILE ASSIGN TO PRTDD",
            "        ORGANIZATION IS LINE SEQUENTIAL.",
            "DATA DIVISION.",
            "FILE SECTION.",
            "FD  PRINT-FILE.",
            "01  PRINT-REC PIC X(3).",
            "WORKING-STORAGE SECTION.",
            "01  WS-LINES PIC 9 VALUE 3.",
            "PROCEDURE DIVISION.",
            "    OPEN OUTPUT PRINT-FILE.",
        };
        String[] tail = {
            "    CLOSE PRINT-FILE.",
            "    STOP RUN.",
        };
        String[] lines = new String[head.length + writes.length + tail.length];
        System.arraycopy(head, 0, lines, 0, head.length);
        System.arraycopy(writes, 0, lines, head.length, writes.length);
        System.arraycopy(tail, 0, lines, head.length + writes.length, tail.length);
        return source(lines);
    }

    /** 行順ファイルを読んで、行の並びへ戻す。 */
    private static List<String> linesOf(Path path) {
        List<String> out = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (byte b : bytesOf(path)) {
            if (b == 0x15) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(CodePages.DEFAULT.decode(new byte[] {b}));
            }
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    @Test
    @DisplayName("AFTER ADVANCING 1 LINE は 1 行ずつ書く (FR-102)")
    void singleSpacingWritesOneLineEach(@TempDir Path directory) {
        run(directory, printer(
                "    MOVE 'ONE' TO PRINT-REC.",
                "    WRITE PRINT-REC AFTER ADVANCING 1 LINE.",
                "    MOVE 'TWO' TO PRINT-REC.",
                "    WRITE PRINT-REC AFTER ADVANCING 1 LINE."));

        assertEquals(List.of("ONE", "TWO"), linesOf(directory.resolve("PRTDD")));
    }

    @Test
    @DisplayName("AFTER ADVANCING 2 LINES は 1 行空ける (FR-102)")
    void doubleSpacingLeavesOneBlankLine(@TempDir Path directory) {
        // 2 行送って印字するとき、文字が乗るのは最後の 1 行だけである
        run(directory, printer(
                "    MOVE 'ONE' TO PRINT-REC.",
                "    WRITE PRINT-REC AFTER ADVANCING 1 LINE.",
                "    MOVE 'TWO' TO PRINT-REC.",
                "    WRITE PRINT-REC AFTER ADVANCING 2 LINES."));

        assertEquals(List.of("ONE", "", "TWO"), linesOf(directory.resolve("PRTDD")));
    }

    @Test
    @DisplayName("BEFORE ADVANCING は書いてから送る (FR-102)")
    void beforeAdvancingWritesFirst(@TempDir Path directory) {
        run(directory, printer(
                "    MOVE 'ONE' TO PRINT-REC.",
                "    WRITE PRINT-REC BEFORE ADVANCING 3 LINES.",
                "    MOVE 'TWO' TO PRINT-REC.",
                "    WRITE PRINT-REC AFTER ADVANCING 1 LINE."));

        assertEquals(List.of("ONE", "", "", "TWO"), linesOf(directory.resolve("PRTDD")));
    }

    @Test
    @DisplayName("送る行数は実行時に決まってもよい (FR-102)")
    void theNumberOfLinesCanBeAnItem(@TempDir Path directory) {
        run(directory, printer(
                "    MOVE 'ONE' TO PRINT-REC.",
                "    WRITE PRINT-REC AFTER ADVANCING 1 LINE.",
                "    MOVE 'TWO' TO PRINT-REC.",
                "    WRITE PRINT-REC AFTER ADVANCING WS-LINES LINES."));

        // WS-LINES は 3 である
        assertEquals(List.of("ONE", "", "", "TWO"), linesOf(directory.resolve("PRTDD")));
    }

    @Test
    @DisplayName("ADVANCING PAGE は改頁の行を置く (FR-102, 暫定判断 P-063)")
    void advancingPageWritesAPageBreak(@TempDir Path directory) {
        // ホストは紙送りの制御文字をレコードの先頭に持つ。その桁取りを確かめて
        // いないので、いまは改頁の文字だけの行を置く
        run(directory, printer(
                "    MOVE 'ONE' TO PRINT-REC.",
                "    WRITE PRINT-REC AFTER ADVANCING 1 LINE.",
                "    MOVE 'TWO' TO PRINT-REC.",
                "    WRITE PRINT-REC AFTER ADVANCING PAGE."));

        assertEquals(List.of("ONE", "\f", "TWO"), linesOf(directory.resolve("PRTDD")));
    }

    @Test
    @DisplayName("ADVANCING 0 は空行を足さない (FR-102, 暫定判断 P-063)")
    void advancingZeroAddsNothing(@TempDir Path directory) {
        // 紙の上では重ね印字になる。行の並びでは表せないので、そのまま次の行になる
        run(directory, printer(
                "    MOVE 'ONE' TO PRINT-REC.",
                "    WRITE PRINT-REC AFTER ADVANCING 1 LINE.",
                "    MOVE 'TWO' TO PRINT-REC.",
                "    WRITE PRINT-REC AFTER ADVANCING 0 LINES."));

        assertEquals(List.of("ONE", "TWO"), linesOf(directory.resolve("PRTDD")));
    }

    @Test
    @DisplayName("決まった長さのレコードなら空行も空白で埋める (FR-102)")
    void aFixedLengthBlankLineIsSpaces(@TempDir Path directory) {
        run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. FIXEDPRT.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT PRINT-FILE ASSIGN TO FIXDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  PRINT-FILE.",
                "01  PRINT-REC PIC X(3).",
                "PROCEDURE DIVISION.",
                "    OPEN OUTPUT PRINT-FILE.",
                "    MOVE 'ONE' TO PRINT-REC.",
                "    WRITE PRINT-REC AFTER ADVANCING 2 LINES.",
                "    CLOSE PRINT-FILE.",
                "    STOP RUN."));

        // 行の切れ目を持たない様式では、空行は空白のレコードである
        assertArrayEquals(ebcdic("   ONE"), bytesOf(directory.resolve("FIXDD")));
    }

    @Test
    @DisplayName("SAME RECORD AREA で結んだファイルはレコード領域を 1 つ持つ (FR-100)")
    void filesJoinedBySameRecordAreaShareOneRecordArea(@TempDir Path directory) {
        // 片方へ読み込めば、もう片方の記述でそのまま読める。CCVS85 の SG204A は
        // READ FILE3 のあと転記せずに RELEASE S3 と書いている
        assertEquals("[ABC][ABC]|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SAMEREC.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT ONE-FILE ASSIGN TO ONEDD.",
                "    SELECT TWO-FILE ASSIGN TO TWODD.",
                "I-O-CONTROL.",
                "    SAME RECORD AREA FOR ONE-FILE TWO-FILE.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  ONE-FILE.",
                "01  ONE-REC PIC X(3).",
                "FD  TWO-FILE.",
                "01  TWO-REC PIC X(3).",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    MOVE 'ABC' TO ONE-REC.",
                "    DISPLAY '[' ONE-REC '][' TWO-REC ']'.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("結んでいないファイルのレコード領域は別である (FR-100)")
    void filesNotJoinedKeepTheirOwnRecordArea(@TempDir Path directory) {
        assertEquals("[ABC][   ]|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. APART.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT ONE-FILE ASSIGN TO ONEDD.",
                "    SELECT TWO-FILE ASSIGN TO TWODD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  ONE-FILE.",
                "01  ONE-REC PIC X(3).",
                "FD  TWO-FILE.",
                "01  TWO-REC PIC X(3).",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    MOVE SPACES TO TWO-REC.",
                "    MOVE 'ABC' TO ONE-REC.",
                "    DISPLAY '[' ONE-REC '][' TWO-REC ']'.",
                "    STOP RUN.")));
    }
}
