package dev.cobolonjava.mq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 起動の条件と起動電文 (設計 88 §4.5、暫定判断 P-255)。 */
@Tag("V1")
class MqTriggerTest {

    private static final CodePage CP = CodePages.DEFAULT;

    private static MqQueueManager manager(TriggerDefinition.Type type, int depth) {
        return new MqQueueManager("QM01", CP, 1047, Clock.systemUTC(), MqConstants.MQAT_CICS)
                .defineLocalQueue("INITQ")
                .define(new ProcessDefinition("PROC", "TRN1 extra", MqConstants.MQAT_CICS, "ENV", "USR"))
                .define(QueueDefinition.local("APP.Q").withTrigger(
                        TriggerDefinition.of(type, "INITQ", "PROC").withDepth(depth).withData("TDATA")));
    }

    private static void put(MqQueueManager qm, String text) {
        qm.put("APP.Q", MqMessage.builder(CP).text(text).build());
    }

    @Test
    @DisplayName("起動電文は MQTM 1 版で、QName・ProcessName・TriggerData・ApplType・ApplId・EnvData・UserData を持つ")
    void triggerMessageLayout() {
        MqQueueManager qm = manager(TriggerDefinition.Type.FIRST, 1);
        try (MqTriggerMonitor monitor = new MqTriggerMonitor(qm, "INITQ")) {
            put(qm, "A");
            MqMessage raw = qm.messages("INITQ").get(0);
            assertEquals("MQTRIG", raw.format(CP));
            List<TriggerMessage> triggers = monitor.poll();
            assertEquals(1, triggers.size());
            byte[] tm = triggers.get(0).bytes();
            assertEquals(684, tm.length);
            assertEquals("TM  ", text(tm, 0, 4));
            assertEquals(1, Structure.readInteger(tm, 4));
            assertEquals("APP.Q", text(tm, 8, 48).strip());
            assertEquals("PROC", text(tm, 56, 48).strip());
            assertEquals("TDATA", text(tm, 104, 64).strip());
            assertEquals(MqConstants.MQAT_CICS, Structure.readInteger(tm, 168));
            assertEquals("TRN1 extra", text(tm, 172, 256).strip());
            assertEquals("ENV", text(tm, 428, 128).strip());
            assertEquals("USR", text(tm, 556, 128).strip());
            assertEquals("TRN1", triggers.get(0).transactionId());
        }
    }

    @Test
    @DisplayName("FIRST は 0 から 1 のときだけ、EVERY は毎回、DEPTH は TRIGDPTH に達したとき 1 度で TRIGGER が切れる")
    void triggerTypes() {
        MqQueueManager first = manager(TriggerDefinition.Type.FIRST, 1);
        MqQueueManager every = manager(TriggerDefinition.Type.EVERY, 1);
        MqQueueManager depth = manager(TriggerDefinition.Type.DEPTH, 3);
        try (MqTriggerMonitor f = new MqTriggerMonitor(first, "INITQ");
             MqTriggerMonitor e = new MqTriggerMonitor(every, "INITQ");
             MqTriggerMonitor d = new MqTriggerMonitor(depth, "INITQ")) {
            for (int i = 0; i < 4; i++) {
                put(first, "M" + i);
                put(every, "M" + i);
                put(depth, "M" + i);
            }
            assertEquals(1, f.poll().size());
            assertEquals(4, e.poll().size());
            assertEquals(1, d.poll().size());
            depth.setTriggerControl("APP.Q", true);
            put(depth, "M4");
            assertEquals(1, d.poll().size(), "TRIGGER を戻せば、深さが TRIGDPTH 以上なので次の電文で起動する");
        }
    }

    @Test
    @DisplayName("開始待ち行列を開いている monitor が無ければ起動しない。FIRST は待ち行列を入力で開いているものがあっても起動しない")
    void noTriggerWithoutAMonitorOrWithAnOpenReader() {
        MqQueueManager qm = manager(TriggerDefinition.Type.FIRST, 1);
        put(qm, "BEFORE");
        assertEquals(0, qm.depth("INITQ"));
        MqQueueManager other = manager(TriggerDefinition.Type.FIRST, 1);
        try (MqTriggerMonitor monitor = new MqTriggerMonitor(other, "INITQ");
             MqTriggerMonitor reader = new MqTriggerMonitor(other, "APP.Q")) {
            put(other, "WHILE-OPEN");
            assertEquals(0, monitor.poll().size());
        }
    }

    @Test
    @DisplayName("同期点の下の PUT は確約で起動し、取り消せば起動しない")
    void syncpointPutTriggersOnCommit() {
        MqQueueManager qm = manager(TriggerDefinition.Type.EVERY, 1);
        try (MqTriggerMonitor monitor = new MqTriggerMonitor(qm, "INITQ")) {
            List<String> lines = MqProgram.compile("TRIGPUT", List.of(
                    "    MOVE 'APP.Q' TO MQOD-OBJECTNAME",
                    "    COMPUTE OPTS = MQOO-OUTPUT",
                    "    PERFORM OPEN-Q",
                    "    COMPUTE MQPMO-OPTIONS = MQPMO-SYNCPOINT",
                    "    MOVE 4 TO BUFLEN",
                    "    MOVE 'DROP' TO BUF PERFORM PUT-MSG",
                    "    CALL 'MQBACK' USING HCONN CC RC",
                    "    DISPLAY 'AFTER-BACK'",
                    "    MOVE 'KEEP' TO BUF PERFORM PUT-MSG",
                    "    CALL 'MQCMIT' USING HCONN CC RC")).run(qm);
            assertEquals(List.of("AFTER-BACK"), lines);
            assertEquals(1, monitor.poll().size());
            assertEquals(List.of("KEEP"), qm.messages("APP.Q").stream().map(m -> m.text(CP)).toList());
        }
    }

    @Test
    @DisplayName("開始待ち行列に起動電文でないものがあれば、それらしい transaction を起こさずに断る")
    void refusesNonTriggerMessages() {
        MqQueueManager qm = manager(TriggerDefinition.Type.FIRST, 1);
        try (MqTriggerMonitor monitor = new MqTriggerMonitor(qm, "INITQ")) {
            qm.put("INITQ", MqMessage.builder(CP).text("NOT A TRIGGER").build());
            assertThrows(IllegalArgumentException.class, monitor::poll);
        }
    }

    private static String text(byte[] b, int offset, int length) {
        return CP.decode(Arrays.copyOfRange(b, offset, offset + length));
    }
}
