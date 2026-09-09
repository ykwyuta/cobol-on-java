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
import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.compiler.source.CopyBookResolver;
import dev.cobolonjava.compiler.source.Preprocessor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
                         List<Diagnostic> diagnostics, List<Compiled> programs) {

        /**
         * 1 本だけのとき。
         *
         * <p>{@code className} と {@code classFile} は<b>最初のプログラム</b>のものである。
         * 1 本しか書かれていないソースが大多数なので、呼ぶ側はこれだけを見ればよい。
         */
        public Result(String className, byte[] classFile, DataLayout layout,
                      List<Diagnostic> diagnostics) {
            this(className, classFile, layout, diagnostics,
                    className == null ? List.of()
                            : List.of(new Compiled(className, classFile, layout)));
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
    public record Compiled(String className, byte[] classFile, DataLayout layout) {
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
        CobolParsing.Result parsed = CobolParsing.parse(preprocessor, fileName, source);
        if (!parsed.succeeded()) {
            return failed(null, parsed.diagnostics());
        }
        List<Compiled> programs = new ArrayList<>();
        // 告げるだけの診断は翻訳を止めない。積んでおいて結果に載せる (要件 FR-183)
        List<Diagnostic> warnings = new ArrayList<>(parsed.diagnostics());
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
            programs.add(new Compiled(one.className(), one.classFile(), one.layout()));
        }
        if (programs.isEmpty()) {
            return failed(null, List.of(new Diagnostic(null, "no program unit in " + fileName)));
        }
        Compiled first = programs.get(0);
        return new Result(first.className(), first.classFile(), first.layout(),
                List.copyOf(warnings), List.copyOf(programs));
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

        ProgramGenerator.Result generated = ProgramGenerator.generate(
                programNameOf(program), fileName, procedure, image, data.layout(),
                effective, specialNames, visible);
        if (!generated.succeeded()) {
            return failed(data.layout(), generated.diagnostics());
        }
        warnings.addAll(generated.diagnostics());
        return new Result(generated.className(), generated.classFile(), data.layout(),
                List.copyOf(warnings));
    }

    private static Result failed(DataLayout layout, List<Diagnostic> diagnostics) {
        return new Result(null, null, layout, List.copyOf(diagnostics));
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
