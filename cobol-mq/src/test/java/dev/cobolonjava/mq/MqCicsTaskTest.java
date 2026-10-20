package dev.cobolonjava.mq;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsStartData;
import dev.cobolonjava.cics.CicsTaskContext;
import dev.cobolonjava.cics.CicsTaskId;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.cics.CobolCicsTaskProgram;
import dev.cobolonjava.cics.TransId;
import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.compiler.source.CicsSystemCopyBookResolver;
import dev.cobolonjava.compiler.source.CopyBookResolver;
import dev.cobolonjava.compiler.source.MqSystemCopyBookResolver;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.ProgramId;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CICS の task の中の MQI (設計 88 §4.3)。CardDemo の CODATE01 / COACCT01 と同じ形: trigger monitor が渡す MQTM を
 * RETRIEVE で受け、その待ち行列から同期点の下で要求を取り、応答を置き、{@code EXEC CICS SYNCPOINT} で確約する。
 */
@Tag("V1")
class MqCicsTaskTest {

    private static final CodePage CP = CodePages.DEFAULT;

    private static final List<String> DATA = List.of(
            "01 TM. COPY CMQTML.",
            "01 WS-RESP PIC S9(8) COMP.",
            "01 HIN PIC S9(9) BINARY.",
            "01 HOUT PIC S9(9) BINARY.",
            "01 MORE PIC X VALUE 'Y'.",
            "01 WORK PIC X(10).",
            "01 SEEN PIC 9 VALUE 0.");

    private static final List<String> MAIN = List.of(
            "    EXEC CICS RETRIEVE INTO(MQTM) RESP(WS-RESP) END-EXEC",
            "    MOVE MQTM-QNAME TO MQOD-OBJECTNAME",
            "    COMPUTE OPTS = MQOO-INPUT-SHARED",
            "    PERFORM OPEN-Q",
            "    MOVE HOBJ TO HIN",
            "    MOVE 'APP.OUT' TO MQOD-OBJECTNAME",
            "    COMPUTE OPTS = MQOO-OUTPUT",
            "    PERFORM OPEN-Q",
            "    MOVE HOBJ TO HOUT",
            "    PERFORM UNTIL MORE = 'N'",
            "        MOVE HIN TO HOBJ",
            "        COMPUTE MQGMO-OPTIONS = MQGMO-SYNCPOINT + MQGMO-WAIT",
            "        MOVE 100 TO MQGMO-WAITINTERVAL",
            "        MOVE 20 TO BUFLEN",
            "        PERFORM GET-MSG",
            "        IF CC NOT = MQCC-OK",
            "            MOVE 'N' TO MORE",
            "        ELSE",
            "            ADD 1 TO SEEN",
            "            MOVE BUF TO WORK",
            "            MOVE SPACES TO BUF",
            "            STRING 'ECHO ' WORK DELIMITED BY SIZE INTO BUF",
            "            MOVE HOUT TO HOBJ",
            "            COMPUTE MQPMO-OPTIONS = MQPMO-SYNCPOINT",
            "                                  + MQPMO-DEFAULT-CONTEXT",
            "            MOVE 15 TO BUFLEN",
            "            PERFORM PUT-MSG",
            "            IF WORK = 'BOOM'",
            "                EXEC CICS ABEND ABCODE('MQAB') END-EXEC",
            "            END-IF",
            "            EXEC CICS SYNCPOINT END-EXEC",
            "        END-IF",
            "    END-PERFORM",
            "    EXEC CICS RETURN END-EXEC");

    private static MqQueueManager manager() {
        return new MqQueueManager("QM01", CP, 1047, java.time.Clock.systemUTC(), MqConstants.MQAT_CICS)
                .defineLocalQueue("APP.IN")
                .defineLocalQueue("APP.OUT");
    }

