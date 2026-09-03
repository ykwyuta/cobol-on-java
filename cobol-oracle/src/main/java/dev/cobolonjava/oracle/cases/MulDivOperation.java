package dev.cobolonjava.oracle.cases;

import dev.cobolonjava.oracle.machine.Insn;

/** {@code MP} / {@code DP} の種別。 */
public enum MulDivOperation {

    MULTIPLY {
        @Override
        public byte[] instruction(int addr1, int len1, int addr2, int len2) {
            return Insn.mp(addr1, len1, addr2, len2);
        }
    },
    DIVIDE {
        @Override
        public byte[] instruction(int addr1, int len1, int addr2, int len2) {
            return Insn.dp(addr1, len1, addr2, len2);
        }
    };

    public abstract byte[] instruction(int addr1, int len1, int addr2, int len2);
}
