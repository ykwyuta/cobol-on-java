package dev.cobolonjava.hlasm;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HLASM の原文を機械語へ組み立てる。
 *
 * <p>2 周する。1 周目で所在カウンタを進めて記号の値と長さ属性を決め、2 周目でバイト列を作る。
 * {@code USING} は<b>翻訳時の状態</b>であり実行時の値ではないため、2 周目でも同じ順に辿り直す。
 *
 * <p>増分 1 の範囲は「COBOL から呼ばれる副プログラム 1 本」である。ソース内マクロと基本的な
 * 条件付きアセンブリは {@link MacroProcessor} が先に展開する。知らない命令欄は未定義の
 * マクロ呼出しかもしれないが、<b>勝手に読み飛ばさずに断る</b>。
 * 読み飛ばすと、展開されるはずだった命令が消えたまま組み立てが通ってしまう。
 */
public final class Assembler implements Constants.Scope {

    /** 変位の上限。ベース + 変位で表せる範囲である。 */
    private static final int MAX_DISPLACEMENT = 0xFFF;

    private final String fileName;
    private final CodePage codePage;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Map<String, Symbol> symbols = new LinkedHashMap<>();
    private final Map<String, Section> sections = new LinkedHashMap<>();
    private final Using[] usings = new Using[16];
    /** まだ置いていないリテラル。{@code LTORG} か {@code END} でまとめて置く。 */
    private final List<String> pendingLiterals = new ArrayList<>();
    /** 置いたリテラルの変位。綴りごとに 1 つだけ作る。1 周目で決め、2 周目でも使う。 */
    private final Map<String, Integer> placedLiterals = new LinkedHashMap<>();
    /** どのリテラルがどのプールに入るか。1 周目で決めた順に 2 周目が取り出す。 */
    private final List<List<String>> pools = new ArrayList<>();
    private int poolIndex;
    private final List<ObjectModule.Line> listing = new ArrayList<>();
    /** 載せた番地を足す欄と、外の記号の番地を入れる欄。2 周目で集める。 */
    private final List<ObjectModule.Relocation> relocations = new ArrayList<>();
    private final List<ObjectModule.External> externals = new ArrayList<>();
    /** 20 ビットの符号つき変位を持つ命令 (RXY / RSY) を組み立てているあいだだけ立つ。 */
    private boolean longDisplacement;

    private Section current;
    private Section mainSection;
    private byte[] text = new byte[0];
    private int entryOffset;
    private boolean emitting;
    private boolean ended;

    /** 制御節の状態。ダミー節 ({@code DSECT}) はバイト列を持たない。 */
    private static final class Section {
        private final String name;
        private final boolean dummy;
        private int location;
        private int highWater;

        Section(String name, boolean dummy) {
            this.name = name;
            this.dummy = dummy;
        }

        void advance(int bytes) {
            location += bytes;
            highWater = Math.max(highWater, location);
        }
    }

    /** {@code USING} で結んだレジスタと番地。 */
    private record Using(String section, int offset) {
    }

    /** 番地の演算項を解いた結果。 */
    private record Addr(int displacement, int base, int index, Integer length) {
    }

    private Assembler(String fileName, CodePage codePage) {
        this.fileName = fileName;
        this.codePage = codePage;
    }

    @Override
    public CodePage codePage() {
        return codePage;
    }

    /** 組み立ての結果。診断があれば機械語は作らない。 */
    public record Result(ObjectModule module, List<Diagnostic> diagnostics) {

        public Result {
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean succeeded() {
            return module != null && diagnostics.stream()
                    .noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
        }
    }

    public static Result assemble(String fileName, String source) {
        return assemble(fileName, source, CodePages.DEFAULT);
    }

    public static Result assemble(String fileName, String source, CodePage codePage) {
        return assemble(fileName, source, codePage, SourceLibrary.empty());
    }

    static Result assemble(String fileName, String source, CodePage codePage,
                           SourceLibrary library) {
        Assembler assembler = new Assembler(fileName, codePage);
        try {
            MacroProcessor.Expansion expansion = MacroProcessor.expand(
                    HlasmReader.read(source), codePage, fileName, library);
            assembler.diagnostics.addAll(expansion.diagnostics());
            List<Statement> statements = expansion.statements();
            assembler.pass(statements, false);
            assembler.prepareText();
            assembler.pass(statements, true);
        } catch (AssemblyException failure) {
            return new Result(null, List.of(
                    Diagnostic.error(fileName, failure.line(), failure.getMessage())));
        }
        if (assembler.diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR)) {
            return new Result(null, assembler.diagnostics);
        }
        return new Result(assembler.module(), assembler.diagnostics);
    }

    private ObjectModule module() {
        Map<String, Integer> dummies = new LinkedHashMap<>();
        sections.values().stream().filter(s -> s.dummy)
                .forEach(s -> dummies.put(s.name, s.highWater));
        return new ObjectModule(mainSection == null ? fileName : mainSection.name, text,
                entryOffset, symbols, dummies, listing, relocations, externals);
    }

    private void prepareText() {
        if (mainSection == null) {
            throw new AssemblyException(1, "the source defines no control section");
        }
        text = new byte[mainSection.highWater];
        // 2 周目は状態を作り直す。USING は翻訳時の状態であり、1 周目の残りを持ち越してはならない
        Arrays.fill(usings, null);
        sections.values().forEach(section -> section.location = 0);
        pendingLiterals.clear();
        // 置き場は 1 周目で決まっている。2 周目はそれを使うので消さない
        poolIndex = 0;
        listing.clear();
        relocations.clear();
        externals.clear();
        current = null;
        ended = false;
    }

