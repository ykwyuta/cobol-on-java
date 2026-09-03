package dev.cobolonjava.oracle.run;

import static dev.cobolonjava.oracle.OracleSupport.hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.oracle.OracleSupport;
import dev.cobolonjava.oracle.machine.Insn;
import dev.cobolonjava.oracle.script.HerculesCase;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.verb.Inspect;
import dev.cobolonjava.runtime.verb.Region;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>検証レベル V2</b>: {@code INSPECT ... CONVERTING} を実機の {@code TR} 命令と
 * 突き合わせる (要件 FR-065)。
 *
 * <p>ホストの {@code TR} 命令は、各バイトの値を添字として 256 バイトの変換表を引き、
 * その値で置き換える。COBOL の {@code CONVERTING} はこの命令に対応する。
 * {@link Inspect#translationTable} が作る表は、そのまま {@code TR} に渡せる。
 */
@Tag("V2")
class InspectOracleTest {

    private static final CodePage CP = CodePages.IBM_1047;

    private static HerculesRunner runner;

    @BeforeAll
    static void setUp() {
        runner = OracleSupport.requireHercules();
    }

    private record Case(String data, String from, String to) {
        @Override
        public String toString() {
            return "[" + data + "] " + from + " -> " + to;
        }
    }

    @Test
    @DisplayName("CONVERTING がホストの TR と一致する (FR-065)")
    void convertingMatchesHost() throws Exception {
        List<Case> cases = List.of(
                new Case("hello world", "abcdefghijklmnopqrstuvwxyz", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
                new Case("ABC123XYZ", "0123456789", "**********"),
                new Case("AAA", "A", "B"),
                new Case("no change here", "QQ", "ZZ"),
                new Case("A1B2C3", "ABC123", "XYZ789"),
                // 変換元に同じ文字が複数回現れる。最初の対応が使われる
                new Case("AAA", "AA", "XY")
        );

        HerculesCase c = new HerculesCase("tr");
        int[] dataAddr = new int[cases.size()];
        for (int i = 0; i < cases.size(); i++) {
            Case ec = cases.get(i);
            byte[] table = Inspect.translationTable(CP.encode(ec.from()), CP.encode(ec.to()));
            byte[] data = CP.encode(ec.data());
            int tableAddr = c.data(table);
            dataAddr[i] = c.data(data);
            c.emit(Insn.tr(dataAddr[i], data.length, tableAddr));
        }
        for (int i = 0; i < cases.size(); i++) {
            c.dump(cases.get(i).toString(), dataAddr[i], CP.encode(cases.get(i).data()).length);
        }

        HerculesResult r = runner.run(c);
        assertTrue(r.completed(), () -> "Hercules で割込みが発生した。ログ:\n" + r.log());

        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            Case ec = cases.get(i);
            byte[] host = r.at(dataAddr[i], CP.encode(ec.data()).length);
            byte[] mine = Inspect.convert(CP.encode(ec.data()), CP.encode(ec.from()),
                    CP.encode(ec.to()), Region.whole());
            if (!hex(host).equals(hex(mine))) {
                mismatches.add(String.format("%s -> Hercules=[%s], cobol-runtime=[%s]",
                        ec, CP.decode(host), CP.decode(mine)));
            }
        }
        assertEquals(List.of(), mismatches, mismatches.size() + " 件が不一致");
    }

    @Test
    @DisplayName("範囲を限定した CONVERTING は、その範囲だけに TR をかけたのと同じになる (FR-065)")
    void regionLimitedConvertingMatchesHost() throws Exception {
        String text = "abcde";
        String from = "cde";
        String to = "XYZ";
        byte[] data = CP.encode(text);
        byte[] table = Inspect.translationTable(CP.encode(from), CP.encode(to));

        // AFTER INITIAL "ab" は 3 バイト目以降が範囲になる
        int regionStart = 2;
        int regionLength = data.length - regionStart;

        HerculesCase c = new HerculesCase("tr-region");
        int tableAddr = c.data(table);
        int dataAddr = c.data(data);
        c.emit(Insn.tr(dataAddr + regionStart, regionLength, tableAddr));
        c.dump("converted", dataAddr, data.length);

        HerculesResult r = runner.run(c);
        assertTrue(r.completed());

        byte[] host = r.at(dataAddr, data.length);
        byte[] mine = Inspect.convert(data, CP.encode(from), CP.encode(to), Region.after(CP.encode("ab")));
        assertEquals(hex(host), hex(mine),
                "Hercules=[" + CP.decode(host) + "], cobol-runtime=[" + CP.decode(mine) + "]");
        assertEquals("abXYZ", CP.decode(host));
    }
}
