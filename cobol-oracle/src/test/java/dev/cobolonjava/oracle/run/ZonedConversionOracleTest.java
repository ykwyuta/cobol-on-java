package dev.cobolonjava.oracle.run;

import static dev.cobolonjava.oracle.OracleSupport.bytes;
import static dev.cobolonjava.oracle.OracleSupport.hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.oracle.OracleSupport;
import dev.cobolonjava.oracle.machine.Insn;
import dev.cobolonjava.oracle.script.HerculesCase;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.data.NumProcMode;
import dev.cobolonjava.runtime.data.PackedDecimal;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.data.ZonedDecimal;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>検証レベル V2</b>: ゾーン10進とパック10進の相互変換を、Hercules 上で実行した
 * {@code PACK} / {@code UNPK} 命令と突き合わせる (要件 FR-031)。
 *
 * <p>特に {@code ZonedDecimal.decode} が「符号位置以外のゾーンニブルを見ない」という
 * 前提で実装されている点は、{@code PACK} 命令の挙動そのものである。ここで裏付けが取れる。
 */
@Tag("V2")
class ZonedConversionOracleTest {

    private static final int ZONED_BYTES = 5;
    private static final int PACKED_BYTES = 3;
    private static final int DIGITS = 5;

    private static HerculesRunner runner;

    @BeforeAll
    static void setUp() {
        runner = OracleSupport.requireHercules();
    }

    /** ゾーン10進のバイト列に {@code PACK} を適用し、パック10進の結果を返す。 */
    private List<byte[]> pack(List<String> zonedHex) throws Exception {
        HerculesCase c = new HerculesCase("pack");
        int[] target = new int[zonedHex.size()];
        for (int i = 0; i < zonedHex.size(); i++) {
            target[i] = c.reserve(PACKED_BYTES);
            int source = c.data(bytes(zonedHex.get(i)));
            c.emit(Insn.pack(target[i], PACKED_BYTES, source, ZONED_BYTES));
        }
        for (int i = 0; i < zonedHex.size(); i++) {
            c.dump("packed " + i, target[i], PACKED_BYTES);
        }
        HerculesResult r = runner.run(c);
        assertTrue(r.completed(), () -> "Hercules で割込みが発生した。ログ:\n" + r.log());
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < zonedHex.size(); i++) {
            out.add(r.at(target[i], PACKED_BYTES));
        }
        return out;
    }

    /** パック10進のバイト列に {@code UNPK} を適用し、ゾーン10進の結果を返す。 */
    private List<byte[]> unpack(List<String> packedHex) throws Exception {
        HerculesCase c = new HerculesCase("unpk");
        int[] target = new int[packedHex.size()];
        for (int i = 0; i < packedHex.size(); i++) {
            target[i] = c.reserve(ZONED_BYTES);
            int source = c.data(bytes(packedHex.get(i)));
            c.emit(Insn.unpk(target[i], ZONED_BYTES, source, PACKED_BYTES));
        }
        for (int i = 0; i < packedHex.size(); i++) {
            c.dump("zoned " + i, target[i], ZONED_BYTES);
        }
        HerculesResult r = runner.run(c);
        assertTrue(r.completed(), () -> "Hercules で割込みが発生した。ログ:\n" + r.log());
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < packedHex.size(); i++) {
            out.add(r.at(target[i], ZONED_BYTES));
        }
        return out;
    }

    @Test
    @DisplayName("UNPK の結果が ZonedDecimal.encode と一致する (FR-031)")
    void unpackMatchesRuntimeEncode() throws Exception {
        List<String> packed = List.of("12345C", "12345D", "00000C", "99999C", "00001D");
        List<byte[]> hostResults = unpack(packed);
        for (int i = 0; i < packed.size(); i++) {
            Decimal value = PackedDecimal.decode(bytes(packed.get(i)), 0, NumProcMode.NOPFD);
            byte[] mine = ZonedDecimal.encode(value, DIGITS, 0, SignPosition.TRAILING, CodePages.IBM_1047);
            String expected = hex(hostResults.get(i));
            assertEquals(expected, hex(mine), "UNPK of " + packed.get(i));
        }
    }

    @Test
    @DisplayName("PACK は符号位置以外のゾーンニブルを見ない。ZonedDecimal.decode の前提を裏付ける")
    void packIgnoresNonSignZones() throws Exception {
        // 同じ数字を持つがゾーンが異なる 3 通り。いずれも符号位置のゾーンだけが結果を分ける。
        List<byte[]> results = pack(List.of(
                "F1F2F3F4C5",   // 正の符号ゾーン C
                "0102030405",   // ゾーンがすべて 0。数字ニブルだけが使われる
                "A1B2C3D4C5"    // ゾーンがばらばら
        ));
        assertEquals("12345C", hex(results.get(0)));
        assertEquals("123450", hex(results.get(1)),
                "最右端バイトのゾーン 0 がそのまま符号ニブルになる。PACK は符号を検査しない");
        assertEquals("12345C", hex(results.get(2)),
                "符号位置以外のゾーンは結果に影響しない");

        // ランタイムの decode も同じ数字を読む (符号位置を見ない UNSIGNED 指定)
        for (String zoned : List.of("F1F2F3F4F5", "0102030405", "A1B2C3D4E5")) {
            Decimal d = ZonedDecimal.decode(bytes(zoned), 0, SignPosition.UNSIGNED,
                    CodePages.IBM_1047, NumProcMode.NOPFD);
            assertEquals(0, Decimal.parse("12345").compareTo(d), "decode of " + zoned);
        }
    }

    @Test
    @DisplayName("PACK は符号ニブルをそのまま移す。優先符号への正規化は行わない (FR-033)")
    void packPreservesSignNibbleVerbatim() throws Exception {
        List<byte[]> results = pack(List.of(
                "F1F2F3F4C5", "F1F2F3F4D5", "F1F2F3F4F5", "F1F2F3F4A5", "F1F2F3F4B5"));
        assertEquals("12345C", hex(results.get(0)));
        assertEquals("12345D", hex(results.get(1)));
        assertEquals("12345F", hex(results.get(2)), "F は F のまま。C へ正規化されない");
        assertEquals("12345A", hex(results.get(3)), "A は A のまま");
        assertEquals("12345B", hex(results.get(4)), "B は B のまま");

        // ランタイムは符号ニブルを解釈したうえで優先符号 (C / D) を書き出す。
        // したがって F / A / B の入力では PACK の結果とバイト列が一致しない。
        // これは意図した差であり provisional.md の P-015 に記録している。
        for (int i = 0; i < 2; i++) {
            String zoned = List.of("F1F2F3F4C5", "F1F2F3F4D5").get(i);
            Decimal d = ZonedDecimal.decode(bytes(zoned), 0, SignPosition.TRAILING,
                    CodePages.IBM_1047, NumProcMode.NOPFD);
            assertEquals(hex(results.get(i)), hex(PackedDecimal.encode(d, DIGITS, 0, true)),
                    "優先符号の入力では PACK と一致する: " + zoned);
        }
    }
}
