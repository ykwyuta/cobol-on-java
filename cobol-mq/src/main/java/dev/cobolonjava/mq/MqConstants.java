package dev.cobolonjava.mq;

/**
 * MQI の定数のうち、この実装が読み書きするもの。
 *
 * <p>値は IBM MQ の公開文書の定数の表による (z/OS の値)。COBOL の側の {@code CMQV} (処理系が用意する写し句、
 * 暫定判断 P-250) と同じ値でなければならない。試験 ({@code MqConstantsTest}) で写し句と突き合わせている。
 */
public final class MqConstants {

    private MqConstants() {
    }

    // 完了コード
    public static final int MQCC_OK = 0;
    public static final int MQCC_WARNING = 1;
    public static final int MQCC_FAILED = 2;

    // 理由コード
    public static final int MQRC_NONE = 0;
    public static final int MQRC_ALREADY_CONNECTED = 2002;
    public static final int MQRC_BUFFER_LENGTH_ERROR = 2005;
    public static final int MQRC_CHAR_ATTR_LENGTH_ERROR = 2006;
    public static final int MQRC_CHAR_ATTRS_TOO_SHORT = 2008;
    public static final int MQRC_EXPIRY_ERROR = 2013;
    public static final int MQRC_GET_INHIBITED = 2016;
    public static final int MQRC_HCONN_ERROR = 2018;
    public static final int MQRC_HOBJ_ERROR = 2019;
    public static final int MQRC_INT_ATTR_COUNT_ERROR = 2021;
    public static final int MQRC_INT_ATTR_COUNT_TOO_SMALL = 2022;
    public static final int MQRC_MD_ERROR = 2026;
    public static final int MQRC_MSG_TOO_BIG_FOR_Q = 2030;
    public static final int MQRC_NO_MSG_AVAILABLE = 2033;
    public static final int MQRC_NOT_OPEN_FOR_INPUT = 2037;
    public static final int MQRC_NOT_OPEN_FOR_INQUIRE = 2038;
    public static final int MQRC_NOT_OPEN_FOR_OUTPUT = 2039;
    public static final int MQRC_OBJECT_IN_USE = 2042;
    public static final int MQRC_OBJECT_TYPE_ERROR = 2043;
    public static final int MQRC_OD_ERROR = 2044;
    public static final int MQRC_OPTION_NOT_VALID_FOR_TYPE = 2045;
    public static final int MQRC_OPTIONS_ERROR = 2046;
    public static final int MQRC_PERSISTENCE_ERROR = 2047;
    public static final int MQRC_PRIORITY_ERROR = 2050;
    public static final int MQRC_PUT_INHIBITED = 2051;
    public static final int MQRC_Q_FULL = 2053;
    public static final int MQRC_Q_MGR_NAME_ERROR = 2058;
    public static final int MQRC_SELECTOR_COUNT_ERROR = 2065;
    public static final int MQRC_SELECTOR_ERROR = 2067;
    public static final int MQRC_TRUNCATED_MSG_ACCEPTED = 2079;
    public static final int MQRC_TRUNCATED_MSG_FAILED = 2080;
    public static final int MQRC_UNKNOWN_OBJECT_NAME = 2085;
    public static final int MQRC_UNKNOWN_REMOTE_Q_MGR = 2087;
    public static final int MQRC_WAIT_INTERVAL_ERROR = 2090;
    public static final int MQRC_PMO_ERROR = 2173;
    public static final int MQRC_GMO_ERROR = 2186;
    public static final int MQRC_MATCH_OPTIONS_ERROR = 2247;

    // 接続と対象の handle
    public static final int MQHC_DEF_HCONN = 0;
    public static final int MQHC_UNUSABLE_HCONN = -1;
    public static final int MQHO_UNUSABLE_HOBJ = -1;

    // 対象の種類
    public static final int MQOT_Q = 1;

    // MQOPEN の選択
    public static final int MQOO_INPUT_AS_Q_DEF = 1;
    public static final int MQOO_INPUT_SHARED = 2;
    public static final int MQOO_INPUT_EXCLUSIVE = 4;
    public static final int MQOO_BROWSE = 8;
    public static final int MQOO_OUTPUT = 16;
    public static final int MQOO_INQUIRE = 32;
    public static final int MQOO_SET = 64;
    public static final int MQOO_SAVE_ALL_CONTEXT = 128;
    public static final int MQOO_PASS_IDENTITY_CONTEXT = 256;
    public static final int MQOO_PASS_ALL_CONTEXT = 512;
    public static final int MQOO_SET_IDENTITY_CONTEXT = 1024;
    public static final int MQOO_SET_ALL_CONTEXT = 2048;
    public static final int MQOO_ALTERNATE_USER_AUTHORITY = 4096;
    public static final int MQOO_FAIL_IF_QUIESCING = 8192;
    public static final int MQOO_BIND_ON_OPEN = 16384;
    public static final int MQOO_BIND_NOT_FIXED = 32768;

