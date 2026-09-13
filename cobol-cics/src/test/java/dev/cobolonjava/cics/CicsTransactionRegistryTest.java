package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.interop.ProgramId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class CicsTransactionRegistryTest {

    @Test
    @DisplayName("外部TRANSIDを正規化し許可済みprogramだけへ解決する")
    void resolvesOnlyRegisteredTransactions() {
        CicsTransactionDefinition definition = definition("ab1", true);
        CicsTransactionRegistry registry = new CicsTransactionRegistry(List.of(definition));

        assertEquals(ProgramId.of("ENTRY1"), registry.resolve(" AB1 ").initialProgram());
        assertThrows(UnknownTransactionException.class, () -> registry.resolve("NOPE"));
        assertThrows(IllegalArgumentException.class, () -> registry.resolve("../X"));
    }

    @Test
    @DisplayName("重複TRANSIDとdisabled transactionを明示的に拒否する")
    void rejectsDuplicateAndDisabledTransactions() {
        CicsTransactionDefinition enabled = definition("AB1", true);
        CicsTransactionDefinition disabled = definition("AB1", false);

        assertThrows(IllegalArgumentException.class,
                () -> new CicsTransactionRegistry(List.of(enabled, disabled)));
        CicsTransactionRegistry registry = new CicsTransactionRegistry(List.of(disabled));
        assertThrows(DisabledTransactionException.class, () -> registry.resolve("AB1"));
    }

    @Test
    @DisplayName("task起動前にCOMMAREAとcontainerの各上限を独立して検査する")
    void validatesAllPayloadLimits() {
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("LIM1"), ProgramId.of("ENTRY1"), Duration.ofSeconds(5),
                2, 1, 3, 3, true);

        definition.validate(new CicsPayload(new byte[2], Map.of("ONE", new byte[3])));
        assertLimit("COMMAREA", () -> definition.validate(
                CicsPayload.ofCommarea(new byte[3])));
        assertLimit("container count", () -> definition.validate(
                new CicsPayload(new byte[0], Map.of("ONE", new byte[1], "TWO", new byte[1]))));
        assertLimit("single container", () -> definition.validate(
                new CicsPayload(new byte[0], Map.of("ONE", new byte[4]))));

        CicsTransactionDefinition total = new CicsTransactionDefinition(
                TransId.of("LIM2"), ProgramId.of("ENTRY1"), Duration.ofSeconds(5),
                0, 2, 3, 4, true);
        assertLimit("container total", () -> total.validate(
                new CicsPayload(new byte[0], Map.of("ONE", new byte[3], "TWO", new byte[2]))));
    }

    private static void assertLimit(String resource, Runnable action) {
        CicsInputLimitException failure = assertThrows(CicsInputLimitException.class, action::run);
        assertEquals(resource, failure.resource());
    }

    private static CicsTransactionDefinition definition(String transId, boolean enabled) {
        return new CicsTransactionDefinition(
                TransId.of(transId), ProgramId.of("ENTRY1"), Duration.ofSeconds(5),
                32_767, 16, 1_024, 4_096, enabled);
    }
}
