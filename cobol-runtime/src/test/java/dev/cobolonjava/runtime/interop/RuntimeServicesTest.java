package dev.cobolonjava.runtime.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.program.ProgramControlTransfer;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class RuntimeServicesTest {

    @Test
    @DisplayName("task固有serviceを型で生成programへ渡す")
    void exposesTypedSessionServiceToProgram() {
        Marker marker = new Marker("task-1");
        RuntimeServices services = RuntimeServices.builder()
                .service(Marker.class, marker)
                .build();
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("SERVICE", () -> program((context, arguments) ->
                        assertSame(marker, context.service(Marker.class))))
                .build();

        try (CobolSession session = CobolRuntime.builder(catalog).build().openSession(services)) {
            session.runMain("SERVICE");
        }
        assertThrows(MissingRuntimeServiceException.class,
                () -> RuntimeServices.EMPTY.require(Marker.class));
        assertThrows(IllegalArgumentException.class,
                () -> RuntimeServices.builder().service(Marker.class, marker)
                        .service(Marker.class, marker));
    }

    @Test
    @DisplayName("正常な非局所制御移送はsessionをfailedにせずframeを片付ける")
    void keepsSessionUsableAfterControlTransfer() {
        int[] calls = {0};
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("TRANSFER", () -> program((context, arguments) -> {
                    if (calls[0]++ == 0) {
                        throw new TestTransfer();
                    }
                })).build();

        try (CobolSession session = CobolRuntime.builder(catalog).build().openSession()) {
            assertThrows(TestTransfer.class, () -> session.runMain("TRANSFER"));
            assertEquals(false, session.failed());
            session.runMain("TRANSFER");
            assertEquals(2, calls[0]);
        }
    }

    private static CobolProgram program(ProgramBody body) {
        return new CobolProgram() {
            @Override
            public byte[] initialStorage() {
                return new byte[0];
            }

            @Override
            public void run(Storage storage, ProgramContext context, DataView[] arguments) {
                body.run(context, arguments);
            }
        };
    }

    private record Marker(String value) {
    }

    @FunctionalInterface
    private interface ProgramBody {
        void run(ProgramContext context, DataView[] arguments);
    }

    private static final class TestTransfer extends ProgramControlTransfer {
        private TestTransfer() {
            super("test transfer");
        }
    }
}
