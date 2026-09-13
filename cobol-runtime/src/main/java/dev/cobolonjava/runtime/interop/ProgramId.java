package dev.cobolonjava.runtime.interop;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.util.Locale;
import java.util.Objects;

/** COBOL の呼び出し名を一意に扱うための正規化済み識別子。 */
public record ProgramId(String value) {

    public ProgramId {
        Objects.requireNonNull(value, "value");
        value = value.strip().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("program name must not be empty");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("program name must not contain control characters");
        }
    }

    /** Java 側の名前から識別子を作る。 */
    public static ProgramId of(String value) {
        return new ProgramId(value);
    }

    /** COBOL の記憶域にある名前を実行時コードページで復号して識別子を作る。 */
    public static ProgramId from(byte[] value, CodePage codePage) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(codePage, "codePage");
        return of(codePage.decode(value));
    }
}
