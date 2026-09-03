package dev.cobolonjava.oracle.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.oracle.machine.Insn;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class HerculesCaseTest {

    @Test
    @DisplayName("32 バイトを超えるデータは複数の r コマンドに分割される")
    void largeDataIsSplitIntoChunks() {
        // Hercules の r コマンドは 1 回に 32 バイトまで。超えると拒否され、
        // 記憶域は初期値のまま残る。分割しないとゼロを実機の結果として採取してしまう。
        HerculesCase c = new HerculesCase("chunk");
        byte[] table = new byte[256];
        for (int i = 0; i < 256; i++) {
            table[i] = (byte) i;
        }
        int addr = c.data(table);
        c.emit(Insn.tr(addr, 1, addr));

        String script = c.toScript();
        long storeLines = script.lines()
                .filter(l -> l.startsWith("r " + Integer.toHexString(addr).toUpperCase())
                        || l.matches("r [0-9A-F]+=.*"))
                .filter(l -> !l.contains("PSW"))
                .count();
        assertTrue(storeLines >= 8, "256 バイトなら 8 行以上に分かれるはず: " + storeLines);

        // どの書き込み行も 32 バイト (16 進 64 文字) を超えない
        script.lines()
                .filter(l -> l.matches("r [0-9A-F]+=[0-9A-F]+\\s*(#.*)?"))
                .forEach(l -> {
                    String value = l.substring(l.indexOf('=') + 1).split("\\s+")[0];
                    assertTrue(value.length() <= 64,
                            "1 回の書き込みは 32 バイト以下でなければならない: " + value.length() / 2 + " バイト");
                });
    }

    @Test
    @DisplayName("データ領域はベースレジスタ 0 のアドレス上限を超えないよう検査される")
    void dataAreaIsBounded() {
        HerculesCase c = new HerculesCase("overflow");
        byte[] big = new byte[256];
        // 0x400 から 0x1000 までは 3072 バイト。256 バイトを 12 個で埋まる
        for (int i = 0; i < 12; i++) {
            c.data(big);
        }
        assertEquals(IllegalStateException.class,
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                        () -> c.data(Arrays.copyOf(big, 256))).getClass());
    }
}
