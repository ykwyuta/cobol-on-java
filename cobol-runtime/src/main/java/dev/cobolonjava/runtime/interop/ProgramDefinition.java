package dev.cobolonjava.runtime.interop;

import java.util.Objects;

/** 明示登録された一つの呼び先。 */
public record ProgramDefinition(
        ProgramId id,
        ProgramKind kind,
        ProgramSignature signature,
        ProgramFactory factory) {

    public ProgramDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(factory, "factory");
        if (signature != null && !id.equals(signature.programId())) {
            throw new IllegalArgumentException("signature belongs to another program: "
                    + signature.programId().value());
        }
    }

    /** 署名をまだ持たない既存生成物向けの互換入口。 */
    public ProgramDefinition(ProgramId id, ProgramKind kind, ProgramFactory factory) {
        this(id, kind, null, factory);
    }
}
