package dev.cobolonjava.oracle.machine;

/**
 * z/Architecture の機械語命令の組み立て (要件 FR-211)。
 *
 * <p>外部アセンブラには依存しない。オラクルとして必要になるのは SS 形式の 10 進命令が中心であり、
 * いずれも固定長・固定レイアウトであるため、小さなエンコーダで足りる。
 *
 * <p>すべての命令でベースレジスタに 0 を用いる。ベースレジスタ 0 は「ベースなし」を意味し、
 * 実効アドレスが変位そのものになるため、アドレスは 0〜4095 に限られる。
 * オラクルのテストは小さなメモリ領域で完結するため、この制約で十分である。
 */
public final class Insn {

    /** ベースレジスタ 0 を用いる場合にアドレスとして指定できる上限。 */
    public static final int MAX_ADDRESS = 0xFFF;

    // --- SS-b 形式 (2 つの長さを持つ 10 進命令) の命令コード ---
    private static final int OP_MVO = 0xF1;
    private static final int OP_PACK = 0xF2;
    private static final int OP_UNPK = 0xF3;
    private static final int OP_ZAP = 0xF8;
    private static final int OP_CP = 0xF9;
    private static final int OP_AP = 0xFA;
    private static final int OP_SP = 0xFB;
    private static final int OP_MP = 0xFC;
    private static final int OP_DP = 0xFD;

    // --- SS-a 形式 (1 つの長さ) の命令コード ---
    private static final int OP_MVC = 0xD2;
    private static final int OP_CLC = 0xD5;
    private static final int OP_ED = 0xDE;
    private static final int OP_EDMK = 0xDF;

    // --- SS-c 形式 ---
    private static final int OP_SRP = 0xF0;

    private Insn() {
    }

    /** {@code AP D1(L1),D2(L2)} — 10 進加算。 */
    public static byte[] ap(int addr1, int len1, int addr2, int len2) {
        return ssb(OP_AP, addr1, len1, addr2, len2);
    }

    /** {@code SP D1(L1),D2(L2)} — 10 進減算。 */
    public static byte[] sp(int addr1, int len1, int addr2, int len2) {
        return ssb(OP_SP, addr1, len1, addr2, len2);
    }

    /** {@code MP D1(L1),D2(L2)} — 10 進乗算。 */
    public static byte[] mp(int addr1, int len1, int addr2, int len2) {
        return ssb(OP_MP, addr1, len1, addr2, len2);
    }

    /** {@code DP D1(L1),D2(L2)} — 10 進除算。 */
    public static byte[] dp(int addr1, int len1, int addr2, int len2) {
        return ssb(OP_DP, addr1, len1, addr2, len2);
    }

    /** {@code ZAP D1(L1),D2(L2)} — ゼロ埋めして加算 (実質は符号込みの転記)。 */
    public static byte[] zap(int addr1, int len1, int addr2, int len2) {
        return ssb(OP_ZAP, addr1, len1, addr2, len2);
    }

    /** {@code CP D1(L1),D2(L2)} — 10 進比較。結果は条件コードに残る。 */
    public static byte[] cp(int addr1, int len1, int addr2, int len2) {
        return ssb(OP_CP, addr1, len1, addr2, len2);
    }

    /** {@code PACK D1(L1),D2(L2)} — ゾーン10進からパック10進へ。 */
    public static byte[] pack(int addr1, int len1, int addr2, int len2) {
        return ssb(OP_PACK, addr1, len1, addr2, len2);
    }

    /** {@code UNPK D1(L1),D2(L2)} — パック10進からゾーン10進へ。 */
    public static byte[] unpk(int addr1, int len1, int addr2, int len2) {
        return ssb(OP_UNPK, addr1, len1, addr2, len2);
    }

    /** {@code MVO D1(L1),D2(L2)} — オフセット付き転記。 */
    public static byte[] mvo(int addr1, int len1, int addr2, int len2) {
        return ssb(OP_MVO, addr1, len1, addr2, len2);
    }

    /** {@code MVC D1(L),D2} — 文字転記。 */
    public static byte[] mvc(int addr1, int len, int addr2) {
        return ssa(OP_MVC, addr1, len, addr2);
    }

    /** {@code CLC D1(L),D2} — 論理比較。 */
    public static byte[] clc(int addr1, int len, int addr2) {
        return ssa(OP_CLC, addr1, len, addr2);
    }

    /**
     * {@code ED D1(L),D2} — 編集。第 1 オペランドの編集マスクを、第 2 オペランドの
     * パック10進数で置き換える。COBOL の数字編集項目への転記がこの命令に対応する。
     */
    public static byte[] ed(int addr1, int len, int addr2) {
        return ssa(OP_ED, addr1, len, addr2);
    }

    /** {@code EDMK D1(L),D2} — 編集して 有効数字の位置を記録する。 */
    public static byte[] edmk(int addr1, int len, int addr2) {
        return ssa(OP_EDMK, addr1, len, addr2);
    }