    private void pass(List<Statement> statements, boolean emit) {
        emitting = emit;
        for (Statement statement : statements) {
            if (ended) {
                break;
            }
            assemble(statement);
        }
        if (!ended) {
            // END が無い原文でも、置き残したリテラルは置く
            placeLiterals(lastLine(statements));
        }
    }

    private static int lastLine(List<Statement> statements) {
        return statements.isEmpty() ? 1 : statements.get(statements.size() - 1).line();
    }

    private void assemble(Statement statement) {
        switch (statement.operation()) {
            case "CSECT", "START" -> startSection(statement, false);
            case "DSECT" -> startSection(statement, true);
            case "USING" -> using(statement);
            case "DROP" -> drop(statement);
            case "DC" -> constants(statement, true);
            case "DS" -> constants(statement, false);
            case "EQU" -> equate(statement);
            case "ORG" -> org(statement);
            case "LTORG" -> {
                defineLabel(statement, 1);
                placeLiterals(statement.line());
            }
            case "END" -> end(statement);
            // 一覧・整形の指示は組み立てに影響しないので読み飛ばす
            case "PRINT", "SPACE", "EJECT", "TITLE", "POP", "PUSH" -> { }
            // AMODE / RMODE は載せる側の属性であり、増分 1 では 31 ビットに固定している
            case "AMODE", "RMODE" -> { }
            case "MACRO", "MEND", "MEXIT", "AIF", "AGO", "ANOP", "ACTR", "SETA", "SETB", "SETC",
                 "GBLA", "GBLB", "GBLC", "LCLA", "LCLB", "LCLC", "MNOTE" ->
                    throw new AssemblyException(statement.line(),
                            "the macro and conditional assembly language is not supported yet: "
                                    + statement.operation());
            // 外の記号の宣言。V 型の定数が名前で引くので、宣言そのものは何も置かない
            case "ENTRY", "EXTRN", "WXTRN" -> { }
            case "CNOP" -> cnop(statement);
            case "DCB" -> dataControlBlock(statement);
            case "OPEN" -> openClose(statement, true);
            case "CLOSE" -> openClose(statement, false);
            case "PUT" -> getPut(statement, SVC_PUT);
            case "GET" -> getPut(statement, SVC_GET);
            case "COM", "RSECT", "COPY", "ICTL", "OPSYN" ->
                    throw new AssemblyException(statement.line(),
                            statement.operation() + " is not supported yet");
            default -> machine(statement);
        }
    }

    // --- 制御節 ---

    private void startSection(Statement statement, boolean dummy) {
        String name = statement.label() != null ? statement.label()
                : dummy ? null : defaultSectionName();
        if (name == null) {
            throw new AssemblyException(statement.line(), "DSECT requires a name");
        }
        if ("START".equals(statement.operation()) && statement.hasOperands()
                && absolute(statement.operands(), statement.line()) != 0) {
            // 節の先頭を 0 以外にすると、変位と番地が一致しなくなる。載せる側が番地を決める
            throw new AssemblyException(statement.line(),
                    "START with a nonzero origin is not supported; the loader decides the address");
        }
        Section section = sections.computeIfAbsent(name, key -> new Section(key, dummy));
        if (section.dummy != dummy) {
            throw new AssemblyException(statement.line(),
                    name + " is already defined as a different kind of section");
        }
        current = section;
        if (!dummy && mainSection == null) {
            mainSection = section;
        }
        if (!dummy && mainSection != section) {
            throw new AssemblyException(statement.line(),
                    "more than one control section is not supported yet: " + name);
        }
        if (!emitting) {
            define(name, new Symbol(name, Value.in(name, 0), 1), statement.line());
        }
    }

    private String defaultSectionName() {
        return fileName.replaceAll("\\.[^.]*$", "").toUpperCase(Locale.ROOT);
    }

    private Section requireSection(int line) {
        if (current == null) {
            throw new AssemblyException(line, "a statement appears before any control section");
        }
        return current;
    }

    // --- USING / DROP ---

    private void using(Statement statement) {
        defineLabel(statement, 1);
        if (!emitting) {
            // USING は所在カウンタを動かさない。1 周目で解くと、あとで定義される節や記号を
            // 指す USING (DSECT を先に USING する書き方) が前方参照で落ちる
            return;
        }
        List<String> operands = statement.operandList();
        if (operands.size() < 2) {
            throw new AssemblyException(statement.line(), "USING requires a base and a register");
        }
        if (operands.get(0).startsWith("(")) {
            throw new AssemblyException(statement.line(),
                    "the USING range form is not supported yet");
        }
        Value base = evaluate(operands.get(0), statement.line());
        if (base.isAbsolute()) {
            throw new AssemblyException(statement.line(),
                    "USING requires a relocatable base: " + operands.get(0));
        }
        for (int k = 1; k < operands.size(); k++) {
            int register = register(operands.get(k), statement.line());
            // 2 つ目以降のレジスタは、1 つ前の届く範囲の続きを指す
            usings[register] = new Using(base.section(), base.value() + (k - 1) * 4096);
        }
    }

    private void drop(Statement statement) {
        defineLabel(statement, 1);
        if (!emitting) {
            return;
        }
        if (!statement.hasOperands()) {
            Arrays.fill(usings, null);
            return;
        }
        for (String operand : statement.operandList()) {
            usings[register(operand, statement.line())] = null;
        }
    }

    // --- DC / DS ---

    private void constants(Statement statement, boolean values) {
        Section section = requireSection(statement.line());
        boolean first = true;
        for (String operand : statement.operandList()) {
            Constants.Piece piece = Constants.parse(operand, values, this, statement.line());
            align(section, piece.alignment());
            if (first) {
                // 名前欄は最初の演算項を指し、長さ属性もその 1 つ分である
                defineLabel(statement, piece.length());
                first = false;
            }
            if (emitting && piece.bytes() != null && !section.dummy) {
                emitRepeated(section, piece, statement);
            } else {
                if (emitting && !section.dummy) {
                    listing.add(new ObjectModule.Line(statement.line(), section.location,
                            new byte[0]));
                }
                section.advance(piece.totalLength());
            }
        }
        if (first) {
            throw new AssemblyException(statement.line(),
                    statement.operation() + " requires at least one operand");
        }
    }

