package dev.cobolonjava.hlasm;

import dev.cobolonjava.hlasm.FloatingPoint.Converted;
import dev.cobolonjava.hlasm.FloatingPoint.DecimalValue;
import dev.cobolonjava.hlasm.FloatingPoint.Encoded;
import dev.cobolonjava.hlasm.FloatingPoint.Finite;
import dev.cobolonjava.hlasm.FloatingPoint.HexResult;
import dev.cobolonjava.hlasm.FloatingPoint.Infinity;
import dev.cobolonjava.hlasm.FloatingPoint.NotANumber;
import dev.cobolonjava.hlasm.FloatingPoint.Radix;
import dev.cobolonjava.hlasm.FloatingPoint.Ratio;
import dev.cobolonjava.hlasm.FloatingPoint.Rounding;
import dev.cobolonjava.hlasm.FloatingPoint.Shape;
import dev.cobolonjava.hlasm.FloatingPoint.Value;
import java.math.BigInteger;

/**
 * 浮動小数点のレジスター 16 本と FPC、そしてそれを使う命令。
 *
 * <p>命令の意味は z/Architecture Principles of Operation (SA22-7832) の 18〜20 章に従う。
 * 持っている命令の並びと、PFPO の手順は Zeus (同じ権利者の処理系) の
 * {@code hlasm/execute/floating/} から移した。<b>実機とも Hercules とも突き合わせていない。</b>
 *
 * <p>持たないものは断る。IEEE の割り込みを許したとき (FPC のマスク) の振る舞い、NaN と
 * 無限大を含む一部の IEEE 演算である。値を作れないのに近い値を返すと、
 * 計算の結果が黙って変わる。
 */
final class FloatingUnit {

    /** FPC のうち、意味を持つビット。ほかを立てると指定例外になる。 */
    private static final int CONTROL_BITS = 0xFCFCFF77;

    private static final BigInteger LOW = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private final Cpu cpu;
    private final long[] registers = new long[16];
    private int control;

    FloatingUnit(Cpu cpu) {
        this.cpu = cpu;
    }

    // --- レジスターの読み書き ---

    private BigInteger read(int r, int width) {
        return switch (width) {
            case 4 -> BigInteger.valueOf(registers[r] >>> 32);
            case 8 -> unsigned(registers[r]);
            default -> unsigned(registers[pair(r)]).shiftLeft(64).or(unsigned(registers[r + 2]));
        };
    }

    private void write(int r, int width, BigInteger raw) {
        switch (width) {
            // 短形式は左の 32 ビットだけを使い、右は変えない
            case 4 -> registers[r] = (raw.longValue() << 32) | (registers[r] & 0xFFFFFFFFL);
            case 8 -> registers[r] = raw.longValue();
            default -> {
                registers[pair(r)] = raw.shiftRight(64).longValue();
                registers[r + 2] = raw.and(LOW).longValue();
            }
        }
    }

    /** 拡張形式は 2 本を組で使う。組の頭は 0,1,4,5,8,9,12,13 である。 */
    private static int pair(int r) {
        if ((r & 2) != 0) {
            throw new MachineException(MachineException.SPECIFICATION,
                    "an extended floating-point operand needs a valid register pair, not " + r);
        }
        return r;
    }

    private static BigInteger unsigned(long value) {
        return BigInteger.valueOf(value).and(LOW);
    }

    private static MachineException unsupported(String what) {
        return new MachineException(MachineException.OPERATION, what + " is not supported yet");
    }

    // --- 命令 ---

    /** RR 形式 (命令コード 1 バイト)。 */
    void registerRegister(byte[] insn) {
        int r1 = (insn[1] >> 4) & 0x0F;
        int r2 = insn[1] & 0x0F;
        int opcode = insn[0] & 0xFF;
        switch (opcode) {
            case 0x26 -> {
                hexMultiply(r1, read(r2, 16), 16, 16);
                return;
            }
            case 0x27 -> {
                hexMultiply(r1, read(r2, 8), 8, 16);
                return;
            }
            case 0x36, 0x37 -> {
                hexadecimal(opcode == 0x36 ? 0xA : 0xB, 16, r1, read(r2, 16));
                return;
            }
            default -> { }
        }
        int width = opcode < 0x30 ? 8 : 4;
        int operation = opcode & 0x0F;
        int sourceWidth = operation == 5 ? width * 2 : width;
        hexadecimal(operation, width, r1, read(r2, sourceWidth));
    }

