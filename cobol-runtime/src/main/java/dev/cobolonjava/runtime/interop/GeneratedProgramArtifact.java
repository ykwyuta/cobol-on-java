package dev.cobolonjava.runtime.interop;

import dev.cobolonjava.runtime.procedure.ProcedureManifest;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramNotFoundException;
import java.lang.reflect.Modifier;
import java.util.Objects;

/** 配備カタログに列挙する、一つの生成COBOLクラスの不変な識別情報。 */
public record GeneratedProgramArtifact(
        ProgramId programId,
        String className,
        ProgramSignature signature,
        String procedureHash) {

    public GeneratedProgramArtifact {
        Objects.requireNonNull(programId, "programId");
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("generated class name must not be blank");
        }
        Objects.requireNonNull(signature, "signature");
        if (!programId.equals(signature.programId())) {
            throw new IllegalArgumentException("signature belongs to another program: "
                    + signature.programId().value());
        }
        if (procedureHash == null || !procedureHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("procedureHash must be a lowercase SHA-256 value");
        }
    }

    ProgramDefinition definition(String allowedPackage) {
        requireAllowedPackage(allowedPackage);
        return new ProgramDefinition(programId, ProgramKind.COBOL, signature,
                loader -> instantiate(loader, allowedPackage));
    }

    private CobolProgram instantiate(ClassLoader loader, String allowedPackage) {
        requireAllowedPackage(allowedPackage);
        try {
            Class<?> type = Class.forName(className, false, loader);
            if (!CobolProgram.class.isAssignableFrom(type)
                    || type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
                throw new IllegalStateException("catalog class is not a concrete CobolProgram: "
                        + className);
            }
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            ProgramId embeddedId = ProgramId.of(program.name());
            if (!programId.equals(embeddedId)) {
                throw new IllegalStateException("catalog program id and generated class disagree: "
                        + programId.value() + " != " + embeddedId.value());
            }
            if (!signature.equals(program.programSignature())) {
                throw new IllegalStateException(
                        "catalog and generated class signatures disagree: " + programId.value());
            }
            ProcedureManifest procedures = program.procedureManifest();
            if (procedures == null || !programId.equals(procedures.programId())
                    || !procedureHash.equals(procedures.procedureHash())) {
                throw new IllegalStateException(
                        "catalog and generated class procedure manifests disagree: "
                                + programId.value());
            }
            return program;
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new ProgramNotFoundException(programId.value(), e);
        }
    }

    private void requireAllowedPackage(String allowedPackage) {
        Objects.requireNonNull(allowedPackage, "allowedPackage");
        String prefix = allowedPackage.endsWith(".") ? allowedPackage : allowedPackage + ".";
        if (!className.startsWith(prefix) || className.length() == prefix.length()) {
            throw new IllegalArgumentException("generated class is outside the allowed package: "
                    + className);
        }
    }
}
