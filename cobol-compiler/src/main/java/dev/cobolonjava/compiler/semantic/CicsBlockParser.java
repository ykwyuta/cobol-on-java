package dev.cobolonjava.compiler.semantic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** EXEC CICSの初期対応commandだけをfail-closedで解析する小さなisland parser。 */
final class CicsBlockParser {

    private static final Pattern BLOCK = Pattern.compile(
            "(?is)^\\s*EXEC\\s+CICS\\s+(LINK|XCTL|RETURN|SYNCPOINT|ABEND)\\b(.*?)END-EXEC\\s*$");
    private static final Pattern CONDITION_BLOCK = Pattern.compile(
            "(?is)^\\s*EXEC\\s+CICS\\s+(HANDLE|IGNORE)\\s+CONDITION\\b"
                    + "(.*?)END-EXEC\\s*$");
    private static final Pattern HANDLE_STACK_BLOCK = Pattern.compile(
            "(?is)^\\s*EXEC\\s+CICS\\s+(PUSH|POP)\\s+HANDLE\\s*END-EXEC\\s*$");
    private static final Pattern HANDLE_ABEND_BLOCK = Pattern.compile(
            "(?is)^\\s*EXEC\\s+CICS\\s+HANDLE\\s+ABEND\\b(.*?)END-EXEC\\s*$");
    private static final Pattern HANDLE_ABEND_LABEL = Pattern.compile(
            "(?is)^\\s*LABEL\\s*\\(\\s*([A-Z0-9][A-Z0-9-]*)\\s*\\)\\s*$");
    private static final Pattern SEND_BLOCK = Pattern.compile(
            "(?is)^\\s*EXEC\\s+CICS\\s+SEND\\b(.*?)END-EXEC\\s*$");
    /** SENDのoption。値は引用符つきの名前、データ名、数字、または値なし。 */
    /** option の値に書くデータ名。{@code 項目 OF 群} の修飾を含む。 */
    private static final String DATA_NAME = "[A-Z][A-Z0-9-]*(?:\\s+(?:OF|IN)\\s+[A-Z][A-Z0-9-]*)*";
    private static final Pattern SEND_OPTION = Pattern.compile(
            "(?is)\\s*([A-Z0-9][A-Z0-9-]*)"
                    + "(?:\\s*\\(\\s*(?:'([^']*)'|(\\d{1,9})|(" + DATA_NAME + "))\\s*\\))?");
    private static final Pattern BMS_NAME = Pattern.compile("[A-Z@#$][A-Z0-9@#$]{0,6}");
    private static final Pattern CONTAINER_BLOCK = Pattern.compile(
            "(?is)^\\s*EXEC\\s+CICS\\s+(GET|PUT)\\s+CONTAINER\\b(.*?)END-EXEC\\s*$");
    private static final Pattern CONTAINER_NAME = Pattern.compile("[A-Z0-9_-]{1,16}");
    private static final Pattern ENQUEUE_BLOCK = Pattern.compile(
            "(?is)^\\s*EXEC\\s+CICS\\s+(ENQ|DEQ)\\b(.*?)END-EXEC\\s*$");
    private static final Pattern RECEIVE_BLOCK = Pattern.compile(
            "(?is)^\\s*EXEC\\s+CICS\\s+RECEIVE\\b(.*?)END-EXEC\\s*$");
    private static final Pattern DELAY_BLOCK = Pattern.compile(
            "(?is)^\\s*EXEC\\s+CICS\\s+DELAY\\b(.*?)END-EXEC\\s*$");
    /** DELAYのoption。値はデータ名か符号なし整数、または値なし (FOR、NOHANDLE)。 */
    private static final Pattern DELAY_OPTION = Pattern.compile(
            "(?is)\\s*([A-Z0-9][A-Z0-9-]*)"
                    + "(?:\\s*\\(\\s*(?:(\\d{1,9})|([A-Z][A-Z0-9-]*))\\s*\\))?");
    private static final Pattern TIME_BLOCK = Pattern.compile(
            "(?is)^\\s*EXEC\\s+CICS\\s+(ASKTIME|FORMATTIME)\\b(.*?)END-EXEC\\s*$");
    /** 時間命令のoption。値はデータ名か1文字の定数、または値なし (DATESEP等)。 */
    private static final Pattern TIME_OPTION = Pattern.compile(
            "(?is)\\s*([A-Z0-9][A-Z0-9-]*)"
                    + "(?:\\s*\\(\\s*(?:(" + DATA_NAME + "|[0-9][A-Z0-9-]*)|'([^'])')\\s*\\))?");
    private static final Pattern ASSIGN_BLOCK = Pattern.compile(
            "(?is)^\\s*EXEC\\s+CICS\\s+ASSIGN\\b(.*?)END-EXEC\\s*$");
    /** ASSIGNのoptionは受取域のデータ名だけをとる。定数やRESPはここで形が合わない。 */
    private static final Pattern ASSIGN_OPTION = Pattern.compile(
            "(?is)\\s*([A-Z0-9][A-Z0-9-]*)\\s*\\(\\s*([A-Z0-9][A-Z0-9-]*)\\s*\\)");
    private static final Pattern CONDITION_OPTION = Pattern.compile(
            "(?is)\\s*([A-Z0-9][A-Z0-9-]*)(?:\\s*\\(\\s*([A-Z0-9][A-Z0-9-]*)\\s*\\))?");
    private static final Pattern QUOTED_OPTION = Pattern.compile(
            "(?is)\\b(PROGRAM|TRANSID|ABCODE)\\s*\\(\\s*(['\"])(.*?)\\2\\s*\\)");
    /** 引用符を外したあとに残るPROGRAM(データ名)。静的な名前はQUOTED_OPTIONが先に取る。 */
    private static final Pattern PROGRAM_DATA_OPTION = Pattern.compile(
            "(?is)\\bPROGRAM\\s*\\(\\s*([A-Z0-9][A-Z0-9-]*)\\s*\\)");
    /** 引用符を外したあとに残るABCODE(データ名)。 */
    private static final Pattern ABCODE_DATA_OPTION = Pattern.compile(
            "(?is)\\bABCODE\\s*\\(\\s*([A-Z][A-Z0-9-]*)\\s*\\)");
    private static final Pattern BIF_DEEDIT_BLOCK = Pattern.compile(
            "(?is)^\\s*EXEC\\s+CICS\\s+BIF\\s+DEEDIT\\s+FIELD\\s*\\(\\s*([A-Z][A-Z0-9-]*)\\s*\\)"
                    + "\\s*END-EXEC\\s*$");
    private static final Pattern NAME_OPTION = Pattern.compile(
            "(?is)\\bCOMMAREA\\s*\\(\\s*([A-Z0-9][A-Z0-9-]*)\\s*\\)");
    private static final Pattern LENGTH_OPTION = Pattern.compile(
            "(?is)\\bLENGTH\\s*\\(\\s*(\\d+)\\s*\\)");
    private static final Pattern RESP_OPTION = Pattern.compile(
            "(?is)\\bRESP\\s*\\(\\s*([A-Z0-9][A-Z0-9-]*)\\s*\\)");
    private static final Pattern RESP2_OPTION = Pattern.compile(
            "(?is)\\bRESP2\\s*\\(\\s*([A-Z0-9][A-Z0-9-]*)\\s*\\)");