    /**
     * {@code SRP D1(L1),D2,I3} — 10 進のシフトと丸め。
     * COBOL の {@code ROUNDED} 句はこの命令に対応する。
     *
     * @param shiftAmount 桁移動量。正なら左シフト、負なら右シフト。下位 6 ビットが 2 の補数として使われる
     * @param roundingDigit 右シフトで捨てられる最上位の桁に加える値 (0〜9)。
     *                      5 を指定すると四捨五入、0 を指定すると切り捨てになる
     */
    public static byte[] srp(int addr1, int len1, int shiftAmount, int roundingDigit) {
        checkLength4Bit(len1, "L1");
        if (roundingDigit < 0 || roundingDigit > 9) {
            throw new IllegalArgumentException("rounding digit must be 0..9: " + roundingDigit);
        }
        int shiftField = shiftAmount & 0x3F;
        return new byte[] {
                (byte) OP_SRP,
                (byte) (((len1 - 1) << 4) | roundingDigit),
                (byte) ((addr1 >>> 8) & 0x0F), (byte) (addr1 & 0xFF),
                (byte) ((shiftField >>> 8) & 0x0F), (byte) (shiftField & 0xFF)
        };
    }

    /** {@code LA R1,D2} — アドレスを汎用レジスタへロードする。 */
    public static byte[] la(int r1, int addr) {
        checkRegister(r1);
        checkAddress(addr);
        return new byte[] {
                0x41,
                (byte) (r1 << 4),
                (byte) ((addr >>> 8) & 0x0F), (byte) (addr & 0xFF)
        };
    }

    /**
     * {@code BCTR R1,0} — 汎用レジスタから 1 を引く。第 2 オペランドが 0 なので分岐はしない。
     *
     * <p>{@code EDMK} が返した「最初の有効数字の位置」の 1 つ手前へアドレスを動かすために使う。
     * 浮動挿入の記号はそこへ置かれる。
     */
    public static byte[] bctr(int r1) {
        checkRegister(r1);
        return new byte[] {0x06, (byte) (r1 << 4)};
    }

    /** {@code MVI D1(B1),I2} — 即値 1 バイトを記憶域へ格納する。 */
    public static byte[] mvi(int baseRegister, int displacement, byte value) {
        checkRegister(baseRegister);
        if (displacement < 0 || displacement > 0xFFF) {
            throw new IllegalArgumentException("displacement must be 0..4095: " + displacement);
        }
        return new byte[] {
                (byte) 0x92,
                value,
                (byte) ((baseRegister << 4) | ((displacement >>> 8) & 0x0F)),
                (byte) (displacement & 0xFF)
        };
    }

    /**
     * {@code IPM R1} — プログラムマスクと条件コードを汎用レジスタへ取り出す。
     *
     * <p>比較命令の結果は条件コードにしか残らず、そのままでは記憶域から読み出せない。
     * この命令でレジスタへ移し、{@link #st} で記憶域へ格納することで採取できるようになる。
     *
     * <p>格納されたワードの先頭バイトのビット 2〜3 が条件コードである
     * (すなわち先頭バイトの値は {@code 条件コード << 4})。
     */
    public static byte[] ipm(int r1) {
        checkRegister(r1);
        return new byte[] {(byte) 0xB2, 0x22, 0x00, (byte) (r1 << 4)};
    }

    /** {@code ST R1,D2} — 汎用レジスタの下位 4 バイトを記憶域へ格納する。 */
    public static byte[] st(int r1, int addr) {
        checkRegister(r1);
        checkAddress(addr);
        return new byte[] {
                0x50,
                (byte) (r1 << 4),
                (byte) ((addr >>> 8) & 0x0F), (byte) (addr & 0xFF)
        };
    }

    /** {@code LPSWE D2} — 16 バイトの PSW をロードする。テストの終了に用いる。 */
    public static byte[] lpswe(int addr) {
        checkAddress(addr);
        return new byte[] {
                (byte) 0xB2, (byte) 0xB2,
                (byte) ((addr >>> 8) & 0x0F), (byte) (addr & 0xFF)
        };
    }

    /** SS-b 形式: 命令コード、L1 と L2 のニブル、B1D1、B2D2 の 6 バイト。 */
    static byte[] ssb(int opcode, int addr1, int len1, int addr2, int len2) {
        checkAddress(addr1);
        checkAddress(addr2);
        checkLength4Bit(len1, "L1");
        checkLength4Bit(len2, "L2");
        return new byte[] {
                (byte) opcode,
                (byte) (((len1 - 1) << 4) | (len2 - 1)),
                (byte) ((addr1 >>> 8) & 0x0F), (byte) (addr1 & 0xFF),
                (byte) ((addr2 >>> 8) & 0x0F), (byte) (addr2 & 0xFF)
        };
    }

    /** SS-a 形式: 命令コード、長さ 1 バイト、B1D1、B2D2 の 6 バイト。 */
    static byte[] ssa(int opcode, int addr1, int len, int addr2) {
        checkAddress(addr1);
        checkAddress(addr2);
        if (len < 1 || len > 256) {
            throw new IllegalArgumentException("length must be 1..256: " + len);
        }
        return new byte[] {
                (byte) opcode,
                (byte) (len - 1),
                (byte) ((addr1 >>> 8) & 0x0F), (byte) (addr1 & 0xFF),
                (byte) ((addr2 >>> 8) & 0x0F), (byte) (addr2 & 0xFF)
        };
    }

    private static void checkAddress(int addr) {
        if (addr < 0 || addr > MAX_ADDRESS) {
            throw new IllegalArgumentException(String.format(
                    "address 0x%X is out of range for base-0 addressing (0..0x%X)", addr, MAX_ADDRESS));
        }
    }

    private static void checkRegister(int r) {
        if (r < 0 || r > 15) {
            throw new IllegalArgumentException("register must be 0..15: " + r);
        }
    }

    private static void checkLength4Bit(int len, String what) {
        if (len < 1 || len > 16) {
            throw new IllegalArgumentException(what + " must be 1..16: " + len);
        }
    }
}
