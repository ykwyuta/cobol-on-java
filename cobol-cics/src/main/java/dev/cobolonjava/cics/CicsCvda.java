package dev.cobolonjava.cics;

import java.util.Locale;
import java.util.Objects;

/**
 * CICS-value data area (CVDA) の値のうち、公開文書で数値を確かめたもの (暫定判断 P-130)。
 *
 * <p>値は CICS TS for z/OS 5.6「CVDAs and numeric values in numeric sequence」の表による。
 * TXSeries の表は同じ名前に別の数 (UCTRAN 450 等) を載せているので、製品を取り違えない。
 * ここに無い名前は推測せず断る。
 */
public final class CicsCvda {

    /** 端末の入力をすべて大文字にする。 */
    public static final int UCTRAN = 451;
    /** 端末の入力を大文字にしない。 */
    public static final int NOUCTRAN = 452;
    /** transaction ID だけを大文字にする。 */
    public static final int TRANIDONLY = 460;

    private CicsCvda() {
    }

    /** {@code DFHVALUE(name)} の値。 */
    public static int forName(String name) {
        String normalized = Objects.requireNonNull(name, "name").strip().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "UCTRAN" -> UCTRAN;
            case "NOUCTRAN" -> NOUCTRAN;
            case "TRANIDONLY" -> TRANIDONLY;
            default -> throw new IllegalArgumentException("unsupported CVDA name: " + normalized);
        };
    }

    /** UCTRANST に置ける値か。 */
    public static boolean isUppercaseTranslation(int value) {
        return value == UCTRAN || value == NOUCTRAN || value == TRANIDONLY;
    }
}
