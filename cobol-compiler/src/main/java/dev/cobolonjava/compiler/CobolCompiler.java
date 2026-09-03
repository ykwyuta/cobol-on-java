package dev.cobolonjava.compiler;

import dev.cobolonjava.compiler.codegen.ProgramGenerator;
import dev.cobolonjava.compiler.parser.CobolParser;
import dev.cobolonjava.compiler.parser.CobolParsing;
import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.semantic.DataDivisionBuilder;
import dev.cobolonjava.compiler.semantic.DataLayout;
import dev.cobolonjava.compiler.semantic.InitialImage;
import dev.cobolonjava.compiler.semantic.ProcedureBuilder;
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

    public CobolCompiler(Preprocessor preprocessor) {
        this.preprocessor = preprocessor;
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
                         List<Diagnostic> diagnostics) {

        public boolean succeeded() {
            return diagnostics.isEmpty();
        }
    }

    /** ソースを翻訳する。 */
    public Result compile(String fileName, String source) {
        CobolParsing.Result parsed = CobolParsing.parse(preprocessor, fileName, source);
        if (!parsed.succeeded()) {
            return failed(null, parsed.diagnostics());
        }

        DataDivisionBuilder.Result data = DataDivisionBuilder.build(parsed.tree());
        if (!data.succeeded()) {
            return failed(data.layout(), data.diagnostics());
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        InitialImage.Result image = InitialImage.build(data.layout());
        diagnostics.addAll(image.diagnostics());
        ProcedureBuilder.Result procedure = ProcedureBuilder.build(parsed.tree(), data.layout());
        diagnostics.addAll(procedure.diagnostics());
        if (!diagnostics.isEmpty()) {
            return failed(data.layout(), diagnostics);
        }

        ProgramGenerator.Result generated =
                ProgramGenerator.generate(programNameOf(parsed.tree()), procedure, image);
        if (!generated.succeeded()) {
            return failed(data.layout(), generated.diagnostics());
        }
        return new Result(generated.className(), generated.classFile(), data.layout(), List.of());
    }

    private static Result failed(DataLayout layout, List<Diagnostic> diagnostics) {
        return new Result(null, null, layout, List.copyOf(diagnostics));
    }

    /** 最初のプログラムの名前。文字定数で書かれていれば引用符を外す。 */
    private static String programNameOf(CobolParser.CompilationUnitContext tree) {
        String name = tree.programUnit(0).identificationDivision().programIdParagraph()
                .programName().getText();
        if (name.length() >= 2 && (name.charAt(0) == '\'' || name.charAt(0) == '"')) {
            name = name.substring(1, name.length() - 1);
        }
        return name.toUpperCase(Locale.ROOT);
    }
}
