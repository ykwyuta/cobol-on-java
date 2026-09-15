package dev.cobolonjava.ims.dli;

import static dev.cobolonjava.ims.gen.Cards.card;
import static dev.cobolonjava.ims.gen.Cards.deck;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.db.Segment;
import dev.cobolonjava.ims.dbd.DbdParser;
import dev.cobolonjava.ims.psb.PsbParser;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.CobolCallResult;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.storage.DataView;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 翻訳した COBOL から {@code CALL 'CBLTDLI'} を呼ぶ (設計 78、暫定判断 P-152、P-154)。
 *
 * <p>IMS のプログラムの形 — 先頭の {@code ENTRY 'DLITCBL' USING}、POINTER の連絡節の番地に PCB マスクを
 * 結ぶ {@code SET ADDRESS OF}、KEYLEN より長いキー帰還域を書いたマスク — を通して、Java の試験と同じ
 * 呼び出しが届くことを見る。
 */
@Tag("V1")
class CobolDliCallTest {

    private static final List<String> PROGRAM = List.of(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. BANKLOAD.",
            "DATA DIVISION.",
            "WORKING-STORAGE SECTION.",
            "77 ISRT PIC X(4) VALUE 'ISRT'.",
            "77 GHU PIC X(4) VALUE 'GHU '.",
            "77 REPL PIC X(4) VALUE 'REPL'.",
            "77 GN PIC X(4) VALUE 'GN  '.",
            "77 FOUND PIC 9(4) COMP VALUE 0.",
            "01 CUST-SEG.",
            "   05 CUSTNO PIC X(4).",
            "   05 CITY PIC X(6).",
            "01 CUST-UNQ.",
            "   05 FILLER PIC X(8) VALUE 'CUST'.",
            "   05 FILLER PIC X VALUE ' '.",
            "01 CUST-QUAL.",
            "   05 FILLER PIC X(8) VALUE 'CUST'.",
            "   05 FILLER PIC X VALUE '('.",
            "   05 FILLER PIC X(8) VALUE 'CUSTNO'.",
            "   05 FILLER PIC X(2) VALUE 'EQ'.",
            "   05 QUAL-KEY PIC X(4).",
            "   05 FILLER PIC X VALUE ')'.",
            "LINKAGE SECTION.",
            "01 PCB1 POINTER.",
            "01 DBPCB.",
            "   05 DBDNAME PIC X(8).",
            "   05 SEGLEVEL PIC XX.",
            "   05 DBSTAT PIC XX.",
            "   05 PROCOPTS PIC X(4).",
            "   05 FILLER PIC 9(8) COMP.",
            "   05 SEGNAME PIC X(8).",
            "   05 KEYLEN PIC 9(8) COMP.",
            "   05 NSENS PIC 9(8) COMP.",
            "   05 KEYFB PIC X(20).",
            "PROCEDURE DIVISION.",
            "    ENTRY 'DLITCBL' USING PCB1.",
            "BEGIN.",
            "    SET ADDRESS OF DBPCB TO ADDRESS OF PCB1",
            "    MOVE '0002OSAKA' TO CUST-SEG",
            "    CALL 'CBLTDLI' USING ISRT DBPCB CUST-SEG CUST-UNQ",
            "    MOVE '0001TOKYO' TO CUST-SEG",
            "    CALL 'CBLTDLI' USING ISRT DBPCB CUST-SEG CUST-UNQ",
            "    MOVE '0001' TO QUAL-KEY",
            "    CALL 'CBLTDLI' USING GHU DBPCB CUST-SEG CUST-QUAL",
            "    MOVE 'KYOTO' TO CITY",
            "    CALL 'CBLTDLI' USING REPL DBPCB CUST-SEG",
            "    PERFORM UNTIL DBSTAT NOT = SPACES",
            "        CALL 'CBLTDLI' USING GN DBPCB CUST-SEG CUST-UNQ",
            "        IF DBSTAT = SPACES",
            "            ADD 1 TO FOUND",
            "        END-IF",
            "    END-PERFORM",
            "    MOVE FOUND TO RETURN-CODE",
            "    GOBACK.");

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(CobolDliCallTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    @Test
    @DisplayName("翻訳した COBOL が ENTRY 'DLITCBL' で PCB を受け、CALL 'CBLTDLI' で入れて読み替えて辿る")
    void aCompiledProgramCallsDli() throws Exception {
        HierarchicalDatabase database = new HierarchicalDatabase(DbdParser.parse(deck(
                card("         DBD   NAME=BANKDB,ACCESS=HIDAM"),
                card("         SEGM  NAME=CUST,PARENT=0,BYTES=10"),
                card("         FIELD NAME=(CUSTNO,SEQ,U),BYTES=4,START=1"),
                card("         DBDGEN"))));
        ImsRegion region = new ImsRegion(PsbParser.parse(deck(
                card("         PCB   TYPE=DB,DBDNAME=BANKDB,PROCOPT=A,KEYLEN=4"),
                card("         SENSEG NAME=CUST,PARENT=0"),
                card("         PSBGEN PSBNAME=BANKPSB,LANG=COBOL"))), List.of(database), CodePages.DEFAULT);

        StringBuilder source = new StringBuilder();
        for (String line : PROGRAM) {
            source.append("       ").append(line).append('\n');
        }
        CobolCompiler.Result compiled = CobolCompiler.standard().compile("BANKLOAD.cbl", source.toString());
        assertTrue(compiled.succeeded(), () -> compiled.diagnostics().toString());
        Class<?> type = new GeneratedLoader().define(compiled.className(), compiled.classFile());
        ProgramCatalog catalog = region.register(ProgramCatalog.builder()
                .cobolProgram("BANKLOAD", () -> instantiate(type))).build();
        // USING は 4 byte の POINTER だが、マスク (56 byte) はその番地に結ばれて PCB の記憶域の中に収まる
        DataView[] pcbs = region.programArguments(instantiate(type).programSignature());
        assertEquals(4, pcbs[0].length());

        try (CobolSession session = CobolRuntime.builder(catalog).build().openSession()) {
            CobolCallResult result = session.call("BANKLOAD", pcbs);
            // GHU で 0001 を読み替えたあと、GN で 0002 を 1 件読み、次の GN が GB で抜ける
            assertEquals(1, result.returnCode());
        }

        assertEquals(List.of("0001KYOTO ", "0002OSAKA "), database.roots().stream()
                .map(Segment::data).map(CodePages.DEFAULT::decode).toList());
        assertEquals("GB", CodePages.DEFAULT.decode(
                region.programArguments()[0].subView(DatabasePcb.STATUS, 2).toByteArray()));
    }

    private static CobolProgram instantiate(Class<?> type) {
        try {
            return (CobolProgram) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot load the generated program", e);
        }
    }
}
