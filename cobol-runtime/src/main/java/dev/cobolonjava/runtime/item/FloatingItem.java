package dev.cobolonjava.runtime.item;

import dev.cobolonjava.runtime.hfp.HexFloat;
import dev.cobolonjava.runtime.storage.DataView;
import java.math.BigDecimal;

/**
 * 浮動小数点項目 ({@code USAGE COMPUTATIONAL-1} / {@code COMPUTATIONAL-2}) の記述子
 * (要件 FR-032)。
 *
 * <p>{@link NumericItem} と分けているのは、<b>浮動小数点項目が PICTURE を持たない</b>ためである。
 * 桁数も小数位置もなく、記憶域の幅だけで表現が決まる。PICTURE を前提とした記述子に
 * 無理に載せると、桁数のない項目のために空の PICTURE を作ることになり、意味論が濁る。
 *
 * <p>値は {@link BigDecimal} でやり取りする。HFP の値はすべて 2 進の有限小数であるため
 * 厳密に表現でき、{@code double} を経由すると長形式の 56 ビット小数部を失う。
 */
public final class FloatingItem {

    private final Usage usage;

    private FloatingItem(Usage usage) {
        if (!usage.isFloatingPoint()) {
            throw new IllegalArgumentException("not a floating-point usage: " + usage);
        }
        this.usage = usage;
    }

    /** 短形式 ({@code COMP-1}) の項目。 */
    public static FloatingItem comp1() {
        return new FloatingItem(Usage.COMP_1);
    }

    /** 長形式 ({@code COMP-2}) の項目。 */
    public static FloatingItem comp2() {
        return new FloatingItem(Usage.COMP_2);
    }

    public Usage usage() {
        return usage;
    }

    public int byteLength() {
        return usage == Usage.COMP_1 ? HexFloat.SHORT_BYTES : HexFloat.LONG_BYTES;
    }

    public byte[] encode(BigDecimal value) {
        return usage == Usage.COMP_1 ? HexFloat.encodeShort(value) : HexFloat.encodeLong(value);
    }

    public BigDecimal decode(byte[] bytes) {
        return usage == Usage.COMP_1 ? HexFloat.decodeShort(bytes) : HexFloat.decodeLong(bytes);
    }

    public void store(DataView view, BigDecimal value) {
        view.setBytes(encode(value));
    }

    public BigDecimal load(DataView view) {
        return decode(view.toByteArray());
    }

    @Override
    public String toString() {
        return "FloatingItem[" + usage + " " + byteLength() + " bytes]";
    }
}
