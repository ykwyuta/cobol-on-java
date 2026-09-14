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
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** INQUIRE ASSOCIATION の origin data (暫定判断 P-132)。 */
@Tag("V1")
class CicsAssociationTest {

    private static final CodePage CP = CodePages.DEFAULT;

    private static ProgramContext context(CicsEnvironment environment, Optional<String> terminal,
                                          Optional<String> user) {
        CicsTaskContext task = new CicsTaskContext(new CicsTaskId("task_assoc"), TransId.of("OCRA"),
                "association-test", Instant.EPOCH).withTerminalId(terminal).withUserId(user);
        CicsExecution execution = new CicsExecution(task, 0, environment);
        return ProgramContext.standard().withCodePage(CP).withServices(
                RuntimeServices.builder().service(CicsExecution.class, execution).build());
    }

    @Test
    @DisplayName("端末から起きたtask自身のorigin dataを、regionとtaskの値から8文字に詰めて返す")
    void returnsOriginDataOfTheTerminalTask() {
        ProgramContext context = context(CicsEnvironment.withApplid("CICSA01").withNetworkId("NETA"),
                Optional.of("T001"), Optional.of("USER01"));
        Storage applid = Storage.allocate(8);
        Storage user = Storage.allocate(8);
        Storage facility = Storage.allocate(8);
        Storage network = Storage.allocate(8);
        Storage type = Storage.allocate(4);

        CicsRuntimeOps.inquireAssociationCondition(context, applid.whole(), user.whole(), facility.whole(),
                network.whole(), type.whole(), false);

        assertEquals("CICSA01 ", CP.decode(applid.array()));
        assertEquals("USER01  ", CP.decode(user.array()));
        assertEquals("T001    ", CP.decode(facility.array()));
        assertEquals("NETA    ", CP.decode(network.array()));
        assertArrayEquals(ByteBuffer.allocate(4).putInt(214).array(), type.array());
    }

    @Test
    @DisplayName("値の出どころが無いoptionは空白と推測せず失敗させ、書かれていないoptionは要求しない")
    void failsWhenAnOriginValueIsUnknown() {
        ProgramContext noUser = context(CicsEnvironment.withApplid("CICSA01"), Optional.of("T001"), Optional.empty());
        assertThrows(CicsTaskStateException.class, () -> CicsRuntimeOps.inquireAssociationCondition(
                noUser, null, Storage.allocate(8).whole(), null, null, null, false));
        assertThrows(CicsTaskStateException.class, () -> CicsRuntimeOps.inquireAssociationCondition(
                context(CicsEnvironment.unconfigured(), Optional.empty(), Optional.of("USER01")),
                null, null, null, null, Storage.allocate(4).whole(), false));
        Storage applid = Storage.allocate(8);
        CicsRuntimeOps.inquireAssociationCondition(noUser, applid.whole(), null, null, null, null, false);
        assertEquals("CICSA01 ", CP.decode(applid.array()));
    }
}
