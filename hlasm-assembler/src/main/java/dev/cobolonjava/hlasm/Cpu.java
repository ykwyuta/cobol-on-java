package dev.cobolonjava.hlasm;

import dev.cobolonjava.runtime.data.NumProcMode;
import dev.cobolonjava.runtime.data.PackedDecimal;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;

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
    /**
     * 汎用レジスタの上位 32 ビット。
     *
     * <p>z/Architecture のレジスタは 64 ビットである。32 ビットの命令は下位だけを読み書きし、
     * 上位には触らない。だから下位を今までどおり {@link #gpr} に置き、上位を別に持つ。
     * こうしておけば、Hercules で裏づけた 32 ビットの命令の実装に手を入れずに済む。
     */
    private final int[] high = new int[REGISTERS];
    /**
     * 番地の幅。SAM24 で 24 ビット、SAM31 で 31 ビットになる。実効番地と分岐先をこれで切る。
     * 24 ビットのときの BAL / BALR の連絡情報 (上位バイトに命令長・条件コード・マスクを置く) は
     * 持たない。番地だけを置く。実機と突き合わせていない。
     */
    private int addressMask = 0x7FFFFFFF;
    /** 浮動小数点のレジスターと FPC。 */
    private final FloatingUnit floating = new FloatingUnit(this);

    /** この CPU から外へ出る口 (外の記号への呼び出しと SVC)。無ければ {@code null}。 */
    private Services services;

    /**
     * 機械の外にあるもの。機械語だけでは片づかない 2 つである。
     */
    public interface Services {

        /** 外の記号の入口へ分岐した。名前のプログラムを呼び、戻りコードを R15 に置く。 */
        void call(String name, Cpu cpu);

        /** SVC 命令。 */
        void supervisorCall(int number, Cpu cpu);
    }

    public void setServices(Services value) {
        services = value;
    }

    /** 64 ビットのレジスタの値。 */
    public long register64(int index) {
        return (long) high[index] << 32 | (gpr[index] & 0xFFFFFFFFL);
    }

    public void setRegister64(int index, long value) {
        high[index] = (int) (value >>> 32);
        gpr[index] = (int) value;
    }

    public void setConditionCode(int value) {
        conditionCode = value & 3;
    }

    /** 次に実行する命令の番地を変える。サービスが出口 (EODAD など) へ飛ばすのに使う。 */
    public void branchTo(int address) {
        instructionAddress = address & 0x7FFFFFFF;
    }
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
    private int operationAddress;
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
            String external = services == null ? null : memory.externalAt(at);
            if (external != null) {
                // 外の記号の入口。機械語は無い。名前で呼び、R14 へ戻る
                int back = gpr[14] & 0x7FFFFFFF;
                services.call(external, this);
                instructionAddress = back;
                continue;
            }
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
        operationAddress = at;
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
                    instructionAddress = target & addressMask;
                }
            }
            case 0x0D -> { // BASR
                int r1 = r1(insn);
                int r2 = r2(insn);
                int target = gpr[r2];
                gpr[r1] = instructionAddress;
                if (r2 != 0) {
                    instructionAddress = target & addressMask;
                }
            }
            case 0x0E, 0x0F -> longString(insn, opcode == 0x0E);
            case 0x06 -> { // BCTR
                int r1 = r1(insn);
                int r2 = r2(insn);
                gpr[r1] = gpr[r1] - 1;
                if (gpr[r1] != 0 && r2 != 0) {
                    instructionAddress = gpr[r2] & addressMask;
                }
            }
            case 0x07 -> { // BCR
                int mask = r1(insn);
                int r2 = r2(insn);
                if (r2 != 0 && selected(mask)) {
                    instructionAddress = gpr[r2] & addressMask;
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
            case 0x0A -> {
                if (services == null) {
                    throw new MachineException(MachineException.OPERATION,
                            "SVC " + (insn[1] & 0xFF) + " is not supported yet:"
                                    + " this CPU runs without an operating system service");
                }
                services.supervisorCall(insn[1] & 0xFF, this);
            }
            case 0xB9 -> registerRegister64(insn);
            // --- 浮動小数点 ---
            case 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B,
                    0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35,
                    0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F ->
                    floating.registerRegister(insn);
            case 0x60, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x6F,
                    0x70, 0x78, 0x79, 0x7A, 0x7B, 0x7C, 0x7D, 0x7E, 0x7F ->
                    floating.registerStorage(insn, effective(insn));
            case 0xB3 -> floating.extended(insn);
            case 0xED -> floating.binaryStorage(insn, effective(insn));
            case 0x01 -> {
                switch (insn[1] & 0xFF) {
                    case 0x0A -> floating.performOperation(); // PFPO
                    case 0x0C -> addressMask = 0x00FFFFFF; // SAM24
                    case 0x0D -> addressMask = 0x7FFFFFFF; // SAM31
                    // SAM64 は断る。番地空間が 31 ビットまでしか無い
                    default -> throw new MachineException(MachineException.OPERATION,
                            String.format("operation code 01%02X is not implemented",
                                    insn[1] & 0xFF));
                }
            }
            case 0xA7 -> registerImmediate(insn);
            case 0xA5 -> registerHalfwordImmediate(insn);
            case 0xC0 -> registerImmediateLong(insn);
            case 0xC6 -> {
                if ((insn[1] & 0xF) != 0) {
                    throw new MachineException(MachineException.OPERATION, "unsupported C6 operation");
                }
                execute(r1(insn), relativeTarget(immediateWord(insn)), executed);
            }
            case 0xCC -> {
                if ((insn[1] & 0xF) != 6) {
                    throw new MachineException(MachineException.OPERATION, "unsupported CC operation");
                }
                if (--high[r1(insn)] != 0) { // BRCTH
                    instructionAddress = relativeTarget(immediateWord(insn));
                }
            }
            case 0xC2 -> {
                int r1 = r1(insn);
                switch (insn[1] & 0x0F) {
                    case 8 -> add64(r1, register64(r1), immediateWord(insn)); // AGFI
                    case 12 -> conditionCode = compare64(register64(r1), immediateWord(insn)); // CGFI
                    default -> throw new MachineException(MachineException.OPERATION, "unimplemented C2 operation");
                }
            }
            case 0xE3 -> registerStorage64(insn);
            case 0xEB -> registerStorageExtended(insn);
            case 0xEC -> {
                int operation = insn[5] & 0xFF;
                if (operation == 0x42 || operation == 0x46 || operation == 0x4E) {
                    if (selected(r2(insn))) {
                        int value = (short) ((insn[2] & 0xFF) << 8 | insn[3] & 0xFF);
                        conditionalLoad(operation, r1(insn), value);
                    }
                } else {
                    rotateThenInsert(insn);
                }
            }
            case 0xBF -> insertUnderMask(r1(insn), r3(insn), effectiveSi(insn), false); // ICM
            case 0xBE -> { // STCM
                int address = effectiveSi(insn);
                int mask = r3(insn);
                for (int bit = 0; bit < 4; bit++) {
                    if ((mask & (8 >> bit)) != 0) {
                        memory.set(address++, (byte) (gpr[r1(insn)] >>> (24 - 8 * bit)));
                    }
                }
            }
            case 0xBD -> { // CLM
                int address = effectiveSi(insn);
                int mask = r3(insn);
                int result = 0;
                for (int bit = 0; bit < 4 && result == 0; bit++) {
                    if ((mask & (8 >> bit)) != 0) {
                        result = compareUnsigned((gpr[r1(insn)] >>> (24 - 8 * bit)) & 0xFF,
                                memory.get(address++) & 0xFF);
                    }
                }
                conditionCode = result;
            }
            case 0xBA -> compareAndSwap(r1(insn), r3(insn), effectiveSi(insn), 4, false); // CS
            case 0xBB -> compareAndSwap(r1(insn), r3(insn), effectiveSi(insn), 4, true); // CDS
            case 0x93 -> { // TS
                int address = effectiveSi(insn);
                synchronized (memory) {
                    conditionCode = memory.get(address) < 0 ? 1 : 0;
                    memory.set(address, (byte) 0xFF);
                }
            }
            case 0xC8 -> {
                if ((insn[1] & 0xF) == 2) {
                    compareSwapAndStore(r1(insn), ssFirst(insn), ssSecond(insn));
                } else {
                    loadDisjoint(insn);
                }
            }

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
            case 0x44 -> execute(r1(insn), effective(insn), executed); // EX

            // --- RS 形式 ---
            case 0x98 -> transferWords(r1(insn), r3(insn), effectiveSi(insn), true); // LM
            case 0x90 -> transferWords(r1(insn), r3(insn), effectiveSi(insn), false); // STM
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
            case 0xB2 -> { // 2 バイトの命令コードの族
                int operation = insn[1] & 0xFF;
                if (operation == 0x55 || operation == 0x5D || operation == 0x5E) {
                    terminatedString(insn, operation);
                    return;
                }
                if (floating.control(insn, effectiveSi(insn))) {
                    return;
                }
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

    // --- 64 ビットの命令 ---
    //
    // 意味は z/Architecture Principles of Operation (SA22-7832) の各命令の記述に従う。
    // 命令の並びは Zeus (同じ権利者の処理系) の hlasm/execute.rs を見て決めた。
    // <b>Hercules とは突き合わせていない</b> (32 ビットの命令は突き合わせてある)。

    private static int compare64(long left, long right) {
        return left == right ? 0 : left < right ? 1 : 2;
    }

    private static int compareUnsigned64(long left, long right) {
        int c = Long.compareUnsigned(left, right);
        return c == 0 ? 0 : c < 0 ? 1 : 2;
    }

    /** 64 ビットの符号つき加算。あふれたら条件コード 3。 */
    private void add64(int r1, long left, long right) {
        long result = left + right;
        setRegister64(r1, result);
        boolean overflow = ((left ^ result) & (right ^ result)) < 0;
        conditionCode = overflow ? 3 : compare64(result, 0);
        fixedOverflow();
    }

    private void subtract64(int r1, long left, long right) {
        long result = left - right;
        setRegister64(r1, result);
        boolean overflow = ((left ^ right) & (left ^ result)) < 0;
        conditionCode = overflow ? 3 : compare64(result, 0);
        fixedOverflow();
    }

    /** 64 ビットの論理加算。条件コードは「結果が 0 か」と「繰り上がったか」で決まる。 */
    private void addLogical64(int r1, long left, long right, int carryIn) {
        long partial = left + right;
        boolean carry = Long.compareUnsigned(partial, left) < 0;
        long result = partial + carryIn;
        carry |= carryIn != 0 && result == 0;
        setRegister64(r1, result);
        conditionCode = result == 0 ? (carry ? 2 : 0) : (carry ? 3 : 1);
    }

    private void logical64(int r1, long result) {
        setRegister64(r1, result);
        conditionCode = result == 0 ? 0 : 1;
    }

    /**
     * 長さの上位 8 ビットは保存する。比較では不一致位置に番地を残し、破壊的な重なりの
     * ある転送では記憶域もレジスターも更新しない (Zeus hlasm/execute/long.rs)。
     */
    private void longString(byte[] insn, boolean move) {
        int r1 = r1(insn);
        int r2 = r2(insn);
        if (((r1 | r2) & 1) != 0) {
            throw new MachineException(MachineException.SPECIFICATION, "long strings require even registers");
        }
        int a = gpr[r1] & addressMask;
        int b = gpr[r2] & addressMask;
        int n1 = gpr[r1 + 1] & 0xFFFFFF;
        int n2 = gpr[r2 + 1] & 0xFFFFFF;
        int pad = gpr[r2 + 1] >>> 24;
        if (move) {
            int copied = Math.min(n1, n2);
            if (copied != 0 && a > b && a - b < copied) {
                conditionCode = 3;
                return;
            }
            conditionCode = compare(n1, n2);
            for (int i = 0; i < n1; i++) {
                int value = i < copied ? memory.get((b + i) & addressMask) : pad;
                memory.set((a + i) & addressMask, (byte) value);
            }
            a += n1;
            b += copied;
            n1 = 0;
            n2 -= copied;
        } else {
            conditionCode = 0;
            while (n1 != 0 || n2 != 0) {
                int left = n1 != 0 ? memory.get(a) & 0xFF : pad;
                int right = n2 != 0 ? memory.get(b) & 0xFF : pad;
                if (left != right) {
                    conditionCode = compare(left, right);
                    break;
                }
                if (n1 != 0) {
                    a = (a + 1) & addressMask;
                    n1--;
                }
                if (n2 != 0) {
                    b = (b + 1) & addressMask;
                    n2--;
                }
            }
        }
        gpr[r1] = a & addressMask;
        gpr[r2] = b & addressMask;
        gpr[r1 + 1] = (gpr[r1 + 1] & 0xFF000000) | n1;
        gpr[r2 + 1] = (gpr[r2 + 1] & 0xFF000000) | n2;
    }

    /** 区切り文字付きの命令は 4096 バイトごとに CC=3 で継続できる。 */
    private void terminatedString(byte[] insn, int operation) {
        if ((gpr[0] & 0xFFFFFF00) != 0) {
            throw new MachineException(MachineException.SPECIFICATION, "invalid string terminator");
        }
        int stop = gpr[0] & 0xFF;
        int r1 = (insn[3] >>> 4) & 0xF;
        int r2 = insn[3] & 0xF;
        int a = gpr[r1] & addressMask;
        int b = gpr[r2] & addressMask;
        boolean search = operation == 0x5E;
        for (int i = 0; i < 4096; i++) {
            if (search && a == b) {
                conditionCode = 2;
                return;
            }
            int right = memory.get(b) & 0xFF;
            if (search) {
                if (right == stop) {
                    gpr[r1] = b;
                    conditionCode = 1;
                    return;
                }
            } else if (operation == 0x55) { // MVST
                memory.set(a, (byte) right);
                if (right == stop) {
                    gpr[r1] = a;
                    conditionCode = 1;
                    return;
                }
            } else { // CLST
                int left = memory.get(a) & 0xFF;
                if (left == stop && right == stop) {
                    conditionCode = 0;
                    return;
                }
                if (left != right) {
                    conditionCode = left == stop ? 1 : right == stop ? 2 : compare(left, right);
                    gpr[r1] = a;
                    gpr[r2] = b;
                    return;
                }
            }
            a = (a + 1) & addressMask;
            b = (b + 1) & addressMask;
        }
        if (!search) {
            gpr[r1] = a;
        }
        gpr[r2] = b;
        conditionCode = 3;
    }

    /** B9xx。演算項は 2 つのレジスタ (3 つ目を持つものは 3 バイト目の上位)。 */
    private void registerRegister64(byte[] insn) {
        int r1 = (insn[3] >> 4) & 0x0F;
        int r2 = insn[3] & 0x0F;
        int r3 = (insn[2] >> 4) & 0x0F;
        long a = register64(r1);
        long b = register64(r2);
        switch (insn[1] & 0xFF) {
            case 0x04 -> setRegister64(r1, b); // LGR
            case 0x02 -> { // LTGR
                setRegister64(r1, b);
                conditionCode = compare64(b, 0);
            }
            case 0x03 -> { // LCGR
                setRegister64(r1, -b);
                conditionCode = b == Long.MIN_VALUE ? 3 : compare64(-b, 0);
                fixedOverflow();
            }
            case 0x00 -> { // LPGR
                setRegister64(r1, Math.abs(b));
                conditionCode = b == Long.MIN_VALUE ? 3 : b == 0 ? 0 : 2;
                fixedOverflow();
            }
            case 0x01 -> { // LNGR
                setRegister64(r1, -Math.abs(b));
                conditionCode = b == 0 ? 0 : 1;
            }
            case 0x06 -> setRegister64(r1, (byte) b); // LGBR
            case 0x08 -> add64(r1, a, b); // AGR
            case 0x09 -> subtract64(r1, a, b); // SGR
            case 0x0A -> addLogical64(r1, a, b, 0); // ALGR
            case 0x0B -> addLogical64(r1, a, ~b, 1); // SLGR
            case 0x0C -> setRegister64(r1, a * b); // MSGR
            case 0x0D -> divideSigned64(r1, b); // DSGR
            case 0x1D -> divideSigned64(r1, (int) b); // DSGFR
            case 0x86 -> multiplyLogical64(r1, b); // MLGR
            case 0x87 -> divideLogical64(r1, b); // DLGR
            case 0x96 -> multiplyLogical32(r1, (int) b); // MLR
            case 0x97 -> divideLogical32(r1, (int) b); // DLR
            case 0x0F -> setRegister64(r1, Long.reverseBytes(b)); // LRVGR
            case 0x14 -> setRegister64(r1, (int) b); // LGFR
            case 0x16 -> setRegister64(r1, b & 0xFFFFFFFFL); // LLGFR
            case 0x18 -> add64(r1, a, (int) b); // AGFR
            case 0x19 -> subtract64(r1, a, (int) b); // SGFR
            case 0x1F -> gpr[r1] = Integer.reverseBytes(gpr[r2]); // LRVR
            case 0x20 -> conditionCode = compare64(a, b); // CGR
            case 0x21 -> conditionCode = compareUnsigned64(a, b); // CLGR
            case 0x26 -> gpr[r1] = (byte) b; // LBR
            case 0x30 -> conditionCode = compare64(a, (int) b); // CGFR
            case 0x46 -> { // BCTGR
                setRegister64(r1, a - 1);
                if (a != 1 && r2 != 0) {
                    instructionAddress = (int) b & addressMask;
                }
            }
            case 0x80 -> logical64(r1, a & b); // NGR
            case 0x81 -> logical64(r1, a | b); // OGR
            case 0x82 -> logical64(r1, a ^ b); // XGR
            case 0x83 -> { // FLOGR
                requireEven(r1, "FLOGR");
                if (b == 0) {
                    setRegister64(r1, 64);
                    setRegister64(r1 + 1, 0);
                    conditionCode = 0;
                } else {
                    int zeros = Long.numberOfLeadingZeros(b);
                    setRegister64(r1, zeros);
                    setRegister64(r1 + 1, b & ~(0x8000000000000000L >>> zeros));
                    conditionCode = 2;
                }
            }
            // 繰り上がりは前の命令の条件コード (2 か 3) に入っている
            case 0x88 -> addLogical64(r1, a, b, conditionCode >= 2 ? 1 : 0); // ALCGR
            case 0x89 -> addLogical64(r1, a, ~b, conditionCode >= 2 ? 1 : 0); // SLBGR
            case 0x98 -> addLogical(r1, (int) b, conditionCode >= 2 ? 1 : 0); // ALCR
            case 0x99 -> addLogical(r1, ~(int) b, conditionCode >= 2 ? 1 : 0); // SLBR
            case 0xE0, 0xE2, 0xF2 -> {
                if (selected(r3)) {
                    int operation = insn[1] & 0xFF;
                    conditionalLoad(operation == 0xE0 ? 0x4E : operation == 0xE2 ? 0x46 : 0x42,
                            r1, operation == 0xE0 ? high[r2] : b);
                }
            }
            case 0xC0, 0xE3, 0xF0 -> {
                int source = selected(insn[2] & 0xF) ? r2 : r3;
                int operation = insn[1] & 0xFF;
                conditionalLoad(operation == 0xC0 ? 0x4E : operation == 0xE3 ? 0x46 : 0x42,
                        r1, operation == 0xC0 ? high[source] : register64(source));
            }
            case 0xE1 -> { // POPCNT: バイトごとに 1 の数を数え、そのバイトに置く
                if ((insn[2] & 0x80) != 0) {
                    // M3 の 8 のビットが立っていれば、64 ビット全体の 1 の数である
                    logical64(r1, Long.bitCount(b));
                    return;
                }
                long result = 0;
                for (int k = 0; k < 8; k++) {
                    result |= (long) Long.bitCount(b >>> (8 * k) & 0xFF) << (8 * k);
                }
                logical64(r1, result);
            }
            case 0xE8 -> add64(r1, b, register64(r3)); // AGRK
            case 0xE9 -> subtract64(r1, b, register64(r3)); // SGRK
            case 0xE4 -> logical64(r1, b & register64(r3)); // NGRK
            case 0xE6 -> logical64(r1, b | register64(r3)); // OGRK
            case 0xE7 -> logical64(r1, b ^ register64(r3)); // XGRK
            case 0x64 -> logical64(r1, ~(b & register64(r3))); // NNGRK
            case 0x65 -> logical64(r1, b | ~register64(r3)); // OCGRK
            case 0x66 -> logical64(r1, ~(b | register64(r3))); // NOGRK
            case 0x67 -> logical64(r1, ~(b ^ register64(r3))); // NXGRK
            case 0x74 -> logical(r1, ~(gpr[r2] & gpr[r3])); // NNRK
            case 0x75 -> logical(r1, gpr[r2] | ~gpr[r3]); // OCRK
            case 0x76 -> logical(r1, ~(gpr[r2] | gpr[r3])); // NORK
            case 0x77 -> logical(r1, ~(gpr[r2] ^ gpr[r3])); // NXRK
            case 0xE5 -> logical64(r1, b & ~register64(r3)); // NCGRK
            case 0xF4 -> logical(r1, gpr[r2] & gpr[r3]); // NRK
            case 0xF5 -> logical(r1, gpr[r2] & ~gpr[r3]); // NCRK
            case 0xF6 -> logical(r1, gpr[r2] | gpr[r3]); // ORK
            case 0xF7 -> logical(r1, gpr[r2] ^ gpr[r3]); // XRK
            default -> throw new MachineException(MachineException.OPERATION, String.format(
                    "operation code B9%02X is not implemented", insn[1] & 0xFF));
        }
    }

    /** A7x?。16 ビットの符号つき即値。 */
    private void registerImmediate(byte[] insn) {
        int r1 = r1(insn);
        int immediate = (short) ((insn[2] & 0xFF) << 8 | (insn[3] & 0xFF));
        switch (insn[1] & 0x0F) {
            case 0x0, 0x1, 0x2, 0x3 -> {
                int shift = switch (insn[1] & 3) {
                    case 0 -> 16;
                    case 1 -> 0;
                    case 2 -> 48;
                    default -> 32;
                };
                int mask = immediate & 0xFFFF;
                int result = ((int) (register64(r1) >>> shift)) & mask;
                conditionCode = result == 0 ? 0 : result == mask ? 3
                        : (result & Integer.highestOneBit(mask)) == 0 ? 1 : 2;
            }
            case 0x4 -> branchRelativeIfSelected(r1, immediate); // BRC
            case 0x5 -> branchRelativeAndSave(r1, immediate); // BRAS
            case 0x6 -> { // BRCT
                if (--gpr[r1] != 0) {
                    instructionAddress = relativeTarget(immediate);
                }
            }
            case 0x7 -> { // BRCTG
                long count = register64(r1) - 1;
                setRegister64(r1, count);
                if (count != 0) {
                    instructionAddress = relativeTarget(immediate);
                }
            }
            case 0x8 -> gpr[r1] = immediate; // LHI
            case 0x9 -> setRegister64(r1, immediate); // LGHI
            case 0xA -> add(r1, immediate); // AHI
            case 0xB -> add64(r1, register64(r1), immediate); // AGHI
            case 0xC -> gpr[r1] *= immediate; // MHI
            case 0xD -> setRegister64(r1, register64(r1) * immediate); // MGHI
            case 0xE -> conditionCode = compare(gpr[r1], immediate); // CHI
            case 0xF -> conditionCode = compare64(register64(r1), immediate); // CGHI
            default -> throw new MachineException(MachineException.OPERATION, String.format(
                    "operation code A7x%X is not implemented", insn[1] & 0x0F));
        }
    }

    private void registerHalfwordImmediate(byte[] insn) {
        int operation = insn[1] & 0x0F;
        int r1 = r1(insn);
        int shift = 16 * (3 - (operation & 3));
        long value = ((insn[2] & 0xFFL) << 8 | (insn[3] & 0xFFL)) << shift;
        if (operation < 12) {
            long original = register64(r1);
            if (operation >= 4) {
                long halfword = original & (0xFFFFL << shift);
                value = operation < 8 ? value & halfword : value | halfword;
                conditionCode = value == 0 ? 0 : 1;
            }
            value |= original & ~(0xFFFFL << shift);
        }
        setRegister64(r1, value);
    }

    private static int immediateWord(byte[] insn) {
        return (insn[2] & 0xFF) << 24 | (insn[3] & 0xFF) << 16
                | (insn[4] & 0xFF) << 8 | (insn[5] & 0xFF);
    }

    /** C0x?。32 ビットの即値。 */
    private void registerImmediateLong(byte[] insn) {
        int r1 = r1(insn);
        int immediate = immediateWord(insn);
        switch (insn[1] & 0x0F) {
            case 0x0 -> gpr[r1] = relativeTarget(immediate); // LARL
            case 0x4 -> branchRelativeIfSelected(r1, immediate); // BRCL
            case 0x5 -> branchRelativeAndSave(r1, immediate); // BRASL
            case 0x6 -> logicalHigh(r1, high[r1] ^ immediate); // XIHF
            case 0x7 -> logical(r1, gpr[r1] ^ immediate); // XILF
            case 0x8 -> high[r1] = immediate; // IIHF
            case 0x9 -> gpr[r1] = immediate; // IILF
            case 0xA -> logicalHigh(r1, high[r1] & immediate); // NIHF
            case 0xB -> logical(r1, gpr[r1] & immediate); // NILF
            case 0xC -> logicalHigh(r1, high[r1] | immediate); // OIHF
            case 0xD -> logical(r1, gpr[r1] | immediate); // OILF
            case 0x1 -> setRegister64(r1, immediate); // LGFI
            case 0xE -> setRegister64(r1, (long) immediate << 32); // LLIHF
            case 0xF -> setRegister64(r1, Integer.toUnsignedLong(immediate)); // LLILF
            default -> throw new MachineException(MachineException.OPERATION, String.format(
                    "operation code C0x%X is not implemented", insn[1] & 0x0F));
        }
    }

    private int relativeTarget(int halfwords) {
        return (operationAddress + (halfwords << 1)) & addressMask;
    }

    private void branchRelativeIfSelected(int mask, int halfwords) {
        if (selected(mask)) {
            instructionAddress = relativeTarget(halfwords);
        }
    }

    private void branchRelativeAndSave(int register, int halfwords) {
        gpr[register] = instructionAddress;
        instructionAddress = relativeTarget(halfwords);
    }

    /** 20 ビットの符号つき変位。下位 12 ビットは 3〜4 バイト目、上位 8 ビットは 5 バイト目。 */
    private static int longDisplacement(byte[] insn) {
        return insn[4] << 12 | (insn[2] & 0x0F) << 8 | (insn[3] & 0xFF);
    }

    private int effectiveLong(byte[] insn, boolean indexed) {
        int base = (insn[2] >> 4) & 0x0F;
        int address = longDisplacement(insn);
        if (base != 0) {
            address += gpr[base];
        }
        int index = insn[1] & 0x0F;
        if (indexed && index != 0) {
            address += gpr[index];
        }
        return address & addressMask;
    }

    /** E3xx。レジスタと記憶域。 */
    private void registerStorage64(byte[] insn) {
        int r1 = r1(insn);
        int address = effectiveLong(insn, true);
        long a = register64(r1);
        switch (insn[5] & 0xFF) {
            case 0x04 -> setRegister64(r1, memory.getLong(address)); // LG
            case 0x08 -> add64(r1, a, memory.getLong(address)); // AG
            case 0x09 -> subtract64(r1, a, memory.getLong(address)); // SG
            case 0x0A -> addLogical64(r1, a, memory.getLong(address), 0); // ALG
            case 0x0B -> addLogical64(r1, a, ~memory.getLong(address), 1); // SLG
            case 0x0C -> setRegister64(r1, a * memory.getLong(address)); // MSG
            case 0x0D -> divideSigned64(r1, memory.getLong(address)); // DSG
            case 0x0F -> setRegister64(r1, Long.reverseBytes(memory.getLong(address))); // LRVG
            case 0x14 -> setRegister64(r1, memory.getInt(address)); // LGF
            case 0x15 -> setRegister64(r1, memory.getShort(address)); // LGH
            case 0x16 -> setRegister64(r1, memory.getInt(address) & 0xFFFFFFFFL); // LLGF
            case 0x18 -> add64(r1, a, memory.getInt(address)); // AGF
            case 0x19 -> subtract64(r1, a, memory.getInt(address)); // SGF
            case 0x1D -> divideSigned64(r1, memory.getInt(address)); // DSGF
            case 0x1E -> gpr[r1] = Integer.reverseBytes(memory.getInt(address)); // LRV
            case 0x1F -> gpr[r1] = (gpr[r1] & 0xFFFF0000)
                    | Short.toUnsignedInt(Short.reverseBytes(memory.getShort(address))); // LRVH
            case 0x20 -> conditionCode = compare64(a, memory.getLong(address)); // CG
            case 0x21 -> conditionCode = compareUnsigned64(a, memory.getLong(address)); // CLG
            case 0x24 -> memory.putLong(address, a); // STG
            case 0x2F -> memory.putLong(address, Long.reverseBytes(a)); // STRVG
            case 0x30 -> conditionCode = compare64(a, memory.getInt(address)); // CGF
            case 0x3E -> memory.putInt(address, Integer.reverseBytes(gpr[r1])); // STRV
            case 0x3F -> memory.putShort(address, Short.reverseBytes((short) gpr[r1])); // STRVH
            case 0x46 -> { // BCTG
                setRegister64(r1, a - 1);
                if (a != 1) {
                    instructionAddress = address;
                }
            }
            case 0x50 -> memory.putInt(address, gpr[r1]); // STY
            case 0x54 -> logical(r1, gpr[r1] & memory.getInt(address)); // NY
            case 0x55 -> conditionCode = compareUnsigned(gpr[r1], memory.getInt(address)); // CLY
            case 0x56 -> logical(r1, gpr[r1] | memory.getInt(address)); // OY
            case 0x57 -> logical(r1, gpr[r1] ^ memory.getInt(address)); // XY
            case 0x51 -> gpr[r1] *= memory.getInt(address); // MSY
            case 0x58 -> gpr[r1] = memory.getInt(address); // LY
            case 0x59 -> conditionCode = compare(gpr[r1], memory.getInt(address)); // CY
            case 0x5A -> add(r1, memory.getInt(address)); // AY
            case 0x5B -> subtract(r1, memory.getInt(address)); // SY
            case 0x5E -> addLogical(r1, memory.getInt(address)); // ALY
            case 0x5F -> subtractLogical(r1, memory.getInt(address)); // SLY
            case 0x70 -> memory.putShort(address, (short) gpr[r1]); // STHY
            case 0x71 -> gpr[r1] = address; // LAY
            case 0x72 -> memory.set(address, (byte) gpr[r1]); // STCY
            case 0x73 -> gpr[r1] = (gpr[r1] & 0xFFFFFF00) | (memory.get(address) & 0xFF); // ICY
            case 0x76 -> gpr[r1] = memory.get(address); // LB
            case 0x77 -> setRegister64(r1, memory.get(address)); // LGB
            case 0x78 -> gpr[r1] = memory.getShort(address); // LHY
            case 0x79 -> conditionCode = compare(gpr[r1], memory.getShort(address)); // CHY
            case 0x7A -> add(r1, memory.getShort(address)); // AHY
            case 0x7B -> subtract(r1, memory.getShort(address)); // SHY
            case 0x80 -> logical64(r1, a & memory.getLong(address)); // NG
            case 0x81 -> logical64(r1, a | memory.getLong(address)); // OG
            case 0x82 -> logical64(r1, a ^ memory.getLong(address)); // XG
            case 0x86 -> multiplyLogical64(r1, memory.getLong(address)); // MLG
            case 0x87 -> divideLogical64(r1, memory.getLong(address)); // DLG
            case 0x88 -> addLogical64(r1, a, memory.getLong(address), conditionCode >= 2 ? 1 : 0); // ALCG
            case 0x89 -> addLogical64(r1, a, ~memory.getLong(address), conditionCode >= 2 ? 1 : 0); // SLBG
            case 0x8E, 0x8F -> {
                requireEven(r1, "LPQ/STPQ");
                requireAlignment(address, 16);
                synchronized (memory) {
                    if ((insn[5] & 1) != 0) {
                        ByteBuffer value = ByteBuffer.wrap(memory.read(address, 16));
                        setRegister64(r1, value.getLong());
                        setRegister64(r1 + 1, value.getLong());
                    } else {
                        ByteBuffer value = ByteBuffer.allocate(16);
                        value.putLong(register64(r1)).putLong(register64(r1 + 1));
                        memory.write(address, value.array());
                    }
                }
            }
            case 0x90 -> setRegister64(r1, memory.get(address) & 0xFFL); // LLGC
            case 0x91 -> setRegister64(r1, memory.getShort(address) & 0xFFFFL); // LLGH
            case 0x94 -> gpr[r1] = memory.get(address) & 0xFF; // LLC
            case 0x95 -> gpr[r1] = memory.getShort(address) & 0xFFFF; // LLH
            case 0x96 -> multiplyLogical32(r1, memory.getInt(address)); // ML
            case 0x97 -> divideLogical32(r1, memory.getInt(address)); // DL
            case 0x98 -> addLogical(r1, memory.getInt(address), conditionCode >= 2 ? 1 : 0); // ALC
            case 0x99 -> addLogical(r1, ~memory.getInt(address), conditionCode >= 2 ? 1 : 0); // SLB
            default -> throw new MachineException(MachineException.OPERATION, String.format(
                    "operation code E3%02X is not implemented", insn[5] & 0xFF));
        }
    }

    /** EBxx。レジスタ 2 つ (またはレジスタとマスク) と記憶域。 */
    private void registerStorageExtended(byte[] insn) {
        int r1 = r1(insn);
        int r3 = r3(insn);
        int address = effectiveLong(insn, false);
        int amount = address & 0x3F;
        long source = register64(r3);
        switch (insn[5] & 0xFF) {
            case 0x04 -> { // LMG
                for (int r = r1; ; r = (r + 1) % REGISTERS) {
                    setRegister64(r, memory.getLong(address));
                    address += 8;
                    if (r == r3) {
                        break;
                    }
                }
            }
            case 0x24 -> { // STMG
                for (int r = r1; ; r = (r + 1) % REGISTERS) {
                    memory.putLong(address, register64(r));
                    address += 8;
                    if (r == r3) {
                        break;
                    }
                }
            }
            case 0x0C -> setRegister64(r1, source >>> amount); // SRLG
            case 0x0D -> setRegister64(r1, source << amount); // SLLG
            case 0x1C -> setRegister64(r1, Long.rotateLeft(source, amount)); // RLLG
            case 0x1D -> gpr[r1] = Integer.rotateLeft((int) source, amount); // RLL
            case 0x20 -> compareUnderMask(high[r1], r3, address); // CLMH
            case 0x21 -> compareUnderMask(gpr[r1], r3, address); // CLMY
            case 0x2C -> storeUnderMask(high[r1], r3, address); // STCMH
            case 0x2D -> storeUnderMask(gpr[r1], r3, address); // STCMY
            case 0x0A -> { // SRAG
                long result = source >> amount;
                setRegister64(r1, result);
                conditionCode = compare64(result, 0);
            }
            case 0x0B -> { // SLAG: 符号は保ち、押し出したビットが符号と違えばあふれである
                long sign = source & 0x8000000000000000L;
                long magnitude = source & 0x7FFFFFFFFFFFFFFFL;
                boolean overflow = false;
                for (int k = 0; k < amount; k++) {
                    magnitude <<= 1;
                    if ((magnitude & 0x8000000000000000L) != sign) {
                        overflow = true;
                    }
                    magnitude &= 0x7FFFFFFFFFFFFFFFL;
                }
                long result = sign | magnitude;
                setRegister64(r1, result);
                conditionCode = overflow ? 3 : compare64(result, 0);
                fixedOverflow();
            }
            case 0x14 -> compareAndSwap(r1, r3, address, 4, false); // CSY
            case 0x30 -> compareAndSwap(r1, r3, address, 8, false); // CSG
            case 0x31 -> compareAndSwap(r1, r3, address, 4, true); // CDSY
            case 0x3E -> compareAndSwap(r1, r3, address, 8, true); // CDSG
            case 0x51 -> testUnderMask(address, insn[1] & 0xFF); // TMY
            case 0x54 -> immediate(address, insn[1], (a, b) -> (byte) (a & b)); // NIY
            case 0x55 -> conditionCode = compare(memory.get(address) & 0xFF, insn[1] & 0xFF); // CLIY
            case 0x56 -> immediate(address, insn[1], (a, b) -> (byte) (a | b)); // OIY
            case 0x57 -> immediate(address, insn[1], (a, b) -> (byte) (a ^ b)); // XIY
            case 0x80 -> insertUnderMask(r1, r3, address, true); // ICMH
            case 0x81 -> insertUnderMask(r1, r3, address, false); // ICMY
            case 0x90 -> transferWords(r1, r3, address, false); // STMY
            case 0x98 -> transferWords(r1, r3, address, true); // LMY
            case 0xE0, 0xE2, 0xF2 -> {
                if (selected(r3)) {
                    int operation = insn[5] & 0xFF;
                    conditionalLoad(operation == 0xE0 ? 0x4E : operation == 0xE2 ? 0x46 : 0x42,
                            r1, operation == 0xE2 ? memory.getLong(address) : memory.getInt(address));
                }
            }
            case 0xE1, 0xE3, 0xF3 -> {
                if (selected(r3)) {
                    int operation = insn[5] & 0xFF;
                    if (operation == 0xE3) {
                        memory.putLong(address, register64(r1));
                    } else {
                        memory.putInt(address, operation == 0xE1 ? high[r1] : gpr[r1]);
                    }
                }
            }
            case 0xE4, 0xE6, 0xE7, 0xE8, 0xEA, 0xF4, 0xF6, 0xF7, 0xF8, 0xFA ->
                    loadAndUpdate(insn[5] & 0xFF, r1, source, address);
            case 0xC0 -> testDecimal(insn); // TP
            default -> throw new MachineException(MachineException.OPERATION, String.format(
                    "operation code EB%02X is not implemented", insn[5] & 0xFF));
        }
    }

    private static void requireAlignment(int address, int width) {
        if ((address & (width - 1)) != 0) {
            throw new MachineException(MachineException.SPECIFICATION, "misaligned interlocked operand");
        }
    }

    private void loadDisjoint(byte[] insn) {
        int extension = insn[1] & 0xF;
        int width = switch (extension) {
            case 4 -> 4;
            case 5 -> 8;
            default -> throw new MachineException(MachineException.OPERATION, "unsupported C8 operation");
        };
        int register = r1(insn);
        requireEven(register, "LPD/LPDG");
        int first = effectiveSi(insn);
        int second = ((insn[4] & 0xF) << 8 | insn[5] & 0xFF);
        int base = (insn[4] >>> 4) & 0xF;
        second = (second + (base == 0 ? 0 : gpr[base])) & addressMask;
        requireAlignment(first, width);
        requireAlignment(second, width);
        synchronized (memory) {
            long left = readWord(first, width);
            long right = readWord(second, width);
            writeRegisterWord(register, width, left);
            writeRegisterWord(register + 1, width, right);
        }
        conditionCode = 0;
    }

    private void compareAndSwap(int first, int replacement, int address, int width, boolean pair) {
        requireAlignment(address, pair ? 2 * width : width);
        if (pair) {
            requireEven(first, "compare double and swap");
            requireEven(replacement, "compare double and swap");
        }
        synchronized (memory) {
            long current = readWord(address, width);
            long next = pair ? readWord(address + width, width) : 0;
            if (current == registerWord(first, width)
                    && (!pair || next == registerWord(first + 1, width))) {
                int length = pair ? width * 2 : width;
                ByteBuffer update = ByteBuffer.allocate(length);
                for (int i = 0; i < length / width; i++) {
                    if (width == 4) {
                        update.putInt(gpr[replacement + i]);
                    } else {
                        update.putLong(register64(replacement + i));
                    }
                }
                memory.write(address, update.array());
                conditionCode = 0;
            } else {
                writeRegisterWord(first, width, current);
                if (pair) {
                    writeRegisterWord(first + 1, width, next);
                }
                conditionCode = 1;
            }
        }
    }

    private void compareSwapAndStore(int register, int first, int second) {
        int function = gpr[0] & 0xFF;
        int characteristic = (gpr[0] >>> 8) & 0xFF;
        if (function > 2 || characteristic > 4) {
            throw new MachineException(MachineException.SPECIFICATION,
                    "unassigned CSST function or store characteristic");
        }
        boolean pair = function == 2;
        int width = function == 0 ? 4 : 8;
        int compareLength = pair ? 16 : width;
        int storeLength = 1 << characteristic;
        if (pair) requireEven(register, "CSST");
        requireAlignment(first, compareLength);
        requireAlignment(second, storeLength);
        int parameters = gpr[1] & addressMask & ~15;

        synchronized (memory) {
            byte[] original = memory.read(first, compareLength);
            memory.requireRange(second, storeLength);
            byte[] replacement = memory.read(parameters, compareLength);
            byte[] stored = memory.read((parameters + 16) & addressMask, storeLength);

            ByteBuffer prior = ByteBuffer.wrap(original);
            long current = width == 4 ? prior.getInt() : prior.getLong();
            long next = pair ? prior.getLong() : 0;
            if (current == registerWord(register, width)
                    && (!pair || next == registerWord(register + 1, width))) {
                memory.write(first, replacement);
                memory.write(second, stored);
                conditionCode = 0;
            } else {
                writeRegisterWord(register, width, current);
                if (pair) writeRegisterWord(register + 1, width, next);
                conditionCode = 1;
            }
        }
    }

    private long readWord(int address, int width) {
        return width == 4 ? memory.getInt(address) : memory.getLong(address);
    }

    private long registerWord(int register, int width) {
        return width == 4 ? gpr[register] : register64(register);
    }

    private void writeRegisterWord(int register, int width, long value) {
        if (width == 4) {
            gpr[register] = (int) value;
        } else {
            setRegister64(register, value);
        }
    }

    private void loadAndUpdate(int opcode, int register, long operand, int address) {
        int width = opcode < 0xF0 ? 8 : 4;
        requireAlignment(address, width);
        if (width == 4) {
            operand = (int) operand;
        }
        synchronized (memory) {
            long before = readWord(address, width);
            int operation = opcode & 0xF;
            long result = switch (operation) {
                case 4 -> before & operand;
                case 6 -> before | operand;
                case 7 -> before ^ operand;
                default -> before + operand;
            };
            if (width == 4) {
                result = (int) result;
                memory.putInt(address, (int) result);
            } else {
                memory.putLong(address, result);
            }
            writeRegisterWord(register, width, before);
            boolean overflow = ((before ^ result) & (operand ^ result)) < 0;
            conditionCode = switch (operation) {
                case 8 -> overflow ? 3 : compare64(result, 0);
                case 0xA -> (result == 0 ? 0 : 1) + (Long.compareUnsigned(result, before) < 0 ? 2 : 0);
                default -> result == 0 ? 0 : 1;
            };
            if (operation == 8) {
                fixedOverflow();
            }
        }
    }

    /**
     * ICM / ICMH。マスクの立っている桁へ、記憶域から順にバイトを入れる。
     *
     * <p>条件コードは、入れたビットがすべて 0 (またはマスクが 0) なら 0、いちばん左のビットが
     * 1 なら 1、そうでなければ 2 である。
     */
    private void insertUnderMask(int r1, int mask, int address, boolean upper) {
        int value = upper ? high[r1] : gpr[r1];
        boolean any = false;
        boolean first = true;
        boolean leftmost = false;
        for (int bit = 0; bit < 4; bit++) {
            if ((mask & (8 >> bit)) == 0) {
                continue;
            }
            int inserted = memory.get(address++) & 0xFF;
            int shift = 24 - 8 * bit;
            value = (value & ~(0xFF << shift)) | (inserted << shift);
            if (first) {
                leftmost = (inserted & 0x80) != 0;
                first = false;
            }
            any |= inserted != 0;
        }
        if (upper) {
            high[r1] = value;
        } else {
            gpr[r1] = value;
        }
        conditionCode = !any ? 0 : leftmost ? 1 : 2;
    }

    /**
     * RISBG。R2 を左へ回し、選んだビットの範囲を R1 へ入れる。
     *
     * <p>範囲は I3 (始まり) から I4 (終わり) までで、始まりのほうが大きければ端を回り込む。
     * I4 の最上位ビットが立っていれば、範囲の外を 0 にする。そうでなければ R1 の元の値を残す。
     */
    private void rotateThenInsert(byte[] insn) {
        int operation = insn[5] & 0xFF;
        int r1 = r1(insn);
        int r2 = r2(insn);
        long original = register64(r1);
        long rotated = Long.rotateLeft(register64(r2), insn[4] & 0x3F);
        long selected = switch (operation) {
            case 0x51, 0x55, 0x59, 0x5D -> rotated;
            case 0x54 -> original & rotated;
            case 0x56 -> original | rotated;
            case 0x57 -> original ^ rotated;
            default -> throw new MachineException(MachineException.OPERATION,
                    String.format("operation code EC%02X is not implemented", operation));
        };
        // HIGH/LOW 形式では語の境界で折り返す (Zeus hlasm/execute/bits.rs)。
        long word = operation == 0x51 ? 0xFFFFFFFFL
                : operation == 0x5D ? 0xFFFFFFFF00000000L : -1L;
        int width = word == -1L ? 64 : 32;
        int start = insn[2] & (width - 1);
        int end = insn[3] & (width - 1);
        long left = (word >>> start) & word;
        long right = (word << (width - 1 - end)) & word;
        long mask = start <= end ? left & right : left | right;
        selected &= mask;
        boolean logical = operation == 0x54 || operation == 0x56 || operation == 0x57;
        long preserved = !logical && (insn[3] & 0x80) != 0 ? ~word : ~mask;
        long result = (original & preserved) | selected;
        if (logical) {
            conditionCode = selected == 0 ? 0 : 1;
        } else if (operation == 0x55) {
            conditionCode = compare64(result, 0);
        }
        if (!logical || (insn[2] & 0x80) == 0) {
            setRegister64(r1, result);
        }
    }

    /**
     * TP。詰め 10 進として正しいかを調べる。条件コードは、正しければ 0、符号が違えば 1、
     * 数字が違えば 2、両方違えば 3 である。
     */
    private void testDecimal(byte[] insn) {
        int length = ((insn[1] >> 4) & 0x0F) + 1;
        int base = (insn[2] >> 4) & 0x0F;
        int displacement = ((insn[2] & 0x0F) << 8) | (insn[3] & 0xFF);
        int address = (base == 0 ? displacement : gpr[base] + displacement) & addressMask;
        byte[] field = memory.read(address, length);
        boolean badDigit = false;
        for (int k = 0; k < length; k++) {
            if (((field[k] >> 4) & 0x0F) > 9) {
                badDigit = true;
            }
            if (k < length - 1 && (field[k] & 0x0F) > 9) {
                badDigit = true;
            }
        }
        boolean badSign = (field[length - 1] & 0x0F) < 0x0A;
        conditionCode = (badDigit ? 2 : 0) | (badSign ? 1 : 0);
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
        return address & addressMask;
    }

    /** RS / SI 形式の実効番地 (指標を持たない)。 */
    private int effectiveSi(byte[] insn) {
        int base = (insn[2] >> 4) & 0x0F;
        int displacement = ((insn[2] & 0x0F) << 8) | (insn[3] & 0xFF);
        return (base == 0 ? displacement : gpr[base] + displacement) & addressMask;
    }

    /** SS 形式の第 1 演算項の番地。 */
    private int ssFirst(byte[] insn) {
        int base = (insn[2] >> 4) & 0x0F;
        int displacement = ((insn[2] & 0x0F) << 8) | (insn[3] & 0xFF);
        return (base == 0 ? displacement : gpr[base] + displacement) & addressMask;
    }

    /** SS 形式の第 2 演算項の番地。 */
    private int ssSecond(byte[] insn) {
        int base = (insn[4] >> 4) & 0x0F;
        int displacement = ((insn[4] & 0x0F) << 8) | (insn[5] & 0xFF);
        return (base == 0 ? displacement : gpr[base] + displacement) & addressMask;
    }

    /** シフト量は実効番地の下位 6 ビットである。 */
    private int shift(byte[] insn) {
        return effectiveSi(insn) & 0x3F;
    }

    private void conditionalLoad(int operation, int register, long value) {
        switch (operation) {
            case 0x42 -> gpr[register] = (int) value;
            case 0x46 -> setRegister64(register, value);
            case 0x4E -> high[register] = (int) value;
            default -> throw new IllegalArgumentException("unknown conditional load");
        }
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
        addLogical(r1, value, 0);
    }

    private void addLogical(int r1, int value, int carryIn) {
        long result = Integer.toUnsignedLong(gpr[r1]) + Integer.toUnsignedLong(value) + carryIn;
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

    private void logicalHigh(int r1, int result) {
        high[r1] = result;
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

    private void multiplyLogical32(int register, int operand) {
        requireEven(register, "ML/MLR");
        long product = Integer.toUnsignedLong(gpr[register + 1]) * Integer.toUnsignedLong(operand);
        gpr[register] = (int) (product >>> 32);
        gpr[register + 1] = (int) product;
    }

    private void multiplyLogical64(int register, long operand) {
        requireEven(register, "MLG/MLGR");
        long value = register64(register + 1);
        setRegister64(register, Math.unsignedMultiplyHigh(value, operand));
        setRegister64(register + 1, value * operand);
    }

    private void divideLogical32(int register, int operand) {
        requireEven(register, "DL/DLR");
        long divisor = Integer.toUnsignedLong(operand);
        long upper = Integer.toUnsignedLong(gpr[register]);
        if (upper >= divisor) {
            throw new MachineException(MachineException.FIXED_DIVIDE,
                    "zero divisor or unsigned quotient exceeds 32 bits");
        }
        long dividend = (upper << 32) | Integer.toUnsignedLong(gpr[register + 1]);
        gpr[register] = (int) Long.remainderUnsigned(dividend, divisor);
        gpr[register + 1] = (int) Long.divideUnsigned(dividend, divisor);
    }

    private void divideLogical64(int register, long divisor) {
        requireEven(register, "DLG/DLGR");
        long upper = register64(register);
        if (Long.compareUnsigned(upper, divisor) >= 0) {
            throw new MachineException(MachineException.FIXED_DIVIDE,
                    "zero divisor or unsigned quotient exceeds 64 bits");
        }
        ByteBuffer dividend = ByteBuffer.allocate(16);
        dividend.putLong(upper).putLong(register64(register + 1));
        ByteBuffer operand = ByteBuffer.allocate(8).putLong(divisor);
        BigInteger[] result = new BigInteger(1, dividend.array())
                .divideAndRemainder(new BigInteger(1, operand.array()));
        setRegister64(register, result[1].longValue());
        setRegister64(register + 1, result[0].longValue());
    }

    private void divideSigned64(int register, long divisor) {
        requireEven(register, "DSG/DSGF/DSGR/DSGFR");
        long dividend = register64(register + 1);
        if (divisor == 0 || (dividend == Long.MIN_VALUE && divisor == -1)) {
            throw new MachineException(MachineException.FIXED_DIVIDE,
                    "zero divisor or signed quotient exceeds 64 bits");
        }
        setRegister64(register, dividend % divisor);
        setRegister64(register + 1, dividend / divisor);
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

    private void transferWords(int first, int last, int address, boolean load) {
        for (int r = first; ; r = (r + 1) % REGISTERS) {
            if (load) {
                gpr[r] = memory.getInt(address);
            } else {
                memory.putInt(address, gpr[r]);
            }
            address += 4;
            if (r == last) {
                return;
            }
        }
    }

    private void storeUnderMask(int value, int mask, int address) {
        for (int bit = 0; bit < 4; bit++) {
            if ((mask & (8 >> bit)) != 0) {
                memory.set(address++, (byte) (value >>> (24 - 8 * bit)));
            }
        }
    }

    private void compareUnderMask(int value, int mask, int address) {
        conditionCode = 0;
        for (int bit = 0; bit < 4 && conditionCode == 0; bit++) {
            if ((mask & (8 >> bit)) != 0) {
                conditionCode = compare((value >>> (24 - 8 * bit)) & 0xFF,
                        memory.get(address++) & 0xFF);
            }
        }
    }

    // --- SI 形式 ---

    @FunctionalInterface
    private interface ByteOperation {
        byte apply(byte left, byte right);
    }

    private void immediate(byte[] insn, ByteOperation operation) {
        immediate(effectiveSi(insn), insn[1], operation);
    }

    private void immediate(int address, byte operand, ByteOperation operation) {
        byte result = operation.apply(memory.get(address), operand);
        memory.set(address, result);
        conditionCode = result == 0 ? 0 : 1;
    }

    /**
     * マスクで選んだビットを調べる。
     * すべて 0 なら 0、混ざっていれば 1、すべて 1 なら 3 である。マスクが 0 なら 0 とする。
     */
    private void testUnderMask(byte[] insn) {
        testUnderMask(effectiveSi(insn), insn[1] & 0xFF);
    }

    private void testUnderMask(int address, int mask) {
        int value = memory.get(address) & 0xFF;
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
    private void execute(int r1, int target, boolean executed) {
        if (executed) {
            throw new MachineException(MachineException.EXECUTE, "EX cannot execute another EX");
        }
        int opcode = memory.get(target) & 0xFF;
        byte[] subject = memory.read(target, lengthOf(opcode));
        if (opcode == 0x44 || (opcode == 0xC6 && (subject[1] & 0xF) == 0)) {
            throw new MachineException(MachineException.EXECUTE, "EX cannot execute another EX");
        }
        if (r1 != 0) {
            subject[1] = (byte) (subject[1] | (gpr[r1] & 0xFF));
        }
        int original = operationAddress;
        operationAddress = target;
        try {
            perform(subject, true);
        } finally {
            operationAddress = original;
        }
    }
}
