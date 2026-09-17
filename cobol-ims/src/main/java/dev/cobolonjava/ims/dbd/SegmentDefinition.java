package dev.cobolonjava.ims.dbd;

import java.util.List;

/**
 * DBD の {@code SEGM} 文 1 つと、その下の {@code FIELD} 文。
 *
 * @param name       セグメント名 (大文字)
 * @param parent     親のセグメント名。根なら {@code null}
 * @param level      階層の段 (根が 1)
 * @param maxBytes   長さ。可変長なら最大の長さ
 * @param minBytes   可変長の最小の長さ。固定長なら 0
 * @param insertRule {@code RULES=} の挿入位置
 * @param fields     書いた順のフィールド
 */
public record SegmentDefinition(String name, String parent, int level, int maxBytes, int minBytes,
                                InsertRule insertRule, List<FieldDefinition> fields) {

    public SegmentDefinition {
        fields = List.copyOf(fields);
    }

    public boolean root() {
        return parent == null;
    }

    public boolean variableLength() {
        return minBytes > 0;
    }

    /** 順序フィールド。無ければ {@code null}。 */
    public FieldDefinition sequenceField() {
        for (FieldDefinition field : fields) {
            if (field.sequence()) {
                return field;
            }
        }
        return null;
    }

    /** 名前でフィールドを引く。無ければ {@code null}。 */
    public FieldDefinition field(String fieldName) {
        for (FieldDefinition field : fields) {
            if (field.name().equals(fieldName)) {
                return field;
            }
        }
        return null;
    }
}
