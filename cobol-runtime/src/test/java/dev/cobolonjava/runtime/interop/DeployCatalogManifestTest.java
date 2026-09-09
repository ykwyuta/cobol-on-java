package dev.cobolonjava.runtime.interop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.procedure.ProcedureManifest;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 独立したJSON配備カタログの境界・改ざん検出試験。 */
@Tag("V1")
class DeployCatalogManifestTest {

    private static final ProgramSignature SIGNATURE = ProgramSignature.of("MANIFESTPGM", List.of(
            ProgramParameter.fixedReference("VALUE", 1, "one-byte-layout")));
    private static final ProcedureManifest PROCEDURES =
            ProcedureManifest.of("MANIFESTPGM", List.of());

    public static final class ManifestProgram implements CobolProgram {

        @Override
        public byte[] initialStorage() {
            return new byte[0];
        }

        @Override
        public void run(Storage storage, ProgramContext context, DataView[] arguments) {
            arguments[0].setBytes(new byte[] {42});
        }

        @Override
        public String name() {
            return "MANIFESTPGM";
        }

        @Override
        public ProgramSignature programSignature() {
            return SIGNATURE;
        }

        @Override
        public ProcedureManifest procedureManifest() {
            return PROCEDURES;
        }
    }

    @Test
    @DisplayName("JSONを往復した配備カタログから検証済みクラスを呼べる")
    void roundTripsAndBuildsVerifiedCatalog() {
        DeployCatalogManifest original = manifest(SIGNATURE);
        DeployCatalogManifest decoded = DeployCatalogManifest.fromJson(original.toJson());

        assertEquals(original, decoded);
        Storage argument = Storage.allocate(1);
        try (CobolSession session = CobolRuntime.builder(decoded.toProgramCatalog())
                .classLoader(getClass().getClassLoader()).build().openSession()) {
            session.call("MANIFESTPGM", argument.whole());
            assertEquals(new CatalogRevision("deploy-r1"), session.catalogRevision());
        }
        assertEquals(42, argument.array()[0]);
    }

    @Test
    @DisplayName("配備カタログとクラス内署名の食い違いは実行前に拒否する")
    void rejectsStaleSignatureBeforeExecution() {
        ProgramSignature stale = ProgramSignature.of("MANIFESTPGM", List.of(
                ProgramParameter.fixedReference("VALUE", 2, "two-byte-layout")));

        try (CobolSession session = CobolRuntime.builder(manifest(stale).toProgramCatalog())
                .classLoader(getClass().getClassLoader()).build().openSession()) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> session.call("MANIFESTPGM", Storage.allocate(2).whole()));
            assertTrue(failure.getMessage().contains("signatures disagree"));
        }
    }

    @Test
    @DisplayName("未知フィールド、重複キー、許可外パッケージを受理しない")
    void rejectsAmbiguousOrOutOfScopeCatalog() {
        String json = manifest(SIGNATURE).toJson();
        assertThrows(IllegalArgumentException.class, () -> DeployCatalogManifest.fromJson(
                json.replace("\"programs\":", "\"unknown\": 1, \"programs\":")));
        assertThrows(IllegalArgumentException.class, () -> DeployCatalogManifest.fromJson(
                json.replaceFirst("\\{", "{\"formatVersion\":1,")));
        assertThrows(IllegalArgumentException.class, () -> new DeployCatalogManifest(
                DeployCatalogManifest.CURRENT_FORMAT_VERSION, new CatalogRevision("deploy-r1"),
                "test-compiler", DeployCatalogManifest.CURRENT_RUNTIME_ABI_VERSION,
                "example.generated", manifest(SIGNATURE).programs()));
    }

    private static DeployCatalogManifest manifest(ProgramSignature signature) {
        GeneratedProgramArtifact artifact = new GeneratedProgramArtifact(
                ProgramId.of("MANIFESTPGM"), ManifestProgram.class.getName(), signature,
                PROCEDURES.procedureHash());
        return new DeployCatalogManifest(DeployCatalogManifest.CURRENT_FORMAT_VERSION,
                new CatalogRevision("deploy-r1"), "test-compiler",
                DeployCatalogManifest.CURRENT_RUNTIME_ABI_VERSION,
                DeployCatalogManifestTest.class.getPackageName(), List.of(artifact));
    }
}
