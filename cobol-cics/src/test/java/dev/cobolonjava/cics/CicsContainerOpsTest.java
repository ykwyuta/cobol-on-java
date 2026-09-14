package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** GET / PUT CONTAINER の channel と condition。 */
@Tag("V1")
class CicsContainerOpsTest {

    private static final CodePage CP = CodePages.DEFAULT;

    private final CicsExecution execution = new CicsExecution(new CicsTaskContext(
            new CicsTaskId("task_container"), TransId.of("TX01"), "container-test", Instant.EPOCH), 0);
    private final ProgramContext context = ProgramContext.standard().withCodePage(CP).withServices(
            RuntimeServices.builder().service(CicsExecution.class, execution).build());

    private static Storage fullword(int value) {
        return Storage.copyOf(ByteBuffer.allocate(4).putInt(value).array());
    }

    private int eibResp() {
        return ByteBuffer.wrap(execution.eib(CP).storage().array(), 76, 4).getInt();
    }

    @Test
    @DisplayName("名前のchannelからcontainerを読み、FLENGTHにデータの長さを返す")
    void getsFromNamedChannel() {
        execution.openCurrentChannel("CIPCREDCHANN", Map.of("CIPA", CP.encode("ABC")));
        Storage into = Storage.copyOf(CP.encode("-----"));
        Storage length = fullword(5);

        int target = CicsRuntimeOps.getContainerCondition(context, null, CP.encode("CIPA            "),
                "CIPCREDCHANN", null, into.whole(), length.whole(), false);

        assertEquals(CicsRuntimeOps.NO_CONDITION_TRANSFER, target);
        assertEquals("ABC--", CP.decode(into.array()));
        assertArrayEquals(fullword(3).array(), length.array());
    }

    @Test
    @DisplayName("受取域より長いデータは入る分だけ写してLENGERR、無いcontainerとchannelはそれぞれのcondition")
    void raisesContainerConditions() {
        execution.openCurrentChannel("CH1", Map.of("LONG", CP.encode("ABCDEFGH")));
        Storage into = Storage.copyOf(CP.encode("xxxx"));
        Storage length = fullword(4);

        CicsRuntimeOps.getContainerCondition(context, "LONG", null, "CH1", null,
                into.whole(), length.whole(), true);
        assertEquals("ABCD", CP.decode(into.array()));
        assertArrayEquals(fullword(8).array(), length.array());
        assertEquals(CicsResponseCode.LENGERR, eibResp());

        CicsRuntimeOps.getContainerCondition(context, "NONE", null, "CH1", null, into.whole(), null, true);
        assertEquals(CicsResponseCode.CONTAINERERR, eibResp());
        CicsRuntimeOps.getContainerCondition(context, "LONG", null, "OTHER", null, into.whole(), null, true);
        assertEquals(CicsResponseCode.CHANNELERR, eibResp());

        assertThrows(CicsTaskStateException.class, () -> CicsRuntimeOps.getContainerCondition(
                context, "NONE", null, "CH1", null, into.whole(), null, false));
    }

    @Test
    @DisplayName("PUTは現在のchannelへFLENGTHの分だけ置き、名前の分からないchannelと別名は突き合わせない")
    void putsIntoCurrentChannel() {
        execution.openCurrentChannel(null, Map.of());
        CicsRuntimeOps.putContainerCondition(context, "OUT", null, null, null,
                Storage.copyOf(CP.encode("HELLO")).whole(), null, 2, false);

        assertArrayEquals(CP.encode("HE"), execution.currentChannelContainers().orElseThrow().get("OUT"));
        assertThrows(CicsTaskStateException.class, () -> CicsRuntimeOps.putContainerCondition(
                context, "OUT", null, "NAMED", null, Storage.copyOf(CP.encode("X")).whole(), null, -1, false));
        assertThrows(CicsTaskStateException.class, () -> CicsRuntimeOps.putContainerCondition(
                context, "OUT", null, null, null, Storage.copyOf(CP.encode("X")).whole(), null, 2, false));
    }
}