    private void emitRepeated(Section section, Constants.Piece piece, Statement statement) {
        byte[] unit = piece.bytes();
        int start = section.location;
        for (int repeat = 0; repeat < piece.duplication(); repeat++) {
            resolveReferences(unit, piece, section.location, statement.line());
            write(section.location, unit);
            section.advance(unit.length);
        }
        listing.add(new ObjectModule.Line(statement.line(), start,
                Arrays.copyOfRange(text, start, section.location)));
    }

    /**
     * 番地定数の値を入れ、載せる側が直す欄を控える。
     *
     * <p>{@code A(記号)} の値は節の先頭からの変位であり、載せた番地を足して初めて番地になる。
     * {@code V(名前)} は外の記号で、値は載せる側が入れる。どちらも<b>欄の位置を控えて</b>
     * 載せる側へ渡す。以前は控えていなかったので、番地定数を通した分岐や引数表は
     * 変位のまま使われていた (ZHVS の報告のマクロが {@code A(ZCMD+X'80000000')} と
     * {@code =V(ZHVRPT)} を使っており、最初の 1 本目で見つかった)。
     */
    private void resolveReferences(byte[] unit, Constants.Piece piece, int location, int line) {
        for (Constants.AddressReference reference : piece.references()) {
            if (reference.external()) {
                externals.add(new ObjectModule.External(location + reference.offset(),
                        reference.length(), reference.expression().trim()
                                .toUpperCase(Locale.ROOT)));
                continue;
            }
            Value value = evaluate(reference.expression(), line);
            writeInt(unit, reference.offset(), reference.length(), value.value());
            if (value.isRelocatable() && reference.length() >= 3) {
                relocations.add(new ObjectModule.Relocation(location + reference.offset(),
                        reference.length()));
            }
        }
    }

    private static void writeInt(byte[] target, int offset, int length, int value) {
        int remaining = value;
        for (int k = offset + length - 1; k >= offset; k--) {
            target[k] = (byte) (remaining & 0xFF);
            remaining >>= 8;
        }
    }

    private void align(Section section, int alignment) {
        if (alignment <= 1) {
            return;
        }
        int pad = (alignment - section.location % alignment) % alignment;
        section.advance(pad);
    }

    /**
     * {@code CNOP b,w}。所在カウンタが {@code w} の境界から {@code b} のところへ来るまで、
     * 何もしない命令 ({@code NOPR 0} = X'0700') で埋める。
     */
    private void cnop(Statement statement) {
        List<String> operands = statement.operandList();
        require(operands, 2, statement);
        Section section = requireSection(statement.line());
        int offset = absolute(operands.get(0), statement.line());
        int boundary = absolute(operands.get(1), statement.line());
        if (boundary <= 0 || offset < 0 || offset >= boundary || offset % 2 != 0) {
            throw new AssemblyException(statement.line(), "bad CNOP operands: "
                    + statement.operands());
        }
        align(section, 2);
        defineLabel(statement, 2);
        while (section.location % boundary != offset) {
            if (emitting) {
                write(section.location, new byte[] {0x07, 0x00});
            }
            section.advance(2);
        }
    }

    // --- QSAM のマクロ (DCB / OPEN / CLOSE / GET / PUT) ---

    /**
     * {@code GET} と {@code PUT} が出す SVC の番号。
     *
     * <p>IBM の展開は SVC ではなく、DCB に入っているアクセス方式のルーチンへ分岐する。
     * この処理系はそのルーチンを持たないので、<b>自分で決めた SVC の番号</b>で実行時の
     * サービスへ渡す。だからこの 2 つのマクロが出す機械語は IBM のものと一致しない。
     * 一致させるのは振る舞いである。{@code OPEN} (SVC 19) と {@code CLOSE} (SVC 20) は
     * IBM と同じ番号を使う。
     */
    static final int SVC_GET = 250;
    static final int SVC_PUT = 251;

    /** DCB の長さ。実機の QSAM の DCB (96 byte) と同じ場所に、同じ欄を置く。 */
    static final int DCB_LENGTH = 96;

