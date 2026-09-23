package dev.cobolonjava.compiler;

import dev.cobolonjava.compiler.codegen.ProgramGenerator;
import dev.cobolonjava.compiler.parser.CobolParser;
import dev.cobolonjava.compiler.parser.CobolParsing;
import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.semantic.DataDivisionBuilder;
import dev.cobolonjava.compiler.semantic.DataLayout;
import dev.cobolonjava.compiler.semantic.FileDescription;
import dev.cobolonjava.compiler.semantic.InitialImage;
import dev.cobolonjava.compiler.semantic.ProcedureBuilder;
import dev.cobolonjava.compiler.semantic.ReferenceResolver;
import dev.cobolonjava.compiler.semantic.SpecialNames;
import dev.cobolonjava.compiler.semantic.Statement;
import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.compiler.source.CopyBookResolver;
import dev.cobolonjava.compiler.source.Preprocessor;
import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.interop.ProgramParameter;
import dev.cobolonjava.runtime.interop.ProgramSignature;
import dev.cobolonjava.runtime.procedure.ProcedureDescriptor;
import dev.cobolonjava.runtime.procedure.ProcedureId;
import dev.cobolonjava.runtime.procedure.ProcedureKind;
import dev.cobolonjava.runtime.procedure.ProcedureManifest;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 翻訳の入口。ソース 1 本からクラスファイルまでを通す。
 *
 * <h2>段を分けて、途中で止める</h2>
 * <p>構文解析 → データ部の割り付け → 初期イメージ → 手続き部 → コード生成の順に進み、
 * <b>誤りが出た段でそこから先へ進まない</b>。割り付けが決まっていない状態で
 * 手続き部を解いても、意味のない誤りが山のように出るだけである。
 */
public final class CobolCompiler {

    private final Preprocessor preprocessor;
    private final CompilerOptions options;

    public CobolCompiler(Preprocessor preprocessor) {
        this(preprocessor, CompilerOptions.NONE);
    }

    public CobolCompiler(Preprocessor preprocessor, CompilerOptions options) {
        this.preprocessor = preprocessor;
        this.options = options;
    }

    /**
     * 起動時に与える翻訳時オプションを差し替えた構成を返す (要件 FR-093)。
     *
     * <p>ソースの {@code CBL} / {@code PROCESS} に書かれた指定のほうが<b>あとに重なる</b>。
     * ソースに書いた指定が起動時の指定を上書きするのが参照実装の規則である。
     */
    public CobolCompiler withOptions(CompilerOptions values) {
        return new CobolCompiler(preprocessor, values);
    }

    /** コピー句を持たない構成。 */
    public static CobolCompiler standard() {
        return new CobolCompiler(Preprocessor.withoutCopybooks());
    }

    /** コピー句を解決する構成。 */
    public static CobolCompiler with(CopyBookResolver resolver) {
        return new CobolCompiler(Preprocessor.with(resolver));
    }

    /**
     * 翻訳の結果。
     *
     * @param className   生成したクラスの名前。誤りがあれば {@code null}
     * @param classFile   クラスファイルの中身。誤りがあれば {@code null}
     * @param layout      データ部の割り付け。構文解析に失敗すれば {@code null}
     * @param diagnostics 見つかった誤り。空なら成功
     */
    public record Result(String className, byte[] classFile, DataLayout layout,
                         ProgramSignature programSignature,
                         ProcedureManifest procedureManifest,
                         List<Diagnostic> diagnostics, List<Compiled> programs) {

        /**
         * 1 本だけのとき。
         *
         * <p>{@code className} と {@code classFile} は<b>最初のプログラム</b>のものである。
         * 1 本しか書かれていないソースが大多数なので、呼ぶ側はこれだけを見ればよい。
         */
        public Result(String className, byte[] classFile, DataLayout layout,
                      ProgramSignature programSignature,
                      ProcedureManifest procedureManifest, List<Diagnostic> diagnostics) {
            this(className, classFile, layout, programSignature, procedureManifest, diagnostics,
                    className == null ? List.of()
                            : List.of(new Compiled(className, classFile, layout,
                                    programSignature, procedureManifest)));
        }

        /** 手続きmanifestだけを持つ移行途中の呼出側向け互換入口。 */
        public Result(String className, byte[] classFile, DataLayout layout,
                      ProcedureManifest procedureManifest, List<Diagnostic> diagnostics) {
            this(className, classFile, layout, null, procedureManifest, diagnostics);
        }

        /**
         * manifest導入前の呼出側と、翻訳結果を合成する検証コード向けの互換入口。
         * 実際のコンパイラが成功結果を返す場合はmanifest付きの入口を使う。
         */
        public Result(String className, byte[] classFile, DataLayout layout,
                      List<Diagnostic> diagnostics) {
            this(className, classFile, layout, null, null, diagnostics,
                    className == null ? List.of()
                            : List.of(new Compiled(className, classFile, layout, null, null)));
        }

        public boolean succeeded() {
            return !Diagnostic.blocking(diagnostics);
        }
    }

