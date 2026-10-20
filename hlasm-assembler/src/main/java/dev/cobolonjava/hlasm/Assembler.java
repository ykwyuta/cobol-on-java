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
    /** 長い変位 (符号付き 20 ビット) の範囲。 */
    private static final int MIN_LONG_DISPLACEMENT = -0x80000;
    private static final int MAX_LONG_DISPLACEMENT = 0x7FFFF;

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
    /** 載せる側が直す番地定数。2 周目で集める。 */
    private final List<ObjectModule.Relocation> relocations = new ArrayList<>();

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
            // DCB / OPEN / PUT / CLOSE はライブラリのマクロが無ければ組み込みの展開を使う
            List<Statement> statements = SystemMacros.expand(expansion.statements());
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
                entryOffset, symbols, dummies, listing, relocations);
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
            case "ENTRY", "EXTRN", "WXTRN", "COM", "RSECT", "CNOP", "COPY", "ICTL", "OPSYN" ->
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
        int start = emitPiece(section, piece, statement.line());
        listing.add(new ObjectModule.Line(statement.line(), start,
                Arrays.copyOfRange(text, start, section.location)));
    }

    /**
     * 定数 1 つを反復係数の数だけ置き、番地定数の値を入れる。置いた先頭の変位を返す。
     *
     * <p>番地定数は節の先頭からの変位で組み立て、載せる側が直す場所として {@link #relocations} に
     * 残す。以前は変位のまま置くだけで、実行時に {@code DC A(X)} を番地として使うと、節を置いた番地を
     * 足していない値を読んでいた。
     */
    private int emitPiece(Section section, Constants.Piece piece, int line) {
        byte[] unit = piece.bytes();
        int start = section.location;
        for (int repeat = 0; repeat < piece.duplication(); repeat++) {
            for (Constants.AddressReference reference : piece.references()) {
                int at = section.location + reference.offset();
                if (reference.external()) {
                    writeInt(unit, reference.offset(), reference.length(), 0);
                    relocations.add(new ObjectModule.Relocation(at, reference.length(),
                            reference.expression()));
                    continue;
                }
                Value value = evaluate(reference.expression(), line);
                if (value.isRelocatable()) {
                    relocatable(value, reference, line);
                    relocations.add(new ObjectModule.Relocation(at, reference.length(), null));
                }
                writeInt(unit, reference.offset(), reference.length(), value.value());
            }
            write(section.location, unit);
            section.advance(unit.length);
        }
        return start;
    }

    /**
     * 番地を持つ値を番地定数に置けるかを確かめる。
     *
     * <p>ダミー節の記号は番地ではなく、節の先頭からの変位でしかない (載せる側が置く節がない)。
     * 3 バイトより短い定数は、載せた番地が入りきらない。どちらも黙って変位のまま置くと、組み立ては
     * 通って実行時に違う番地を読むので断る。
     */
    private void relocatable(Value value, Constants.AddressReference reference, int line) {
        Section target = sections.get(value.section());
        if (target != null && target.dummy) {
            throw new AssemblyException(line, "an address constant cannot point into the dummy section "
                    + value.section() + ": " + reference.expression());
        }
        if (reference.length() < 3) {
            throw new AssemblyException(line, "a relocatable address needs a constant of 3 or 4 bytes: "
                    + reference.expression());
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
            // リテラルの番地定数 (=A(X)、=V(Y)) も値を入れる。以前は 0 のまま置いていた
            int start = emitPiece(section, piece, line);
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
        int open = topLevelParen(text);
        if (open >= 0 && text.substring(0, open).toUpperCase(Locale.ROOT).matches("=\\d*[AVY](L\\d+)?")) {
            // =A(X) / =V(X) の括弧は定数の値である。指標の括弧はその後ろにしか来ない
            int close = closingParen(text, open);
            int next = close < 0 ? -1 : topLevelParen(text.substring(close + 1));
            return next < 0 ? text : text.substring(0, close + 1 + next);
        }
        return open < 0 ? text : text.substring(0, open);
    }

    /** {@code open} の括弧に対応する閉じ括弧。引用符の中は数えない。無ければ -1。 */
    private static int closingParen(String text, int open) {
        boolean quoted = false;
        int depth = 0;
        for (int k = open; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == '\'' && Quotes.isDelimiter(text, k, quoted)) {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')' && --depth == 0) {
                return k;
            }
        }
        return -1;
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
        if (PAIRED.contains(definition.mnemonic()) && !operands.isEmpty()) {
            evenRegister(register(operands.get(0), line), statement);
        }
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
            case E -> {
                require(operands, 0, statement);
                yield new byte[] {(byte) (definition.opcode() >> 8), (byte) definition.opcode()};
            }
            case RS_MASK -> {
                require(operands, 3, statement);
                Addr address = address(operands.get(2), false, false, line);
                yield rs(definition.opcode(), register(operands.get(0), line), mask(operands.get(1), line),
                        address);
            }
            case RI -> {
                require(operands, 2, statement);
                int immediate = checkRange(absolute(operands.get(1), line), Short.MIN_VALUE, Short.MAX_VALUE,
                        line, "the immediate operand");
                yield new byte[] {(byte) (definition.opcode() >> 4),
                        (byte) (register(operands.get(0), line) << 4 | definition.opcode() & 0xF),
                        (byte) (immediate >> 8), (byte) immediate};
            }
            case RIL -> {
                require(operands, 2, statement);
                // 32 ビットの即値は、符号付きでも符号なしでも書ける (IILF X'FFFFFFFF' と IILF -1 は同じ)
                long immediate = immediate32(operands.get(1), line);
                yield new byte[] {(byte) (definition.opcode() >> 4),
                        (byte) (register(operands.get(0), line) << 4 | definition.opcode() & 0xF),
                        (byte) (immediate >> 24), (byte) (immediate >> 16), (byte) (immediate >> 8),
                        (byte) immediate};
            }
            case RRE -> {
                require(operands, 2, statement);
                int r1 = register(operands.get(0), line);
                if (definition.mnemonic().equals("FLOGR")) {
                    evenRegister(r1, statement);
                }
                yield rre(definition.opcode(), 0, r1, register(operands.get(1), line));
            }
            case RRF_M3 -> {
                if (operands.size() != 2) {
                    require(operands, 3, statement);
                }
                int m3 = operands.size() == 3 ? mask(operands.get(2), line) : 0;
                yield rre(definition.opcode(), m3 << 4, register(operands.get(0), line),
                        register(operands.get(1), line));
            }
            case RRF_R3 -> {
                require(operands, 3, statement);
                yield rre(definition.opcode(), register(operands.get(2), line) << 4,
                        register(operands.get(0), line), register(operands.get(1), line));
            }
            case RXY -> {
                require(operands, 2, statement);
                Addr address = address(operands.get(1), true, false, true, line);
                yield longForm(definition.opcode(), register(operands.get(0), line), address.index(), address);
            }
            case RSY, RSY_MASK -> {
                require(operands, 3, statement);
                Addr address = address(operands.get(2), false, false, true, line);
                int third = definition.format() == Instructions.Format.RSY_MASK
                        ? mask(operands.get(1), line) : register(operands.get(1), line);
                yield longForm(definition.opcode(), register(operands.get(0), line), third, address);
            }
            case RIE_F -> {
                if (operands.size() != 4) {
                    require(operands, 5, statement);
                }
                int i3 = checkRange(absolute(operands.get(2), line), 0, 255, line, "the third operand");
                int i4 = checkRange(absolute(operands.get(3), line), 0, 255, line, "the fourth operand");
                int i5 = operands.size() == 5
                        ? checkRange(absolute(operands.get(4), line), 0, 255, line, "the fifth operand") : 0;
                yield new byte[] {(byte) (definition.opcode() >> 8),
                        (byte) (register(operands.get(0), line) << 4 | register(operands.get(1), line)),
                        (byte) i3, (byte) i4, (byte) i5, (byte) definition.opcode()};
            }
            case RSL -> {
                require(operands, 1, statement);
                Addr first = address(operands.get(0), false, true, line);
                int l1 = first.length() == null ? lengthAttributeOf(operands.get(0)) : first.length();
                int length = checkRange(l1, 1, 16, line, "the length");
                yield new byte[] {(byte) (definition.opcode() >> 8), (byte) ((length - 1) << 4),
                        (byte) (first.base() << 4 | first.displacement() >> 8), (byte) first.displacement(),
                        0, (byte) definition.opcode()};
            }
        };
    }

    /** 第 1 演算項が偶数・奇数のレジスタの対を指す命令 (Principles of Operation)。 */
    private static final java.util.Set<String> PAIRED = java.util.Set.of(
            "MR", "M", "DR", "D", "SLDL", "SRDL", "SLDA", "SRDA");

    /** 4 ビットのマスク。 */
    private int mask(String operand, int line) {
        return checkRange(absolute(operand, line), 0, 15, line, "the mask");
    }

    /**
     * 偶数・奇数の対を使う命令の第 1 演算項。
     *
     * <p>HLASM は奇数のレジスタを組み立ての時点で誤りにする (HLASM Language Reference の診断 ASMA029E
     * "Incorrect register specification")。以前は組み立てを通し、実行時に仕様例外としていた。
     */
    private static void evenRegister(int register, Statement statement) {
        if (register % 2 != 0) {
            throw new AssemblyException(statement.line(), statement.operation()
                    + " requires an even register for the register pair but R" + register + " was given");
        }
    }

    private long immediate32(String operand, int line) {
        String text = operand.trim();
        Value value = evaluate(text, line);
        if (!value.isAbsolute()) {
            throw new AssemblyException(line, "an absolute expression is required here: " + operand);
        }
        return value.value() & 0xFFFFFFFFL;
    }

    private static byte[] rre(int opcode, int third, int first, int second) {
        return new byte[] {(byte) (opcode >> 8), (byte) opcode, (byte) third, (byte) (first << 4 | second)};
    }

    /** RXY / RSY 形式。変位は下位 12 ビット (DL) と上位 8 ビット (DH) に分けて置く。 */
    private static byte[] longForm(int opcode, int first, int second, Addr address) {
        int displacement = address.displacement();
        return new byte[] {(byte) (opcode >> 8), (byte) (first << 4 | second),
                (byte) (address.base() << 4 | (displacement >> 8) & 0xF), (byte) displacement,
                (byte) (displacement >> 12), (byte) opcode};
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
        return address(operand, allowIndex, allowLength, false, line);
    }

    /**
     * @param longDisplacement 変位が符号付き 20 ビット (RXY / RSY 形式) なら真
     */
    private Addr address(String operand, boolean allowIndex, boolean allowLength, boolean longDisplacement,
                         int line) {
        int low = longDisplacement ? MIN_LONG_DISPLACEMENT : 0;
        int high = longDisplacement ? MAX_LONG_DISPLACEMENT : MAX_DISPLACEMENT;
        String text = operand.trim();
        String literal = literalTermOf(text);
        // リテラルの値の括弧 (=V(X)) を指標の括弧と読まない
        int open = literal == null ? topLevelParen(text)
                : literal.length() < text.length() ? literal.length() : -1;
        if (open < 0) {
            Value value = evaluate(text, line);
            return resolve(value, 0, null, high, line, text);
        }
        if (!text.endsWith(")")) {
            throw new AssemblyException(line, "unexpected text after the address: " + operand);
        }
        String head = text.substring(0, open);
        List<String> inside = Statement.split(text.substring(open + 1, text.length() - 1), line);
        if (inside.size() == 2) {
            // 明示形。変位は絶対値でなければならない
            int displacement = checkRange(absolute(head, line), low, high, line,
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
            return resolve(value, 0, absolute(only, line), high, line, head);
        }
        if (allowIndex) {
            // S(X) — 指標だけを明示した暗黙形。変位が絶対値ならベースは 0 になる
            Value value = evaluate(head, line);
            return resolve(value, register(only, line), null, high, line, head);
        }
        // D(B) — ベースだけを明示した形
        int displacement = checkRange(absolute(head, line), low, high, line,
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
    private Addr resolve(Value value, int index, Integer length, int high, int line, String text) {
        if (value.isAbsolute()) {
            return new Addr(checkRange(value.value(), 0, high, line,
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
            if (displacement < 0 || displacement > high) {
                continue;
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
