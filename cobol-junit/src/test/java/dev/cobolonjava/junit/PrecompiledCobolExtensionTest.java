package dev.cobolonjava.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.interop.ProgramSignatureMismatchException;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** コンパイル結果を捨てた生成classからmetadataを復元する結合契約。 */
class PrecompiledCobolExtensionTest {

    private static final String SOURCE = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. PRECOMPILED.",
            "       DATA DIVISION.",
            "       LINKAGE SECTION.",
            "       01 LK-TEXT PIC X(3).",
            "       PROCEDURE DIVISION USING LK-TEXT.",
            "       MAIN-START.",
            "           GOBACK.",
            "       MUTATE SECTION.",
            "       MUTATE-P.",
            "           MOVE 'YES' TO LK-TEXT.");

    @RegisterExtension
    final CobolExtension cobol = CobolExtension.builder()
            .program("PRECOMPILED", precompiled())
            .build();

    @Test
    void invokesASectionUsingOnlyMetadataEmbeddedInTheClass() {
        Storage argument = Storage.allocate(3);

        cobol.program("PRECOMPILED")
                .byReference(argument.whole())
                .invokeSection("MUTATE");

        assertEquals("YES", cobol.codePage().decode(argument.array()));
    }

    @Test
    void validatesTheEmbeddedSignatureWithoutTheCompilerResult() {
        assertThrows(ProgramSignatureMismatchException.class,
                () -> cobol.program("PRECOMPILED")
                        .byReference(Storage.allocate(2).whole())
                        .call());
    }

    private static Supplier<CobolProgram> precompiled() {
        CobolCompiler.Result result = CobolCompiler.standard()
                .compile("PRECOMPILED.cbl", SOURCE);
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        Class<?> type = new ClassLoader(PrecompiledCobolExtensionTest.class.getClassLoader()) {
            private Class<?> define(byte[] bytecode) {
                return defineClass(result.className(), bytecode, 0, bytecode.length);
            }
        }.define(result.classFile());
        return () -> {
            try {
                return (CobolProgram) type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("cannot instantiate precompiled program", failure);
            }
        };
    }
}