    /**
     * 翻訳できたプログラム 1 本。
     *
     * <p>1 本のソースに何本あってもよい。{@code END PROGRAM} で区切って並べる書き方で
     * ある。
     */
    public record Compiled(String className, byte[] classFile, DataLayout layout,
                           ProgramSignature programSignature,
                           ProcedureManifest procedureManifest) {

        /** ABI署名導入前の呼出側向け互換入口。 */
        public Compiled(String className, byte[] classFile, DataLayout layout,
                        ProcedureManifest procedureManifest) {
            this(className, classFile, layout, null, procedureManifest);
        }
    }

    /**
     * ソースを翻訳する。
     *
     * <p>1 本のソースに<b>プログラムが何本あってもよい</b>。{@code END PROGRAM} で区切って
     * 並べる書き方であり、実資産にも NIST の検査スイートにもある。1 本ずつ別々に翻訳し、
     * <b>プログラムの数だけクラスを出す</b>。
     *
     * <p>まとめて 1 つの割り付けにしてはならない。名前は<b>プログラムごとに独立</b>で
     * あり、同じ名前の {@code FD} が 2 本あっても互いに関わりがないからである。
     * まとめると、関わりのない重なりを誤りとして報せてしまう。
     */
    public Result compile(String fileName, String source) {
        CompilerOptions effective = options.merge(preprocessor.optionsOf(source));
        // 効かないオプションを黙って受理しない (要件 FR-181)
        List<Diagnostic> optionDiagnostics = OptionSupport.check(effective);
        if (Diagnostic.blocking(optionDiagnostics)) {
            return failed(null, optionDiagnostics);
        }
        CobolParsing.Result parsed = CobolParsing.parse(preprocessor, fileName, source);
        if (!parsed.succeeded()) {
            return failed(null, parsed.diagnostics());
        }
        List<Compiled> programs = new ArrayList<>();
        // 告げるだけの診断は翻訳を止めない。積んでおいて結果に載せる (要件 FR-183)
        List<Diagnostic> warnings = new ArrayList<>(optionDiagnostics);
        warnings.addAll(parsed.diagnostics());
        List<Inherited> inherited = inheritedGlobals(parsed.tree().programUnit());
        // 囲む側の USE GLOBAL 宣言節。親は子より先に翻訳されるので、順に積める
        Map<String, List<ProcedureBuilder.GlobalDeclarative>> globals = new LinkedHashMap<>();
        int at = 0;
        for (CobolParser.ProgramUnitContext unit : parsed.tree().programUnit()) {
            Result one = compile(unit, fileName, effective, inherited.get(at++), globals);
            if (!one.succeeded()) {
                return one;
            }
            warnings.addAll(one.diagnostics());
            programs.add(new Compiled(one.className(), one.classFile(), one.layout(),
                    one.programSignature(), one.procedureManifest()));
        }
        if (programs.isEmpty()) {
            return failed(null, List.of(new Diagnostic(null, "no program unit in " + fileName)));
        }
        Compiled first = programs.get(0);
        return new Result(first.className(), first.classFile(), first.layout(),
                first.programSignature(), first.procedureManifest(), List.copyOf(warnings),
                List.copyOf(programs));
    }

    /**
     * 1 本のプログラムが囲む側から引き継ぐもの (要件 FR-091)。
     *
     * @param data    作業場所の {@code GLOBAL} 01 レベル
     * @param files   {@code FD ... GLOBAL} の記述項
     * @param selects その {@code FD} と対になる {@code SELECT}
     */
    private record Inherited(List<DataDivisionBuilder.InheritedGlobal> data,
                             List<DataDivisionBuilder.InheritedFile> files,
                             List<CobolParser.SelectEntryContext> selects,
                             List<String> ancestors) {
    }

