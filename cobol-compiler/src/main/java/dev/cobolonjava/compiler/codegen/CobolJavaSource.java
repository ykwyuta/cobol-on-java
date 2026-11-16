package dev.cobolonjava.compiler.codegen;

import dev.cobolonjava.compiler.semantic.Condition;
import dev.cobolonjava.compiler.semantic.CompileCodePage;
import dev.cobolonjava.compiler.semantic.DataCategory;
import dev.cobolonjava.compiler.semantic.DataDivisionBuilder;
import dev.cobolonjava.compiler.semantic.DataItem;
import dev.cobolonjava.compiler.semantic.DataLayout;
import dev.cobolonjava.compiler.semantic.DataReference;
import dev.cobolonjava.compiler.semantic.DataSection;
import dev.cobolonjava.compiler.semantic.Expression;
import dev.cobolonjava.compiler.semantic.InitialImage;
import dev.cobolonjava.compiler.semantic.InitializeImage;
import dev.cobolonjava.compiler.semantic.IntermediateDigits;
import dev.cobolonjava.compiler.semantic.LiteralValue;
import dev.cobolonjava.compiler.semantic.NationalLiterals;
import dev.cobolonjava.compiler.semantic.Operand;
import dev.cobolonjava.compiler.semantic.ProcedureBuilder;
import dev.cobolonjava.compiler.semantic.SpecialNames;
import dev.cobolonjava.compiler.semantic.Statement;
import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.item.Usage;
import dev.cobolonjava.runtime.picture.Picture;
import dev.cobolonjava.runtime.procedure.ProcedureManifest;
import dev.cobolonjava.runtime.program.ProgramSupport;
import dev.cobolonjava.runtime.verb.ClassTest;
import dev.cobolonjava.source.JavaSource;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** 構造化できる手続き部を、意味解析の結果から Java へ出す。 */
public final class CobolJavaSource {

    private final ProcedureBuilder.Result procedure;
    private final CobolJavaNames names;
    private final List<String> paragraphs;
    private final Map<String, Integer> fields = new LinkedHashMap<>();
    private final Map<String, String> numericFields = new LinkedHashMap<>();
    private final Map<String, String> pictureFields = new LinkedHashMap<>();
    private final Map<String, String> initializeFields = new LinkedHashMap<>();
    private final StringBuilder methods = new StringBuilder();
    private final SpecialNames specialNames;
    private final byte[] initialStorage;
    private final String trunc;
    private final int arithmeticDigits;
    private final int classSigns;
    private final boolean rangeChecks;
    private int indent;
    private int loopNumber;
    private int arithmeticNumber;
    private int stringNumber;
    private int labelNumber;
    private int paragraphIndex;
    private BreakTarget sentenceEnd;
    private BreakTarget performEnd;
    private BreakTarget performCycle;

    private static final class BreakTarget {
        private final String name;
        private boolean used;

        private BreakTarget(String name) {
            this.name = name;
        }
    }

    private CobolJavaSource(ProcedureBuilder.Result procedure, SpecialNames specialNames,
                            CompilerOptions options, byte[] initialStorage) {
        this.procedure = procedure;
        this.specialNames = specialNames;
        this.initialStorage = initialStorage;
        trunc = ProgramGenerator.truncMode(options);
        arithmeticDigits = options.intermediateDigits();
        classSigns = ProgramGenerator.classSigns(options);
        rangeChecks = options.subscriptRangeChecks();
        names = new CobolJavaNames(procedure.paragraphs());
        paragraphs = procedure.paragraphs().stream().map(ProcedureBuilder.Paragraph::name).toList();
    }

    public static Optional<String> generate(String programId, String className,
                                            ProcedureBuilder.Result procedure, DataLayout layout,
                                            InitialImage.Result image, SpecialNames specialNames,
                                            CompilerOptions options, ProcedureManifest manifest) {
        if (options.zeusCompatibility() || procedure.paragraphs().isEmpty() || !procedure.entries().isEmpty()
                || !procedure.parameters().isEmpty() || !procedure.declaratives().isEmpty()
                || specialNames.collatingSequence() != null
                || CompileCodePage.current() != CodePages.DEFAULT
                || procedure.paragraphs().stream().anyMatch(ProcedureBuilder.Paragraph::hasDebugEntry)
                || !layout.findAll(DataDivisionBuilder.DEBUG_LINE_SLOT).isEmpty()
                || !layout.findAll(DataDivisionBuilder.DEBUG_REASON_SLOT).isEmpty()
                || layout.records().stream().anyMatch(CobolJavaSource::unsupportedStorage)
                || layout.all().stream().anyMatch(item -> item.isUtf8() || item.isDynamic()
                        || nestedDependingTable(item))) {
            return Optional.empty();
        }
        try {
            return Optional.of(new CobolJavaSource(procedure, specialNames, options, image.storage())
                    .render(programId, className, layout, manifest));
        } catch (UnsupportedConstruct e) {
            return Optional.empty();
        }
    }

    private static boolean unsupportedStorage(DataItem item) {
        return item.external() || (item.section() != DataSection.WORKING_STORAGE
                && item.section() != DataSection.SPECIAL_REGISTER);
    }

    private static boolean nestedDependingTable(DataItem item) {
        if (item.occursDepending() == null) {
            return false;
        }
        for (DataItem parent = item.parent(); parent != null; parent = parent.parent()) {
            if (parent.occursDepending() != null) {
                return true;
            }
        }
        return false;
    }

