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

    @Test
    @DisplayName("KEYED のファイルは鍵で読み書きし、重なる鍵は ONCODE 52、無い鍵は 51 の KEY 条件になる")
    void keyedFiles() throws Exception {
        assertEquals("01FIRST,52,01EDITED,51", run("""
                DCL KF FILE RECORD KEYED DIRECT UPDATE ENV(F RECSIZE(8) KEYLENGTH(2) KEYLOC(1));
                DCL REC CHAR(8), CODE FIXED BIN(31) INIT(0);
                ON KEY(KF) CODE = ONCODE();
                OPEN FILE(KF) OUTPUT TITLE('KSDS');
                REC = '01FIRST'; WRITE FILE(KF) FROM(REC) KEYFROM('01');
                CLOSE FILE(KF);
                OPEN FILE(KF) UPDATE TITLE('KSDS');
                READ FILE(KF) INTO(REC) KEY('01');
                PUT SKIP LIST(REC);
                WRITE FILE(KF) FROM(REC) KEYFROM('01');
                PUT SKIP LIST(CODE);
                REC = '01EDITED'; REWRITE FILE(KF) FROM(REC) KEY('01');
                READ FILE(KF) INTO(REC) KEY('01');
                PUT SKIP LIST(REC);
                DELETE FILE(KF) KEY('01');
                READ FILE(KF) INTO(REC) KEY('01');
                PUT SKIP LIST(CODE);
                CLOSE FILE(KF);
                """));
    }

    @Test
    @DisplayName("LOCATE で取ったバッファは、次の LOCATE と CLOSE の前に 1 レコードずつ書き出される")
    void locateWritesTheBufferLater() throws Exception {
        run("""
                DCL OUT FILE RECORD OUTPUT ENV(F RECSIZE(4));
                DCL P POINTER;
                DCL X CHAR(4) BASED(P);
                OPEN FILE(OUT);
                LOCATE X FILE(OUT); X = 'AAAA';
                LOCATE X FILE(OUT); X = 'BBBB';
                CLOSE FILE(OUT);
                """);
        byte[] written = Files.readAllBytes(directory.resolve("OUT"));
        assertEquals(8, written.length);
    }

    @Test
    @DisplayName("PRINT ファイルは行と頁を数え、ENDPAGE はその頁で 1 度だけ起き、on-unit が無ければ改頁する")
    void printFilesCountLinesAndPages() throws Exception {
        assertEquals("1 1,3 1,4 1 1,5 1 1,1 2,1 4", run("""
                DCL F FILE STREAM OUTPUT PRINT ENV(F RECSIZE(21));
                DCL G FILE STREAM OUTPUT PRINT ENV(F RECSIZE(21));
                DCL N FIXED BIN(31) INIT(0);
                OPEN FILE(F) LINESIZE(20) PAGESIZE(3);
                PUT FILE(F) SKIP EDIT('ABC')(A);
                PUT SKIP LIST(LINENO(F), PAGENO(F));
                PUT FILE(F) LINE(3) EDIT('GHI')(A);
                PUT SKIP LIST(LINENO(F), PAGENO(F));
                ON ENDPAGE(F) N = N + 1;
                PUT FILE(F) SKIP EDIT('OUT')(A);
                PUT SKIP LIST(LINENO(F), PAGENO(F), N);
                PUT FILE(F) SKIP EDIT('MORE')(A);
                PUT SKIP LIST(LINENO(F), PAGENO(F), N);
                PUT FILE(F) PAGE EDIT('P2')(A);
                PUT SKIP LIST(LINENO(F), PAGENO(F));
                CLOSE FILE(F);
                OPEN FILE(G) PAGESIZE(1);
                PUT FILE(G) SKIP EDIT('A')(A);
                PUT FILE(G) SKIP EDIT('B')(A);
                PUT FILE(G) SKIP EDIT('C')(A);
                PUT FILE(G) SKIP EDIT('D')(A);
                PUT SKIP LIST(LINENO(G), PAGENO(G));
                CLOSE FILE(G);
                """).replaceAll(" +", " ").replace(", ", ","));
        byte[] f = Files.readAllBytes(directory.resolve("F"));
        assertEquals(6 * 21, f.length);
    }
}
