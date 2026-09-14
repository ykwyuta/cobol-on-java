package dev.cobolonjava.cics;

import java.util.Locale;
import java.util.Objects;

/** 初期対応するCICS conditionのDFHRESP数値。 */
public final class CicsResponseCode {

    public static final int NORMAL = 0;
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