    private String render(String programId, String className, DataLayout layout,
                          ProcedureManifest manifest) {
        StringBuilder dispatch = new StringBuilder();
        for (int i = 0; i < paragraphs.size(); i++) {
            paragraphIndex = i;
            String name = "paragraph$" + names.paragraph(i);
            dispatch.append("                case ").append(paragraphConstant(i)).append(" -> ")
                    .append(name).append("(storage, context);\n");
            indent = 1;
            line("");
            line("private int " + name + "(Storage storage, ProgramContext context) {");
            indent++;
            if (emitStatements(procedure.paragraphs().get(i).statements())) {
                line("return FALL_THROUGH;");
            }
            indent--;
            line("}");
        }
        String first = paragraphConstant(procedure.firstNormalParagraph());
        String last = paragraphConstant(paragraphs.size() - 1);
        String constants = fields.entrySet().stream().map(field ->
                "    private static final int " + field.getKey() + " = " + field.getValue() + ";\n")
                .collect(Collectors.joining());
        constants += constantFields("NumericItem", numericFields)
                + constantFields("Picture", pictureFields)
                + constantFields("byte[]", initializeFields);
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        return """
                package %s;

                import dev.cobolonjava.runtime.abend.StorageMap;
                import dev.cobolonjava.runtime.codepage.CodePages;
                import dev.cobolonjava.runtime.data.Dbcs;
                import dev.cobolonjava.runtime.data.National;
                import dev.cobolonjava.runtime.data.SignPosition;
                import dev.cobolonjava.runtime.data.TruncMode;
                import dev.cobolonjava.runtime.decimal.CobolRounding;
                import dev.cobolonjava.runtime.decimal.Decimal;
                import dev.cobolonjava.runtime.decimal.DecimalDivideException;
                import dev.cobolonjava.runtime.interop.ProgramId;
                import dev.cobolonjava.runtime.interop.ProgramSignature;
                import dev.cobolonjava.runtime.item.NumericItem;
                import dev.cobolonjava.runtime.item.Usage;
                import dev.cobolonjava.runtime.picture.Picture;
                import dev.cobolonjava.runtime.picture.PictureParser;
                import dev.cobolonjava.runtime.procedure.ProcedureDescriptor;
                import dev.cobolonjava.runtime.procedure.ProcedureId;
                import dev.cobolonjava.runtime.procedure.ProcedureKind;
                import dev.cobolonjava.runtime.procedure.ProcedureManifest;
                import dev.cobolonjava.runtime.program.CobolProgram;
                import dev.cobolonjava.runtime.program.Ops;
                import dev.cobolonjava.runtime.program.ProgramContext;
                import dev.cobolonjava.runtime.storage.DataView;
                import dev.cobolonjava.runtime.storage.Storage;
                import dev.cobolonjava.runtime.verb.ClassTest;
                import dev.cobolonjava.runtime.verb.StringVerb;
                import java.util.HexFormat;
                import java.util.List;

                public final class %s implements CobolProgram {
                    private static final int FALL_THROUGH = -1;
                    private static final String PROGRAM_ID = %s;
                    private static final byte[] INITIAL_STORAGE = %s;
                    private static final StorageMap STORAGE_MAP = StorageMap.parse(%s);
                %s
                    @Override
                    public String name() {
                        return PROGRAM_ID;
                    }

                    @Override
                    public boolean acceptsCobolBatchOptions() {
                        return true;
                    }

                    @Override
                    public boolean initialProgram() {
                        return %s;
                    }

                    @Override
                    public byte[] initialStorage() {
                        return INITIAL_STORAGE.clone();
                    }

                    @Override
                    public StorageMap storageMap() {
                        return STORAGE_MAP;
                    }

                    @Override
                    public ProgramSignature programSignature() {
                        return ProgramSignature.of(PROGRAM_ID, List.of());
                    }

                    @Override
                    public ProcedureManifest procedureManifest() {
                        return ProcedureManifest.of(PROGRAM_ID, %s);
                    }

                    @Override
                    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
                        performRange(%s, %s, storage, context);
                    }

                    @Override
                    public void performGlobalRange(int first, int last, Storage storage,
                                                   ProgramContext context) {
                        performRange(first, last, storage, context);
                    }

                    @Override
                    public void performProcedureRange(int first, int last, Storage storage,
                                                      ProgramContext context, DataView[] arguments) {
                        performRange(first, last, storage, context);
                    }

                    private void performRange(int first, int last, Storage storage, ProgramContext context) {
                        int paragraph = first;
                        do {
                            int next = switch (paragraph) {
                %s                default -> FALL_THROUGH;
                            };
                            if (next >= 0) {
                                paragraph = next;
                            } else {
                                if (next < FALL_THROUGH) {
                                    // EXIT SECTION completes the section's last paragraph.
                                    paragraph = -next - 2;
                                }
                                if (paragraph == last) {
                                    return;
                                }
                                paragraph++;
                            }
                        } while (paragraph < %s);
                        Ops.programReturn();
                    }
                %s
                    public static void main(String[] args) {
                        System.exit(new %s().runMain());
                    }
                }
                """.formatted(ProgramSupport.GENERATED_PACKAGE, simpleName,
                JavaSource.literal(programId), hex(initialStorage),
                JavaSource.textExpression(ProgramGenerator.storageMapOf(layout).encoded(), 12),
                constants, procedure.initialProgram(), descriptors(manifest), first, last,
                dispatch, paragraphs.size(), methods, simpleName);
    }

    private static String constantFields(String type, Map<String, String> fields) {
        return fields.entrySet().stream().map(field ->
                "    private static final " + type + " " + field.getKey() + " =\n"
                        + "            " + field.getValue() + ";\n")
                .collect(Collectors.joining());
    }

    /** 末尾へそのまま進めるか。Java で到達不能になる後続文は出力しない。 */
    private boolean emitStatements(List<Statement> statements) {
        for (Statement statement : statements) {
            if (!emitStatement(statement)) {
                return false;
            }
        }
        return true;
    }

    private boolean emitStatement(Statement statement) {
        switch (statement) {
            case Statement.Sentence sentence -> { return emitSentence(sentence); }
            case Statement.NextSentence ignored -> { return emitBreak(sentenceEnd); }
            case Statement.Exit exit -> { return emitExit(exit); }
            case Statement.Sequence sequence -> { return emitStatements(sequence.statements()); }
            case Statement.Display display -> emitDisplay(display);
            case Statement.Move move -> emitMove(move);
            case Statement.Accept accept -> emitAccept(accept);
            case Statement.Initialize initialize -> emitInitialize(initialize);
            case Statement.StringStatement string -> { return emitString(string); }
            case Statement.Compute compute -> { return emitCompute(compute); }
            case Statement.Arithmetic arithmetic -> { return emitArithmetic(arithmetic); }
            case Statement.ArithmeticGroup group -> { return emitArithmeticGroup(group); }
            case Statement.DivideRemainder divide -> { return emitDivideRemainder(divide); }
            case Statement.If branch -> { return emitIf(branch); }
            case Statement.Perform perform -> { return emitPerform(perform); }
            case Statement.Search search -> { return emitSearch(search); }
            case Statement.SearchAll search -> { return emitSearchAll(search); }
            case Statement.GoTo jump -> {
                require(jump.target() != null);
                line("return " + jumpTarget(jump.target()) + ";");
                return false;
            }
            case Statement.GoToDepending jump -> emitGoToDepending(jump);
            case Statement.Stop stop -> line(stop.wholeRun()
                    ? "Ops.stopRun();" : "Ops.programReturn();");
            case Statement.ExitProgram ignored -> line("Ops.exitProgram(context);");
            case Statement.Continue ignored -> { }
            default -> throw new UnsupportedConstruct();
        }
        return true;
    }

    private boolean emitSentence(Statement.Sentence sentence) {
        BreakTarget previous = sentenceEnd;
        BreakTarget end = new BreakTarget("sentence" + ++labelNumber);
        sentenceEnd = end;
        int start = methods.length();
        boolean fallsThrough = emitStatements(sentence.body());
        sentenceEnd = previous;
        wrapBreakTarget(end, start);
        return fallsThrough || end.used;
    }

    private boolean emitExit(Statement.Exit exit) {
        return switch (exit.kind()) {
            case PERFORM -> emitBreak(performEnd);
            case CYCLE -> emitBreak(performCycle);
            case PARAGRAPH -> {
                line("return FALL_THROUGH;");
                yield false;
            }
            case SECTION -> {
                int last = paragraphIndex;
                for (ProcedureBuilder.Section section : procedure.sections()) {
                    int first = paragraphs.indexOf(section.first());
                    int end = paragraphs.indexOf(section.last());
                    if (first <= paragraphIndex && paragraphIndex <= end) {
                        last = end;
                    }
                }
                line("return -(" + paragraphConstant(last) + " + 2);");
                yield false;
            }
        };
    }

    private boolean emitBreak(BreakTarget target) {
        require(target != null);
        target.used = true;
        line("break " + target.name + ";");
        return false;
    }

