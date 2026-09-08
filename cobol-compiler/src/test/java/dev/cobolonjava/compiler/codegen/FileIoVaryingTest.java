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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 可変長レコードと {@code REWRITE} を翻訳して実行する
 * (要件 FR-102, FR-103, FR-106、設計 80 の第 2 段)。
 *
 * <p>可変長では<b>長さそのものがデータである</b>。書くときは {@code DEPENDING ON} の項目が
 * レコード長を決め、読んだときは実際の長さがその項目へ入る。
 */
@Tag("V1")
class FileIoVaryingTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(FileIoVaryingTest.class.getClassLoader());
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

    /** 4 バイトの RDW を付けた 1 レコード。 */
    private static byte[] rdw(String text) {
        byte[] body = ebcdic(text);
        byte[] out = new byte[body.length + 4];
        int length = out.length;
        out[0] = (byte) (length >> 8);
        out[1] = (byte) length;
        System.arraycopy(body, 0, out, 4, body.length);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    private static final String VARYING_WRITER = source(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. VWRITER.",
            "ENVIRONMENT DIVISION.",
            "INPUT-OUTPUT SECTION.",
            "FILE-CONTROL.",
            "    SELECT OUT-FILE ASSIGN TO VOUT",
            "        FILE STATUS IS WS-STATUS.",
            "DATA DIVISION.",
            "FILE SECTION.",
            "FD  OUT-FILE",
            "    RECORD IS VARYING IN SIZE FROM 1 TO 20 DEPENDING ON WS-LEN.",
            "01  OUT-REC PIC X(20).",
            "WORKING-STORAGE SECTION.",
            "01  WS-STATUS PIC XX.",
            "01  WS-LEN    PIC 9(3) COMP.",
            "PROCEDURE DIVISION.",
            "    OPEN OUTPUT OUT-FILE.",
            "    MOVE 'ABC' TO OUT-REC.",
            "    MOVE 3 TO WS-LEN.",
            "    WRITE OUT-REC.",
            "    MOVE 'HELLO WORLD' TO OUT-REC.",
            "    MOVE 11 TO WS-LEN.",
            "    WRITE OUT-REC.",
            "    CLOSE OUT-FILE.",
            "    DISPLAY WS-STATUS.",
            "    STOP RUN.");

    @Test
    @DisplayName("可変長は DEPENDING ON の値だけ書き、RDW が付く (FR-106, FR-110)")
    void writingUsesTheDependingLength(@TempDir Path directory) {
        assertEquals("00|", run(directory, VARYING_WRITER));
        assertArrayEquals(concat(rdw("ABC"), rdw("HELLO WORLD")),
                bytesOf(directory.resolve("VOUT")));
    }

    @Test
    @DisplayName("書いたファイルの様式はサイドカーに残る (FR-110)")
    void theSidecarSaysItIsVariable(@TempDir Path directory) {
        run(directory, VARYING_WRITER);
        try {
            assertEquals("recfm=V", Files.readAllLines(directory.resolve("VOUT.meta")).get(0));
            assertEquals("lrecl=20", Files.readAllLines(directory.resolve("VOUT.meta")).get(1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("READ は読めた長さを DEPENDING ON の項目へ入れる (FR-106)")
    void readingSetsTheDependingItem(@TempDir Path directory) {
        write(directory.resolve("VIN"), concat(rdw("ABC"), rdw("HELLO WORLD")));
        write(directory.resolve("VIN.meta"),
                "recfm=V\nlrecl=20\ncodepage=IBM-1047\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("003|011|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. VREADER.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IN-FILE ASSIGN TO VIN",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IN-FILE",
                "    RECORD IS VARYING IN SIZE FROM 1 TO 20 DEPENDING ON WS-LEN.",
                "01  IN-REC PIC X(20).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "01  WS-LEN    PIC 9(3) COMP.",
                "01  WS-DONE   PIC X VALUE 'N'.",
                "PROCEDURE DIVISION.",
                "    OPEN INPUT IN-FILE.",
                "    PERFORM UNTIL WS-DONE = 'Y'",
                "        READ IN-FILE",
                "            AT END MOVE 'Y' TO WS-DONE",
                "            NOT AT END DISPLAY WS-LEN",
                "        END-READ",
                "    END-PERFORM.",
                "    CLOSE IN-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("RECORDING MODE V でも可変長になる (FR-100)")
    void recordingModeVMeansVariable(@TempDir Path directory) {
        assertEquals("00|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. VMODE.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT OUT-FILE ASSIGN TO VMOUT",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  OUT-FILE",
                "    RECORDING MODE IS V.",
                "01  OUT-REC PIC X(4).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "PROCEDURE DIVISION.",
                "    OPEN OUTPUT OUT-FILE.",
                "    MOVE 'ABCD' TO OUT-REC.",
                "    WRITE OUT-REC.",
                "    CLOSE OUT-FILE.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
        assertArrayEquals(rdw("ABCD"), bytesOf(directory.resolve("VMOUT")));
    }

    @Test
    @DisplayName("REWRITE は読んだレコードをその場で書き換える (FR-102)")
    void rewriteReplacesTheRecordJustRead(@TempDir Path directory) {
        write(directory.resolve("IODD"), ebcdic("AAABBBCCC"));
        write(directory.resolve("IODD.meta"),
                "recfm=F\nlrecl=3\ncodepage=IBM-1047\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("00|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. UPDATER.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IO-FILE ASSIGN TO IODD",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IO-FILE.",
                "01  IO-REC PIC X(3).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "01  WS-DONE   PIC X VALUE 'N'.",
                "PROCEDURE DIVISION.",
                "    OPEN I-O IO-FILE.",
                "    PERFORM UNTIL WS-DONE = 'Y'",
                "        READ IO-FILE",
                "            AT END MOVE 'Y' TO WS-DONE",
                "            NOT AT END",
                "                IF IO-REC = 'BBB'",
                "                    MOVE 'XYZ' TO IO-REC",
                "                    REWRITE IO-REC",
                "                END-IF",
                "        END-READ",
                "    END-PERFORM.",
                "    CLOSE IO-FILE.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
        assertArrayEquals(ebcdic("AAAXYZCCC"), bytesOf(directory.resolve("IODD")));
    }

    @Test
    @DisplayName("読まずに REWRITE すれば 43 が返る (FR-103)")
    void rewritingWithoutReadingReportsFortyThree(@TempDir Path directory) {
        write(directory.resolve("IODD"), ebcdic("AAA"));
        write(directory.resolve("IODD.meta"),
                "recfm=F\nlrecl=3\ncodepage=IBM-1047\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("43|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. TOOSOON.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IO-FILE ASSIGN TO IODD",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IO-FILE.",
                "01  IO-REC PIC X(3).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "PROCEDURE DIVISION.",
                "    OPEN I-O IO-FILE.",
                "    MOVE 'XYZ' TO IO-REC.",
                "    REWRITE IO-REC.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("REWRITE を INPUT で開いたファイルに書けば 49 になる (FR-103)")
    void rewritingAnInputFileReportsFortyNine(@TempDir Path directory) {
        write(directory.resolve("IODD"), ebcdic("AAA"));
        write(directory.resolve("IODD.meta"),
                "recfm=F\nlrecl=3\ncodepage=IBM-1047\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("49|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. READONLY.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IO-FILE ASSIGN TO IODD",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IO-FILE.",
                "01  IO-REC PIC X(3).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "PROCEDURE DIVISION.",
                "    OPEN INPUT IO-FILE.",
                "    READ IO-FILE AT END CONTINUE END-READ.",
                "    REWRITE IO-REC.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
    }

    private static final String OPTIONAL_READER = source(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. MAYBE.",
            "ENVIRONMENT DIVISION.",
            "INPUT-OUTPUT SECTION.",
            "FILE-CONTROL.",
            "    SELECT OPTIONAL IN-FILE ASSIGN TO MAYBEDD",
            "        FILE STATUS IS WS-STATUS.",
            "DATA DIVISION.",
            "FILE SECTION.",
            "FD  IN-FILE.",
            "01  IN-REC PIC X(3).",
            "WORKING-STORAGE SECTION.",
            "01  WS-STATUS PIC XX.",
            "01  WS-COUNT  PIC 9(3) VALUE 0.",
            "01  WS-DONE   PIC X VALUE 'N'.",
            "PROCEDURE DIVISION.",
            "    OPEN INPUT IN-FILE.",
            "    DISPLAY WS-STATUS.",
            "    PERFORM UNTIL WS-DONE = 'Y'",
            "        READ IN-FILE",
            "            AT END MOVE 'Y' TO WS-DONE",
            "            NOT AT END ADD 1 TO WS-COUNT",
            "        END-READ",
            "    END-PERFORM.",
            "    CLOSE IN-FILE.",
            "    DISPLAY WS-COUNT.",
            "    STOP RUN.");

    @Test
    @DisplayName("OPTIONAL のファイルがなければ 05 で空として開く (FR-103)")
    void anOptionalFileMayBeMissing(@TempDir Path directory) {
        assertEquals("05|000|", run(directory, OPTIONAL_READER));
    }

    @Test
    @DisplayName("OPTIONAL のファイルがあれば普通に読む (FR-103)")
    void anOptionalFileIsReadWhenItExists(@TempDir Path directory) {
        write(directory.resolve("MAYBEDD"), ebcdic("AAABBB"));
        write(directory.resolve("MAYBEDD.meta"),
                "recfm=F\nlrecl=3\ncodepage=IBM-1047\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("00|002|", run(directory, OPTIONAL_READER));
    }

    @Test
    @DisplayName("OPTIONAL でなければ I-O で開いても 35 になる (FR-103)")
    void inputOutputStillNeedsTheFileToExist(@TempDir Path directory) {
        assertEquals("35|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. NOFILE.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IO-FILE ASSIGN TO GONE",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IO-FILE.",
                "01  IO-REC PIC X(3).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "PROCEDURE DIVISION.",
                "    OPEN I-O IO-FILE.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("EXTEND は末尾から書き足す (FR-102)")
    void extendAppendsToTheEnd(@TempDir Path directory) {
        write(directory.resolve("APPDD"), ebcdic("AAA"));
        write(directory.resolve("APPDD.meta"),
                "recfm=F\nlrecl=3\ncodepage=IBM-1047\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("00|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. APPENDER.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT OUT-FILE ASSIGN TO APPDD",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  OUT-FILE.",
                "01  OUT-REC PIC X(3).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "PROCEDURE DIVISION.",
                "    OPEN EXTEND OUT-FILE.",
                "    MOVE 'BBB' TO OUT-REC.",
                "    WRITE OUT-REC.",
                "    CLOSE OUT-FILE.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
        assertArrayEquals(ebcdic("AAABBB"), bytesOf(directory.resolve("APPDD")));
    }

    @Test
    @DisplayName("DEPENDING ON に数値でない項目は書けない (FR-106)")
    void theDependingItemMustBeNumeric() {
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. BADLEN.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT OUT-FILE ASSIGN TO OUTDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  OUT-FILE",
                "    RECORD IS VARYING IN SIZE FROM 1 TO 20 DEPENDING ON WS-TEXT.",
                "01  OUT-REC PIC X(20).",
                "WORKING-STORAGE SECTION.",
                "01  WS-TEXT PIC X(5).",
                "PROCEDURE DIVISION.",
                "    STOP RUN."));
        assertTrue(result.diagnostics().toString().contains("requires a numeric item"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("どの FD にも属さないレコードは REWRITE できない (FR-102)")
    void rewriteNeedsARecordOfSomeFd() {
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. NOTAREC.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01  WS-REC PIC X(3).",
                "PROCEDURE DIVISION.",
                "    REWRITE WS-REC.",
                "    STOP RUN."));
        assertTrue(result.diagnostics().toString().contains("REWRITE names an item"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("DEPENDING ON が上限を超えていれば収めて 04 になる (FR-106)")
    void aLengthAboveTheMaximumIsClamped(@TempDir Path directory) {
        assertEquals("04|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. TOOLONG.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT OUT-FILE ASSIGN TO CLAMPDD",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  OUT-FILE",
                "    RECORD IS VARYING IN SIZE FROM 1 TO 4 DEPENDING ON WS-LEN.",
                "01  OUT-REC PIC X(4).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "01  WS-LEN    PIC 9(3) COMP.",
                "PROCEDURE DIVISION.",
                "    OPEN OUTPUT OUT-FILE.",
                "    MOVE 'ABCD' TO OUT-REC.",
                "    MOVE 99 TO WS-LEN.",
                "    WRITE OUT-REC.",
                "    DISPLAY WS-STATUS.",
                "    CLOSE OUT-FILE.",
                "    STOP RUN.")));
        // 領域の外へはみ出さず、4 バイトだけが書かれる
        assertArrayEquals(rdw("ABCD"), bytesOf(directory.resolve("CLAMPDD")));
    }

    @Test
    @DisplayName("上限がレコード領域より長い指定は、告げて通す (FR-106, FR-183)")
    void theMaximumBeyondTheRecordAreaIsWarnedAbout() {
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. TOOBIG.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT OUT-FILE ASSIGN TO OUTDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  OUT-FILE",
                "    RECORD IS VARYING IN SIZE FROM 1 TO 100 DEPENDING ON WS-LEN.",
                "01  OUT-REC PIC X(20).",
                "WORKING-STORAGE SECTION.",
                "01  WS-LEN PIC 9(3) COMP.",
                "PROCEDURE DIVISION.",
                "    STOP RUN."));
        // 規格に沿わないが意味は決まる。止めてしまうと、その先の本当の誤りが見えなくなる
        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertEquals(1, result.diagnostics().size(), result.diagnostics().toString());
        assertTrue(result.diagnostics().get(0).isWarning(), result.diagnostics().toString());
        assertTrue(result.diagnostics().toString().contains("exceeds the record area"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("短い可変長レコードを読んでも領域の残りは前のままである (FR-106)")
    void aShortRecordLeavesTheRestOfTheArea(@TempDir Path directory) {
        write(directory.resolve("SHORTDD"), rdw("AB"));
        write(directory.resolve("SHORTDD.meta"),
                "recfm=V\nlrecl=8\ncodepage=IBM-1047\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("ABZZZZZZ|002|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. LEFTOVER.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IN-FILE ASSIGN TO SHORTDD",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IN-FILE",
                "    RECORD IS VARYING IN SIZE FROM 1 TO 8 DEPENDING ON WS-LEN.",
                "01  IN-REC PIC X(8).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "01  WS-LEN    PIC 9(3) COMP.",
                "PROCEDURE DIVISION.",
                "    MOVE 'ZZZZZZZZ' TO IN-REC.",
                "    OPEN INPUT IN-FILE.",
                "    READ IN-FILE",
                "        AT END CONTINUE",
                "        NOT AT END DISPLAY IN-REC",
                "    END-READ.",
                "    DISPLAY WS-LEN.",
                "    CLOSE IN-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("DEPENDING ON を書かなければ、レコード記述がレコード長を決める (FR-106)")
    void withoutADependingPhraseTheRecordDescriptionDecidesTheLength(@TempDir Path directory) {
        // RECORD IS VARYING だけを書いたときのレコード長は、レコード記述に書かれた
        // OCCURS ... DEPENDING ON の<b>いまの値</b>で決まる。RL211A がこの形である
        assertEquals("00|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. ODOWRIT.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT OUT-FILE ASSIGN TO ODOUT",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  OUT-FILE",
                "    RECORD IS VARYING.",
                "01  OUT-REC.",
                "    02  OUT-HEAD PIC X(2).",
                "    02  OUT-LEN  PIC 9.",
                "    02  OUT-TAIL PIC X OCCURS 1 TO 5 DEPENDING ON OUT-LEN.",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "PROCEDURE DIVISION.",
                "    OPEN OUTPUT OUT-FILE.",
                "    MOVE 'AB' TO OUT-HEAD.",
                "    MOVE 1 TO OUT-LEN.",
                "    MOVE 'P' TO OUT-TAIL (1).",
                "    WRITE OUT-REC.",
                "    MOVE 5 TO OUT-LEN.",
                "    MOVE 'V' TO OUT-TAIL (5).",
                "    WRITE OUT-REC.",
                "    CLOSE OUT-FILE.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
        // 1 個ぶんなら 2 + 1 + 1 = 4 バイト、5 個ぶんなら 2 + 1 + 5 = 8 バイト。
        // 最大の 8 バイトを 2 本書いていたら、この突き合わせで落ちる
        assertArrayEquals(concat(rdw("AB1P"), rdw("AB5P   V")),
                bytesOf(directory.resolve("ODOUT")));
    }
}
