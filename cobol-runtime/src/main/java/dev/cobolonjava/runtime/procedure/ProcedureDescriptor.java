package dev.cobolonjava.runtime.procedure;

import java.util.Objects;

/** 生成物に含める手続き範囲と直接起動適格性。 */
public record ProcedureDescriptor(
        ProcedureId id,
        int firstParagraph,
        int lastParagraph,
        boolean declarative,
        String sourceFile,
        int sourceLine,
        boolean directInvocationEligible,
        String ineligibilityReason) {

    public ProcedureDescriptor {
        Objects.requireNonNull(id, "id");
        if (firstParagraph < 0 || lastParagraph < firstParagraph) {
            throw new IllegalArgumentException("invalid procedure paragraph range: "
                    + firstParagraph + ".." + lastParagraph);
        }
        if (sourceLine < 0) {
            throw new IllegalArgumentException("sourceLine must not be negative");
        }
        if (directInvocationEligible) {
            if (declarative || id.kind() != ProcedureKind.SECTION) {
                throw new IllegalArgumentException(
                        "only a normal SECTION can be directly invocable: " + id);
            }
            if (ineligibilityReason != null) {
                throw new IllegalArgumentException(
                        "an eligible procedure must not have an ineligibility reason");
            }
        } else if (ineligibilityReason == null || ineligibilityReason.isBlank()) {
            throw new IllegalArgumentException(
                    "an ineligible procedure must have an ineligibility reason");
        }
    }
}
