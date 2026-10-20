package dev.cobolonjava.hlasm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.file.DataSetCatalog;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 組み込みの {@code DCB} / {@code OPEN} / {@code PUT} / {@code CLOSE} (P-231)。
 *
 * <p>期待は DFSMS Macro Instructions for Data Sets が説明する振る舞いから書いた。OPEN は R15 に 0 を返し、
 * PUT は LRECL バイトを 1 レコードとして書き、開く前の DCB への PUT は異常終了する。
 */
class DataManagementTest {

    @TempDir
    Path directory;

    private int run(String... lines) {
        Assembler.Result result = Assembler.assemble("W.asm", String.join("\n", lines));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        ProgramContext context = ProgramContext.capturing(new ByteArrayOutputStream())
                .withCatalog(new DataSetCatalog(directory));
        Storage area = Storage.allocate(4);
        HlasmRuntime.execute(result.module(), context, new DataView[] {area.whole()});
        return area.whole().storage().array()[3];
    }

    private static byte[] ebcdic(String text) {
        return text.getBytes(CodePages.DEFAULT.charset());
    }

    @Test
    @DisplayName("OPEN した DCB へ PUT した固定長のレコードが、DD 名のファイルに順に入る")
    void writesFixedLengthRecordsToTheDdName() throws Exception {
        int rc = run(
                "W        CSECT",
                "         USING W,12",
                "         LR    12,15",
                "         ST    14,SAVE14",
                "         L     9,0(0,1)",
                "         OPEN  (OUT,(OUTPUT))",
                "         STC   15,3(0,9)",
                "         PUT   OUT,REC1",
                "         LA    2,REC2",
                "         PUT   OUT,(2)",
                "         CLOSE (OUT)",
                "         L     14,SAVE14",
                "         SR    15,15",
                "         BR    14",
                "SAVE14   DS    F",
                "REC1     DC    CL6'FIRST'",
                "REC2     DC    CL6'SECOND'",
                "OUT      DCB   DDNAME=REPORT,MACRF=PM,RECFM=F,LRECL=6",
                "         LTORG",
                "         END");
        assertEquals(0, rc);
        assertArrayEquals(ebcdic("FIRST SECOND"), Files.readAllBytes(directory.resolve("REPORT")));
    }

    @Test
    @DisplayName("CLOSE しないで終わっても、タスクの終わりに閉じてレコードが残る")
    void closesOpenDataControlBlocksAtTheEndOfTheTask() throws Exception {
        run(
                "W        CSECT",
                "         USING W,12",
                "         LR    12,15",
                "         ST    14,SAVE14",
                "         OPEN  (OUT,OUTPUT)",
                "         PUT   OUT,REC1",
                "         L     14,SAVE14",
                "         BR    14",
                "SAVE14   DS    F",
                "REC1     DC    CL3'ONE'",
                "OUT      DCB   DDNAME=LOG,MACRF=PM,RECFM=FB,LRECL=3,BLKSIZE=30",
                "         LTORG",
                "         END");
        assertArrayEquals(ebcdic("ONE"), Files.readAllBytes(directory.resolve("LOG")));
    }

    @Test
    @DisplayName("開く前の DCB への PUT は、入口が 0 なので 0 番地へ分岐して止まる")
    void putBeforeOpenFails() {
        assertThrows(HlasmRuntime.HlasmExecutionException.class, () -> run(
                "W        CSECT",
                "         USING W,12",
                "         LR    12,15",
                "         PUT   OUT,REC1",
                "         BR    14",
                "REC1     DC    CL3'ONE'",
                "OUT      DCB   DDNAME=LOG,MACRF=PM,RECFM=F,LRECL=3",
                "         LTORG",
                "         END"));
        assertFalse(Files.exists(directory.resolve("LOG")));
    }

    @Test
    @DisplayName("入力、locate 方式、可変長はまだ断る")
    void rejectsWhatIsNotImplemented() {
        assertFalse(Assembler.assemble("W.asm", String.join("\n",
                "W        CSECT",
                "         OPEN  (IN,(INPUT))",
                "IN       DCB   DDNAME=IN,MACRF=PM,RECFM=F,LRECL=3",
                "         END")).succeeded());
        assertFalse(Assembler.assemble("W.asm", String.join("\n",
                "W        CSECT",
                "IN       DCB   DDNAME=IN,MACRF=GM,RECFM=F,LRECL=3",
                "         END")).succeeded());
        assertFalse(Assembler.assemble("W.asm", String.join("\n",
                "W        CSECT",
                "IN       DCB   DDNAME=IN,MACRF=PM,RECFM=VB,LRECL=3",
                "         END")).succeeded());
    }

    @Test
    @DisplayName("DCB の長さ属性は制御ブロック全体である")
    void lengthAttributeCoversTheWholeBlock() {
        Assembler.Result result = Assembler.assemble("W.asm", String.join("\n",
                "W        CSECT",
                "         DC    X'01'",
                "OUT      DCB   DDNAME=REPORT,MACRF=PM,RECFM=F,LRECL=120",
                "         END"));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        assertEquals(96, result.module().symbols().get("OUT").length());
        assertEquals(4, result.module().symbols().get("OUT").value().value());
        assertEquals(100, result.module().length());
    }
}