    /** 必要なときだけ、既に出力した本体をラベル付きブロックに入れる。 */
    private void wrapBreakTarget(BreakTarget target, int start) {
        if (target.used) {
            String body = methods.substring(start);
            methods.setLength(start);
            line(target.name + ": {");
            body.lines().forEach(text -> methods.append(text.isEmpty() ? "" : "    " + text).append('\n'));
            line("}");
        }
    }

    private String jumpTarget(String target) {
        int index = paragraphs.indexOf(target);
        require(index >= 0);
        return paragraphConstant(index);
    }

    private void emitGoToDepending(Statement.GoToDepending jump) {
        line("switch (Ops.toInt(" + decimal(new Operand.Reference(jump.selector())) + ")) {");
        indent++;
        for (int i = 0; i < jump.targets().size(); i++) {
            line("case " + (i + 1) + " -> { return " + jumpTarget(jump.targets().get(i)) + "; }");
        }
        indent--;
        line("}");
    }

    private void emitDisplay(Statement.Display display) {
        for (int i = 0; i < display.operands().size(); i++) {
            line("Ops.display(%s, context, %s, %s);".formatted(displayBytes(display.operands().get(i)),
                    display.advancing() && i == display.operands().size() - 1,
                    display.upon() == SpecialNames.FunctionName.SYSERR));
        }
    }

    private boolean emitString(Statement.StringStatement statement) {
        DataReference target = statement.target();
        require(target.constantLength().isPresent());
        DataReference pointer = statement.pointer();
        String start = pointer == null ? "1" : "Ops.toInt(" + readNumeric(pointer) + ")";
        List<String> sources = new ArrayList<>();
        for (Statement.StringStatement.StringSource group : statement.sources()) {
            String delimiter = group.delimiter() == null ? null : stringBytes(group.delimiter());
            for (Operand operand : group.values()) {
                String value = stringBytes(operand);
                sources.add(delimiter == null ? "StringVerb.Source.bySize(" + value + ")"
                        : "StringVerb.Source.delimitedBy(" + value + ", " + delimiter + ")");
            }
        }
        String call = "Ops.string(storage, %s, %s, %s".formatted(offset(target), length(target), start);
        if (!sources.isEmpty()) {
            call += ",\n" + "    ".repeat(indent + 1)
                    + String.join(",\n" + "    ".repeat(indent + 1), sources);
        }
        Statement.Overflow overflow = statement.overflow();
        if (pointer == null && overflow == null) {
            line(call + ");");
            return true;
        }
        String result = "stringResult" + ++stringNumber;
        line("StringVerb.Result " + result + " = " + call + ");");
        if (pointer != null) {
            line("Ops.storeInteger(%s.pointer(), %s, storage, %s);"
                    .formatted(result, numericShape(numericItem(pointer)), offset(pointer)));
        }
        return overflow == null
                || emitBranch(result + ".overflow()", overflow.onOverflow(), overflow.otherwise());
    }

    private String stringBytes(Operand operand) {
        return switch (operand) {
            case Operand.Reference reference -> readBytes(reference.reference());
            case Operand.Literal literal -> switch (literal.value()) {
                case LiteralValue.Text ignored -> bytes(operand);
                case LiteralValue.Number ignored -> bytes(operand);
                default -> hex(ProgramGenerator.literalBytes(literal.value(), 1,
                        CodePages.DEFAULT, specialNames));
            };
            case Operand.Function ignored -> throw new UnsupportedConstruct();
            case Operand.Address ignored -> throw new UnsupportedConstruct();
        };
    }

    private void emitMove(Statement.Move move) {
        require(!move.corresponding());
        for (Statement.Move.Target target : move.targets()) {
            DataReference reference = target.reference();
            switch (target.kind()) {
                case ALPHANUMERIC -> {
                    int width = reference.constantLength().orElse(reference.item().length());
                    emitAlphanumericMove(moveBytes(move.source(), reference, width), reference);
                }
                case NUMERIC -> emitNumericMove(decimal(move.source()), reference);
                case NUMERIC_EDITED -> {
                    DataItem item = workingStorageItem(reference);
                    line("Ops.moveNumericEdited(%s, %s, storage, %s, CodePages.DEFAULT);".formatted(
                            decimal(move.source()), editedPicture(item), offset(reference)));
                }
                case ALPHANUMERIC_EDITED -> {
                    DataItem item = workingStorageItem(reference);
                    Picture picture = item.picture();
                    require(!picture.isNationalEdited());
                    String source = moveBytes(move.source(), reference,
                            ProgramGenerator.dataPositions(picture));
                    line("Ops.moveAlphanumericEdited(%s, %s, storage, %s, CodePages.DEFAULT);"
                            .formatted(source, editedPicture(item), offset(reference)));
                }
                case NATIONAL -> emitNationalMove(move.source(), reference);
                default -> throw new UnsupportedConstruct();
            }
        }
    }

    private void emitNationalMove(Operand source, DataReference target) {
        DataItem item = workingStorageItem(target);
        String value = nationalBytes(source, target.constantLength().orElse(item.length()));
        if (item.picture() != null && item.picture().isNationalEdited() && target.refMod() == null) {
            line("Ops.moveAlphanumericEdited(%s, %s, storage, %s, CodePages.DEFAULT);"
                    .formatted(value, editedPicture(item), offset(target)));
        } else {
            line("National.move(%s, storage, %s, %s, %s);"
                    .formatted(value, offset(target), length(target), item.justified()));
        }
    }

    private String nationalBytes(Operand operand, int width) {
        if (operand instanceof Operand.Literal literal) {
            require(!(literal.value() instanceof LiteralValue.Text text && text.utf8()));
            try {
                return hex(NationalLiterals.bytesOf(literal.value(), width, CodePages.DEFAULT));
            } catch (IllegalArgumentException ex) {
                throw new UnsupportedConstruct();
            }
        }
        require(operand instanceof Operand.Reference);
        DataReference source = ((Operand.Reference) operand).reference();
        Picture picture = source.item().picture();
        DataCategory category = DataCategory.of(source);
        if (category == DataCategory.NATIONAL || (picture != null && picture.isNationalEdited())) {
            return readBytes(source);
        }
        if (category == DataCategory.DBCS) {
            return "Dbcs.toNational(" + readBytes(source) + ", CodePages.DEFAULT)";
        }
        return "National.fromAlphanumeric(" + alphanumericBytes(source, true) + ", CodePages.DEFAULT)";
    }

    private String moveBytes(Operand source, DataReference target, int width) {
        if (source instanceof Operand.Literal literal
                && (literal.value() instanceof LiteralValue.Figure
                    || literal.value() instanceof LiteralValue.Repeated)) {
            return hex(ProgramGenerator.literalBytes(literal.value(), width, CodePages.DEFAULT,
                    specialNames));
        }
        if (source instanceof Operand.Reference operand) {
            DataCategory category = DataCategory.of(operand.reference());
            boolean convert = DataCategory.of(target) != DataCategory.GROUP;
            if (!convert || category.isNumeric() || category == DataCategory.NUMERIC_EDITED) {
                return alphanumericBytes(operand.reference(), convert);
            }
        }
        return bytes(source);
    }

