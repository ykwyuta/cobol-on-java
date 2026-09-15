package dev.cobolonjava.ims.batch;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.storage.DataView;
import java.util.Locale;

/**
 * {@code EXEC PGM=DFSRRC00,PARM='DLI,プログラム,PSB,...'} の先頭の 3 つ。
 *
 * <p>4 つ目より後ろ (バッファ数、DBRC、IRLM 等) は、RDB やメモリに置くこの処理系では結果に効かないので読まない。
 *
 * @param regionType {@code DLI}、{@code DBB}、{@code BMP}
 * @param program    動かすプログラム
 * @param psb        PSB の名前。省けばプログラムと同じ名前
 */
record RegionParameters(String regionType, String program, String psb) {

    /** BMP は PSB の CMPAT によらず I/O PCB を先頭に置く。 */
    boolean ioPcb() {
        return regionType.equals("BMP");
    }

    static RegionParameters of(CodePage codePage, DataView[] arguments) {
        if (arguments.length == 0 || arguments[0].length() < 2) {
            throw new ImsBatchException("DFSRRC00 requires PARM='DLI,program,psb'");
        }
        DataView parm = arguments[0];
        int length = ((parm.get(0) & 0xFF) << 8) | (parm.get(1) & 0xFF);
        if (length + 2 > parm.length()) {
            throw new ImsBatchException("the PARM of DFSRRC00 is shorter than its length prefix");
        }
        String[] fields = codePage.decode(parm.subView(2, length).toByteArray()).split(",", -1);
        String regionType = fields[0].strip().toUpperCase(Locale.ROOT);
        switch (regionType) {
            case "DLI", "DBB" -> {
            }
            case "BMP" -> {
                // BMP の 4 つ目は電文を読む取引コード (IN=)。電文の入口はまだ無い (P-158)
                if (fields.length > 3 && !fields[3].isBlank()) {
                    throw new ImsBatchException("a BMP that reads messages (IN=" + fields[3].strip()
                            + ") is not supported yet (design 78 section 4)");
                }
            }
            case "MSG", "IFP", "JBP", "JMP" -> throw new ImsBatchException("DFSRRC00 region type " + regionType
                    + " is not supported yet; it needs IMS TM (design 78 section 4)");
            case "ULU", "UDR", "ULR" -> throw new ImsBatchException("DFSRRC00 region type " + regionType
                    + " (an IMS utility) is not supported yet (design 78 section 7.2)");
            default -> throw new ImsBatchException("unknown DFSRRC00 region type: " + regionType);
        }
        String program = fields.length > 1 ? fields[1].strip().toUpperCase(Locale.ROOT) : "";
        if (program.isEmpty()) {
            throw new ImsBatchException("the PARM of DFSRRC00 requires a program name");
        }
        String psb = fields.length > 2 ? fields[2].strip().toUpperCase(Locale.ROOT) : "";
        return new RegionParameters(regionType, program, psb.isEmpty() ? program : psb);
    }
}
