package dev.cobolonjava.hlasm;

import dev.cobolonjava.hlasm.FloatingPoint.Converted;
import dev.cobolonjava.hlasm.FloatingPoint.DecimalValue;
import dev.cobolonjava.hlasm.FloatingPoint.Encoded;
import dev.cobolonjava.hlasm.FloatingPoint.Finite;
import dev.cobolonjava.hlasm.FloatingPoint.HexResult;
import dev.cobolonjava.hlasm.FloatingPoint.Infinity;
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
 * 無限大の演算、短形式と拡張形式の HFP の演算である。値を作れないのに近い値を返すと、
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
        switch (insn[0] & 0xFF) {
            case 0x28 -> registers[r1] = registers[r2]; // LDR
            case 0x38 -> write(r1, 4, read(r2, 4)); // LER
            default -> hexadecimal((insn[0] & 0x0F) | 0x60, r1, unsigned(registers[r2]));
        }
    }

    /** RX 形式。{@code address} は第 2 演算項の番地。 */
    void registerStorage(byte[] insn, int address) {
        int r1 = (insn[1] >> 4) & 0x0F;
        AddressSpace memory = cpu.memory();
        switch (insn[0] & 0xFF) {
            case 0x68 -> registers[r1] = memory.getLong(address); // LD
            case 0x78 -> write(r1, 4, BigInteger.valueOf(memory.getInt(address) & 0xFFFFFFFFL)); // LE
            case 0x60 -> memory.putLong(address, registers[r1]); // STD
            case 0x70 -> memory.putInt(address, (int) (registers[r1] >>> 32)); // STE
            default -> hexadecimal(insn[0] & 0xFF, r1, unsigned(memory.getLong(address)));
        }
    }

    /**
     * 長形式の HFP の演算。{@code operation} は RX 形式の命令コードで表す
     * (69: 比較、6A: 加算、6B: 減算、6C: 乗算、6D: 除算)。
     */
    private void hexadecimal(int operation, int r1, BigInteger second) {
        BigInteger first = read(r1, 8);
        Ratio a = FloatingPoint.decodeHex(first, 8);
        Ratio b = FloatingPoint.decodeHex(second, 8);
        Ratio result;
        switch (operation) {
            case 0x69 -> {
                cpu.setConditionCode(compare(a, b));
                return;
            }
            case 0x6A, 0x6B -> {
                if (operation == 0x6B) {
                    b = b.negate();
                }
                int exponent = Math.max(FloatingPoint.hexExponent(first, 8),
                        FloatingPoint.hexExponent(second, 8));
                result = a.addWithHexGuard(b, exponent, 56);
            }
            case 0x6C -> result = a.multiply(b);
            case 0x6D -> {
                if (b.isZero()) {
                    throw new MachineException(MachineException.DATA, "HFP divide by zero");
                }
                result = a.divide(b);
            }
            default -> throw new MachineException(MachineException.OPERATION,
                    String.format("operation code %02X is not implemented", operation));
        }
        storeHex(r1, result);
        if (operation == 0x6A || operation == 0x6B) {
            cpu.setConditionCode(signOf(FloatingPoint.decodeHex(read(r1, 8), 8)));
        }
    }

    private void storeHex(int r1, Ratio result) {
        HexResult packed = FloatingPoint.packHex(result, 8, null, Rounding.ZERO,
                (cpu.programMask() & 2) != 0);
        write(r1, 8, packed.raw());
        if (packed.exception() != null) {
            throw new MachineException(MachineException.DATA, packed.exception());
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
            case 0x84 -> setControl(cpu.register(r1)); // SFPC
            case 0x8C -> cpu.setRegister(r1, control); // EFPC
            case 0x1A, 0x1B, 0x1C, 0x1D, 0x15, 0x19 -> binary(operation, r1, r2);
            case 0xD0, 0xD1, 0xD2, 0xD3, 0xE4 -> decimal(operation, r1, r2, r3);
            default -> throw new MachineException(MachineException.OPERATION,
                    String.format("operation code B3%02X is not implemented", operation));
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
        Rounding mode = FloatingPoint.binaryRounding(control);
        Ratio b = finite(FloatingPoint.decodeBinary(read(r2, 8), 8));
        if (operation == 0x15) {
            if (b.negative() && !b.isZero()) {
                throw unsupported("the BFP square root of a negative value");
            }
            Encoded root = FloatingPoint.squareRootBinary(b, 8, mode);
            write(r1, 8, root.raw());
            raise(root.flags());
            return;
        }
        Ratio a = finite(FloatingPoint.decodeBinary(read(r1, 8), 8));
        if (operation == 0x19) {
            cpu.setConditionCode(compare(a, b));
            return;
        }
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
        Encoded encoded = FloatingPoint.encodeBinary(result, 8, mode);
        write(r1, 8, encoded.raw());
        raise(encoded.flags());
        if (operation == 0x1A || operation == 0x1B) {
            cpu.setConditionCode(signOf(result));
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
