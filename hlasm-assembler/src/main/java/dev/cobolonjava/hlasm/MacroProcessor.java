package dev.cobolonjava.hlasm;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ソース内マクロと条件付きアセンブリを、所在カウンタを動かす前に展開する。
 *
 * <p>未知の変数や未対応の式は断る。誤った展開を組み立てると、機械語も実行結果も
 * 一見正しく見えてしまうためである。
 */
final class MacroProcessor {

    private static final int MAX_STEPS = 100_000;
    private static final int MAX_DEPTH = 64;
    private static final Pattern VARIABLE = Pattern.compile("&[A-Z_@$#][A-Z0-9_@$#]*");
    private static final Pattern ORDINARY_NAME = Pattern.compile("[A-Z_@$#][A-Z0-9_@$#]*");
    private static final Pattern SET_REFERENCE = Pattern.compile(
            "^(&[A-Z_@$#][A-Z0-9_@$#]*)(?:\\((.+)\\))?$");
    private static final Pattern SEQUENCE = Pattern.compile("\\.[A-Z@$#][A-Z0-9@$#]*");

    private final Map<String, Macro> macros = new LinkedHashMap<>();
    private final Set<String> libraryMacros = new HashSet<>();
    private final Map<String, String> globals = new HashMap<>();
    private final Map<String, Character> globalKinds = new HashMap<>();
    private final Map<String, String> systemGlobals = new HashMap<>();
    private final Map<String, SetArray> globalArrays = new HashMap<>();
    private final Map<String, Character> knownTypes = new HashMap<>();
    private final Map<String, Integer> knownLengths = new HashMap<>();
    private final Map<String, Integer> knownScales = new HashMap<>();
    private final Map<String, Integer> knownIntegers = new HashMap<>();
    private final Map<String, String> knownAssemblerTypes = new HashMap<>();
    private final Map<String, String> knownProgramTypes = new HashMap<>();
    private final Set<String> definedNames = new HashSet<>();
    private final Map<String, List<ForwardAttribute>> forwardAttributes = new HashMap<>();
    private final List<Statement> output = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final CodePage codePage;
    private final String fileName;
    private final SourceLibrary library;
    private int steps;
    private int macroCalls;
    private int lookaheadStart;
    private String currentSectionName = "";
    private String currentSectionType = "";
    private boolean ended;

    private record Macro(String name, String nameParameter, List<String> positional,
                         Map<String, String> keywords, List<Statement> body) {
    }

    /** IBM の SET 配列は宣言時の次元を越えても使えるため、値は疎に持つ。 */
    private static final class SetArray {
        private final char kind;
        private final String initial;
        private final Map<Integer, String> values = new HashMap<>();
        private int highestAssigned;

        SetArray(char kind) {
            this.kind = kind;
            this.initial = kind == 'C' ? "" : "0";
        }

        String get(int index) {
            return values.getOrDefault(index, initial);
        }

        void put(int index, String value) {
            values.put(index, value);
            highestAssigned = Math.max(highestAssigned, index);
        }
    }

    private record SetReference(String name, String subscript) {
    }

    private record IndexedValue(String value, int end) {
    }

    private record ForwardAttribute(int index, char type, int length, int scale,
                                    int integer) {
        ForwardAttribute(int index, char type, int length) {
            this(index, type, length, -1, -1);
        }
    }

