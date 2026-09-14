package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.program.CobolProgram;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** USAGE POINTER と LENGTH OF の初期 subset。 */
@Tag("V1")
class PointerAndLengthOfTest {

    private static final class Loader extends ClassLoader {

        Loader() {
            super(PointerAndLengthOfTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String source(List<String> storage, String... procedure) {
        StringBuilder out = new StringBuilder();
        for (String line : List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. PTRLEN.",
                "DATA DIVISION.", "WORKING-STORAGE SECTION.")) {
            out.append("       ").append(line).append('\n');
        }
        storage.forEach(line -> out.append("       ").append(line).append('\n'));
        out.append("       PROCEDURE DIVISION.\n");
        for (String line : procedure) {
            out.append("           ").append(line).append('\n');
        }
        return out.toString();
    }

    private static final List<String> STORAGE = List.of(
            "01  P USAGE POINTER.",
            "01  PB REDEFINES P PIC X(4).",
            "01  G.",
            "    03  Q POINTER.",
            "    03  R PIC X(3) VALUE 'ABC'.",
            "01  N PIC S9(4) COMP.");

    @Test
    @DisplayName("POINTER は 4 byte で SET TO NULL が X'00' を置き、LENGTH OF は翻訳時の長さになる")
    void setsNullAndComputesLength() throws ReflectiveOperationException {
        CobolCompiler.Result result = CobolCompiler.standard().compile("PTRLEN.cbl", source(STORAGE,
                "MOVE ALL 'Z' TO PB",
                "MOVE ALL 'Y' TO G",
                "SET Q TO NULL",
                "SET P TO Q",
                "MOVE LENGTH OF G TO N",
                "COMPUTE N = N + LENGTH OF R",
                "GOBACK."));
        assertTrue(result.succeeded(), result.diagnostics().toString());

        CobolProgram program = (CobolProgram) new Loader().define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        byte[] storage = Arrays.copyOf(program.runFresh().array(), 13);

        // P (4) / G: Q (4) R (3) / N (2)
        assertArrayEquals(new byte[4], Arrays.copyOfRange(storage, 0, 4));
        assertArrayEquals(new byte[4], Arrays.copyOfRange(storage, 4, 8));
        assertArrayEquals(new byte[] {0, 10}, Arrays.copyOfRange(storage, 11, 13));
    }

    @Test
    @DisplayName("PICTURE つき POINTER、NULL 以外の SET、POINTER の MOVE、長さの決まらない LENGTH OF は断る")
    void rejectsUnsupportedForms() {
        for (String[] rejected : List.of(
                new String[] {"01  X PIC X(4) USAGE POINTER.", "MOVE 1 TO N", "cannot have a PICTURE"},
                new String[] {"01  X PIC X.", "SET P TO 5", "supports only TO NULL"},
                new String[] {"01  X PIC X.", "MOVE 'ABCD' TO P", "POINTER item cannot be used in a MOVE"},
                new String[] {"01  X PIC X.", "MOVE LENGTH OF NOPE TO N", "NOPE"})) {
            List<String> storage = new java.util.ArrayList<>(STORAGE);
            storage.add(rejected[0]);
            CobolCompiler.Result result = CobolCompiler.standard().compile("PTRLEN.cbl",
                    source(storage, rejected[1], "GOBACK."));

            assertFalse(result.succeeded(), rejected[1]);
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains(rejected[2])),
                    rejected[1] + " " + result.diagnostics());
        }
    }
}
