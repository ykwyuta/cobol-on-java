package dev.cobolonjava.compiler.semantic;

/** 文の被演算子 (要件 FR-060)。データ項目への参照か、定数である。 */
public sealed interface Operand {

    /** データ項目への参照。 */
    record Reference(DataReference reference) implements Operand {
    }

    /** 定数。 */
    record Literal(LiteralValue value) implements Operand {
    }
}