    private String alphanumericBytes(DataReference reference, boolean convert) {
        DataItem item = workingStorageItem(reference);
        int zeros = ProgramGenerator.scalingZerosOf(item);
        if (convert && DataCategory.of(reference) == DataCategory.NUMERIC_INTEGER
                && usage(item) == Usage.DISPLAY
                && (item.picture().signPosition().isSigned() || zeros > 0)) {
            String digits = "Ops.readUnsignedDigits(%s, storage, %s, CodePages.DEFAULT)"
                    .formatted(numericShape(numericItem(reference)), offset(reference));
            return zeros == 0 ? digits
                    : "Ops.withScalingZeros(%s, %s, CodePages.DEFAULT)".formatted(digits, zeros);
        }
        return readBytes(reference);
    }

    private void emitAlphanumericMove(String source, DataReference target) {
        DataItem item = alphaItem(target);
        line("Ops.moveAlphanumeric(%s, storage, %s, %s, %s, CodePages.DEFAULT);".formatted(
                source, offset(target), length(target), item.justified()));
    }

    private void emitNumericMove(String source, DataReference target) {
        DataItem item = numericItem(target);
        line("Ops.moveNumeric(%s, %s, storage, %s);".formatted(
                source, numericShape(item), offset(target)));
    }

    private void emitAccept(Statement.Accept accept) {
        DataReference target = accept.target();
        require(target.constantLength().isPresent());
        String source = switch (accept.source()) {
            case CONSOLE -> "Ops.acceptLine(context, " + length(target) + ")";
            case REGISTER -> "Ops.register(context, " + JavaSource.literal(accept.register()) + ")";
        };
        switch (accept.kind()) {
            case ALPHANUMERIC -> emitAlphanumericMove(source, target);
            case NUMERIC -> emitNumericMove("Ops.asInteger(" + source + ", CodePages.DEFAULT)", target);
            default -> throw new UnsupportedConstruct();
        }
    }

    private void emitInitialize(Statement.Initialize initialize) {
        DataReference target = initialize.target();
        DataItem item = workingStorageItem(target);
        String address = offset(target);
        InitializeImage.Result image = InitializeImage.build(item, initialize.withFiller(),
                initialize.replacing(), initialize.toValue(), initialize.toDefault(), initialStorage,
                CodePages.DEFAULT, specialNames.highValue(), specialNames.lowValue());
        require(image.succeeded() && !image.runs().isEmpty());
        for (InitializeImage.Run run : image.runs()) {
            String field = "INITIALIZE_" + names.item(item) + "$" + (initializeFields.size() + 1);
            initializeFields.put(field, hex(run.bytes()));
            String destination = run.offset() == 0 ? address : address + " + " + run.offset();
            line("Ops.moveAlphanumeric(%s, storage, %s, %s.length, false, CodePages.DEFAULT);"
                    .formatted(field, destination, field));
        }
    }

    private boolean emitCompute(Statement.Compute compute) {
        int number = ++arithmeticNumber;
        String result = "result" + number;
        String error = compute.isChecked() ? "sizeError" + number : null;
        String value = expression(compute.value(), IntermediateDigits.of(
                compute.value(), compute.targets(), arithmeticDigits));
        if (error == null) {
            line("Decimal " + result + " = " + value + ";");
        } else {
            line("boolean " + error + " = false;");
            line("Decimal " + result + " = null;");
            emitCheckedEvaluation(error, result + " = " + value + ";");
            line("if (!" + error + ") {");
            indent++;
        }
        for (Statement.Arithmetic.Target target : compute.targets()) {
            emitArithmeticStore(target, result, error);
        }
        if (error != null) {
            indent--;
            line("}");
            return emitSizeErrorBranch(compute.sizeError(), error);
        }
        return true;
    }

    private boolean emitArithmetic(Statement.Arithmetic arithmetic) {
        int number = ++arithmeticNumber;
        String error = arithmetic.isChecked() ? "sizeError" + number : null;
        if (error != null) {
            line("boolean " + error + " = false;");
        }
        emitArithmeticTargets(arithmetic, number, error);
        return error == null || emitSizeErrorBranch(arithmetic.sizeError(), error);
    }

    private boolean emitArithmeticGroup(Statement.ArithmeticGroup group) {
        if (group.sizeError() == null) {
            group.operations().forEach(this::emitArithmetic);
            return true;
        }
        String error = "sizeError" + ++arithmeticNumber;
        line("boolean " + error + " = false;");
        for (Statement.Arithmetic pair : group.operations()) {
            emitArithmeticTargets(pair, ++arithmeticNumber, error);
        }
        return emitSizeErrorBranch(group.sizeError(), error);
    }

    private void emitArithmeticTargets(Statement.Arithmetic arithmetic, int number, String error) {
        List<String> operands = new ArrayList<>();
        for (Operand operand : arithmetic.operands()) {
            String name = "operand" + number + "_" + (operands.size() + 1);
            line("Decimal " + name + " = " + decimal(operand) + ";");
            operands.add(name);
        }
        boolean checkFoldDivisors = error != null && operands.size() > 1
                && arithmetic.fold() == Statement.Arithmetic.Operator.DIVIDE;
        if (checkFoldDivisors) {
            String zeroDivisor = operands.subList(1, operands.size()).stream()
                    .map(name -> "Ops.isZero(" + name + ")").collect(Collectors.joining(" || "));
            emitZeroDivisorBranch(zeroDivisor, error);
        }
        int targetNumber = 0;
        for (Statement.Arithmetic.Target target : arithmetic.targets()) {
            DataItem item = arithmetic.accumulate() == null
                    ? arithmeticItem(target.reference()) : numericItem(target.reference());
            int scale = item.picture().scale();
            String rounding = rounding(target);
            String folded = operands.getFirst();
            for (String operand : operands.subList(1, operands.size())) {
                folded = operation(arithmetic.fold().name(), folded, operand, scale, rounding);
            }
            boolean checkDivisor = error != null
                    && arithmetic.accumulate() == Statement.Arithmetic.Operator.DIVIDE;
            if (checkDivisor) {
                String divisor = "divisor" + number + "_" + (++targetNumber);
                line("Decimal " + divisor + " = " + folded + ";");
                folded = divisor;
                emitZeroDivisorBranch("Ops.isZero(" + divisor + ")", error);
            }
            String value = arithmetic.accumulate() == null ? folded
                    : operation(arithmetic.accumulate().name(), readNumeric(target.reference()),
                            folded, scale, rounding);
            emitArithmeticStore(target, value, error);
            if (checkDivisor) {
                indent--;
                line("}");
            }
        }
        if (checkFoldDivisors) {
            indent--;
            line("}");
        }
    }

