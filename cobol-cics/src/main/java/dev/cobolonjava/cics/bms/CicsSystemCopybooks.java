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
            case "DFHBMSCA" -> Optional.of(attributes());
            default -> Optional.empty();
        };
    }

    /**
     * {@code DFHBMSCA} の名前と値 (暫定判断 P-129)。
     *
     * <p>名前と意味は CICS の公開文書「BMS constants」の表による。値はその意味を 3270 データストリームの
     * 属性 byte (保護 X'20'、数字 X'10'、明るさ X'0C'、MDT X'01' と、印字可能にする変換表) で表したもので、
     * {@link BmsAttributeCodes} が読み戻す。印字制御 ({@code DFHBMPEM} / {@code DFHBMPNL}) は値の出どころを
     * 持たないので置かない。
     */
    static final java.util.List<java.util.Map.Entry<String, Integer>> ATTRIBUTE_CONSTANTS = java.util.List.of(
            java.util.Map.entry("DFHBMUNP", 0x40),
            java.util.Map.entry("DFHBMUNN", 0x50),
            java.util.Map.entry("DFHBMPRO", 0x60),
            java.util.Map.entry("DFHBMASK", 0xF0),
            java.util.Map.entry("DFHBMBRY", 0xC8),
            java.util.Map.entry("DFHBMDAR", 0x4C),
            java.util.Map.entry("DFHBMFSE", 0xC1),
            java.util.Map.entry("DFHBMPRF", 0x61),
            java.util.Map.entry("DFHBMASF", 0xF1),
            java.util.Map.entry("DFHBMASB", 0xF8));

    /** 拡張色と拡張強調の名前と値。{@code X'00'} は「既定のまま」である。 */
    static final java.util.List<java.util.Map.Entry<String, Integer>> EXTENDED_CONSTANTS = java.util.List.of(
            java.util.Map.entry("DFHDFCOL", 0x00),
            java.util.Map.entry("DFHBLUE", 0xF1),
            java.util.Map.entry("DFHRED", 0xF2),
            java.util.Map.entry("DFHPINK", 0xF3),
            java.util.Map.entry("DFHGREEN", 0xF4),
            java.util.Map.entry("DFHTURQ", 0xF5),
            java.util.Map.entry("DFHYELLO", 0xF6),
            java.util.Map.entry("DFHNEUTR", 0xF7),
            java.util.Map.entry("DFHDFHI", 0x00),
            java.util.Map.entry("DFHBLINK", 0xF1),
            java.util.Map.entry("DFHREVRS", 0xF2),
            java.util.Map.entry("DFHUNDLN", 0xF4));

    private static String attributes() {
        StringBuilder out = new StringBuilder("       01  DFHBMSCA.\n");
        for (java.util.List<java.util.Map.Entry<String, Integer>> group
                : java.util.List.of(ATTRIBUTE_CONSTANTS, EXTENDED_CONSTANTS)) {
            for (java.util.Map.Entry<String, Integer> constant : group) {
                out.append(String.format("           02  %-8s PIC X VALUE X'%02X'.%n",
                        constant.getKey(), constant.getValue()));
            }
        }
        return out.toString();
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
