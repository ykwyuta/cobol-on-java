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
 * 相対編成を翻訳して実行する (要件 FR-100, FR-101, FR-102, FR-103、設計 80 の第 3 段)。
 *
 * <p>番号が住所である。消してもあとのレコードは動かず、消したところは空きスロットとして残る。
 */
@Tag("V1")
class FileIoRelativeTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(FileIoRelativeTest.class.getClassLoader());
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

    /** 相対編成の宣言。アクセス様式だけを差し替える。 */
    private static String[] declaration(String access) {
        return new String[] {
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. RELTEST.",
            "ENVIRONMENT DIVISION.",
            "INPUT-OUTPUT SECTION.",
            "FILE-CONTROL.",
            "    SELECT R-FILE ASSIGN TO RELDD",
            "        ORGANIZATION IS RELATIVE",
            "        ACCESS MODE IS " + access,
            "        RELATIVE KEY IS WS-RRN",
            "        FILE STATUS IS WS-STATUS.",
            "DATA DIVISION.",
            "FILE SECTION.",
            "FD  R-FILE.",
            "01  R-REC PIC X(3).",
            "WORKING-STORAGE SECTION.",
            "01  WS-STATUS PIC XX.",
            "01  WS-RRN    PIC 9(3) COMP.",
            "01  WS-DONE   PIC X VALUE 'N'.",
            "PROCEDURE DIVISION.",
        };
    }

    private static String program(String access, String... procedure) {
        String[] head = declaration(access);
        String[] all = new String[head.length + procedure.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(procedure, 0, all, head.length, procedure.length);
        return source(all);
    }

    /** 1 番と 4 番だけを持つデータセットを作る。 */
    private static void seed(Path directory) {
        write(directory.resolve("RELDD"), ebcdic("aaa      ddd"));
        write(directory.resolve("RELDD.meta"),
                "recfm=F\nlrecl=3\ncodepage=IBM-1047\nempty=2,3\n"
                        .getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("番号を指定して書けば穴が残る (FR-101)")
    void randomWritesLeaveGaps(@TempDir Path directory) {
        assertEquals("00|", run(directory, program("RANDOM",
                "    OPEN OUTPUT R-FILE.",
                "    MOVE 'aaa' TO R-REC.",
                "    MOVE 1 TO WS-RRN.",
                "    WRITE R-REC.",
                "    MOVE 'ddd' TO R-REC.",
                "    MOVE 4 TO WS-RRN.",
                "    WRITE R-REC.",
                "    CLOSE R-FILE.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
        assertArrayEquals(ebcdic("aaa      ddd"), bytesOf(directory.resolve("RELDD")));
        try {
            assertTrue(Files.readAllLines(directory.resolve("RELDD.meta")).contains("empty=2,3"),
                    Files.readAllLines(directory.resolve("RELDD.meta")).toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("番号で読める (FR-101)")
    void recordsAreReadByNumber(@TempDir Path directory) {
        seed(directory);
        assertEquals("ddd|00|", run(directory, program("RANDOM",
                "    OPEN INPUT R-FILE.",
                "    MOVE 4 TO WS-RRN.",
                "    READ R-FILE.",
                "    DISPLAY R-REC.",
                "    DISPLAY WS-STATUS.",
                "    CLOSE R-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("ない番号を読めば INVALID KEY へ抜ける (FR-103)")
    void readingAMissingNumberTakesTheInvalidKeyPath(@TempDir Path directory) {
        seed(directory);
        assertEquals("NONE|23|", run(directory, program("RANDOM",
                "    OPEN INPUT R-FILE.",
                "    MOVE 2 TO WS-RRN.",
                "    READ R-FILE",
                "        INVALID KEY DISPLAY 'NONE'",
                "        NOT INVALID KEY DISPLAY R-REC",
                "    END-READ.",
                "    DISPLAY WS-STATUS.",
                "    CLOSE R-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("順次読みは空きを飛ばし、番号を鍵の項目へ返す (FR-101, FR-102)")
    void sequentialReadingSkipsGapsAndReportsNumbers(@TempDir Path directory) {
        seed(directory);
        assertEquals("001aaa|004ddd|", run(directory, program("SEQUENTIAL",
                "    OPEN INPUT R-FILE.",
                "    PERFORM UNTIL WS-DONE = 'Y'",
                "        READ R-FILE",
                "            AT END MOVE 'Y' TO WS-DONE",
                "            NOT AT END DISPLAY WS-RRN R-REC",
                "        END-READ",
                "    END-PERFORM.",
                "    CLOSE R-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("消しても残りの番号はずれない (FR-100, FR-101)")
    void deletingKeepsTheOtherNumbers(@TempDir Path directory) {
        write(directory.resolve("RELDD"), ebcdic("aaabbbccc"));
        write(directory.resolve("RELDD.meta"),
                "recfm=F\nlrecl=3\ncodepage=IBM-1047\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("00|ccc|", run(directory, program("RANDOM",
                "    OPEN I-O R-FILE.",
                "    MOVE 2 TO WS-RRN.",
                "    DELETE R-FILE.",
                "    DISPLAY WS-STATUS.",
                "    MOVE 3 TO WS-RRN.",
                "    READ R-FILE.",
                "    DISPLAY R-REC.",
                "    CLOSE R-FILE.",
                "    STOP RUN.")));
        assertArrayEquals(ebcdic("aaa   ccc"), bytesOf(directory.resolve("RELDD")));
    }

    @Test
    @DisplayName("ない番号を消せば INVALID KEY へ抜ける (FR-103)")
    void deletingAMissingNumberTakesTheInvalidKeyPath(@TempDir Path directory) {
        seed(directory);
        assertEquals("NONE|23|", run(directory, program("RANDOM",
                "    OPEN I-O R-FILE.",
                "    MOVE 2 TO WS-RRN.",
                "    DELETE R-FILE",
                "        INVALID KEY DISPLAY 'NONE'",
                "    END-DELETE.",
                "    DISPLAY WS-STATUS.",
                "    CLOSE R-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("使われている番号へ書けば 22 になる (FR-103)")
    void writingOverAnExistingRecordReportsTwentyTwo(@TempDir Path directory) {
        seed(directory);
        assertEquals("DUP|22|", run(directory, program("RANDOM",
                "    OPEN I-O R-FILE.",
                "    MOVE 'xxx' TO R-REC.",
                "    MOVE 1 TO WS-RRN.",
                "    WRITE R-REC",
                "        INVALID KEY DISPLAY 'DUP'",
                "    END-WRITE.",
                "    DISPLAY WS-STATUS.",
                "    CLOSE R-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("番号を指定して書き換えられる (FR-101)")
    void recordsAreRewrittenByNumber(@TempDir Path directory) {
        seed(directory);
        assertEquals("00|", run(directory, program("RANDOM",
                "    OPEN I-O R-FILE.",
                "    MOVE 'AAA' TO R-REC.",
                "    MOVE 1 TO WS-RRN.",
                "    REWRITE R-REC.",
                "    DISPLAY WS-STATUS.",
                "    CLOSE R-FILE.",
                "    STOP RUN.")));
        assertArrayEquals(ebcdic("AAA      ddd"), bytesOf(directory.resolve("RELDD")));
    }

    @Test
    @DisplayName("START は読まずに位置を決める (FR-101)")
    void startPositionsWithoutReading(@TempDir Path directory) {
        seed(directory);
        assertEquals("ddd|", run(directory, program("DYNAMIC",
                "    OPEN INPUT R-FILE.",
                "    MOVE 2 TO WS-RRN.",
                "    START R-FILE KEY IS NOT LESS THAN WS-RRN",
                "        INVALID KEY DISPLAY 'NONE'",
                "    END-START.",
                "    READ R-FILE NEXT",
                "        AT END DISPLAY 'END'",
                "        NOT AT END DISPLAY R-REC",
                "    END-READ.",
                "    CLOSE R-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("満たすレコードがなければ START は INVALID KEY へ抜ける (FR-103)")
    void startWithoutAMatchTakesTheInvalidKeyPath(@TempDir Path directory) {
        seed(directory);
        assertEquals("NONE|23|", run(directory, program("DYNAMIC",
                "    OPEN INPUT R-FILE.",
                "    MOVE 9 TO WS-RRN.",
                "    START R-FILE KEY IS NOT LESS THAN WS-RRN",
                "        INVALID KEY DISPLAY 'NONE'",
                "    END-START.",
                "    DISPLAY WS-STATUS.",
                "    CLOSE R-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("動的アクセスでは READ と READ NEXT が別の意味を持つ (FR-101)")
    void dynamicAccessHasBothKindsOfRead(@TempDir Path directory) {
        seed(directory);
        assertEquals("aaa|ddd|", run(directory, program("DYNAMIC",
                "    OPEN INPUT R-FILE.",
                "    MOVE 1 TO WS-RRN.",
                "    READ R-FILE",
                "        INVALID KEY DISPLAY 'NONE'",
                "        NOT INVALID KEY DISPLAY R-REC",
                "    END-READ.",
                "    READ R-FILE NEXT",
                "        AT END DISPLAY 'END'",
                "        NOT AT END DISPLAY R-REC",
                "    END-READ.",
                "    CLOSE R-FILE.",
                "    STOP RUN.")));
    }

    // ---- 組み合わせの検査 ----

    @Test
    @DisplayName("順アクセスでも NEXT と書いてよい。意味は変わらない (FR-101)")
    void sequentialAccessMayAlsoSayNext(@TempDir Path directory) {
        // 順アクセスの READ はもともと次のレコードを読む。NEXT は<b>印であって
        // 指定ではない</b>。動的アクセスでだけ、鍵で読むのか順に読むのかを分ける。
        // ここを「動的アクセスだけ」と狭く決めていて、正しいプログラムを断っていた
        seed(directory);
        assertEquals("aaa|ddd|", run(directory, program("SEQUENTIAL",
                "    OPEN INPUT R-FILE.",
                "    READ R-FILE NEXT AT END DISPLAY 'END'",
                "        NOT AT END DISPLAY R-REC END-READ.",
                "    READ R-FILE AT END DISPLAY 'END'",
                "        NOT AT END DISPLAY R-REC END-READ.",
                "    CLOSE R-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("乱アクセスに「次」は無い (FR-101)")
    void randomAccessHasNoNextRecord() {
        assertTrue(diagnostics(program("RANDOM",
                "    READ R-FILE NEXT AT END CONTINUE END-READ.",
                "    STOP RUN.")).toString().contains("ACCESS MODE IS RANDOM"));
    }

    @Test
    @DisplayName("順編成に INVALID KEY は書けない (FR-103)")
    void invalidKeyNeedsAKeyedFile() {
        assertTrue(diagnostics(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. PLAIN.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT S-FILE ASSIGN TO SEQDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  S-FILE.",
                "01  S-REC PIC X(3).",
                "PROCEDURE DIVISION.",
                "    WRITE S-REC INVALID KEY CONTINUE END-WRITE.",
                "    STOP RUN.")).toString().contains("INVALID KEY is not allowed here"));
    }

    @Test
    @DisplayName("順編成に START と DELETE は書けない (FR-101)")
    void startAndDeleteNeedAKeyedFile() {
        String head = source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. PLAIN.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT S-FILE ASSIGN TO SEQDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  S-FILE.",
                "01  S-REC PIC X(3).",
                "PROCEDURE DIVISION.");
        assertTrue(diagnostics(head + source("    DELETE S-FILE.", "    STOP RUN."))
                .toString().contains("DELETE requires a RELATIVE or INDEXED file"));
        assertTrue(diagnostics(head + source("    START S-FILE.", "    STOP RUN."))
                .toString().contains("START requires a RELATIVE or INDEXED file"));
    }

    @Test
    @DisplayName("鍵で引くには RELATIVE KEY が要る (FR-101)")
    void keyedAccessNeedsARelativeKey() {
        assertTrue(diagnostics(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. NOKEY.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT R-FILE ASSIGN TO RELDD",
                "        ORGANIZATION IS RELATIVE",
                "        ACCESS MODE IS RANDOM.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  R-FILE.",
                "01  R-REC PIC X(3).",
                "PROCEDURE DIVISION.",
                "    STOP RUN.")).toString().contains("requires a RELATIVE KEY"));
    }

    @Test
    @DisplayName("順編成に RELATIVE KEY は書けない (FR-101)")
    void aRelativeKeyNeedsARelativeFile() {
        assertTrue(diagnostics(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MISPLACED.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT S-FILE ASSIGN TO SEQDD",
                "        RELATIVE KEY IS WS-RRN.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  S-FILE.",
                "01  S-REC PIC X(3).",
                "WORKING-STORAGE SECTION.",
                "01  WS-RRN PIC 9(3) COMP.",
                "PROCEDURE DIVISION.",
                "    STOP RUN.")).toString().contains("requires ORGANIZATION IS RELATIVE"));
    }

    @Test
    @DisplayName("相対編成でも可変長のレコードを持てる。スロットは固定である (FR-100, FR-101)")
    void aRelativeFileMayHoldVaryingRecords(@TempDir Path directory) {
        // 番号が住所である以上、スロットの大きさは変えられない。宣言した最大で取り、
        // 先頭 4 バイトに実際の長さを置く。ホストの可変長 RRDS と同じ形である。
        // ここを「可変長は置けない」と断っていた
        String varying = source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. VARSLOT.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT R-FILE ASSIGN TO RELDD",
                "        ORGANIZATION IS RELATIVE",
                "        ACCESS MODE IS RANDOM",
                "        RELATIVE KEY IS WS-RRN",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  R-FILE",
                "    RECORD IS VARYING IN SIZE FROM 1 TO 5 DEPENDING ON WS-LEN.",
                "01  R-REC PIC X(5).",
                "WORKING-STORAGE SECTION.",
                "01  WS-RRN PIC 9(3) COMP.",
                "01  WS-LEN PIC 9(3) COMP.",
                "01  WS-STATUS PIC XX.",
                "PROCEDURE DIVISION.",
                "    OPEN OUTPUT R-FILE.",
                "    MOVE 'ab' TO R-REC MOVE 2 TO WS-LEN MOVE 1 TO WS-RRN.",
                "    WRITE R-REC.",
                "    MOVE 'cdefg' TO R-REC MOVE 5 TO WS-LEN MOVE 3 TO WS-RRN.",
                "    WRITE R-REC.",
                "    CLOSE R-FILE.",
                "    OPEN INPUT R-FILE.",
                "    MOVE 3 TO WS-RRN.",
                "    READ R-FILE INVALID KEY DISPLAY 'NONE'",
                "        NOT INVALID KEY DISPLAY R-REC END-READ.",
                "    MOVE 1 TO WS-RRN.",
                "    READ R-FILE INVALID KEY DISPLAY 'NONE'",
                "        NOT INVALID KEY DISPLAY R-REC END-READ.",
                "    CLOSE R-FILE.",
                "    STOP RUN.");

        // 2 番は書いていないので空きスロットのまま残る
        assertEquals("cdefg|ab   |", run(directory, varying));
        assertEquals(27, bytesOf(directory.resolve("RELDD")).length);
    }

    @Test
    @DisplayName("索引編成には RECORD KEY が要る (FR-100)")
    void indexedNeedsARecordKey() {
        assertTrue(diagnostics(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. KSDS.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT K-FILE ASSIGN TO KSDSDD",
                "        ORGANIZATION IS INDEXED.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  K-FILE.",
                "01  K-REC PIC X(3).",
                "PROCEDURE DIVISION.",
                "    STOP RUN.")).toString().contains("requires a RECORD KEY"));
    }
}