    record Expansion(List<Statement> statements, List<Diagnostic> diagnostics) {
        Expansion {
            statements = List.copyOf(statements);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private MacroProcessor(CodePage codePage, String fileName, SourceLibrary library) {
        this.codePage = codePage;
        this.fileName = fileName;
        this.library = library;
        initializeSystemGlobals();
    }

    private void initializeSystemGlobals() {
        LocalDateTime started = LocalDateTime.now();
        systemGlobals.put("&SYSDATC", started.format(DateTimeFormatter.BASIC_ISO_DATE));
        systemGlobals.put("&SYSDATE", started.format(DateTimeFormatter.ofPattern("MM/dd/yy")));
        systemGlobals.put("&SYSTIME", started.format(DateTimeFormatter.ofPattern("HH.mm")));
        systemGlobals.put("&SYSASM", "COBOL-ON-JAVA");
        systemGlobals.put("&SYSVER", "COBOL-ON-JAVA");
        systemGlobals.put("&SYSTEM_ID", "JAVA");
        systemGlobals.put("&SYSJOB", "(NOJOB)");
        systemGlobals.put("&SYSSTEP", "(NOSTEP)");
        systemGlobals.put("&SYSPARM", "");
        systemGlobals.put("&SYSM_HSEV", "0");
        systemGlobals.put("&SYSM_SEV", "0");
        systemGlobals.put("&SYSSTMT", "1");
        systemGlobals.put("&SYS_HLASM_DATE", "00000000");
        systemGlobals.put("&SYS_HLASM_PTF", "");
        systemGlobals.put("&SYS_HLASM_RPM", "");
        systemGlobals.put("&SYSCODEPAGE", codePage.name());
        for (String option : List.of("DBCS", "RENT", "XOBJECT")) {
            systemGlobals.put("&SYSOPT_" + option, "0");
        }
        String ebcdicCcsid = codePage.name().replaceAll("[^0-9]", "");
        ebcdicCcsid = ebcdicCcsid.isEmpty() ? "00000"
                : String.format(Locale.ROOT, "%05d", Integer.parseInt(ebcdicCcsid));
        systemGlobals.put("&SYSOPT_ASCII", "00819");
        systemGlobals.put("&SYSOPT_CA", "00819");
        systemGlobals.put("&SYSOPT_CE", ebcdicCcsid);
        systemGlobals.put("&SYSOPT_CODEPAGE", ebcdicCcsid);
        systemGlobals.put("&SYSOPT_EBCDIC", ebcdicCcsid);
        systemGlobals.put("&SYSOPT_CU", "01200");
        systemGlobals.put("&SYSOPT_UNICODE", "01200");
        systemGlobals.put("&SYSOPT_OPTABLE", "ESA");
        systemGlobals.put("&SYSOPT_CURR_OPTABLE", "ESA");
        for (String name : List.copyOf(systemGlobals.keySet())) {
            String override = System.getProperty("hlasm.sysvar." + name.substring(1));
            if (override != null) {
                systemGlobals.put(name, override);
            }
        }
    }

    private void initializeLocalSystemValues(Map<String, String> values) {
        values.put("&SYSIN_DSN", fileName);
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        values.put("&SYSIN_MEMBER", fileName.substring(slash + 1));
        values.put("&SYSIN_VOLUME", "");
        for (String prefix : List.of("SYSADATA", "SYSLIB", "SYSLIN", "SYSPRINT",
                "SYSPUNCH", "SYSTERM")) {
            values.put("&" + prefix + "_DSN", "");
            values.put("&" + prefix + "_MEMBER", "");
            values.put("&" + prefix + "_VOLUME", "");
        }
        for (String key : List.copyOf(values.keySet())) {
            if (!key.startsWith("&SYS")) {
                continue;
            }
            String override = System.getProperty("hlasm.sysvar." + key.substring(1));
            if (override != null) {
                values.put(key, override);
            }
        }
    }

    private String variableValue(String key, Map<String, String> locals) {
        if (locals.containsKey(key)) {
            return locals.get(key);
        }
        if (globals.containsKey(key)) {
            return globals.get(key);
        }
        return systemGlobals.get(key);
    }

    static Expansion expand(List<Statement> source, CodePage codePage, String fileName) {
        return expand(source, codePage, fileName, SourceLibrary.empty());
    }

    static Expansion expand(List<Statement> source, CodePage codePage, String fileName,
                            SourceLibrary library) {
        MacroProcessor processor = new MacroProcessor(codePage, fileName, library);
        processor.indexForwardAttributes(source);
        processor.process(source, new HashMap<>(), new HashMap<>(), 0, false);
        return new Expansion(processor.output, processor.diagnostics);
    }

    private void process(List<Statement> statements, Map<String, String> locals,
                         Map<String, SetArray> localArrays, int depth, boolean inMacro) {
        if (depth > MAX_DEPTH) {
            throw new AssemblyException(statements.isEmpty() ? 1 : statements.get(0).line(),
                    "macro nesting exceeds " + MAX_DEPTH);
        }
        Map<String, Integer> sequences = new HashMap<>();
        // IBM HLASM の条件付き分岐カウンタは開放コードと各マクロ呼出しに局所的である。
        int branchBudget = 4096;
        for (int k = 0; k < statements.size(); k++) {
            Statement statement = statements.get(k);
            if (statement.operation().equals("MACRO")) {
                k = matchingMend(statements, k);
                continue;
            }
            String label = statement.label();
            if (label != null && SEQUENCE.matcher(label).matches()
                    && sequences.putIfAbsent(label, k) != null) {
                throw new AssemblyException(statements.get(k).line(),
                        "sequence symbol defined twice: " + label);
            }
        }
        for (int pc = 0; pc < statements.size() && !ended; pc++) {
            Statement original = statements.get(pc);
            systemGlobals.put("&SYSSTMT", Integer.toString(steps + 1));
            if (depth == 0) {
                lookaheadStart = pc + 1;
            }
            if (++steps > MAX_STEPS) {
                throw new AssemblyException(original.line(),
                        "conditional assembly or macro expansion exceeds " + MAX_STEPS
                                + " statements");
            }
            String operation = original.operation();
            if ("MACRO".equals(operation)) {
                int end = matchingMend(statements, pc);
                defineMacro(statements.subList(pc + 1, end), original.line());
                pc = end;
                continue;
            }
            if ("MEND".equals(operation)) {
                throw new AssemblyException(original.line(), "MEND without MACRO");
            }
            if ("MEXIT".equals(operation)) {
                if (!inMacro) {
                    throw new AssemblyException(original.line(), "MEXIT outside a macro");
                }
                return;
            }
            if (operation.equals("LCLA") || operation.equals("LCLB")
                    || operation.equals("LCLC") || operation.equals("GBLA")
                    || operation.equals("GBLB") || operation.equals("GBLC")) {
                Map<String, String> target = operation.startsWith("GBL") ? globals : locals;
                Map<String, SetArray> arrays = operation.startsWith("GBL")
                        ? globalArrays : localArrays;
                for (String name : original.operandList()) {
                    SetReference reference = setReference(name, original.line());
                    String key = reference.name();
                    if (key.startsWith("&SYS")) {
                        throw new AssemblyException(original.line(),
                                "system variable is read-only: " + key);
                    }
                    if (locals.containsKey("@PARAM:" + key)) {
                        throw new AssemblyException(original.line(),
                                "macro parameter is read-only: " + key);
                    }
                    if (operation.startsWith("GBL")
                            ? locals.containsKey(key) || localArrays.containsKey(key)
                            : globals.containsKey(key) || globalArrays.containsKey(key)) {
                        throw new AssemblyException(original.line(),
                                "SET symbol cannot be both local and global: " + key);
                    }
                    if (reference.subscript() == null) {
                        if (arrays.containsKey(key)) {
                            throw new AssemblyException(original.line(),
                                    "SET symbol is already an array: " + key);
                        }
                        target.putIfAbsent(key, operation.endsWith("C") ? "" : "0");
                        if (operation.startsWith("GBL")) {
                            globalKinds.put(key, operation.charAt(3));
                        } else {
                            locals.put("@KIND:" + key, Character.toString(operation.charAt(3)));
                        }
                    } else {
                        if (!reference.subscript().matches("[0-9]+")
                                || !positiveDimension(reference.subscript())) {
                            throw new AssemblyException(original.line(),
                                    "SET array dimension must be positive: " + name);
                        }
                        if (target.containsKey(key)) {
                            throw new AssemblyException(original.line(),
                                    "SET symbol is already a scalar: " + key);
                        }
                        SetArray previous = arrays.putIfAbsent(key,
                                new SetArray(operation.charAt(3)));
                        if (previous != null && previous.kind != operation.charAt(3)) {
                            throw new AssemblyException(original.line(),
                                    "SET array type does not match declaration: " + key);
                        }
                    }
                }
                continue;
            }
            if (operation.equals("SETA") || operation.equals("SETB")
                    || operation.equals("SETC")) {
                SetReference reference = setReference(original.label(), original.line());
                String key = reference.name();
                if (key.startsWith("&SYS")) {
                    throw new AssemblyException(original.line(),
                            "system variable is read-only: " + key);
                }
                if (locals.containsKey("@PARAM:" + key)) {
                    throw new AssemblyException(original.line(),
                            "macro parameter is read-only: " + key);
                }
                if (locals.containsKey(key) && !inMacro && globals.containsKey(key)) {
                    throw new AssemblyException(original.line(), "ambiguous SET symbol: " + key);
                }
                String value = operation.equals("SETC")
                        ? substituteSetc(original.operands(), locals, localArrays, original.line())
                        : substitute(original.operands(), locals, localArrays, original.line());
                if (operation.equals("SETA")) {
                    value = Integer.toString(arithmetic(value, original.line()));
                } else if (operation.equals("SETB")) {
                    value = condition(value, original.line()) ? "1" : "0";
                } else {
                    value = characterExpression(value, original.line());
                }
                if (reference.subscript() == null) {
                    if (localArrays.containsKey(key) || globalArrays.containsKey(key)) {
                        throw new AssemblyException(original.line(),
                                "SET array requires a subscript: " + key);
                    }
                    (globals.containsKey(key) && !locals.containsKey(key) ? globals : locals)
                            .put(key, value);
                    if (globals.containsKey(key) && !locals.containsKey(key)) {
                        globalKinds.put(key, operation.charAt(3));
                    } else {
                        locals.put("@KIND:" + key, Character.toString(operation.charAt(3)));
                    }
                } else {
                    if (locals.containsKey(key) || globals.containsKey(key)) {
                        throw new AssemblyException(original.line(),
                                "SET scalar cannot be subscripted: " + key);
                    }
                    SetArray array = localArrays.containsKey(key) ? localArrays.get(key)
                            : globalArrays.get(key);
                    if (array == null) {
                        array = new SetArray(operation.charAt(3));
                        localArrays.put(key, array);
                    }
                    if (array.kind != operation.charAt(3)) {
                        throw new AssemblyException(original.line(),
                                "SET array type does not match " + operation + ": " + key);
                    }
                    int index = subscript(reference.subscript(), locals, localArrays,
                            original.line());
                    array.put(index, value);
                }
                continue;
            }
            if (operation.equals("ACTR")) {
                branchBudget = arithmetic(substitute(original.operands(), locals, localArrays,
                        original.line()), original.line());
                if (branchBudget < 0) {
                    throw new AssemblyException(original.line(), "ACTR requires a nonnegative value");
                }
                continue;
            }
            if (operation.equals("AIF")) {
                String operand = substitute(original.operands(), locals, localArrays,
                        original.line()).trim();
                int close = closingParen(operand, original.line());
                String target = operand.substring(close + 1).trim().toUpperCase(Locale.ROOT);
                if (condition(operand.substring(1, close), original.line())) {
                    branchBudget = consumeBranch(branchBudget, original.line());
                    pc = sequenceIndex(sequences, target, original.line()) - 1;
                }
                continue;
            }
            if (operation.equals("AGO")) {
                String target = substitute(original.operands(), locals, localArrays, original.line())
                        .trim().toUpperCase(Locale.ROOT);
                branchBudget = consumeBranch(branchBudget, original.line());
                pc = sequenceIndex(sequences, target, original.line()) - 1;
                continue;
            }
            if (operation.equals("ANOP")) {
                continue;
            }
            if (operation.equals("MNOTE")) {
                mnote(original, locals, localArrays);
                continue;
            }

            Statement statement = model(original, locals, localArrays, inMacro);
            Macro macro = macros.get(statement.operation());
            if (macro == null && Instructions.find(statement.operation()) == null
                    && !CopyExpander.isDirective(statement.operation())) {
                macro = loadLibraryMacro(statement.operation(), statement.line());
            }
            if (macro != null) {
                Map<String, String> arguments = bind(macro, statement);
                arguments.put("&SYSNDX", String.format(Locale.ROOT, "%04d", ++macroCalls));
                arguments.put("&SYSNEST", Integer.toString(depth + 1));
                arguments.put("&SYSMAC", macro.name());
                arguments.put("@SYSMAC:0", macro.name());
                for (int ancestor = 0; ancestor < depth; ancestor++) {
                    arguments.put("@SYSMAC:" + (ancestor + 1),
                            locals.getOrDefault("@SYSMAC:" + ancestor, "OPEN CODE"));
                }
                arguments.put("@SYSMAC:" + (depth + 1), "OPEN CODE");
                arguments.put("&SYSECT", currentSectionName);
                arguments.put("&SYSLOC", currentSectionName);
                arguments.put("&SYSSTYP", currentSectionType);
                arguments.put("&SYSCLOCK", LocalDateTime.now(ZoneOffset.UTC).format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")));
                arguments.put("&SYSSEQF", depth == 0 ? statement.identification()
                        : locals.getOrDefault("&SYSSEQF", ""));
                initializeLocalSystemValues(arguments);
                if (libraryMacros.contains(macro.name())
                        && System.getProperty("hlasm.sysvar.SYSLIB_MEMBER") == null) {
                    arguments.put("&SYSLIB_MEMBER", macro.name());
                }
                int savedLookaheadStart = lookaheadStart;
                int firstDiagnostic = diagnostics.size();
                systemGlobals.put("&SYSM_SEV", "0");
                process(macro.body(), arguments, new HashMap<>(), depth + 1, true);
                int macroSeverity = 0;
                for (Diagnostic diagnostic : diagnostics.subList(firstDiagnostic,
                        diagnostics.size())) {
                    Matcher mnote = Pattern.compile("^MNOTE ([0-9]+):").matcher(
                            diagnostic.message());
                    if (mnote.find()) {
                        macroSeverity = Math.max(macroSeverity,
                                Integer.parseInt(mnote.group(1)));
                    }
                }
                systemGlobals.put("&SYSM_SEV", Integer.toString(macroSeverity));
                lookaheadStart = savedLookaheadStart;
            } else {
                rememberType(statement);
                if (Set.of("START", "CSECT", "DSECT", "RSECT", "COM")
                        .contains(statement.operation())) {
                    currentSectionName = statement.label() == null ? "" : statement.label();
                    currentSectionType = statement.operation();
                }
                output.add(statement);
                if (statement.operation().equals("END")) {
                    ended = true;
                }
            }
        }
    }

    private Macro loadLibraryMacro(String name, int line) {
        if (!name.matches("[A-Z@$#][A-Z0-9@$#]{0,7}")) {
            return null;
        }
        String member;
        try {
            member = library.find(name);
        } catch (RuntimeException failure) {
            throw new AssemblyException(line,
                    "cannot read macro member " + name + ": " + failure.getMessage());
        }
        if (member == null) {
            return null;
        }
        List<Statement> definition;
        try {
            definition = HlasmReader.read(CopyExpander.expandMemberCopies(member, library));
        } catch (AssemblyException failure) {
            throw new AssemblyException(line, "macro member " + name + " line "
                    + failure.line() + ": " + failure.getMessage());
        }
        if (definition.size() < 3 || !definition.get(0).operation().equals("MACRO")
                || !definition.get(1).operation().equals(name)
                || !definition.get(definition.size() - 1).operation().equals("MEND")) {
            throw new AssemblyException(line,
                    "library member " + name + " must define macro " + name);
        }
        defineMacro(definition.subList(1, definition.size() - 1), line);
        libraryMacros.add(name);
        return macros.get(name);
    }

    private static int matchingMend(List<Statement> statements, int start) {
        int nesting = 1;
        for (int k = start + 1; k < statements.size(); k++) {
            String operation = statements.get(k).operation();
            if (operation.equals("MACRO")) {
                nesting++;
            } else if (operation.equals("MEND") && --nesting == 0) {
                return k;
            }
        }
        throw new AssemblyException(statements.get(start).line(), "MACRO has no MEND");
    }

    private void defineMacro(List<Statement> definition, int line) {
        if (definition.isEmpty()) {
            throw new AssemblyException(line, "MACRO requires a prototype");
        }
        Statement prototype = definition.get(0);
        String nameParameter = prototype.label();
        if (nameParameter != null) {
            variableName(nameParameter, prototype.line());
            if (nameParameter.startsWith("&SYS")) {
                throw new AssemblyException(prototype.line(),
                        "system variable cannot be a macro parameter: " + nameParameter);
            }
        }
        List<String> positional = new ArrayList<>();
        Map<String, String> keywords = new LinkedHashMap<>();
        boolean seenKeyword = false;
        for (String operand : prototype.operandList()) {
            int equals = topLevelEquals(operand);
            String name = (equals < 0 ? operand : operand.substring(0, equals))
                    .trim().toUpperCase(Locale.ROOT);
            variableName(name, prototype.line());
            if (name.startsWith("&SYS")) {
                throw new AssemblyException(prototype.line(),
                        "system variable cannot be a macro parameter: " + name);
            }
            if (name.equals(nameParameter) || positional.contains(name)
                    || keywords.containsKey(name)) {
                throw new AssemblyException(prototype.line(),
                        "macro parameter defined twice: " + name);
            }
            if (equals < 0) {
                if (seenKeyword) {
                    throw new AssemblyException(prototype.line(),
                            "positional parameter after keyword parameter: " + name);
                }
                positional.add(name);
            } else {
                seenKeyword = true;
                keywords.put(name, operand.substring(equals + 1));
            }
        }
        macros.put(prototype.operation(), new Macro(prototype.operation(), nameParameter,
                List.copyOf(positional), Map.copyOf(keywords),
                List.copyOf(definition.subList(1, definition.size()))));
    }

    private static Map<String, String> bind(Macro macro, Statement call) {
        Map<String, String> values = new HashMap<>();
        Set<String> assignedKeywords = new HashSet<>();
        values.put("@SYSLIST:0", call.label() == null || call.label().startsWith(".")
                ? "" : call.label());
        if (macro.nameParameter() != null) {
            values.put(macro.nameParameter(), call.label() == null ? "" : call.label());
            values.put("@PARAM:" + macro.nameParameter(), "1");
        }
        values.putAll(macro.keywords());
        macro.keywords().keySet().forEach(key -> values.put("@PARAM:" + key, "1"));
        macro.positional().forEach(key -> values.put("@PARAM:" + key, "1"));
        int position = 0;
        for (String operand : call.operandList()) {
            int equals = topLevelEquals(operand);
            String key = equals < 0 ? "" : "&" + operand.substring(0, equals)
                    .trim().toUpperCase(Locale.ROOT);
            if (macro.keywords().containsKey(key)) {
                if (!assignedKeywords.add(key)) {
                    throw new AssemblyException(call.line(),
                            "keyword argument specified twice: " + key);
                }
                values.put(key, operand.substring(equals + 1));
            } else if (equals >= 0) {
                throw new AssemblyException(call.line(),
                        "unknown keyword argument for macro " + macro.name() + ": " + key);
            } else {
                values.put("@SYSLIST:" + (position + 1), operand);
                if (position < macro.positional().size()) {
                    values.put(macro.positional().get(position), operand);
                }
                position++;
            }
        }
        values.put("@SYSLIST_COUNT", Integer.toString(position));
        for (int remaining = position; remaining < macro.positional().size(); remaining++) {
            values.put(macro.positional().get(remaining), "");
        }
        return values;
    }

    private Statement model(Statement source, Map<String, String> locals,
                            Map<String, SetArray> localArrays, boolean inMacro) {
        if (!inMacro && locals.isEmpty() && globals.isEmpty()
                && localArrays.isEmpty() && globalArrays.isEmpty()
                && !source.operands().toUpperCase(Locale.ROOT).contains("&SYS")
                && !source.operation().toUpperCase(Locale.ROOT).contains("&SYS")
                && (source.label() == null
                || !source.label().toUpperCase(Locale.ROOT).contains("&SYS"))) {
            return source;
        }
        String label = source.label() == null ? null
                : substitute(source.label(), locals, localArrays, source.line(), inMacro);
        if (label != null && (label.isEmpty() || label.startsWith("."))) {
            label = null;
        }
        return new Statement(label,
                substitute(source.operation(), locals, localArrays, source.line(), inMacro)
                        .toUpperCase(Locale.ROOT),
                substitute(source.operands(), locals, localArrays, source.line(), inMacro),
                source.line(), source.identification());
    }

    private String substitute(String input, Map<String, String> locals,
                              Map<String, SetArray> localArrays, int line) {
        return substitute(input, locals, localArrays, line, true, true, false);
    }

    private String substituteSetc(String input, Map<String, String> locals,
                                  Map<String, SetArray> localArrays, int line) {
        return substitute(input, locals, localArrays, line, true, true, true);
    }

    private String substitute(String input, Map<String, String> locals,
                              Map<String, SetArray> localArrays, int line,
                              boolean collapseAmpersands) {
        return substitute(input, locals, localArrays, line, collapseAmpersands, false, false);
    }

    private String substitute(String input, Map<String, String> locals,
                              Map<String, SetArray> localArrays, int line,
                              boolean collapseAmpersands, boolean resolveType,
                              boolean unsignedQuotedArithmetic) {
        StringBuilder out = new StringBuilder();
        boolean quoted = false;
        for (int k = 0; k < input.length();) {
            if (input.charAt(k) == '\'' && Quotes.isDelimiter(input, k, quoted)) {
                quoted = !quoted;
                out.append(input.charAt(k++));
                continue;
            }
            if (!quoted && resolveType && k + 2 < input.length()
                    && input.charAt(k) == 'T' && input.charAt(k + 1) == '\'') {
                String remainder = input.substring(k + 2).toUpperCase(Locale.ROOT);
                Matcher variable = VARIABLE.matcher(remainder);
                if (variable.lookingAt()) {
                    String key = variable.group();
                    String value = variableValue(key, locals);
                    int end = k + 2 + variable.end();
                    if (key.equals("&SYSLIST")) {
                        IndexedValue selected = syslist(input, end, locals, localArrays, line);
                        value = selected.value();
                        end = selected.end();
                    } else if (value == null) {
                        throw new AssemblyException(line, "undefined macro variable: " + key);
                    } else if (locals.containsKey("@PARAM:" + key)
                            && end < input.length() && input.charAt(end) == '(') {
                        int close = closingSubscript(input, end, line);
                        value = sublistEntry(value, input.substring(end + 1, close),
                                locals, localArrays, line);
                        end = close + 1;
                    }
                    out.append(key.startsWith("&SYS") && !key.equals("&SYSLIST")
                            ? systemType(key, value) : typeOf(value, line));
                    k = end;
                    continue;
                }
                Matcher symbol = ORDINARY_NAME.matcher(remainder);
                if (symbol.lookingAt()) {
                    out.append(typeOf(symbol.group(), line));
                    k += 2 + qualifiedEnd(remainder, symbol.end());
                    continue;
                }
            }
            if (!quoted && resolveType && k + 2 < input.length()
                    && "DILOS".indexOf(input.charAt(k)) >= 0
                    && input.charAt(k + 1) == '\'') {
                char attribute = input.charAt(k);
                String remainder = input.substring(k + 2).toUpperCase(Locale.ROOT);
                Matcher variable = VARIABLE.matcher(remainder);
                String value;
                int end;
                if (variable.lookingAt()) {
                    String key = variable.group();
                    end = k + 2 + variable.end();
                    if (key.equals("&SYSLIST")) {
                        IndexedValue selected = syslist(input, end, locals, localArrays, line);
                        value = selected.value();
                        end = selected.end();
                    } else {
                        value = variableValue(key, locals);
                        if (value == null) {
                            throw new AssemblyException(line, "undefined macro variable: " + key);
                        }
                        if (locals.containsKey("@PARAM:" + key)
                                && end < input.length() && input.charAt(end) == '(') {
                            int close = closingSubscript(input, end, line);
                            value = sublistEntry(value, input.substring(end + 1, close),
                                    locals, localArrays, line);
                            end = close + 1;
                        }
                    }
                } else {
                    Matcher symbol = ORDINARY_NAME.matcher(remainder);
                    if (!symbol.lookingAt()) {
                        throw new AssemblyException(line,
                                "attribute requires a symbol: " + input.substring(k));
                    }
                    value = symbol.group();
                    end = k + 2 + qualifiedEnd(remainder, symbol.end());
                }
                String name = attributeName(value);
                switch (attribute) {
                    case 'D' -> {
                        if (!ORDINARY_NAME.matcher(name).matches() && !name.startsWith("=")) {
                            throw new AssemblyException(line,
                                    "D' requires an ordinary symbol: " + value);
                        }
                        out.append(definedNames.contains(name) || knownTypes.containsKey(name)
                                ? 1 : 0);
                    }
                    case 'L' -> out.append(lengthOf(name, line));
                    case 'S' -> out.append(scaleOf(name, line));
                    case 'I' -> out.append(integerOf(name, line));
                    case 'O' -> out.append(operationType(name, line));
                    default -> throw new AssertionError();
                }
                k = end;
                continue;
            }
            if (!quoted && k + 2 < input.length()
                    && (input.charAt(k) == 'K' || input.charAt(k) == 'N')
                    && input.charAt(k + 1) == '\'' && input.charAt(k + 2) == '&') {
                Matcher attribute = VARIABLE.matcher(input.substring(k + 2)
                        .toUpperCase(Locale.ROOT));
                if (attribute.lookingAt()) {
                    String key = attribute.group();
                    SetArray array = localArrays.containsKey(key) ? localArrays.get(key)
                            : globalArrays.get(key);
                    String value = variableValue(key, locals);
                    if (array != null && input.charAt(k) == 'N') {
                        out.append(array.highestAssigned);
                        k += 2 + attribute.end();
                        continue;
                    }
                    int end = k + 2 + attribute.end();
                    if (key.equals("&SYSLIST")) {
                        if (input.charAt(k) == 'N' && (end >= input.length()
                                || input.charAt(end) != '(')) {
                            out.append(syslistCount(locals, line));
                            k = end;
                            continue;
                        }
                        IndexedValue selected = syslist(input, end, locals, localArrays, line);
                        value = selected.value();
                        end = selected.end();
                    } else if (value == null) {
                        throw new AssemblyException(line, "undefined macro variable: " + key);
                    }
                    if (input.charAt(k) == 'N' && key.startsWith("&SYS")
                            && !key.equals("&SYSLIST")) {
                        out.append(0);
                        k = end;
                        continue;
                    }
                    if (input.charAt(k) == 'N' && !key.equals("&SYSLIST")
                            && !locals.containsKey("@PARAM:" + key)) {
                        throw new AssemblyException(line,
                                "N' requires a macro parameter sublist: " + key);
                    }
                    if (locals.containsKey("@PARAM:" + key)
                            && end < input.length() && input.charAt(end) == '(') {
                        int close = closingSubscript(input, end, line);
                        value = sublistEntry(value, input.substring(end + 1, close),
                                locals, localArrays, line);
                        end = close + 1;
                    }
                    out.append(input.charAt(k) == 'K' ? value.length()
                            : sublistSize(value, line));
                    k = end;
                    continue;
                }
            }
            if (input.charAt(k) != '&') {
                out.append(input.charAt(k++));
                continue;
            }
            if (k + 1 < input.length() && input.charAt(k + 1) == '&') {
                out.append(collapseAmpersands ? "&" : "&&");
                k += 2;
                continue;
            }
            Matcher matcher = VARIABLE.matcher(input.substring(k).toUpperCase(Locale.ROOT));
            if (!matcher.lookingAt()) {
                throw new AssemblyException(line, "invalid variable symbol in: " + input);
            }
            String key = matcher.group();
            k += matcher.end();
            if (key.equals("&SYSLIST")) {
                IndexedValue selected = syslist(input, k, locals, localArrays, line);
                out.append(selected.value());
                k = selected.end();
                if (k < input.length() && input.charAt(k) == '.') {
                    k++;
                }
                continue;
            }
            SetArray array = localArrays.containsKey(key) ? localArrays.get(key)
                    : globalArrays.get(key);
            if (array != null) {
                if (k >= input.length() || input.charAt(k) != '(') {
                    throw new AssemblyException(line, "SET array requires a subscript: " + key);
                }
                int end = closingSubscript(input, k, line);
                int index = subscript(input.substring(k + 1, end), locals, localArrays, line);
                String selected = array.get(index);
                out.append(unsignedQuotedArithmetic && quoted && array.kind == 'A'
                        && selected.startsWith("-") ? selected.substring(1) : selected);
                k = end + 1;
                if (k < input.length() && input.charAt(k) == '.') {
                    k++;
                }
                continue;
            }
            String value = variableValue(key, locals);
            if (value == null) {
                throw new AssemblyException(line, "undefined macro variable: " + key);
            }
            if (key.equals("&SYSMAC") && k < input.length() && input.charAt(k) == '(') {
                int end = closingSubscript(input, k, line);
                int ancestor = arithmetic(substitute(input.substring(k + 1, end), locals,
                        localArrays, line), line);
                int nesting = Integer.parseInt(locals.get("&SYSNEST"));
                if (ancestor < 0 || ancestor > nesting) {
                    throw new AssemblyException(line, "&SYSMAC subscript exceeds nesting level");
                }
                value = locals.get("@SYSMAC:" + ancestor);
                k = end + 1;
            }
            if (locals.containsKey("@PARAM:" + key)
                    && k < input.length() && input.charAt(k) == '(') {
                int end = closingSubscript(input, k, line);
                value = sublistEntry(value, input.substring(k + 1, end),
                        locals, localArrays, line);
                k = end + 1;
            }
            char kind = locals.containsKey("@KIND:" + key)
                    ? locals.get("@KIND:" + key).charAt(0)
                    : globalKinds.getOrDefault(key, '?');
            out.append(unsignedQuotedArithmetic && quoted && kind == 'A'
                    && value.startsWith("-") ? value.substring(1) : value);
            // 単一の点は連結記号。2 つの点なら後者を実際の点として残す。
            if (k < input.length() && input.charAt(k) == '.') {
                k++;
            }
        }
        return out.toString();
    }

    private void rememberType(Statement statement) {
        if (Instructions.find(statement.operation()) != null) {
            for (String operand : statement.operandList()) {
                String literal = Assembler.literalTermOf(operand);
                if (literal != null) {
                    ForwardAttribute attribute = attributeOf(new Statement(literal, "DC",
                            literal.substring(1), statement.line()), -1);
                    if (attribute != null) {
                        knownTypes.put(literal, attribute.type());
                        if (attribute.length() >= 0) {
                            knownLengths.put(literal, attribute.length());
                        }
                        if (attribute.scale() >= 0) {
                            knownScales.put(literal, attribute.scale());
                            knownIntegers.put(literal, attribute.integer());
                        }
                    }
                }
            }
        }
        if (statement.label() == null || !ORDINARY_NAME.matcher(statement.label()).matches()) {
            return;
        }
        definedNames.add(statement.label());
        ForwardAttribute attribute = attributeOf(statement, -1);
        if (attribute != null) {
            knownTypes.put(statement.label(), attribute.type());
            if (attribute.length() >= 0) {
                knownLengths.put(statement.label(), attribute.length());
            }
            if (attribute.scale() >= 0) {
                knownScales.put(statement.label(), attribute.scale());
                knownIntegers.put(statement.label(), attribute.integer());
            }
        }
        if ((statement.operation().equals("DC") || statement.operation().equals("DS"))
                && !statement.operandList().isEmpty()) {
            Matcher constant = Pattern.compile("^[0-9]*([ABCFHPXZEDLY])([HBD])?.*",
                    Pattern.CASE_INSENSITIVE).matcher(statement.operandList().get(0).trim());
            if (constant.matches()) {
                String extension = "EDL".contains(constant.group(1).toUpperCase(Locale.ROOT))
                        && constant.group(2) != null ? constant.group(2) : "";
                knownAssemblerTypes.put(statement.label(),
                        constant.group(1).toUpperCase(Locale.ROOT) + extension);
            }
        } else if (statement.operation().equals("EQU")) {
            List<String> fields = statement.operandList();
            if (fields.size() > 4 && !fields.get(4).isBlank()) {
                knownAssemblerTypes.put(statement.label(),
                        fields.get(4).trim().toUpperCase(Locale.ROOT));
            }
            if (fields.size() > 3 && fields.get(3).trim().matches("(?i)^C'.*'$")) {
                knownProgramTypes.put(statement.label(),
                        unquote(fields.get(3).trim().substring(1), statement.line()));
            }
        }
    }

    private void indexForwardAttributes(List<Statement> source) {
        for (int index = 0; index < source.size(); index++) {
            Statement statement = source.get(index);
            if (statement.operation().equals("MACRO")) {
                index = matchingMend(source, index);
                continue;
            }
            if (statement.label() == null || !ORDINARY_NAME.matcher(statement.label()).matches()
                    || statement.label().contains("&")) {
                continue;
            }
            ForwardAttribute attribute = attributeOf(statement, index);
            if (attribute != null) {
                forwardAttributes.computeIfAbsent(statement.label(), ignored -> new ArrayList<>())
                        .add(attribute);
            }
        }
    }

    /** IBM HLASM ignores an ordinary-symbol qualifier in an attribute reference. */
    private static int qualifiedEnd(String text, int nameEnd) {
        if (nameEnd < text.length() && text.charAt(nameEnd) == '.') {
            Matcher qualifier = ORDINARY_NAME.matcher(text.substring(nameEnd + 1));
            if (qualifier.lookingAt()) {
                return nameEnd + 1 + qualifier.end();
            }
        }
        return nameEnd;
    }

    private static String attributeName(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (value.startsWith("(") && closingSubscript(value, 0, 1) == value.length() - 1) {
            List<String> entries = Statement.split(value.substring(1, value.length() - 1), 1);
            return entries.isEmpty() ? "" : attributeName(entries.get(0));
        }
        Matcher name = ORDINARY_NAME.matcher(value);
        if (name.lookingAt()) {
            int end = qualifiedEnd(value, name.end());
            if (end == value.length() || "+-*/(, ".indexOf(value.charAt(end)) >= 0) {
                return name.group();
            }
        }
        return value;
    }

    private ForwardAttribute attributeOf(Statement statement, int index) {
        Instructions.Definition instruction = Instructions.find(statement.operation());
        if (instruction != null) {
            return new ForwardAttribute(index, 'I', instruction.format().length());
        }
        if (Set.of("START", "CSECT", "DSECT", "RSECT", "COM")
                .contains(statement.operation())) {
            return new ForwardAttribute(index, 'J', 1);
        }
        if (statement.operation().equals("EQU")) {
            List<String> fields = statement.operandList();
            int length = fields.size() > 1 && fields.get(1).trim().matches("[0-9]+")
                    ? Integer.parseInt(fields.get(1).trim()) : 1;
            char type = fields.size() > 2 && fields.get(2).trim().matches("(?i)^C'.'$")
                    ? Character.toUpperCase(fields.get(2).trim().charAt(2)) : 'U';
            return new ForwardAttribute(index, type, length);
        }
        if (!statement.operation().equals("DC") && !statement.operation().equals("DS")) {
            return null;
        }
        List<String> operands = statement.operandList();
        if (operands.isEmpty()) {
            return null;
        }
        String first = operands.get(0).trim().toUpperCase(Locale.ROOT);
        Matcher typed = Pattern.compile("^[0-9]*([ABCFHPXZEDLY])(?:[HBD])?"
                + "(?=L[0-9]+|['(]|$)")
                .matcher(first);
        if (!typed.find()) {
            return null;
        }
        int length = -1;
        try {
            length = Constants.parse(first, false, () -> codePage, statement.line()).length();
        } catch (AssemblyException ignored) {
            // Lookahead can establish a type even when its length depends on unresolved text.
        }
        char type = typed.group(1).charAt(0);
        boolean explicitLength = first.matches("^[0-9]*[A-Z](?:[HBD])?L[0-9]+.*");
        char attributeType = explicitLength && "FH".indexOf(type) >= 0 ? 'G'
                : explicitLength && "EDL".indexOf(type) >= 0 ? 'K'
                : explicitLength && "AY".indexOf(type) >= 0 ? 'R' : type;
        int scale = -1;
        int integer = -1;
        if (length >= 0 && "FHPZEDL".indexOf(type) >= 0) {
            scale = 0;
            if (type == 'P' || type == 'Z') {
                Matcher nominal = Pattern.compile("^[0-9]*[PZ](?:L[0-9]+)?'[^']*?\\.([0-9]+)")
                        .matcher(first);
                if (nominal.find()) {
                    scale = nominal.group(1).length();
                }
            }
            integer = switch (type) {
                case 'F', 'H' -> 8 * length - scale - 1;
                case 'P' -> 2 * length - scale - 1;
                case 'Z' -> length - scale;
                default -> length <= 8 ? 2 * (length - 1) - scale
                        : 2 * (length - 1) - scale - 2;
            };
        }
        return new ForwardAttribute(index, attributeType, length, scale, integer);
    }

    private ForwardAttribute forwardAttribute(String name) {
        List<ForwardAttribute> attributes = forwardAttributes.getOrDefault(name, List.of());
        ForwardAttribute found = null;
        for (ForwardAttribute attribute : attributes) {
            if (attribute.index() >= lookaheadStart) {
                if (found != null) {
                    return new ForwardAttribute(attribute.index(), 'U', -1);
                }
                found = attribute;
            }
        }
        return found;
    }

    private int lengthOf(String raw, int line) {
        String name = attributeName(raw);
        Integer known = knownLengths.get(name);
        if (known != null) {
            return known;
        }
        ForwardAttribute forward = forwardAttribute(name);
        if (forward != null && forward.length() >= 0) {
            return forward.length();
        }
        throw new AssemblyException(line, "length attribute is unavailable: " + raw);
    }

    private int scaleOf(String raw, int line) {
        return numericAttribute(raw, knownScales, true, line);
    }

    private int integerOf(String raw, int line) {
        return numericAttribute(raw, knownIntegers, false, line);
    }

    private int numericAttribute(String raw, Map<String, Integer> known, boolean scale,
                                 int line) {
        String name = attributeName(raw);
        Integer value = known.get(name);
        if (value != null) {
            return value;
        }
        ForwardAttribute forward = forwardAttribute(name);
        int ahead = forward == null ? -1 : scale ? forward.scale() : forward.integer();
        if (ahead >= 0) {
            return ahead;
        }
        throw new AssemblyException(line,
                (scale ? "scale" : "integer") + " attribute is unavailable: " + raw);
    }

    private char operationType(String name, int line) {
        if (macros.containsKey(name)) {
            return 'M';
        }
        Instructions.Definition instruction = Instructions.find(name);
        if (instruction != null) {
            return instruction.mask() == null ? 'O' : 'E';
        }
        if (CopyExpander.isDirective(name)) {
            return 'A';
        }
        try {
            if (library.find(name) != null) {
                return 'S';
            }
        } catch (RuntimeException failure) {
            throw new AssemblyException(line,
                    "cannot search macro member " + name + ": " + failure.getMessage());
        }
        return 'U';
    }

    private char typeOf(String raw, int line) {
        String value = attributeName(raw);
        if (value.isEmpty()) {
            return 'O';
        }
        if (value.matches("[+-]?[0-9]+") || value.matches("[BCDX]'[^']*'")) {
            return 'N';
        }
        if (value.startsWith("(") && value.endsWith(")")) {
            List<String> elements = Statement.split(value.substring(1, value.length() - 1), line);
            return elements.isEmpty() ? 'O' : typeOf(elements.get(0), line);
        }
        Character known = knownTypes.get(value);
        if (known != null) {
            return known;
        }
        ForwardAttribute forward = forwardAttribute(value);
        if (forward != null) {
            return forward.type();
        }
        if (!ORDINARY_NAME.matcher(value).matches() && !value.startsWith("=")) {
            return 'U';
        }
        return 'U';
    }

    private static char systemType(String key, String value) {
        if (Set.of("&SYS_HLASM_DATE", "&SYSDATC", "&SYSM_HSEV", "&SYSM_SEV",
                "&SYSNDX", "&SYSNEST", "&SYSOPT_DBCS", "&SYSOPT_RENT",
                "&SYSOPT_XOBJECT", "&SYSSTMT", "&SYSOPT_ASCII", "&SYSOPT_CA",
                "&SYSOPT_CE", "&SYSOPT_CODEPAGE", "&SYSOPT_CU",
                "&SYSOPT_EBCDIC", "&SYSOPT_UNICODE").contains(key)) {
            return 'N';
        }
        if (Set.of("&SYS_HLASM_PTF", "&SYS_HLASM_RPM", "&SYSASM", "&SYSDATE",
                "&SYSJOB", "&SYSSTEP", "&SYSTEM_ID", "&SYSTIME", "&SYSVER",
                "&SYSCLOCK", "&SYSCODEPAGE", "&SYSLOC", "&SYSOPT_OPTABLE",
                "&SYSOPT_CURR_OPTABLE", "&SYSIN_DSN", "&SYSPRINT_DSN",
                "&SYSPUNCH_DSN", "&SYSTERM_DSN").contains(key)) {
            return 'U';
        }
        return value.isEmpty() ? 'O' : 'U';
    }

    private static SetReference setReference(String text, int line) {
        if (text == null) {
            throw new AssemblyException(line, "a variable symbol is required: null");
        }
        Matcher matcher = SET_REFERENCE.matcher(text.trim().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new AssemblyException(line, "a variable symbol is required: " + text);
        }
        return new SetReference(matcher.group(1), matcher.group(2));
    }

    private static boolean positiveDimension(String text) {
        try {
            long value = Long.parseLong(text);
            return value > 0 && value <= Integer.MAX_VALUE;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private int subscript(String expression, Map<String, String> locals,
                          Map<String, SetArray> localArrays, int line) {
        int index = arithmetic(substitute(expression, locals, localArrays, line), line);
        if (index <= 0) {
            throw new AssemblyException(line,
                    "subscript must be positive: " + expression);
        }
        return index;
    }

    private static int closingSubscript(String text, int open, int line) {
        int depth = 0;
        for (int k = open; k < text.length(); k++) {
            if (text.charAt(k) == '(') {
                depth++;
            } else if (text.charAt(k) == ')' && --depth == 0) {
                return k;
            }
        }
        throw new AssemblyException(line, "unclosed subscript: " + text);
    }

    private static int sublistSize(String value, int line) {
        if (value.isEmpty()) {
            return 0;
        }
        if (value.startsWith("(") && value.endsWith(")")) {
            String contents = value.substring(1, value.length() - 1);
            return contents.isEmpty() ? 1 : Statement.split(contents, line).size();
        }
        return 1;
    }

    private String sublistEntry(String value, String subscripts,
                                Map<String, String> locals,
                                Map<String, SetArray> localArrays, int line) {
        List<String> indices = Statement.split(subscripts, line);
        if (indices.isEmpty()) {
            throw new AssemblyException(line, "sublist subscript is required");
        }
        for (String expression : indices) {
            int index = subscript(expression, locals, localArrays, line);
            if (value.startsWith("(") && value.endsWith(")")) {
                String contents = value.substring(1, value.length() - 1);
                List<String> entries = contents.isEmpty()
                        ? List.of("") : Statement.split(contents, line);
                value = index <= entries.size() ? entries.get(index - 1) : "";
            } else {
                value = index == 1 ? value : "";
            }
        }
        return value;
    }

    private static int syslistCount(Map<String, String> locals, int line) {
        String count = locals.get("@SYSLIST_COUNT");
        if (count == null) {
            throw new AssemblyException(line, "&SYSLIST is available only in a macro");
        }
        return Integer.parseInt(count);
    }

    private IndexedValue syslist(String input, int open,
                                 Map<String, String> locals,
                                 Map<String, SetArray> localArrays, int line) {
        int count = syslistCount(locals, line);
        if (open >= input.length() || input.charAt(open) != '(') {
            throw new AssemblyException(line, "&SYSLIST requires a subscript");
        }
        int close = closingSubscript(input, open, line);
        List<String> indices = Statement.split(input.substring(open + 1, close), line);
        if (indices.isEmpty()) {
            throw new AssemblyException(line, "&SYSLIST requires a subscript");
        }
        int first = arithmetic(substitute(indices.get(0), locals, localArrays, line), line);
        if (first < 0) {
            throw new AssemblyException(line, "&SYSLIST first subscript must be nonnegative");
        }
        String value = first <= count ? locals.getOrDefault("@SYSLIST:" + first, "") : "";
        if (indices.size() > 1) {
            value = sublistEntry(value, String.join(",", indices.subList(1, indices.size())),
                    locals, localArrays, line);
        }
        return new IndexedValue(value, close + 1);
    }

    private static int sequenceIndex(Map<String, Integer> sequences, String name, int line) {
        Integer index = sequences.get(name);
        if (index == null) {
            throw new AssemblyException(line, "undefined sequence symbol: " + name);
        }
        return index;
    }

    private static int consumeBranch(int budget, int line) {
        if (budget <= 1) {
            throw new AssemblyException(line, "ACTR branch counter exceeded");
        }
        return budget - 1;
    }

    private void mnote(Statement statement, Map<String, String> locals,
                       Map<String, SetArray> localArrays) {
        String operands = substitute(statement.operands(), locals, localArrays, statement.line());
        List<String> fields = Statement.split(operands, statement.line());
        if (fields.size() == 1) {
            mnoteMessage(fields.get(0), statement.line()); // コメント形式
            return;
        }
        if (fields.size() != 2) {
            throw new AssemblyException(statement.line(), "MNOTE requires severity,message");
        }
        String severity = fields.get(0).trim();
        if (severity.equals("*")) {
            mnoteMessage(fields.get(1), statement.line()); // コメント形式
            return;
        }
        int number = severity.isEmpty() ? 1 : arithmetic(severity, statement.line());
        if (number < 0 || number > 255) {
            throw new AssemblyException(statement.line(), "MNOTE severity must be 0..255");
        }
        systemGlobals.put("&SYSM_HSEV", Integer.toString(Math.max(number,
                Integer.parseInt(systemGlobals.get("&SYSM_HSEV")))));
        String message = mnoteMessage(fields.get(1), statement.line());
        Diagnostic.Severity category = number >= 8 ? Diagnostic.Severity.ERROR
                : number >= 4 ? Diagnostic.Severity.WARNING : Diagnostic.Severity.INFO;
        diagnostics.add(new Diagnostic(category, fileName, statement.line(),
                "MNOTE " + number + ": " + message));
    }

    private static String mnoteMessage(String operand, int line) {
        if (!operand.trim().startsWith("'")) {
            throw new AssemblyException(line, "MNOTE message must be quoted");
        }
        return unquote(operand, line);
    }

    private static int topLevelEquals(String operand) {
        if (operand.stripLeading().startsWith("=")) {
            return -1; // A literal operand is positional, not a keyword argument.
        }
        int depth = 0;
        boolean quoted = false;
        for (int k = 0; k < operand.length(); k++) {
            char c = operand.charAt(k);
            if (c == '\'' && Quotes.isDelimiter(operand, k, quoted)) {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && c == '=') {
                return k;
            }
        }
        return -1;
    }

    private static void variableName(String name, int line) {
        if (name == null || !VARIABLE.matcher(name).matches()) {
            throw new AssemblyException(line, "a variable symbol is required: " + name);
        }
    }

    private static int closingParen(String operand, int line) {
        if (!operand.startsWith("(")) {
            throw new AssemblyException(line, "AIF requires (condition).sequence");
        }
        int depth = 0;
        boolean quoted = false;
        for (int k = 0; k < operand.length(); k++) {
            char c = operand.charAt(k);
            if (c == '\'' && Quotes.isDelimiter(operand, k, quoted)) {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')' && --depth == 0) {
                return k;
            }
        }
        throw new AssemblyException(line, "AIF has an unclosed condition");
    }

    private boolean condition(String expression, int line) {
        String value = expression.trim();
        while (value.startsWith("(") && closingParen(value, line) == value.length() - 1) {
            value = value.substring(1, value.length() - 1).trim();
        }
        int xor = logicalOperator(value, "XOR");
        if (xor >= 0) {
            return condition(value.substring(0, xor), line)
                    ^ condition(value.substring(xor + 3), line);
        }
        int or = logicalOperator(value, "OR");
        if (or >= 0) {
            return condition(value.substring(0, or), line)
                    || condition(value.substring(or + 2), line);
        }
        int and = logicalOperator(value, "AND");
        if (and >= 0) {
            return condition(value.substring(0, and), line)
                    && condition(value.substring(and + 3), line);
        }
        if (value.regionMatches(true, 0, "NOT ", 0, 4)) {
            return !condition(value.substring(4), line);
        }
        int relation = -1;
        String relationName = null;
        for (String candidate : List.of("EQ", "NE", "LT", "LE", "GT", "GE")) {
            int found = logicalOperator(value, candidate);
            if (found >= 0 && (relation < 0 || found < relation)) {
                relation = found;
                relationName = candidate;
            }
        }
        if (relation >= 0) {
            String leftTerm = value.substring(0, relation).trim();
            String rightTerm = value.substring(relation + relationName.length()).trim();
            if (leftTerm.isEmpty() || rightTerm.isEmpty()) {
                throw new AssemblyException(line,
                        "incomplete conditional relation: " + expression);
            }
            if (leftTerm.matches("(?i)^[KNT]'.*")
                    || rightTerm.matches("(?i)^[KNT]'.*")) {
                throw new AssemblyException(line,
                        "unsupported conditional expression: " + expression);
            }
            int compared;
            if (arithmeticCandidate(leftTerm)) {
                compared = Integer.compare(arithmetic(leftTerm, line),
                        arithmetic(rightTerm, line));
            } else {
                compared = compareCharacters(characterExpression(leftTerm, line),
                        characterExpression(rightTerm, line));
            }
            return switch (relationName) {
                case "EQ" -> compared == 0;
                case "NE" -> compared != 0;
                case "LT" -> compared < 0;
                case "LE" -> compared <= 0;
                case "GT" -> compared > 0;
                case "GE" -> compared >= 0;
                default -> throw new AssertionError();
            };
        }
        if (arithmeticCandidate(value)) {
            return arithmetic(value, line) != 0;
        }
        throw new AssemblyException(line, "unsupported conditional expression: " + expression);
    }

    /** HLASM compares lengths first, then unsigned EBCDIC bytes for equal lengths. */
    private int compareCharacters(String left, String right) {
        byte[] first = codePage.encode(left);
        byte[] second = codePage.encode(right);
        int length = Integer.compare(first.length, second.length);
        if (length != 0) {
            return length;
        }
        for (int k = 0; k < first.length; k++) {
            int compared = Integer.compare(first[k] & 0xFF, second[k] & 0xFF);
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    private static boolean numericExpression(String value) {
        return value.matches("[0-9()+*/\\s-]+") && value.matches(".*[0-9].*");
    }

    private static boolean arithmeticCandidate(String value) {
        return !value.startsWith("'") && (numericExpression(value)
                || value.matches("(?is)^[BCX]'[^']*'.*")
                || value.matches("(?is)^(?:B2A|C2A|D2A|DCLEN|FIND|INDEX|X2A|"
                        + "ISBIN|ISDEC|ISHEX|ISSYM)\\(.*")
                || value.matches("(?is).*\\s+(?:AND|OR|XOR|SLA|SLL|SRA|SRL|FIND|INDEX)\\s+.*"));
    }

    /** 引用符と括弧の外にある論理演算子だけを分割する。 */
    private static int logicalOperator(String expression, String operator) {
        int depth = 0;
        boolean quoted = false;
        for (int k = 0; k <= expression.length() - operator.length() - 2; k++) {
            char c = expression.charAt(k);
            if (c == '\'' && Quotes.isDelimiter(expression, k, quoted)) {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && c == ' '
                    && expression.regionMatches(true, k + 1, operator, 0,
                            operator.length())
                    && expression.charAt(k + 1 + operator.length()) == ' ') {
                return k + 1;
            }
        }
        return -1;
    }

    private int arithmetic(String expression, int line) {
        return MacroArithmetic.evaluate(expression, codePage, line,
                argument -> characterExpression(argument, line));
    }

    private String characterExpression(String expression, int line) {
        String value = expression.trim();
        int join = characterJoin(value);
        if (join >= 0) {
            String combined = characterExpression(value.substring(0, join), line)
                    + characterExpression(value.substring(join + 1), line);
            if (combined.length() > 4064) {
                throw new AssemblyException(line, "SETC string exceeds 4064 characters");
            }
            return combined;
        }
        Matcher functionPrefix = Pattern.compile("(?is)^[A-Z][A-Z0-9]*\\(").matcher(value);
        if (functionPrefix.find()) {
            int end = closingSubscript(value, functionPrefix.end() - 1, line);
            if (end + 1 < value.length() && value.charAt(end + 1) == '(') {
                int close = closingSubscript(value, end + 1, line);
                if (close == value.length() - 1) {
                    return characterSubstring(characterExpression(value.substring(0, end + 1),
                            line), value.substring(end + 2, close), line);
                }
            }
        }
        Matcher logicalFunction = Pattern.compile(
                "(?is)^\\((BYTE|LOWER|UPPER|DOUBLE|SIGNED)\\s+(.+)\\)$")
                .matcher(value);
        if (logicalFunction.matches()) {
            String answer = MacroFunctions.character(logicalFunction.group(1),
                    logicalFunction.group(2), codePage, line,
                    argument -> characterExpression(argument, line),
                    argument -> arithmetic(argument, line));
            if (answer.length() > 4064) {
                throw new AssemblyException(line, "SETC string exceeds 4064 characters");
            }
            return answer;
        }
        Matcher parenthesizedRepetition = Pattern.compile("(?s)^\\(([^()]*)\\)(.+)$")
                .matcher(value);
        if (parenthesizedRepetition.matches()
                && arithmeticCandidate(parenthesizedRepetition.group(1).trim())) {
            int count = arithmetic(parenthesizedRepetition.group(1), line);
            String part = characterExpression(parenthesizedRepetition.group(2), line);
            if (count < 0 || (long) count * part.length() > 4064) {
                throw new AssemblyException(line, "SETC string exceeds 4064 characters");
            }
            return part.repeat(count);
        }
        Matcher repetition = Pattern.compile("(?is)^([0-9]+)([A-Z][A-Z0-9]*\\(.*\\))$")
                .matcher(value);
        if (repetition.matches()) {
            int count = arithmetic(repetition.group(1), line);
            String part = characterExpression(repetition.group(2), line);
            if (count < 0 || (long) count * part.length() > 4064) {
                throw new AssemblyException(line, "SETC string exceeds 4064 characters");
            }
            return part.repeat(count);
        }
        int adjacent = characterJuxtaposition(value);
        if (adjacent >= 0) {
            String combined = characterExpression(value.substring(0, adjacent), line)
                    + characterExpression(value.substring(adjacent), line);
            if (combined.length() > 4064) {
                throw new AssemblyException(line, "SETC string exceeds 4064 characters");
            }
            return combined;
        }
        Matcher function = Pattern.compile("(?is)^([A-Z][A-Z0-9]*)\\((.*)\\)$")
                .matcher(value);
        if (function.matches()) {
            List<String> arguments = Statement.split(function.group(2), line);
            if (arguments.size() != 1) {
                throw new AssemblyException(line,
                        "character function requires one argument: " + function.group(1));
            }
            if (function.group(1).equalsIgnoreCase("SYSATTRA")) {
                String symbol = characterExpression(arguments.get(0), line)
                        .toUpperCase(Locale.ROOT);
                return knownAssemblerTypes.getOrDefault(symbol, "");
            }
            if (function.group(1).equalsIgnoreCase("SYSATTRP")) {
                String symbol = characterExpression(arguments.get(0), line)
                        .toUpperCase(Locale.ROOT);
                return knownProgramTypes.getOrDefault(symbol, "");
            }
            String answer = MacroFunctions.character(function.group(1), arguments.get(0), codePage,
                    line, argument -> characterExpression(argument, line),
                    argument -> arithmetic(argument, line));
            if (answer.length() > 4064) {
                throw new AssemblyException(line, "SETC string exceeds 4064 characters");
            }
            return answer;
        }
        if (!value.startsWith("'")) {
            return unquote(value, line);
        }
        StringBuilder result = new StringBuilder();
        int at = 0;
        while (at < value.length()) {
            if (value.charAt(at) != '\'') {
                throw new AssemblyException(line, "unsupported character expression: " + expression);
            }
            StringBuilder part = new StringBuilder();
            at++;
            boolean closed = false;
            while (at < value.length()) {
                char current = value.charAt(at++);
                if (current == '\'') {
                    if (at < value.length() && value.charAt(at) == '\'') {
                        part.append('\'');
                        at++;
                    } else {
                        closed = true;
                        break;
                    }
                } else {
                    part.append(current);
                }
            }
            if (!closed) {
                throw new AssemblyException(line, "unbalanced SETC string: " + expression);
            }
            String selected = part.toString();
            if (at < value.length() && value.charAt(at) == '(') {
                int close = closingSubscript(value, at, line);
                selected = characterSubstring(selected, value.substring(at + 1, close), line);
                at = close + 1;
            }
            result.append(selected);
            if (at == value.length()) {
                break;
            }
            if (value.charAt(at++) != '.' || at == value.length()) {
                throw new AssemblyException(line, "unsupported character expression: " + expression);
            }
        }
        if (result.length() > 4064) {
            throw new AssemblyException(line, "SETC string exceeds 4064 characters");
        }
        return result.toString();
    }

    private String characterSubstring(String selected, String fieldsText, int line) {
        List<String> fields = Statement.split(fieldsText, line);
        if (fields.size() != 2) {
            throw new AssemblyException(line, "substring requires start,length");
        }
        if (selected.isEmpty() || selected.length() > 4064) {
            throw new AssemblyException(line, "substring source length must be 1..4064");
        }
        int start = arithmetic(fields.get(0), line);
        int length = fields.get(1).trim().equals("*")
                ? Math.max(0, selected.length() - start + 1)
                : arithmetic(fields.get(1), line);
        if (start <= 0 || length < 0 || length > 4064) {
            throw new AssemblyException(line, "invalid substring start or length");
        }
        return start > selected.length() ? ""
                : selected.substring(start - 1,
                (int) Math.min(selected.length(), (long) start - 1 + length));
    }

    private static int characterJuxtaposition(String value) {
        boolean quoted = false;
        int depth = 0;
        for (int at = 0; at < value.length() - 1; at++) {
            char c = value.charAt(at);
            if (c == '\'' && Quotes.isDelimiter(value, at, quoted)) {
                quoted = !quoted;
                if (!quoted && depth == 0) {
                    int next = at + 1;
                    while (next < value.length() && Character.isWhitespace(value.charAt(next))) {
                        next++;
                    }
                    if (next < value.length() && (Character.isLetter(value.charAt(next))
                            || next > at + 1 && value.charAt(next) == '\'')) {
                        return next;
                    }
                }
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')' && --depth == 0) {
                int next = at + 1;
                while (next < value.length() && Character.isWhitespace(value.charAt(next))) {
                    next++;
                }
                if (next < value.length() && (Character.isLetter(value.charAt(next))
                        || value.charAt(next) == '\'')) {
                    return next;
                }
            }
        }
        return -1;
    }

    private static int characterJoin(String value) {
        boolean quoted = false;
        int depth = 0;
        for (int at = 0; at < value.length(); at++) {
            char c = value.charAt(at);
            if (c == '\'' && Quotes.isDelimiter(value, at, quoted)) {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && c == '.') {
                return at;
            }
        }
        return -1;
    }

    private static String unquote(String expression, int line) {
        String value = expression.trim();
        if (value.startsWith("'") || value.endsWith("'")) {
            if (value.length() < 2 || !value.startsWith("'") || !value.endsWith("'")) {
                throw new AssemblyException(line, "unbalanced SETC string: " + expression);
            }
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }
}
