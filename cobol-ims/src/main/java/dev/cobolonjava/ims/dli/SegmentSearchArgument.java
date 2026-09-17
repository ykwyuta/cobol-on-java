package dev.cobolonjava.ims.dli;

import dev.cobolonjava.ims.dbd.FieldDefinition;
import dev.cobolonjava.ims.dbd.SegmentDefinition;
import java.util.Arrays;
import java.util.List;

/**
 * 解析した SSA 1 つ。
 *
 * <p>修飾は「AND で結んだ組」を OR で並べた形で持つ。DL/I では AND ({@code *} / {@code &}) が
 * OR ({@code +} / {@code |}) より強く結ぶ。
 *
 * @param segment         名指したセグメント型
 * @param alternatives    OR で並べた、AND の組。無限定なら空
 * @param commandCodes    コマンドコード (C / D / F / L / N / P)。null の {@code -} と {@code Q} は含めない
 * @param concatenatedKey {@code *C} の連結キー。{@code *C} でなければ {@code null}
 */
record SegmentSearchArgument(SegmentDefinition segment, List<List<Qualification>> alternatives,
                             String commandCodes, byte[] concatenatedKey) {

    SegmentSearchArgument {
        alternatives = alternatives.stream().map(List::copyOf).toList();
        concatenatedKey = concatenatedKey == null ? null : concatenatedKey.clone();
    }

    SegmentSearchArgument(SegmentDefinition segment, List<List<Qualification>> alternatives) {
        this(segment, alternatives, "", null);
    }

    /** 修飾しているか。{@code *C} の連結キーも修飾である。 */
    boolean qualified() {
        return !alternatives.isEmpty() || concatenatedKey != null;
    }

    boolean has(char commandCode) {
        return commandCodes.indexOf(commandCode) >= 0;
    }

    /** セグメントの値が修飾を満たすか。無限定なら常に真。 */
    boolean matches(byte[] data) {
        if (alternatives.isEmpty()) {
            return true;
        }
        for (List<Qualification> group : alternatives) {
            if (group.stream().allMatch(qualification -> qualification.matches(data))) {
                return true;
            }
        }
        return false;
    }

    /** 関係演算子 6 種。 */
    enum Operator {
        EQ, NE, GT, GE, LT, LE;

        boolean test(int comparison) {
            return switch (this) {
                case EQ -> comparison == 0;
                case NE -> comparison != 0;
                case GT -> comparison > 0;
                case GE -> comparison >= 0;
                case LT -> comparison < 0;
                case LE -> comparison <= 0;
            };
        }

        /** 2 文字の綴り (記号形と英字形)。知らなければ {@code null}。 */
        static Operator of(String spelled) {
            return switch (spelled) {
                case "EQ", "= ", " =" -> EQ;
                case "NE", "!=", "=!", "¬=", "=¬" -> NE;
                case "GT", "> ", " >" -> GT;
                case "GE", ">=", "=>" -> GE;
                case "LT", "< ", " <" -> LT;
                case "LE", "<=", "=<" -> LE;
                default -> null;
            };
        }
    }

    /** 修飾 1 つ。値の長さはフィールドの長さである。 */
    record Qualification(FieldDefinition field, Operator operator, byte[] value) {

        Qualification {
            value = value.clone();
        }

        /** 可変長のセグメントでフィールドが現在の長さに収まらなければ、満たさない。 */
        boolean matches(byte[] data) {
            int end = field.offset() + field.bytes();
            if (end > data.length) {
                return false;
            }
            byte[] actual = Arrays.copyOfRange(data, field.offset(), end);
            return operator.test(FieldComparison.compare(field.type(), actual, value));
        }
    }
}
