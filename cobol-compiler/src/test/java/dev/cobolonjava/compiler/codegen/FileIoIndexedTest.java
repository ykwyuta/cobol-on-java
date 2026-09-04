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
 * 索引編成を翻訳して実行する (要件 FR-100, FR-101, FR-102, FR-103、設計 80 の第 3 段)。
 *
 * <p>鍵はレコードの中にある。住所ではなく持ち物なので、書き換えても場所は変わらないが、
 * 主鍵を変えることはできない。別のレコードになってしまうからである。
 */
@Tag("V1")
class FileIoIndexedTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(FileIoIndexedTest.class.getClassLoader());
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

    /**
     * 索引編成の宣言。レコードは 8 バイトで、主鍵が先頭 3 バイト、副鍵が次の 2 バイトである。
     */
    private static String[] declaration(String access, String... extra) {
        List<String> lines = new java.util.ArrayList<>(List.of(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. IDXTEST.",
            "ENVIRONMENT DIVISION.",
            "INPUT-OUTPUT SECTION.",
            "FILE-CONTROL.",
            "    SELECT K-FILE ASSIGN TO KSDSDD",
            "        ORGANIZATION IS INDEXED",
            "        ACCESS MODE IS " + access,
            "        RECORD KEY IS K-ID"));
        lines.addAll(List.of(extra));
        lines.addAll(List.of(
            "        FILE STATUS IS WS-STATUS.",
            "DATA DIVISION.",
            "FILE SECTION.",
            "FD  K-FILE.",
            "01  K-REC.",
            "    05  K-ID   PIC X(3).",
            "    05  K-DEPT PIC XX.",
            "    05  K-NAME PIC XXX.",
            "WORKING-STORAGE SECTION.",
            "01  WS-STATUS PIC XX.",
            "01  WS-DONE   PIC X VALUE 'N'.",
            "PROCEDURE DIVISION."));
        return lines.toArray(new String[0]);
    }

    private static String program(String access, String[] extra, String... procedure) {
        String[] head = declaration(access, extra);
        String[] all = new String[head.length + procedure.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(procedure, 0, all, head.length, procedure.length);
        return source(all);
    }

    private static final String[] NO_EXTRA = new String[0];
    private static final String[] ALTERNATE = {
        "        ALTERNATE RECORD KEY IS K-DEPT WITH DUPLICATES",
    };

    /** 主鍵 A / B / C の 3 件を持つデータセットを置く。 */
    private static void seed(Path directory) {
        write(directory.resolve("KSDSDD"), ebcdic("AAAX1oneBBBY1twoCCCX1thr"));
        write(directory.resolve("KSDSDD.meta"),
                "recfm=F\nlrecl=8\ncodepage=IBM-1047\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("書き出す並びは主鍵の順である (FR-100)")
    void recordsAreStoredInKeyOrder(@TempDir Path directory) {
        assertEquals("00|", run(directory, program("RANDOM", NO_EXTRA,
                "    OPEN OUTPUT K-FILE.",
                "    MOVE 'BBBY1two' TO K-REC.",
                "    WRITE K-REC.",
                "    MOVE 'AAAX1one' TO K-REC.",
                "    WRITE K-REC.",
                "    CLOSE K-FILE.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
        assertArrayEquals(ebcdic("AAAX1oneBBBY1two"), bytesOf(directory.resolve("KSDSDD")));
    }

    @Test
    @DisplayName("主鍵で引ける (FR-101)")
    void recordsAreReadByPrimaryKey(@TempDir Path directory) {
        seed(directory);
        assertEquals("BBBY1two|", run(directory, program("RANDOM", NO_EXTRA,
                "    OPEN INPUT K-FILE.",
                "    MOVE 'BBB' TO K-ID.",
                "    READ K-FILE",
                "        INVALID KEY DISPLAY 'NONE'",
                "        NOT INVALID KEY DISPLAY K-REC",
                "    END-READ.",
                "    CLOSE K-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("ない鍵を読めば INVALID KEY へ抜ける (FR-103)")
    void readingAMissingKeyTakesTheInvalidKeyPath(@TempDir Path directory) {
        seed(directory);
        assertEquals("NONE|23|", run(directory, program("RANDOM", NO_EXTRA,
                "    OPEN INPUT K-FILE.",
                "    MOVE 'ZZZ' TO K-ID.",
                "    READ K-FILE",
                "        INVALID KEY DISPLAY 'NONE'",
                "    END-READ.",
                "    DISPLAY WS-STATUS.",
                "    CLOSE K-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("順次読みは主鍵の順に返す (FR-102)")
    void sequentialReadingFollowsTheKeyOrder(@TempDir Path directory) {
        seed(directory);
        assertEquals("AAAX1one|BBBY1two|CCCX1thr|", run(directory, program("SEQUENTIAL", NO_EXTRA,
                "    OPEN INPUT K-FILE.",
                "    PERFORM UNTIL WS-DONE = 'Y'",
                "        READ K-FILE",
                "            AT END MOVE 'Y' TO WS-DONE",
                "            NOT AT END DISPLAY K-REC",
                "        END-READ",
                "    END-PERFORM.",
                "    CLOSE K-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("同じ主鍵は 2 つ書けない (FR-103)")
    void aDuplicatePrimaryKeyTakesTheInvalidKeyPath(@TempDir Path directory) {
        seed(directory);
        assertEquals("DUP|22|", run(directory, program("RANDOM", NO_EXTRA,
                "    OPEN I-O K-FILE.",
                "    MOVE 'AAAZ9dup' TO K-REC.",
                "    WRITE K-REC",
                "        INVALID KEY DISPLAY 'DUP'",
                "    END-WRITE.",
                "    DISPLAY WS-STATUS.",
                "    CLOSE K-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("鍵で引いて書き換えと削除ができる (FR-101)")
    void recordsAreChangedByKey(@TempDir Path directory) {
        seed(directory);
        assertEquals("00|00|", run(directory, program("RANDOM", NO_EXTRA,
                "    OPEN I-O K-FILE.",
                "    MOVE 'BBBY9TWO' TO K-REC.",
                "    REWRITE K-REC.",
                "    DISPLAY WS-STATUS.",
                "    MOVE 'AAA' TO K-ID.",
                "    DELETE K-FILE.",
                "    DISPLAY WS-STATUS.",
                "    CLOSE K-FILE.",
                "    STOP RUN.")));
        assertArrayEquals(ebcdic("BBBY9TWOCCCX1thr"), bytesOf(directory.resolve("KSDSDD")));
    }

    @Test
    @DisplayName("ない鍵の書き換えと削除は INVALID KEY へ抜ける (FR-103)")
    void changingAMissingKeyTakesTheInvalidKeyPath(@TempDir Path directory) {
        seed(directory);
        assertEquals("NOREC|NODEL|", run(directory, program("RANDOM", NO_EXTRA,
                "    OPEN I-O K-FILE.",
                "    MOVE 'ZZZY9non' TO K-REC.",
                "    REWRITE K-REC",
                "        INVALID KEY DISPLAY 'NOREC'",
                "    END-REWRITE.",
                "    MOVE 'ZZZ' TO K-ID.",
                "    DELETE K-FILE",
                "        INVALID KEY DISPLAY 'NODEL'",
                "    END-DELETE.",
                "    CLOSE K-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("START は読まずに位置を決める (FR-101)")
    void startPositionsWithoutReading(@TempDir Path directory) {
        seed(directory);
        assertEquals("BBBY1two|CCCX1thr|", run(directory, program("DYNAMIC", NO_EXTRA,
                "    OPEN INPUT K-FILE.",
                "    MOVE 'BBB' TO K-ID.",
                "    START K-FILE KEY IS NOT LESS THAN K-ID",
                "        INVALID KEY DISPLAY 'NONE'",
                "    END-START.",
                "    PERFORM UNTIL WS-DONE = 'Y'",
                "        READ K-FILE NEXT",
                "            AT END MOVE 'Y' TO WS-DONE",
                "            NOT AT END DISPLAY K-REC",
                "        END-READ",
                "    END-PERFORM.",
                "    CLOSE K-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("副鍵で引ける (FR-100, FR-101)")
    void recordsAreReadByAnAlternateKey(@TempDir Path directory) {
        seed(directory);
        assertEquals("AAAX1one|", run(directory, program("RANDOM", ALTERNATE,
                "    OPEN INPUT K-FILE.",
                "    MOVE 'X1' TO K-DEPT.",
                "    READ K-FILE KEY IS K-DEPT",
                "        INVALID KEY DISPLAY 'NONE'",
                "        NOT INVALID KEY DISPLAY K-REC",
                "    END-READ.",
                "    CLOSE K-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("副鍵の順に順次読みできる (FR-101)")
    void sequentialReadingCanFollowAnAlternateIndex(@TempDir Path directory) {
        seed(directory);
        assertEquals("AAAX1one|CCCX1thr|BBBY1two|", run(directory, program("DYNAMIC", ALTERNATE,
                "    OPEN INPUT K-FILE.",
                "    MOVE 'X1' TO K-DEPT.",
                "    START K-FILE KEY IS NOT LESS THAN K-DEPT",
                "        INVALID KEY DISPLAY 'NONE'",
                "    END-START.",
                "    PERFORM UNTIL WS-DONE = 'Y'",
                "        READ K-FILE NEXT",
                "            AT END MOVE 'Y' TO WS-DONE",
                "            NOT AT END DISPLAY K-REC",
                "        END-READ",
                "    END-PERFORM.",
                "    CLOSE K-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("順アクセスの書き込みは主鍵の昇順でなければならない (FR-103)")
    void sequentialWritesMustAscend(@TempDir Path directory) {
        assertEquals("00|21|", run(directory, program("SEQUENTIAL", NO_EXTRA,
                "    OPEN OUTPUT K-FILE.",
                "    MOVE 'BBBY1two' TO K-REC.",
                "    WRITE K-REC.",
                "    DISPLAY WS-STATUS.",
                "    MOVE 'AAAX1one' TO K-REC.",
                "    WRITE K-REC.",
                "    DISPLAY WS-STATUS.",
                "    CLOSE K-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("書き換えで主鍵は変えられない (FR-103)")
    void rewritingCannotChangeThePrimaryKey(@TempDir Path directory) {
        seed(directory);
        assertEquals("21|", run(directory, program("SEQUENTIAL", NO_EXTRA,
                "    OPEN I-O K-FILE.",
                "    READ K-FILE AT END CONTINUE END-READ.",
                "    MOVE 'ZZZX1one' TO K-REC.",
                "    REWRITE K-REC.",
                "    DISPLAY WS-STATUS.",
                "    CLOSE K-FILE.",
                "    STOP RUN.")));
    }

    // ---- 組み合わせの検査 ----

    @Test
    @DisplayName("鍵はレコードの中になければならない (FR-100)")
    void aKeyMustLiveInsideTheRecord() {
        assertTrue(diagnostics(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. OUTSIDE.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT K-FILE ASSIGN TO KSDSDD",
                "        ORGANIZATION IS INDEXED",
                "        RECORD KEY IS WS-KEY.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  K-FILE.",
                "01  K-REC PIC X(8).",
                "WORKING-STORAGE SECTION.",
                "01  WS-KEY PIC X(3).",
                "PROCEDURE DIVISION.",
                "    STOP RUN.")).toString().contains("must be inside the record area"));
    }

    @Test
    @DisplayName("順編成に RECORD KEY は書けない (FR-100)")
    void aRecordKeyNeedsAnIndexedFile() {
        assertTrue(diagnostics(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MISPLACED.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT S-FILE ASSIGN TO SEQDD",
                "        RECORD KEY IS S-ID.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  S-FILE.",
                "01  S-REC.",
                "    05  S-ID PIC X(3).",
                "PROCEDURE DIVISION.",
                "    STOP RUN.")).toString().contains("requires ORGANIZATION IS INDEXED"));
    }

    @Test
    @DisplayName("READ ... KEY に鍵でない項目は書けない (FR-101)")
    void readKeyNamesOneOfTheKeys() {
        assertTrue(diagnostics(program("RANDOM", NO_EXTRA,
                "    OPEN INPUT K-FILE.",
                "    READ K-FILE KEY IS K-NAME",
                "        INVALID KEY CONTINUE",
                "    END-READ.",
                "    STOP RUN.")).toString()
                .contains("not a RECORD KEY or ALTERNATE RECORD KEY"));
    }

    @Test
    @DisplayName("READ ... KEY は索引編成だけである (FR-101)")
    void readKeyNeedsAnIndexedFile() {
        assertTrue(diagnostics(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. WRONGORG.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT R-FILE ASSIGN TO RELDD",
                "        ORGANIZATION IS RELATIVE",
                "        ACCESS MODE IS RANDOM",
                "        RELATIVE KEY IS WS-RRN.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  R-FILE.",
                "01  R-REC PIC X(3).",
                "WORKING-STORAGE SECTION.",
                "01  WS-RRN PIC 9(3) COMP.",
                "PROCEDURE DIVISION.",
                "    READ R-FILE KEY IS WS-RRN INVALID KEY CONTINUE END-READ.",
                "    STOP RUN.")).toString().contains("READ ... KEY requires ORGANIZATION"));
    }
}
