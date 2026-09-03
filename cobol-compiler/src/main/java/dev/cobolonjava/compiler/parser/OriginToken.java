package dev.cobolonjava.compiler.parser;

import dev.cobolonjava.compiler.source.Origin;
import dev.cobolonjava.compiler.source.SourceToken;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonToken;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenSource;
import org.antlr.v4.runtime.misc.Pair;

/**
 * 出自を持ち回るトークン (要件 FR-094)。
 *
 * <p>ANTLR の行と桁は 1 本の文字列を前提にしている。COBOL は {@code COPY} で
 * 複数のファイルにまたがるため、<b>ファイル名まで含めて</b>元へ戻せなければならない。
 * 元の {@link SourceToken} をそのまま持たせて、診断のときに引く。
 */
public final class OriginToken extends CommonToken {

    private final transient SourceToken source;

    OriginToken(Pair<TokenSource, CharStream> stream, int type, SourceToken source) {
        this(stream, type, source, source.text());
    }

    /**
     * <p>字句の出どころ ({@code stream}) を持たせるのは、ANTLR の誤り回復が
     * 「足りないトークン」を作るときにこれを辿るためである。持たせないとそこで落ちる。
     */
    OriginToken(Pair<TokenSource, CharStream> stream, int type, SourceToken source, String text) {
        super(stream, type, Token.DEFAULT_CHANNEL, 0, 0);
        this.source = source;
        setText(text);
        if (source != null) {
            setLine(source.origin().line());
            setCharPositionInLine(source.origin().column() - 1);
        }
    }

    /** 元のトークン。ソースの終わりを表すものでは、最後のトークンになる。 */
    public SourceToken source() {
        return source;
    }

    /** 元のソース上の位置。トークンがなければ {@code null}。 */
    public Origin origin() {
        return source == null ? null : source.origin();
    }
}
