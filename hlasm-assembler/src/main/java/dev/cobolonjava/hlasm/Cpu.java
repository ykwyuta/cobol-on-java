package dev.cobolonjava.hlasm;

import dev.cobolonjava.runtime.data.NumProcMode;
import dev.cobolonjava.runtime.data.PackedDecimal;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.math.BigInteger;

/**
 * z/Architecture の命令を 1 本ずつ実行する。
 *
 * <p>調査レポートで「歩く対象が文ではなく機械の状態である」と書いた層である。構文木を歩く
 * {@code PliRuntime.Executor} の構造は流用できない。{@code EX} による自己書き換えと、
 * レジスタの値へ飛ぶ計算分岐があるため、JVM のバイトコードへ素直に写すこともできない。
 * 命令を 1 本ずつ解いて実行するのが、この機械に対しては<b>いちばん素直な</b>書き方である。
 *
 * <p>AMODE 31 とし、汎用レジスタは 32 ビットとして扱う。
 *
 * <p>10 進命令の意味論は {@code cobol-runtime} の {@link Decimal} と {@link PackedDecimal} に
 * 委ねる。この層は COBOL の算術のために既に Hercules と突き合わせてあり、
 * <b>ゼロ結果の符号が命令ごとに違う</b>という Principles of Operation から読み取れない規則も
 * 実測で入っている (暫定判断 P-001)。ここで書き直すと、その裏づけを捨てることになる。
 *
 * <p>実装していない命令は<b>断る</b>。近い振る舞いを返すと、組み立ても実行も通ったうえで
 * 違うバイト列が出る。演算例外として上げれば、どこで足りなかったかが残る。
 */
public final class Cpu {

    /** 汎用レジスタの本数。 */
    public static final int REGISTERS = 16;

    /** 戻り番地として置く見張り。ここへ分岐したら実行を終える。 */
    public static final int RETURN_SENTINEL = 0x00000002;

    /** 入口でのプログラムマスク。10 進オーバーフローだけが割込みになる。 */
    public static final int DEFAULT_PROGRAM_MASK = 0x4;

    private static final int MASK_FIXED_OVERFLOW = 0x8;
    private static final int MASK_DECIMAL_OVERFLOW = 0x4;

    /** 実行する命令数の上限。返ってこないプログラムを見捨てるためにある。 */
    private static final long DEFAULT_STEP_LIMIT = 50_000_000L;

    private final AddressSpace memory;
    private final int[] gpr = new int[REGISTERS];
    private final long stepLimit;
    private int conditionCode;
    /**
     * PSW のプログラムマスク (4 ビット)。上から固定小数点オーバーフロー、10 進オーバーフロー、
     * 指数アンダーフロー、有効数字。立っていれば、その事象でプログラム割込みになる。
     *
     * <p>既定は 10 進オーバーフローだけを立てる ({@link #DEFAULT_PROGRAM_MASK})。以前の実装の
     * 振る舞い (固定小数点のあふれは条件コード 3、10 進のあふれは S0CA) と同じにするためである。
     * COBOL から呼ばれたときに実機のマスクが何であるかは確かめていない (暫定判断 P-174、
     * z/OS probe の ASMPM)。
     */
    private int programMask = DEFAULT_PROGRAM_MASK;
    private int instructionAddress;
    private long steps;

    public Cpu(AddressSpace memory) {
        this(memory, DEFAULT_STEP_LIMIT);
    }

    public Cpu(AddressSpace memory, long stepLimit) {
        this.memory = memory;
        this.stepLimit = stepLimit;
    }

    public AddressSpace memory() {
        return memory;
    }

    public int register(int index) {
        return gpr[index];
    }

    public void setRegister(int index, int value) {
        gpr[index] = value;
    }

    public int conditionCode() {
        return conditionCode;
    }

    public int programMask() {
        return programMask;
    }

    public void setProgramMask(int value) {
        programMask = value & 0xF;
    }

    public long steps() {
        return steps;
    }

    /** 入口から実行し、{@link #RETURN_SENTINEL} へ戻るまで回す。戻り値は R15 である。 */
    public int run(int entry) {
        instructionAddress = entry;
        while (instructionAddress != RETURN_SENTINEL) {
            if (++steps > stepLimit) {
                // プログラム割込みとは別の例外である。戻ってこないのは資産の出来事ではなく、
                // 測定の側から見た「結果が取れなかった」である
                throw new RunawayProgramException(stepLimit);
            }
            int at = instructionAddress;
            try {
                step();
            } catch (MachineException failure) {
                failure.at(at);
                throw failure;
            }
        }
        return gpr[15];
    }

    private void step() {
        int at = instructionAddress;
        int opcode = memory.get(at) & 0xFF;
        int length = lengthOf(opcode);
        byte[] insn = memory.read(at, length);
        instructionAddress = at + length;
        perform(insn, false);
    }

    /**
     * 命令の長さは命令コードの上位 2 ビットで決まる。
     * {@code 00} なら 2 バイト、{@code 01} と {@code 10} なら 4 バイト、{@code 11} なら 6 バイトである。
     */
    private static int lengthOf(int opcode) {
        return switch (opcode >> 6) {
            case 0 -> 2;
            case 1, 2 -> 4;
            default -> 6;
        };
    }

    // --- 命令の実行 ---