    private boolean emitDivideRemainder(Statement.DivideRemainder divide) {
        int number = ++arithmeticNumber;
        String dividend = "dividend" + number;
        String divisor = "divisor" + number;
        String quotient = "quotient" + number;
        String remainder = "remainder" + number;
        String error = divide.isChecked() ? "sizeError" + number : null;
        int scale = arithmeticItem(divide.quotient().reference()).picture().scale();
        line("Decimal " + dividend + " = " + decimal(divide.dividend()) + ";");
        line("Decimal " + divisor + " = " + decimal(divide.divisor()) + ";");
        String quotientValue = operation("DIVIDE", dividend, divisor, scale, rounding(divide.quotient()));
        String remainderValue = "Ops.remainder(%s, %s, %s)".formatted(dividend, divisor, scale);
        if (error == null) {
            line("Decimal " + quotient + " = " + quotientValue + ";");
            line("Decimal " + remainder + " = " + remainderValue + ";");
            emitArithmeticStore(divide.quotient(), quotient, null);
            emitArithmeticStore(divide.remainder(), remainder, null);
            return true;
        }

        line("boolean " + error + " = false;");
        line("Decimal " + quotient + " = null;");
        line("Decimal " + remainder + " = null;");
        emitCheckedEvaluation(error, quotient + " = " + quotientValue + ";",
                remainder + " = " + remainderValue + ";");
        line("if (!" + error + ") {");
        indent++;
        emitArithmeticStore(divide.quotient(), quotient, error);
        // 商が受取項目に収まらなければ剰余も書き込まない。
        line("if (!" + error + ") {");
        indent++;
        emitArithmeticStore(divide.remainder(), remainder, error);
        indent--;
        line("}");
        indent--;
        line("}");
        return emitSizeErrorBranch(divide.sizeError(), error);
    }

    private void emitCheckedEvaluation(String error, String... assignments) {
        line("try {");
        indent++;
        for (String assignment : assignments) {
            line(assignment);
        }
        indent--;
        line("} catch (DecimalDivideException ignored) {");
        indent++;
        line(error + " = true;");
        indent--;
        line("}");
    }

    private void emitZeroDivisorBranch(String condition, String error) {
        line("if (" + condition + ") {");
        indent++;
        line(error + " = true;");
        indent--;
        line("} else {");
        indent++;
    }

    private void emitArithmeticStore(Statement.Arithmetic.Target target, String value, String error) {
        DataItem item = arithmeticItem(target.reference());
        boolean edited = DataCategory.of(target.reference()) == DataCategory.NUMERIC_EDITED;
        String shape = edited ? editedPicture(item) : numericShape(item);
        String store = edited ? "Ops.storeEdited" : "Ops.store";
        String arguments = "%s, %s, storage, %s, %s"
                .formatted(value, shape, offset(target.reference()), rounding(target));
        if (edited) {
            arguments += ", CodePages.DEFAULT";
        }
        // 先の受取項目であふれても、残りの項目への格納を続ける。
        line(error == null ? store + "(" + arguments + ");"
                : error + " |= " + store + "Checked(" + arguments + ");");
    }

    private boolean emitSizeErrorBranch(Statement.Arithmetic.SizeError sizeError, String error) {
        return sizeError.onError().isEmpty()
                ? emitBranch("!" + error, sizeError.otherwise(), List.of())
                : emitBranch(error, sizeError.onError(), sizeError.otherwise());
    }

    private String expression(Expression expression, IntermediateDigits digits) {
        return switch (expression) {
            case Expression.Value value -> decimal(value.operand());
            case Expression.Negate negate -> "Ops.negate(" + expression(negate.operand(), digits) + ")";
            case Expression.Binary binary -> {
                int scale = digits.of(binary).scale();
                String value = operation(binary.operator().name(), expression(binary.left(), digits),
                        expression(binary.right(), digits), scale, "CobolRounding.TRUNCATION");
                boolean truncate = binary.operator() == Expression.Operator.POWER
                        || (binary.operator() != Expression.Operator.DIVIDE
                                && scale < ProgramGenerator.naturalScale(binary, digits));
                yield truncate ? "Ops.truncate(" + value + ", " + scale + ")" : value;
            }
        };
    }

    private static String operation(String operator, String left, String right, int scale,
                                    String rounding) {
        String arguments = left + ", " + right;
        if (operator.equals("DIVIDE")) {
            arguments += ", " + scale + ", " + rounding;
        }
        return "Ops." + operator.toLowerCase(Locale.ROOT) + "(" + arguments + ")";
    }

    private static String rounding(Statement.Arithmetic.Target target) {
        return target.rounded() ? "CobolRounding.NEAREST_AWAY_FROM_ZERO" : "CobolRounding.TRUNCATION";
    }

    private boolean emitIf(Statement.If branch) {
        // EVALUATE の WHEN は意味解析で IF の連なりになる。
        line("if (" + condition(branch.condition()) + ") {");
        boolean fallsThrough = false;
        while (true) {
            indent++;
            fallsThrough |= emitStatements(branch.onTrue());
            indent--;
            List<Statement> otherwise = branch.onFalse();
            if (otherwise.size() == 1 && otherwise.getFirst() instanceof Statement.If next) {
                line("} else if (" + condition(next.condition()) + ") {");
                branch = next;
            } else {
                if (otherwise.isEmpty()) {
                    fallsThrough = true;
                } else {
                    line("} else {");
                    indent++;
                    fallsThrough |= emitStatements(otherwise);
                    indent--;
                }
                line("}");
                return fallsThrough;
            }
        }
    }

    private boolean emitBranch(String condition, List<Statement> onTrue, List<Statement> onFalse) {
        line("if (" + condition + ") {");
        indent++;
        boolean trueFallsThrough = emitStatements(onTrue);
        indent--;
        boolean falseFallsThrough = true;
        if (!onFalse.isEmpty()) {
            line("} else {");
            indent++;
            falseFallsThrough = emitStatements(onFalse);
            indent--;
        }
        line("}");
        return trueFallsThrough || falseFallsThrough;
    }

    private boolean emitPerform(Statement.Perform perform) {
        require(perform.debug().isEmpty());
        if (perform.callsParagraph()) {
            return emitPerformLoop(perform);
        }
        BreakTarget previous = performEnd;
        BreakTarget end = new BreakTarget("performEnd" + ++labelNumber);
        performEnd = end;
        int start = methods.length();
        boolean fallsThrough = emitPerformLoop(perform);
        performEnd = previous;
        wrapBreakTarget(end, start);
        return fallsThrough || end.used;
    }

    private boolean emitPerformLoop(Statement.Perform perform) {
        if (!perform.varying().isEmpty()) {
            return emitVarying(perform);
        }
        if (perform.forever()) {
            line("while (true) {");
        } else if (perform.times() != null) {
            int loop = ++loopNumber;
            String limit;
            if (perform.times() instanceof Operand.Literal literal
                    && literal.value() instanceof LiteralValue.Number count) {
                limit = Integer.toString(count.value().toBigDecimal().intValue());
            } else {
                limit = "limit" + loop;
                // 本体が回数項目を書き換えても、繰り返す回数は入口で一度だけ読む。
                line("int " + limit + " = Ops.toInt(" + decimal(perform.times()) + ");");
            }
            String counter = "iteration" + loop;
            line("for (int %s = 0; %s < %s; %s++) {".formatted(counter, counter, limit, counter));
        } else if (perform.until() != null) {
            line(perform.testAfter() ? "do {" : "while (!(" + condition(perform.until()) + ")) {");
        } else {
            return emitPerformBody(perform);
        }
        indent++;
        boolean bodyFallsThrough = emitPerformBody(perform);
        indent--;
        line(perform.until() != null && perform.testAfter()
                ? "} while (!(" + condition(perform.until()) + "));" : "}");
        return !perform.forever()
                && (perform.until() == null || !perform.testAfter() || bodyFallsThrough);
    }