    /** RX 形式。{@code address} は第 2 演算項の番地。 */
    void registerStorage(byte[] insn, int address) {
        int r1 = (insn[1] >> 4) & 0x0F;
        AddressSpace memory = cpu.memory();
        switch (insn[0] & 0xFF) {
            case 0x67 -> hexMultiply(r1, unsigned(memory.getLong(address)), 8, 16); // MXD
            case 0x68 -> registers[r1] = memory.getLong(address); // LD
            case 0x78 -> write(r1, 4, BigInteger.valueOf(memory.getInt(address) & 0xFFFFFFFFL)); // LE
            case 0x60 -> memory.putLong(address, registers[r1]); // STD
            case 0x70 -> memory.putInt(address, (int) (registers[r1] >>> 32)); // STE
            default -> {
                int opcode = insn[0] & 0xFF;
                int width = opcode < 0x70 ? 8 : 4;
                BigInteger source = width == 8 ? unsigned(memory.getLong(address))
                        : BigInteger.valueOf(Integer.toUnsignedLong(memory.getInt(address)));
                hexadecimal(opcode & 0x0F, width, r1, source);
            }
        }
    }

    private void hexadecimal(int operation, int width, int r1, BigInteger second) {
        if (operation <= 3) {
            int signBit = width * 8 - 1;
            BigInteger raw = switch (operation) {
                case 0 -> second.clearBit(signBit);
                case 1 -> second.setBit(signBit);
                case 3 -> second.flipBit(signBit);
                default -> second;
            };
            write(r1, width, raw);
            cpu.setConditionCode(signOf(FloatingPoint.decodeHex(raw, width)));
            return;
        }
        if (operation == 8) {
            write(r1, width, second);
            return;
        }
        if (operation == 5) {
            storeHex(r1, FloatingPoint.decodeHex(second, width * 2), width,
                    FloatingPoint.hexExponent(second, width * 2), Rounding.NEAREST_AWAY);
            return;
        }
        BigInteger first = read(r1, width);
        Ratio a = FloatingPoint.decodeHex(first, width);
        Ratio b = FloatingPoint.decodeHex(second, width);
        Ratio result;
        Integer exponent = null;
        boolean addSubtract = false;
        switch (operation) {
            case 4 -> result = b.divide(Ratio.binary(false, BigInteger.TWO, 0)); // HER/HDR
            case 9 -> {
                cpu.setConditionCode(compare(a, b));
                return;
            }
            case 0xA, 0xB, 0xE, 0xF -> {
                addSubtract = true;
                if ((operation & 1) != 0) {
                    b = b.negate();
                }
                int alignedExponent = Math.max(FloatingPoint.hexExponent(first, width),
                        FloatingPoint.hexExponent(second, width));
                result = a.addWithHexGuard(b, alignedExponent, FloatingPoint.hexPrecision(width));
                if (operation >= 0xE) exponent = alignedExponent;
                if (result.isZero() && (cpu.programMask() & 1) != 0) {
                    write(r1, width, FloatingPoint.hexRaw(false, BigInteger.ZERO, alignedExponent, width));
                    cpu.setConditionCode(0);
                    throw new MachineException(MachineException.HFP_SIGNIFICANCE, "HFP significance exception");
                }
            }
            case 0xC -> {
                hexMultiply(r1, second, width, 8);
                return;
            }
            case 0xD -> {
                if (b.isZero()) {
                    throw new MachineException(MachineException.HFP_DIVIDE, "HFP divide by zero");
                }
                result = a.divide(b);
            }
            default -> throw new MachineException(MachineException.OPERATION,
                    String.format("operation code %02X is not implemented", operation));
        }
        storeHex(r1, result, width, exponent, Rounding.ZERO);
        if (addSubtract) {
            cpu.setConditionCode(signOf(FloatingPoint.decodeHex(read(r1, width), width)));
        }
    }

    private void hexMultiply(int register, BigInteger second, int width, int destination) {
        if (destination == 16) pair(register);
        Ratio left = FloatingPoint.decodeHex(read(register, width), width);
        Ratio right = FloatingPoint.decodeHex(second, width);
        storeHex(register, left.multiply(right), destination, null, Rounding.ZERO);
    }

    private void storeHex(int r1, Ratio result) {
        storeHex(r1, result, 8, null, Rounding.ZERO);
    }