    private void perform(byte[] insn, boolean executed) {
        int opcode = insn[0] & 0xFF;
        switch (opcode) {
            // --- RR 形式 ---
            case 0x05 -> { // BALR
                int r1 = r1(insn);
                int r2 = r2(insn);
                int target = gpr[r2];
                gpr[r1] = instructionAddress;
                if (r2 != 0) {
                    instructionAddress = target & 0x7FFFFFFF;
                }
            }
            case 0x0D -> { // BASR
                int r1 = r1(insn);
                int r2 = r2(insn);
                int target = gpr[r2];
                gpr[r1] = instructionAddress;
                if (r2 != 0) {
                    instructionAddress = target & 0x7FFFFFFF;
                }
            }
            case 0x06 -> { // BCTR
                int r1 = r1(insn);
                int r2 = r2(insn);
                gpr[r1] = gpr[r1] - 1;
                if (gpr[r1] != 0 && r2 != 0) {
                    instructionAddress = gpr[r2] & 0x7FFFFFFF;
                }
            }
            case 0x07 -> { // BCR
                int mask = r1(insn);
                int r2 = r2(insn);
                if (r2 != 0 && selected(mask)) {
                    instructionAddress = gpr[r2] & 0x7FFFFFFF;
                }
            }
            case 0x10 -> { // LPR
                int r1 = r1(insn);
                int value = gpr[r2(insn)];
                gpr[r1] = Math.abs(value);
                conditionCode = value == Integer.MIN_VALUE ? 3 : value == 0 ? 0 : 2;
                fixedOverflow();
            }
            case 0x11 -> { // LNR
                int r1 = r1(insn);
                int value = gpr[r2(insn)];
                gpr[r1] = -Math.abs(value);
                conditionCode = gpr[r1] == 0 ? 0 : 1;
            }
            case 0x12 -> { // LTR
                int value = gpr[r2(insn)];
                gpr[r1(insn)] = value;
                conditionCode = compareToZero(value);
            }
            case 0x13 -> { // LCR
                int value = gpr[r2(insn)];
                gpr[r1(insn)] = -value;
                conditionCode = value == Integer.MIN_VALUE ? 3 : compareToZero(-value);
                fixedOverflow();
            }
            case 0x14 -> logical(r1(insn), gpr[r1(insn)] & gpr[r2(insn)]); // NR
            case 0x16 -> logical(r1(insn), gpr[r1(insn)] | gpr[r2(insn)]); // OR
            case 0x17 -> logical(r1(insn), gpr[r1(insn)] ^ gpr[r2(insn)]); // XR
            case 0x15 -> conditionCode = compareUnsigned(gpr[r1(insn)], gpr[r2(insn)]); // CLR
            case 0x18 -> gpr[r1(insn)] = gpr[r2(insn)]; // LR
            case 0x19 -> conditionCode = compare(gpr[r1(insn)], gpr[r2(insn)]); // CR
            case 0x1A -> add(r1(insn), gpr[r2(insn)]); // AR
            case 0x1B -> subtract(r1(insn), gpr[r2(insn)]); // SR
            case 0x1C -> multiply(r1(insn), gpr[r2(insn)]); // MR
            case 0x1D -> divide(r1(insn), gpr[r2(insn)]); // DR
            case 0x1E -> addLogical(r1(insn), gpr[r2(insn)]); // ALR
            case 0x1F -> subtractLogical(r1(insn), gpr[r2(insn)]); // SLR

            // --- I 形式 ---
            case 0x0A -> throw new MachineException(MachineException.OPERATION,
                    "SVC " + (insn[1] & 0xFF) + " is not supported yet;"
                            + " operating system services come in a later increment");

            // --- RX 形式 ---
            case 0x41 -> gpr[r1(insn)] = effective(insn); // LA
            case 0x58 -> gpr[r1(insn)] = memory.getInt(effective(insn)); // L
            case 0x50 -> memory.putInt(effective(insn), gpr[r1(insn)]); // ST
            case 0x48 -> gpr[r1(insn)] = memory.getShort(effective(insn)); // LH
            case 0x40 -> memory.putShort(effective(insn), gpr[r1(insn)]); // STH
            case 0x43 -> { // IC
                int r1 = r1(insn);
                gpr[r1] = (gpr[r1] & ~0xFF) | (memory.get(effective(insn)) & 0xFF);
            }
            case 0x42 -> memory.set(effective(insn), (byte) gpr[r1(insn)]); // STC
            case 0x5A -> add(r1(insn), memory.getInt(effective(insn))); // A
            case 0x5B -> subtract(r1(insn), memory.getInt(effective(insn))); // S
            case 0x4A -> add(r1(insn), memory.getShort(effective(insn))); // AH
            case 0x4B -> subtract(r1(insn), memory.getShort(effective(insn))); // SH
            case 0x5C -> multiply(r1(insn), memory.getInt(effective(insn))); // M
            case 0x4C -> gpr[r1(insn)] = gpr[r1(insn)] * memory.getShort(effective(insn)); // MH
            case 0x5D -> divide(r1(insn), memory.getInt(effective(insn))); // D
            case 0x59 -> conditionCode = compare(gpr[r1(insn)], memory.getInt(effective(insn))); // C
            case 0x49 -> conditionCode = compare(gpr[r1(insn)], memory.getShort(effective(insn))); // CH
            case 0x55 -> conditionCode = // CL
                    compareUnsigned(gpr[r1(insn)], memory.getInt(effective(insn)));
            case 0x54 -> logical(r1(insn), gpr[r1(insn)] & memory.getInt(effective(insn))); // N
            case 0x56 -> logical(r1(insn), gpr[r1(insn)] | memory.getInt(effective(insn))); // O
            case 0x57 -> logical(r1(insn), gpr[r1(insn)] ^ memory.getInt(effective(insn))); // X
            case 0x5E -> addLogical(r1(insn), memory.getInt(effective(insn))); // AL
            case 0x5F -> subtractLogical(r1(insn), memory.getInt(effective(insn))); // SL
            case 0x47 -> { // BC
                if (selected(r1(insn))) {
                    instructionAddress = effective(insn);
                }
            }
            case 0x45 -> { // BAL
                int target = effective(insn);
                gpr[r1(insn)] = instructionAddress;
                instructionAddress = target;
            }
            case 0x4D -> { // BAS
                int target = effective(insn);
                gpr[r1(insn)] = instructionAddress;
                instructionAddress = target;
            }
            case 0x46 -> { // BCT
                int r1 = r1(insn);
                int target = effective(insn);
                gpr[r1] = gpr[r1] - 1;
                if (gpr[r1] != 0) {
                    instructionAddress = target;
                }
            }
            case 0x4E -> convertToDecimal(insn); // CVD
            case 0x4F -> convertToBinary(insn); // CVB
            case 0x44 -> execute(insn, executed); // EX

            // --- RS 形式 ---
            case 0x98 -> loadMultiple(insn); // LM
            case 0x90 -> storeMultiple(insn); // STM
            case 0x89 -> gpr[r1(insn)] = gpr[r1(insn)] << shift(insn); // SLL
            case 0x88 -> gpr[r1(insn)] = gpr[r1(insn)] >>> shift(insn); // SRL
            case 0x8B -> shiftLeftArithmetic(insn); // SLA
            case 0x8A -> { // SRA
                int r1 = r1(insn);
                gpr[r1] = gpr[r1] >> shift(insn);
                conditionCode = compareToZero(gpr[r1]);
            }
            case 0x8D -> shiftDoubleLogical(insn, true); // SLDL
            case 0x8C -> shiftDoubleLogical(insn, false); // SRDL
            case 0x86 -> branchOnIndex(insn, true); // BXH
            case 0x87 -> branchOnIndex(insn, false); // BXLE

            // --- SI 形式 ---
            case 0x92 -> memory.set(effectiveSi(insn), insn[1]); // MVI
            case 0x95 -> conditionCode = // CLI
                    compareUnsigned(memory.get(effectiveSi(insn)) & 0xFF, insn[1] & 0xFF);
            case 0x94 -> immediate(insn, (a, b) -> (byte) (a & b)); // NI
            case 0x96 -> immediate(insn, (a, b) -> (byte) (a | b)); // OI
            case 0x97 -> immediate(insn, (a, b) -> (byte) (a ^ b)); // XI
            case 0x91 -> testUnderMask(insn); // TM

            // --- SS 形式 (長さ 1 つ) ---
            case 0xD2 -> move(insn); // MVC
            case 0xD1 -> moveNibble(insn, 0x0F); // MVN
            case 0xD3 -> moveNibble(insn, 0xF0); // MVZ
            case 0xD4 -> storageLogical(insn, (a, b) -> (byte) (a & b)); // NC
            case 0xD6 -> storageLogical(insn, (a, b) -> (byte) (a | b)); // OC
            case 0xD7 -> storageLogical(insn, (a, b) -> (byte) (a ^ b)); // XC
            case 0xD5 -> compareLogicalCharacters(insn); // CLC
            case 0xDC -> translate(insn); // TR

            // --- SS 形式 (10 進命令) ---
            case 0xF2 -> pack(insn); // PACK
            case 0xF3 -> unpack(insn); // UNPK
            case 0xF8 -> zeroAndAdd(insn); // ZAP
            case 0xFA -> decimalAdd(insn, false); // AP
            case 0xFB -> decimalAdd(insn, true); // SP
            case 0xF9 -> decimalCompare(insn); // CP
            case 0xFC -> decimalMultiply(insn); // MP
            case 0xFD -> decimalDivide(insn); // DP
            case 0xF0 -> shiftAndRound(insn); // SRP
            case 0xF1 -> moveWithOffset(insn); // MVO
            case 0xDD -> translateAndTest(insn); // TRT
            case 0xDE -> edit(insn, false); // ED
            case 0xDF -> edit(insn, true); // EDMK
            case 0x8F -> shiftDoubleArithmetic(insn, true); // SLDA
            case 0x8E -> shiftDoubleArithmetic(insn, false); // SRDA
            case 0x04 -> { // SPM
                int value = gpr[r1(insn)];
                conditionCode = (value >>> 28) & 0x3;
                programMask = (value >>> 24) & 0xF;
            }
            case 0xB2 -> { // 2 バイトの命令コードの族。持つのは IPM だけである
                if ((insn[1] & 0xFF) != 0x22) {
                    throw new MachineException(MachineException.OPERATION, String.format(
                            "operation code B2%02X is not implemented", insn[1] & 0xFF));
                }
                // IPM: 第 1 演算項の 32〜39 ビットを 0・条件コード・プログラムマスクにする。
                // 40〜63 ビットは変えない
                int r1 = (insn[3] >> 4) & 0x0F;
                gpr[r1] = (gpr[r1] & 0x00FFFFFF) | (conditionCode << 28) | (programMask << 24);
            }

            default -> throw new MachineException(MachineException.OPERATION,
                    String.format("operation code %02X is not implemented", opcode));
        }
    }

