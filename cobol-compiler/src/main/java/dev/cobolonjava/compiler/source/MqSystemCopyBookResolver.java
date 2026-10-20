package dev.cobolonjava.compiler.source;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * IBM MQ が COBOL 向けに配る写し句 ({@code CMQV}、{@code CMQODV}、{@code CMQMDV}、{@code CMQPMOV}、{@code CMQGMOV}、
 * {@code CMQTMV} と、初期値の無い {@code ...L} の形) を、処理系の側で作って返す (設計 88、暫定判断 P-250)。
 *
 * <p><b>IBM 製品の写し句の原文は参照していない。</b>欄の並び・長さ・既定値は IBM MQ の公開文書
 * (Application Programming Reference の MQOD / MQMD / MQPMO / MQGMO / MQTM の欄の説明と定数の値) と、
 * 同じ権利者の Zeus (d2f1cfc の {@code crates/zeus-cobol/src/front/syslib_mq.rs}) の読みによる。
 * 書き直したのは名前と欄の形だけで、写し句の注釈・段付けは IBM のものと一致させていない。
 *
 * <p>構造体は z/OS の 31 bit の形 (番地は 4 byte、整数は big endian の {@code S9(9) BINARY}) で、
 * MQI の実装 ({@code cobol-mq}) が読む版の欄まで全部を持つ: MQOD 4 版 (400 byte)、MQMD 2 版 (364 byte)、
 * MQPMO 3 版 (176 byte)、MQGMO 4 版 (112 byte)、MQTM 1 版 (684 byte)。{@code VERSION} の既定値は
 * IBM の写し句と同じく 1 である (公開文書の「初期値」の表による)。版の欄より後ろも記憶域にはあるが、
 * 版が 1 のままなら MQI はそこを読まない。
 *
 * <p>構造体は 10 段から始まる。資産は {@code 01 MQ-OBJECT-DESCRIPTOR. COPY CMQODV.} のように自分の
 * 01 の下へ写すからである。利用者の置き場より後ろに連ねるので、資産が自前の写し句を置いていればそちらを使う。
 *
 * <p>publish/subscribe や message handle の構造体 (MQSD、MQCMHO など) はまだ置かない。MQI の側に
 * {@code MQSUB} などが無いので、写し句だけ通しても実行で必ず止まる。翻訳の段で「写し句が無い」と断るほうが
 * 止まる場所が分かりやすい。
 */
public final class MqSystemCopyBookResolver implements CopyBookResolver {

    /** 写し句の 1 項目: 段、名前、PICTURE (null なら集団項目、"POINTER" なら番地)、初期値 (null なら無し)。 */
    private record Item(int level, String name, String picture, String value) {
    }

    private static final String L = "S9(9) BINARY";
    /** MQINT64 (message handle)。 */
    private static final String L64 = "S9(18) BINARY";
    private static final String P = "POINTER";
    /** MQCHARV (20 byte: 番地、offset、buffer 長、長さ、CCSID)。 */
    private static final String V = "CHARV";

    private static Item group(String name) {
        return new Item(10, name, null, null);
    }

    private static Item f(String name, String picture, String value) {
        return new Item(15, name, picture, value);
    }

    private static final List<Item> MQOD = List.of(
            group("MQOD"),
            f("MQOD-STRUCID", "X(4)", "'OD  '"),
            f("MQOD-VERSION", L, "1"),
            f("MQOD-OBJECTTYPE", L, "1"),
            f("MQOD-OBJECTNAME", "X(48)", "SPACES"),
            f("MQOD-OBJECTQMGRNAME", "X(48)", "SPACES"),
            f("MQOD-DYNAMICQNAME", "X(48)", "'CSQ.*'"),
            f("MQOD-ALTERNATEUSERID", "X(12)", "SPACES"),
            // 2 版
            f("MQOD-RECSPRESENT", L, "0"),
            f("MQOD-KNOWNDESTCOUNT", L, "0"),
            f("MQOD-UNKNOWNDESTCOUNT", L, "0"),
            f("MQOD-INVALIDDESTCOUNT", L, "0"),
            f("MQOD-OBJECTRECOFFSET", L, "0"),
            f("MQOD-RESPONSERECOFFSET", L, "0"),
            f("MQOD-OBJECTRECPTR", P, null),
            f("MQOD-RESPONSERECPTR", P, null),
            // 3 版
            f("MQOD-ALTERNATESECURITYID", "X(40)", "LOW-VALUES"),
            f("MQOD-RESOLVEDQNAME", "X(48)", "SPACES"),
            f("MQOD-RESOLVEDQMGRNAME", "X(48)", "SPACES"),
            // 4 版
            f("MQOD-OBJECTSTRING", V, null),
            f("MQOD-SELECTIONSTRING", V, null),
            f("MQOD-RESOBJECTSTRING", V, null),
            f("MQOD-RESOLVEDTYPE", L, "0"));

