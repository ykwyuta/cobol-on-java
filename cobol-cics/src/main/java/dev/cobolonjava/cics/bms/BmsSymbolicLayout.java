package dev.cobolonjava.cics.bms;

import dev.cobolonjava.cics.bms.BmsModel.ExtendedAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Field;
import dev.cobolonjava.cics.bms.BmsModel.Mapset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 記号マップの byte 位置 (設計 79 §8.3)。
 *
 * <p>{@link BmsSymbolicMapWriter} が写し句として書く形と同じ規則から計算する。写し句の文字列を
 * 実行時に解析しない。入力側 (I) と出力側 (O) は同じ領域を重ねているので、1 つの位置表で
 * 両方を読める。
 *
 * <pre>
 * [TIOA 接頭 12] { L(2) F/A(1) 拡張属性(n) データ(LENGTH) } ...
 * </pre>
 */
public final class BmsSymbolicLayout {

    /** TIOAPFX=YES で先頭に置かれる領域の長さ。 */
    private static final int TIOA_PREFIX_LENGTH = 12;

    /**
     * 名前つき field の 1 回分の位置。
     *
     * @param occurrence 1 始まり。OCCURS の無い field は 1
     */
    public record Slot(Field field, String name, int occurrence, int lengthOffset,
                       int flagOffset, int attributeOffset, int dataOffset, int dataLength) {
    }

    private final List<ExtendedAttribute> attributeOrder;
    private final List<Slot> slots;
    private final int length;

    private BmsSymbolicLayout(List<ExtendedAttribute> attributeOrder, List<Slot> slots, int length) {
        this.attributeOrder = List.copyOf(attributeOrder);
        this.slots = List.copyOf(slots);
        this.length = length;
    }

    public static BmsSymbolicLayout of(Mapset mapset, BmsModel.Map map) {
        Objects.requireNonNull(mapset, "mapset");
        Objects.requireNonNull(map, "map");
        // EnumSet の反復順は宣言順であり、写し句の属性 byte の並びと同じである
        List<ExtendedAttribute> order = new ArrayList<>(map.dataAttributes());
        int position = mapset.tioaPrefix() ? TIOA_PREFIX_LENGTH : 0;
        List<Slot> slots = new ArrayList<>();
        for (Field field : map.namedFields()) {
            for (int occurrence = 1; occurrence <= field.occurs(); occurrence++) {
                int attributes = position + 3;
                int data = attributes + order.size();
                slots.add(new Slot(field, field.name().orElseThrow(), occurrence,
                        position, position + 2, attributes, data, field.length()));
                position = data + field.length();
            }
        }
        return new BmsSymbolicLayout(order, slots, position);
    }

    /** 記号マップ全体の長さ。FROM / INTO の項目はこの長さでなければならない。 */
    public int length() {
        return length;
    }

    public List<Slot> slots() {
        return slots;
    }

    /** 拡張属性 byte の並び。 */
    public List<ExtendedAttribute> attributeOrder() {
        return attributeOrder;
    }

    /** 拡張属性 byte の位置。その属性を記号マップに持たなければ空。 */
    public Optional<Integer> attributeOffset(Slot slot, ExtendedAttribute attribute) {
        int index = attributeOrder.indexOf(attribute);
        return index < 0 ? Optional.empty() : Optional.of(slot.attributeOffset() + index);
    }
}
