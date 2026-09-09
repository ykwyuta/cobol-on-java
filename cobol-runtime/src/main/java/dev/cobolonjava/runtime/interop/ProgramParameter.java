package dev.cobolonjava.runtime.interop;

import java.util.Objects;

/** プログラム入口の引数1個についての固定された低レベルABI契約。 */
public record ProgramParameter(
        String name,
        int minimumBytes,
        int maximumBytes,
        Presence presence,
        PassingMode passingMode,
        Direction direction,
        String layoutHash) {

    public enum Presence { REQUIRED, OPTIONAL }

    public enum PassingMode { REFERENCE, CONTENT, VALUE }

    public enum Direction { IN, OUT, INOUT }

    public ProgramParameter {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("parameter name must not be blank");
        }
        if (minimumBytes < 0 || maximumBytes < minimumBytes) {
            throw new IllegalArgumentException("invalid parameter length: "
                    + minimumBytes + ".." + maximumBytes);
        }
        Objects.requireNonNull(presence, "presence");
        Objects.requireNonNull(passingMode, "passingMode");
        Objects.requireNonNull(direction, "direction");
        if (layoutHash == null || layoutHash.isBlank()) {
            throw new IllegalArgumentException("parameter layoutHash must not be blank");
        }
    }

    /** 必須・固定長・参照渡しのCOBOL引数。 */
    public static ProgramParameter fixedReference(
            String name, int bytes, String layoutHash) {
        return new ProgramParameter(name, bytes, bytes, Presence.REQUIRED,
                PassingMode.REFERENCE, Direction.INOUT, layoutHash);
    }
}
