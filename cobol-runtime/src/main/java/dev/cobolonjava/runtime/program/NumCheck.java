package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.data.BinaryDecimal;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.item.NumericItem;
import dev.cobolonjava.runtime.storage.Storage;
import dev.cobolonjava.runtime.verb.ClassTest;
import java.util.Locale;

/** 数値項目を送り出す前の NUMCHECK 検証。 */
public final class NumCheck {
    private static final int ZON = 1;
    private static final int PAC = 2;
    private static final int BIN = 4;
    private static final int ABD = 8;

    private NumCheck() {
    }

    public static int options(String value) {
        int result = 0;
        for (String word : value.toUpperCase(Locale.ROOT).split(",")) {
            result = switch (word.trim()) {
                case "ZON" -> result | ZON;
                case "PAC" -> result | PAC;
                case "BIN" -> result | BIN;
                case "ABD" -> result | ABD;
                case "MSG" -> result & ~ABD;
                case "" -> result;
                default -> throw new IllegalArgumentException("unsupported NUMCHECK suboption: " + word);
            };
        }
        return (result & (ZON | PAC | BIN)) == 0 ? result | ZON | PAC | BIN : result;
    }

    public static Decimal read(NumericItem source, Storage storage, int offset,
                               ProgramContext context, CodePage page, int flags, int signs) {
        byte[] bytes = storage.view(offset, source.byteLength()).toByteArray();
        boolean valid = switch (source.usage()) {
            case DISPLAY -> (flags & ZON) == 0 || ClassTest.numeric(bytes, source, page, signs);
            case COMP_3 -> (flags & PAC) == 0 || ClassTest.numeric(bytes, source, page, signs);
            case COMP, COMP_5 -> (flags & BIN) == 0 || BinaryDecimal
                    .decode(bytes, source.picture().scale(), source.signPosition().isSigned())
                    .fitsInDigits(source.picture().digits(), source.picture().scale());
            default -> true;
        };
        if (!valid) {
            if ((flags & ABD) != 0) {
                context.setReturnCode(16);
                Ops.stopRun();
            }
            context.display(page.encode("NUMCHECK: invalid sending numeric item"), true, true);
        }
        return source.decode(bytes);
    }
}