    /**
     * {@code DCB DDNAME=,MACRF=,RECFM=,LRECL=,BLKSIZE=,EODAD=,SYNAD=}。
     *
     * <p>欄の置き場は Zeus (同じ権利者の処理系) の {@code data_management.rs} から移した。
     * DSORG は +26、RECFM は +36、DDNAME は +40、MACRF は +50 / +51、BLKSIZE は +62、
     * LRECL は +82、EODAD は +33 (3 byte)、SYNAD は +57 (3 byte) である。DSECT IHADCB の
     * 公開されている配置と同じである。
     */
    private void dataControlBlock(Statement statement) {
        Section section = requireSection(statement.line());
        align(section, 4);
        defineLabel(statement, DCB_LENGTH);
        int start = section.location;
        if (!emitting) {
            section.advance(DCB_LENGTH);
            return;
        }
        byte[] block = new byte[DCB_LENGTH];
        block[26] = 0x40;
        Arrays.fill(block, 40, 48, codePage.space());
        for (String operand : statement.operandList()) {
            int equals = operand.indexOf('=');
            if (equals < 0) {
                throw new AssemblyException(statement.line(), "DCB takes keyword operands: "
                        + operand);
            }
            String keyword = operand.substring(0, equals).trim().toUpperCase(Locale.ROOT);
            String value = operand.substring(equals + 1).trim();
            switch (keyword) {
                case "DDNAME" -> {
                    byte[] name = value.toUpperCase(Locale.ROOT).getBytes(codePage.charset());
                    System.arraycopy(name, 0, block, 40, Math.min(8, name.length));
                }
                case "RECFM" -> {
                    String format = value.toUpperCase(Locale.ROOT);
                    block[36] = (byte) ((format.startsWith("V") ? 0x40 : 0x80)
                            | (format.contains("B") ? 0x10 : 0));
                }
                case "LRECL" -> writeInt(block, 82, 2, absolute(value, statement.line()));
                case "BLKSIZE" -> writeInt(block, 62, 2, absolute(value, statement.line()));
                case "MACRF" -> {
                    String macro = value.replace("(", "").replace(")", "")
                            .toUpperCase(Locale.ROOT);
                    for (String part : macro.split(",")) {
                        // G / P の次の字が M なら移動モード、L なら位置づけモード
                        int mode = 0x20 | (part.contains("L") ? 0x08 : 0x10);
                        if (part.startsWith("G")) {
                            block[50] = (byte) mode;
                        } else if (part.startsWith("P")) {
                            block[51] = (byte) mode;
                        }
                    }
                }
                case "EODAD", "SYNAD" -> {
                    int field = keyword.equals("EODAD") ? 33 : 57;
                    Value exit = evaluate(value, statement.line());
                    writeInt(block, field, 3, exit.value());
                    if (exit.isRelocatable()) {
                        relocations.add(new ObjectModule.Relocation(start + field, 3));
                    }
                }
                // 配置と編成の指定。順編成しか持たないので、読むだけである
                case "DSORG", "BUFNO", "OPTCD", "EROPT", "BFTEK", "BFALN" -> { }
                default -> throw new AssemblyException(statement.line(),
                        "DCB keyword " + keyword + " is not supported yet");
            }
        }
        write(start, block);
        section.advance(DCB_LENGTH);
        listing.add(new ObjectModule.Line(statement.line(), start, block));
    }

    /**
     * {@code OPEN (dcb,OUTPUT),...} と {@code CLOSE (dcb),...}。
     *
     * <p>DCB ごとに {@code LA 0,開き方} / {@code LA 1,dcb} / {@code SVC 19} を出す。開き方は
     * 0 が INPUT、1 が OUTPUT、2 が EXTEND である。{@code CLOSE} は {@code LA 1,dcb} /
     * {@code SVC 20}。IBM の展開は引数表を命令の並びに挟むが、ここでは挟まない。
     */
    private void openClose(Statement statement, boolean open) {
        String label = statement.label();
        List<String> operands = statement.operandList();
        if (operands.isEmpty()) {
            throw new AssemblyException(statement.line(),
                    statement.operation() + " requires a DCB");
        }
        for (String operand : operands) {
            String text = operand.trim();
            if (text.startsWith("(") && text.endsWith(")")) {
                text = text.substring(1, text.length() - 1);
            }
            List<String> parts = Statement.split(text, statement.line());
            for (int k = 0; k < parts.size(); k += open ? 2 : 1) {
                String dcb = parts.get(k).trim();
                if (open) {
                    String option = k + 1 < parts.size()
                            ? parts.get(k + 1).replace("(", "").replace(")", "").trim()
                                    .toUpperCase(Locale.ROOT) : "INPUT";
                    int mode = switch (option) {
                        case "", "INPUT" -> 0;
                        case "OUTPUT" -> 1;
                        case "EXTEND" -> 2;
                        default -> throw new AssemblyException(statement.line(),
                                "OPEN option " + option + " is not supported yet");
                    };
                    machine(new Statement(label, "LA", "0," + mode, statement.line()));
                    label = null;
                }
                machine(new Statement(label, "LA", "1," + dcb, statement.line()));
                label = null;
                machine(new Statement(null, "SVC", open ? "19" : "20", statement.line()));
            }
        }
    }

    /** {@code GET dcb,area} と {@code PUT dcb,area}。{@code LA 1,dcb} / {@code LA 0,area} / SVC。 */
    private void getPut(Statement statement, int svc) {
        List<String> operands = statement.operandList();
        if (operands.isEmpty() || operands.size() > 2) {
            throw new AssemblyException(statement.line(),
                    statement.operation() + " takes a DCB and an optional area");
        }
        machine(new Statement(statement.label(), "LA", "1," + operands.get(0).trim(),
                statement.line()));
        // 領域を書かないのは位置づけモードである。番地 0 で伝える
        machine(new Statement(null, "LA", "0," + (operands.size() == 2
                ? operands.get(1).trim() : "0"), statement.line()));
        machine(new Statement(null, "SVC", String.valueOf(svc), statement.line()));
    }

    // --- EQU / ORG / END ---

    private void equate(Statement statement) {
        if (statement.label() == null) {
            throw new AssemblyException(statement.line(), "EQU requires a name");
        }
        if (!statement.hasOperands()) {
            throw new AssemblyException(statement.line(), "EQU requires a value");
        }
        List<String> operands = statement.operandList();
        Value value = evaluate(operands.get(0), statement.line());
        int length = operands.size() > 1 && !operands.get(1).isBlank()
                ? absolute(operands.get(1), statement.line())
                : lengthAttributeOf(operands.get(0));
        if (!emitting) {
            define(statement.label(), new Symbol(statement.label(), value, length),
                    statement.line());
        }
    }

    private void org(Statement statement) {
        Section section = requireSection(statement.line());
        if (!statement.hasOperands()) {
            section.location = section.highWater;
            return;
        }
        Value value = evaluate(statement.operands(), statement.line());
        if (value.isAbsolute() || !value.section().equals(section.name)) {
            throw new AssemblyException(statement.line(),
                    "ORG requires an address in the current control section");
        }
        section.location = value.value();
        section.highWater = Math.max(section.highWater, section.location);
    }