    /** trigger monitor が START で渡す MQTM (1 版、684 byte)。 */
    private static byte[] triggerMessage(String queue) {
        ByteBuffer tm = ByteBuffer.allocate(684);
        byte[] spaces = new byte[684];
        Arrays.fill(spaces, CP.space());
        tm.put(spaces).rewind();
        tm.put(CP.encode("TM  ")).putInt(1);
        byte[] name = Arrays.copyOf(CP.encode(queue), 48);
        Arrays.fill(name, queue.length(), 48, CP.space());
        tm.put(name);
        tm.putInt(172, 1);
        return tm.array();
    }

    private static void runTask(MqQueueManager qm) {
        CopyBookResolver mq = new MqSystemCopyBookResolver();
        CopyBookResolver cics = new CicsSystemCopyBookResolver();
        CopyBookResolver both = (text, library) -> mq.resolve(text, library).or(() -> cics.resolve(text, library));
        MqProgram program = MqProgram.of("MQECHO", CobolCompiler.with(both)
                .compile("MQECHO.cbl", MqProgram.source("MQECHO", DATA, MAIN)));
        ProgramCatalog catalog = Mqi.register(ProgramCatalog.builder()
                .cobolProgram("MQECHO", program.factory()), qm).build();
        CicsStartData start = new CicsStartData("TRIG0001", TransId.of("MQEC"), triggerMessage("APP.IN"),
                Optional.empty(), Optional.empty(), Optional.empty(), "trigger-monitor", Optional.empty());
        new CobolCicsTaskProgram(CobolRuntime.builder(catalog).classLoader(program.loader()).build(), 2)
                .execute(new CicsTransactionDefinition(TransId.of("MQEC"), ProgramId.of("MQECHO"),
                                Duration.ofSeconds(10), 16, 0, 0, 0, true),
                        CicsPayload.ofCommarea(new byte[0]),
                        new CicsTaskContext(new CicsTaskId("task_mq"), TransId.of("MQEC"), "mq-test",
                                Instant.now()).withStart(Optional.of(start)),
                        (action, ignored) -> { });
    }

    private static byte[] correl(char c) {
        byte[] id = new byte[24];
        Arrays.fill(id, CP.ch(c));
        return id;
    }

    @Test
    @DisplayName("MQTM の待ち行列から要求を取り、応答を同じ MsgId / CorrelId で置き、SYNCPOINT と task の終わりで確約する")
    void answersEveryRequest() {
        MqQueueManager qm = manager();
        MqMessage one = qm.put("APP.IN", MqMessage.builder(CP).text("ONE").correlationId(correl('1')).build());
        qm.put("APP.IN", MqMessage.builder(CP).text("TWO").correlationId(correl('2')).build());

        runTask(qm);

        List<MqMessage> replies = qm.messages("APP.OUT");
        assertEquals(List.of("ECHO ONE", "ECHO TWO"), replies.stream().map(m -> m.text(CP)).toList());
        assertArrayEquals(correl('1'), replies.get(0).correlationId());
        assertArrayEquals(one.messageId(), replies.get(0).messageId());
        assertEquals(MqConstants.MQAT_CICS, Structure.readInteger(replies.get(0).descriptor(),
                Structure.MD_PUT_APPL_TYPE));
        assertEquals(0, qm.depth("APP.IN"));
    }

    @Test
    @DisplayName("task が ABEND すれば、最後の SYNCPOINT より後の GET と PUT は取り消され、要求は BackoutCount 1 で戻る")
    void abendBacksOutTheLastUnitOfWork() {
        MqQueueManager qm = manager();
        qm.put("APP.IN", MqMessage.builder(CP).text("ONE").build());
        qm.put("APP.IN", MqMessage.builder(CP).text("BOOM").build());

        assertThrows(RuntimeException.class, () -> runTask(qm));

        assertEquals(List.of("ECHO ONE"), qm.messages("APP.OUT").stream().map(m -> m.text(CP)).toList());
        List<MqMessage> left = qm.messages("APP.IN");
        assertEquals(List.of("BOOM"), left.stream().map(m -> m.text(CP)).toList());
        assertEquals(1, left.get(0).backoutCount());
        assertEquals(1, qm.depth("APP.OUT"), "取り消した応答は深さにも残らない");
    }
}