    private boolean emitVarying(Statement.Perform perform) {
        require(perform.varying().size() == 1);
        Statement.Perform.Varying level = perform.varying().getFirst();
        require(level.debug().isEmpty() && level.debugTest().isEmpty());
        DataReference counter = level.target();
        String initial = store(counter, decimal(level.from()));
        String step = store(counter, "Ops.add(%s, %s)"
                .formatted(readNumeric(counter), decimal(level.by())));
        String until = condition(level.until());
        boolean bodyFallsThrough = true;
        if (perform.testAfter()) {
            line(initial + ";");
            line("while (true) {");
            indent++;
            bodyFallsThrough = emitPerformBody(perform);
            if (bodyFallsThrough) {
                line("if (" + until + ") {");
                indent++;
                line("break;");
                indent--;
                line("}");
                line(step + ";");
            }
        } else {
            line("for (" + initial + ";");
            line("        !(" + until + ");");
            line("        " + step + ") {");
            indent++;
            emitPerformBody(perform);
        }
        indent--;
        line("}");
        return bodyFallsThrough;
    }

    private boolean emitPerformBody(Statement.Perform perform) {
        if (!perform.callsParagraph()) {
            BreakTarget previous = performCycle;
            BreakTarget cycle = new BreakTarget("performCycle" + ++labelNumber);
            performCycle = cycle;
            int start = methods.length();
            boolean fallsThrough = emitStatements(perform.body());
            performCycle = previous;
            wrapBreakTarget(cycle, start);
            return fallsThrough || cycle.used;
        }
        String target = perform.target();
        String end = perform.through() == null ? target : perform.through();
        for (ProcedureBuilder.Section section : procedure.sections()) {
            // 節のフックと範囲の解釈は従来の生成経路で扱う。
            require(!section.name().equals(target) && !section.name().equals(end));
        }
        int first = paragraphs.indexOf(target);
        int last = paragraphs.indexOf(end);
        require(first >= 0 && last >= 0);
        line("performRange(%s, %s, storage, context);"
                .formatted(paragraphConstant(first), paragraphConstant(last)));
        return true;
    }

    private boolean emitSearch(Statement.Search search) {
        String done = "search" + ++labelNumber;
        int start = methods.length();
        BreakTarget searchEnd = new BreakTarget(done);
        line("while (Ops.toInt(" + readNumeric(search.index()) + ") <= "
                + searchLimit(search.occurs(), search.occursDepending()) + ") {");
        indent++;
        boolean matchedFallsThrough = false;
        for (Statement.Search.When when : search.whens()) {
            line("if (" + condition(when.condition()) + ") {");
            indent++;
            if (emitStatements(when.statements())) {
                emitBreak(searchEnd);
                matchedFallsThrough = true;
            }
            indent--;
            line("}");
        }
        emitSearchStep(search.index());
        if (search.varying() != null) {
            emitSearchStep(search.varying());
        }
        indent--;
        line("}");
        boolean atEndFallsThrough = emitStatements(search.atEnd());
        wrapBreakTarget(searchEnd, start);
        return matchedFallsThrough || atEndFallsThrough;
    }

    private void emitSearchStep(DataReference index) {
        line(store(index, "Ops.add(" + readNumeric(index) + ", Decimal.of(1, 0))") + ";");
    }

    private boolean emitSearchAll(Statement.SearchAll search) {
        int number = ++labelNumber;
        BreakTarget searchEnd = new BreakTarget("searchAll" + number);
        int start = methods.length();
        String low = "low" + number;
        String high = "high" + number;
        String middle = "middle" + number;
        String order = "order" + number;
        line("int " + low + " = 1;");
        line("int " + high + " = " + searchLimit(search.occurs(), search.occursDepending()) + ";");
        line("while (" + low + " <= " + high + ") {");
        indent++;
        line("int " + middle + " = (" + low + " + " + high + ") / 2;");
        DataReference index = search.index();
        line("Ops.storeInteger(%s, %s, storage, %s);"
                .formatted(middle, numericShape(numericItem(index)), offset(index)));
        require(!search.keys().isEmpty());
        for (int i = 0; i < search.keys().size(); i++) {
            Statement.SearchAll.KeyTest key = search.keys().get(i);
            String compared = comparison(key.test());
            // 符号だけが必要。降順は比較方向を反転する。
            String normalized = key.ascending() ? compared : "-Integer.signum(" + compared + ")";
            if (i == 0) {
                line("int " + order + " = " + normalized + ";");
            } else {
                line("if (" + order + " == 0) {");
                indent++;
                line(order + " = " + normalized + ";");
                indent--;
                line("}");
            }
        }
        line("if (" + order + " < 0) {");
        indent++;
        line(low + " = " + middle + " + 1;");
        indent--;
        line("} else if (" + order + " > 0) {");
        indent++;
        line(high + " = " + middle + " - 1;");
        indent--;
        line("} else {");
        indent++;
        if (emitStatements(search.whenStatements())) {
            emitBreak(searchEnd);
        }
        indent--;
        line("}");
        indent--;
        line("}");
        boolean atEndFallsThrough = emitStatements(search.atEnd());
        wrapBreakTarget(searchEnd, start);
        return searchEnd.used || atEndFallsThrough;
    }

    private String searchLimit(int maximum, DataReference depending) {
        return depending == null ? Integer.toString(maximum)
                : "Ops.toInt(" + readNumeric(depending) + ")";
    }

    private String condition(Condition condition) {
        return switch (condition) {
            case Condition.Relation relation -> {
                String operator = switch (relation.comparison()) {
                    case EQUAL -> "==";
                    case NOT_EQUAL -> "!=";
                    case LESS -> "<";
                    case LESS_OR_EQUAL -> "<=";
                    case GREATER -> ">";
                    case GREATER_OR_EQUAL -> ">=";
                };
                yield comparison(relation) + " " + operator + " 0";
            }
            case Condition.And and -> "(" + condition(and.left()) + ") && (" + condition(and.right()) + ")";
            case Condition.Or or -> "(" + condition(or.left()) + ") || (" + condition(or.right()) + ")";
            case Condition.Not not -> "!(" + condition(not.inner()) + ")";
            case Condition.ClassTest test -> classCondition(test);
            case Condition.SwitchTest test -> (test.whenOn() ? "" : "!")
                    + "Ops.switchState(context, " + test.index() + ")";
        };
    }

    private String comparison(Condition.Relation relation) {
        if (relation.numeric()) {
            return "Ops.compareNumeric(%s, %s)".formatted(
                    expression(relation.left(), IntermediateDigits.of(
                            relation.left(), List.of(), arithmeticDigits)),
                    expression(relation.right(), IntermediateDigits.of(
                            relation.right(), List.of(), arithmeticDigits)));
        }
        Operand left = Condition.Relation.operandOf(relation.left());
        Operand right = Condition.Relation.operandOf(relation.right());
        if (ProgramGenerator.isNationalRelation(relation)) {
            require(left != null && right != null);
            int width = Math.max(2, Math.max(ProgramGenerator.nationalLength(left),
                    ProgramGenerator.nationalLength(right)));
            return "National.compare(%s, %s)".formatted(nationalBytes(left, width),
                    nationalBytes(right, width));
        }
        return "Ops.compareAlphanumeric(%s, %s, CodePages.DEFAULT)".formatted(bytes(left), bytes(right));
    }