    // --- 命令語の欄 ---

    private static int r1(byte[] insn) {
        return (insn[1] >> 4) & 0x0F;
    }

    private static int r2(byte[] insn) {
        return insn[1] & 0x0F;
    }

    private static int r3(byte[] insn) {
        return insn[1] & 0x0F;
    }

    /** RX 形式の実効番地。ベースと指標が 0 番なら足さない。 */
    private int effective(byte[] insn) {
        int index = insn[1] & 0x0F;
        int base = (insn[2] >> 4) & 0x0F;
        int displacement = ((insn[2] & 0x0F) << 8) | (insn[3] & 0xFF);
        int address = displacement;
        if (base != 0) {
            address += gpr[base];
        }
        if (index != 0) {
            address += gpr[index];
        }
        return address & 0x7FFFFFFF;
    }

    /** RS / SI 形式の実効番地 (指標を持たない)。 */
    private int effectiveSi(byte[] insn) {
        int base = (insn[2] >> 4) & 0x0F;
        int displacement = ((insn[2] & 0x0F) << 8) | (insn[3] & 0xFF);
        return (base == 0 ? displacement : gpr[base] + displacement) & 0x7FFFFFFF;
    }

    /** SS 形式の第 1 演算項の番地。 */
    private int ssFirst(byte[] insn) {
        int base = (insn[2] >> 4) & 0x0F;
        int displacement = ((insn[2] & 0x0F) << 8) | (insn[3] & 0xFF);
        return (base == 0 ? displacement : gpr[base] + displacement) & 0x7FFFFFFF;
    }