    /**
     * 入れ子の関係を組み立て、各プログラムが囲む側から引き継ぐ {@code GLOBAL} を数え上げる
     * (要件 FR-091、暫定判断 P-070)。
     *
     * <p>文法は入れ子を持たない。並んだプログラムとして読み、{@code END PROGRAM} の名前で
     * <b>あとから木を作る</b>。ある本の {@code END PROGRAM} が続けて何枚も来れば、
     * その枚数だけ囲みが閉じたということである。
     *
     * <pre>
     * PROGRAM-ID. A.        → 積む [A]
     * PROGRAM-ID. A-1.      → 積む [A, A-1]   … A-1 は A に囲まれている
     * END PROGRAM A-1.      → 下ろす [A]
     * END PROGRAM A.        → 下ろす []
     * </pre>
     *
     * @return プログラムの並び順に、そのプログラムが引き継ぐ {@code GLOBAL} の記述項
     */
    private static List<Inherited> inheritedGlobals(
            List<CobolParser.ProgramUnitContext> units) {
        List<Inherited> out = new ArrayList<>();
        // 囲んでいるプログラムの、名前と GLOBAL の記述項
        Deque<DataDivisionBuilder.InheritedGlobal> open = new ArrayDeque<>();
        Deque<DataDivisionBuilder.InheritedFile> openFiles = new ArrayDeque<>();
        Deque<OpenSelect> openSelects = new ArrayDeque<>();
        Deque<String> names = new ArrayDeque<>();
        for (CobolParser.ProgramUnitContext unit : units) {
            // いま積まれているものが、このプログラムから見える
            out.add(new Inherited(outermostFirst(open), outermostFirst(openFiles),
                    outermostFirst(openSelects).stream().map(OpenSelect::entry).toList(),
                    outermostFirst(names)));

            String name = programNameOf(unit);
            names.push(name);
            for (List<CobolParser.DataDescriptionEntryContext> group : globalEntriesOf(unit)) {
                open.push(new DataDivisionBuilder.InheritedGlobal(name, group));
            }
            for (CobolParser.FileDescriptionEntryContext fd : globalFilesOf(unit)) {
                openFiles.push(new DataDivisionBuilder.InheritedFile(name, fd));
                CobolParser.SelectEntryContext select =
                        selectOf(unit, fd.IDENTIFIER().getText());
                if (select != null) {
                    openSelects.push(new OpenSelect(name, select));
                }
            }
            // END PROGRAM の枚数だけ囲みが閉じる
            for (int i = 0; i < unit.endProgramStatement().size() && !names.isEmpty(); i++) {
                String closed = names.pop();
                while (!open.isEmpty() && closed.equalsIgnoreCase(open.peek().owner())) {
                    open.pop();
                }
                while (!openFiles.isEmpty() && closed.equalsIgnoreCase(openFiles.peek().owner())) {
                    openFiles.pop();
                }
                while (!openSelects.isEmpty()
                        && closed.equalsIgnoreCase(openSelects.peek().owner())) {
                    openSelects.pop();
                }
            }
        }
        return out;
    }

    /** 積まれたものを<b>外側から</b>並べ直す。 */
    private static <T> List<T> outermostFirst(Deque<T> stack) {
        List<T> out = new ArrayList<>(stack);
        java.util.Collections.reverse(out);
        return List.copyOf(out);
    }

    /** 積んでいる {@code SELECT} と、その持ち主。 */
    private record OpenSelect(String owner, CobolParser.SelectEntryContext entry) {
    }

    /**
     * そのプログラムが書いた {@code USE GLOBAL} 宣言節 (要件 FR-091)。
     *
     * <p>段落の番号は<b>そのプログラムの並び</b>での番号である。囲まれた側はこの番号で
     * 呼ぶ。番号の付け方は生成側と同じでなければならない——書かれた順である。
     */
    private static List<ProcedureBuilder.GlobalDeclarative> globalDeclarativesOf(
            String owner, ProcedureBuilder.Result procedure) {
        List<String> names = new ArrayList<>();
        for (ProcedureBuilder.Paragraph paragraph : procedure.paragraphs()) {
            names.add(paragraph.name());
        }
        List<ProcedureBuilder.GlobalDeclarative> out = new ArrayList<>();
        for (ProcedureBuilder.Declarative declarative : procedure.declaratives()) {
            if (!declarative.global()) {
                continue;
            }
            int from = names.indexOf(declarative.first());
            int through = names.indexOf(declarative.last());
            if (from < 0 || through < 0) {
                continue;
            }
            out.add(new ProcedureBuilder.GlobalDeclarative(owner, from, through,
                    declarative.files().stream().map(FileDescription::name).toList(),
                    declarative.mode()));
        }
        return List.copyOf(out);
    }

