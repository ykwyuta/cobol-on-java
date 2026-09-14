package dev.cobolonjava.cics.bms;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * CICS が提供する写し句のうち、公開仕様の値から作れるもの。
 *
 * <p>IBM 製品の原文は参照しない。ここにあるのは値の表から組み立てた写し句であり、
 * 項目の並びや FILLER の有無は実物と突き合わせていない (暫定判断 P-112)。
 * まだ作れないものは空を返し、COPY は「見つからない」と断る。
 */
public final class CicsSystemCopybooks {

    private CicsSystemCopybooks() {
    }

    /** 名前に対応する写し句の固定形式テキスト。 */
    public static Optional<String> text(String name) {
        Objects.requireNonNull(name, "name");
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "DFHAID" -> Optional.of(aid());
            default -> Optional.empty();
        };
    }

    private static String aid() {
        StringBuilder out = new StringBuilder("       01  DFHAID.\n");
        for (BmsAid aid : BmsAid.values()) {
            out.append(String.format("           02  %-8s PIC X VALUE X'%02X'.%n",
                    aid.cobolName(), aid.value()));
        }
        return out.toString();
    }
}
