package dev.cobolonjava.runtime.procedure;

import dev.cobolonjava.runtime.interop.ProgramId;
import java.util.Locale;
import java.util.Objects;

/** 生成メソッド名や段落番号を公開しない、安定した手続き識別子。 */
public record ProcedureId(ProgramId programId, ProcedureKind kind, String name) {

    public ProcedureId {
        Objects.requireNonNull(programId, "programId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        name = name.strip().toUpperCase(Locale.ROOT);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("procedure name must not be empty");
        }
        if (name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("procedure name must not contain control characters");
        }
    }

    public static ProcedureId section(String program, String section) {
        return new ProcedureId(ProgramId.of(program), ProcedureKind.SECTION, section);
    }
}
