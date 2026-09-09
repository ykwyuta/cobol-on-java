package dev.cobolonjava.runtime.procedure;

import dev.cobolonjava.runtime.interop.ProgramId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** コンパイラ生成物とテスト側の手続き名を照合する機械可読manifest。 */
public record ProcedureManifest(
        ProgramId programId,
        List<ProcedureDescriptor> procedures,
        String procedureHash) {

    public ProcedureManifest {
        Objects.requireNonNull(programId, "programId");
        procedures = List.copyOf(procedures);
        Objects.requireNonNull(procedureHash, "procedureHash");
        Set<ProcedureId> ids = new HashSet<>();
        for (ProcedureDescriptor procedure : procedures) {
            Objects.requireNonNull(procedure, "procedure");
            if (!programId.equals(procedure.id().programId())) {
                throw new IllegalArgumentException("procedure belongs to another program: "
                        + procedure.id());
            }
            if (!ids.add(procedure.id())) {
                throw new IllegalArgumentException("duplicate procedure: " + procedure.id());
            }
        }
        String expected = hash(programId, procedures);
        if (!expected.equals(procedureHash)) {
            throw new IllegalArgumentException("procedureHash does not match manifest contents");
        }
    }

    public static ProcedureManifest of(String program, List<ProcedureDescriptor> procedures) {
        ProgramId id = ProgramId.of(program);
        List<ProcedureDescriptor> copy = List.copyOf(procedures);
        return new ProcedureManifest(id, copy, hash(id, copy));
    }

    public boolean contains(ProcedureId id) {
        return procedures.stream().anyMatch(procedure -> procedure.id().equals(id));
    }

    private static String hash(ProgramId programId, List<ProcedureDescriptor> procedures) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "procedure-manifest-v1\n");
            update(digest, programId.value());
            update(digest, "\n");
            for (ProcedureDescriptor procedure : procedures) {
                update(digest, procedure.id().kind().name());
                digest.update((byte) 0);
                update(digest, procedure.id().name());
                digest.update((byte) 0);
                update(digest, Integer.toString(procedure.firstParagraph()));
                digest.update((byte) ':');
                update(digest, Integer.toString(procedure.lastParagraph()));
                digest.update((byte) 0);
                digest.update((byte) (procedure.declarative() ? 1 : 0));
                digest.update((byte) (procedure.directInvocationEligible() ? 1 : 0));
                digest.update((byte) '\n');
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }
}
