package dev.cobolonjava.compiler.parser;

import dev.cobolonjava.compiler.source.CopyBookResolver;
import dev.cobolonjava.compiler.source.Preprocessor;
import dev.cobolonjava.compiler.source.SourceReader;
import dev.cobolonjava.compiler.source.SourceToken;
import java.util.List;
import org.antlr.v4.runtime.CommonTokenStream;

/**
 * 構文解析の入口 (方針 ARC-8)。
 *
 * <p>プリプロセッサからトークン列を受け取り、構文木を作る。誤りは例外にせず
 * {@link Diagnostic} として集める。<b>1 つ目の誤りで止まると、移行作業で使い物にならない</b>。
 */
public final class CobolParsing {

    private CobolParsing() {
    }

    /**
     * 構文解析の結果。
     *
     * @param tree        構文木。誤りがあっても、ANTLR が回復した範囲では作られる
     * @param diagnostics 見つかった誤り。空なら成功
     */
    public record Result(CobolParser.CompilationUnitContext tree, List<Diagnostic> diagnostics) {

        public boolean succeeded() {
            return diagnostics.isEmpty();
        }
    }

    /** トークン列を構文解析する。 */
    public static Result parse(List<SourceToken> tokens) {
        CobolParser parser = new CobolParser(new CommonTokenStream(new SourceTokenSource(tokens)));
        DiagnosticListener listener = new DiagnosticListener();
        parser.removeErrorListeners();
        parser.addErrorListener(listener);
        CobolParser.CompilationUnitContext tree = parser.compilationUnit();
        return new Result(tree, listener.diagnostics());
    }

    /** ソースをプリプロセッサに通してから構文解析する。 */
    public static Result parse(Preprocessor preprocessor, String fileName, String source) {
        return parse(preprocessor.tokenize(fileName, source));
    }

    /** コピー句と参照形式を指定してソースを構文解析する。 */
    public static Result parse(CopyBookResolver resolver, SourceReader reader,
                               String fileName, String source) {
        return parse(Preprocessor.with(resolver, reader), fileName, source);
    }
}
