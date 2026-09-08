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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
        for (CobolParser.ProgramUnitContext unit : parsed.tree().programUnit()) {
            Result one = compile(unit, fileName, effective);
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

    /** プログラム 1 本を翻訳する。 */
    private Result compile(CobolParser.ProgramUnitContext program, String fileName,
                           CompilerOptions effective) {
        // 告げるだけの診断は段をまたいで積む。止めるものが出たところで打ち切る
        List<Diagnostic> warnings = new ArrayList<>();

        // 環境部を先に読む。PICTURE の解釈が通貨記号に依るためである
        SpecialNames.Result environment = SpecialNames.build(program);
        if (!environment.succeeded()) {
            return failed(null, environment.diagnostics());
        }
        warnings.addAll(environment.diagnostics());
        SpecialNames specialNames = environment.specialNames();

        DataDivisionBuilder.Result data = DataDivisionBuilder.build(program, specialNames);
        if (!data.succeeded()) {
            return failed(data.layout(), data.diagnostics());
        }
        warnings.addAll(data.diagnostics());

        // SELECT と FD は離れて書かれる。両方を読み終えてから突き合わせる
        List<Diagnostic> fileDiagnostics = new ArrayList<>();
        FileDescription.Result declared = FileDescription.build(program,
                FileDescription.select(program, fileDiagnostics), data.fileRecords(),
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

        ProgramGenerator.Result generated = ProgramGenerator.generate(
                programNameOf(program), fileName, procedure, image, data.layout(),
                effective, specialNames);
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