    private CicsBlockParser() {
    }

    static Parsed parse(String source) {
        Objects.requireNonNull(source, "source");
        Matcher assign = ASSIGN_BLOCK.matcher(source);
        if (assign.matches()) {
            return new Parsed(null, null, null, -1, null, null,
                    false, false, false, false, false, null, List.of(), null, null, null,
                    parseAssignments(assign.group(1)), null, null, null, null, null, null);
        }
        Matcher deedit = BIF_DEEDIT_BLOCK.matcher(source);
        if (deedit.matches()) {
            return new Parsed(null, null, null, -1, null, null,
                    false, false, false, false, false, null, List.of(), null, null, null,
                    List.of(), null, null, null, null, null, deedit.group(1).toUpperCase(Locale.ROOT));
        }
        Matcher time = TIME_BLOCK.matcher(source);
        if (time.matches()) {
            return new Parsed(null, null, null, -1, null, null,
                    false, false, false, false, false, null, List.of(), null, null, null,
                    List.of(), null,
                    parseTime(time.group(1).toUpperCase(Locale.ROOT), time.group(2)), null, null, null, null);
        }
        Matcher delay = DELAY_BLOCK.matcher(source);
        if (delay.matches()) {
            return parseDelay(delay.group(1));
        }
        Matcher send = SEND_BLOCK.matcher(source);
        if (send.matches()) {
            return parseSend(send.group(1));
        }
        Matcher receive = RECEIVE_BLOCK.matcher(source);
        if (receive.matches()) {
            return parseReceive(receive.group(1));
        }
        Matcher enqueue = ENQUEUE_BLOCK.matcher(source);
        if (enqueue.matches()) {
            return parseEnqueue(enqueue.group(1).equalsIgnoreCase("ENQ"), enqueue.group(2));
        }
        Matcher container = CONTAINER_BLOCK.matcher(source);
        if (container.matches()) {
            return parseContainer(container.group(1).equalsIgnoreCase("PUT"),
                    "CONTAINER" + container.group(2));
        }
        Matcher handleStack = HANDLE_STACK_BLOCK.matcher(source);
        if (handleStack.matches()) {
            Statement.CicsHandleStackAction action = Statement.CicsHandleStackAction.valueOf(
                    handleStack.group(1).toUpperCase(Locale.ROOT));
            return new Parsed(null, null, null, -1, null, null,
                    false, false, false, false, false, null, List.of(), action, null, null, null, null,
                    null, null, null, null, null);
        }
        Matcher handleAbend = HANDLE_ABEND_BLOCK.matcher(source);
        if (handleAbend.matches()) {
            String option = handleAbend.group(1).strip();
            Statement.CicsAbendHandlerAction action;
            String target = null;
            Matcher label = HANDLE_ABEND_LABEL.matcher(option);
            if (label.matches()) {
                action = Statement.CicsAbendHandlerAction.LABEL;
                target = label.group(1).toUpperCase(Locale.ROOT);
            } else if (option.isEmpty() || option.equalsIgnoreCase("CANCEL")) {
                action = Statement.CicsAbendHandlerAction.CANCEL;
            } else if (option.equalsIgnoreCase("RESET")) {
                action = Statement.CicsAbendHandlerAction.RESET;
            } else {
                throw new IllegalArgumentException(
                        "initial HANDLE ABEND support accepts LABEL, CANCEL, or RESET");
            }
            return new Parsed(null, null, null, -1, null, null,
                    false, false, false, false, false, null, List.of(), null, action, target, null, null,
                    null, null, null, null, null);
        }
        Matcher condition = CONDITION_BLOCK.matcher(source);
        if (condition.matches()) {
            Statement.CicsConditionAction action = Statement.CicsConditionAction.valueOf(
                    condition.group(1).toUpperCase(Locale.ROOT));
            List<ConditionSpec> conditions = parseConditions(action, condition.group(2));
            return new Parsed(null, null, null, -1, null, null,
                    false, false, false, false, false, action, conditions, null, null, null, null, null,
                    null, null, null, null, null);
        }
        Matcher block = BLOCK.matcher(source);
        if (!block.matches()) {
            throw new IllegalArgumentException("unsupported or malformed EXEC CICS block");
        }
        Statement.CicsOperation operation = Statement.CicsOperation.valueOf(
                block.group(1).toUpperCase(Locale.ROOT));
        String remainder = block.group(2);
        String program = null;
        String transId = null;
        String abendCode = null;

        Matcher quoted = QUOTED_OPTION.matcher(remainder);
        StringBuffer stripped = new StringBuffer();
        while (quoted.find()) {
            String name = quoted.group(1).toUpperCase(Locale.ROOT);
            String value = quoted.group(3);
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be empty");
            }
            switch (name) {
                case "PROGRAM" -> {
                    if (program != null) {
                        throw new IllegalArgumentException("duplicate PROGRAM option");
                    }
                    program = value;
                }
                case "TRANSID" -> {
                    if (transId != null) {
                        throw new IllegalArgumentException("duplicate TRANSID option");
                    }
                    transId = value;
                }
                case "ABCODE" -> {
                    if (abendCode != null) {
                        throw new IllegalArgumentException("duplicate ABCODE option");
                    }
                    abendCode = value;
                }
                default -> throw new IllegalStateException(name);
            }
            quoted.appendReplacement(stripped, " ");
        }
        quoted.appendTail(stripped);
        remainder = stripped.toString();

