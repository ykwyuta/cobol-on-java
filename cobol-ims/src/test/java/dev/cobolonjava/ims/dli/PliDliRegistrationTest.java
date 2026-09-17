package dev.cobolonjava.ims.dli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.ims.psb.ProgramSpecification;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.ProgramId;
import java.util.List;
import org.junit.jupiter.api.Test;

class PliDliRegistrationTest {

    @Test
    void regionRegistersBothLanguageEnvironmentEntryNames() {
        ImsRegion region = new ImsRegion(
                new ProgramSpecification("EMPTY", "PLI", false, List.of()),
                List.of(), CodePages.DEFAULT);

        ProgramCatalog catalog = region.register(ProgramCatalog.builder()).build();

        assertTrue(catalog.definitions().containsKey(ProgramId.of("CBLTDLI")));
        assertTrue(catalog.definitions().containsKey(ProgramId.of("PLITDLI")));
    }
}
