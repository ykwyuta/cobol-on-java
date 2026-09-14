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
    public static final int ILLOGIC = 21;
    public static final int NOTAUTH = 70;
    /** 受取域が送られたデータより短い (GET CONTAINER)。 */
    public static final int LENGERR = 22;
    public static final int PGMIDERR = 27;
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
            case "ILLOGIC" -> ILLOGIC;
            case "NOTAUTH" -> NOTAUTH;
            case "LENGERR" -> LENGERR;
            case "PGMIDERR" -> PGMIDERR;
            case "MAPFAIL" -> MAPFAIL;
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
            case "ERROR" -> ERROR_HANDLER_KEY;
            default -> throw new IllegalArgumentException(
                    "unsupported CICS handler condition: " + normalized);
        };
    }
}