    private static final List<Item> MQMD = List.of(
            group("MQMD"),
            f("MQMD-STRUCID", "X(4)", "'MD  '"),
            f("MQMD-VERSION", L, "1"),
            f("MQMD-REPORT", L, "0"),
            f("MQMD-MSGTYPE", L, "8"),
            f("MQMD-EXPIRY", L, "-1"),
            f("MQMD-FEEDBACK", L, "0"),
            // z/OS の MQENC_NATIVE
            f("MQMD-ENCODING", L, "785"),
            f("MQMD-CODEDCHARSETID", L, "0"),
            f("MQMD-FORMAT", "X(8)", "SPACES"),
            f("MQMD-PRIORITY", L, "-1"),
            f("MQMD-PERSISTENCE", L, "2"),
            f("MQMD-MSGID", "X(24)", "LOW-VALUES"),
            f("MQMD-CORRELID", "X(24)", "LOW-VALUES"),
            f("MQMD-BACKOUTCOUNT", L, "0"),
            f("MQMD-REPLYTOQ", "X(48)", "SPACES"),
            f("MQMD-REPLYTOQMGR", "X(48)", "SPACES"),
            f("MQMD-USERIDENTIFIER", "X(12)", "SPACES"),
            f("MQMD-ACCOUNTINGTOKEN", "X(32)", "LOW-VALUES"),
            f("MQMD-APPLIDENTITYDATA", "X(32)", "SPACES"),
            f("MQMD-PUTAPPLTYPE", L, "0"),
            f("MQMD-PUTAPPLNAME", "X(28)", "SPACES"),
            f("MQMD-PUTDATE", "X(8)", "SPACES"),
            f("MQMD-PUTTIME", "X(8)", "SPACES"),
            f("MQMD-APPLORIGINDATA", "X(4)", "SPACES"),
            // 2 版
            f("MQMD-GROUPID", "X(24)", "LOW-VALUES"),
            f("MQMD-MSGSEQNUMBER", L, "1"),
            f("MQMD-OFFSET", L, "0"),
            f("MQMD-MSGFLAGS", L, "0"),
            f("MQMD-ORIGINALLENGTH", L, "-1"));

    private static final List<Item> MQPMO = List.of(
            group("MQPMO"),
            f("MQPMO-STRUCID", "X(4)", "'PMO '"),
            f("MQPMO-VERSION", L, "1"),
            f("MQPMO-OPTIONS", L, "0"),
            f("MQPMO-TIMEOUT", L, "-1"),
            f("MQPMO-CONTEXT", L, "0"),
            f("MQPMO-KNOWNDESTCOUNT", L, "0"),
            f("MQPMO-UNKNOWNDESTCOUNT", L, "0"),
            f("MQPMO-INVALIDDESTCOUNT", L, "0"),
            f("MQPMO-RESOLVEDQNAME", "X(48)", "SPACES"),
            f("MQPMO-RESOLVEDQMGRNAME", "X(48)", "SPACES"),
            // 2 版
            f("MQPMO-RECSPRESENT", L, "0"),
            f("MQPMO-PUTMSGRECFIELDS", L, "0"),
            f("MQPMO-PUTMSGRECOFFSET", L, "0"),
            f("MQPMO-RESPONSERECOFFSET", L, "0"),
            f("MQPMO-PUTMSGRECPTR", P, null),
            f("MQPMO-RESPONSERECPTR", P, null),
            // 3 版
            f("MQPMO-ORIGINALMSGHANDLE", L64, "0"),
            f("MQPMO-NEWMSGHANDLE", L64, "0"),
            f("MQPMO-ACTION", L, "0"),
            f("MQPMO-PUBLEVEL", L, "9"));

