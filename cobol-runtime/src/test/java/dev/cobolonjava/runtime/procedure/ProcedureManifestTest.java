package dev.cobolonjava.runtime.procedure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class ProcedureManifestTest {

    @Test
    void computesAStableContentHash() {
        List<ProcedureDescriptor> procedures = List.of(
                section("PGM", "READ-RATE"),
                new ProcedureDescriptor(new ProcedureId(
                        dev.cobolonjava.runtime.interop.ProgramId.of("PGM"),
                        ProcedureKind.PARAGRAPH, "READ-P"), 1, 1, false,
                        "PGM.cbl", 10, false,
                        "direct invocation is supported only for SECTIONs"));

        ProcedureManifest first = ProcedureManifest.of("PGM", procedures);
        ProcedureManifest second = ProcedureManifest.of("pgm", procedures);

        assertEquals(first.procedureHash(), second.procedureHash());
        assertEquals(64, first.procedureHash().length());
        assertTrue(first.contains(ProcedureId.section("PGM", "READ-RATE")));
        assertNotEquals(first.procedureHash(),
                ProcedureManifest.of("PGM", procedures.reversed()).procedureHash());
    }

    @Test
    void rejectsForeignDuplicateAndForgedEntries() {
        ProcedureDescriptor section = section("PGM", "S1");
        assertThrows(IllegalArgumentException.class,
                () -> ProcedureManifest.of("OTHER", List.of(section)));
        assertThrows(IllegalArgumentException.class,
                () -> ProcedureManifest.of("PGM", List.of(section, section)));
        ProcedureManifest valid = ProcedureManifest.of("PGM", List.of(section));
        assertThrows(IllegalArgumentException.class, () -> new ProcedureManifest(
                valid.programId(), valid.procedures(), "0".repeat(64)));
    }

    private static ProcedureDescriptor section(String program, String section) {
        return new ProcedureDescriptor(ProcedureId.section(program, section),
                0, 0, false, "PGM.cbl", 1, true, null);
    }
}