    // MQCLOSE の選択
    public static final int MQCO_NONE = 0;
    public static final int MQCO_DELETE = 1;
    public static final int MQCO_DELETE_PURGE = 2;

    // MQPUT の選択
    public static final int MQPMO_SYNCPOINT = 2;
    public static final int MQPMO_NO_SYNCPOINT = 4;
    public static final int MQPMO_DEFAULT_CONTEXT = 32;
    public static final int MQPMO_NEW_MSG_ID = 64;
    public static final int MQPMO_NEW_CORREL_ID = 128;
    public static final int MQPMO_PASS_IDENTITY_CONTEXT = 256;
    public static final int MQPMO_PASS_ALL_CONTEXT = 512;
    public static final int MQPMO_SET_IDENTITY_CONTEXT = 1024;
    public static final int MQPMO_SET_ALL_CONTEXT = 2048;
    public static final int MQPMO_ALTERNATE_USER_AUTHORITY = 4096;
    public static final int MQPMO_FAIL_IF_QUIESCING = 8192;
    public static final int MQPMO_NO_CONTEXT = 16384;
    public static final int MQPMO_LOGICAL_ORDER = 32768;

    // MQGET の選択
    public static final int MQGMO_WAIT = 1;
    public static final int MQGMO_SYNCPOINT = 2;
    public static final int MQGMO_NO_SYNCPOINT = 4;
    public static final int MQGMO_SET_SIGNAL = 8;
    public static final int MQGMO_BROWSE_FIRST = 16;
    public static final int MQGMO_BROWSE_NEXT = 32;
    public static final int MQGMO_ACCEPT_TRUNCATED_MSG = 64;
    public static final int MQGMO_MARK_SKIP_BACKOUT = 128;
    public static final int MQGMO_MSG_UNDER_CURSOR = 256;
    public static final int MQGMO_LOCK = 512;
    public static final int MQGMO_UNLOCK = 1024;
    public static final int MQGMO_BROWSE_MSG_UNDER_CURSOR = 2048;
    public static final int MQGMO_SYNCPOINT_IF_PERSISTENT = 4096;
    public static final int MQGMO_FAIL_IF_QUIESCING = 8192;
    public static final int MQGMO_CONVERT = 16384;

    // 突き合わせの選択
    public static final int MQMO_MATCH_MSG_ID = 1;
    public static final int MQMO_MATCH_CORREL_ID = 2;

    public static final int MQWI_UNLIMITED = -1;
    public static final int MQEI_UNLIMITED = -1;
    public static final int MQPRI_PRIORITY_AS_Q_DEF = -1;
    public static final int MQPER_NOT_PERSISTENT = 0;
    public static final int MQPER_PERSISTENT = 1;
    public static final int MQPER_PERSISTENCE_AS_Q_DEF = 2;

    public static final int MQMT_REQUEST = 1;
    public static final int MQMT_REPLY = 2;
    public static final int MQMT_REPORT = 4;
    public static final int MQMT_DATAGRAM = 8;

    public static final int MQRO_PASS_CORREL_ID = 64;
    public static final int MQRO_PASS_MSG_ID = 128;

    public static final int MQENC_NATIVE = 785;
    public static final int MQCCSI_Q_MGR = 0;

    // 応用の種類
    public static final int MQAT_NO_CONTEXT = 0;
    public static final int MQAT_CICS = 1;
    public static final int MQAT_ZOS = 2;

    // MQINQ の属性
    public static final int MQIA_CURRENT_Q_DEPTH = 3;
    public static final int MQIA_INHIBIT_GET = 9;
    public static final int MQIA_INHIBIT_PUT = 10;
    public static final int MQIA_MAX_MSG_LENGTH = 13;
    public static final int MQIA_MAX_Q_DEPTH = 15;
    public static final int MQIA_OPEN_INPUT_COUNT = 17;
    public static final int MQIA_OPEN_OUTPUT_COUNT = 18;
    public static final int MQIA_Q_TYPE = 20;
    public static final int MQCA_Q_DESC = 2013;
    public static final int MQCA_Q_NAME = 2016;
    public static final int MQQT_LOCAL = 1;
    public static final int MQQA_GET_ALLOWED = 0;
    public static final int MQQA_GET_INHIBITED = 1;
    public static final int MQQA_PUT_ALLOWED = 0;
    public static final int MQQA_PUT_INHIBITED = 1;
}