    private static final List<Item> MQGMO = List.of(
            group("MQGMO"),
            f("MQGMO-STRUCID", "X(4)", "'GMO '"),
            f("MQGMO-VERSION", L, "1"),
            f("MQGMO-OPTIONS", L, "0"),
            f("MQGMO-WAITINTERVAL", L, "0"),
            f("MQGMO-SIGNAL1", L, "0"),
            f("MQGMO-SIGNAL2", L, "0"),
            f("MQGMO-RESOLVEDQNAME", "X(48)", "SPACES"),
            // 2 版
            f("MQGMO-MATCHOPTIONS", L, "3"),
            f("MQGMO-GROUPSTATUS", "X", "SPACE"),
            f("MQGMO-SEGMENTSTATUS", "X", "SPACE"),
            f("MQGMO-SEGMENTATION", "X", "SPACE"),
            f("MQGMO-RESERVED1", "X", "SPACE"),
            // 3 版
            f("MQGMO-MSGTOKEN", "X(16)", "LOW-VALUES"),
            f("MQGMO-RETURNEDLENGTH", L, "-1"),
            // 4 版
            f("MQGMO-RESERVED2", L, "0"),
            f("MQGMO-MSGHANDLE", L64, "0"));

    private static final List<Item> MQTM = List.of(
            group("MQTM"),
            f("MQTM-STRUCID", "X(4)", "'TM  '"),
            f("MQTM-VERSION", L, "1"),
            f("MQTM-QNAME", "X(48)", "SPACES"),
            f("MQTM-PROCESSNAME", "X(48)", "SPACES"),
            f("MQTM-TRIGGERDATA", "X(64)", "SPACES"),
            f("MQTM-APPLTYPE", L, "0"),
            f("MQTM-APPLID", "X(256)", "SPACES"),
            f("MQTM-ENVDATA", "X(128)", "SPACES"),
            f("MQTM-USERDATA", "X(128)", "SPACES"));