    private void storeHex(int r1, Ratio result, int width, Integer exponent, Rounding rounding) {
        HexResult packed = FloatingPoint.packHex(result, width, exponent, rounding,
                (cpu.programMask() & 2) != 0);
        write(r1, width, packed.raw());
        if (packed.exception() != null) {
            int code = packed.exception().endsWith("underflow")
                    ? MachineException.HFP_EXPONENT_UNDERFLOW : MachineException.HFP_EXPONENT_OVERFLOW;
            throw new MachineException(code, packed.exception());
        }
    }

    private static int signOf(Ratio value) {
        return value.isZero() ? 0 : value.negative() ? 1 : 2;
    }

    private static int compare(Ratio a, Ratio b) {
        // 0 は符号を見ない。+0 と -0 は等しい
        int left = a.isZero() ? 0 : a.negative() ? -1 : 1;
        int right = b.isZero() ? 0 : b.negative() ? -1 : 1;
        int order = left != right ? Integer.compare(left, right)
                : left * a.compareMagnitude(b);
        return order == 0 ? 0 : order < 0 ? 1 : 2;
    }

    /** {@code B2xx} の族のうち浮動小数点のもの。扱ったら {@code true}。 */
    boolean control(byte[] insn, int address) {
        switch (insn[1] & 0xFF) {
            case 0x44 -> { // SQDR
                int r1 = (insn[3] >> 4) & 0x0F;
                Ratio value = FloatingPoint.decodeHex(read(insn[3] & 0x0F, 8), 8);
                if (value.isZero()) {
                    write(r1, 8, BigInteger.ZERO);
                } else if (value.negative()) {
                    throw new MachineException(MachineException.DATA,
                            "HFP square root of a negative value");
                } else {
                    storeHex(r1, FloatingPoint.squareRootHex(value, 8));
                }
            }
            case 0x9C -> cpu.memory().putInt(address, control); // STFPC
            case 0x9D -> setControl(cpu.memory().getInt(address)); // LFPC
            case 0x99 -> control = (control & ~7) | (address & 3); // SRNM
            case 0xB8 -> setControl((control & ~7) | (address & 7)); // SRNMB
            case 0xB9 -> control = (control & ~0x70) | ((address & 7) << 4); // SRNMT
            default -> {
                return false;
            }
        }
        return true;
    }

    private void setControl(int value) {
        if ((value & ~CONTROL_BITS) != 0 || ((value & 7) >= 4 && (value & 7) <= 6)) {
            throw new MachineException(MachineException.SPECIFICATION,
                    "reserved bits are set in the floating-point-control register");
        }
        control = value;
    }

    /** {@code B3xx} の族。 */
    void extended(byte[] insn) {
        int r1 = (insn[3] >> 4) & 0x0F;
        int r2 = insn[3] & 0x0F;
        int r3 = (insn[2] >> 4) & 0x0F;
        int operation = insn[1] & 0xFF;
        switch (operation) {
            case 0x65 -> write(r1, 16, read(r2, 16)); // LXR
            case 0x84 -> setControl(cpu.register(r1)); // SFPC
            case 0x8C -> cpu.setRegister(r1, control); // EFPC
            case 0x1A, 0x1B, 0x1C, 0x1D, 0x15, 0x19 -> binary(operation, r1, r2);
            case 0xD0, 0xD1, 0xD2, 0xD3, 0xE4 -> decimal(operation, r1, r2, r3);
            case 0xEC -> cpu.setConditionCode(compare( // CXTR
                    finite(FloatingPoint.decodeDecimal(read(r1, 16), 16).value()),
                    finite(FloatingPoint.decodeDecimal(read(r2, 16), 16).value())));
            // LPDFR / LNDFR / LCDFR: 符号のビットだけを変える。条件コードは変えない
            case 0x70 -> registers[r1] = registers[r2] & Long.MAX_VALUE;
            case 0x71 -> registers[r1] = registers[r2] | Long.MIN_VALUE;
            case 0x73 -> registers[r1] = registers[r2] ^ Long.MIN_VALUE;
            default -> throw new MachineException(MachineException.OPERATION,
                    String.format("operation code B3%02X is not implemented", operation));
        }
    }


