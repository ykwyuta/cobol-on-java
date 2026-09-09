package dev.cobolonjava.runtime.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.storage.Storage;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class ProgramSignatureTest {

    @Test
    void validatesCountAndFixedByteLengths() {
        ProgramSignature signature = ProgramSignature.of("SUB",
                List.of(ProgramParameter.fixedReference("REQUEST", 3, "request-v1"),
                        ProgramParameter.fixedReference("RESPONSE", 5, "response-v1")));

        signature.validate(new dev.cobolonjava.runtime.storage.DataView[] {
                Storage.allocate(3).whole(), Storage.allocate(5).whole()});

        ProgramSignatureMismatchException missing = assertThrows(
                ProgramSignatureMismatchException.class,
                () -> signature.validate(new dev.cobolonjava.runtime.storage.DataView[] {
                        Storage.allocate(3).whole()}));
        assertEquals(ProgramId.of("SUB"), missing.programId());
        assertTrue(missing.getMessage().contains("expected 2 argument"));

        ProgramSignatureMismatchException wrongLength = assertThrows(
                ProgramSignatureMismatchException.class,
                () -> signature.validate(new dev.cobolonjava.runtime.storage.DataView[] {
                        Storage.allocate(4).whole(), Storage.allocate(5).whole()}));
        assertTrue(wrongLength.getMessage().contains("REQUEST"));
        assertTrue(wrongLength.getMessage().contains("expected 3 byte"));
    }

    @Test
    void permitsOnlyTrailingOptionalParameters() {
        ProgramParameter optional = new ProgramParameter("OPTIONAL", 1, 4,
                ProgramParameter.Presence.OPTIONAL,
                ProgramParameter.PassingMode.REFERENCE,
                ProgramParameter.Direction.INOUT, "optional-v1");
        ProgramSignature signature = ProgramSignature.of("SUB", List.of(optional));

        signature.validate(new dev.cobolonjava.runtime.storage.DataView[0]);
        signature.validate(new dev.cobolonjava.runtime.storage.DataView[] {
                Storage.allocate(4).whole()});

        ProgramParameter required = ProgramParameter.fixedReference("REQUIRED", 1, "required-v1");
        assertThrows(IllegalArgumentException.class,
                () -> ProgramSignature.of("SUB", List.of(optional, required)));
    }

    @Test
    void doesNotPretendThatDataViewImplementsByValue() {
        ProgramParameter byValue = new ProgramParameter("NUMBER", 4, 4,
                ProgramParameter.Presence.REQUIRED,
                ProgramParameter.PassingMode.VALUE,
                ProgramParameter.Direction.IN, "binary-int-v1");
        ProgramSignature signature = ProgramSignature.of("SUB", List.of(byValue));

        ProgramSignatureMismatchException failure = assertThrows(
                ProgramSignatureMismatchException.class,
                () -> signature.validate(new dev.cobolonjava.runtime.storage.DataView[] {
                        Storage.allocate(4).whole()}));

        assertTrue(failure.getMessage().contains("BY VALUE"));
    }
}
