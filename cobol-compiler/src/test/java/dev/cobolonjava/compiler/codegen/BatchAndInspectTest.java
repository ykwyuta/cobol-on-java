package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ZCVS の残りを流して見つかった、狭く決めていた書き方と、通していた誤り。
 *
 * <p>期待値の根拠は Enterprise COBOL の言語解説書とプログラミングの手引きである。
 * 実機とは突き合わせていない。
 */
@Tag("V1")
class BatchAndInspectTest {

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(BatchAndInspectTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static CobolCompiler.Result compile(List<String> lines) {
        StringBuilder source = new StringBuilder();
        for (String line : lines) {
            source.append("       ").append(line).append('\n');
        }
        return CobolCompiler.standard().compile("PGM.cbl", source.toString());
    }

    private static List<String> run(List<String> lines) {
        CobolCompiler.Result result = compile(lines);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        GeneratedLoader loader = new GeneratedLoader();
        List<CobolProgram> loaded = new ArrayList<>();
        for (CobolCompiler.Compiled program : result.programs()) {
            try {
                Class<?> type = loader.define(program.className(), program.classFile());
                loaded.add((CobolProgram) type.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot load the generated program", e);
            }
        }
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        loaded.get(0).runFresh(ProgramContext.capturing(sink));
        return sink.toString(StandardCharsets.UTF_8).lines().toList();
    }

    @Test
    @DisplayName("INSPECT は関数の値も数えられる。元の項目は変わらない")
    void talliesAFunctionValue() {
        // 'ABC  ' を逆さにすると頭に空白が 2 つ来る。後ろの空白の数を数える書き方である
        assertEquals(List.of("02/ABC  "), run(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MAIN.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-TEXT PIC X(5) VALUE 'ABC'.",
                "01 WS-N PIC 99 VALUE 0.",
                "PROCEDURE DIVISION.",
                "    INSPECT FUNCTION REVERSE(WS-TEXT)",
                "        TALLYING WS-N FOR LEADING SPACES.",
                "    DISPLAY WS-N '/' WS-TEXT.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("関数の値は書き換えられないので、REPLACING は断る")
    void refusesToReplaceInAFunctionValue() {
        CobolCompiler.Result result = compile(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MAIN.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-TEXT PIC X(5) VALUE 'ABC'.",
                "PROCEDURE DIVISION.",
                "    INSPECT FUNCTION REVERSE(WS-TEXT)",
                "        REPLACING ALL 'A' BY 'B'.",
                "    STOP RUN."));
        assertFalse(result.succeeded());
    }

    @Test
    @DisplayName("続けて書いたプログラムの前の CBL 文は、そのプログラムからの指定である")
    void appliesAProcessStatementToTheFollowingProgram() {
        // 1 本目は TRUNC(STD) で 4 桁に切れ、2 本目は TRUNC(BIN) で halfword まで入る
        assertEquals(List.of("2345", "+12345"), run(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MAIN.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-H PIC 9(4) COMP.",
                "01 WS-BIG PIC 9(5) VALUE 12345.",
                "PROCEDURE DIVISION.",
                "    MOVE WS-BIG TO WS-H.",
                "    DISPLAY WS-H.",
                "    CALL 'SECOND'.",
                "    STOP RUN.",
                "END PROGRAM MAIN.",
                "CBL TRUNC(BIN)",
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SECOND.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-H PIC S9(4) COMP.",
                "01 WS-BIG PIC 9(5) VALUE 12345.",
                "01 WS-OUT PIC +9(5).",
                "PROCEDURE DIVISION.",
                "    MOVE WS-BIG TO WS-H.",
                "    MOVE WS-H TO WS-OUT.",
                "    DISPLAY WS-OUT.",
                "    GOBACK.",
                "END PROGRAM SECOND.")));
    }

    private static List<String> withFile(String ioControl, String codeSet, String record) {
        List<String> lines = new ArrayList<>(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MAIN.",
                "ENVIRONMENT DIVISION.",
                "CONFIGURATION SECTION.",
                "SPECIAL-NAMES.",
                "    ALPHABET ASCII-CS IS STANDARD-1.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT F ASSIGN TO OUTFILE."));
        if (ioControl != null) {
            lines.add("I-O-CONTROL.");
            lines.add("    " + ioControl);
        }
        lines.addAll(List.of(
                "DATA DIVISION.",
                "FILE SECTION.",
                codeSet == null ? "FD  F." : "FD  F " + codeSet,
                "01  F-REC " + record,
                "PROCEDURE DIVISION.",
                "    STOP RUN."));
        return lines;
    }

    @Test
    @DisplayName("CODE-SET の下の符号つきの数字項目は SIGN IS SEPARATE を要る")
    void requiresASeparateSignUnderCodeSet() {
        CobolCompiler.Result refused =
                compile(withFile(null, "CODE-SET IS ASCII-CS.", "PIC S9(4)."));
        assertFalse(refused.succeeded());
        assertTrue(refused.diagnostics().toString().contains("CODE-SET requires"),
                () -> refused.diagnostics().toString());

        CobolCompiler.Result accepted = compile(withFile(null, "CODE-SET IS ASCII-CS.",
                "PIC S9(4) SIGN TRAILING SEPARATE."));
        assertTrue(accepted.succeeded(), () -> accepted.diagnostics().toString());
    }

    @Test
    @DisplayName("RERUN の EVERY n RECORDS のあとには OF ファイル名が要る")
    void requiresAFileNameInRerun() {
        CobolCompiler.Result refused =
                compile(withFile("RERUN ON F EVERY 100 RECORDS.", null, "PIC X(10)."));
        assertFalse(refused.succeeded());
        assertTrue(refused.diagnostics().toString().contains("name expected"),
                () -> refused.diagnostics().toString());

        CobolCompiler.Result accepted =
                compile(withFile("RERUN ON F EVERY 100 RECORDS OF F.", null, "PIC X(10)."));
        assertTrue(accepted.succeeded(), () -> accepted.diagnostics().toString());
    }

    @Test
    @DisplayName("外部浮動小数点は、左端の桁が 0 にならないように寄せて字で持つ")
    void normalizesExternalFloatingItems() {
        assertEquals(List.of("+00.00E+00/Y", "+12.50E-01", "-12.50E-01", "+12.34E+02",
                "00123400000"), run(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MAIN.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 EXT PIC +99.99E+99.",
                "01 BACK PIC 9(5)V9(6).",
                "01 FLAG PIC X VALUE 'N'.",
                "PROCEDURE DIVISION.",
                "    MOVE 0 TO EXT.",
                "    IF EXT IS NUMERIC MOVE 'Y' TO FLAG.",
                "    DISPLAY EXT '/' FLAG.",
                "    MOVE 1.25 TO EXT.",
                "    DISPLAY EXT.",
                "    MOVE -1.25 TO EXT.",
                "    DISPLAY EXT.",
                "    MOVE 1234 TO EXT.",
                "    DISPLAY EXT.",
                "    MOVE 123.4 TO EXT.",
                "    MOVE EXT TO BACK.",
                "    DISPLAY BACK.",
                "    STOP RUN.")));
    }

    @Test
    @DisplayName("外部浮動小数点に VALUE と 2 進の USAGE は書けない")
    void refusesClausesAnExternalFloatingItemCannotHave() {
        for (String clause : List.of("VALUE 1", "JUSTIFIED", "COMP")) {
            CobolCompiler.Result result = compile(List.of(
                    "IDENTIFICATION DIVISION.",
                    "PROGRAM-ID. MAIN.",
                    "DATA DIVISION.",
                    "WORKING-STORAGE SECTION.",
                    "01 EXT PIC +9.9E+99 " + clause + ".",
                    "PROCEDURE DIVISION.",
                    "    STOP RUN."));
            assertFalse(result.succeeded(), clause);
            assertTrue(result.diagnostics().toString().contains("external floating item"),
                    () -> clause + ": " + result.diagnostics());
        }
    }
}