    /**
     * SLDT / SRDT / SLXT / SRXT (RXF)。係数を 10 進の桁で動かし、指数はそのまま残す。
     * 左へはみ出た桁は捨てる。無限大と NaN は係数の続きの欄だけを動かす (先頭の桁を持たない)。
     * 手順は Zeus の {@code hlasm/execute/floating/decimal.rs} の {@code shift} を移した。
     */
    void decimalShift(byte[] insn, int address) {
        int operation = insn[5] & 0xFF;
        int width = operation >= 0x48 ? 16 : 8;
        boolean left = (operation & 1) == 0;
        int r1 = (insn[4] >>> 4) & 0x0F;
        int r3 = (insn[1] >>> 4) & 0x0F;
        if (width == 16) {
            pair(r1);
        }
        BigInteger raw = read(r3, width);
        int count = address & 63;
        int precision = FloatingPoint.decimalPrecision(width);
        int trailing = (precision - 1) / 3 * 10;
        int combination = raw.shiftRight(width * 8 - 6).intValue() & 31;
        boolean special = combination >= 30;
        int capacity = special ? precision - 1 : precision;
        BigInteger digits = FloatingPoint.decimalCoefficient(raw, width);
        digits = left ? digits.multiply(FloatingPoint.pow10(count)).mod(FloatingPoint.pow10(capacity))
                : digits.divide(FloatingPoint.pow10(count));
        boolean negative = raw.testBit(width * 8 - 1);
        BigInteger result;
        if (special) {
            BigInteger payload = FloatingPoint.packDecimal(false, digits, 0, width)
                    .and(BigInteger.ONE.shiftLeft(trailing).subtract(BigInteger.ONE));
            result = raw.shiftRight(width * 8 - 7).shiftLeft(width * 8 - 7).or(payload);
        } else {
            result = FloatingPoint.packDecimal(negative, digits,
                    FloatingPoint.decimalExponent(raw, width), width);
        }
        write(r1, width, result);
    }

    /**
     * CDPT / CXPT / CPDT / CPXT / CDZT / CXZT / CZDT / CZXT (RSL-b)。DFP と記憶域の
     * パック 10 進・ゾーン 10 進のあいだの変換。M3 の 8 が符号つき、4 がゾーンを 3 にする (ASCII)、
     * 2 が正の符号を F にする、1 が (パックでは) 符号を見ない / (逆向きでは) 負の 0 を正にする。
     *
     * <p>DFP へは指数 0 の整数にする。記憶域へは係数を右詰めで書き、入りきらない上の桁は捨てて
     * 条件コード 3 (プログラムマスクが許せば 10 進オーバーフロー) にする。手順は Zeus の
     * {@code hlasm/execute/floating/decimal/memory.rs} を移した。
     */
    void decimalMemory(byte[] insn, int address, boolean decimalOverflowEnabled) {
        int operation = insn[5] & 0xFF;
        boolean zoned = operation < 0xAC;
        boolean toDecimal = (operation & 2) != 0;
        int width = (operation & 1) != 0 ? 16 : 8;
        int r1 = (insn[4] >>> 4) & 0x0F;
        int m3 = insn[4] & 0x0F;
        int length = (insn[1] & 0xFF) + 1;
        if (width == 16) {
            pair(r1);
        }
        int maximum = zoned ? (width == 8 ? 16 : 34) : (width == 8 ? 9 : 18);
        if (length > maximum) {
            throw new MachineException(MachineException.SPECIFICATION,
                    "the field of a DFP memory conversion is longer than " + maximum + " bytes");
        }
        var memory = cpu.memory();
        boolean signed = (m3 & 8) != 0;
        int digitCount = zoned ? length : length * 2 - (signed ? 1 : 0);
        if (toDecimal) {
            byte[] data = memory.read(address, length);
            int sign = zoned ? (data[length - 1] >> 4) & 15 : data[length - 1] & 15;
            boolean negative = false;
            if (signed && (zoned || (m3 & 1) == 0)) {
                if (sign < 10) {
                    throw new MachineException(MachineException.DATA, "invalid decimal sign");
                }
                negative = sign == 11 || sign == 13;
            }
            BigInteger digits = BigInteger.ZERO;
            for (int position = 0; position < digitCount; position++) {
                int digit = zoned ? data[position] & 15
                        : (data[position / 2] >> (position % 2 == 0 ? 4 : 0)) & 15;
                if (digit > 9) {
                    throw new MachineException(MachineException.DATA, "invalid decimal digit");
                }
                digits = digits.multiply(BigInteger.TEN).add(BigInteger.valueOf(digit));
            }
            if (digits.toString().length() > FloatingPoint.decimalPrecision(width)) {
                throw new MachineException(MachineException.DATA, "nonzero unused packed digit");
            }
            write(r1, width, FloatingPoint.packDecimal(negative, digits, 0, width));
            return;
        }
        BigInteger source = read(r1, width);
        BigInteger original = FloatingPoint.decimalCoefficient(source, width);
        BigInteger limit = FloatingPoint.pow10(digitCount);
        boolean lost = original.compareTo(limit) >= 0;
        BigInteger digits = original.mod(limit);
        boolean negative = source.testBit(width * 8 - 1);
        boolean special = (source.shiftRight(width * 8 - 6).intValue() & 31) >= 30;
        byte[] data = new byte[length];
        String text = digits.toString();
        text = "0".repeat(digitCount - text.length()) + text;
        for (int position = 0; position < digitCount; position++) {
            int digit = text.charAt(position) - '0';
            if (zoned) {
                data[position] = (byte) (((m3 & 4) != 0 ? 0x30 : 0xF0) | digit);
            } else {
                data[position / 2] |= (byte) (digit << (position % 2 == 0 ? 4 : 0));
            }
        }
        if (signed) {
            boolean minus = negative && !((m3 & 1) != 0 && digits.signum() == 0);
            int sign = minus ? 13 : (m3 & 2) != 0 ? 15 : 12;
            if (zoned) {
                data[length - 1] = (byte) ((data[length - 1] & 15) | (sign << 4));
            } else {
                data[length - 1] |= (byte) sign;
            }
        }
        memory.write(address, data);
        cpu.setConditionCode(special || lost ? 3 : original.signum() == 0 ? 0 : negative ? 1 : 2);
        if (lost && decimalOverflowEnabled) {
            throw new MachineException(MachineException.DECIMAL_OVERFLOW,
                    "the DFP coefficient does not fit in the decimal field");
        }
    }