    private void end(Statement statement) {
        placeLiterals(statement.line());
        if (statement.hasOperands()) {
            Value value = evaluate(statement.operands(), statement.line());
            if (value.isAbsolute() || !value.section().equals(mainSection.name)) {
                throw new AssemblyException(statement.line(),
                        "the END operand must name a place in the control section");
            }
            entryOffset = value.value();
        }
        ended = true;
    }

    // --- リテラル ---

    /**
     * 置き残したリテラルをまとめて置く。
     *
     * <p>同じ綴りのリテラルは 1 つにまとめる。HLASM がそうするためであり、
     * 別々に置くと組み立て表の変位が参照実装と合わなくなる。
     */
    private void placeLiterals(int line) {
        if (mainSection == null) {
            pendingLiterals.clear();
            return;
        }
        List<String> pool = emitting
                ? (poolIndex < pools.size() ? pools.get(poolIndex++) : List.<String>of())
                : List.copyOf(pendingLiterals);
        if (!emitting) {
            pools.add(pool);
        }
        pendingLiterals.clear();
        Section section = mainSection;
        for (String literal : pool) {
            Constants.Piece piece = Constants.parse(literal.substring(1), true, this, line);
            align(section, piece.alignment());
            if (!emitting) {
                placedLiterals.put(literal, section.location);
                section.advance(piece.totalLength());
                continue;
            }
            byte[] unit = piece.bytes();
            int start = section.location;
            for (int repeat = 0; repeat < piece.duplication(); repeat++) {
                // リテラルの番地定数 (=A(X)、=V(X)) も、DC と同じに値を入れて欄を控える
                resolveReferences(unit, piece, section.location, line);
                write(section.location, unit);
                section.advance(unit.length);
            }
            listing.add(new ObjectModule.Line(line, start,
                    Arrays.copyOfRange(text, start, section.location)));
        }
    }

    /**
     * 演算項に書かれたリテラルの綴り。リテラルでなければ {@code null}。
     *
     * <p>{@code =F'7'(R2)} のように指標が付くことがあるので、末尾の括弧は落とす。
     */
    static String literalTermOf(String operand) {
        String text = operand.trim();
        if (!text.startsWith("=")) {
            return null;
        }
        // 番地定数 (=A(X)、=V(X)、=Y(X)) の括弧は値である。指標ではない。
        // 以前はここで切っていたので、=V(ZHVRPT) が「=V」になり、値の無い定数として断っていた
        int at = 1;
        while (at < text.length() && Character.isDigit(text.charAt(at))) {
            at++;
        }
        if (at < text.length() && "AVY".indexOf(Character.toUpperCase(text.charAt(at))) >= 0) {
            int value = at + 1;
            if (value < text.length() && Character.toUpperCase(text.charAt(value)) == 'L') {
                value++;
                while (value < text.length() && Character.isDigit(text.charAt(value))) {
                    value++;
                }
            }
            if (value < text.length() && text.charAt(value) == '(') {
                int depth = 0;
                for (int k = value; k < text.length(); k++) {
                    if (text.charAt(k) == '(') {
                        depth++;
                    } else if (text.charAt(k) == ')' && --depth == 0) {
                        return text.substring(0, k + 1);
                    }
                }
            }
        }
        int open = topLevelParen(text);
        return open < 0 ? text : text.substring(0, open);
    }

    /** リテラルの綴りを記録し、その場所を指す値を返す。 */
    private Value literal(String operand, int line) {
        if (mainSection == null) {
            throw new AssemblyException(line,
                    "a literal appears before any control section: " + operand);
        }
        if (!emitting) {
            if (!pendingLiterals.contains(operand) && !placedLiterals.containsKey(operand)) {
                // 組み立てられるかどうかを、置くときではなく見つけたときに確かめる
                Constants.parse(operand.substring(1), true, this, line);
                pendingLiterals.add(operand);
            }
            // 1 周目は置き場が決まっていない。長さだけが要るので節の先頭を返す
            return Value.in(mainSection.name, 0);
        }
        Integer offset = placedLiterals.get(operand);
        if (offset == null) {
            throw new AssemblyException(line, "the literal has no place: " + operand);
        }
        return Value.in(mainSection.name, offset);
    }

    // --- 機械命令 ---

    private void machine(Statement statement) {
        Instructions.Definition definition = Instructions.find(statement.operation());
        if (definition == null) {
            throw new AssemblyException(statement.line(),
                    "unknown operation: " + statement.operation()
                            + " (a macro call would be expanded here; macros are not supported yet)");
        }
        Section section = requireSection(statement.line());
        // 機械命令は半語境界に置かれる
        align(section, 2);
        defineLabel(statement, definition.format().length());
        int start = section.location;
        if (!emitting) {
            // 1 周目は演算項を解かない (USING をまだ辿っていない)。リテラルだけは字面から拾う。
            // ここで拾わないと、プールの大きさが 1 周目に決まらず、あとに続く番地が全部ずれる
            for (String operand : statement.operandList()) {
                String term = literalTermOf(operand);
                if (term != null) {
                    literal(term, statement.line());
                }
            }
            section.advance(definition.format().length());
            return;
        }
        byte[] bytes = encode(definition, statement);
        write(start, bytes);
        section.advance(bytes.length);
        listing.add(new ObjectModule.Line(statement.line(), start, bytes));
    }