    /** CMQV の整数の定数。値は公開文書の定数の表による (z/OS の値。MQENC_NATIVE は 785)。 */
    private static final Object[][] INTS = {
            // 完了コードと理由コード
            {"MQCC-OK", 0}, {"MQCC-WARNING", 1}, {"MQCC-FAILED", 2}, {"MQCC-UNKNOWN", -1},
            {"MQRC-NONE", 0},
            {"MQRC-ALREADY-CONNECTED", 2002},
            {"MQRC-BUFFER-LENGTH-ERROR", 2005},
            {"MQRC-CONNECTION-BROKEN", 2009},
            {"MQRC-DATA-LENGTH-ERROR", 2010},
            {"MQRC-ENVIRONMENT-ERROR", 2012},
            {"MQRC-EXPIRY-ERROR", 2013},
            {"MQRC-GET-INHIBITED", 2016},
            {"MQRC-HCONN-ERROR", 2018},
            {"MQRC-HOBJ-ERROR", 2019},
            {"MQRC-MD-ERROR", 2026},
            {"MQRC-MSG-TYPE-ERROR", 2029},
            {"MQRC-NO-MSG-AVAILABLE", 2033},
            {"MQRC-NOT-AUTHORIZED", 2035},
            {"MQRC-NOT-OPEN-FOR-INPUT", 2037},
            {"MQRC-NOT-OPEN-FOR-INQUIRE", 2038},
            {"MQRC-NOT-OPEN-FOR-OUTPUT", 2039},
            {"MQRC-NOT-OPEN-FOR-SET", 2040},
            {"MQRC-OBJECT-IN-USE", 2042},
            {"MQRC-OBJECT-TYPE-ERROR", 2043},
            {"MQRC-OD-ERROR", 2044},
            {"MQRC-OPTIONS-ERROR", 2046},
            {"MQRC-PERSISTENCE-ERROR", 2047},
            {"MQRC-PRIORITY-ERROR", 2050},
            {"MQRC-PUT-INHIBITED", 2051},
            {"MQRC-Q-FULL", 2053},
            {"MQRC-Q-MGR-NAME-ERROR", 2058},
            {"MQRC-Q-MGR-NOT-AVAILABLE", 2059},
            {"MQRC-SELECTOR-COUNT-ERROR", 2065},
            {"MQRC-SELECTOR-ERROR", 2067},
            {"MQRC-TRUNCATED-MSG-ACCEPTED", 2079},
            {"MQRC-TRUNCATED-MSG-FAILED", 2080},
            {"MQRC-UNKNOWN-OBJECT-NAME", 2085},
            {"MQRC-UNKNOWN-REMOTE-Q-MGR", 2087},
            {"MQRC-FORMAT-ERROR", 2110},
            {"MQRC-NOT-CONVERTED", 2119},
            {"MQRC-Q-MGR-QUIESCING", 2161},
            {"MQRC-Q-MGR-STOPPING", 2162},
            {"MQRC-PMO-ERROR", 2173},
            {"MQRC-GMO-ERROR", 2186},
            {"MQRC-CONNECTION-QUIESCING", 2202},
            {"MQRC-CONNECTION-STOPPING", 2203},
            {"MQRC-CALL-IN-PROGRESS", 2219},
            // 接続と対象の handle
            {"MQHC-DEF-HCONN", 0}, {"MQHC-UNUSABLE-HCONN", -1},
            {"MQHO-NONE", 0}, {"MQHO-UNUSABLE-HOBJ", -1},
            // 対象の種類
            {"MQOT-NONE", 0}, {"MQOT-Q", 1}, {"MQOT-NAMELIST", 2}, {"MQOT-PROCESS", 3},
            {"MQOT-STORAGE-CLASS", 4}, {"MQOT-Q-MGR", 5}, {"MQOT-CHANNEL", 6}, {"MQOT-TOPIC", 8},
            // MQOPEN の選択
            {"MQOO-BIND-AS-Q-DEF", 0}, {"MQOO-INPUT-AS-Q-DEF", 1}, {"MQOO-INPUT-SHARED", 2},
            {"MQOO-INPUT-EXCLUSIVE", 4}, {"MQOO-BROWSE", 8}, {"MQOO-OUTPUT", 16}, {"MQOO-INQUIRE", 32},
            {"MQOO-SET", 64}, {"MQOO-SAVE-ALL-CONTEXT", 128}, {"MQOO-PASS-IDENTITY-CONTEXT", 256},
            {"MQOO-PASS-ALL-CONTEXT", 512}, {"MQOO-SET-IDENTITY-CONTEXT", 1024},
            {"MQOO-SET-ALL-CONTEXT", 2048}, {"MQOO-ALTERNATE-USER-AUTHORITY", 4096},
            {"MQOO-FAIL-IF-QUIESCING", 8192}, {"MQOO-BIND-ON-OPEN", 16384},
            {"MQOO-BIND-NOT-FIXED", 32768},
            // MQCLOSE の選択
            {"MQCO-NONE", 0}, {"MQCO-DELETE", 1}, {"MQCO-DELETE-PURGE", 2},
            // MQPUT の選択
            {"MQPMO-NONE", 0}, {"MQPMO-SYNCPOINT", 2}, {"MQPMO-NO-SYNCPOINT", 4},
            {"MQPMO-DEFAULT-CONTEXT", 32}, {"MQPMO-NEW-MSG-ID", 64}, {"MQPMO-NEW-CORREL-ID", 128},
            {"MQPMO-PASS-IDENTITY-CONTEXT", 256}, {"MQPMO-PASS-ALL-CONTEXT", 512},
            {"MQPMO-SET-IDENTITY-CONTEXT", 1024}, {"MQPMO-SET-ALL-CONTEXT", 2048},
            {"MQPMO-ALTERNATE-USER-AUTHORITY", 4096}, {"MQPMO-FAIL-IF-QUIESCING", 8192},
            {"MQPMO-NO-CONTEXT", 16384}, {"MQPMO-LOGICAL-ORDER", 32768},
            // MQGET の選択
            {"MQGMO-NONE", 0}, {"MQGMO-NO-WAIT", 0}, {"MQGMO-WAIT", 1}, {"MQGMO-SYNCPOINT", 2},
            {"MQGMO-NO-SYNCPOINT", 4}, {"MQGMO-SET-SIGNAL", 8}, {"MQGMO-BROWSE-FIRST", 16},
            {"MQGMO-BROWSE-NEXT", 32}, {"MQGMO-ACCEPT-TRUNCATED-MSG", 64},
            {"MQGMO-MARK-SKIP-BACKOUT", 128}, {"MQGMO-MSG-UNDER-CURSOR", 256}, {"MQGMO-LOCK", 512},
            {"MQGMO-UNLOCK", 1024}, {"MQGMO-BROWSE-MSG-UNDER-CURSOR", 2048},
            {"MQGMO-SYNCPOINT-IF-PERSISTENT", 4096}, {"MQGMO-FAIL-IF-QUIESCING", 8192},
            {"MQGMO-CONVERT", 16384}, {"MQGMO-LOGICAL-ORDER", 32768}, {"MQGMO-COMPLETE-MSG", 65536},
            {"MQGMO-ALL-MSGS-AVAILABLE", 131072}, {"MQGMO-ALL-SEGMENTS-AVAILABLE", 262144},
            // 突き合わせの選択
            {"MQMO-NONE", 0}, {"MQMO-MATCH-MSG-ID", 1}, {"MQMO-MATCH-CORREL-ID", 2},
            {"MQMO-MATCH-GROUP-ID", 4}, {"MQMO-MATCH-MSG-SEQ-NUMBER", 8}, {"MQMO-MATCH-OFFSET", 16},
            {"MQMO-MATCH-MSG-TOKEN", 32},
            // 待ち時間、有効期限、優先度、永続性
            {"MQWI-UNLIMITED", -1}, {"MQEI-UNLIMITED", -1}, {"MQPRI-PRIORITY-AS-Q-DEF", -1},
            {"MQPER-NOT-PERSISTENT", 0}, {"MQPER-PERSISTENT", 1}, {"MQPER-PERSISTENCE-AS-Q-DEF", 2},
            // 電文の種類、報告の選択、feedback
            {"MQMT-REQUEST", 1}, {"MQMT-REPLY", 2}, {"MQMT-REPORT", 4}, {"MQMT-DATAGRAM", 8},
            {"MQRO-NONE", 0}, {"MQRO-PASS-CORREL-ID", 64}, {"MQRO-PASS-MSG-ID", 128},
            {"MQRO-COPY-MSG-ID-TO-CORREL-ID", 0}, {"MQRO-NEW-MSG-ID", 0},
            {"MQFB-NONE", 0},
            // 符号化と文字集合
            {"MQENC-NATIVE", 785}, {"MQCCSI-Q-MGR", 0}, {"MQCCSI-INHERIT", -2}, {"MQCCSI-EMBEDDED", -1},
            {"MQCCSI-APPL", -3},
            // 構造体の版
            {"MQOD-VERSION-1", 1}, {"MQOD-VERSION-2", 2}, {"MQOD-VERSION-3", 3}, {"MQOD-VERSION-4", 4},
            {"MQOD-CURRENT-VERSION", 4},
            {"MQMD-VERSION-1", 1}, {"MQMD-VERSION-2", 2}, {"MQMD-CURRENT-VERSION", 2},
            {"MQPMO-VERSION-1", 1}, {"MQPMO-VERSION-2", 2}, {"MQPMO-VERSION-3", 3},
            {"MQPMO-CURRENT-VERSION", 3},
            {"MQGMO-VERSION-1", 1}, {"MQGMO-VERSION-2", 2}, {"MQGMO-VERSION-3", 3},
            {"MQGMO-VERSION-4", 4}, {"MQGMO-CURRENT-VERSION", 4},
            {"MQTM-VERSION-1", 1}, {"MQTM-CURRENT-VERSION", 1},
            // MQINQ / MQSET の属性
            {"MQIA-CURRENT-Q-DEPTH", 3}, {"MQIA-INHIBIT-GET", 9}, {"MQIA-INHIBIT-PUT", 10},
            {"MQIA-MAX-MSG-LENGTH", 13}, {"MQIA-MAX-Q-DEPTH", 15}, {"MQIA-OPEN-INPUT-COUNT", 17},
            {"MQIA-OPEN-OUTPUT-COUNT", 18}, {"MQIA-Q-TYPE", 20}, {"MQIA-TRIGGER-CONTROL", 24},
            {"MQIA-TRIGGER-MSG-PRIORITY", 26}, {"MQIA-TRIGGER-TYPE", 28}, {"MQIA-TRIGGER-DEPTH", 29},
            {"MQCA-Q-DESC", 2013}, {"MQCA-Q-MGR-NAME", 2015}, {"MQCA-Q-NAME", 2016},
            {"MQCA-TRIGGER-DATA", 2023},
            {"MQQA-GET-ALLOWED", 0}, {"MQQA-GET-INHIBITED", 1}, {"MQQA-PUT-ALLOWED", 0},
            {"MQQA-PUT-INHIBITED", 1}, {"MQQT-LOCAL", 1}, {"MQTC-OFF", 0}, {"MQTC-ON", 1},
            // 応用の種類
            {"MQAT-CICS", 1}, {"MQAT-MVS", 2}, {"MQAT-ZOS", 2},
    };