    /** 例外の旗を FPC へ残す。割り込みを許していたら断る。 */
    private void raise(int flags) {
        int masks = control >>> 24;
        if ((flags & masks & 0xF8) != 0) {
            throw unsupported("an IEEE exception with its trap enabled in the FPC");
        }
        control |= (flags & 0xF8) << 16;
    }

    private static Ratio finite(Value value) {
        if (value instanceof Finite f) {
            return f.value();
        }
        throw unsupported("arithmetic on an infinity or a NaN");
    }

    /** 長形式の BFP。1A: 加算、1B: 減算、1C: 乗算、1D: 除算、15: 平方根、19: 比較。 */
    private void binary(int operation, int r1, int r2) {
        binary(operation, r1, read(r2, 8), 8);
    }

    /** RXE / RXF の BFP はレジスター形式と同じ丸め・例外処理を使う。 */
    void binaryStorage(byte[] insn, int address) {
        int operation = insn[5] & 0xFF;
        int source = (insn[1] >>> 4) & 0xF;
        if (operation >= 0x50 && operation <= 0x59 && (operation & 2) == 0) {
            int width = 4 << ((operation - 0x50) >>> 2);
            int mask = FloatingPoint.decimalClassMask(read(source, width), width, (operation & 1) != 0);
            cpu.setConditionCode((mask & address) != 0 ? 1 : 0);
            return;
        }
        if (operation >= 0x10 && operation <= 0x12) {
            int width = 4 << (operation - 0x10);
            int mask = FloatingPoint.binaryClassMask(read(source, width), width);
            cpu.setConditionCode((mask & address) != 0 ? 1 : 0);
            return;
        }
        int width = switch (operation) {
            case 0x05, 0x07, 0x15, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F -> 8;
            default -> 4;
        };
        var memory = cpu.memory();
        BigInteger operand = width == 4 ? BigInteger.valueOf(Integer.toUnsignedLong(memory.getInt(address)))
                : unsigned(memory.getLong(address));
        switch (operation) {
            case 0x04, 0x05, 0x06, 0x07, 0x0C -> {
                binaryWiden(source, operand, width, (operation == 0x04 || operation == 0x0C) ? 8 : 16,
                        operation == 0x07 || operation == 0x0C);
                return;
            }
            default -> { }
        }
        int normalized = switch (operation) {
            case 0x08, 0x09, 0x18, 0x19 -> 0x19;
            case 0x0A, 0x1A -> 0x1A;
            case 0x0B, 0x1B -> 0x1B;
            case 0x17, 0x1C -> 0x1C;
            case 0x0D, 0x1D -> 0x1D;
            case 0x14, 0x15 -> 0x15;
            case 0x0E, 0x0F, 0x1E, 0x1F -> operation;
            default -> throw new MachineException(MachineException.OPERATION,
                    String.format("operation code ED%02X is not implemented", operation));
        };
        if (normalized == 0x19) {
            boolean signal = operation == 0x08 || operation == 0x18;
            compareBinary(FloatingPoint.decodeBinary(read(source, width), width),
                    FloatingPoint.decodeBinary(operand, width), signal);
        } else if (normalized == 0x0E || normalized == 0x0F
                || normalized == 0x1E || normalized == 0x1F) {
            int destination = (insn[4] >>> 4) & 0xF;
            Rounding mode = FloatingPoint.binaryRounding(control);
            Ratio product = finite(FloatingPoint.decodeBinary(read(source, width), width))
                    .multiply(finite(FloatingPoint.decodeBinary(operand, width)));
            Ratio accumulator = finite(FloatingPoint.decodeBinary(read(destination, width), width));
            Encoded result = FloatingPoint.encodeBinary(product.add(
                    (operation & 1) == 0 ? accumulator : accumulator.negate(),
                    mode == Rounding.NEGATIVE), width, mode);
            write(destination, width, result.raw());
            raise(result.flags());
        } else {
            binary(normalized, source, operand, width);
        }
    }

