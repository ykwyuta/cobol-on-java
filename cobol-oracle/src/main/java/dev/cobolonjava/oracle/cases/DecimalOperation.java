package dev.cobolonjava.oracle.cases;

import dev.cobolonjava.oracle.machine.Insn;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.util.function.BinaryOperator;

/**
 * 合成ジェネレータが対象とする 2 項 10 進演算。
 *
 * <p>機械語命令と、ランタイム側の対応する演算を対にして保持する。
 * 両者が同じ結果を出すことがオラクル検証の内容である。
 *
 * <p>{@code MP} と {@code DP} を含めていないのは、オペランドの長さに
 * 「第 1 オペランドの上位に第 2 オペランドの長さぶんのゼロが必要」といった制約があり、
 * 一様な組み合わせ表に載せにくいためである。これらは個別のテストで扱う。
 */
public enum DecimalOperation {

    ADD(Decimal::add) {
        @Override
        public byte[] instruction(int addr1, int len1, int addr2, int len2) {
            return Insn.ap(addr1, len1, addr2, len2);
        }
    },
    SUBTRACT(Decimal::subtract) {
        @Override
        public byte[] instruction(int addr1, int len1, int addr2, int len2) {
            return Insn.sp(addr1, len1, addr2, len2);
        }
    },
    /**
     * {@code ZAP} による符号込みの転記。
     *
     * <p>ランタイム側は「第 2 オペランドをそのまま結果とする」だが、<b>値がゼロの場合は
     * 正号にする</b>。これは {@code ZAP} が負のゼロを正のゼロへ正規化するという実測に基づく
     * (provisional.md P-001)。
     */
    MOVE_WITH_SIGN((a, b) -> b.isZero() ? b.abs() : b) {
        @Override
        public byte[] instruction(int addr1, int len1, int addr2, int len2) {
            return Insn.zap(addr1, len1, addr2, len2);
        }
    };

    private final BinaryOperator<Decimal> runtimeOperation;

    DecimalOperation(BinaryOperator<Decimal> runtimeOperation) {
        this.runtimeOperation = runtimeOperation;
    }

    /** この演算に対応する機械語命令。 */
    public abstract byte[] instruction(int addr1, int len1, int addr2, int len2);

    /** ランタイム側の対応する演算。 */
    public Decimal apply(Decimal left, Decimal right) {
        return runtimeOperation.apply(left, right);
    }
}
