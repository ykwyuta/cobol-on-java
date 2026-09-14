package dev.cobolonjava.cics.bms;

import java.util.Arrays;
import java.util.Optional;

/**
 * 端末の注意識別 (AID)。EIBAID に入る 1 byte である (要件 FR-161, FR-167)。
 *
 * <p>値は 3270 データストリームの公開仕様にある AID byte である。IBM 提供の写し句
 * {@code DFHAID} の原文は参照しない。byte 値は code page に依らないので、COBOL へは
 * 16 進定数として見せる。文字定数で書くと、実行時の code page によって別の byte になる。
 */
public enum BmsAid {
    NULL("DFHNULL", 0x00),
    ENTER("DFHENTER", 0x7D),
    CLEAR("DFHCLEAR", 0x6D),
    CLEAR_PARTITION("DFHCLRP", 0x6A),
    PEN("DFHPEN", 0x7E),
    OPERATOR_ID("DFHOPID", 0xE6),
    MAGNETIC_STRIPE("DFHMSRE", 0xE7),
    STRUCTURED_FIELD("DFHSTRF", 0x88),
    TRIGGER("DFHTRIG", 0x7F),
    PA1("DFHPA1", 0x6C),
    PA2("DFHPA2", 0x6E),
    PA3("DFHPA3", 0x6B),
    PF1("DFHPF1", 0xF1),
    PF2("DFHPF2", 0xF2),
    PF3("DFHPF3", 0xF3),
    PF4("DFHPF4", 0xF4),
    PF5("DFHPF5", 0xF5),
    PF6("DFHPF6", 0xF6),
    PF7("DFHPF7", 0xF7),
    PF8("DFHPF8", 0xF8),
    PF9("DFHPF9", 0xF9),
    PF10("DFHPF10", 0x7A),
    PF11("DFHPF11", 0x7B),
    PF12("DFHPF12", 0x7C),
    PF13("DFHPF13", 0xC1),
    PF14("DFHPF14", 0xC2),
    PF15("DFHPF15", 0xC3),
    PF16("DFHPF16", 0xC4),
    PF17("DFHPF17", 0xC5),
    PF18("DFHPF18", 0xC6),
    PF19("DFHPF19", 0xC7),
    PF20("DFHPF20", 0xC8),
    PF21("DFHPF21", 0xC9),
    PF22("DFHPF22", 0x4A),
    PF23("DFHPF23", 0x4B),
    PF24("DFHPF24", 0x4C);

    private final String cobolName;
    private final int value;

    BmsAid(String cobolName, int value) {
        this.cobolName = cobolName;
        this.value = value;
    }

    /** 写し句 {@code DFHAID} に出る項目名。 */
    public String cobolName() {
        return cobolName;
    }

    /** EIBAID に入る byte (0〜255)。 */
    public int value() {
        return value;
    }

    public static Optional<BmsAid> ofValue(int value) {
        return Arrays.stream(values()).filter(aid -> aid.value == value).findFirst();
    }
}