    private byte[] encode(Instructions.Definition definition, Statement statement) {
        List<String> operands = statement.operandList();
        int line = statement.line();
        return switch (definition.format()) {
            case I -> {
                require(operands, 1, statement);
                yield new byte[] {(byte) definition.opcode(),
                        (byte) checkRange(absolute(operands.get(0), line), 0, 255, line, "the SVC number")};
            }
            case RR -> {
                require(operands, 2, statement);
                yield rr(definition.opcode(), register(operands.get(0), line),
                        register(operands.get(1), line));
            }
            case RR_R1 -> {
                require(operands, 1, statement);
                yield rr(definition.opcode(), register(operands.get(0), line), 0);
            }
            case RRE_R1 -> {
                require(operands, 1, statement);
                yield new byte[] {(byte) (definition.opcode() >> 8), (byte) definition.opcode(), 0,
                        (byte) (register(operands.get(0), line) << 4)};
            }
            case RR_MASK -> {
                if (definition.mask() != null) {
                    require(operands, 1, statement);
                    yield rr(definition.opcode(), definition.mask(), register(operands.get(0), line));
                }
                require(operands, 2, statement);
                yield rr(definition.opcode(),
                        checkRange(absolute(operands.get(0), line), 0, 15, line, "the branch mask"),
                        register(operands.get(1), line));
            }
            case RX, RX_MASK -> {
                int first;
                String second;
                if (definition.mask() != null) {
                    require(operands, 1, statement);
                    first = definition.mask();
                    second = operands.get(0);
                } else {
                    require(operands, 2, statement);
                    first = definition.format() == Instructions.Format.RX_MASK
                            ? checkRange(absolute(operands.get(0), line), 0, 15, line,
                                    "the branch mask")
                            : register(operands.get(0), line);
                    second = operands.get(1);
                }
                Addr address = address(second, true, false, line);
                yield rx(definition.opcode(), first, address);
            }
            case RS -> {
                require(operands, 3, statement);
                Addr address = address(operands.get(2), false, false, line);
                yield rs(definition.opcode(), register(operands.get(0), line),
                        register(operands.get(1), line), address);
            }
            case RS_SHIFT -> {
                require(operands, 2, statement);
                Addr address = address(operands.get(1), false, false, line);
                yield rs(definition.opcode(), register(operands.get(0), line), 0, address);
            }
            case SI -> {
                require(operands, 2, statement);
                Addr address = address(operands.get(0), false, false, line);
                int immediate = checkRange(absolute(operands.get(1), line), 0, 255, line,
                        "the immediate operand");
                yield si(definition.opcode(), immediate, address);
            }
            case SS_A -> {
                require(operands, 2, statement);
                Addr first = address(operands.get(0), false, true, line);
                Addr second = address(operands.get(1), false, false, line);
                int length = first.length() == null ? lengthAttributeOf(operands.get(0))
                        : first.length();
                yield ss(definition.opcode(),
                        checkRange(length, 1, 256, line, "the length") - 1, first, second);
            }
            case SS_B -> {
                require(operands, 2, statement);
                Addr first = address(operands.get(0), false, true, line);
                Addr second = address(operands.get(1), false, true, line);
                int l1 = first.length() == null ? lengthAttributeOf(operands.get(0)) : first.length();
                int l2 = second.length() == null ? lengthAttributeOf(operands.get(1)) : second.length();
                int packed = (checkRange(l1, 1, 16, line, "the first length") - 1) << 4
                        | (checkRange(l2, 1, 16, line, "the second length") - 1);
                yield ss(definition.opcode(), packed, first, second);
            }
            case RRE -> {
                require(operands, 2, statement);
                yield new byte[] {(byte) (definition.opcode() >> 8), (byte) definition.opcode(), 0,
                        (byte) (register(operands.get(0), line) << 4
                                | register(operands.get(1), line))};
            }
            case RRF -> {
                require(operands, 3, statement);
                yield new byte[] {(byte) (definition.opcode() >> 8), (byte) definition.opcode(),
                        (byte) (register(operands.get(2), line) << 4),
                        (byte) (register(operands.get(0), line) << 4
                                | register(operands.get(1), line))};
            }
            case RI -> {
                require(operands, 2, statement);
                int immediate = checkRange(absolute(operands.get(1), line), -32768, 65535, line,
                        "the immediate operand");
                yield new byte[] {(byte) (definition.opcode() >> 8),
                        (byte) (register(operands.get(0), line) << 4
                                | (definition.opcode() & 0x0F)),
                        (byte) (immediate >> 8), (byte) immediate};
            }
            case RIL -> {
                require(operands, 2, statement);
                int immediate = absolute(operands.get(1), line);
                yield new byte[] {(byte) (definition.opcode() >> 8),
                        (byte) (register(operands.get(0), line) << 4
                                | (definition.opcode() & 0x0F)),
                        (byte) (immediate >> 24), (byte) (immediate >> 16),
                        (byte) (immediate >> 8), (byte) immediate};
            }
            case RXY -> {
                require(operands, 2, statement);
                yield longForm(definition.opcode(), register(operands.get(0), line),
                        operands.get(1), true, line, -1);
            }
            case RSY -> {
                // シフトは「R1,R3,D2(B2)」。ICMH は「R1,M3,D2(B2)」で、2 つ目がマスクである
                require(operands, 3, statement);
                yield longForm(definition.opcode(), register(operands.get(0), line),
                        operands.get(2), false, line, register(operands.get(1), line));
            }
            case RIE_F -> {
                if (operands.size() != 4 && operands.size() != 5) {
                    throw new AssemblyException(line, statement.operation()
                            + " takes four or five operands");
                }
                int i5 = operands.size() == 5
                        ? checkRange(absolute(operands.get(4), line), 0, 255, line,
                                "the rotate amount") : 0;
                yield new byte[] {(byte) (definition.opcode() >> 8),
                        (byte) (register(operands.get(0), line) << 4
                                | register(operands.get(1), line)),
                        (byte) checkRange(absolute(operands.get(2), line), 0, 255, line,
                                "the start bit"),
                        (byte) checkRange(absolute(operands.get(3), line), 0, 255, line,
                                "the end bit"),
                        (byte) i5, (byte) definition.opcode()};
            }
            case RSL -> {
                require(operands, 1, statement);
                Addr first = address(operands.get(0), false, true, line);
                int length = first.length() == null ? lengthAttributeOf(operands.get(0))
                        : first.length();
                yield new byte[] {(byte) (definition.opcode() >> 8),
                        (byte) ((checkRange(length, 1, 16, line, "the length") - 1) << 4),
                        (byte) (first.base() << 4 | first.displacement() >> 8),
                        (byte) first.displacement(), 0, (byte) definition.opcode()};
            }
            case S -> {
                require(operands, 1, statement);
                Addr address = address(operands.get(0), false, false, line);
                yield new byte[] {(byte) (definition.opcode() >> 8), (byte) definition.opcode(),
                        (byte) (address.base() << 4 | address.displacement() >> 8),
                        (byte) address.displacement()};
            }
            case E -> {
                require(operands, 0, statement);
                yield new byte[] {(byte) (definition.opcode() >> 8), (byte) definition.opcode()};
            }
            case SS_C -> {
                require(operands, 3, statement);
                Addr first = address(operands.get(0), false, true, line);
                Addr second = address(operands.get(1), false, false, line);
                int l1 = first.length() == null ? lengthAttributeOf(operands.get(0)) : first.length();
                int i3 = checkRange(absolute(operands.get(2), line), 0, 15, line,
                        "the rounding digit");
                int packed = (checkRange(l1, 1, 16, line, "the first length") - 1) << 4 | i3;
                yield ss(definition.opcode(), packed, first, second);
            }
        };
    }

