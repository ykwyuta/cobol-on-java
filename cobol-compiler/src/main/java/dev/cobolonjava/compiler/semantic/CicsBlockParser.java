package dev.cobolonjava.compiler.semantic;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** EXEC CICSの初期対応commandだけをfail-closedで解析する小さなisland parser。 */
final class CicsBlockParser {

    private static final Pattern BLOCK = Pattern.compile(
            "(?is)^\\s*EXEC\\s+CICS\\s+(LINK|XCTL|RETURN|SYNCPOINT|ABEND)\\b(.*?)END-EXEC\\s*$");
    private static final Pattern QUOTED_OPTION = Pattern.compile(
            "(?is)\\b(PROGRAM|TRANSID|ABCODE)\\s*\\(\\s*(['\"])(.*?)\\2\\s*\\)");
    private static final Pattern NAME_OPTION = Pattern.compile(
            "(?is)\\bCOMMAREA\\s*\\(\\s*([A-Z0-9][A-Z0-9-]*)\\s*\\)");
    private static final Pattern LENGTH_OPTION = Pattern.compile(
            "(?is)\\bLENGTH\\s*\\(\\s*(\\d+)\\s*\\)");

    private CicsBlockParser() {
    }

    static Parsed parse(String source) {
        Objects.requireNonNull(source, "source");
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

        ParsedOption<String> commarea = extractOne(NAME_OPTION, remainder, matcher -> matcher.group(1));
        remainder = commarea.remainder;
        ParsedOption<Integer> length = extractOne(
                LENGTH_OPTION, remainder, matcher -> parseLength(matcher.group(1)));
        remainder = length.remainder;

        ParsedOption<Boolean> rollbackOption = extractFlag("ROLLBACK", remainder);
        remainder = rollbackOption.remainder;
        ParsedOption<Boolean> cancelOption = extractFlag("CANCEL", remainder);
        remainder = cancelOption.remainder;
        ParsedOption<Boolean> noDumpOption = extractFlag("NODUMP", remainder);
        remainder = noDumpOption.remainder;
        boolean rollback = Boolean.TRUE.equals(rollbackOption.value);
        boolean cancel = Boolean.TRUE.equals(cancelOption.value);
        boolean noDump = Boolean.TRUE.equals(noDumpOption.value);
        if (!remainder.isBlank()) {
            throw new IllegalArgumentException(
                    "unsupported EXEC CICS option: " + remainder.strip());
        }
        validate(operation, program, transId, abendCode, commarea.value, length.value,
                rollback, cancel, noDump);
        String target = switch (operation) {
            case RETURN -> transId;
            case ABEND -> abendCode;
            default -> program;
        };
        return new Parsed(operation, target, commarea.value,
                length.value == null ? -1 : length.value, rollback, cancel, noDump);
    }

    private static void validate(
            Statement.CicsOperation operation, String program, String transId, String abendCode,
            String commarea, Integer length, boolean rollback, boolean cancel, boolean noDump) {
        if ((operation == Statement.CicsOperation.LINK
                || operation == Statement.CicsOperation.XCTL) && program == null) {
            throw new IllegalArgumentException(operation + " requires static PROGRAM('name')");
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
            boolean rollback,
            boolean cancel,
            boolean noDump) {
    }

    private record ParsedOption<T>(T value, String remainder) {
    }
}