    /** SS 形式の第 2 演算項の番地。 */
    private int ssSecond(byte[] insn) {
        int base = (insn[4] >> 4) & 0x0F;
        int displacement = ((insn[4] & 0x0F) << 8) | (insn[5] & 0xFF);
        return (base == 0 ? displacement : gpr[base] + displacement) & 0x7FFFFFFF;
    }

    /** シフト量は実効番地の下位 6 ビットである。 */
    private int shift(byte[] insn) {
        return effectiveSi(insn) & 0x3F;
    }

    private boolean selected(int mask) {
        return (mask & (8 >> conditionCode)) != 0;
    }

    // --- 2 進算術 ---

    private static int compare(int left, int right) {
        return left == right ? 0 : left < right ? 1 : 2;
    }

    private static int compareUnsigned(int left, int right) {
        int c = Integer.compareUnsigned(left, right);
        return c == 0 ? 0 : c < 0 ? 1 : 2;
    }

    private static int compareToZero(int value) {
        return value == 0 ? 0 : value < 0 ? 1 : 2;
    }

    /**
     * 固定小数点の加算。あふれたら条件コード 3 を立てる。
     *
     * <p>PSW の固定小数点オーバーフローマスクが立っていれば、結果を置いたあとでプログラム
     * 割込み (S0C8) になる。既定のマスクは立っていない ({@link #DEFAULT_PROGRAM_MASK})。
     */
    private void add(int r1, int value) {
        long result = (long) gpr[r1] + value;
        gpr[r1] = (int) result;
        conditionCode = result != (int) result ? 3 : compareToZero((int) result);
        fixedOverflow();
    }

    private void subtract(int r1, int value) {
        long result = (long) gpr[r1] - value;
        gpr[r1] = (int) result;
        conditionCode = result != (int) result ? 3 : compareToZero((int) result);
        fixedOverflow();
    }

    /** 条件コード 3 のあふれを、マスクが立っていればプログラム割込みにする。 */
    private void fixedOverflow() {
        if (conditionCode == 3 && (programMask & MASK_FIXED_OVERFLOW) != 0) {
            throw new MachineException(MachineException.FIXED_OVERFLOW, "fixed-point overflow");
        }
    }

    private void addLogical(int r1, int value) {
        long result = Integer.toUnsignedLong(gpr[r1]) + Integer.toUnsignedLong(value);
        gpr[r1] = (int) result;
        boolean carry = (result & 0x100000000L) != 0;
        conditionCode = gpr[r1] == 0 ? (carry ? 2 : 0) : (carry ? 3 : 1);
    }

    private void subtractLogical(int r1, int value) {
        long result = Integer.toUnsignedLong(gpr[r1]) + Integer.toUnsignedLong(~value) + 1;
        gpr[r1] = (int) result;
        boolean carry = (result & 0x100000000L) != 0;
        conditionCode = gpr[r1] == 0 ? (carry ? 2 : 0) : (carry ? 3 : 1);
    }

    private void logical(int r1, int result) {
        gpr[r1] = result;
        conditionCode = result == 0 ? 0 : 1;
    }

    /** 積は偶数・奇数の対に入る。第 1 演算項は偶数でなければならない。 */
    private void multiply(int r1, int value) {
        requireEven(r1, "M");
        long product = (long) gpr[r1 + 1] * value;
        gpr[r1] = (int) (product >> 32);
        gpr[r1 + 1] = (int) product;
    }

    /** 被除数は偶数・奇数の対の 64 ビットである。商は奇数側、剰余は偶数側に入る。 */
    private void divide(int r1, int value) {
        requireEven(r1, "D");
        if (value == 0) {
            throw new MachineException(MachineException.FIXED_DIVIDE, "fixed-point division by zero");
        }
        long dividend = ((long) gpr[r1] << 32) | Integer.toUnsignedLong(gpr[r1 + 1]);
        long quotient = dividend / value;
        if (quotient != (int) quotient) {
            throw new MachineException(MachineException.FIXED_DIVIDE,
                    "the fixed-point quotient does not fit in 32 bits");
        }
        gpr[r1] = (int) (dividend % value);
        gpr[r1 + 1] = (int) quotient;
    }

    private void requireEven(int r1, String mnemonic) {
        if (r1 % 2 != 0) {
            throw new MachineException(MachineException.SPECIFICATION,
                    mnemonic + " requires an even register but R" + r1 + " was given");
        }
    }

