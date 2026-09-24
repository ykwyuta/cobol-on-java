package dev.cobolonjava.hlasm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.oracle.machine.Insn;
import dev.cobolonjava.oracle.run.HerculesResult;
import dev.cobolonjava.oracle.run.HerculesRunner;
import dev.cobolonjava.oracle.script.HerculesCase;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 同じ HLASM 機械語を Hercules と Cpu に流し、実行結果を突き合わせる。 */
@Tag("V2")
class HerculesExecutionOracleTest {

    private static final int HERCULES_CODE = 0x200;
    private static final int HERCULES_DATA = 0x400;
    private static final int CAPTURE = 64;
    private static final int DATA_LENGTH = 96;
    private static HerculesRunner runner;

    @BeforeAll
    static void setUp() {
        HerculesRunner.Detection detection = HerculesRunner.detect(Path.of(
                System.getProperty("java.io.tmpdir"), "hlasm-hercules-oracle"));
        if (detection instanceof HerculesRunner.Detection.Unavailable unavailable) {
            Assumptions.abort("Hercules 実行比較をスキップ: " + unavailable.reason());
        }
        runner = ((HerculesRunner.Detection.Available) detection).runner();
    }

    @Test
    void overlappingMove() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        data[0] = 0x11;
        compare("mvc-overlap", data, "MVC   1(7,4),0(4)");
    }

    @Test
    void byteComparisonConditionCode() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        data[0] = 1;
        data[1] = 2;
        compare("clc-less", data, "CLC   0(1,4),1(4)");
    }

    @Test
    void packedDecimalAdd() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        System.arraycopy(HexFormat.of().parseHex("12345C"), 0, data, 0, 3);
        System.arraycopy(HexFormat.of().parseHex("00001C"), 0, data, 8, 3);
        compare("ap-positive", data, "AP    0(3,4),8(3,4)");
    }

    @Test
    void packedShiftAndRound() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        System.arraycopy(HexFormat.of().parseHex("12345C"), 0, data, 0, 3);
        compare("srp-left", data, "SRP   0(3,4),2(0),5");
    }

    @Test
    void moveWithOffset() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        System.arraycopy(HexFormat.of().parseHex("12345C"), 0, data, 0, 3);
        System.arraycopy(HexFormat.of().parseHex("67890F"), 0, data, 8, 3);
        compare("mvo", data, "MVO   0(3,4),8(3,4)");
    }

    @Test
    void editAndMark() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        System.arraycopy(HexFormat.of().parseHex("40202020202000123C"), 0, data, 0, 9);
        compare("edmk", data, "EDMK  0(6,4),6(4)");
    }

    @Test
    void shiftDoubleLeftWithOverflow() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        System.arraycopy(HexFormat.of().parseHex("6000000000000000"), 0, data, 0, 8);
        compare("slda-overflow", data,
                "L     0,0(0,4)", "L     1,4(0,4)", "SLDA  0,1");
    }

    @Test
    void shiftDoubleRight() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        System.arraycopy(HexFormat.of().parseHex("C000000000000001"), 0, data, 0, 8);
        compare("srda-negative", data,
                "L     0,0(0,4)", "L     1,4(0,4)", "SRDA  0,1");
    }

    @Test
    void translateAndTest() throws Exception {
        byte[] data = new byte[352];
        data[0] = (byte) 0xC1;
        data[1] = (byte) 0xC2;
        data[2] = 0x6B;
        data[3] = (byte) 0xC3;
        data[32 + 0x6B] = 4;
        compare("trt", data, 320, "TRT   0(4,4),32(4)");
    }

    @Test
    void executeWithModifiedLength() throws Exception {
        compare("ex-mvc", new byte[DATA_LENGTH],
                "MVI   0(4),X'33'", "LA    5,2", "EX    5,MOVEIT",
                "B     SKIP", "@MOVEIT   MVC   1(1,4),0(4)",
                "@SKIP     MVI   8(4),X'55'");
    }

    @Test
    void branchOnIndexHigh() throws Exception {
        compare("bxh", new byte[DATA_LENGTH],
                "LA    0,1", "LA    1,2", "LA    2,4",
                "@LOOP     BXH   0,1,HIGH", "B     LOOP",
                "@HIGH     STC   0,10(4)");
    }

    @Test
    void maximumMvcLength() throws Exception {
        byte[] data = new byte[512];
        data[0] = 0x5A;
        compare("mvc-256", data, 480, "MVC   1(256,4),0(4)");
    }

    @Test
    void translateAndTestNoMatch() throws Exception {
        byte[] data = new byte[352];
        data[0] = (byte) 0xC1;
        data[1] = (byte) 0xC2;
        compare("trt-no-match", data, 320, "LA    1,0", "LA    2,0",
                "TRT   0(2,4),32(4)");
    }

    @Test
    void packedArithmeticBoundary() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        System.arraycopy(HexFormat.of().parseHex("99999C"), 0, data, 0, 3);
        System.arraycopy(HexFormat.of().parseHex("00001C"), 0, data, 8, 3);
        compare("ap-overflow-unmasked", data, "AP    0(3,4),8(3,4)");
    }

    @Test
    void dividePackedDecimal() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        System.arraycopy(HexFormat.of().parseHex("0001234C"), 0, data, 0, 4);
        System.arraycopy(HexFormat.of().parseHex("002C"), 0, data, 8, 2);
        compare("dp-remainder", data, "DP    0(4,4),8(2,4)");
    }

    @Test
    void dataExceptionCode() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        System.arraycopy(HexFormat.of().parseHex("12345C"), 0, data, 0, 3);
        System.arraycopy(HexFormat.of().parseHex("0000AC"), 0, data, 8, 3);
        compareInterrupt("ap-invalid-digit", data, "AP    0(3,4),8(3,4)");
    }

    @Test
    void decimalDivideExceptionCode() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        System.arraycopy(HexFormat.of().parseHex("0001234C"), 0, data, 0, 4);
        System.arraycopy(HexFormat.of().parseHex("000C"), 0, data, 8, 2);
        compareInterrupt("dp-zero", data, "DP    0(4,4),8(2,4)");
    }

    @Test
    void fixedDivideExceptionCode() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        compareInterrupt("dr-zero", data, "XR    0,0", "XR    1,1", "DR    0,1");
    }

    @Test
    void specificationExceptionCode() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        compareInterrupt("mr-odd-register", data, "MR    1,2");
    }

    @Test
    void executeExceptionCode() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        compareInterrupt("ex-of-ex", data, "EX    0,AGAIN", "B     SKIP",
                "@AGAIN    EX    0,AGAIN", "@SKIP     BR    14");
    }

    @Test
    void decimalOverflowExceptionCodeAndStoredResult() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        System.arraycopy(HexFormat.of().parseHex("99999C"), 0, data, 0, 3);
        System.arraycopy(HexFormat.of().parseHex("00001C"), 0, data, 8, 3);
        System.arraycopy(HexFormat.of().parseHex("04000000"), 0, data, 16, 4);
        compareInterrupt("ap-overflow-masked", data,
                "L     2,16(0,4)", "SPM   2", "AP    0(3,4),8(3,4)");
    }

    @Test
    void fixedOverflowExceptionCode() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        System.arraycopy(HexFormat.of().parseHex("7FFFFFFF"), 0, data, 0, 4);
        System.arraycopy(HexFormat.of().parseHex("08000000"), 0, data, 16, 4);
        System.arraycopy(HexFormat.of().parseHex("00000001"), 0, data, 20, 4);
        compareInterrupt("a-overflow-masked", data,
                "L     0,0(0,4)", "L     2,16(0,4)", "SPM   2", "A     0,20(0,4)");
    }

    @Test
    void packZonedDigits() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        System.arraycopy(HexFormat.of().parseHex("F1F2C3"), 0, data, 8, 3);
        compare("pack", data, "PACK  0(2,4),8(3,4)");
    }

    @Test
    void unpackPackedDigits() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        System.arraycopy(HexFormat.of().parseHex("12345D"), 0, data, 8, 3);
        compare("unpk", data, "UNPK  0(5,4),8(3,4)");
    }

    @Test
    void branchOnIndexLowOrEqualWithEvenIncrementRegister() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        compare("bxle-even", data, "LA    0,1", "LA    2,2", "LA    3,4",
                "BXLE  0,2,LOW", "MVI   8(4),X'FF'",
                "@LOW      STC   0,10(4)");
    }

    @Test
    void executeBranchWithModifiedMask() throws Exception {
        byte[] data = new byte[DATA_LENGTH];
        compare("ex-branch", data, "LA    5,240", "EX    5,BRANCH",
                "MVI   8(4),X'FF'", "B     SKIP",
                "@BRANCH   BC    0,TARGET", "@TARGET   MVI   9(4),X'55'",
                "@SKIP     MVI   10(4),X'66'");
    }

    private static void compare(String name, byte[] initial, String... operations) throws Exception {
        compare(name, initial, CAPTURE, operations);
    }

    private static void compare(String name, byte[] initial, int capture,
                                String... operations) throws Exception {
        byte[] code = assemble(name, capture, operations);

        HerculesCase host = createHost(name, initial, code);
        host.dump(name, HERCULES_DATA, initial.length);
        HerculesResult actual = runner.run(host);
        assertTrue(actual.completed(), () -> name + ": Hercules 割込み\n" + actual.log());
        byte[] hostData = actual.at(HERCULES_DATA, initial.length);

        byte[] localData = initial.clone();
        AddressSpace.Builder builder = AddressSpace.builder();
        int codeBase = builder.place("CODE", code.clone());
        int dataBase = builder.place("DATA", localData);
        Cpu cpu = createCpu(builder.build(), codeBase, dataBase);
        cpu.run(codeBase);

        assertArrayEquals(Arrays.copyOfRange(hostData, 0, capture),
                Arrays.copyOfRange(localData, 0, capture), name + " 作業域");
        assertArrayEquals(Arrays.copyOfRange(hostData, capture + 16, initial.length),
                Arrays.copyOfRange(localData, capture + 16, initial.length), name + " 後続作業域");
        assertEquals(word(hostData, capture), word(localData, capture), name + " R0");
        int hostR1 = word(hostData, capture + 4);
        int localR1 = word(localData, capture + 4);
        if (hostR1 >= HERCULES_DATA && hostR1 < HERCULES_DATA + initial.length
                && localR1 >= dataBase && localR1 < dataBase + initial.length) {
            assertEquals(hostR1 - HERCULES_DATA, localR1 - dataBase, name + " R1 相対番地");
        } else {
            assertEquals(hostR1, localR1, name + " R1");
        }
        assertEquals(word(hostData, capture + 8), word(localData, capture + 8), name + " R2");
        assertEquals((hostData[capture + 12] >>> 4) & 3,
                (localData[capture + 12] >>> 4) & 3, name + " 条件コード");
        assertEquals((hostData[capture + 12] >>> 4) & 3, cpu.conditionCode(),
                name + " Cpu 条件コード");
    }

    private static void compareInterrupt(String name, byte[] initial,
                                         String... operations) throws Exception {
        byte[] code = assemble(name, CAPTURE, operations);
        HerculesCase host = createHost(name, initial, code);
        host.dump("program interruption code", 0x8E, 2);
        host.dump(name, HERCULES_DATA, initial.length);
        HerculesResult actual = runner.run(host);
        assertTrue(actual.programCheck(), () -> name + ": Hercules でプログラム割込みなし\n" + actual.log());

        byte[] localData = initial.clone();
        AddressSpace.Builder builder = AddressSpace.builder();
        int codeBase = builder.place("CODE", code.clone());
        int dataBase = builder.place("DATA", localData);
        Cpu cpu = createCpu(builder.build(), codeBase, dataBase);
        MachineException exception = assertThrows(MachineException.class,
                () -> cpu.run(codeBase), name + " Cpu 割込み");
        byte[] interruptionCode = actual.at(0x8E, 2);
        assertEquals((interruptionCode[0] & 0xFF) << 8 | interruptionCode[1] & 0xFF,
                exception.code(), name + " プログラム割込みコード");
        assertArrayEquals(actual.at(HERCULES_DATA, initial.length), localData,
                name + " 割込み時の作業域");
    }

    private static byte[] assemble(String name, int capture, String... operations) {
        List<String> source = new ArrayList<>();
        source.add("TEST     CSECT");
        source.add("         USING TEST,15");
        for (String operation : operations) {
            source.add(operation.startsWith("@") ? operation.substring(1)
                    : "         " + operation);
        }
        // ST は条件コードを変えない。IPM で条件コードを取り出して記憶域に残す。
        source.add("         ST    0," + capture + "(0,4)");
        source.add("         ST    1," + (capture + 4) + "(0,4)");
        source.add("         ST    2," + (capture + 8) + "(0,4)");
        source.add("         IPM   5");
        source.add("         ST    5," + (capture + 12) + "(0,4)");
        source.add("         BR    14");
        source.add("         END");
        Assembler.Result assembled = Assembler.assemble(name + ".asm", String.join("\n", source));
        assertTrue(assembled.succeeded(), () -> assembled.diagnostics().toString());
        return assembled.module().text();
    }

    private static HerculesCase createHost(String name, byte[] initial, byte[] code) {
        HerculesCase host = new HerculesCase(name);
        assertEquals(HERCULES_DATA, host.data(initial));
        host.emit(Insn.la(4, HERCULES_DATA));
        host.emit(Insn.la(14, HERCULES_CODE + 12 + code.length));
        host.emit(Insn.la(15, HERCULES_CODE + 12));
        for (int offset = 0; offset < code.length; offset += 32) {
            host.emit(Arrays.copyOfRange(code, offset, Math.min(code.length, offset + 32)));
        }
        return host;
    }

    private static Cpu createCpu(AddressSpace space, int codeBase, int dataBase) {
        Cpu cpu = new Cpu(space);
        cpu.setRegister(4, dataBase);
        cpu.setRegister(14, Cpu.RETURN_SENTINEL);
        cpu.setRegister(15, codeBase);
        cpu.setProgramMask(0); // HerculesCase の restart PSW に合わせる
        return cpu;
    }

    private static int word(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) << 24 | (bytes[offset + 1] & 0xFF) << 16
                | (bytes[offset + 2] & 0xFF) << 8 | (bytes[offset + 3] & 0xFF);
    }
}
