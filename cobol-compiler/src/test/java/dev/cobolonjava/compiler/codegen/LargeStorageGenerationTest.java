package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 大きな作業場所。初期値の像は class の文字列定数に置くが、文字列定数は符号化して 65535 byte までで、
 * EBCDIC の文字は 1 byte が 2 byte に膨らむ。以前は 32KB を越える作業場所で翻訳が落ちた。
 */
@Tag("V1")
class LargeStorageGenerationTest {

    private static final class Loader extends ClassLoader {

        Loader() {
            super(LargeStorageGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    @Test
    @DisplayName("100 KB の作業場所も翻訳でき、初期値は端まで正しく置かれる")
    void compilesWorkingStorageLargerThanOneConstant() throws ReflectiveOperationException {
        StringBuilder source = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. BIGWS.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01  T.",
                "    05 E PIC X(100) VALUE ALL 'A' OCCURS 1000.",
                "01  LAST-ONE PIC X(3) VALUE 'END'.",
                "PROCEDURE DIVISION.",
                "    GOBACK.")) {
            source.append("       ").append(line).append('\n');
        }

        CobolCompiler.Result result = CobolCompiler.standard().compile("BIGWS.cbl", source.toString());
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        CobolProgram program = (CobolProgram) new Loader().define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        byte[] storage = program.runFresh().array();

        assertEquals(CodePages.DEFAULT.encode("A")[0], storage[0]);
        assertEquals(CodePages.DEFAULT.encode("A")[0], storage[99_999]);
        assertEquals("END", CodePages.DEFAULT.decode(java.util.Arrays.copyOfRange(storage, 100_000, 100_003)));
    }
}
