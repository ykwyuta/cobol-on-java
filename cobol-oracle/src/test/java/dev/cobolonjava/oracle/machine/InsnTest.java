package dev.cobolonjava.oracle.machine;

import static dev.cobolonjava.oracle.OracleSupport.hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 機械語の組み立ての検証 (要件 FR-211)。
 *
 * <p>期待値は Principles of Operation の命令形式から手で計算したものであり、
 * かつ実際に Hercules 上で意図どおり実行されることが V2 テストで裏付けられている。
 */
@Tag("V1")
class InsnTest {

    @Test
    @DisplayName("SS-b 形式: 命令コード / L1L2 ニブル / B1D1 / B2D2")
    void ssbFormat() {
        // AP 0x400(3),0x408(3) -> FA (3-1)(3-1) 0400 0408
        assertEquals("FA2204000408", hex(Insn.ap(0x400, 3, 0x408, 3)));
        assertEquals("FB1104100418", hex(Insn.sp(0x410, 2, 0x418, 2)));
        assertEquals("FC3004300438", hex(Insn.mp(0x430, 4, 0x438, 1)));
        assertEquals("FD3004400448", hex(Insn.dp(0x440, 4, 0x448, 1)));
        assertEquals("F81104200428", hex(Insn.zap(0x420, 2, 0x428, 2)));
    }

    @Test
    @DisplayName("SS-a 形式: 命令コード / 長さ 1 バイト / B1D1 / B2D2")
    void ssaFormat() {
        assertEquals("D20F04000500", hex(Insn.mvc(0x400, 16, 0x500)));
        assertEquals("DE0904000500", hex(Insn.ed(0x400, 10, 0x500)));
    }

    @Test
    @DisplayName("SS-c 形式 (SRP): L1 と丸め桁がニブルに入り、第 2 オペランドアドレスが桁移動量になる")
    void srpFormat() {
        // 右へ 1 桁、丸め桁 5 -> 桁移動量は 6 ビットの 2 の補数で 0x3F
        assertEquals("F0150400003F", hex(Insn.srp(0x400, 2, -1, 5)));
        // 左へ 2 桁、丸めなし
        assertEquals("F01004000002", hex(Insn.srp(0x400, 2, 2, 0)));
    }

    @Test
    @DisplayName("ベースレジスタ 0 の制約と長さの範囲は検査される")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> Insn.ap(0x1000, 3, 0x400, 3));
        assertThrows(IllegalArgumentException.class, () -> Insn.ap(0x400, 17, 0x400, 3));
        assertThrows(IllegalArgumentException.class, () -> Insn.ap(0x400, 0, 0x400, 3));
        assertThrows(IllegalArgumentException.class, () -> Insn.srp(0x400, 2, -1, 10));
    }
}
