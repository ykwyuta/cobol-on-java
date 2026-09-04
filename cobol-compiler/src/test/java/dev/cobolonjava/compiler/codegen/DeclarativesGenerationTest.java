package dev.cobolonjava.compiler.codegen;

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
 * 宣言部分と節 (要件 FR-061, FR-105、設計 80 の第 4 段)。
 *
 * <p>{@code USE AFTER STANDARD ERROR PROCEDURE} は文ではない。その節が<b>いつ動くか</b>の
 * 宣言であり、入出力で異常が起きたときに呼ばれて、終われば元の場所へ戻る。
 */
@Tag("V1")
class DeclarativesGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(DeclarativesGenerationTest.class.getClassLoader());
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

    private static void seed(Path directory) {
        try {
            Files.write(directory.resolve("INDD"), CodePages.DEFAULT.encode("aaabbb"));
            Files.write(directory.resolve("INDD.meta"),
                    "recfm=F\nlrecl=3\ncodepage=IBM-1047\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("開けなければ宣言節が動き、元の場所へ戻る (FR-105)")
    void aFailedOpenRunsTheUseProcedure(@TempDir Path directory) {
        assertEquals("IO FAILED|AFTER OPEN|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. DECL.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IN-FILE ASSIGN TO NOSUCH.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IN-FILE.",
                "01  IN-REC PIC X(3).",
                "PROCEDURE DIVISION.",
                "DECLARATIVES.",
                "ERR-SECT SECTION.",
                "    USE AFTER STANDARD ERROR PROCEDURE ON IN-FILE.",
                "ERR-PARA.",
                "    DISPLAY 'IO FAILED'.",
                "END DECLARATIVES.",
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    OPEN INPUT IN-FILE.",
                "    DISPLAY 'AFTER OPEN'.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("宣言部分は通常の流れでは通らない (FR-105)")
    void declarativesAreNotReachedByFallingThrough(@TempDir Path directory) {
        seed(directory);
        assertEquals("MAIN RAN|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SKIPDECL.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IN-FILE ASSIGN TO INDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IN-FILE.",
                "01  IN-REC PIC X(3).",
                "PROCEDURE DIVISION.",
                "DECLARATIVES.",
                "ERR-SECT SECTION.",
                "    USE AFTER STANDARD ERROR PROCEDURE ON IN-FILE.",
                "ERR-PARA.",
                "    DISPLAY 'SHOULD NOT RUN'.",
                "END DECLARATIVES.",
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    DISPLAY 'MAIN RAN'.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("AT END を書いてあれば宣言節は動かない (FR-105)")
    void anHandledEndOfFileIsNotAFailure(@TempDir Path directory) {
        seed(directory);
        assertEquals("aaa|bbb|DONE|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HANDLED.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IN-FILE ASSIGN TO INDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IN-FILE.",
                "01  IN-REC PIC X(3).",
                "WORKING-STORAGE SECTION.",
                "01  WS-DONE PIC X VALUE 'N'.",
                "PROCEDURE DIVISION.",
                "DECLARATIVES.",
                "ERR-SECT SECTION.",
                "    USE AFTER STANDARD ERROR PROCEDURE ON IN-FILE.",
                "ERR-PARA.",
                "    DISPLAY 'SHOULD NOT RUN'.",
                "END DECLARATIVES.",
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    OPEN INPUT IN-FILE.",
                "    PERFORM UNTIL WS-DONE = 'Y'",
                "        READ IN-FILE",
                "            AT END MOVE 'Y' TO WS-DONE",
                "            NOT AT END DISPLAY IN-REC",
                "        END-READ",
                "    END-PERFORM.",
                "    CLOSE IN-FILE.",
                "    DISPLAY 'DONE'.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("AT END を書かずに終わりまで読めば宣言節が動く (FR-105)")
    void anUnhandledEndOfFileRunsTheUseProcedure(@TempDir Path directory) {
        seed(directory);
        assertEquals("aaa|bbb|AT THE END|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. UNHANDLED.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IN-FILE ASSIGN TO INDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IN-FILE.",
                "01  IN-REC PIC X(3).",
                "PROCEDURE DIVISION.",
                "DECLARATIVES.",
                "ERR-SECT SECTION.",
                "    USE AFTER STANDARD ERROR PROCEDURE ON IN-FILE.",
                "ERR-PARA.",
                "    DISPLAY 'AT THE END'.",
                "END DECLARATIVES.",
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    OPEN INPUT IN-FILE.",
                "    READ IN-FILE.",
                "    DISPLAY IN-REC.",
                "    READ IN-FILE.",
                "    DISPLAY IN-REC.",
                "    READ IN-FILE.",
                "    CLOSE IN-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("FILE STATUS を書いてあっても宣言節は動く (FR-103, FR-105)")
    void theStatusItemAndTheUseProcedureBothHappen(@TempDir Path directory) {
        assertEquals("SAW 35|35|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. BOTH.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IN-FILE ASSIGN TO NOSUCH",
                "        FILE STATUS IS WS-STATUS.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IN-FILE.",
                "01  IN-REC PIC X(3).",
                "WORKING-STORAGE SECTION.",
                "01  WS-STATUS PIC XX.",
                "PROCEDURE DIVISION.",
                "DECLARATIVES.",
                "ERR-SECT SECTION.",
                "    USE AFTER STANDARD ERROR PROCEDURE ON IN-FILE.",
                "ERR-PARA.",
                "    DISPLAY 'SAW ' WS-STATUS.",
                "END DECLARATIVES.",
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    OPEN INPUT IN-FILE.",
                "    DISPLAY WS-STATUS.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("開き方で受け持ちを指定できる (FR-105)")
    void aUseProcedureMayNameAnOpenMode(@TempDir Path directory) {
        assertEquals("INPUT FAILED|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. BYMODE.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IN-FILE ASSIGN TO NOSUCH.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IN-FILE.",
                "01  IN-REC PIC X(3).",
                "PROCEDURE DIVISION.",
                "DECLARATIVES.",
                "IN-SECT SECTION.",
                "    USE AFTER STANDARD ERROR PROCEDURE ON INPUT.",
                "IN-PARA.",
                "    DISPLAY 'INPUT FAILED'.",
                "OUT-SECT SECTION.",
                "    USE AFTER STANDARD ERROR PROCEDURE ON OUTPUT.",
                "OUT-PARA.",
                "    DISPLAY 'OUTPUT FAILED'.",
                "END DECLARATIVES.",
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    OPEN INPUT IN-FILE.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("開き方の宣言節は実行時の開き方で選ばれる (FR-105)")
    void theOpenModeIsCheckedAtRunTime(@TempDir Path directory) {
        seed(directory);
        assertEquals("INPUT TROUBLE|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. RUNMODE.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IN-FILE ASSIGN TO INDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IN-FILE.",
                "01  IN-REC PIC X(3).",
                "PROCEDURE DIVISION.",
                "DECLARATIVES.",
                "IN-SECT SECTION.",
                "    USE AFTER STANDARD ERROR PROCEDURE ON INPUT.",
                "IN-PARA.",
                "    DISPLAY 'INPUT TROUBLE'.",
                "OUT-SECT SECTION.",
                "    USE AFTER STANDARD ERROR PROCEDURE ON OUTPUT.",
                "OUT-PARA.",
                "    DISPLAY 'OUTPUT TROUBLE'.",
                "END DECLARATIVES.",
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    OPEN INPUT IN-FILE.",
                "    READ IN-FILE.",
                "    READ IN-FILE.",
                "    READ IN-FILE.",
                "    CLOSE IN-FILE.",
                "    STOP RUN.")));
    }

    // ---- 節 ----

    @Test
    @DisplayName("PERFORM 節名 は節の全体を動かす (FR-061)")
    void performingASectionRunsAllOfIt(@TempDir Path directory) {
        assertEquals("ONE|TWO|BACK|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SECPERF.",
                "PROCEDURE DIVISION.",
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    PERFORM WORK-SECT.",
                "    DISPLAY 'BACK'.",
                "    STOP RUN.",
                "WORK-SECT SECTION.",
                "WORK-ONE.",
                "    DISPLAY 'ONE'.",
                "WORK-TWO.",
                "    DISPLAY 'TWO'.")));
    }

    @Test
    @DisplayName("節は書かれた順に落ちて続く (FR-061)")
    void sectionsFallThroughInOrder(@TempDir Path directory) {
        assertEquals("FIRST|SECOND|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SECFLOW.",
                "PROCEDURE DIVISION.",
                "FIRST-SECT SECTION.",
                "    DISPLAY 'FIRST'.",
                "SECOND-SECT SECTION.",
                "    DISPLAY 'SECOND'.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("GO TO 節名 はその節の先頭へ飛ぶ (FR-062)")
    void goingToASectionEntersItsFirstParagraph(@TempDir Path directory) {
        assertEquals("JUMPED|", run(directory, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SECGOTO.",
                "PROCEDURE DIVISION.",
                "MAIN-SECT SECTION.",
                "MAIN-PARA.",
                "    GO TO TARGET-SECT.",
                "    DISPLAY 'NOT REACHED'.",
                "TARGET-SECT SECTION.",
                "    DISPLAY 'JUMPED'.",
                "    STOP RUN.")));
    }

    // ---- 組み合わせの検査 ----

    @Test
    @DisplayName("同じファイルを 2 つの宣言節が受け持てない (FR-105)")
    void twoUseProceduresCannotShareAFile() {
        assertTrue(diagnostics(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. TWICE.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IN-FILE ASSIGN TO INDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IN-FILE.",
                "01  IN-REC PIC X(3).",
                "PROCEDURE DIVISION.",
                "DECLARATIVES.",
                "A-SECT SECTION.",
                "    USE AFTER STANDARD ERROR PROCEDURE ON IN-FILE.",
                "A-PARA.",
                "    CONTINUE.",
                "B-SECT SECTION.",
                "    USE AFTER STANDARD ERROR PROCEDURE ON IN-FILE.",
                "B-PARA.",
                "    CONTINUE.",
                "END DECLARATIVES.",
                "MAIN-SECT SECTION.",
                "    STOP RUN.")).toString().contains("name the same file"));
    }

    @Test
    @DisplayName("同じ開き方を 2 つの宣言節が受け持てない (FR-105)")
    void twoUseProceduresCannotShareAnOpenMode() {
        assertTrue(diagnostics(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. TWICEMODE.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT IN-FILE ASSIGN TO INDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  IN-FILE.",
                "01  IN-REC PIC X(3).",
                "PROCEDURE DIVISION.",
                "DECLARATIVES.",
                "A-SECT SECTION.",
                "    USE AFTER STANDARD ERROR PROCEDURE ON INPUT.",
                "A-PARA.",
                "    CONTINUE.",
                "B-SECT SECTION.",
                "    USE AFTER STANDARD ERROR PROCEDURE ON INPUT.",
                "B-PARA.",
                "    CONTINUE.",
                "END DECLARATIVES.",
                "MAIN-SECT SECTION.",
                "    STOP RUN.")).toString().contains("name the same open mode"));
    }

    @Test
    @DisplayName("知らないファイルを受け持つ宣言節は誤りである (FR-105)")
    void aUseProcedureNamesAKnownFile() {
        assertTrue(diagnostics(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. NOSUCHFILE.",
                "PROCEDURE DIVISION.",
                "DECLARATIVES.",
                "A-SECT SECTION.",
                "    USE AFTER STANDARD ERROR PROCEDURE ON GHOST-FILE.",
                "A-PARA.",
                "    CONTINUE.",
                "END DECLARATIVES.",
                "MAIN-SECT SECTION.",
                "    STOP RUN.")).toString()
                .contains("file is not declared in the FILE-CONTROL paragraph"));
    }
}