    /** CMQV の文字の定数: 名前、PICTURE、値。 */
    private static final String[][] CHARS = {
            {"MQFMT-NONE", "X(8)", "SPACES"},
            {"MQFMT-STRING", "X(8)", "'MQSTR   '"},
            {"MQFMT-TRIGGER", "X(8)", "'MQTRIG  '"},
            {"MQFMT-XMIT-Q-HEADER", "X(8)", "'MQXMIT  '"},
            {"MQFMT-DEAD-LETTER-HEADER", "X(8)", "'MQDEAD  '"},
            {"MQFMT-RF-HEADER-2", "X(8)", "'MQHRF2  '"},
            {"MQMI-NONE", "X(24)", "LOW-VALUES"},
            {"MQCI-NONE", "X(24)", "LOW-VALUES"},
            {"MQGI-NONE", "X(24)", "LOW-VALUES"},
            {"MQACT-NONE", "X(32)", "LOW-VALUES"},
            {"MQMTOK-NONE", "X(16)", "LOW-VALUES"},
    };

    @Override
    public Optional<CopyBook> resolve(String textName, String libraryName) {
        if (libraryName != null) {
            return Optional.empty();
        }
        String name = textName.toUpperCase(Locale.ROOT);
        return text(name).map(text -> new CopyBook(name + " (MQ system copybook)", text));
    }