    private void shiftLeftArithmetic(byte[] insn) {
        int r1 = r1(insn);
        int amount = shift(insn);
        int value = gpr[r1];
        long shifted = (long) value << amount;
        // 符号と違うビットが押し出されたらあふれである。符号ビットそのものは動かない
        boolean overflow = amount >= 32 ? value != 0 && value != -1 : shifted != (int) shifted;
        int signed = (int) (shifted & 0x7FFFFFFF) | (value & 0x80000000);
        gpr[r1] = signed;
        conditionCode = overflow ? 3 : compareToZero(signed);
        fixedOverflow();
    }

    private void shiftDoubleLogical(byte[] insn, boolean left) {
        int r1 = r1(insn);
        requireEven(r1, left ? "SLDL" : "SRDL");
        int amount = shift(insn);
        long pair = ((long) gpr[r1] << 32) | Integer.toUnsignedLong(gpr[r1 + 1]);
        long result = left ? pair << amount : pair >>> amount;
        gpr[r1] = (int) (result >> 32);
        gpr[r1 + 1] = (int) result;
    }

    private void branchOnIndex(byte[] insn, boolean high) {
        int r1 = r1(insn);
        int r3 = r3(insn);
        int target = effectiveSi(insn);
        int increment = gpr[r3];
        // 比べる相手は、R3 が偶数ならその次のレジスタ、奇数なら R3 自身である
        int comparand = gpr[r3 % 2 == 0 ? r3 + 1 : r3];
        gpr[r1] = gpr[r1] + increment;
        if (high ? gpr[r1] > comparand : gpr[r1] <= comparand) {
            instructionAddress = target;
        }
    }

    private void loadMultiple(byte[] insn) {
        int address = effectiveSi(insn);
        for (int r = r1(insn); ; r = (r + 1) % REGISTERS) {
            gpr[r] = memory.getInt(address);
            address += 4;
            if (r == r3(insn)) {
                return;
            }
        }
    }

    private void storeMultiple(byte[] insn) {
        int address = effectiveSi(insn);
        for (int r = r1(insn); ; r = (r + 1) % REGISTERS) {
            memory.putInt(address, gpr[r]);
            address += 4;
            if (r == r3(insn)) {
                return;
            }
        }
    }

    // --- SI 形式 ---

    @FunctionalInterface
    private interface ByteOperation {
        byte apply(byte left, byte right);
    }

    private void immediate(byte[] insn, ByteOperation operation) {
        int address = effectiveSi(insn);
        byte result = operation.apply(memory.get(address), insn[1]);
        memory.set(address, result);
        conditionCode = result == 0 ? 0 : 1;
    }

    /**
     * マスクで選んだビットを調べる。
     * すべて 0 なら 0、混ざっていれば 1、すべて 1 なら 3 である。マスクが 0 なら 0 とする。
     */
    private void testUnderMask(byte[] insn) {
        int value = memory.get(effectiveSi(insn)) & 0xFF;
        int mask = insn[1] & 0xFF;
        int selected = value & mask;
        conditionCode = mask == 0 || selected == 0 ? 0 : selected == mask ? 3 : 1;
    }

    // --- SS 形式 (文字) ---

    /** SS-a 形式の長さ。欄には 1 を引いた値が入っている。 */
    private static int ssLength(byte[] insn) {
        return (insn[1] & 0xFF) + 1;
    }

    /**
     * 文字転記。
     *
     * <p>重なった領域は<b>1 バイトずつ左から</b>写す。これは意味論の一部である。
     * {@code MVC X+1(n),X} で 1 バイトを領域いっぱいに広げる書き方が、この順に依存している。
     */
    private void move(byte[] insn) {
        int to = ssFirst(insn);
        int from = ssSecond(insn);
        int length = ssLength(insn);
        for (int k = 0; k < length; k++) {
            memory.set(to + k, memory.get(from + k));
        }
    }

    private void moveNibble(byte[] insn, int mask) {
        int to = ssFirst(insn);
        int from = ssSecond(insn);
        int length = ssLength(insn);
        for (int k = 0; k < length; k++) {
            int target = memory.get(to + k) & 0xFF;
            int source = memory.get(from + k) & 0xFF;
            memory.set(to + k, (byte) ((target & ~mask) | (source & mask)));
        }
    }

    private void storageLogical(byte[] insn, ByteOperation operation) {
        int to = ssFirst(insn);
        int from = ssSecond(insn);
        int length = ssLength(insn);
        int nonZero = 0;
        for (int k = 0; k < length; k++) {
            byte result = operation.apply(memory.get(to + k), memory.get(from + k));
            memory.set(to + k, result);
            nonZero |= result & 0xFF;
        }
        conditionCode = nonZero == 0 ? 0 : 1;
    }

    private void compareLogicalCharacters(byte[] insn) {
        int left = ssFirst(insn);
        int right = ssSecond(insn);
        int length = ssLength(insn);
        for (int k = 0; k < length; k++) {
            int a = memory.get(left + k) & 0xFF;
            int b = memory.get(right + k) & 0xFF;
            if (a != b) {
                conditionCode = a < b ? 1 : 2;
                return;
            }
        }
        conditionCode = 0;
    }

    /** 各バイトの値を添字として、第 2 演算項の 256 バイトの表から引いた値で置き換える。 */
    private void translate(byte[] insn) {
        int target = ssFirst(insn);
        int table = ssSecond(insn);
        int length = ssLength(insn);
        for (int k = 0; k < length; k++) {
            int index = memory.get(target + k) & 0xFF;
            memory.set(target + k, memory.get(table + index));
        }
    }

    // --- SS 形式 (10 進) ---