    private void binaryWiden(int register, BigInteger operand, int width, int destination,
                             boolean multiply) {
        if (destination == 16) pair(register);
        Value source = FloatingPoint.decodeBinary(operand, width);
        if (!multiply && source instanceof Infinity infinity) {
            write(register, destination, FloatingPoint.binaryInfinity(infinity.negative(), destination));
            return;
        }
        Ratio result = finite(source);
        if (multiply) {
            result = finite(FloatingPoint.decodeBinary(read(register, width), width)).multiply(result);
        }
        Encoded encoded = FloatingPoint.encodeBinary(result, destination, FloatingPoint.binaryRounding(control));
        write(register, destination, encoded.raw());
        raise(encoded.flags());
    }

    private void binary(int operation, int r1, BigInteger operand, int width) {
        Rounding mode = FloatingPoint.binaryRounding(control);
        Value second = FloatingPoint.decodeBinary(operand, width);
        if (operation == 0x19) {
            compareBinary(FloatingPoint.decodeBinary(read(r1, width), width), second, false);
            return;
        }
        Ratio b = finite(second);
        if (operation == 0x15) {
            if (b.negative() && !b.isZero()) {
                throw unsupported("the BFP square root of a negative value");
            }
            Encoded root = FloatingPoint.squareRootBinary(b, width, mode);
            write(r1, width, root.raw());
            raise(root.flags());
            return;
        }
        Ratio a = finite(FloatingPoint.decodeBinary(read(r1, width), width));
        Ratio result = switch (operation) {
            case 0x1A -> a.add(b, mode == Rounding.NEGATIVE);
            case 0x1B -> a.add(b.negate(), mode == Rounding.NEGATIVE);
            case 0x1C -> a.multiply(b);
            default -> {
                if (b.isZero()) {
                    throw unsupported("BFP division by zero");
                }
                yield a.divide(b);
            }
        };
        Encoded encoded = FloatingPoint.encodeBinary(result, width, mode);
        write(r1, width, encoded.raw());
        raise(encoded.flags());
        if (operation == 0x1A || operation == 0x1B) {
            cpu.setConditionCode(signOf(result));
        }
    }

    private void compareBinary(Value left, Value right, boolean signal) {
        if (left instanceof NotANumber || right instanceof NotANumber) {
            boolean signaling = (left instanceof NotANumber a && a.signaling())
                    || (right instanceof NotANumber b && b.signaling());
            if (signal || signaling) {
                raise(FloatingPoint.INVALID);
            }
            cpu.setConditionCode(3);
        } else if (left instanceof Infinity a && right instanceof Infinity b) {
            cpu.setConditionCode(a.negative() == b.negative() ? 0 : a.negative() ? 1 : 2);
        } else if (left instanceof Infinity a) {
            cpu.setConditionCode(a.negative() ? 1 : 2);
        } else if (right instanceof Infinity b) {
            cpu.setConditionCode(b.negative() ? 2 : 1);
        } else {
            cpu.setConditionCode(compare(finite(left), finite(right)));
        }
    }

