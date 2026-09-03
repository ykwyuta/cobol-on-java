package dev.cobolonjava.compiler.parser;

import dev.cobolonjava.compiler.source.Origin;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

/**
 * 構文解析器の誤りを集め、元のソース上の位置へ戻す (要件 FR-183, FR-094)。
 *
 * <p>ANTLR が渡してくる行と桁は使わない。<b>トークンが持つ出自を使う</b>。
 * ANTLR の位置は 1 本の文字列を前提にしているが、{@code COPY} を展開した
 * トークン列は複数のファイルにまたがるためである。
 */
public final class DiagnosticListener extends BaseErrorListener {

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    public List<Diagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    public boolean hasErrors() {
        return !diagnostics.isEmpty();
    }

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                            int line, int charPositionInLine, String message,
                            RecognitionException e) {
        diagnostics.add(new Diagnostic(originOf(offendingSymbol), message));
    }

    private static Origin originOf(Object offendingSymbol) {
        return offendingSymbol instanceof OriginToken token ? token.origin() : null;
    }
}