    private static int ssLength1(byte[] insn) {
        return ((insn[1] >> 4) & 0x0F) + 1;
    }

    private static int ssLength2(byte[] insn) {
        return (insn[1] & 0x0F) + 1;
    }

    /** ゾーン 10 進をパック 10 進へ。右から詰め、最後のバイトのゾーンが符号になる。 */
    private void pack(byte[] insn) {
        byte[] source = memory.read(ssSecond(insn), ssLength2(insn));
        byte[] out = new byte[ssLength1(insn)];
        int last = source.length - 1;
        out[out.length - 1] = (byte) (((source[last] & 0x0F) << 4) | ((source[last] >> 4) & 0x0F));
        int nibble = 1;
        for (int k = last - 1; k >= 0; k--) {
            int digit = source[k] & 0x0F;
            int index = out.length - 1 - (nibble + 1) / 2;
            if (index < 0) {
                break;
            }
            out[index] |= (byte) (nibble % 2 == 1 ? digit : digit << 4);
            nibble++;
        }
        memory.write(ssFirst(insn), out);
    }

    /** パック 10 進をゾーン 10 進へ。最後のバイトのゾーンに符号が移る。 */
    private void unpack(byte[] insn) {
        byte[] source = memory.read(ssSecond(insn), ssLength2(insn));
        byte[] out = new byte[ssLength1(insn)];
        int zone = 0xF0;
        int last = source.length - 1;
        out[out.length - 1] = (byte) (((source[last] & 0x0F) << 4) | ((source[last] >> 4) & 0x0F));
        int nibble = 1;
        for (int k = out.length - 2; k >= 0; k--) {
            int index = source.length - 1 - (nibble + 1) / 2;
            int digit = index < 0 ? 0
                    : nibble % 2 == 1 ? source[index] & 0x0F : (source[index] >> 4) & 0x0F;
            out[k] = (byte) (zone | digit);
            nibble++;
        }
        memory.write(ssFirst(insn), out);
    }

    /**
     * パック 10 進として読む。
     *
     * <p>10 進数として読めないバイト列はデータ例外である。COBOL 資産が見慣れた {@code S0C7} は
     * これである。黙って 0 として読まない。
     */
    private Decimal readPacked(int address, int length) {
        byte[] bytes = memory.read(address, length);
        try {
            return PackedDecimal.decode(bytes, 0, NumProcMode.NOPFD);
        } catch (RuntimeException failure) {
            throw new MachineException(MachineException.DATA,
                    "the operand is not valid packed decimal");
        }
    }

    /**
     * パック 10 進として書く。入りきらなければ 10 進オーバーフローである。
     *
     * <p>あふれたときは、上の桁を捨てた値を置いて条件コード 3 にする。割込みになるのは
     * プログラムマスクの 10 進オーバーフローが立っているときだけである (Principles of Operation)。
     *
     * <p>符号の付け方は {@link Decimal} に委ねる。ゼロ結果の符号が命令ごとに違うという
     * 実測の結果 (暫定判断 P-001) が、そちらに入っている。
     */
    private void writePacked(int address, int length, Decimal value) {
        int digits = length * 2 - 1;
        BigInteger limit = BigInteger.TEN.pow(digits);
        boolean overflow = value.magnitude().compareTo(limit) >= 0;
        Decimal stored = overflow
                ? Decimal.of(value.magnitude().mod(limit), value.scale(), value.sign())
                : value;
        memory.write(address, PackedDecimal.encode(stored, digits, 0, true));
        conditionCode = overflow ? 3 : value.signum() == 0 ? 0 : value.signum() < 0 ? 1 : 2;
        if (overflow && (programMask & MASK_DECIMAL_OVERFLOW) != 0) {
            throw new MachineException(MachineException.DECIMAL_OVERFLOW,
                    "the decimal result does not fit in " + digits + " digit(s)");
        }
    }

    private void decimalAdd(byte[] insn, boolean subtract) {
        Decimal left = readPacked(ssFirst(insn), ssLength1(insn));
        Decimal right = readPacked(ssSecond(insn), ssLength2(insn));
        Decimal result = subtract ? left.subtract(right) : left.add(right);
        writePacked(ssFirst(insn), ssLength1(insn), result);
    }

    private void zeroAndAdd(byte[] insn) {
        Decimal value = readPacked(ssSecond(insn), ssLength2(insn));
        writePacked(ssFirst(insn), ssLength1(insn), value);
    }

    private void decimalCompare(byte[] insn) {
        Decimal left = readPacked(ssFirst(insn), ssLength1(insn));
        Decimal right = readPacked(ssSecond(insn), ssLength2(insn));
        int c = left.compareTo(right);
        conditionCode = c == 0 ? 0 : c < 0 ? 1 : 2;
    }

    /** 乗算は条件コードを変えない。 */
    private void decimalMultiply(byte[] insn) {
        Decimal left = readPacked(ssFirst(insn), ssLength1(insn));
        Decimal right = readPacked(ssSecond(insn), ssLength2(insn));
        // 被乗数は、乗数の桁数以上の 0 を左に持たなければならない。持たなければデータ例外で
        // ある (Principles of Operation)。このため MP はあふれない
        int l1 = ssLength1(insn);
        int l2 = ssLength2(insn);
        if (l2 > 8 || l2 >= l1) {
            throw new MachineException(MachineException.SPECIFICATION,
                    "the MP multiplier must be at most 8 bytes and shorter than the multiplicand");
        }
        if (left.magnitude().compareTo(BigInteger.TEN.pow(2 * (l1 - l2) - 1)) >= 0) {
            throw new MachineException(MachineException.DATA,
                    "the MP multiplicand does not have enough leading zeros");
        }
        int saved = conditionCode;
        writePacked(ssFirst(insn), ssLength1(insn), left.multiply(right));
        conditionCode = saved;
    }

