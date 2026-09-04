package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
                "PROGRAM-ID. LINES.",
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
}
