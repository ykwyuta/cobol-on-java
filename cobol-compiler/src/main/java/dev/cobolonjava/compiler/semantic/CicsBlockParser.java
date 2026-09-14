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
    private static final Pattern ASSIGN_ABCODE_BLOCK = Pattern.compile(
            "(?is)^\\s*EXEC\\s+CICS\\s+ASSIGN\\s+ABCODE\\s*"
                    + "\\(\\s*([A-Z0-9][A-Z0-9-]*)\\s*\\)\\s*END-EXEC\\s*$");
    private static final Pattern CONDITION_OPTION = Pattern.compile(
            "(?is)\\s*([A-Z0-9][A-Z0-9-]*)(?:\\s*\\(\\s*([A-Z0-9][A-Z0-9-]*)\\s*\\))?");
    private static final Pattern QUOTED_OPTION = Pattern.compile(
            "(?is)\\b(PROGRAM|TRANSID|ABCODE)\\s*\\(\\s*(['\"])(.*?)\\2\\s*\\)");
    /** 引用符を外したあとに残るPROGRAM(データ名)。静的な名前はQUOTED_OPTIONが先に取る。 */
    private static final Pattern PROGRAM_DATA_OPTION = Pattern.compile(
            "(?is)\\bPROGRAM\\s*\\(\\s*([A-Z0-9][A-Z0-9-]*)\\s*\\)");
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
        Matcher assignAbcode = ASSIGN_ABCODE_BLOCK.matcher(source);
        if (assignAbcode.matches()) {
            return new Parsed(null, null, null, -1, null, null,
                    false, false, false, false, false, null, List.of(), null, null, null,
                    assignAbcode.group(1).toUpperCase(Locale.ROOT), null);
        }
        Matcher handleStack = HANDLE_STACK_BLOCK.matcher(source);
        if (handleStack.matches()) {
            Statement.CicsHandleStackAction action = Statement.CicsHandleStackAction.valueOf(
                    handleStack.group(1).toUpperCase(Locale.ROOT));
            return new Parsed(null, null, null, -1, null, null,
                    false, false, false, false, false, null, List.of(), action, null, null, null, null);
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
                    false, false, false, false, false, null, List.of(), null, action, target, null, null);
        }
        Matcher condition = CONDITION_BLOCK.matcher(source);
        if (condition.matches()) {
            Statement.CicsConditionAction action = Statement.CicsConditionAction.valueOf(
                    condition.group(1).toUpperCase(Locale.ROOT));
            List<ConditionSpec> conditions = parseConditions(action, condition.group(2));
            return new Parsed(null, null, null, -1, null, null,
                    false, false, false, false, false, action, conditions, null, null, null, null, null);
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
        boolean rollback = Boolean.TRUE.equals(rollbackOption.value);
        boolean cancel = Boolean.TRUE.equals(cancelOption.value);
        boolean noDump = Boolean.TRUE.equals(noDumpOption.value);
        boolean noHandle = Boolean.TRUE.equals(noHandleOption.value);
        boolean immediate = Boolean.TRUE.equals(immediateOption.value);
        if (!remainder.isBlank()) {
            throw new IllegalArgumentException(
                    "unsupported EXEC CICS option: " + remainder.strip());
        }
        validate(operation, program != null || programData.value != null, transId, abendCode,
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
                null, List.of(), null, null, null, null, programData.value);
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
        if (commarea != null && length == null) {
            throw new IllegalArgumentException("initial EXEC CICS support requires numeric LENGTH");
        }
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
            String assignAbcodeTarget,
            String programData) {
        Parsed {
            conditions = List.copyOf(conditions);
        }
    }

    record ConditionSpec(String name, String target) {
    }

    private record ParsedOption<T>(T value, String remainder) {
    }
}
