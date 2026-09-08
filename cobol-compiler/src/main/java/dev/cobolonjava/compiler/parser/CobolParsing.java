package dev.cobolonjava.compiler.parser;

import dev.cobolonjava.compiler.source.CopyBookResolver;
import dev.cobolonjava.compiler.source.Preprocessor;
import dev.cobolonjava.compiler.source.SourceFormatException;
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
        CobolParser parser = new CobolParser(new CommonTokenStream(
                new SourceTokenSource(tokens, commaDecimalPoint(tokens))));
        DiagnosticListener listener = new DiagnosticListener();
        parser.removeErrorListeners();
        parser.addErrorListener(listener);
        CobolParser.CompilationUnitContext tree = parser.compilationUnit();
        return new Result(tree, listener.diagnostics());
    }

    /**
     * {@code DECIMAL-POINT IS COMMA} が書かれているか (要件 FR-054)。
     *
     * <p>字句の読み方が変わるので、<b>構文解析より前に</b>知らなければならない。
     * 3 語が続いているところを探すだけでよい — この綴びはほかの意味を持たない。
     */
    private static boolean commaDecimalPoint(List<SourceToken> tokens) {
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (word(tokens, i, "DECIMAL-POINT") && word(tokens, i + 1, "IS")
                    && word(tokens, i + 2, "COMMA")) {
                return true;
            }
        }
        return false;
    }

    private static boolean word(List<SourceToken> tokens, int at, String spelling) {
        return tokens.get(at).kind() == dev.cobolonjava.compiler.source.SourceTokenKind.WORD
                && tokens.get(at).text().equalsIgnoreCase(spelling);
    }

    /**
     * ソースをプリプロセッサに通してから構文解析する。
     *
     * <p>プリプロセッサは読めない原文に出会うと例外で止まる。行を継ぎ、写し句を展開し、
     * 語へ切る流れ作業なので、途中から先のトークン列が作れないからである。
     *
     * <p>ここで受け止めて<b>診断へ変える</b>。呼ぶ側は診断を求めているのだから、例外が
     * 表へ出てはならない。出ていると、読めなかったのか処理系が壊れたのかを呼ぶ側が
     * 区別できない (暫定判断 P-062)。
     */
    public static Result parse(Preprocessor preprocessor, String fileName, String source) {
        List<SourceToken> tokens;
        try {
            tokens = preprocessor.tokenize(fileName, source);
        } catch (SourceFormatException unreadable) {
            return new Result(null,
                    List.of(new Diagnostic(unreadable.origin(), unreadable.detail())));
        }
        return parse(tokens);
    }

    /** コピー句と参照形式を指定してソースを構文解析する。 */
    public static Result parse(CopyBookResolver resolver, SourceReader reader,
                               String fileName, String source) {
        return parse(Preprocessor.with(resolver, reader), fileName, source);
    }
}