    /** そのプログラムが {@code GLOBAL} と書いた {@code FD}。 */
    private static List<CobolParser.FileDescriptionEntryContext> globalFilesOf(
            CobolParser.ProgramUnitContext unit) {
        List<CobolParser.FileDescriptionEntryContext> out = new ArrayList<>();
        if (unit.dataDivision() == null) {
            return out;
        }
        for (CobolParser.DataDivisionSectionContext section : unit.dataDivision().dataDivisionSection()) {
            if (section.fileSection() == null) {
                continue;
            }
            for (CobolParser.FileDescriptionEntryContext fd : section.fileSection().fileDescriptionEntry()) {
                if (DataDivisionBuilder.isGlobalFile(fd)) {
                    out.add(fd);
                }
            }
        }
        return out;
    }

    /** その名前の {@code SELECT}。書かれていなければ {@code null}。 */
    private static CobolParser.SelectEntryContext selectOf(CobolParser.ProgramUnitContext unit,
                                                           String fileName) {
        if (unit.environmentDivision() == null
                || unit.environmentDivision().inputOutputSection() == null
                || unit.environmentDivision().inputOutputSection().fileControlParagraph() == null) {
            return null;
        }
        for (CobolParser.SelectEntryContext entry : unit.environmentDivision()
                .inputOutputSection().fileControlParagraph().selectEntry()) {
            if (entry.IDENTIFIER().getText().equalsIgnoreCase(fileName)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * そのプログラムが {@code GLOBAL} と書いた 01 レベルと、その配下。
     *
     * <p>配下の項目は文法の上では<b>並んだ記述項</b>である。次の 01 か 77 が来るまでが
     * 1 つの塊になる。
     */
    private static List<List<CobolParser.DataDescriptionEntryContext>> globalEntriesOf(
            CobolParser.ProgramUnitContext unit) {
        List<List<CobolParser.DataDescriptionEntryContext>> out = new ArrayList<>();
        if (unit.dataDivision() == null) {
            return out;
        }
        for (CobolParser.DataDivisionSectionContext section : unit.dataDivision().dataDivisionSection()) {
            if (section.workingStorageSection() == null) {
                continue;
            }
            List<CobolParser.DataDescriptionEntryContext> group = null;
            for (CobolParser.DataDescriptionEntryContext entry
                    : section.workingStorageSection().dataDescriptionEntry()) {
                if (startsRecord(entry)) {
                    group = isGlobalRecord(entry) ? new ArrayList<>() : null;
                    if (group != null) {
                        group.add(entry);
                        out.add(group);
                    }
                    continue;
                }
                if (group != null) {
                    group.add(entry);
                }
            }
        }
        return out;
    }

    /** 記憶域の先頭から始まる記述項 (01 または 77) か。 */
    private static boolean startsRecord(CobolParser.DataDescriptionEntryContext entry) {
        String level = entry.levelNumber().getText();
        return "01".equals(level) || "1".equals(level) || "77".equals(level);
    }

    /** {@code GLOBAL} と書かれた記述項か。 */
    private static boolean isGlobalRecord(CobolParser.DataDescriptionEntryContext entry) {
        for (CobolParser.DataClauseContext clause : entry.dataClause()) {
            if (clause.globalClause() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 数字項目の桁数の上限 (要件 FR-041)。
     *
     * <p>{@code ARITH(COMPAT)} では 18 桁、{@code ARITH(EXTEND)} では 31 桁である。2 進
     * ({@code COMP} / {@code COMP-5}) の 19 桁以上は、ここより前に項目の長さを決めるところで
     * 断っている (暫定判断 P-005)。以前は上限を調べず、19 桁以上の 10 進の項目を黙って受け取っていた。
     * COMPAT のまま 19 桁以上を書いた原文は実機では翻訳できないので、ここでも断る。
     */
    /**
     * 固定小数点の数字定数の桁数の上限。{@code ARITH(COMPAT)} で 18、{@code ARITH(EXTEND)} で 31
     * (IBM Enterprise COBOL の {@code ARITH} オプションの説明)。浮動小数点の定数 ({@code 1.5E10})
     * は数えない。
     */
    private static List<Diagnostic> literalLimits(org.antlr.v4.runtime.tree.ParseTree tree,
                                                  CompilerOptions options) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        collectLiteralLimits(tree, options.maximumNumericDigits(),
                options.extendedArithmetic() ? "ARITH(EXTEND)" : "ARITH(COMPAT)", diagnostics);
        return diagnostics;
    }

    private static void collectLiteralLimits(org.antlr.v4.runtime.tree.ParseTree tree, int limit,
                                             String arith, List<Diagnostic> diagnostics) {
        if (tree instanceof org.antlr.v4.runtime.tree.TerminalNode terminal) {
            var token = terminal.getSymbol();
            String text = token.getText();
            if (token.getType() == CobolParser.NUMBER && text.indexOf('E') < 0
                    && text.indexOf('e') < 0) {
                long digits = text.chars().filter(Character::isDigit).count();
                if (digits > limit) {
                    diagnostics.add(new Diagnostic(token instanceof dev.cobolonjava.compiler.parser.OriginToken origin
                            ? origin.origin() : null, "the numeric literal "
                            + text + " has " + digits + " digits; the maximum is " + limit
                            + " under " + arith));
                }
            }
            return;
        }
        for (int k = 0; k < tree.getChildCount(); k++) {
            collectLiteralLimits(tree.getChild(k), limit, arith, diagnostics);
        }
    }

    private static List<Diagnostic> digitLimits(DataLayout layout, CompilerOptions options) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        String arith = options.extendedArithmetic() ? "ARITH(EXTEND)" : "ARITH(COMPAT)";
        for (var item : layout.all()) {
            var picture = item.picture();
            if (picture == null || !(picture.isNumeric() || picture.isNumericEdited())) {
                continue;
            }
            int limit = options.maximumNumericDigits();
            if (picture.digits() > limit) {
                diagnostics.add(new Diagnostic(item.origin(), "the PICTURE of " + item.name()
                        + " has " + picture.digits() + " digits; the maximum is " + limit
                        + " under " + arith));
            }
        }
        return diagnostics;
    }

    /** プログラム 1 本を翻訳する。 */
    private Result compile(CobolParser.ProgramUnitContext program, String fileName,
                           CompilerOptions effective, Inherited inherited,
                           Map<String, List<ProcedureBuilder.GlobalDeclarative>> globals) {
        // 告げるだけの診断は段をまたいで積む。止めるものが出たところで打ち切る
        List<Diagnostic> warnings = new ArrayList<>();

        // 環境部を先に読む。PICTURE の解釈が通貨記号に依るためである
        SpecialNames.Result environment = SpecialNames.build(program);
        if (!environment.succeeded()) {
            return failed(null, environment.diagnostics());
        }
        warnings.addAll(environment.diagnostics());
        SpecialNames specialNames = environment.specialNames();

        DataDivisionBuilder.Result data = DataDivisionBuilder.build(program, specialNames,
                programNameOf(program), inherited.data(), inherited.files());
        if (!data.succeeded()) {
            return failed(data.layout(), data.diagnostics());
        }
        List<Diagnostic> limits = digitLimits(data.layout(), effective);
        limits.addAll(literalLimits(program, effective));
        if (Diagnostic.blocking(limits)) {
            return failed(data.layout(), limits);
        }
        warnings.addAll(data.diagnostics());

        // SELECT と FD は離れて書かれる。両方を読み終えてから突き合わせる
        List<Diagnostic> fileDiagnostics = new ArrayList<>();
        FileDescription.Result declared = FileDescription.build(program,
                FileDescription.select(program, inherited.selects(), fileDiagnostics),
                data.fileRecords(),
                new ReferenceResolver(data.layout(), fileDiagnostics), fileDiagnostics);
        if (!declared.succeeded()) {
            return failed(data.layout(), declared.diagnostics());
        }
        warnings.addAll(declared.diagnostics());

        List<Diagnostic> diagnostics = new ArrayList<>();
        InitialImage.Result image = InitialImage.build(data.layout(), specialNames);
        diagnostics.addAll(image.diagnostics());
        ProcedureBuilder.Result procedure = ProcedureBuilder.build(program, data.layout(),
                specialNames, declared.files(), data.reports());
        diagnostics.addAll(procedure.diagnostics());
        if (Diagnostic.blocking(diagnostics)) {
            return failed(data.layout(), diagnostics);
        }
        warnings.addAll(diagnostics);

        // 囲む側が書いた USE GLOBAL は、こちらに受け持ちがなければこちらでも動く
        List<ProcedureBuilder.GlobalDeclarative> visible = new ArrayList<>();
        for (String ancestor : inherited.ancestors()) {
            visible.addAll(globals.getOrDefault(ancestor.toUpperCase(Locale.ROOT), List.of()));
        }
        globals.put(programNameOf(program).toUpperCase(Locale.ROOT),
                globalDeclarativesOf(programNameOf(program), procedure));

        ProgramSignature signature = programSignature(programNameOf(program), procedure,
                ProcedureBuilder.implicitUsing(program.procedureDivision()));
        ProcedureManifest manifest = procedureManifest(programNameOf(program), procedure);
        ProgramGenerator.Result generated = ProgramGenerator.generate(
                programNameOf(program), fileName, procedure, image, data.layout(),
                effective, specialNames, visible, signature, manifest);
        if (!generated.succeeded()) {
            return failed(data.layout(), generated.diagnostics());
        }
        warnings.addAll(generated.diagnostics());
        return new Result(generated.className(), generated.classFile(), data.layout(), signature,
                manifest, List.copyOf(warnings));
    }

    private static Result failed(DataLayout layout, List<Diagnostic> diagnostics) {
        return new Result(null, null, layout, null, null, List.copyOf(diagnostics));
    }

    /**
     * @param implicitUsing USING を書いていない。引数は CICS translator が補う DFHCOMMAREA だけである
     */
    private static ProgramSignature programSignature(
            String program, ProcedureBuilder.Result procedure, boolean implicitUsing) {
        List<ProgramParameter> parameters = procedure.parameters().stream()
                .map(item -> implicitUsing
                        // CICS は COMMAREA を渡さないこと (EIBCALEN=0) も、宣言と違う長さで渡すこともある。
                        // 範囲外を読んだときに失敗させ、入口では断らない (暫定判断 P-122)
                        ? new ProgramParameter(item.name(), 0, Short.MAX_VALUE,
                                ProgramParameter.Presence.OPTIONAL, ProgramParameter.PassingMode.REFERENCE,
                                ProgramParameter.Direction.INOUT, dataItemHash(item))
                        : ProgramParameter.fixedReference(
                                item.name(), item.totalLength(), dataItemHash(item)))
                .toList();
        return ProgramSignature.of(program, parameters);
    }

    /** 同じ全長でも子項目の境界やUSAGEが違うレイアウトを区別する。 */
    private static String dataItemHash(dev.cobolonjava.compiler.semantic.DataItem root) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLayoutHash(digest, "cobol-data-layout-v1\n");
            updateLayoutHash(digest, root, 0);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void updateLayoutHash(MessageDigest digest,
                                         dev.cobolonjava.compiler.semantic.DataItem item,
                                         int depth) {
        updateLayoutHash(digest, Integer.toString(depth));
        digest.update((byte) 0);
        updateLayoutHash(digest, item.name() == null ? "FILLER" : item.name());
        digest.update((byte) 0);
        updateLayoutHash(digest, Integer.toString(item.level()));
        digest.update((byte) ':');
        updateLayoutHash(digest, Integer.toString(item.offset()));
        digest.update((byte) ':');
        updateLayoutHash(digest, Integer.toString(item.length()));
        digest.update((byte) ':');
        updateLayoutHash(digest, Integer.toString(item.occurs()));
        digest.update((byte) 0);
        updateLayoutHash(digest, item.usage() == null ? "GROUP" : item.usage().name());
        digest.update((byte) 0);
        updateLayoutHash(digest, item.picture() == null ? "" : item.picture().source());
        digest.update((byte) 0);
        updateLayoutHash(digest, item.signPosition().name());
        digest.update((byte) '\n');
        for (dev.cobolonjava.compiler.semantic.DataItem child : item.children()) {
            updateLayoutHash(digest, child, depth + 1);
        }
    }

    private static void updateLayoutHash(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ProcedureManifest procedureManifest(
            String program, ProcedureBuilder.Result procedure) {
        ProgramId programId = ProgramId.of(program);
        List<ProcedureDescriptor> descriptors = new ArrayList<>();
        List<String> paragraphNames = procedure.paragraphs().stream()
                .map(ProcedureBuilder.Paragraph::name).toList();
        for (ProcedureBuilder.Section section : procedure.sections()) {
            int first = paragraphNames.indexOf(section.first());
            int last = paragraphNames.indexOf(section.last());
            if (first < 0 || last < first) {
                throw new IllegalStateException("invalid SECTION range: " + section.name());
            }
            dev.cobolonjava.compiler.source.Origin origin =
                    procedure.paragraphs().get(first).origin();
            String reason = section.declarative()
                    ? "declarative SECTIONs require their declared runtime condition"
                    : unsupportedDirectTransfer(procedure, first, last);
            descriptors.add(new ProcedureDescriptor(new ProcedureId(programId,
                    ProcedureKind.SECTION, section.name()), first, last, section.declarative(),
                    origin == null ? null : origin.fileName(), origin == null ? 0 : origin.line(),
                    reason == null, reason));
        }
        for (int i = 0; i < procedure.paragraphs().size(); i++) {
            ProcedureBuilder.Paragraph paragraph = procedure.paragraphs().get(i);
            // 見出しより前の文は内部的な匿名段落になる。外部から参照できる安定IDではない。
            if (paragraph.name() == null || paragraph.name().isBlank()) {
                continue;
            }
            dev.cobolonjava.compiler.source.Origin origin = paragraph.origin();
            descriptors.add(new ProcedureDescriptor(new ProcedureId(programId,
                    ProcedureKind.PARAGRAPH, paragraph.name()),
                    i, i, i < procedure.firstNormalParagraph(),
                    origin == null ? null : origin.fileName(), origin == null ? 0 : origin.line(),
                    false, "direct invocation is supported only for SECTIONs"));
        }
        return ProcedureManifest.of(program, descriptors);
    }

    /**
     * 初期版は非構造化transferを含むSECTIONを保守的に拒否する。
     * 局所GO TOを許す解析はcontrol-flow graphを導入する段で行う。
     */
    private static String unsupportedDirectTransfer(
            ProcedureBuilder.Result procedure, int first, int last) {
        for (int i = first; i <= last; i++) {
            ProcedureBuilder.Paragraph paragraph = procedure.paragraphs().get(i);
            String reason = unsupportedTransfer(paragraph.statements());
            if (reason == null) {
                reason = unsupportedTransfer(paragraph.debugEntry());
            }
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private static String unsupportedTransfer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Statement.GoTo statement) {
            return "contains GO TO at " + statement.origin();
        }
        if (value instanceof Statement.GoToDepending statement) {
            return "contains GO TO DEPENDING ON at " + statement.origin();
        }
        if (value instanceof Statement.Alter statement) {
            return "contains ALTER at " + statement.origin();
        }
        if (value instanceof Statement.NextSentence statement) {
            return "contains NEXT SENTENCE at " + statement.origin();
        }
        if (value instanceof Iterable<?> values) {
            for (Object one : values) {
                String reason = unsupportedTransfer(one);
                if (reason != null) {
                    return reason;
                }
            }
            return null;
        }
        Class<?> type = value.getClass();
        if (!type.isRecord() || !belongsToStatement(type)) {
            return null;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            try {
                String reason = unsupportedTransfer(component.getAccessor().invoke(value));
                if (reason != null) {
                    return reason;
                }
            } catch (ReflectiveOperationException impossible) {
                throw new IllegalStateException("cannot inspect " + type.getName(), impossible);
            }
        }
        return null;
    }

    private static boolean belongsToStatement(Class<?> type) {
        for (Class<?> owner = type.getEnclosingClass(); owner != null;
             owner = owner.getEnclosingClass()) {
            if (owner == Statement.class) {
                return true;
            }
        }
        return false;
    }

    /** プログラムの名前。文字定数で書かれていれば引用符を外す。 */
    private static String programNameOf(CobolParser.ProgramUnitContext program) {
        String name = program.identificationDivision().programIdParagraph()
                .programName().getText();
        if (name.length() >= 2 && (name.charAt(0) == '\'' || name.charAt(0) == '"')) {
            name = name.substring(1, name.length() - 1);
        }
        return name.toUpperCase(Locale.ROOT);
    }
}
