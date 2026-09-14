package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 16 進定数 {@code X'..'}。バイトは code page を通さない。 */
@Tag("V1")
class HexadecimalLiteralTest {

    private static final class Loader extends ClassLoader {

        Loader() {
            super(HexadecimalLiteralTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String source(List<String> storage, String... procedure) {
        StringBuilder out = new StringBuilder();
        for (String line : List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. HEXLIT.",
                "DATA DIVISION.", "WORKING-STORAGE SECTION.")) {
            out.append("       ").append(line).append('\n');
        }
        for (String line : storage) {
            out.append("       ").append(line).append('\n');
        }
        out.append("       PROCEDURE DIVISION.\n");
        for (String line : procedure) {
            out.append("           ").append(line).append('\n');
        }
        return out.toString();
    }

    @Test
    @DisplayName("VALUE・MOVE・比較・ALLで16進定数のバイトがそのまま入る")
    void placesBytesWithoutCodePage() throws ReflectiveOperationException {
        CobolCompiler.Result result = CobolCompiler.standard().compile("HEXLIT.cbl", source(
                List.of(
                        "01  WS-A PIC X(2) VALUE X'7D00'.",
                        "01  WS-B PIC X(3).",
                        "01  WS-C PIC X(4) VALUE ALL x\"C1\".",
                        "01  WS-F PIC X VALUE 'N'."),
                "MOVE X'F1F2' TO WS-B",
                "IF WS-A = X'7D00'",
                "    MOVE X'e8' TO WS-F",
                "END-IF",
                "GOBACK."));
        assertTrue(result.succeeded(), result.diagnostics().toString());

        CobolProgram program = (CobolProgram) new Loader()
                .define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        byte[] storage = Arrays.copyOf(program.runFresh().array(), 10);

        byte space = CodePages.DEFAULT.space();
        assertArrayEquals(new byte[] {
                0x7D, 0x00,
                (byte) 0xF1, (byte) 0xF2, space,
                (byte) 0xC1, (byte) 0xC1, (byte) 0xC1, (byte) 0xC1,
                (byte) 0xE8}, storage);
    }

    @Test
    @DisplayName("16進の桁が奇数個・16進でない文字・空は断る")
    void rejectsMalformedHexadecimalLiterals() {
        for (String literal : List.of("X'7'", "X'7G'", "X''")) {
            CobolCompiler.Result result = CobolCompiler.standard().compile("HEXLIT.cbl", source(
                    List.of("01  WS-B PIC X(3)."),
                    "MOVE " + literal + " TO WS-B",
                    "GOBACK."));

            assertFalse(result.succeeded(), literal);
            assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                            diagnostic.message().contains("hexadecimal")),
                    literal + " " + result.diagnostics());
        }
    }
}
