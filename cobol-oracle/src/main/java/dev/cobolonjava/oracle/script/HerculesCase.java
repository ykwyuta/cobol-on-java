package dev.cobolonjava.oracle.script;

import dev.cobolonjava.oracle.machine.Insn;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Hercules 上で 1 件の命令列を実行し、記憶域を読み出すテストケース (要件 FR-210)。
 *
 * <p>生成物は Hercules のコンソールコマンドからなる {@code .tst} スクリプトである。
 * 実行の流れは Hercules のテスト機構そのものに従う。
 *
 * <pre>
 * sysclear / archlvl   マシン状態とアーキテクチャモードの設定
 * r &lt;addr&gt;=&lt;hex&gt;       記憶域の初期化 (PSW、命令列、データ)
 * runtest              再始動割込みで実行を開始し、全 CPU 停止で制御を戻す
 * r &lt;addr&gt;.&lt;len&gt;       記憶域の読み出し
 * </pre>
 *
 * <p>プログラム割込みが起きた場合、割込み新 PSW によって命令アドレス {@code 0xDEAD} の
 * 待機状態へ入る。正常終了時は命令アドレス 0 の待機状態になる。この違いによって、
 * データ例外 ({@code S0C7} の元となる) の発生を検出できる。
 */
public final class HerculesCase {

    /** z/Arch の再始動新 PSW の位置。 */
    private static final int RESTART_PSW_ADDR = 0x1A0;
    /** z/Arch のプログラム割込み新 PSW の位置。 */
    private static final int PGM_NEW_PSW_ADDR = 0x1D0;
    /** 命令列の先頭。 */
    private static final int CODE_ORIGIN = 0x200;
    /** 正常終了用の待機 PSW の位置。 */
    private static final int GOOD_PSW_ADDR = 0x380;
    /** データ領域の先頭。 */
    private static final int DATA_ORIGIN = 0x400;

    /** プログラム割込みが起きたことを示す、待機 PSW の命令アドレス。 */
    public static final int PROGRAM_CHECK_MARKER = 0xDEAD;

    private static final String RESTART_PSW = "0000000180000000000000000000" + hex16(CODE_ORIGIN);
    private static final String PGM_NEW_PSW = "0002000180000000000000000000DEAD";
    private static final String GOOD_PSW = "00020001800000000000000000000000";

    /** 読み出す記憶域の範囲。 */
    public record Dump(String label, int address, int length) {
    }

    private final String name;
    private final NavigableMap<Integer, byte[]> data = new TreeMap<>();
    private final List<byte[]> instructions = new ArrayList<>();
    private final List<Dump> dumps = new ArrayList<>();
    private int nextData = DATA_ORIGIN;

    public HerculesCase(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    /**
     * データ領域を確保して初期値を書き込み、その先頭アドレスを返す。
     * アドレスは 8 バイト境界に揃える。読みやすさのためであり、意味論上の要求ではない。
     */
    public int data(byte[] bytes) {
        int addr = nextData;
        data.put(addr, bytes.clone());
        nextData = align(addr + bytes.length, 8);
        checkDataFits();
        return addr;
    }

    /** ゼロで初期化した領域を確保する。 */
    public int reserve(int length) {
        return data(new byte[length]);
    }

    /** 命令を 1 個追加する。 */
    public HerculesCase emit(byte[] instruction) {
        instructions.add(instruction.clone());
        return this;
    }

    /** 実行後に読み出す範囲を指定する。 */
    public HerculesCase dump(String label, int address, int length) {
        dumps.add(new Dump(label, address, length));
        return this;
    }

    public List<Dump> dumps() {
        return List.copyOf(dumps);
    }

    /** 生成される {@code .tst} スクリプト。 */
    public String toScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("*Testcase ").append(name).append('\n');
        sb.append("sysclear\n");
        sb.append("archlvl z/Arch\n");
        sb.append("r ").append(hexAddr(RESTART_PSW_ADDR)).append('=').append(RESTART_PSW)
                .append("   # restart PSW: 実行は 0x").append(hexAddr(CODE_ORIGIN)).append(" から\n");
        sb.append("r ").append(hexAddr(PGM_NEW_PSW_ADDR)).append('=').append(PGM_NEW_PSW)
                .append("   # program new PSW: 割込み時は 0xDEAD で待機\n");

        int addr = CODE_ORIGIN;
        for (byte[] insn : instructions) {
            sb.append("r ").append(hexAddr(addr)).append('=').append(hex(insn)).append('\n');
            addr += insn.length;
        }
        byte[] end = Insn.lpswe(GOOD_PSW_ADDR);
        if (addr + end.length > GOOD_PSW_ADDR) {
            throw new IllegalStateException("instruction stream overruns the good-PSW area");
        }
        sb.append("r ").append(hexAddr(addr)).append('=').append(hex(end))
                .append("   # LPSWE: 正常終了\n");
        sb.append("r ").append(hexAddr(GOOD_PSW_ADDR)).append('=').append(GOOD_PSW)
                .append("   # good PSW: 命令アドレス 0 の待機状態\n");

        for (var e : data.entrySet()) {
            if (e.getValue().length > 0) {
                sb.append("r ").append(hexAddr(e.getKey())).append('=').append(hex(e.getValue()))
                        .append('\n');
            }
        }

        sb.append("runtest 1\n");
        sb.append("*Compare\n");
        for (Dump d : dumps) {
            sb.append("r ").append(hexAddr(d.address())).append('.').append(d.length())
                    .append("   # ").append(d.label()).append('\n');
        }
        sb.append("*Done\n");
        return sb.toString();
    }

    private void checkDataFits() {
        if (nextData > Insn.MAX_ADDRESS + 1) {
            throw new IllegalStateException(
                    "data area exceeds the base-0 addressing limit of 0x" + Integer.toHexString(Insn.MAX_ADDRESS));
        }
    }

    private static int align(int value, int alignment) {
        int r = value % alignment;
        return r == 0 ? value : value + (alignment - r);
    }

    private static String hexAddr(int addr) {
        return String.format("%X", addr);
    }

    private static String hex16(int value) {
        return String.format("%04X", value);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
