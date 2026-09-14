package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 数字編集項目から数字編集項目への転記。 */
@Tag("V1")
class NumericEditedToNumericEditedMoveTest {

    private static final class Loader extends ClassLoader {

        Loader() {
            super(NumericEditedToNumericEditedMoveTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    @Test
    @DisplayName("送り側の編集を解いた値を、受取側の編集で書き直す")
    void deEditsThenEdits() throws ReflectiveOperationException {
        String source = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. NEDIT.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  A PIC 9999.99.",
                "       01  B PIC ZZZ9.99.",
                "       PROCEDURE DIVISION.",
                "           MOVE 12.5 TO A",
                "           MOVE A TO B",
                "           GOBACK.") + "\n";
        CobolCompiler.Result result = CobolCompiler.standard().compile("NEDIT.cbl", source);
        assertTrue(result.succeeded(), result.diagnostics().toString());

        CobolProgram program = (CobolProgram) new Loader().define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        byte[] storage = program.runFresh().array();

        assertEquals("0012.50", CodePages.DEFAULT.decode(Arrays.copyOfRange(storage, 0, 7)));
        assertEquals("  12.50", CodePages.DEFAULT.decode(Arrays.copyOfRange(storage, 7, 14)));
    }
}
