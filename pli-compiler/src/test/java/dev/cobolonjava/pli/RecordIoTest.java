package dev.cobolonjava.pli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePage;
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

/**
 * レコード入出力 (Enterprise PL/I Language Reference, "OPEN statement"、"WRITE statement"、
 * "READ statement"、"Implicit opening"、"RECORD condition")。ファイルは DD 名 (TITLE か
 * ファイルの名) で作業場所のデータセットを引く。
 */
class RecordIoTest {

    @TempDir
    Path directory;

    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader() {
            super(RecordIoTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private ProgramContext context;

    /** 本体を主手続きに包んで動かし、SYSPRINT を返す。 */
    private String run(String body) throws Exception {
        PliCompiler.Result result = PliCompiler.standard().compile("RIO.pli",
                "RIO: PROCEDURE OPTIONS(MAIN);\n" + body + "\nEND RIO;\n");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        CobolProgram program = (CobolProgram) new GeneratedLoader()
                .define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        context = ProgramContext.capturing(output).withCatalog(new DataSetCatalog(directory));
        program.runFresh(context);
        return output.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n");
    }

    private String dataSet(String dd) throws Exception {
        CodePage codePage = context.codePage();
        return codePage.decode(Files.readAllBytes(context.catalog().resolve(dd)));
    }

    @Test
    @DisplayName("WRITE FROM は変数の記憶域を F 様式の 1 レコードにし、CLOSE で DD 名のデータセットへ出す")
    void writeProducesFixedRecordsUnderTheFileName() throws Exception {
        run("""
                DCL REPORT FILE RECORD OUTPUT ENV(F RECSIZE(6));
                DCL LINE CHAR(6);
                OPEN FILE(REPORT);
                LINE = 'AB'; WRITE FILE(REPORT) FROM(LINE);
                LINE = 'CDEFGH'; WRITE FILE(REPORT) FROM(LINE);
                CLOSE FILE(REPORT);
                """);
        // 改行は入らない。'AB' の右は空白で埋まった 6 byte である
        assertEquals("AB    CDEFGH", dataSet("REPORT"));
    }

    @Test
    @DisplayName("TITLE が DD 名になり、書いたものを別のファイル定数で読み返せる。尽きれば ON ENDFILE")
    void titleNamesTheDataSetAndReadSeesWhatWasWritten() throws Exception {
        String printed = run("""
                DCL OUT FILE RECORD OUTPUT ENV(F RECSIZE(4));
                DCL INP FILE RECORD INPUT ENV(F RECSIZE(4));
                DCL REC CHAR(4);
                DCL EOF BIT(1) INIT('0'B);
                OPEN FILE(OUT) TITLE('WORK1');
                REC = 'ONE '; WRITE FILE(OUT) FROM(REC);
                REC = 'TWO '; WRITE FILE(OUT) FROM(REC);
                CLOSE FILE(OUT);
                ON ENDFILE(INP) BEGIN; EOF = '1'B; END;
                OPEN FILE(INP) TITLE('WORK1');
                READ FILE(INP) INTO(REC);
                DO WHILE(^EOF);
                  PUT SKIP LIST(REC);
                  READ FILE(INP) INTO(REC);
                END;
                CLOSE FILE(INP);
                """);
        assertEquals("ONE \nTWO \n", printed);
        assertFalse(Files.exists(context.catalog().resolve("OUT")));
    }

    @Test
    @DisplayName("OPEN を書かない WRITE は暗に OUTPUT で開き、プログラムの終わりに閉じる")
    void writeOpensImplicitlyAndTheEndOfTheProgramCloses() throws Exception {
        run("""
                DCL F FILE RECORD ENV(F RECSIZE(3));
                DCL X CHAR(3) INIT('XYZ');
                WRITE FILE(F) FROM(X);
                """);
        assertEquals("XYZ", dataSet("F"));
    }

    @Test
    @DisplayName("F 様式でレコード長と FROM の長さが違えば RECORD で止まる (黙って切り詰めない)")
    void lengthMismatchRaisesRecord() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run("""
                DCL F FILE RECORD OUTPUT ENV(F RECSIZE(8));
                DCL X CHAR(3) INIT('XYZ');
                WRITE FILE(F) FROM(X);
                """));
        assertTrue(failure.getMessage().contains("RECORD"), failure.getMessage());
    }

    @Test
    @DisplayName("INPUT と宣言したファイルへの WRITE は UNDEFINEDFILE で止まる")
    void writeToAnInputFileIsRefused() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run("""
                DCL F FILE RECORD INPUT ENV(F RECSIZE(3));
                DCL X CHAR(3) INIT('XYZ');
                WRITE FILE(F) FROM(X);
                """));
        assertTrue(failure.getMessage().contains("UNDEFINEDFILE"), failure.getMessage());
    }

    @Test
    @DisplayName("1 つの OPEN / CLOSE に複数のファイルを書ける")
    void openAndCloseTakeSeveralFiles() throws Exception {
        run("""
                DCL A FILE RECORD ENV(F RECSIZE(1));
                DCL B FILE RECORD ENV(F RECSIZE(1));
                DCL X CHAR(1) INIT('A');
                OPEN FILE(A) OUTPUT, FILE(B) OUTPUT;
                WRITE FILE(A) FROM(X);
                X = 'B'; WRITE FILE(B) FROM(X);
                CLOSE FILE(A), FILE(B);
                """);
        assertEquals("A", dataSet("A"));
        assertEquals("B", dataSet("B"));
    }

    @Test
    @DisplayName("鍵つきのファイルと F の族のほかの様式は、まだ持たないので翻訳で断る")
    void keyedFilesAndOtherFormatsAreRefused() {
        for (String declaration : new String[] {
                "DCL F FILE RECORD KEYED;", "DCL F FILE RECORD ENV(V RECSIZE(10));"}) {
            PliCompiler.Result result = PliCompiler.standard().compile("RIO.pli",
                    "RIO: PROCEDURE OPTIONS(MAIN);\n" + declaration + "\nEND RIO;\n");
            assertFalse(result.succeeded(), declaration);
        }
    }
}