    /** 商は左側、剰余は右側に入る。剰余の長さは第 2 演算項と同じである。条件コードは変わらない。 */
    private void decimalDivide(byte[] insn) {
        int first = ssFirst(insn);
        int l1 = ssLength1(insn);
        int l2 = ssLength2(insn);
        Decimal dividend = readPacked(first, l1);
        Decimal divisor = readPacked(ssSecond(insn), l2);
        if (divisor.signum() == 0) {
            throw new MachineException(MachineException.DECIMAL_DIVIDE, "decimal division by zero");
        }
        Decimal quotient = dividend.divide(divisor, 0, dev.cobolonjava.runtime.decimal.CobolRounding.TRUNCATION);
        Decimal remainder = dividend.remainder(divisor, 0);
        int quotientLength = l1 - l2;
        if (quotientLength <= 0) {
            throw new MachineException(MachineException.SPECIFICATION,
                    "the divisor is not shorter than the dividend");
        }
        if (quotient.magnitude().compareTo(BigInteger.TEN.pow(quotientLength * 2 - 1)) >= 0) {
            throw new MachineException(MachineException.DECIMAL_DIVIDE,
                    "the decimal quotient does not fit in " + quotientLength + " byte(s)");
        }
        int saved = conditionCode;
        writePacked(first, quotientLength, quotient);
        writePacked(first + quotientLength, l2, remainder);
        conditionCode = saved;
    }

    private void convertToDecimal(byte[] insn) {
        Decimal value = Decimal.of(BigInteger.valueOf(gpr[r1(insn)]), 0);
        memory.write(effective(insn), PackedDecimal.encode(value, 15, 0, true));
    }

    private void convertToBinary(byte[] insn) {
        Decimal value = readPacked(effective(insn), 8);
        BigInteger signed = value.signedUnscaled();
        if (signed.bitLength() > 31) {
            throw new MachineException(MachineException.FIXED_DIVIDE,
                    "the decimal value does not fit in 32 bits");
        }
        gpr[r1(insn)] = signed.intValue();
    }

    // --- 設計 27 §6.1 で後回しにしていた命令 (Principles of Operation の記述から) ---

    /**
     * 64 ビットの算術シフト。偶数と奇数のレジスタの対を 1 つの符号付きの数として動かす。
     *
     * <p>左シフトは符号ビットを動かさない。符号と違うビットが押し出されたら条件コード 3
     * であり、マスクが立っていれば固定小数点オーバーフローの割込みになる。
     */
    private void shiftDoubleArithmetic(byte[] insn, boolean left) {
        int r1 = r1(insn);
        requireEven(r1, left ? "SLDA" : "SRDA");
        int amount = shift(insn);
        long pair = ((long) gpr[r1] << 32) | Integer.toUnsignedLong(gpr[r1 + 1]);
        long result;
        boolean overflow = false;
        if (left) {
            long shifted = pair << amount;
            overflow = (shifted >> amount) != pair;
            result = (shifted & Long.MAX_VALUE) | (pair & Long.MIN_VALUE);
        } else {
            result = pair >> amount;
        }
        gpr[r1] = (int) (result >> 32);
        gpr[r1 + 1] = (int) result;
        conditionCode = overflow ? 3 : result == 0 ? 0 : result < 0 ? 1 : 2;
        fixedOverflow();
    }

    /**
     * 表を引いて、0 でない関数バイトを持つ最初のバイトを探す。第 1 演算項は書き換えない。
     *
     * <p>見つかれば R1 の下位 31 ビットにそのバイトの番地、R2 の下位 8 ビットに関数バイトを
     * 置く。R1 の最上位ビットと R2 の上位 24 ビットは変えない (AMODE 31)。
     */
    private void translateAndTest(byte[] insn) {
        int argument = ssFirst(insn);
        int table = ssSecond(insn);
        int length = ssLength(insn);
        for (int k = 0; k < length; k++) {
            int function = memory.get(table + (memory.get(argument + k) & 0xFF)) & 0xFF;
            if (function != 0) {
                gpr[1] = (gpr[1] & 0x80000000) | ((argument + k) & 0x7FFFFFFF);
                gpr[2] = (gpr[2] & 0xFFFFFF00) | function;
                conditionCode = k == length - 1 ? 2 : 1;
                return;
            }
        }
        conditionCode = 0;
    }

    /**
     * 第 2 演算項のニブルを、第 1 演算項の右端のニブル (符号) の左へ置く。
     *
     * <p>右から 1 バイトずつ取り出して置く。重なった領域の結果がこの順に依存するためである
     * (PACK / UNPK と同じ)。第 1 演算項の左に余った桁は 0、入りきらない桁は捨てる。
     * 中身が 10 進数として正しいかは調べない。
     */
    private void moveWithOffset(byte[] insn) {
        int to = ssFirst(insn);
        int from = ssSecond(insn);
        int l1 = ssLength1(insn);
        int l2 = ssLength2(insn);
        int previous = memory.get(to + l1 - 1) & 0x0F; // 右端のニブルは残す (初回だけ下位に使う)
        boolean first = true;
        for (int j = 0; j < l1; j++) {
            int source = j < l2 ? memory.get(from + l2 - 1 - j) & 0xFF : 0;
            int low = first ? previous : (previous >> 4) & 0x0F;
            memory.set(to + l1 - 1 - j, (byte) (((source & 0x0F) << 4) | low));
            previous = source;
            first = false;
        }
    }

