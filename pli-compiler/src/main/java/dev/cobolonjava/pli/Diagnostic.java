package dev.cobolonjava.pli;

import java.util.Objects;

/** PL/I の翻訳診断。 */
public record Diagnostic(Severity severity, String fileName, int line, int column,
                         String message) {

    public enum Severity { ERROR, WARNING }

    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(message, "message");
    }

    @Override
    public String toString() {
        return fileName + ":" + line + ":" + column + ": "
                + severity.name().toLowerCase() + ": " + message;
    }
}
