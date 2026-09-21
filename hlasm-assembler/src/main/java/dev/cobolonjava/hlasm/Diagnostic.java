package dev.cobolonjava.hlasm;

import java.util.Objects;

/** HLASM の組み立て診断。位置を持つのは、同じ原因で止まった行を引けるようにするためである。 */
public record Diagnostic(Severity severity, String fileName, int line, String message) {

    public enum Severity { ERROR, WARNING }

    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(message, "message");
    }

    public static Diagnostic error(String fileName, int line, String message) {
        return new Diagnostic(Severity.ERROR, fileName, line, message);
    }

    @Override
    public String toString() {
        return fileName + ":" + line + ": " + severity.name().toLowerCase() + ": " + message;
    }
}