        ParsedOption<String> programData = extractOne(
                PROGRAM_DATA_OPTION, remainder, matcher -> matcher.group(1).toUpperCase(Locale.ROOT));
        remainder = programData.remainder;
        if (program != null && programData.value != null) {
            throw new IllegalArgumentException("duplicate PROGRAM option");
        }
        ParsedOption<String> abcodeData = extractOne(
                ABCODE_DATA_OPTION, remainder, matcher -> matcher.group(1).toUpperCase(Locale.ROOT));
        remainder = abcodeData.remainder;
        if (abendCode != null && abcodeData.value != null) {
            throw new IllegalArgumentException("duplicate ABCODE option");
        }

        ParsedOption<String> commarea = extractOne(NAME_OPTION, remainder, matcher -> matcher.group(1));
        remainder = commarea.remainder;
        ParsedOption<Integer> length = extractOne(
                LENGTH_OPTION, remainder, matcher -> parseLength(matcher.group(1)));
        remainder = length.remainder;
        ParsedOption<String> response = extractOne(
                RESP_OPTION, remainder, matcher -> matcher.group(1));
        remainder = response.remainder;
        ParsedOption<String> response2 = extractOne(
                RESP2_OPTION, remainder, matcher -> matcher.group(1));
        remainder = response2.remainder;