    private String classCondition(Condition.ClassTest test) {
        DataItem item = workingStorageItem(test.item());
        String value = readBytes(test.item());
        return switch (test.kind()) {
            case NUMERIC -> {
                if (item.picture() != null && item.picture().isNumeric()) {
                    String signs = switch (classSigns) {
                        case ClassTest.SIGNS_PREFERRED -> "SIGNS_PREFERRED";
                        case ClassTest.SIGNS_ALTERNATE -> "SIGNS_ALTERNATE";
                        default -> "SIGNS_PRIMARY";
                    };
                    yield "ClassTest.numeric(%s, %s, CodePages.DEFAULT, ClassTest.%s)"
                            .formatted(value, numericShape(numericItem(test.item())), signs);
                }
                if (item.picture() != null && item.picture().isExternalFloating()) {
                    String method = item.picture().isNationalEdited()
                            ? "externalFloatingNational" : "externalFloating";
                    yield "ClassTest." + method + "(" + value + ", CodePages.DEFAULT)";
                }
                yield "ClassTest.digits(" + value + ", CodePages.DEFAULT)";
            }
            case DEFINED -> "ClassTest.member(" + value + ", " + hex(test.allowed()) + ")";
            case ALPHABETIC, ALPHABETIC_LOWER, ALPHABETIC_UPPER -> {
                int mode = switch (test.kind()) {
                    case ALPHABETIC_LOWER -> 1;
                    case ALPHABETIC_UPPER -> 2;
                    default -> 0;
                };
                yield "ClassTest.alphabetic(%s, CodePages.DEFAULT, %s)".formatted(value, mode);
            }
        };
    }

    private String displayBytes(Operand operand) {
        if (ProgramGenerator.categoryOfArgument(operand) == DataCategory.NATIONAL) {
            return "National.toDisplay(" + nationalBytes(operand, 0) + ", CodePages.DEFAULT)";
        }
        if (operand instanceof Operand.Reference reference) {
            DataCategory category = DataCategory.of(reference.reference());
            if (category == DataCategory.NUMERIC_EDITED) {
                arithmeticItem(reference.reference());
                return readBytes(reference.reference());
            }
            if (category.isNumeric()) {
                DataItem item = numericItem(reference.reference());
                if (usage(item) == Usage.DISPLAY) {
                    return readBytes(reference.reference());
                }
                String shape = "DISPLAY_" + names.item(item);
                numericFields.putIfAbsent(shape,
                        "NumericItem.of(" + JavaSource.literal(item.picture().source()) + ", Usage.DISPLAY)");
                return "Ops.displayForm(" + readNumeric(reference.reference()) + ", " + shape + ")";
            }
        }
        return bytes(operand);
    }

    private String decimal(Operand operand) {
        if (operand instanceof Operand.Reference reference) {
            DataReference source = reference.reference();
            if (DataCategory.of(source) == DataCategory.NUMERIC_EDITED) {
                Picture picture = workingStorageItem(source).picture();
                String method = picture.isExternalFloating() ? "deEditFloating" : "deEdit";
                int scale = picture.isExternalFloating() ? picture.floatingImpliedScale() : picture.scale();
                if (picture.isNationalEdited()) {
                    method += "National";
                }
                return "Ops.%s(storage, %s, %s, %s, CodePages.DEFAULT)"
                        .formatted(method, offset(source), length(source), scale);
            }
            return readNumeric(source);
        }
        if (operand instanceof Operand.Literal literal) {
            if (literal.value() instanceof LiteralValue.Number number) {
                return "Decimal.parse(" + JavaSource.literal(number.value().toBigDecimal().toPlainString()) + ")";
            }
            if (literal.value() instanceof LiteralValue.Figure figure
                    && figure.constant() == LiteralValue.FigurativeConstant.ZERO) {
                return "Decimal.zero(0)";
            }
        }
        throw new UnsupportedConstruct();
    }

    private String readNumeric(DataReference reference) {
        return "Ops.readNumeric(%s, storage, %s)"
                .formatted(numericShape(numericItem(reference)), offset(reference));
    }

    private String store(DataReference reference, String value) {
        return "Ops.store(%s, %s, storage, %s, CobolRounding.TRUNCATION)"
                .formatted(value, numericShape(numericItem(reference)), offset(reference));
    }

    private String numericShape(DataItem item) {
        String name = "NUMERIC_" + names.item(item);
        numericFields.computeIfAbsent(name, ignored -> {
            Usage usage = usage(item);
            String picture = JavaSource.literal(item.picture().source());
            String code = "NumericItem.of(" + picture + ", Usage." + usage;
            if (specialNames.currency() != SpecialNames.DEFAULT_CURRENCY
                    || specialNames.decimalPoint() != '.') {
                code += ", " + charLiteral(specialNames.currency())
                        + ", " + charLiteral(specialNames.decimalPoint());
            }
            code += ")";
            if (item.signPosition() != SignPosition.UNSIGNED) {
                code += ".withSignPosition(SignPosition." + item.signPosition() + ")";
            }
            if (usage == Usage.COMP && !trunc.equals("STD")) {
                code += ".withTruncMode(TruncMode." + trunc + ")";
            }
            return code;
        });
        return name;
    }

    private String editedPicture(DataItem item) {
        String name = "PICTURE_" + names.item(item);
        pictureFields.computeIfAbsent(name, ignored -> {
            Picture picture = item.picture();
            String expression = "PictureParser.parse(%s, %s, %s)".formatted(
                    JavaSource.literal(picture.source()), charLiteral(specialNames.currency()),
                    charLiteral(specialNames.decimalPoint()));
            if (picture.blankWhenZero()) {
                expression += ".withBlankWhenZero(true)";
            }
            return picture.isNationalEdited() ? expression + ".asNational()" : expression;
        });
        return name;
    }

    private static String charLiteral(char value) {
        String quoted = JavaSource.literal(Character.toString(value));
        return value == '\'' ? "'\\''" : "'" + quoted.substring(1, quoted.length() - 1) + "'";
    }

    private String bytes(Operand operand) {
        if (operand instanceof Operand.Reference reference) {
            DataReference source = reference.reference();
            if (DataCategory.of(source) == DataCategory.ALPHANUMERIC_EDITED) {
                require(!workingStorageItem(source).picture().isNationalEdited());
            } else {
                alphaItem(source);
            }
            return readBytes(source);
        }
        if (operand instanceof Operand.Literal literal) {
            if (literal.value() instanceof LiteralValue.Text text) {
                require(!text.utf8());
                return text.isHex() ? hex(text.hex()) : encode(text.text());
            }
            if (literal.value() instanceof LiteralValue.Number number) {
                return encode(number.source());
            }
        }
        throw new UnsupportedConstruct();
    }

    private String readBytes(DataReference reference) {
        return "Ops.read(storage, %s, %s)".formatted(offset(reference), sendingLength(reference));
    }

