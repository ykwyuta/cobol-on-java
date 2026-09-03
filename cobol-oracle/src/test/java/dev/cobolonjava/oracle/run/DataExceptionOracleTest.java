package dev.cobolonjava.oracle.run;

import static dev.cobolonjava.oracle.OracleSupport.bytes;
import static dev.cobolonjava.oracle.OracleSupport.hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.oracle.OracleSupport;
import dev.cobolonjava.oracle.machine.Insn;
import dev.cobolonjava.oracle.script.HerculesCase;
import dev.cobolonjava.runtime.data.NumProcMode;
import dev.cobolonjava.runtime.data.PackedDecimal;
import dev.cobolonjava.runtime.decimal.DataException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>検証レベル V2</b>: 不正な数字ニブルに対する 10 進演算例外を実機で確認する
 * (要件 FR-033, FR-141)。
 *
 * <p>COBOL から見た {@code S0C7} は、z/Architecture のデータ例外
 * (プログラム割込みコード {@code 0x0007}) である。ランタイムが
 * {@link DataException} を投げる条件が、実機で実際に割込みが起きる条件と
 * 一致していることを確かめる。
 */
@Tag("V2")
class DataExceptionOracleTest {

    /** z/Architecture がプログラム割込みコードを格納する実アドレス。 */
    private static final int INTERRUPTION_CODE_ADDRESS = 0x8E;
    /** データ例外の割込みコード。 */
    private static final String DATA_EXCEPTION = "0007";

    private static HerculesRunner runner;

    @BeforeAll
    static void setUp() {
        runner = OracleSupport.requireHercules();
    }

    private HerculesResult runAp(String name, String operand1Hex, String operand2Hex) throws Exception {
        HerculesCase c = new HerculesCase(name);
        byte[] op1 = bytes(operand1Hex);
        byte[] op2 = bytes(operand2Hex);
        int a1 = c.data(op1);
        int a2 = c.data(op2);
        c.emit(Insn.ap(a1, op1.length, a2, op2.length));
        c.dump("interruption code", INTERRUPTION_CODE_ADDRESS, 2);
        c.dump("result", a1, op1.length);
        return runner.run(c);
    }

    @Test
    @DisplayName("不正な数字ニブルは実機でデータ例外を起こす。ランタイムも DataException を投げる")
    void invalidDigitNibbleRaisesDataException() throws Exception {
        // 0x1A345C の 'A' は数字ニブルとして妥当でない
        HerculesResult r = runAp("s0c7-digit", "1A345C", "00001C");
        assertTrue(r.programCheck(),
                () -> "実機でプログラム割込みが起きるはずである。待機 PSW アドレス = 0x"
                        + Long.toHexString(r.waitPswAddress()));
        assertEquals(DATA_EXCEPTION, hex(r.at(INTERRUPTION_CODE_ADDRESS, 2)),
                "割込みコードはデータ例外 (0x0007) でなければならない");

        assertThrows(DataException.class,
                () -> PackedDecimal.decode(bytes("1A345C"), 0, NumProcMode.NOPFD),
                "ランタイムも同じ入力を不正として検出しなければならない");
    }

    @Test
    @DisplayName("不正な符号ニブルも実機でデータ例外を起こす")
    void invalidSignNibbleRaisesDataException() throws Exception {
        // 末尾ニブル 3 は符号ニブルとして妥当でない
        HerculesResult r = runAp("s0c7-sign", "123453", "00001C");
        assertTrue(r.programCheck(), "実機でプログラム割込みが起きるはずである");
        assertEquals(DATA_EXCEPTION, hex(r.at(INTERRUPTION_CODE_ADDRESS, 2)));

        assertThrows(DataException.class,
                () -> PackedDecimal.decode(bytes("123453"), 0, NumProcMode.NOPFD));
    }

    @Test
    @DisplayName("妥当なデータでは割込みが起きない")
    void validDataDoesNotRaise() throws Exception {
        HerculesResult r = runAp("s0c7-valid", "12345C", "00001C");
        assertFalse(r.programCheck(), "妥当なデータで割込みが起きてはならない");
        assertEquals("12346C", hex(r.at(0x400, 3)));
    }
}
