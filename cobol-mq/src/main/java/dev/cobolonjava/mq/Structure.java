package dev.cobolonjava.mq;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.storage.DataView;
import java.util.Arrays;

/**
 * MQI の構造体 1 つ。プログラムが渡した記憶域を z/OS の形 (整数は 4 byte の big endian、文字はプログラムの
 * code page) のまま読み書きする。
 *
 * <p>欄の位置は IBM MQ の公開文書の構造体の説明と、処理系が用意する写し句 ({@code MqSystemCopyBookResolver}、
 * 暫定判断 P-250) による。版ごとの長さを持ち、渡された記憶域が {@code VERSION} の長さに足りなければ
 * 構造体の誤り (MQRC_OD_ERROR など) として扱う。
 */
final class Structure {

    /** 構造体の種類: 目印 ({@code STRUCID})、版ごとの長さ。 */
    enum Kind {
        OD("OD  ", 168, 200, 336, 400),
        MD("MD  ", 324, 364),
        PMO("PMO ", 128, 152, 176),
        GMO("GMO ", 72, 80, 100, 112);

        final String id;
        final int[] lengths;

        Kind(String id, int... lengths) {
            this.id = id;
            this.lengths = lengths;
        }
    }

    // MQOD
    static final int OD_OBJECT_TYPE = 8;
    static final int OD_OBJECT_NAME = 12;
    static final int OD_OBJECT_Q_MGR_NAME = 60;
    static final int OD_RECS_PRESENT = 168;
    static final int OD_RESOLVED_Q_NAME = 240;
    static final int OD_RESOLVED_Q_MGR_NAME = 288;

    // MQMD
    static final int MD_REPORT = 8;
    static final int MD_MSG_TYPE = 12;
    static final int MD_EXPIRY = 16;
    static final int MD_ENCODING = 24;
    static final int MD_CCSID = 28;
    static final int MD_FORMAT = 32;
    static final int MD_PRIORITY = 40;
    static final int MD_PERSISTENCE = 44;
    static final int MD_MSG_ID = 48;
    static final int MD_CORREL_ID = 72;
    static final int MD_BACKOUT_COUNT = 96;
    static final int MD_USER_IDENTIFIER = 196;
    static final int MD_ACCOUNTING_TOKEN = 208;
    static final int MD_APPL_IDENTITY_DATA = 240;
    static final int MD_PUT_APPL_TYPE = 272;
    static final int MD_PUT_APPL_NAME = 276;
    static final int MD_PUT_DATE = 304;
    static final int MD_PUT_TIME = 312;
    static final int MD_APPL_ORIGIN_DATA = 320;
    static final int MD_GROUP_ID = 324;
    static final int MD_MSG_SEQ_NUMBER = 348;
    static final int MD_OFFSET = 352;
    static final int MD_MSG_FLAGS = 356;
    static final int MD_ORIGINAL_LENGTH = 360;
    /** 記憶しておく MQMD の長さ (2 版)。 */
    static final int MD_LENGTH = 364;

    // MQPMO
    static final int PMO_OPTIONS = 8;
    static final int PMO_RESOLVED_Q_NAME = 32;
    static final int PMO_RESOLVED_Q_MGR_NAME = 80;
    static final int PMO_RECS_PRESENT = 128;

    // MQGMO
    static final int GMO_OPTIONS = 8;
    static final int GMO_WAIT_INTERVAL = 12;
    static final int GMO_RESOLVED_Q_NAME = 24;
    static final int GMO_MATCH_OPTIONS = 72;
    static final int GMO_GROUP_STATUS = 76;
    static final int GMO_MSG_TOKEN = 80;
    static final int GMO_RETURNED_LENGTH = 96;

    static final int ID_LENGTH = 24;
    static final int NAME_LENGTH = 48;

    private final DataView view;
    private final CodePage codePage;
    private final int version;

    private Structure(DataView view, CodePage codePage, int version) {
        this.view = view;
        this.codePage = codePage;
        this.version = version;
    }

    /**
     * 渡された記憶域を {@code kind} の構造体として読む。目印・版・長さのどれかが合わなければ null。
     *
     * <p>MQ は目印と版を見て構造体の誤りを返す (公開文書の各 MQRC_xx_ERROR の説明)。長さが版に足りない記憶域は、
     * 実機なら隣の記憶域を読むが、ここでは同じ誤りとして断る。
     */
    static Structure of(Kind kind, DataView view, CodePage codePage) {
        if (view.length() < 8) {
            return null;
        }
        Structure probe = new Structure(view, codePage, 0);
        if (!codePage.decode(probe.bytes(0, 4)).equals(kind.id)) {
            return null;
        }
        int version = probe.integer(4);
        if (version < 1 || version > kind.lengths.length || view.length() < kind.lengths[version - 1]) {
            return null;
        }
        return new Structure(view, codePage, version);
    }

    int version() {
        return version;
    }

    int integer(int offset) {
        byte[] a = view.storage().array();
        int at = view.offset() + offset;
        return ((a[at] & 0xff) << 24) | ((a[at + 1] & 0xff) << 16) | ((a[at + 2] & 0xff) << 8) | (a[at + 3] & 0xff);
    }

    void setInteger(int offset, int value) {
        writeInteger(view, offset, value);
    }

    byte[] bytes(int offset, int length) {
        return Arrays.copyOfRange(view.storage().array(), view.offset() + offset, view.offset() + offset + length);
    }

    void setBytes(int offset, byte[] value) {
        System.arraycopy(value, 0, view.storage().array(), view.offset() + offset, value.length);
    }

    /** 文字の欄。後ろの空白を落とした文字列。 */
    String text(int offset, int length) {
        return stripTrailingBlanks(codePage.decode(bytes(offset, length)));
    }

    /** 文字の欄を書く。足りない分は空白で埋める。 */
    void setText(int offset, int length, String value) {
        setBytes(offset, padded(codePage, value, length));
    }

    static String stripTrailingBlanks(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    static byte[] padded(CodePage codePage, String value, int length) {
        byte[] out = new byte[length];
        Arrays.fill(out, codePage.space());
        byte[] text = codePage.encode(value);
        if (text.length > length) {
            throw new IllegalArgumentException("text is longer than " + length + " bytes: " + value);
        }
        System.arraycopy(text, 0, out, 0, text.length);
        return out;
    }

    static int readInteger(DataView view, int offset) {
        byte[] a = view.storage().array();
        int at = view.offset() + offset;
        return ((a[at] & 0xff) << 24) | ((a[at + 1] & 0xff) << 16) | ((a[at + 2] & 0xff) << 8) | (a[at + 3] & 0xff);
    }

    static void writeInteger(DataView view, int offset, int value) {
        byte[] a = view.storage().array();
        int at = view.offset() + offset;
        a[at] = (byte) (value >>> 24);
        a[at + 1] = (byte) (value >>> 16);
        a[at + 2] = (byte) (value >>> 8);
        a[at + 3] = (byte) value;
    }

    static int readInteger(byte[] a, int at) {
        return ((a[at] & 0xff) << 24) | ((a[at + 1] & 0xff) << 16) | ((a[at + 2] & 0xff) << 8) | (a[at + 3] & 0xff);
    }

    static void writeInteger(byte[] a, int at, int value) {
        a[at] = (byte) (value >>> 24);
        a[at + 1] = (byte) (value >>> 16);
        a[at + 2] = (byte) (value >>> 8);
        a[at + 3] = (byte) value;
    }
}
