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
        SIY(6),
        /** {@code op D1(L,B1),D2(B2)} — 6 バイト。長さは 1 つ。 */
        SS_A(6),
        /** {@code op D1(L1,B1),D2(L2,B2)} — 6 バイト。長さは 2 つ。 */
        SS_B(6),
        /** {@code op D1(L1,B1),D2(B2),I3} — 6 バイト。{@code SRP} だけが使う。 */
        SS_C(6),
        /** {@code op R1,R2} — 4 バイト。命令コードが 2 バイトある。 */
        RRE(4),
        /** {@code op R1,R2,R3} — 4 バイト。3 つ目のレジスタは 3 バイト目の上位に入る。 */
        RRF(4),
        RRF_MASK(4),
        RRF_SELECT(4),
        /** {@code op R1,I2} — 4 バイト。16 ビットの符号つき即値。 */
        RI(4),
        REL16(4),
        REL32(6),
        /** {@code op R1,I2} — 6 バイト。32 ビットの即値。 */
        RIL(6),
        /** {@code op R1,D2(X2,B2)} — 6 バイト。20 ビットの符号つき変位。 */
        RXY(6),
        /** {@code op R1,R3,D2(B2)} — 6 バイト。20 ビットの符号つき変位。 */
        RSY(6),
        RSY_MASK(6),
        RIE_COND(6),
        /** {@code op R1,R2,I3,I4[,I5]} — 6 バイト。ビットの範囲を取る命令。 */
        RIE_F(6),
        /** {@code op D1(L1,B1)} — 6 バイト。10 進の検査 ({@code TP}) が使う。 */
        RSL(6),
        SSF(6),
        RXE(6),
        RXF(6),
        /** {@code op D2(B2)} — 4 バイト。命令コードが 2 バイトある。 */
        S(4),
        /** 演算項なし — 2 バイト。 */
        E(2);

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
        define("MVCL", 0x0E, Format.RR);
        define("CLCL", 0x0F, Format.RR);
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
        define("CLIY", 0xEB55, Format.SIY);
        define("TMY", 0xEB51, Format.SIY);
        define("NIY", 0xEB54, Format.SIY);
        define("OIY", 0xEB56, Format.SIY);
        define("XIY", 0xEB57, Format.SIY);
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
        define("MVST", 0xB255, Format.RRE);
        define("CLST", 0xB25D, Format.RRE);
        define("SRST", 0xB25E, Format.RRE);

        // --- z/Architecture の 64 ビットの命令 ---
        // 命令コードは「1 バイト目 << 8 | 残りの命令コード」で持つ。RI と RIL は残りが
        // 4 ビット (1 バイト目の次のバイトの下位)、ほかは 1 バイトである。
        // ZHVS (Zeus の HLASM 検査スイート) の HA / HM / HI / HD の区分が使うものを入れた
        // 短形式・長形式の HFP と、長形式へ丸める命令。
        define("LPDR", 0x20, Format.RR);
        define("LNDR", 0x21, Format.RR);
        define("LTDR", 0x22, Format.RR);
        define("LCDR", 0x23, Format.RR);
        define("HDR", 0x24, Format.RR);
        define("LRDR", 0x25, Format.RR);
        define("LDXR", 0x25, Format.RR);
        define("MXR", 0x26, Format.RR);
        define("MXDR", 0x27, Format.RR);
        define("LDR", 0x28, Format.RR);
        define("LER", 0x38, Format.RR);
        define("CDR", 0x29, Format.RR);
        define("ADR", 0x2A, Format.RR);
        define("SDR", 0x2B, Format.RR);
        define("MDR", 0x2C, Format.RR);
        define("DDR", 0x2D, Format.RR);
        define("AWR", 0x2E, Format.RR);
        define("SWR", 0x2F, Format.RR);
        define("LPER", 0x30, Format.RR);
        define("LNER", 0x31, Format.RR);
        define("LTER", 0x32, Format.RR);
        define("LCER", 0x33, Format.RR);
        define("HER", 0x34, Format.RR);
        define("LEDR", 0x35, Format.RR);
        define("LRER", 0x35, Format.RR);
        define("AXR", 0x36, Format.RR);
        define("SXR", 0x37, Format.RR);
        define("CER", 0x39, Format.RR);
        define("AER", 0x3A, Format.RR);
        define("SER", 0x3B, Format.RR);
        define("MER", 0x3C, Format.RR);
        define("MDER", 0x3C, Format.RR);
        define("DER", 0x3D, Format.RR);
        define("AUR", 0x3E, Format.RR);
        define("SUR", 0x3F, Format.RR);
        define("LD", 0x68, Format.RX);
        define("LE", 0x78, Format.RX);
        define("STD", 0x60, Format.RX);
        define("MXD", 0x67, Format.RX);
        define("STE", 0x70, Format.RX);
        define("CD", 0x69, Format.RX);
        define("AD", 0x6A, Format.RX);
        define("SD", 0x6B, Format.RX);
        define("MD", 0x6C, Format.RX);
        define("DD", 0x6D, Format.RX);
        define("AW", 0x6E, Format.RX);
        define("SW", 0x6F, Format.RX);
        define("CE", 0x79, Format.RX);
        define("AE", 0x7A, Format.RX);
        define("SE", 0x7B, Format.RX);
        define("ME", 0x7C, Format.RX);
        define("MDE", 0x7C, Format.RX);
        define("DE", 0x7D, Format.RX);
        define("AU", 0x7E, Format.RX);
        define("SU", 0x7F, Format.RX);
        define("SQDR", 0xB244, Format.RRE);
        define("LXR", 0xB365, Format.RRE);
        define("SQDBR", 0xB315, Format.RRE);
        define("LDEB", 0xED04, Format.RXE);
        define("LXDB", 0xED05, Format.RXE);
        define("LXEB", 0xED06, Format.RXE);
        define("MXDB", 0xED07, Format.RXE);
        define("KEB", 0xED08, Format.RXE);
        define("CEB", 0xED09, Format.RXE);
        define("AEB", 0xED0A, Format.RXE);
        define("SEB", 0xED0B, Format.RXE);
        define("MDEB", 0xED0C, Format.RXE);
        define("DEB", 0xED0D, Format.RXE);
        define("MAEB", 0xED0E, Format.RXF);
        define("MSEB", 0xED0F, Format.RXF);
        define("TCEB", 0xED10, Format.RXE);
        define("TCDB", 0xED11, Format.RXE);
        define("TCXB", 0xED12, Format.RXE);
        define("SQEB", 0xED14, Format.RXE);
        define("SQDB", 0xED15, Format.RXE);
        define("TDCET", 0xED50, Format.RXE);
        define("TDGET", 0xED51, Format.RXE);
        define("TDCDT", 0xED54, Format.RXE);
        define("TDGDT", 0xED55, Format.RXE);
        define("TDCXT", 0xED58, Format.RXE);
        define("TDGXT", 0xED59, Format.RXE);
        define("MEEB", 0xED17, Format.RXE);
        define("KDB", 0xED18, Format.RXE);
        define("CDB", 0xED19, Format.RXE);
        define("ADB", 0xED1A, Format.RXE);
        define("SDB", 0xED1B, Format.RXE);
        define("MDB", 0xED1C, Format.RXE);
        define("DDB", 0xED1D, Format.RXE);
        define("MADB", 0xED1E, Format.RXF);
        define("MSDB", 0xED1F, Format.RXF);
        define("CDBR", 0xB319, Format.RRE);
        define("ADBR", 0xB31A, Format.RRE);
        define("SDBR", 0xB31B, Format.RRE);
        define("MDBR", 0xB31C, Format.RRE);
        define("DDBR", 0xB31D, Format.RRE);
        define("MDTR", 0xB3D0, Format.RRF);
        define("DDTR", 0xB3D1, Format.RRF);
        define("ADTR", 0xB3D2, Format.RRF);
        define("SDTR", 0xB3D3, Format.RRF);
        define("CDTR", 0xB3E4, Format.RRE);
        define("SFPC", 0xB384, Format.RRE_R1);
        define("EFPC", 0xB38C, Format.RRE_R1);
        define("STFPC", 0xB29C, Format.S);
        define("LFPC", 0xB29D, Format.S);
        define("SRNM", 0xB299, Format.S);
        define("SRNMB", 0xB2B8, Format.S);
        define("SRNMT", 0xB2B9, Format.S);
        define("PFPO", 0x010A, Format.E);
        define("SAM24", 0x010C, Format.E);
        define("SAM31", 0x010D, Format.E);
        define("LGR", 0xB904, Format.RRE);
        define("LBR", 0xB926, Format.RRE);
        define("LGBR", 0xB906, Format.RRE);
        define("CGFR", 0xB930, Format.RRE);
        define("LTGR", 0xB902, Format.RRE);
        define("LCGR", 0xB903, Format.RRE);
        define("LPGR", 0xB900, Format.RRE);
        define("LNGR", 0xB901, Format.RRE);
        define("AGR", 0xB908, Format.RRE);
        define("SGR", 0xB909, Format.RRE);
        define("ALGR", 0xB90A, Format.RRE);
        define("SLGR", 0xB90B, Format.RRE);
        define("MSGR", 0xB90C, Format.RRE);
        define("DSGR", 0xB90D, Format.RRE);
        define("DSGFR", 0xB91D, Format.RRE);
        define("MLGR", 0xB986, Format.RRE);
        define("DLGR", 0xB987, Format.RRE);
        define("MLR", 0xB996, Format.RRE);
        define("DLR", 0xB997, Format.RRE);
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
        define("SLBGR", 0xB989, Format.RRE);
        define("LRVGR", 0xB90F, Format.RRE);
        define("POPCNT", 0xB9E1, Format.RRE);
        define("AGRK", 0xB9E8, Format.RRF);
        define("SGRK", 0xB9E9, Format.RRF);
        define("NGRK", 0xB9E4, Format.RRF);
        define("NRK", 0xB9F4, Format.RRF);
        define("ORK", 0xB9F6, Format.RRF);
        define("XRK", 0xB9F7, Format.RRF);
        define("NNRK", 0xB974, Format.RRF);
        define("NNGRK", 0xB964, Format.RRF);
        define("NORK", 0xB976, Format.RRF);
        define("NOGRK", 0xB966, Format.RRF);
        define("NXRK", 0xB977, Format.RRF);
        define("NXGRK", 0xB967, Format.RRF);
        define("NCRK", 0xB9F5, Format.RRF);
        define("NCGRK", 0xB9E5, Format.RRF);
        define("OCRK", 0xB975, Format.RRF);
        define("OCGRK", 0xB965, Format.RRF);
        define("OGRK", 0xB9E6, Format.RRF);
        define("XGRK", 0xB9E7, Format.RRF);
        define("LHI", 0xA708, Format.RI);
        define("IIHH", 0xA500, Format.RI);
        define("IIHL", 0xA501, Format.RI);
        define("IILH", 0xA502, Format.RI);
        define("IILL", 0xA503, Format.RI);
        define("LLIHH", 0xA50C, Format.RI);
        define("LLIHL", 0xA50D, Format.RI);
        define("LLILH", 0xA50E, Format.RI);
        define("LLILL", 0xA50F, Format.RI);
        define("NIHH", 0xA504, Format.RI);
        define("NIHL", 0xA505, Format.RI);
        define("NILH", 0xA506, Format.RI);
        define("NILL", 0xA507, Format.RI);
        define("OIHH", 0xA508, Format.RI);
        define("OIHL", 0xA509, Format.RI);
        define("OILH", 0xA50A, Format.RI);
        define("OILL", 0xA50B, Format.RI);
        define("TMLH", 0xA700, Format.RI);
        define("TMLL", 0xA701, Format.RI);
        define("TMHH", 0xA702, Format.RI);
        define("TMHL", 0xA703, Format.RI);
        define("XIHF", 0xC006, Format.RIL);
        define("XILF", 0xC007, Format.RIL);
        define("NIHF", 0xC00A, Format.RIL);
        define("NILF", 0xC00B, Format.RIL);
        define("OIHF", 0xC00C, Format.RIL);
        define("OILF", 0xC00D, Format.RIL);
        define("LGHI", 0xA709, Format.RI);
        define("AHI", 0xA70A, Format.RI);
        define("AGHI", 0xA70B, Format.RI);
        define("MHI", 0xA70C, Format.RI);
        define("MGHI", 0xA70D, Format.RI);
        define("CHI", 0xA70E, Format.RI);
        define("CGHI", 0xA70F, Format.RI);
        define("IIHF", 0xC008, Format.RIL);
        define("IILF", 0xC009, Format.RIL);
        define("LGFI", 0xC001, Format.RIL);
        define("LARL", 0xC000, Format.RIL);
        define("BRAS", 0xA705, Format.REL16);
        define("BRC", 0xA704, Format.REL16);
        define("BRCT", 0xA706, Format.REL16);
        define("BRCTG", 0xA707, Format.REL16);
        define("BRASL", 0xC005, Format.REL32);
        define("BRCL", 0xC004, Format.REL32);
        define("BRCTH", 0xCC06, Format.REL32);
        define("EXRL", 0xC600, Format.REL32);
        define("BCTG", 0xE346, Format.RXY);
        define("MSG", 0xE30C, Format.RXY);
        define("DSG", 0xE30D, Format.RXY);
        define("DSGF", 0xE31D, Format.RXY);
        define("MSY", 0xE351, Format.RXY);
        define("MLG", 0xE386, Format.RXY);
        define("DLG", 0xE387, Format.RXY);
        define("ML", 0xE396, Format.RXY);
        define("DL", 0xE397, Format.RXY);
        define("BCTGR", 0xB946, Format.RRE);
        define("LRV", 0xE31E, Format.RXY);
        define("LRVH", 0xE31F, Format.RXY);
        define("STRV", 0xE33E, Format.RXY);
        define("STRVH", 0xE33F, Format.RXY);
        define("LRVR", 0xB91F, Format.RRE);
        define("LOCR", 0xB9F2, Format.RRF_MASK);
        define("LOCGR", 0xB9E2, Format.RRF_MASK);
        define("LOCFHR", 0xB9E0, Format.RRF_MASK);
        define("SELR", 0xB9F0, Format.RRF_SELECT);
        define("SELGR", 0xB9E3, Format.RRF_SELECT);
        define("SELFHR", 0xB9C0, Format.RRF_SELECT);
        define("LOC", 0xEBF2, Format.RSY_MASK);
        define("LOCG", 0xEBE2, Format.RSY_MASK);
        define("LOCFH", 0xEBE0, Format.RSY_MASK);
        define("STOC", 0xEBF3, Format.RSY_MASK);
        define("STOCG", 0xEBE3, Format.RSY_MASK);
        define("STOCFH", 0xEBE1, Format.RSY_MASK);
        define("LOCHI", 0xEC42, Format.RIE_COND);
        define("LOCGHI", 0xEC46, Format.RIE_COND);
        define("LOCHHI", 0xEC4E, Format.RIE_COND);
        define("LLIHF", 0xC00E, Format.RIL);
        define("LLILF", 0xC00F, Format.RIL);
        define("AGFI", 0xC208, Format.RIL);
        define("CGFI", 0xC20C, Format.RIL);
        define("LG", 0xE304, Format.RXY);
        define("NY", 0xE354, Format.RXY);
        define("OY", 0xE356, Format.RXY);
        define("XY", 0xE357, Format.RXY);
        define("ALG", 0xE30A, Format.RXY);
        define("SLG", 0xE30B, Format.RXY);
        define("ALY", 0xE35E, Format.RXY);
        define("SLY", 0xE35F, Format.RXY);
        define("ALCG", 0xE388, Format.RXY);
        define("SLBG", 0xE389, Format.RXY);
        define("ALC", 0xE398, Format.RXY);
        define("SLB", 0xE399, Format.RXY);
        define("ALCR", 0xB998, Format.RRE);
        define("SLBR", 0xB999, Format.RRE);
        define("CGF", 0xE330, Format.RXY);
        define("CY", 0xE359, Format.RXY);
        define("CHY", 0xE379, Format.RXY);
        define("CLY", 0xE355, Format.RXY);
        define("AGF", 0xE318, Format.RXY);
        define("SGF", 0xE319, Format.RXY);
        define("AY", 0xE35A, Format.RXY);
        define("SY", 0xE35B, Format.RXY);
        define("AHY", 0xE37A, Format.RXY);
        define("SHY", 0xE37B, Format.RXY);
        define("LB", 0xE376, Format.RXY);
        define("LGB", 0xE377, Format.RXY);
        define("LHY", 0xE378, Format.RXY);
        define("LLC", 0xE394, Format.RXY);
        define("LLH", 0xE395, Format.RXY);
        define("LAY", 0xE371, Format.RXY);
        define("STHY", 0xE370, Format.RXY);
        define("STCY", 0xE372, Format.RXY);
        define("ICY", 0xE373, Format.RXY);
        define("AG", 0xE308, Format.RXY);
        define("SG", 0xE309, Format.RXY);
        define("LRVG", 0xE30F, Format.RXY);
        define("LGF", 0xE314, Format.RXY);
        define("LGH", 0xE315, Format.RXY);
        define("LLGF", 0xE316, Format.RXY);
        define("CG", 0xE320, Format.RXY);
        define("CLG", 0xE321, Format.RXY);
        define("STG", 0xE324, Format.RXY);
        define("STRVG", 0xE32F, Format.RXY);
        define("STY", 0xE350, Format.RXY);
        define("LY", 0xE358, Format.RXY);
        define("NG", 0xE380, Format.RXY);
        define("OG", 0xE381, Format.RXY);
        define("XG", 0xE382, Format.RXY);
        define("LLGC", 0xE390, Format.RXY);
        define("LLGH", 0xE391, Format.RXY);
        define("LMG", 0xEB04, Format.RSY);
        define("CLMH", 0xEB20, Format.RSY);
        define("CLMY", 0xEB21, Format.RSY);
        define("STCMH", 0xEB2C, Format.RSY);
        define("STCMY", 0xEB2D, Format.RSY);
        define("ICMY", 0xEB81, Format.RSY);
        define("STMY", 0xEB90, Format.RSY);
        define("LMY", 0xEB98, Format.RSY);
        define("SRAG", 0xEB0A, Format.RSY);
        define("SLAG", 0xEB0B, Format.RSY);
        define("SRLG", 0xEB0C, Format.RSY);
        define("SLLG", 0xEB0D, Format.RSY);
        define("RLLG", 0xEB1C, Format.RSY);
        define("RLL", 0xEB1D, Format.RSY);
        define("STMG", 0xEB24, Format.RSY);
        define("CSG", 0xEB30, Format.RSY);
        define("ICMH", 0xEB80, Format.RSY);
        define("LAAG", 0xEBE8, Format.RSY);
        define("RISBG", 0xEC55, Format.RIE_F);
        define("RISBGN", 0xEC59, Format.RIE_F);
        define("RISBHG", 0xEC5D, Format.RIE_F);
        define("RISBLG", 0xEC51, Format.RIE_F);
        define("RNSBG", 0xEC54, Format.RIE_F);
        define("ROSBG", 0xEC56, Format.RIE_F);
        define("RXSBG", 0xEC57, Format.RIE_F);
        define("TP", 0xEBC0, Format.RSL);
        // 32 ビットだが増分 1 に無かったもの
        define("ICM", 0xBF, Format.RS);
        define("STCM", 0xBE, Format.RS);
        define("CLM", 0xBD, Format.RS);
        define("CS", 0xBA, Format.RS);
        define("CSY", 0xEB14, Format.RSY);
        define("CDSY", 0xEB31, Format.RSY);
        define("CDSG", 0xEB3E, Format.RSY);
        define("LPQ", 0xE38F, Format.RXY);
        define("STPQ", 0xE38E, Format.RXY);
        define("CDS", 0xBB, Format.RS);
        define("CSST", 0xC802, Format.SSF);
        define("LPD", 0xC804, Format.SSF);
        define("LPDG", 0xC805, Format.SSF);
        define("TS", 0x93, Format.S);
        define("LANG", 0xEBE4, Format.RSY);
        define("LAOG", 0xEBE6, Format.RSY);
        define("LAXG", 0xEBE7, Format.RSY);
        define("LAALG", 0xEBEA, Format.RSY);
        define("LAN", 0xEBF4, Format.RSY);
        define("LAO", 0xEBF6, Format.RSY);
        define("LAX", 0xEBF7, Format.RSY);
        define("LAA", 0xEBF8, Format.RSY);
        define("LAAL", 0xEBFA, Format.RSY);

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
