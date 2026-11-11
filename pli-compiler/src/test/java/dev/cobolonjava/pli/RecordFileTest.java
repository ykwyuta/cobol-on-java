package dev.cobolonjava.pli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.file.DataSetCatalog;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** RECORD ファイルの OPEN と TITLE (Enterprise PL/I Language Reference, "OPEN statement")。 */
class RecordFileTest {

    @TempDir
    Path directory;

    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader() {
            super(RecordFileTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private String run(String body) throws Exception {
        PliCompiler.Result result = PliCompiler.standard().compile("RF.pli",
                "RF: PROCEDURE OPTIONS(MAIN);\n" + body + "\nEND RF;\n");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        CobolProgram program = (CobolProgram) new GeneratedLoader()
                .define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        program.runFresh(ProgramContext.capturing(output)
                .withCatalog(new DataSetCatalog(directory)));
        return String.join(",", output.toString(StandardCharsets.UTF_8).strip()
                .lines().map(String::strip).toList());
    }

    @Test
    @DisplayName("TITLE で結んだ DD を、別の名のファイルが書いて読む。ファイルの名の DD は作らない")
    void titleChoosesTheDdName() throws Exception {
        assertEquals("FIRST001,SECOND02,EOF", run("""
                DCL OUT FILE RECORD OUTPUT ENV(F RECSIZE(8));
                DCL INP FILE RECORD INPUT ENV(F RECSIZE(8));
                DCL REC CHAR(8), EOF BIT(1) INIT('0'B);
                OPEN FILE(OUT) TITLE('SHARED');
                REC = 'FIRST001'; WRITE FILE(OUT) FROM(REC);
                REC = 'SECOND02'; WRITE FILE(OUT) FROM(REC);
                CLOSE FILE(OUT);
                ON ENDFILE(INP) EOF = '1'B;
                OPEN FILE(INP) TITLE('SHARED');
                READ FILE(INP) INTO(REC); PUT SKIP LIST(REC);
                READ FILE(INP) INTO(REC); PUT SKIP LIST(REC);
                READ FILE(INP) INTO(REC);
                IF EOF THEN PUT SKIP LIST('EOF');
                CLOSE FILE(INP);
                """));
        assertTrue(Files.exists(directory.resolve("SHARED")));
        assertFalse(Files.exists(directory.resolve("OUT")));
        assertFalse(Files.exists(directory.resolve("INP")));
    }

    @Test
    @DisplayName("ENV(V RECSIZE(12)) の RECSIZE は RDW の 4 byte を含み、8 byte のレコードが書ける")
    void variableRecordSizeIncludesTheRdw() throws Exception {
        run("""
                DCL OUT FILE RECORD OUTPUT ENV(V RECSIZE(12));
                DCL REC CHAR(8) INIT('ABCDEFGH');
                OPEN FILE(OUT);
                WRITE FILE(OUT) FROM(REC);
                CLOSE FILE(OUT);
                """);
        byte[] written = Files.readAllBytes(directory.resolve("OUT"));
        assertEquals(12, written.length);
        assertArrayEquals(new byte[] {0, 12, 0, 0}, java.util.Arrays.copyOf(written, 4));
    }
}