    /** 長形式の DFP。D0: 乗算、D1: 除算、D2: 加算、D3: 減算、E4: 比較。 */
    private void decimal(int operation, int r1, int r2, int r3) {
        Rounding mode = FloatingPoint.decimalRounding(0, control);
        if (operation == 0xE4) {
            cpu.setConditionCode(compare(
                    finite(FloatingPoint.decodeDecimal(read(r1, 8), 8).value()),
                    finite(FloatingPoint.decodeDecimal(read(r2, 8), 8).value())));
            return;
        }
        DecimalValue second = FloatingPoint.decodeDecimal(read(r2, 8), 8);
        DecimalValue third = FloatingPoint.decodeDecimal(read(r3, 8), 8);
        Ratio b = finite(second.value());
        Ratio c = finite(third.value());
        // 望ましい量子は、加減算なら小さいほうの指数、乗算なら和、除算なら差である
        Ratio result;
        int preferred;
        switch (operation) {
            case 0xD2 -> {
                result = b.add(c, mode == Rounding.NEGATIVE);
                preferred = Math.min(second.exponent(), third.exponent());
            }
            case 0xD3 -> {
                result = b.add(c.negate(), mode == Rounding.NEGATIVE);
                preferred = Math.min(second.exponent(), third.exponent());
            }
            case 0xD0 -> {
                result = b.multiply(c);
                preferred = second.exponent() + third.exponent();
            }
            default -> {
                if (c.isZero()) {
                    throw unsupported("DFP division by zero");
                }
                result = b.divide(c);
                preferred = second.exponent() - third.exponent();
            }
        }
        Encoded encoded = FloatingPoint.encodeDecimal(result, preferred, 8, mode);
        write(r1, 8, encoded.raw());
        raise(encoded.flags());
        if (operation == 0xD2 || operation == 0xD3) {
            cpu.setConditionCode(signOf(result));
        }
    }

    /**
     * PFPO: 基数をまたぐ変換。関数コードは汎用レジスター 0 にあり、入力は浮動小数点
     * レジスター 4 (拡張なら 4 と 6)、出力は 0 (拡張なら 0 と 2) である。汎用レジスター 1 は
     * 戻りコードになる。
     */
    void performOperation() {
        int code = cpu.register(0);
        Shape target = Shape.of((code >>> 16) & 0xFF);
        Shape source = Shape.of((code >>> 8) & 0xFF);
        int controls = code & 0xFF;
        int rm = controls & 15;
        boolean valid = (code & 0x7F000000) == 0x01000000 && (rm <= 1 || rm >= 8)
                && source != null && target != null && source.radix() != target.radix()
                && (target.radix() != Radix.BINARY || (controls & 0x30) == 0);
        if ((code & 0x80000000) != 0) {
            // 試すだけ。関数が使えるかを条件コードで返す
            cpu.setRegister(1, 0);
            cpu.setConditionCode(valid ? 0 : 3);
            return;
        }
        if (!valid) {
            throw new MachineException(MachineException.SPECIFICATION,
                    "PFPO: the function code or its controls are not valid");
        }
        Rounding rounding = rm == 1 ? FloatingPoint.binaryRounding(control)
                : FloatingPoint.decimalRounding(rm, control);
        Value value = source.decode(read(4, source.width()));
        Converted converted;
        if (value instanceof Finite f) {
            converted = FloatingPoint.convert(f.value(), target, rounding, controls);
        } else if (value instanceof Infinity infinity) {
            converted = FloatingPoint.convertInfinity(infinity.negative(), target);
        } else {
            throw unsupported("PFPO on a NaN");
        }
        int masks = control >>> 24;
        boolean inexact = (converted.flags() & FloatingPoint.INEXACT) != 0
                && (controls & 0x80) == 0;
        boolean quantum = target.radix() == Radix.DECIMAL && (controls & 0x20) != 0
                && converted.quantum();
        int raised = (converted.flags() & 0xB0) | (inexact ? FloatingPoint.INEXACT : 0)
                | (quantum ? 0x04 : 0);
        if ((raised & masks) != 0) {
            throw unsupported("PFPO with an IEEE exception whose trap is enabled in the FPC");
        }
        write(0, target.width(), converted.raw());
        cpu.setRegister(1, 0);
        control |= raised << 16;
        cpu.setConditionCode(raised != 0 ? 1 : 0);
    }
}
