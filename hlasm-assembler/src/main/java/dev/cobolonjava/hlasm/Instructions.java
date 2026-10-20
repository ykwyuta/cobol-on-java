package dev.cobolonjava.hlasm;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 機械命令の表 (z/Architecture Principles of Operation による)。
 *
 * <p>根拠は公開仕様だけである。IBM 製品のソースも、オラクルとして使う Hercules の
 * ソースも読まない (Hercules は QPL であり Apache-2.0 と両立しない)。
 *
 * <p>{@code cobol-oracle} の {@code Insn} が SS 形式の 10 進命令について同じ命令コードを持つ。
 * あちらはオラクルへ流す短い命令列を組むためのもので、こちらは原文からの組み立てである。
 * 2 つの表が食い違うと測定が信用できなくなるため、{@code InsnAgreementTest} で突き合わせている。
 *
 * <p>増分 1 の範囲は、COBOL から呼ばれる副プログラムが使う命令に限る。浮動小数点、権限命令、
 * クロスメモリ、ベクトル命令は<b>意図して持たない</b>。近い振る舞いを黙って返すより、
 * 知らない命令として断るほうがよい。
 */
public final class Instructions {

    /** 命令の形式。命令語の組み立て方と長さを決める。 */
    public enum Format {
        /** {@code op I} — 2 バイト。{@code SVC} だけが使う。 */
        I(2),
        /** {@code op R1,R2} — 2 バイト。 */
        RR(2),
        /** {@code op R1} — 2 バイト。R2 の欄は 0。{@code SPM} が使う。 */
        RR_R1(2),
        /** {@code op R1} — 4 バイト。命令コードは 2 バイト、R2 の欄は 0。{@code IPM} が使う。 */
        RRE_R1(4),
        /** {@code op M1,R2} — 2 バイト。分岐の第 1 演算項はマスクである。 */
        RR_MASK(2),
        /** {@code op R1,D2(X2,B2)} — 4 バイト。 */
        RX(4),
        /** {@code op M1,D2(X2,B2)} — 4 バイト。 */
        RX_MASK(4),
        /** {@code op R1,R3,D2(B2)} — 4 バイト。 */
        RS(4),
        /** {@code op R1,D2(B2)} — 4 バイト。シフトは第 3 演算項を持たない。 */
        RS_SHIFT(4),
        /** {@code op D1(B1),I2} — 4 バイト。 */
        SI(4),
        /** {@code op D1(L,B1),D2(B2)} — 6 バイト。長さは 1 つ。 */
        SS_A(6),
        /** {@code op D1(L1,B1),D2(L2,B2)} — 6 バイト。長さは 2 つ。 */
        SS_B(6),
        /** {@code op D1(L1,B1),D2(B2),I3} — 6 バイト。{@code SRP} だけが使う。 */
        SS_C(6),
        /** {@code op} — 2 バイトの命令コードだけ。{@code SAM24} / {@code SAM31} が使う。 */
        E(2),
        /** {@code op R1,M3,D2(B2)} — 4 バイト。{@code ICM} など。第 2 演算項はマスクである。 */
        RS_MASK(4),
        /** {@code op R1,I2} — 4 バイト。命令コードは 12 ビット (A7x)、I2 は符号付き 16 ビット。 */
        RI(4),
        /** {@code op R1,I2} — 6 バイト。命令コードは 12 ビット (C0x)、I2 は 32 ビット。 */
        RIL(6),
        /** {@code op R1,R2} — 4 バイト。命令コードは 16 ビット (B9xx)。 */
        RRE(4),
        /** {@code op R1,R2[,M3]} — 4 バイト。M3 は省ける。{@code POPCNT} が使う。 */
        RRF_M3(4),
        /** {@code op R1,R2,R3} — 4 バイト。結果の置き場を別に指す (distinct operands)。 */
        RRF_R3(4),
        /** {@code op R1,D2(X2,B2)} — 6 バイト。変位は符号付き 20 ビット。 */
        RXY(6),
        /** {@code op R1,R3,D2(B2)} — 6 バイト。変位は符号付き 20 ビット。 */
        RSY(6),
        /** {@code op R1,M3,D2(B2)} — 6 バイト。{@code ICMH} が使う。 */
        RSY_MASK(6),
        /** {@code op R1,R2,I3,I4[,I5]} — 6 バイト。{@code RISBG} が使う。 */
        RIE_F(6),
        /** {@code op D1(L1,B1)} — 6 バイト。{@code TP} が使う。 */
        RSL(6);