    /**
     * 20 ビットの符号つき変位を持つ 6 バイトの命令 (RXY / RSY)。
     *
     * <p>変位の下位 12 ビットは 12 ビット変位の命令と同じ場所に、上位 8 ビットは 5 バイト目に
     * 入る。USING から解くときも、届く範囲が -524288〜524287 に広がる。
     *
     * @param third RSY の 2 つ目の欄 (R3 か M3)。RXY なら -1 で、その場所には指標が入る
     */
    private byte[] longForm(int opcode, int first, String operand, boolean indexed, int line,
                            int third) {
        Addr address;
        longDisplacement = true;
        try {
            address = address(operand, indexed, false, line);
        } finally {
            longDisplacement = false;
        }
        int displacement = address.displacement() & 0xFFFFF;
        return new byte[] {(byte) (opcode >> 8),
                (byte) (first << 4 | (third >= 0 ? third : address.index())),
                (byte) (address.base() << 4 | (displacement >> 8 & 0x0F)),
                (byte) displacement, (byte) (displacement >> 12), (byte) opcode};
    }

    private static byte[] rr(int opcode, int first, int second) {
        return new byte[] {(byte) opcode, (byte) (first << 4 | second)};
    }

    private static byte[] rx(int opcode, int first, Addr address) {
        return new byte[] {(byte) opcode, (byte) (first << 4 | address.index()),
                (byte) (address.base() << 4 | address.displacement() >> 8),
                (byte) address.displacement()};
    }

    private static byte[] rs(int opcode, int first, int third, Addr address) {
        return new byte[] {(byte) opcode, (byte) (first << 4 | third),
                (byte) (address.base() << 4 | address.displacement() >> 8),
                (byte) address.displacement()};
    }

    private static byte[] si(int opcode, int immediate, Addr address) {
        return new byte[] {(byte) opcode, (byte) immediate,
                (byte) (address.base() << 4 | address.displacement() >> 8),
                (byte) address.displacement()};
    }

    private static byte[] ss(int opcode, int lengths, Addr first, Addr second) {
        return new byte[] {(byte) opcode, (byte) lengths,
                (byte) (first.base() << 4 | first.displacement() >> 8),
                (byte) first.displacement(),
                (byte) (second.base() << 4 | second.displacement() >> 8),
                (byte) second.displacement()};
    }

    private void require(List<String> operands, int count, Statement statement) {
        if (operands.size() != count) {
            throw new AssemblyException(statement.line(), statement.operation() + " requires "
                    + count + " operand(s) but " + operands.size() + " were given");
        }
    }

    private static int checkRange(int value, int low, int high, int line, String what) {
        if (value < low || value > high) {
            throw new AssemblyException(line,
                    what + " must be between " + low + " and " + high + " but is " + value);
        }
        return value;
    }

    // --- 番地の解決 ---

    /**
     * 番地の演算項を解く。
     *
     * <p>括弧があれば明示形 ({@code D(X,B)} / {@code D(L,B)} / {@code D(B)} / {@code S(X)} /
     * {@code S(L)})、無ければ暗黙形であり {@code USING} から解く。
     */
    private Addr address(String operand, boolean allowIndex, boolean allowLength, int line) {
        String text = operand.trim();
        int open = topLevelParen(text);
        String term = literalTermOf(text);
        if (term != null && term.length() > open && open >= 0) {
            // 番地定数のリテラル。括弧は値であり、そのうしろに指標が付くことがある
            Value value = evaluate(term, line);
            String rest = text.substring(term.length()).trim();
            int index = 0;
            if (rest.startsWith("(") && rest.endsWith(")") && allowIndex) {
                index = register(rest.substring(1, rest.length() - 1), line);
            } else if (!rest.isEmpty()) {
                throw new AssemblyException(line, "unexpected text after the literal: " + operand);
            }
            return resolve(value, index, null, line, term);
        }
        if (open < 0) {
            Value value = evaluate(text, line);
            return resolve(value, 0, null, line, text);
        }
        if (!text.endsWith(")")) {
            throw new AssemblyException(line, "unexpected text after the address: " + operand);
        }
        String head = text.substring(0, open);
        List<String> inside = Statement.split(text.substring(open + 1, text.length() - 1), line);
        if (inside.size() == 2) {
            // 明示形。変位は絶対値でなければならない
            int displacement = checkRange(absolute(head, line), minimumDisplacement(),
                    maximumDisplacement(), line,
                    "the displacement");
            int base = register(inside.get(1), line);
            if (allowLength) {
                return new Addr(displacement, base, 0, absolute(inside.get(0), line));
            }
            if (!allowIndex) {
                throw new AssemblyException(line, "this operand takes no index register: " + operand);
            }
            int index = inside.get(0).isBlank() ? 0 : register(inside.get(0), line);
            return new Addr(displacement, base, index, null);
        }
        if (inside.size() != 1) {
            throw new AssemblyException(line, "unexpected address form: " + operand);
        }
        String only = inside.get(0);
        if (allowLength) {
            // S(L) — 長さだけを明示した暗黙形
            Value value = evaluate(head, line);
            return resolve(value, 0, absolute(only, line), line, head);
        }
        if (allowIndex) {
            // S(X) — 指標だけを明示した暗黙形。変位が絶対値ならベースは 0 になる
            Value value = evaluate(head, line);
            return resolve(value, register(only, line), null, line, head);
        }
        // D(B) — ベースだけを明示した形
        int displacement = checkRange(absolute(head, line), minimumDisplacement(),
                    maximumDisplacement(), line,
                "the displacement");
        return new Addr(displacement, register(only, line), 0, null);
    }