        ParsedOption<Boolean> noHandleOption = extractFlag("NOHANDLE", remainder);
        remainder = noHandleOption.remainder;
        ParsedOption<Boolean> rollbackOption = extractFlag("ROLLBACK", remainder);
        remainder = rollbackOption.remainder;
        ParsedOption<Boolean> cancelOption = extractFlag("CANCEL", remainder);
        remainder = cancelOption.remainder;
        ParsedOption<Boolean> noDumpOption = extractFlag("NODUMP", remainder);
        remainder = noDumpOption.remainder;
        ParsedOption<Boolean> immediateOption = extractFlag("IMMEDIATE", remainder);
        remainder = immediateOption.remainder;
        ParsedOption<Boolean> synconreturnOption = extractFlag("SYNCONRETURN", remainder);
        remainder = synconreturnOption.remainder;
        if (Boolean.TRUE.equals(synconreturnOption.value)
                && operation != Statement.CicsOperation.LINK) {
            throw new IllegalArgumentException("SYNCONRETURN is only supported by LINK");
        }
        // SYNCONRETURNは分散プログラムリンクで遠隔regionに同期点を取らせる指定であり、
        // 同じregion内のLINKでは効果を持たない。このLINKは常にlocalなので保持しない (暫定判断 P-114)
        boolean rollback = Boolean.TRUE.equals(rollbackOption.value);
        boolean cancel = Boolean.TRUE.equals(cancelOption.value);
        boolean noDump = Boolean.TRUE.equals(noDumpOption.value);
        boolean noHandle = Boolean.TRUE.equals(noHandleOption.value);
        boolean immediate = Boolean.TRUE.equals(immediateOption.value);
        if (!remainder.isBlank()) {
            throw new IllegalArgumentException(
                    "unsupported EXEC CICS option: " + remainder.strip());
        }
        validate(operation, program != null || programData.value != null, transId,
                abendCode != null ? abendCode : abcodeData.value,
                commarea.value, length.value,
                response.value, response2.value, rollback, cancel, noDump, immediate);
        String target = switch (operation) {
            case RETURN -> transId;
            case ABEND -> abendCode;
            default -> program;
        };
        return new Parsed(operation, target, commarea.value,
                length.value == null ? -1 : length.value, response.value, response2.value,
                noHandle, rollback, cancel, noDump, immediate,
                null, List.of(), null, null, null, null,
                // LINK / XCTL の PROGRAM と ABEND の ABCODE は、同じ「名前を持つデータ域」として渡す
                operation == Statement.CicsOperation.ABEND ? abcodeData.value : programData.value,
                null, null, null, null, null);
    }

    /**
     * SEND MAP / SEND TEXT / SEND CONTROL を読む (設計 79 §8)。
     *
     * <p>資産が使う option だけを受ける。{@code ERASEAUP}、{@code ACCUM}、{@code PAGING} 等は
     * 画面の合成規則を持たないので、名前をつけて断る。
     */
    private static Parsed parseSend(String source) {
        java.util.Map<String, String[]> options = new java.util.LinkedHashMap<>();
        Matcher option = SEND_OPTION.matcher(source);
        int position = 0;
        while (!source.substring(position).isBlank()) {
            option.region(position, source.length());
            if (!option.lookingAt()) {
                throw new IllegalArgumentException("unsupported or malformed EXEC CICS block");
            }
            String name = option.group(1).toUpperCase(Locale.ROOT);
            if (options.containsKey(name)) {
                throw new IllegalArgumentException("duplicate SEND option: " + name);
            }
            options.put(name, new String[] {option.group(2), option.group(3),
                    option.group(4) == null ? null : option.group(4).toUpperCase(Locale.ROOT)});
            position = option.end();
        }
        Statement.CicsSendKind kind;
        java.util.Set<String> allowed;
        if (options.containsKey("MAP")) {
            kind = Statement.CicsSendKind.MAP;
            allowed = Set.of("MAP", "MAPSET", "FROM", "ERASE", "DATAONLY", "MAPONLY", "CURSOR",
                    "FREEKB", "ALARM", "FRSET", "RESP", "RESP2", "NOHANDLE");
        } else if (options.containsKey("TEXT")) {
            kind = Statement.CicsSendKind.TEXT;
            allowed = Set.of("TEXT", "FROM", "ERASE", "FREEKB", "ALARM", "RESP", "RESP2", "NOHANDLE");
        } else if (options.containsKey("CONTROL")) {
            kind = Statement.CicsSendKind.CONTROL;
            allowed = Set.of("CONTROL", "ERASE", "FREEKB", "ALARM", "FRSET", "CURSOR",
                    "RESP", "RESP2", "NOHANDLE");
        } else {
            throw new IllegalArgumentException("unsupported or malformed EXEC CICS block");
        }
        for (String name : options.keySet()) {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException(
                        "unsupported SEND " + kind + " option: " + name);
            }
        }
        java.util.function.Predicate<String> flag = name -> {
            String[] value = options.get(name);
            if (value == null) {
                return false;
            }
            if (value[0] != null || value[1] != null || value[2] != null) {
                throw new IllegalArgumentException(name + " does not take a value");
            }
            return true;
        };
        int flags = (flag.test("ERASE") ? dev.cobolonjava.cics.CicsRuntimeOps.SEND_ERASE : 0)
                | (flag.test("MAPONLY") ? dev.cobolonjava.cics.CicsRuntimeOps.SEND_MAPONLY : 0)
                | (flag.test("DATAONLY") ? dev.cobolonjava.cics.CicsRuntimeOps.SEND_DATAONLY : 0)
                | (flag.test("FREEKB") ? dev.cobolonjava.cics.CicsRuntimeOps.SEND_FREEKB : 0)
                | (flag.test("ALARM") ? dev.cobolonjava.cics.CicsRuntimeOps.SEND_ALARM : 0)
                | (flag.test("FRSET") ? dev.cobolonjava.cics.CicsRuntimeOps.SEND_FRSET : 0);
        if ((flags & dev.cobolonjava.cics.CicsRuntimeOps.SEND_MAPONLY) != 0
                && (flags & dev.cobolonjava.cics.CicsRuntimeOps.SEND_DATAONLY) != 0) {
            throw new IllegalArgumentException("MAPONLY and DATAONLY are mutually exclusive");
        }
        int cursor = dev.cobolonjava.cics.CicsRuntimeOps.CURSOR_NONE;
        String[] cursorValue = options.get("CURSOR");
        if (cursorValue != null) {
            if (cursorValue[1] != null) {
                cursor = Integer.parseInt(cursorValue[1]);
            } else if (cursorValue[0] != null || cursorValue[2] != null) {
                // CURSOR(データ名) は翻訳時に位置が決まらないので後続増分とする
                throw new IllegalArgumentException("CURSOR accepts only a numeric position");
            } else if (kind == Statement.CicsSendKind.MAP) {
                cursor = dev.cobolonjava.cics.CicsRuntimeOps.CURSOR_SYMBOLIC;
            } else {
                throw new IllegalArgumentException("SEND CONTROL CURSOR requires a position");
            }
        }
        String map = null;
        String mapset = null;
        if (kind == Statement.CicsSendKind.MAP) {
            map = bmsName("MAP", options.get("MAP"));
            mapset = options.containsKey("MAPSET") ? bmsName("MAPSET", options.get("MAPSET")) : map;
        } else if (kind == Statement.CicsSendKind.TEXT && !flag.test("TEXT")) {
            throw new IllegalArgumentException("TEXT does not take a value");
        } else if (kind == Statement.CicsSendKind.CONTROL && !flag.test("CONTROL")) {
            throw new IllegalArgumentException("CONTROL does not take a value");
        }
        String from = null;
        String[] fromValue = options.get("FROM");
        if (fromValue != null) {
            if (fromValue[2] == null) {
                throw new IllegalArgumentException("FROM requires a data name");
            }
            from = fromValue[2];
        }
        boolean mapOnly = (flags & dev.cobolonjava.cics.CicsRuntimeOps.SEND_MAPONLY) != 0;
        if (kind == Statement.CicsSendKind.MAP && from == null && !mapOnly) {
            // 省いた FROM を map 名 + "O" で補う規則は確かめていないので、書くことを求める
            throw new IllegalArgumentException("SEND MAP requires FROM unless MAPONLY is specified");
        }
        if (kind == Statement.CicsSendKind.MAP && from != null && mapOnly) {
            throw new IllegalArgumentException("SEND MAP MAPONLY does not accept FROM");
        }
        if (kind == Statement.CicsSendKind.TEXT && from == null) {
            throw new IllegalArgumentException("SEND TEXT requires FROM");
        }
        String response = sendDataName(options.get("RESP"), "RESP");
        String response2 = sendDataName(options.get("RESP2"), "RESP2");
        if (response2 != null && response == null) {
            throw new IllegalArgumentException("RESP2 requires RESP");
        }
        SendSpec spec = new SendSpec(kind, map, mapset, from, flags, cursor);
        return new Parsed(null, null, null, -1, response, response2,
                flag.test("NOHANDLE"), false, false, false, false,
                null, List.of(), null, null, null, List.of(), null, null, null, spec, null, null);
    }

    /**
     * RECEIVE MAP を読む (設計 79 §8.4)。
     *
     * <p>{@code INTO} は書くことを求める。省いた INTO を map 名 + "I" で補う規則と、
     * {@code SET} による番地渡しは確かめていない。{@code ASIS} 等は名前をつけて断る。
     */
    private static Parsed parseReceive(String source) {
        java.util.Map<String, String[]> options = new java.util.LinkedHashMap<>();
        Matcher option = SEND_OPTION.matcher(source);
        int position = 0;
        while (!source.substring(position).isBlank()) {
            option.region(position, source.length());
            if (!option.lookingAt()) {
                throw new IllegalArgumentException("unsupported or malformed EXEC CICS block");
            }
            String name = option.group(1).toUpperCase(Locale.ROOT);
            if (options.containsKey(name)) {
                throw new IllegalArgumentException("duplicate RECEIVE option: " + name);
            }
            options.put(name, new String[] {option.group(2), option.group(3),
                    option.group(4) == null ? null : option.group(4).toUpperCase(Locale.ROOT)});
            position = option.end();
        }
        if (!options.containsKey("MAP")) {
            throw new IllegalArgumentException("unsupported or malformed EXEC CICS block");
        }
        for (String name : options.keySet()) {
            if (!Set.of("MAP", "MAPSET", "INTO", "RESP", "RESP2", "NOHANDLE").contains(name)) {
                throw new IllegalArgumentException("unsupported RECEIVE MAP option: " + name);
            }
        }
        String map = bmsName("MAP", options.get("MAP"));
        String mapset = options.containsKey("MAPSET") ? bmsName("MAPSET", options.get("MAPSET")) : map;
        String into = sendDataName(options.get("INTO"), "INTO");
        if (into == null) {
            throw new IllegalArgumentException("RECEIVE MAP requires INTO");
        }
        String[] noHandle = options.get("NOHANDLE");
        if (noHandle != null && (noHandle[0] != null || noHandle[1] != null || noHandle[2] != null)) {
            throw new IllegalArgumentException("NOHANDLE does not take a value");
        }
        String response = sendDataName(options.get("RESP"), "RESP");
        String response2 = sendDataName(options.get("RESP2"), "RESP2");
        if (response2 != null && response == null) {
            throw new IllegalArgumentException("RESP2 requires RESP");
        }
        return new Parsed(null, null, null, -1, response, response2,
                noHandle != null, false, false, false, false,
                null, List.of(), null, null, null, List.of(), null, null, null, null,
                new ReceiveSpec(map, mapset, into), null);
    }

    /**
     * GET / PUT CONTAINER を読む (設計 79 §9)。
     *
     * <p>CHANNEL つきの形と、現在のchannelを使う形を受ける。DATATYPE、INTOCCSID、SET、NODATA、APPEND などは
     * 文字の変換や記憶域の番地を伴うので、名前をつけて断る。
     */
    private static Parsed parseContainer(boolean put, String source) {
        java.util.Map<String, String[]> options = new java.util.LinkedHashMap<>();
        Matcher option = SEND_OPTION.matcher(source);
        int position = 0;
        while (!source.substring(position).isBlank()) {
            option.region(position, source.length());
            if (!option.lookingAt()) {
                throw new IllegalArgumentException("unsupported or malformed EXEC CICS block");
            }
            String name = option.group(1).toUpperCase(Locale.ROOT);
            if (options.containsKey(name)) {
                throw new IllegalArgumentException("duplicate CONTAINER option: " + name);
            }
            options.put(name, new String[] {option.group(2), option.group(3),
                    option.group(4) == null ? null : option.group(4).toUpperCase(Locale.ROOT)});
            position = option.end();
        }
        String command = put ? "PUT CONTAINER" : "GET CONTAINER";
        Set<String> allowed = Set.of("CONTAINER", "CHANNEL", put ? "FROM" : "INTO", "FLENGTH",
                "RESP", "RESP2", "NOHANDLE");
        for (String name : options.keySet()) {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("unsupported " + command + " option: " + name);
            }
        }
        String[] name = options.get("CONTAINER");
        String[] channel = options.get("CHANNEL");
        String area = sendDataName(options.get(put ? "FROM" : "INTO"), put ? "FROM" : "INTO");
        if (area == null) {
            throw new IllegalArgumentException(command + " requires " + (put ? "FROM" : "INTO"));
        }
        String[] length = options.get("FLENGTH");
        int lengthLiteral = -1;
        String lengthData = null;
        if (length != null) {
            if (length[2] != null) {
                lengthData = length[2];
            } else if (put && length[1] != null) {
                lengthLiteral = Integer.parseInt(length[1]);
            } else {
                // GET の FLENGTH は長さを返す受取域なので、定数では書けない
                throw new IllegalArgumentException(command + " FLENGTH requires a data name");
            }
        }
        String[] noHandle = options.get("NOHANDLE");
        if (noHandle != null && (noHandle[0] != null || noHandle[1] != null || noHandle[2] != null)) {
            throw new IllegalArgumentException("NOHANDLE does not take a value");
        }
        String response = sendDataName(options.get("RESP"), "RESP");
        String response2 = sendDataName(options.get("RESP2"), "RESP2");
        if (response2 != null && response == null) {
            throw new IllegalArgumentException("RESP2 requires RESP");
        }
        ContainerSpec spec = new ContainerSpec(put,
                containerLiteral("CONTAINER", name), name[2],
                channel == null ? null : containerLiteral("CHANNEL", channel),
                channel == null ? null : channel[2],
                area, lengthData, lengthLiteral);
        return new Parsed(null, null, null, -1, response, response2,
                noHandle != null, false, false, false, false,
                null, List.of(), null, null, null, List.of(), null, null, null, null,
                null, null, spec, null);
    }

    /**
     * ENQ / DEQ を読む (暫定判断 P-128)。
     *
     * <p>{@code LENGTH} を省いた形は、域の<b>番地</b>を資源にする。この処理系は番地を持たないので断る。
     * {@code LUW}、{@code MAXLIFETIME} は名前をつけて断る。
     */
    private static Parsed parseEnqueue(boolean enqueue, String source) {
        String command = enqueue ? "ENQ" : "DEQ";
        java.util.Map<String, String[]> options = new java.util.LinkedHashMap<>();
        Matcher option = SEND_OPTION.matcher(source);
        int position = 0;
        while (!source.substring(position).isBlank()) {
            option.region(position, source.length());
            if (!option.lookingAt()) {
                throw new IllegalArgumentException("unsupported or malformed EXEC CICS block");
            }
            String name = option.group(1).toUpperCase(Locale.ROOT);
            if (options.containsKey(name)) {
                throw new IllegalArgumentException("duplicate " + command + " option: " + name);
            }
            options.put(name, new String[] {option.group(2), option.group(3),
                    option.group(4) == null ? null : option.group(4).toUpperCase(Locale.ROOT)});
            position = option.end();
        }
        Set<String> allowed = enqueue
                ? Set.of("RESOURCE", "LENGTH", "NOSUSPEND", "UOW", "TASK", "RESP", "RESP2", "NOHANDLE")
                : Set.of("RESOURCE", "LENGTH", "UOW", "TASK", "RESP", "RESP2", "NOHANDLE");
        for (String name : options.keySet()) {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("unsupported " + command + " option: " + name);
            }
        }
        for (String flag : List.of("NOSUSPEND", "UOW", "TASK", "NOHANDLE")) {
            String[] value = options.get(flag);
            if (value != null && (value[0] != null || value[1] != null || value[2] != null)) {
                throw new IllegalArgumentException(flag + " does not take a value");
            }
        }
        if (options.containsKey("UOW") && options.containsKey("TASK")) {
            throw new IllegalArgumentException(command + " accepts only one of UOW and TASK");
        }
        String resource = sendDataName(options.get("RESOURCE"), "RESOURCE");
        if (resource == null) {
            throw new IllegalArgumentException(command + " requires RESOURCE");
        }
        String[] length = options.get("LENGTH");
        if (length == null) {
            throw new IllegalArgumentException(command
                    + " requires LENGTH; a resource named by its address is not supported");
        }
        if (length[1] == null) {
            throw new IllegalArgumentException(command + " LENGTH requires an integer literal");
        }
        int bytes = Integer.parseInt(length[1]);
        if (bytes < 1 || bytes > 255) {
            throw new IllegalArgumentException(command + " LENGTH must be 1 to 255: " + bytes);
        }
        String response = sendDataName(options.get("RESP"), "RESP");
        String response2 = sendDataName(options.get("RESP2"), "RESP2");
        if (response2 != null && response == null) {
            throw new IllegalArgumentException("RESP2 requires RESP");
        }
        return new Parsed(null, null, null, -1, response, response2,
                options.containsKey("NOHANDLE"), false, false, false, false,
                null, List.of(), null, null, null, List.of(), null, null, null, null,
                null, null, null, new EnqueueSpec(enqueue, resource, bytes,
                        options.containsKey("NOSUSPEND"), options.containsKey("TASK")));
    }

    /** 引用符の名前ならその値、データ名なら null。どちらでもなければ断る。 */
    private static String containerLiteral(String option, String[] value) {
        if (value[2] != null) {
            return null;
        }
        if (value[0] == null) {
            throw new IllegalArgumentException(option + " requires a quoted name or a data name");
        }
        String name = value[0].stripTrailing();
        if (!CONTAINER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(option + " name must be 1 to 16 characters of A-Z, 0-9, _ or -: "
                    + value[0]);
        }
        return name;
    }

    private static String bmsName(String option, String[] value) {
        if (value[0] == null) {
            throw new IllegalArgumentException(option + " requires a quoted name");
        }
        String name = value[0].strip().toUpperCase(Locale.ROOT);
        if (!BMS_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(option + " must be 1 to 7 characters: " + value[0]);
        }
        return name;
    }

    private static String sendDataName(String[] value, String option) {
        if (value == null) {
            return null;
        }
        if (value[2] == null) {
            throw new IllegalArgumentException(option + " requires a data name");
        }
        return value[2];
    }

    /**
     * DELAYを読む (設計 79 §7)。
     *
     * <p>{@code FOR}の単位と{@code INTERVAL}を受ける。{@code TIME}、{@code UNTIL}、{@code REQID}は
     * 時刻の解釈と取消しの設計を持たないので、名前をつけて断る。optionを何も書かなければ
     * {@code INTERVAL(0)}と同じである。
     */
    private static Parsed parseDelay(String source) {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        Matcher option = DELAY_OPTION.matcher(source);
        int position = 0;
        while (!source.substring(position).isBlank()) {
            option.region(position, source.length());
            if (!option.lookingAt()) {
                throw new IllegalArgumentException("unsupported or malformed EXEC CICS block");
            }
            String name = option.group(1).toUpperCase(Locale.ROOT);
            if (values.containsKey(name)) {
                throw new IllegalArgumentException("duplicate DELAY option: " + name);
            }
            String value = option.group(2) != null ? option.group(2)
                    : option.group(3) == null ? null : option.group(3).toUpperCase(Locale.ROOT);
            boolean flag = name.equals("FOR") || name.equals("NOHANDLE");
            switch (name) {
                case "FOR", "NOHANDLE", "HOURS", "MINUTES", "SECONDS", "MILLISECS",
                        "INTERVAL", "RESP", "RESP2" -> { }
                default -> throw new IllegalArgumentException("unsupported DELAY option: " + name);
            }
            if (flag != (value == null)) {
                throw new IllegalArgumentException(flag
                        ? name + " does not take a value" : name + " requires a value");
            }
            values.put(name, value);
            position = option.end();
        }
        boolean units = values.containsKey("HOURS") || values.containsKey("MINUTES")
                || values.containsKey("SECONDS") || values.containsKey("MILLISECS");
        if (units && !values.containsKey("FOR")) {
            throw new IllegalArgumentException("DELAY HOURS, MINUTES, SECONDS and MILLISECS require FOR");
        }
        if (values.containsKey("FOR") && !units) {
            throw new IllegalArgumentException("DELAY FOR requires HOURS, MINUTES, SECONDS or MILLISECS");
        }
        if (values.containsKey("FOR") && values.containsKey("INTERVAL")) {
            throw new IllegalArgumentException("DELAY FOR cannot be combined with INTERVAL");
        }
        String response = values.get("RESP");
        String response2 = values.get("RESP2");
        if (response2 != null && response == null) {
            throw new IllegalArgumentException("RESP2 requires RESP");
        }
        for (String name : List.of("RESP", "RESP2")) {
            if (values.get(name) != null && values.get(name).chars().allMatch(Character::isDigit)) {
                throw new IllegalArgumentException(name + " requires a data name");
            }
        }
        DelaySpec spec = units
                ? new DelaySpec(values.get("HOURS"), values.get("MINUTES"), values.get("SECONDS"),
                        values.get("MILLISECS"), null)
                : new DelaySpec(null, null, null, null,
                        values.getOrDefault("INTERVAL", "0"));
        return new Parsed(null, null, null, -1, response, response2,
                values.containsKey("NOHANDLE"), false, false, false, false,
                null, List.of(), null, null, null, List.of(), null, null, spec, null, null, null);
    }

    /**
     * ASKTIME / FORMATTIME のoptionを読む (設計 79 §6)。
     *
     * <p>FORMATTIMEは資産が使う日付の並び3つ、TIME、区切りだけを受ける。YYDDD等の
     * 別の形は書く文字数を確かめていないので、名前をつけて断る。
     */
    private static TimeSpec parseTime(String command, String source) {
        java.util.Map<String, String[]> options = new java.util.LinkedHashMap<>();
        Matcher option = TIME_OPTION.matcher(source);
        int position = 0;
        while (!source.substring(position).isBlank()) {
            option.region(position, source.length());
            if (!option.lookingAt()) {
                throw new IllegalArgumentException("unsupported or malformed EXEC CICS block");
            }
            String name = option.group(1).toUpperCase(Locale.ROOT);
            if (options.containsKey(name)) {
                throw new IllegalArgumentException("duplicate " + command + " option: " + name);
            }
            options.put(name, new String[] {
                    option.group(2) == null ? null : option.group(2).toUpperCase(Locale.ROOT),
                    option.group(3)});
            position = option.end();
        }
        if (command.equals("ASKTIME")) {
            for (String name : options.keySet()) {
                if (!name.equals("ABSTIME")) {
                    throw new IllegalArgumentException("unsupported ASKTIME option: " + name);
                }
            }
            String abstime = dataName(command, "ABSTIME", options.get("ABSTIME"), false);
            return new TimeSpec(true, abstime, null, null, null, null, null);
        }
        Statement.CicsDateOrder order = null;
        String date = null;
        String dateSeparator = null;
        String time = null;
        String timeSeparator = null;
        for (java.util.Map.Entry<String, String[]> entry : options.entrySet()) {
            String name = entry.getKey();
            String[] value = entry.getValue();
            switch (name) {
                case "ABSTIME" -> { }
                case "DDMMYYYY", "YYYYMMDD", "MMDDYYYY" -> {
                    if (order != null) {
                        throw new IllegalArgumentException(
                                "FORMATTIME accepts only one date format option");
                    }
                    order = Statement.CicsDateOrder.valueOf(name);
                    date = dataName(command, name, value, true);
                }
                case "TIME" -> time = dataName(command, name, value, true);
                case "DATESEP" -> dateSeparator = separator(name, value, "/");
                case "TIMESEP" -> timeSeparator = separator(name, value, ":");
                default -> throw new IllegalArgumentException(
                        "unsupported FORMATTIME option: " + name);
            }
        }
        String abstime = dataName(command, "ABSTIME", options.get("ABSTIME"), true);
        if (dateSeparator != null && date == null) {
            throw new IllegalArgumentException("DATESEP requires a date format option");
        }
        if (timeSeparator != null && time == null) {
            throw new IllegalArgumentException("TIMESEP requires TIME");
        }
        if (date == null && time == null) {
            throw new IllegalArgumentException("FORMATTIME requires a date format option or TIME");
        }
        return new TimeSpec(false, abstime, order, date, dateSeparator, time, timeSeparator);
    }

    private static String dataName(String command, String name, String[] value, boolean required) {
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException(command + " requires " + name + "(data-name)");
            }
            return null;
        }
        if (value[0] == null) {
            throw new IllegalArgumentException(command + " " + name + " requires a data name");
        }
        return value[0];
    }

    private static String separator(String name, String[] value, String defaultSeparator) {
        if (value[0] != null) {
            // 区切りをデータ名で渡す形は、翻訳時に文字が決まらないので後続増分とする
            throw new IllegalArgumentException(name + " accepts only a one-character literal");
        }
        return value[1] == null ? defaultSeparator : value[1];
    }

    /**
     * ASSIGNのoptionを読む (設計 79 §5)。
     *
     * <p>対応するoptionごとに受取域の長さが決まっている。知らないoptionを捨てると
     * 受取域が書き換わらないまま進むので、名前をつけて断る。
     */
    private static List<AssignSpec> parseAssignments(String source) {
        List<AssignSpec> out = new ArrayList<>();
        Set<Statement.CicsAssignOption> seen = new HashSet<>();
        Matcher option = ASSIGN_OPTION.matcher(source);
        int position = 0;
        while (!source.substring(position).isBlank()) {
            option.region(position, source.length());
            if (!option.lookingAt()) {
                throw new IllegalArgumentException("unsupported or malformed EXEC CICS block");
            }
            String name = option.group(1).toUpperCase(Locale.ROOT);
            Statement.CicsAssignOption kind;
            try {
                kind = Statement.CicsAssignOption.valueOf(name);
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("unsupported ASSIGN option: " + name);
            }
            if (!seen.add(kind)) {
                throw new IllegalArgumentException("duplicate ASSIGN option: " + name);
            }
            out.add(new AssignSpec(kind, option.group(2).toUpperCase(Locale.ROOT)));
            position = option.end();
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("unsupported or malformed EXEC CICS block");
        }
        return List.copyOf(out);
    }

    private static List<ConditionSpec> parseConditions(
            Statement.CicsConditionAction action, String source) {
        List<ConditionSpec> conditions = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Matcher option = CONDITION_OPTION.matcher(source);
        int position = 0;
        while (position < source.length()) {
            if (source.substring(position).isBlank()) {
                break;
            }
            if (position > 0 && !Character.isWhitespace(source.charAt(position))) {
                throw new IllegalArgumentException(
                        "CICS conditions must be separated by whitespace");
            }
            option.region(position, source.length());
            if (!option.lookingAt()) {
                throw new IllegalArgumentException(
                        "unsupported or malformed HANDLE/IGNORE CONDITION option: "
                                + source.substring(position).strip());
            }
            String name = option.group(1).toUpperCase(Locale.ROOT);
            String target = option.group(2) == null
                    ? null : option.group(2).toUpperCase(Locale.ROOT);
            if (!names.add(name)) {
                throw new IllegalArgumentException(
                        "duplicate CICS condition in one command: " + name);
            }
            if (action == Statement.CicsConditionAction.IGNORE && target != null) {
                throw new IllegalArgumentException(
                        "IGNORE CONDITION does not accept a handler paragraph");
            }
            conditions.add(new ConditionSpec(name, target));
            if (conditions.size() > 16) {
                throw new IllegalArgumentException(
                        "HANDLE/IGNORE CONDITION accepts no more than 16 conditions");
            }
            position = option.end();
        }
        if (conditions.isEmpty()) {
            throw new IllegalArgumentException(
                    "HANDLE/IGNORE CONDITION requires at least one condition");
        }
        return List.copyOf(conditions);
    }

    private static void validate(
            Statement.CicsOperation operation, boolean hasProgram, String transId, String abendCode,
            String commarea, Integer length, String response, String response2,
            boolean rollback, boolean cancel, boolean noDump, boolean immediate) {
        String program = hasProgram ? "" : null;
        if (immediate && operation != Statement.CicsOperation.RETURN) {
            throw new IllegalArgumentException("IMMEDIATE is only supported by RETURN");
        }
        if (immediate && transId == null) {
            // 次に始めるtaskの指定が無ければ、IMMEDIATEは起動するものを持たない
            throw new IllegalArgumentException("RETURN IMMEDIATE requires TRANSID");
        }
        if ((operation == Statement.CicsOperation.LINK
                || operation == Statement.CicsOperation.XCTL) && program == null) {
            throw new IllegalArgumentException(
                    operation + " requires PROGRAM('name') or PROGRAM(data-name)");
        }
        if (operation != Statement.CicsOperation.LINK
                && operation != Statement.CicsOperation.XCTL && program != null) {
            throw new IllegalArgumentException(operation + " does not accept PROGRAM");
        }
        if (operation != Statement.CicsOperation.RETURN && transId != null) {
            throw new IllegalArgumentException("TRANSID is only supported by RETURN");
        }
        if (operation != Statement.CicsOperation.ABEND && abendCode != null) {
            throw new IllegalArgumentException("ABCODE is only supported by ABEND");
        }
        if (operation == Statement.CicsOperation.SYNCPOINT
                && (program != null || transId != null || commarea != null || length != null)) {
            throw new IllegalArgumentException("SYNCPOINT does not accept data options");
        }
        if (operation == Statement.CicsOperation.ABEND
                && (commarea != null || length != null || rollback)) {
            throw new IllegalArgumentException("ABEND does not accept data or ROLLBACK options");
        }
        if (operation != Statement.CicsOperation.SYNCPOINT && rollback) {
            throw new IllegalArgumentException("ROLLBACK is only supported by SYNCPOINT");
        }
        if (operation != Statement.CicsOperation.ABEND && (cancel || noDump)) {
            throw new IllegalArgumentException("CANCEL and NODUMP are only supported by ABEND");
        }
        if (length != null && commarea == null) {
            throw new IllegalArgumentException("LENGTH requires COMMAREA");
        }
        // COMMAREAだけを書いたときのLENGTHは、COBOLではtranslatorがデータ項目の長さで補う。
        // 生成側がその長さを使う。LENGTHを必須にしていたのは規則を狭く決めすぎていた

        if (operation == Statement.CicsOperation.RETURN
                && commarea != null && transId == null) {
            throw new IllegalArgumentException("RETURN COMMAREA requires TRANSID");
        }
        if (response2 != null && response == null) {
            throw new IllegalArgumentException("RESP2 requires RESP");
        }
    }

    private static ParsedOption<Boolean> extractFlag(String name, String source) {
        Pattern pattern = Pattern.compile("(?is)\\b" + name + "\\b");
        return extractOne(pattern, source, matcher -> Boolean.TRUE);
    }

    private static int parseLength(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException("LENGTH must be positive");
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("LENGTH is out of range", invalid);
        }
    }

    private static <T> ParsedOption<T> extractOne(
            Pattern pattern, String source, java.util.function.Function<Matcher, T> value) {
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) {
            return new ParsedOption<>(null, source);
        }
        T found = value.apply(matcher);
        StringBuffer stripped = new StringBuffer();
        matcher.appendReplacement(stripped, " ");
        if (matcher.find()) {
            throw new IllegalArgumentException("duplicate EXEC CICS option");
        }
        matcher.appendTail(stripped);
        return new ParsedOption<>(found, stripped.toString());
    }

    record Parsed(
            Statement.CicsOperation operation,
            String target,
            String commarea,
            int length,
            String response,
            String response2,
            boolean noHandle,
            boolean rollback,
            boolean cancel,
            boolean noDump,
            boolean immediate,
            Statement.CicsConditionAction conditionAction,
            List<ConditionSpec> conditions,
            Statement.CicsHandleStackAction handleStackAction,
            Statement.CicsAbendHandlerAction abendHandlerAction,
            String abendHandlerTarget,
            List<AssignSpec> assignments,
            String programData,
            TimeSpec time,
            DelaySpec delay,
            SendSpec send,
            ReceiveSpec receive,
            String deedit,
            ContainerSpec container,
            EnqueueSpec enqueue) {
        Parsed {
            conditions = List.copyOf(conditions);
            assignments = assignments == null ? List.of() : List.copyOf(assignments);
        }

        /** containerを持たない命令の形。 */
        Parsed(Statement.CicsOperation operation, String target, String commarea, int length,
               String response, String response2, boolean noHandle, boolean rollback, boolean cancel,
               boolean noDump, boolean immediate, Statement.CicsConditionAction conditionAction,
               List<ConditionSpec> conditions, Statement.CicsHandleStackAction handleStackAction,
               Statement.CicsAbendHandlerAction abendHandlerAction, String abendHandlerTarget,
               List<AssignSpec> assignments, String programData, TimeSpec time, DelaySpec delay,
               SendSpec send, ReceiveSpec receive, String deedit) {
            this(operation, target, commarea, length, response, response2, noHandle, rollback, cancel,
                    noDump, immediate, conditionAction, conditions, handleStackAction, abendHandlerAction,
                    abendHandlerTarget, assignments, programData, time, delay, send, receive, deedit, null, null);
        }
    }

    /** ENQ / DEQ の、データ名を解決する前の形。 */
    record EnqueueSpec(boolean enqueue, String resource, int length, boolean noSuspend, boolean taskScope) {
    }

    /** GET / PUT CONTAINER の、データ名を解決する前の形。名前は定数かデータ名のどちらか。 */
    record ContainerSpec(boolean put, String nameLiteral, String nameData,
                         String channelLiteral, String channelData,
                         String area, String lengthData, int lengthLiteral) {
    }

    record AssignSpec(Statement.CicsAssignOption option, String target) {
    }

    /** RECEIVE MAPの、データ名を解決する前の形。 */
    record ReceiveSpec(String map, String mapset, String into) {
    }

    /** SEND命令の、データ名を解決する前の形。 */
    record SendSpec(Statement.CicsSendKind kind, String map, String mapset, String from,
                    int flags, int cursor) {
    }

    /** DELAYの各値。数字だけなら整数定数、そうでなければデータ名。書かなければ null。 */
    record DelaySpec(String hours, String minutes, String seconds, String millis, String interval) {
    }

    /** ASKTIME (ask=true) または FORMATTIME の、データ名を解決する前の形。 */
    record TimeSpec(
            boolean ask,
            String abstime,
            Statement.CicsDateOrder dateOrder,
            String date,
            String dateSeparator,
            String time,
            String timeSeparator) {
    }

    record ConditionSpec(String name, String target) {
    }

    private record ParsedOption<T>(T value, String remainder) {
    }
}