    /**
     * 10 進数を桁で動かし、右へ動かすときは丸める。
     *
     * <p>シフト量は第 2 演算項の番地の下位 6 ビットであり、符号付きである (負なら右へ)。
     * 右へ動かすときは、押し出される桁の最上位に丸めの桁 I3 を足して、10 を超えたら繰り上げる。
     * I3 は 10 進の桁として正しいかを調べない (Principles of Operation がそう書いている)。
     * 0 になった結果の符号は正にする。左へ動かして有効な桁が押し出されたら条件コード 3 である。
     */
    private void shiftAndRound(byte[] insn) {
        int address = ssFirst(insn);
        int length = ssLength1(insn);
        int rounding = insn[1] & 0x0F;
        int amount = ssSecond(insn) & 0x3F;
        Decimal operand = readPacked(address, length);
        BigInteger magnitude = operand.magnitude();
        if (amount < 32) {
            magnitude = magnitude.multiply(BigInteger.TEN.pow(amount));
        } else {
            int right = 64 - amount;
            BigInteger[] split = magnitude.divideAndRemainder(BigInteger.TEN.pow(right));
            int dropped = split[1].divide(BigInteger.TEN.pow(right - 1)).intValue();
            magnitude = split[0];
            if (dropped + rounding >= 10) {
                magnitude = magnitude.add(BigInteger.ONE);
            }
        }
        int sign = magnitude.signum() == 0 ? 1 : operand.sign();
        writePacked(address, length, Decimal.of(magnitude, 0, sign));
    }

    /**
     * 模様 (第 1 演算項) に従って、パック 10 進 (第 2 演算項) を文字に直して模様に上書きする。
     *
     * <p>模様の最初のバイトが埋め字になる。X'20' は桁の選択、X'21' は有効数字の開始、X'22' は
     * 欄の区切りであり、ほかは文字としてそのまま残すか埋め字に置き換える。有効数字の表示
     * (significance indicator) が立っているかどうかで決まる。元のバイトの右ニブルが正の符号なら、
     * その桁を置いたあとで表示を下ろす。負の符号なら下ろさない。{@code CR} や {@code -} を
     * 負のときだけ残す定石は、これに依存している。
     *
     * <p>条件コードは最後の欄の値で決まる。すべて 0 なら 0、0 でなく表示が立ったまま終われば
     * 1 (負)、下りていれば 2 (正)。EDMK は、表示が下りていて 0 でない桁が来たとき、その結果の
     * バイトの番地を R1 の下位 31 ビットに置く。
     */
    private void edit(byte[] insn, boolean markFirst) {
        int pattern = ssFirst(insn);
        int source = ssSecond(insn);
        int length = ssLength(insn);
        int fill = memory.get(pattern) & 0xFF;
        boolean significance = false;
        boolean nonZero = false;
        boolean rightHalf = false; // 次の桁を、いま読んでいるバイトの右ニブルから取るか
        int current = 0;
        for (int k = 0; k < length; k++) {
            int p = memory.get(pattern + k) & 0xFF;
            int result;
            if (p == 0x20 || p == 0x21) {
                int digit;
                boolean fromLeft = !rightHalf;
                if (rightHalf) {
                    digit = current & 0x0F;
                    rightHalf = false;
                } else {
                    current = memory.get(source++) & 0xFF;
                    digit = (current >> 4) & 0x0F;
                    if (digit > 9) {
                        throw new MachineException(MachineException.DATA,
                                "the ED source has a sign where a digit is required");
                    }
                    int right = current & 0x0F;
                    rightHalf = right <= 9;
                }
                if (digit != 0 || significance) {
                    if (!significance && markFirst) {
                        gpr[1] = (gpr[1] & 0x80000000) | ((pattern + k) & 0x7FFFFFFF);
                    }
                    result = 0xF0 | digit;
                    significance = true;
                } else {
                    result = fill;
                }
                nonZero |= digit != 0;
                if (p == 0x21) {
                    significance = true;
                }
                // 桁を取ったバイトの右ニブルが符号なら、ここで表示を決める
                if (fromLeft && (current & 0x0F) > 9) {
                    int sign = current & 0x0F;
                    if (sign != 0x0B && sign != 0x0D) {
                        significance = false;
                    }
                }
            } else if (p == 0x22) {
                result = fill;
                significance = false;
                nonZero = false;
            } else {
                result = significance ? p : fill;
            }
            memory.set(pattern + k, (byte) result);
        }
        conditionCode = !nonZero ? 0 : significance ? 1 : 2;
    }

    // --- EX ---

    /**
     * 対象の命令の 2 バイト目に R1 の下位 8 ビットを重ねて実行する。
     *
     * <p>長さが実行時に決まる {@code MVC} の定石である。これが JVM のバイトコードへ
     * 素直に写せない理由の 1 つであり、命令インタプリタならそのまま扱える。
     */
    private void execute(byte[] insn, boolean executed) {
        if (executed) {
            throw new MachineException(MachineException.EXECUTE, "EX cannot execute another EX");
        }
        int r1 = r1(insn);
        int target = effective(insn);
        int opcode = memory.get(target) & 0xFF;
        byte[] subject = memory.read(target, lengthOf(opcode));
        if (subject[0] == 0x44) {
            throw new MachineException(MachineException.EXECUTE, "EX cannot execute another EX");
        }
        if (r1 != 0) {
            subject[1] = (byte) (subject[1] | (gpr[r1] & 0xFF));
        }
        perform(subject, true);
    }
}