        private final int length;

        Format(int length) {
            this.length = length;
        }

        public int length() {
            return length;
        }
    }

    /**
     * 命令 1 つ。
     *
     * @param mnemonic 綴り (大文字)
     * @param opcode   命令コード
     * @param format   形式
     * @param mask     拡張ニーモニックが決める分岐マスク。ふつうの命令は {@code null}
     */
    public record Definition(String mnemonic, int opcode, Format format, Integer mask) {
    }

    private static final Map<String, Definition> TABLE = new LinkedHashMap<>();

    private Instructions() {
    }

    private static void define(String mnemonic, int opcode, Format format) {
        TABLE.put(mnemonic, new Definition(mnemonic, opcode, format, null));
    }

    /** 分岐マスクを綴りに畳み込んだ拡張ニーモニック。 */
    private static void extended(String mnemonic, int opcode, Format format, int mask) {
        TABLE.put(mnemonic, new Definition(mnemonic, opcode, format, mask));
    }

    static {
        // --- I 形式 ---
        define("SVC", 0x0A, Format.I);

        // --- RR 形式 ---
        define("BALR", 0x05, Format.RR);
        define("BCTR", 0x06, Format.RR);
        define("BCR", 0x07, Format.RR_MASK);
        define("BASR", 0x0D, Format.RR);
        define("LPR", 0x10, Format.RR);
        define("LNR", 0x11, Format.RR);
        define("LTR", 0x12, Format.RR);
        define("LCR", 0x13, Format.RR);
        define("NR", 0x14, Format.RR);
        define("CLR", 0x15, Format.RR);
        define("OR", 0x16, Format.RR);
        define("XR", 0x17, Format.RR);
        define("LR", 0x18, Format.RR);
        define("CR", 0x19, Format.RR);
        define("AR", 0x1A, Format.RR);
        define("SR", 0x1B, Format.RR);
        define("MR", 0x1C, Format.RR);
        define("DR", 0x1D, Format.RR);
        define("ALR", 0x1E, Format.RR);
        define("SLR", 0x1F, Format.RR);

        // --- RX 形式 ---
        define("STH", 0x40, Format.RX);
        define("LA", 0x41, Format.RX);
        define("STC", 0x42, Format.RX);
        define("IC", 0x43, Format.RX);
        define("EX", 0x44, Format.RX);
        define("BAL", 0x45, Format.RX);
        define("BCT", 0x46, Format.RX);
        define("BC", 0x47, Format.RX_MASK);
        define("LH", 0x48, Format.RX);
        define("CH", 0x49, Format.RX);
        define("AH", 0x4A, Format.RX);
        define("SH", 0x4B, Format.RX);
        define("MH", 0x4C, Format.RX);
        define("BAS", 0x4D, Format.RX);
        define("CVD", 0x4E, Format.RX);
        define("CVB", 0x4F, Format.RX);
        define("ST", 0x50, Format.RX);
        define("N", 0x54, Format.RX);
        define("CL", 0x55, Format.RX);
        define("O", 0x56, Format.RX);
        define("X", 0x57, Format.RX);
        define("L", 0x58, Format.RX);
        define("C", 0x59, Format.RX);
        define("A", 0x5A, Format.RX);
        define("S", 0x5B, Format.RX);
        define("M", 0x5C, Format.RX);
        define("D", 0x5D, Format.RX);
        define("AL", 0x5E, Format.RX);
        define("SL", 0x5F, Format.RX);

        // --- RS 形式 ---
        define("BXH", 0x86, Format.RS);
        define("BXLE", 0x87, Format.RS);
        define("SRL", 0x88, Format.RS_SHIFT);
        define("SLL", 0x89, Format.RS_SHIFT);
        define("SRA", 0x8A, Format.RS_SHIFT);
        define("SLA", 0x8B, Format.RS_SHIFT);
        define("SRDL", 0x8C, Format.RS_SHIFT);
        define("SLDL", 0x8D, Format.RS_SHIFT);
        define("SRDA", 0x8E, Format.RS_SHIFT);
        define("SLDA", 0x8F, Format.RS_SHIFT);
        define("STM", 0x90, Format.RS);
        define("LM", 0x98, Format.RS);

        // --- SI 形式 ---
        define("TM", 0x91, Format.SI);
        define("MVI", 0x92, Format.SI);
        define("NI", 0x94, Format.SI);
        define("CLI", 0x95, Format.SI);
        define("OI", 0x96, Format.SI);
        define("XI", 0x97, Format.SI);

        // --- SS 形式 (長さ 1 つ) ---
        define("MVN", 0xD1, Format.SS_A);
        define("MVC", 0xD2, Format.SS_A);
        define("MVZ", 0xD3, Format.SS_A);
        define("NC", 0xD4, Format.SS_A);
        define("CLC", 0xD5, Format.SS_A);
        define("OC", 0xD6, Format.SS_A);
        define("XC", 0xD7, Format.SS_A);
        define("TR", 0xDC, Format.SS_A);
        define("TRT", 0xDD, Format.SS_A);
        define("ED", 0xDE, Format.SS_A);
        define("EDMK", 0xDF, Format.SS_A);

        // --- SS 形式 (長さ 2 つ、10 進命令) ---
        define("MVO", 0xF1, Format.SS_B);
        define("PACK", 0xF2, Format.SS_B);
        define("UNPK", 0xF3, Format.SS_B);
        define("ZAP", 0xF8, Format.SS_B);
        define("CP", 0xF9, Format.SS_B);
        define("AP", 0xFA, Format.SS_B);
        define("SP", 0xFB, Format.SS_B);
        define("MP", 0xFC, Format.SS_B);
        define("DP", 0xFD, Format.SS_B);

        // --- SS 形式 (シフトと丸め) ---
        define("SRP", 0xF0, Format.SS_C);
        // プログラムマスクと条件コードの出し入れ (P-174)。COBOL から呼ばれた副プログラムが
        // 固定小数点のあふれを割込みにするかどうかを自分で決めるのに使う
        define("SPM", 0x04, Format.RR_R1);
        define("IPM", 0xB222, Format.RRE_R1);

        // --- z/Architecture の 64 ビットの命令と、ZHVS が使う追加の命令 (Principles of Operation) ---
        define("SAM24", 0x010C, Format.E);
        define("SAM31", 0x010D, Format.E);
        define("ICM", 0xBF, Format.RS_MASK);
        define("STCM", 0xBE, Format.RS_MASK);
        define("CLM", 0xBD, Format.RS_MASK);
        define("CS", 0xBA, Format.RS);
        define("LHI", 0xA78, Format.RI);
        define("LGHI", 0xA79, Format.RI);
        define("AHI", 0xA7A, Format.RI);
        define("AGHI", 0xA7B, Format.RI);
        define("MHI", 0xA7C, Format.RI);
        define("MGHI", 0xA7D, Format.RI);
        define("CHI", 0xA7E, Format.RI);
        define("CGHI", 0xA7F, Format.RI);
        define("IIHF", 0xC08, Format.RIL);
        define("IILF", 0xC09, Format.RIL);
        define("LPGR", 0xB900, Format.RRE);
        define("LNGR", 0xB901, Format.RRE);
        define("LTGR", 0xB902, Format.RRE);
        define("LCGR", 0xB903, Format.RRE);
        define("LGR", 0xB904, Format.RRE);
        define("AGR", 0xB908, Format.RRE);
        define("SGR", 0xB909, Format.RRE);
        define("ALGR", 0xB90A, Format.RRE);
        define("SLGR", 0xB90B, Format.RRE);
        define("LGFR", 0xB914, Format.RRE);
        define("LLGFR", 0xB916, Format.RRE);
        define("AGFR", 0xB918, Format.RRE);
        define("SGFR", 0xB919, Format.RRE);
        define("CGR", 0xB920, Format.RRE);
        define("CLGR", 0xB921, Format.RRE);
        define("NGR", 0xB980, Format.RRE);
        define("OGR", 0xB981, Format.RRE);
        define("XGR", 0xB982, Format.RRE);
        define("FLOGR", 0xB983, Format.RRE);
        define("ALCGR", 0xB988, Format.RRE);
        define("POPCNT", 0xB9E1, Format.RRF_M3);
        define("XGRK", 0xB9E7, Format.RRF_R3);
        define("LG", 0xE304, Format.RXY);
        define("AG", 0xE308, Format.RXY);
        define("SG", 0xE309, Format.RXY);
        define("LRVG", 0xE30F, Format.RXY);
        define("LGF", 0xE314, Format.RXY);
        define("LGH", 0xE315, Format.RXY);
        define("LLGF", 0xE316, Format.RXY);
        define("AGF", 0xE318, Format.RXY);
        define("SGF", 0xE319, Format.RXY);
        define("CG", 0xE320, Format.RXY);
        define("STG", 0xE324, Format.RXY);
        define("STRVG", 0xE32F, Format.RXY);
        define("STY", 0xE350, Format.RXY);
        define("LY", 0xE358, Format.RXY);
        define("LLGC", 0xE390, Format.RXY);
        define("LLGH", 0xE391, Format.RXY);
        define("SRAG", 0xEB0A, Format.RSY);
        define("SLAG", 0xEB0B, Format.RSY);
        define("SRLG", 0xEB0C, Format.RSY);
        define("SLLG", 0xEB0D, Format.RSY);
        define("RLLG", 0xEB1C, Format.RSY);
        define("ICMH", 0xEB80, Format.RSY_MASK);
        define("LAAG", 0xEBE8, Format.RSY);
        define("TP", 0xEBC0, Format.RSL);
        define("RISBG", 0xEC55, Format.RIE_F);

        // --- 拡張ニーモニック (BC / BCR のマスクを綴りに畳み込んだもの) ---
        // 算術の比較で立つ条件コードと、10 進・論理の比較で立つ条件コードで綴りが違う。
        // 同じマスクに 2 つ以上の綴りがあるのはそのためである
        extended("B", 0x47, Format.RX_MASK, 15);
        extended("NOP", 0x47, Format.RX_MASK, 0);
        extended("BH", 0x47, Format.RX_MASK, 2);
        extended("BL", 0x47, Format.RX_MASK, 4);
        extended("BE", 0x47, Format.RX_MASK, 8);
        extended("BNH", 0x47, Format.RX_MASK, 13);
        extended("BNL", 0x47, Format.RX_MASK, 11);
        extended("BNE", 0x47, Format.RX_MASK, 7);
        extended("BP", 0x47, Format.RX_MASK, 2);
        extended("BM", 0x47, Format.RX_MASK, 4);
        extended("BZ", 0x47, Format.RX_MASK, 8);
        extended("BNP", 0x47, Format.RX_MASK, 13);
        extended("BNM", 0x47, Format.RX_MASK, 11);
        extended("BNZ", 0x47, Format.RX_MASK, 7);
        extended("BO", 0x47, Format.RX_MASK, 1);
        extended("BNO", 0x47, Format.RX_MASK, 14);
        extended("BR", 0x07, Format.RR_MASK, 15);
        extended("NOPR", 0x07, Format.RR_MASK, 0);
        extended("BHR", 0x07, Format.RR_MASK, 2);
        extended("BLR", 0x07, Format.RR_MASK, 4);
        extended("BER", 0x07, Format.RR_MASK, 8);
        extended("BNHR", 0x07, Format.RR_MASK, 13);
        extended("BNLR", 0x07, Format.RR_MASK, 11);
        extended("BNER", 0x07, Format.RR_MASK, 7);
        extended("BZR", 0x07, Format.RR_MASK, 8);
        extended("BNZR", 0x07, Format.RR_MASK, 7);
        extended("BPR", 0x07, Format.RR_MASK, 2);
        extended("BMR", 0x07, Format.RR_MASK, 4);
        extended("BOR", 0x07, Format.RR_MASK, 1);
        extended("BNOR", 0x07, Format.RR_MASK, 14);
    }

    /** 綴りから命令を引く。知らない綴りなら {@code null}。 */
    public static Definition find(String mnemonic) {
        return TABLE.get(mnemonic.toUpperCase(Locale.ROOT));
    }

    /** 組み立てられる綴りの一覧。数えるためにある。 */
    public static Map<String, Definition> all() {
        return Map.copyOf(TABLE);
    }
}