    /** 写し句の原文。知らない名前なら空。 */
    static Optional<String> text(String name) {
        return switch (name) {
            case "CMQV" -> Optional.of(constants());
            case "CMQODV" -> Optional.of(structure(MQOD, true));
            case "CMQODL" -> Optional.of(structure(MQOD, false));
            case "CMQMDV" -> Optional.of(structure(MQMD, true));
            case "CMQMDL" -> Optional.of(structure(MQMD, false));
            case "CMQPMOV" -> Optional.of(structure(MQPMO, true));
            case "CMQPMOL" -> Optional.of(structure(MQPMO, false));
            case "CMQGMOV" -> Optional.of(structure(MQGMO, true));
            case "CMQGMOL" -> Optional.of(structure(MQGMO, false));
            case "CMQTMV" -> Optional.of(structure(MQTM, true));
            case "CMQTML" -> Optional.of(structure(MQTM, false));
            default -> Optional.empty();
        };
    }

    private static String constants() {
        StringBuilder out = new StringBuilder();
        for (Object[] c : INTS) {
            line(out, 10, "10 " + c[0]);
            line(out, 14, "PIC S9(9) BINARY VALUE " + c[1] + ".");
        }
        for (String[] c : CHARS) {
            line(out, 10, "10 " + c[0]);
            line(out, 14, "PIC " + c[1] + " VALUE " + c[2] + ".");
        }
        return out.toString();
    }

    /** {@code values} が偽なら ...L の形 (初期値を書かない)。 */
    private static String structure(List<Item> items, boolean values) {
        StringBuilder out = new StringBuilder();
        for (Item item : items) {
            item(out, item.level(), item.name(), item.picture(), item.value(), values);
        }
        return out.toString();
    }

    private static void item(StringBuilder out, int level, String name, String picture, String value,
                             boolean values) {
        int indent = 10 + (level / 5 - 2) * 2;
        if (picture == null) {
            line(out, indent, level + " " + name + ".");
        } else if (picture.equals(V)) {
            line(out, indent, level + " " + name + ".");
            item(out, level + 5, name + "-VSPTR", P, null, values);
            item(out, level + 5, name + "-VSOFFSET", L, "0", values);
            item(out, level + 5, name + "-VSBUFSIZE", L, "0", values);
            item(out, level + 5, name + "-VSLENGTH", L, "0", values);
            item(out, level + 5, name + "-VSCCSID", L, "-3", values);
        } else if (picture.equals(P)) {
            line(out, indent, level + " " + name);
            line(out, indent + 4, values ? "POINTER VALUE NULL." : "POINTER.");
        } else {
            line(out, indent, level + " " + name);
            line(out, indent + 4, "PIC " + picture + (values && value != null ? " VALUE " + value : "") + ".");
        }
    }

    /** 固定形式の 1 行。頭に {@code indent} 個の空白を置く (10 なら 11 桁目から書く。B 領域の中)。 */
    private static void line(StringBuilder out, int indent, String text) {
        out.append(" ".repeat(indent)).append(text).append('\n');
    }
}
