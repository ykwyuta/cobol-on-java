package dev.cobolonjava.cics;

import java.util.Locale;
import java.util.Objects;

/** 初期対応するCICS conditionのDFHRESP数値。 */
public final class CicsResponseCode {

    public static final int NORMAL = 0;
    /**
     * file control の condition。数は CICS TS 6.x「Response codes of EXEC CICS commands」の表による
     * (暫定判断 P-131)。
     */
    public static final int FILENOTFOUND = 12;
    public static final int NOTFND = 13;
    public static final int DUPREC = 14;
    public static final int INVREQ = 16;
    public static final int IOERR = 17;
    public static final int NOSPACE = 18;
    /**
     * browse が file の終わりを越えた。数は CICS TS 6.x の READNEXT の文書による (暫定判断 P-136)。
     * AEIx の abend の並び (AEIS NOTOPEN 19、AEIT ENDFILE、AEIU ILLOGIC 21) とも合う。
     */
    public static final int ENDFILE = 20;
    public static final int ILLOGIC = 21;
    public static final int NOTAUTH = 70;
    /** 受取域が送られたデータより短い (GET CONTAINER)。 */
    public static final int LENGERR = 22;
    public static final int PGMIDERR = 27;
    /**
     * 一時記憶・一時データの condition (暫定判断 P-137)。QIDERR は 6.x の表、QZERO は READQ TD の頁、
     * ITEMERR は READQ TS の頁の数による。
     */
    public static final int QZERO = 23;
    public static final int ITEMERR = 26;
    public static final int QIDERR = 44;
    /**
     * 間隔制御の condition (暫定判断 P-138)。TRANSIDERR は 6.x の表、ENDDATA と ENVDEFERR は RETRIEVE の頁の数による。
     */
    public static final int TRANSIDERR = 28;
    public static final int ENDDATA = 29;
    public static final int ENVDEFERR = 56;
    /** START の TERMID の端末が定義されていない。数は START の頁による (設計 83 §5)。 */
    public static final int TERMIDERR = 11;
    /** 非同期 API の condition (暫定判断 P-140)。数は FETCH ANY / RUN TRANSID の頁による。 */
    public static final int NOTFINISHED = 113;
    public static final int DISABLED = 84;
    /** RECEIVE MAPで送られたfieldが無い。 */
    public static final int MAPFAIL = 36;
    /** ENQの資源を他のtaskが持っていて、待たない指定だった。 */
    public static final int ENQBUSY = 55;
    /** 名前のcontainerがchannelに無い。 */
    public static final int CONTAINERERR = 110;
    /** 名前のchannelが無い。 */
    public static final int CHANNELERR = 122;
    /** generalized ERROR handlerをcondition tableで識別する内部key。EIBRESP値ではない。 */
    static final int ERROR_HANDLER_KEY = Integer.MIN_VALUE;

    private CicsResponseCode() {
    }

    /** 現在保証するcondition名をCICS translatorのDFHRESP値へ変換する。 */
    public static int forCondition(String conditionName) {
        String normalized = Objects.requireNonNull(conditionName, "conditionName")
                .strip().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "NORMAL" -> NORMAL;
            case "FILENOTFOUND" -> FILENOTFOUND;
            case "NOTFND" -> NOTFND;
            case "DUPREC" -> DUPREC;
            case "INVREQ" -> INVREQ;
            case "IOERR" -> IOERR;
            case "NOSPACE" -> NOSPACE;
            case "ENDFILE" -> ENDFILE;
            case "ILLOGIC" -> ILLOGIC;
            case "NOTAUTH" -> NOTAUTH;
            case "LENGERR" -> LENGERR;
            case "PGMIDERR" -> PGMIDERR;
            case "MAPFAIL" -> MAPFAIL;
            case "QZERO" -> QZERO;
            case "ITEMERR" -> ITEMERR;
            case "QIDERR" -> QIDERR;
            case "TRANSIDERR" -> TRANSIDERR;
            case "ENDDATA" -> ENDDATA;
            case "ENVDEFERR" -> ENVDEFERR;
            case "TERMIDERR" -> TERMIDERR;
            case "NOTFINISHED" -> NOTFINISHED;
            case "DISABLED" -> DISABLED;
            case "ENQBUSY" -> ENQBUSY;
            case "CONTAINERERR" -> CONTAINERERR;
            case "CHANNELERR" -> CHANNELERR;
            default -> throw new IllegalArgumentException(
                    "unsupported CICS condition name: " + normalized);
        };
    }

    /** HANDLE/IGNORE CONDITIONで初期対応するcondition table keyへ変換する。 */
    public static int handlerKey(String conditionName) {
        String normalized = Objects.requireNonNull(conditionName, "conditionName")
                .strip().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PGMIDERR" -> PGMIDERR;
            case "MAPFAIL" -> MAPFAIL;
            // file control が返しうる condition (暫定判断 P-136)
            case "FILENOTFOUND" -> FILENOTFOUND;
            case "NOTFND" -> NOTFND;
            case "DUPREC" -> DUPREC;
            case "INVREQ" -> INVREQ;
            case "IOERR" -> IOERR;
            case "NOSPACE" -> NOSPACE;
            case "LENGERR" -> LENGERR;
            case "ENDFILE" -> ENDFILE;
            // 一時記憶・一時データが返しうる condition (暫定判断 P-137)
            case "QZERO" -> QZERO;
            case "ITEMERR" -> ITEMERR;
            case "QIDERR" -> QIDERR;
            // 間隔制御が返しうる condition (暫定判断 P-138)
            case "TRANSIDERR" -> TRANSIDERR;
            case "ENDDATA" -> ENDDATA;
            case "ENVDEFERR" -> ENVDEFERR;
            case "TERMIDERR" -> TERMIDERR;
            // 非同期 API が返しうる condition (暫定判断 P-140)
            case "NOTFINISHED" -> NOTFINISHED;
            case "DISABLED" -> DISABLED;
            case "ERROR" -> ERROR_HANDLER_KEY;
            default -> throw new IllegalArgumentException(
                    "unsupported CICS handler condition: " + normalized);
        };
    }
}
