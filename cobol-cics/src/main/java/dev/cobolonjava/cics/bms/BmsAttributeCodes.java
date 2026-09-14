package dev.cobolonjava.cics.bms;

import dev.cobolonjava.cics.bms.BmsModel.BasicAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Color;
import dev.cobolonjava.cics.bms.BmsModel.Highlight;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * 記号マップに置かれた 3270 属性 byte を読む (設計 79 §8.3)。
 *
 * <p>値は 3270 データストリームの公開仕様による。IBM 提供の {@code DFHBMSCA} の原文は参照しない。
 * byte の意味を持たない値を近い属性へ丸めると、保護 field が入力可能になりうるので断る
 * (暫定判断 P-117)。
 */
public final class BmsAttributeCodes {

    private static final int GRAPHIC = 0x40;
    private static final int PROTECTED = 0x20;
    private static final int NUMERIC = 0x10;
    private static final int INTENSITY = 0x0C;
    private static final int BRIGHT = 0x08;
    private static final int DARK = 0x0C;
    private static final int MDT = 0x01;

    private BmsAttributeCodes() {
    }

    /** 基本属性 byte の意味。 */
    public record Basic(Set<BasicAttribute> attributes, boolean modified) {
    }

    /**
     * 基本属性 byte を読む。
     *
     * <p>3270 の属性 byte は印字可能な文字として表すため {@code X'40'} の bit を持つ。
     * これを持たない値は属性として作られたものではないので断る。
     */
    public static Basic basic(int value) {
        int b = value & 0xFF;
        if ((b & GRAPHIC) == 0) {
            throw new IllegalArgumentException(
                    String.format("not a 3270 attribute byte: X'%02X'", b));
        }
        EnumSet<BasicAttribute> out = EnumSet.noneOf(BasicAttribute.class);
        boolean protect = (b & PROTECTED) != 0;
        boolean numeric = (b & NUMERIC) != 0;
        if (protect && numeric) {
            // 保護と数字を合わせたものが自動skipである
            out.add(BasicAttribute.ASKIP);
        } else if (protect) {
            out.add(BasicAttribute.PROT);
        } else {
            out.add(BasicAttribute.UNPROT);
            if (numeric) {
                out.add(BasicAttribute.NUM);
            }
        }
        switch (b & INTENSITY) {
            case BRIGHT -> out.add(BasicAttribute.BRT);
            case DARK -> out.add(BasicAttribute.DRK);
            default -> out.add(BasicAttribute.NORM);
        }
        return new Basic(out, (b & MDT) != 0);
    }

    /** 拡張色 byte。{@code X'00'} は「変えない」。 */
    public static Optional<Color> color(int value) {
        return switch (value & 0xFF) {
            case 0x00 -> Optional.empty();
            case 0xF1 -> Optional.of(Color.BLUE);
            case 0xF2 -> Optional.of(Color.RED);
            case 0xF3 -> Optional.of(Color.PINK);
            case 0xF4 -> Optional.of(Color.GREEN);
            case 0xF5 -> Optional.of(Color.TURQUOISE);
            case 0xF6 -> Optional.of(Color.YELLOW);
            case 0xF7 -> Optional.of(Color.NEUTRAL);
            default -> throw new IllegalArgumentException(
                    String.format("unsupported extended color: X'%02X'", value & 0xFF));
        };
    }

    /** 拡張強調 byte。{@code X'00'} は「変えない」。 */
    public static Optional<Highlight> highlight(int value) {
        return switch (value & 0xFF) {
            case 0x00 -> Optional.empty();
            case 0xF0 -> Optional.of(Highlight.OFF);
            case 0xF1 -> Optional.of(Highlight.BLINK);
            case 0xF2 -> Optional.of(Highlight.REVERSE);
            case 0xF4 -> Optional.of(Highlight.UNDERLINE);
            default -> throw new IllegalArgumentException(
                    String.format("unsupported extended highlight: X'%02X'", value & 0xFF));
        };
    }
}
