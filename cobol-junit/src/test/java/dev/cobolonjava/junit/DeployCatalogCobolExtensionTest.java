package dev.cobolonjava.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.interop.CatalogRevision;
import dev.cobolonjava.runtime.interop.DeployCatalogManifest;
import dev.cobolonjava.runtime.interop.GeneratedProgramArtifact;
import dev.cobolonjava.runtime.interop.ProgramSignatureMismatchException;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** 配備カタログ経由で事前コンパイル済みCOBOLを試験する結合契約。 */
class DeployCatalogCobolExtensionTest {

    private static final String SOURCE = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. DEPLOYED.",
            "       DATA DIVISION.",
            "       LINKAGE SECTION.",
            "       01 LK-TEXT PIC X(3).",
            "       PROCEDURE DIVISION USING LK-TEXT.",
            "       MAIN-START.",
            "           GOBACK.",
            "       MUTATE SECTION.",
            "       MUTATE-P.",
            "           MOVE 'CAT' TO LK-TEXT.");

    private static final Deployed DEPLOYED = compile();

    @RegisterExtension
    final CobolExtension cobol = CobolExtension.builder()
            .deployCatalog(DEPLOYED.manifest(), DEPLOYED.loader())
            .build();

    @Test
    void invokesSectionFromDeployCatalogMetadata() {
        Storage argument = Storage.allocate(3);

        cobol.program("DEPLOYED").byReference(argument.whole()).invokeSection("MUTATE");

        assertEquals("CAT", cobol.codePage().decode(argument.array()));
    }

    @Test
    void retainsAbiValidationOnTheDeployCatalogPath() {
        assertThrows(ProgramSignatureMismatchException.class,
                () -> cobol.program("DEPLOYED")
                        .byReference(Storage.allocate(2).whole()).call());
    }

    private static Deployed compile() {
        CobolCompiler.Result result = CobolCompiler.standard().compile("DEPLOYED.cbl", SOURCE);
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        GeneratedLoader loader = new GeneratedLoader();
        loader.define(result.className(), result.classFile());
        GeneratedProgramArtifact artifact = new GeneratedProgramArtifact(
                result.programSignature().programId(), result.className(),
                result.programSignature(), result.procedureManifest().procedureHash());
        DeployCatalogManifest manifest = new DeployCatalogManifest(
                DeployCatalogManifest.CURRENT_FORMAT_VERSION, new CatalogRevision("deployed-r1"),
                "test-compiler", DeployCatalogManifest.CURRENT_RUNTIME_ABI_VERSION,
                "cobol.generated", List.of(artifact));
        return new Deployed(manifest, loader);
    }

    private record Deployed(DeployCatalogManifest manifest, ClassLoader loader) {
    }

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(DeployCatalogCobolExtensionTest.class.getClassLoader());
        }

        private Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
