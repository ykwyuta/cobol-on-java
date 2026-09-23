package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.file.DataSetCatalog;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code WRITE ... ADVANCING 呼び名} (要件 FR-102、暫定判断 P-076)。
 *
 * <p>以前は機能名の表に紙送りの通路が無く、{@code C01 IS TOP-OF-FORM} を「知らない機能名」
 * と断っていた (z/OS probe の CBLPRNC)。そのうえ {@code ADVANCING} に書いたどの呼び名も
 * 頁の先頭へ送っていた。
 */
class AdvancingMnemonicTest {

    @TempDir
    Path directory;

    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader() {
            super(AdvancingMnemonicTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String program(String specialNames, String... procedure) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. ADV.",
                "ENVIRONMENT DIVISION.",
                "CONFIGURATION SECTION.",
                "SPECIAL-NAMES.",
                "    " + specialNames + ".",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT PRINT-FILE ASSIGN TO PRTDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  PRINT-FILE.",
                "01  PRINT-REC PIC X(4).",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    OPEN OUTPUT PRINT-FILE",
                "    MOVE 'LINE' TO PRINT-REC",
                "    WRITE PRINT-REC AFTER ADVANCING 1 LINE")) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : procedure) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : List.of("    CLOSE PRINT-FILE", "    STOP RUN.")) {
            FixedFormatSource.append(sb, line);
        }
        return sb.toString();
    }

    /** 翻訳して動かし、印字ファイルのバイト列を返す。 */
    private byte[] printed(String source, String name) throws Exception {
        CobolCompiler.Result result = CobolCompiler.standard().compile("ADV.cbl", source);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        Path run = Files.createDirectories(directory.resolve(name));
        CobolProgram program = (CobolProgram) new GeneratedLoader()
                .define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        program.runFresh(ProgramContext.capturing(new ByteArrayOutputStream())
                .withCatalog(new DataSetCatalog(run)));
        try {
            return Files.readAllBytes(run.resolve("PRTDD"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("C01 は通路 1、頁の先頭へ送る (ADVANCING PAGE と同じ)")
    void channelOneIsTheTopOfTheForm() throws Exception {
        assertArrayEquals(
                printed(program("C01 IS TOP-OF-FORM",
                        "    WRITE PRINT-REC AFTER ADVANCING PAGE"), "page"),
                printed(program("C01 IS TOP-OF-FORM",
                        "    WRITE PRINT-REC AFTER ADVANCING TOP-OF-FORM"), "c01"));
    }

    @Test
    @DisplayName("CSP は行を送らない (ADVANCING 0 LINES と同じ)")
    void cspSuppressesSpacing() throws Exception {
        assertArrayEquals(
                printed(program("CSP IS NO-SPACE",
                        "    WRITE PRINT-REC AFTER ADVANCING 0 LINES"), "zero"),
                printed(program("CSP IS NO-SPACE",
                        "    WRITE PRINT-REC AFTER ADVANCING NO-SPACE"), "csp"));
    }

    @Test
    @DisplayName("通路 2〜12 は行が装置で決まるので、黙って頁の先頭へ送らずに断る")
    void otherChannelsAreRefused() {
        CobolCompiler.Result result = CobolCompiler.standard().compile("ADV.cbl",
                program("C02 IS CHANNEL-2", "    WRITE PRINT-REC AFTER ADVANCING CHANNEL-2"));
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("P-076"),
                () -> result.diagnostics().toString());
    }

    @Test
    @DisplayName("紙送りでない呼び名は ADVANCING に、紙送りの呼び名は DISPLAY に書けない")
    void mnemonicsAreCheckedAgainstTheirUse() {
        CobolCompiler.Result advancing = CobolCompiler.standard().compile("ADV.cbl",
                program("SYSOUT IS PRINTER", "    WRITE PRINT-REC AFTER ADVANCING PRINTER"));
        assertFalse(advancing.succeeded());
        CobolCompiler.Result display = CobolCompiler.standard().compile("ADV.cbl",
                program("C01 IS TOP-OF-FORM", "    DISPLAY 'X' UPON TOP-OF-FORM"));
        assertFalse(display.succeeded());
    }
}
