package dev.cobolonjava.ims.dli;

import static dev.cobolonjava.ims.gen.Cards.card;
import static dev.cobolonjava.ims.gen.Cards.deck;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.db.Segment;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.DbdParser;
import dev.cobolonjava.ims.psb.PsbParser;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.program.CobolProgram;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Bank-of-Z のオンラインと同じ形の MPP を翻訳して流す (設計 78 §4、暫定判断 P-156)。
 *
 * <p>{@code IOPCBA POINTER} の番地に I/O PCB のマスクを結び、QC まで GU を回し、電文ごとに GHU / REPL して
 * I/O PCB へ ISRT で応答する。
 */
@Tag("V1")
class CobolMessageProgramTest {

    private static final List<String> PROGRAM = List.of(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. CUSTUPD.",
            "DATA DIVISION.",
            "WORKING-STORAGE SECTION.",
            "77 GU PIC X(4) VALUE 'GU  '.",
            "77 GHU PIC X(4) VALUE 'GHU '.",
            "77 REPL PIC X(4) VALUE 'REPL'.",
            "77 ISRT PIC X(4) VALUE 'ISRT'.",
            "77 TERM-IO PIC 9 VALUE 0.",
            "01 INPUT-AREA.",
            "   05 LL-IN PIC 9(4) COMP.",
            "   05 ZZ-IN PIC 9(4) COMP.",
            "   05 TRAN-CODE PIC X(8).",
            "   05 IN-KEY PIC X(4).",
            "   05 IN-CITY PIC X(6).",
            "01 OUTPUT-AREA.",
            "   05 LL-OUT PIC 9(4) COMP.",
            "   05 ZZ-OUT PIC 9(4) COMP.",
            "   05 MSG-OUT PIC X(16).",
            "01 CUST-SEG.",
            "   05 CUSTNO PIC X(4).",
            "   05 CITY PIC X(6).",
            "01 CUST-QUAL.",
            "   05 FILLER PIC X(8) VALUE 'CUST'.",
            "   05 FILLER PIC X VALUE '('.",
            "   05 FILLER PIC X(8) VALUE 'CUSTNO'.",
            "   05 FILLER PIC X(2) VALUE 'EQ'.",
            "   05 QUAL-KEY PIC X(4).",
            "   05 FILLER PIC X VALUE ')'.",
            "LINKAGE SECTION.",
            "01 IOPCBA POINTER.",
            "01 DBPCB1 POINTER.",
            "01 LTERMPCB.",
            "   05 LOGTTERM PIC X(8).",
            "   05 FILLER PIC XX.",
            "   05 TPSTAT PIC XX.",
            "   05 IODATE PIC X(4).",
            "   05 IOTIME PIC X(4).",
            "   05 FILLER PIC XX.",
            "   05 SEQNUM PIC XX.",
            "   05 MODNAME PIC X(8).",
            "01 DBPCB.",
            "   05 DBDNAME PIC X(8).",
            "   05 SEGLEVEL PIC XX.",
            "   05 DBSTAT PIC XX.",
            "   05 FILLER PIC X(28).",
            "PROCEDURE DIVISION.",
            "    ENTRY 'DLITCBL' USING IOPCBA DBPCB1.",
            "BEGIN.",
            "    SET ADDRESS OF LTERMPCB TO ADDRESS OF IOPCBA",
            "    SET ADDRESS OF DBPCB TO ADDRESS OF DBPCB1",
            "    PERFORM UNTIL TERM-IO = 1",
            "        CALL 'CBLTDLI' USING GU LTERMPCB INPUT-AREA",
            "        IF TPSTAT = SPACES",
            "            PERFORM UPDATE-CUSTOMER",
            "        ELSE",
            "            MOVE 1 TO TERM-IO",
            "        END-IF",
            "    END-PERFORM",
            "    IF TPSTAT NOT = 'QC'",
            "        MOVE 16 TO RETURN-CODE",
            "    END-IF",
            "    GOBACK.",
            "UPDATE-CUSTOMER.",
            "    MOVE IN-KEY TO QUAL-KEY",
            "    CALL 'CBLTDLI' USING GHU DBPCB CUST-SEG CUST-QUAL",
            "    IF DBSTAT = SPACES",
            "        MOVE IN-CITY TO CITY",
            "        CALL 'CBLTDLI' USING REPL DBPCB CUST-SEG",
            "        MOVE 'UPDATED' TO MSG-OUT",
            "    ELSE",
            "        MOVE 'NO CUSTOMER' TO MSG-OUT",
            "    END-IF",
            "    MOVE 20 TO LL-OUT",
            "    MOVE 0 TO ZZ-OUT",
            "    CALL 'CBLTDLI' USING ISRT LTERMPCB OUTPUT-AREA.");

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(CobolMessageProgramTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    @Test
    @DisplayName("翻訳した MPP が QC まで電文を受け、データベースを読み替えて I/O PCB へ応答する")
    void aCompiledMessageProgramAnswersEachMessage() throws Exception {
        DatabaseDefinition dbd = DbdParser.parse(deck(
                card("         DBD   NAME=BANKDB,ACCESS=HDAM"),
                card("         SEGM  NAME=CUST,PARENT=0,BYTES=10"),
                card("         FIELD NAME=(CUSTNO,SEQ,U),BYTES=4,START=1"),
                card("         DBDGEN")));
        HierarchicalDatabase database = new HierarchicalDatabase(dbd);
        database.insert(null, dbd.root(), CodePages.DEFAULT.encode("0001OSAKA "));
        InMemoryMessageQueue queue = new InMemoryMessageQueue()
                .offer(new InputMessage("LTERM001", List.of(CodePages.DEFAULT.encode("CUSTUPD 0001KOBE  "))))
                .offer(new InputMessage("LTERM002", List.of(CodePages.DEFAULT.encode("CUSTUPD 0009NARA  "))));
        ImsRegion region = new ImsRegion(PsbParser.parse(deck(
                card("         PCB   TYPE=DB,DBDNAME=BANKDB,PROCOPT=A,KEYLEN=4"),
                card("         SENSEG NAME=CUST,PARENT=0"),
                card("         PSBGEN PSBNAME=CUSTUPD,LANG=COBOL"))), List.of(database), CodePages.DEFAULT, true,
                queue, Clock.systemUTC());

        StringBuilder source = new StringBuilder();
        for (String line : PROGRAM) {
            source.append("       ").append(line).append('\n');
        }
        CobolCompiler.Result compiled = CobolCompiler.standard().compile("CUSTUPD.cbl", source.toString());
        assertTrue(compiled.succeeded(), () -> compiled.diagnostics().toString());
        Class<?> type = new GeneratedLoader().define(compiled.className(), compiled.classFile());
        ProgramCatalog catalog = region.register(ProgramCatalog.builder()
                .cobolProgram("CUSTUPD", () -> instantiate(type))).build();

        try (CobolSession session = CobolRuntime.builder(catalog).build().openSession()) {
            assertEquals(0, session.call("CUSTUPD", region.programArguments(instantiate(type).programSignature()))
                    .returnCode());
        }
        region.finish(true);

        List<OutputMessage> sent = queue.sent();
        assertEquals(List.of("LTERM001", "LTERM002"), sent.stream().map(OutputMessage::destination).toList());
        assertEquals(List.of("UPDATED         ", "NO CUSTOMER     "),
                sent.stream().map(message -> CodePages.DEFAULT.decode(message.segments().get(0))).toList());
        assertEquals(List.of("0001KOBE  "), database.roots().stream()
                .map(Segment::data).map(CodePages.DEFAULT::decode).toList());
    }

    private static CobolProgram instantiate(Class<?> type) {
        try {
            return (CobolProgram) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot load the generated program", e);
        }
    }
}
