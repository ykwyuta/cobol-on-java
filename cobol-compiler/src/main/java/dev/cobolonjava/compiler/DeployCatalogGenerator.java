package dev.cobolonjava.compiler;

import dev.cobolonjava.runtime.interop.CatalogRevision;
import dev.cobolonjava.runtime.interop.DeployCatalogManifest;
import dev.cobolonjava.runtime.interop.GeneratedProgramArtifact;
import dev.cobolonjava.runtime.interop.ProgramId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** 一回のコンパイラ起動で生成したclass群から独立配備カタログを構築する。 */
final class DeployCatalogGenerator {

    private static final String GENERATED_PACKAGE = "cobol.generated";

    private DeployCatalogGenerator() {
    }

    static DeployCatalogManifest generate(List<CobolCompiler.Compiled> compiledPrograms) {
        List<CobolCompiler.Compiled> sorted = new ArrayList<>(compiledPrograms);
        sorted.sort(Comparator.comparing(program -> program.programSignature().programId().value()));
        List<GeneratedProgramArtifact> artifacts = new ArrayList<>(sorted.size());
        MessageDigest revision = sha256();
        update(revision, "deploy-catalog-v1\n");
        for (CobolCompiler.Compiled program : sorted) {
            if (program.programSignature() == null || program.procedureManifest() == null) {
                throw new IllegalArgumentException(
                        "compiled program has no embedded metadata: " + program.className());
            }
            ProgramId id = program.programSignature().programId();
            GeneratedProgramArtifact artifact = new GeneratedProgramArtifact(id,
                    program.className(), program.programSignature(),
                    program.procedureManifest().procedureHash());
            artifacts.add(artifact);
            update(revision, id.value());
            update(revision, "\u0000");
            update(revision, program.className());
            update(revision, "\u0000");
            update(revision, program.programSignature().layoutHash());
            update(revision, "\u0000");
            update(revision, program.procedureManifest().procedureHash());
            update(revision, "\u0000");
            revision.update(program.classFile());
            update(revision, "\n");
        }
        return new DeployCatalogManifest(DeployCatalogManifest.CURRENT_FORMAT_VERSION,
                new CatalogRevision("sha256:" + HexFormat.of().formatHex(revision.digest())),
                compilerVersion(), DeployCatalogManifest.CURRENT_RUNTIME_ABI_VERSION,
                GENERATED_PACKAGE, artifacts);
    }

    private static String compilerVersion() {
        String version = CobolCompiler.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }
}