    /** 最上位の開き括弧の位置。引用符の中は数えない。無ければ -1。 */
    private static int topLevelParen(String text) {
        boolean quoted = false;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == '\'' && Quotes.isDelimiter(text, k, quoted)) {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                return k;
            }
        }
        return -1;
    }

    /**
     * 値を変位とベースへ解く。
     *
     * <p>絶対値はそのまま変位になり、ベースは 0 である。再配置可能な値は {@code USING} から
     * 届くものを探す。届くものが 2 つ以上あれば変位の小さいほうを選び、同じなら番号の大きい
     * レジスタを選ぶ。これは HLASM の選び方である。
     */
    private Addr resolve(Value value, int index, Integer length, int line, String text) {
        if (value.isAbsolute()) {
            return new Addr(checkRange(value.value(), minimumDisplacement(),
                    maximumDisplacement(), line,
                    "the displacement"), 0, index, length);
        }
        int bestRegister = -1;
        int bestDisplacement = Integer.MAX_VALUE;
        for (int register = 0; register < usings.length; register++) {
            Using using = usings[register];
            if (using == null || !using.section().equals(value.section())) {
                continue;
            }
            int displacement = value.value() - using.offset();
            if (displacement < minimumDisplacement() || displacement > maximumDisplacement()) {
                continue;
            }
            if (displacement < 0 && bestRegister >= 0) {
                // 負の変位で届くのは 20 ビット変位の命令だけ。正で届くものがあればそちらを使う
                continue;
            }
            if (displacement >= 0 && bestDisplacement < 0) {
                bestDisplacement = Integer.MAX_VALUE;
            }
            if (displacement < bestDisplacement
                    || (displacement == bestDisplacement && register > bestRegister)) {
                bestDisplacement = displacement;
                bestRegister = register;
            }
        }
        if (bestRegister < 0) {
            throw new AssemblyException(line,
                    "no USING register covers " + text + " in section " + value.section());
        }
        return new Addr(bestDisplacement, bestRegister, index, length);
    }

    private int minimumDisplacement() {
        return longDisplacement ? -0x80000 : 0;
    }

    private int maximumDisplacement() {
        return longDisplacement ? 0x7FFFF : MAX_DISPLACEMENT;
    }

    private int register(String operand, int line) {
        return checkRange(absolute(operand, line), 0, 15, line, "the register number");
    }

    // --- 記号と式 ---

    private void defineLabel(Statement statement, int length) {
        if (statement.label() == null || emitting) {
            return;
        }
        Section section = current;
        Value value = section == null ? Value.absolute(0)
                : Value.in(section.name, section.location);
        define(statement.label(), new Symbol(statement.label(), value, length), statement.line());
    }

    private void define(String name, Symbol symbol, int line) {
        if (symbols.putIfAbsent(name, symbol) != null) {
            throw new AssemblyException(line, "the symbol is defined twice: " + name);
        }
    }

    private Value evaluate(String text, int line) {
        String trimmed = text.trim();
        if (trimmed.startsWith("=")) {
            return literal(trimmed, line);
        }
        Value counter = current == null ? null : Value.in(current.name, current.location);
        return Expressions.evaluate(trimmed, counter, symbols::get, codePage, line);
    }

    private int absolute(String text, int line) {
        Value value = evaluate(text, line);
        if (!value.isAbsolute()) {
            throw new AssemblyException(line, "an absolute expression is required here: " + text);
        }
        return value.value();
    }

    /**
     * 式の長さ属性。先頭の項が記号ならその長さ属性、そうでなければ 1 である。
     * 明示長のない SS 形式の長さがこれで決まる。
     */
    private int lengthAttributeOf(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("=")) {
            // リテラルの長さ属性は、そのリテラルが組み立てる長さである
            Constants.Piece piece = Constants.parse(trimmed.substring(1), true, this, 0);
            return piece.length();
        }
        int at = 0;
        if (at < trimmed.length() && Expressions.isNameStart(trimmed.charAt(at))) {
            int start = at;
            while (at < trimmed.length() && Expressions.isNamePart(trimmed.charAt(at))) {
                at++;
            }
            Symbol symbol = symbols.get(trimmed.substring(start, at).toUpperCase(Locale.ROOT));
            if (symbol != null) {
                return symbol.length();
            }
        }
        return 1;
    }

    private void write(int offset, byte[] bytes) {
        System.arraycopy(bytes, 0, text, offset, bytes.length);
    }
}