    private String sendingLength(DataReference reference) {
        if (reference.refMod() == null && reference.subscripts().isEmpty()) {
            List<DataItem> tables = new ArrayList<>();
            DataItem item = reference.item();
            ProgramGenerator.collectTopDependingTables(item, tables);
            if (!tables.isEmpty()) {
                String maximum = item.totalLength() == item.length() ? itemLength(item)
                        : field("MAX_LENGTH_" + names.item(item), item.totalLength());
                return subtractUnusedTableBytes(maximum, tables);
            }
        }
        return length(reference);
    }

    private static DataItem alphaItem(DataReference reference) {
        DataItem item = workingStorageItem(reference);
        DataCategory category = DataCategory.of(reference);
        require(category == DataCategory.ALPHABETIC || category == DataCategory.ALPHANUMERIC
                || category == DataCategory.GROUP);
        return item;
    }

    private static DataItem numericItem(DataReference reference) {
        DataItem item = workingStorageItem(reference);
        require(item.picture() != null && DataCategory.of(reference).isNumeric()
                && switch (usage(item)) {
                    case DISPLAY, COMP, COMP_3, COMP_5 -> true;
                    default -> false;
                });
        return item;
    }

    private static DataItem arithmeticItem(DataReference reference) {
        if (DataCategory.of(reference) != DataCategory.NUMERIC_EDITED) {
            return numericItem(reference);
        }
        DataItem item = workingStorageItem(reference);
        require(!item.picture().isNationalEdited());
        return item;
    }

    private static Usage usage(DataItem item) {
        return item.usage() == null ? Usage.DISPLAY : item.usage();
    }

    private static DataItem workingStorageItem(DataReference reference) {
        DataItem item = reference.item();
        require(item.name() != null && item.section() == DataSection.WORKING_STORAGE);
        return item;
    }

    private String offset(DataReference reference) {
        DataItem item = workingStorageItem(reference);
        List<DataItem> tables = DataReference.tableChain(item);
        require(tables.size() == reference.subscripts().size());
        String offset = field("OFFSET_" + names.item(item), item.record().base() + item.offset());
        for (int i = 0; i < tables.size(); i++) {
            DataItem table = tables.get(i);
            DataReference.Subscript index = reference.subscripts().get(i);
            String value = subscript(index);
            if (rangeChecks && !(index instanceof DataReference.Subscript.Constant)) {
                value = "Ops.checkSubscript(%s, %s, %s)".formatted(value, table.occurs(),
                        JavaSource.literal(table.name() == null ? "FILLER" : table.name()));
            }
            offset += " + (" + value + " - 1) * " + itemLength(table);
        }
        if (reference.refMod() != null) {
            int unit = reference.characterWidth();
            DataReference.Subscript start = reference.refMod().leftmost();
            String position = subscript(start);
            // ASM は開始位置が可変で長さが定数のときにこの検査を行う。
            if (rangeChecks && !(start instanceof DataReference.Subscript.Constant)
                    && reference.constantLength().isPresent()) {
                String characters = unit == 1 ? itemLength(item) : itemLength(item) + " / " + unit;
                position = "Ops.checkRefMod(%s, %s, %s, %s)".formatted(position,
                        reference.constantLength().getAsInt() / unit, characters,
                        JavaSource.literal(item.name()));
            }
            offset += " + (" + position + " - 1)";
            if (unit != 1) {
                offset += " * " + unit;
            }
        }
        return subtractUnusedTableBytes(offset, ProgramGenerator.precedingDependingTables(item));
    }

    private String subtractUnusedTableBytes(String base, List<DataItem> tables) {
        for (DataItem table : tables) {
            DataReference counter = new DataReference(table.occursDepending(), List.of(), null, table.origin());
            String count = "Ops.readInteger(%s, storage, %s)"
                    .formatted(numericShape(numericItem(counter)), offset(counter));
            base += " - (" + table.occurs() + " - " + count + ") * " + itemLength(table);
        }
        return base;
    }

    private String length(DataReference reference) {
        if (reference.refMod() == null) {
            return itemLength(reference.item());
        }
        if (reference.constantLength().isPresent()) {
            return Integer.toString(reference.constantLength().getAsInt());
        }
        DataReference.RefMod refMod = reference.refMod();
        int unit = reference.characterWidth();
        if (unit != 1) {
            return refMod.length() == null
                    ? itemLength(reference.item()) + " + " + unit + " - (" + subscript(refMod.leftmost())
                            + ") * " + unit
                    : "(" + subscript(refMod.length()) + ") * " + unit;
        }
        return refMod.length() == null
                ? itemLength(reference.item()) + " + 1 - (" + subscript(refMod.leftmost()) + ")"
                : subscript(refMod.length());
    }

    private String subscript(DataReference.Subscript subscript) {
        return switch (subscript) {
            case DataReference.Subscript.Constant constant -> Integer.toString(constant.value());
            case DataReference.Subscript.Variable variable -> {
                String value = "Ops.toInt(" + readNumeric(variable.reference()) + ")";
                yield variable.offset() == 0 ? value : "(" + value + " + (" + variable.offset() + "))";
            }
            case DataReference.Subscript.Computed computed -> "Ops.toInt("
                    + expression(computed.expression(), IntermediateDigits.of(
                            computed.expression(), List.of(), arithmeticDigits)) + ")";
            case DataReference.Subscript.All ignored -> throw new UnsupportedConstruct();
        };
    }

    private String itemLength(DataItem item) {
        if (item.name() == null) {
            return Integer.toString(item.length());
        }
        return field("LENGTH_" + names.item(item), item.length());
    }

    private String paragraphConstant(int index) {
        return field("PARAGRAPH_" + names.paragraph(index), index);
    }

    private String field(String name, int value) {
        fields.putIfAbsent(name, value);
        return name;
    }

    private static String descriptors(ProcedureManifest manifest) {
        return manifest.procedures().stream().map(item ->
                ("new ProcedureDescriptor(new ProcedureId(ProgramId.of(PROGRAM_ID), "
                        + "ProcedureKind.%s, %s), %s, %s, %s, %s, %s, %s, %s)")
                        .formatted(item.id().kind(), JavaSource.literal(item.id().name()),
                                item.firstParagraph(), item.lastParagraph(), item.declarative(),
                                nullable(item.sourceFile()), item.sourceLine(),
                                item.directInvocationEligible(), nullable(item.ineligibilityReason())))
                .collect(Collectors.joining(",\n                ", "List.of(", ")"));
    }

    private static String nullable(String value) {
        return value == null ? "null" : JavaSource.literal(value);
    }

    private static String encode(String value) {
        return "CodePages.DEFAULT.encode(" + JavaSource.literal(value) + ")";
    }

    private static String hex(byte[] bytes) {
        return "HexFormat.of().parseHex("
                + JavaSource.textExpression(HexFormat.of().formatHex(bytes), 12) + ")";
    }

    private void line(String text) {
        if (!text.isEmpty()) {
            methods.append("    ".repeat(indent)).append(text);
        }
        methods.append('\n');
    }

    private static void require(boolean supported) {
        if (!supported) {
            throw new UnsupportedConstruct();
        }
    }

    private static final class UnsupportedConstruct extends RuntimeException {
        private UnsupportedConstruct() {
            super(null, null, false, false);
        }
    }
}
