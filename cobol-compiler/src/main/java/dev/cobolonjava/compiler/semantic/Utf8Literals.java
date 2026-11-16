package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.data.Utf8;

/** UTF-8 の受取領域から決まる定数のバイト列。 */
public final class Utf8Literals {
    private Utf8Literals() {
    }

    public static byte[] image(LiteralValue literal, int size, int characters, CodePage page,
                               char quote) {
        if (literal == null) return Utf8.fit(new byte[0], size, characters, false);
        if (literal instanceof LiteralValue.Figure figure) {
            if (figure.constant() == LiteralValue.FigurativeConstant.HIGH_VALUE) {
                return Utf8.high(size, characters);
            }
            byte value = switch (figure.constant()) {
                case ZERO -> '0';
                case SPACE -> ' ';
                case QUOTE -> (byte) quote;
                case LOW_VALUE, NULL -> 0;
                case HIGH_VALUE -> throw new IllegalStateException();
            };
            return Utf8.fit(new byte[] {value}, size, characters, true);
        }
        if (literal instanceof LiteralValue.Repeated repeated) {
            return Utf8.fit(Utf8.fromDisplay(repeated.bytes(page), page), size, characters, true);
        }
        byte[] source = switch (literal) {
            case LiteralValue.Text text -> text.utf8() ? text.bytes(page)
                    : Utf8.fromDisplay(text.bytes(page), page);
            case LiteralValue.National national -> Utf8.fromNational(national.bytes());
            case LiteralValue.Dbcs dbcs -> Utf8.fromNational(
                    dev.cobolonjava.runtime.data.Dbcs.toNational(dbcs.bytes(), page));
            case LiteralValue.Number number -> Utf8.fromDisplay(page.encode(number.source()), page);
            default -> throw new IllegalArgumentException("unsupported UTF-8 source: " + literal);
        };
        return Utf8.fit(source, size, characters, false);
    }
}
