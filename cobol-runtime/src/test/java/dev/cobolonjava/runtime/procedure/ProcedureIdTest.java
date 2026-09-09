package dev.cobolonjava.runtime.procedure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class ProcedureIdTest {

    @Test
    void normalizesProgramAndSectionNames() {
        ProcedureId id = ProcedureId.section(" dash-pgm ", " read-rate ");

        assertEquals("DASH-PGM", id.programId().value());
        assertEquals(ProcedureKind.SECTION, id.kind());
        assertEquals("READ-RATE", id.name());
    }

    @Test
    void rejectsEmptyAndControlCharacterNames() {
        assertThrows(IllegalArgumentException.class, () -> ProcedureId.section("PGM", "  "));
        assertThrows(IllegalArgumentException.class, () -> ProcedureId.section("PGM", "READ\nRATE"));
    }
}
