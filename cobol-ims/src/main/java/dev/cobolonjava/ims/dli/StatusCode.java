package dev.cobolonjava.ims.dli;

/**
 * DL/I の状態コード (設計 78 §3.7)。
 *
 * <p>発生条件は公開仕様の説明から起こしたものであり、実機と突き合わせていない (暫定判断 P-100、P-154)。
 */
public final class StatusCode {

    /** 正常終了。 */
    public static final String OK = "  ";
    /** 無限定の GN / GNP が、階層のより上の段へ移った。 */
    public static final String GA = "GA";
    /** 無限定の GN / GNP が、同じ段の別のセグメント型へ移った。 */
    public static final String GK = "GK";
    /** 見つからない。GNP が親の範囲を越えた。 */
    public static final String GE = "GE";
    /** データベースの終わりに達した。 */
    public static final String GB = "GB";
    /** 親境界が決まっていないのに GNP を呼んだ。 */
    public static final String GP = "GP";
    /** 重ならないキーを重ねて ISRT した。 */
    public static final String II = "II";
    /** Hold していないセグメントを REPL / DLET した。 */
    public static final String DJ = "DJ";
    /** REPL で順序フィールドを変えた。 */
    public static final String DA = "DA";
    /** PROCOPT が許さない呼び出し。 */
    public static final String AM = "AM";
    /** 知らない機能コード。 */
    public static final String AD = "AD";
    /** SSA の形が正しくない。 */
    public static final String AJ = "AJ";
    /** SSA のフィールド名が DBD に無い。 */
    public static final String AK = "AK";
    /** I/O PCB への GU で、メッセージキューが空。 */
    public static final String QC = "QC";
    /** I/O PCB への GN で、電文の次のセグメントが無い。 */
    public static final String QD = "QD";
    /** 読み込み (PROCOPT=L) で、同じキーのセグメントがすでにある。 */
    public static final String LB = "LB";
    /** 読み込みで、根のキーの順が崩れている。 */
    public static final String LC = "LC";
    /** 読み込みで、親が無い。 */
    public static final String LD = "LD";

    private StatusCode() {
    }
}
