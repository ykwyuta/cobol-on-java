package dev.cobolonjava.compiler.parser;

import dev.cobolonjava.compiler.source.SourceToken;
import dev.cobolonjava.compiler.source.SourceTokenKind;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenFactory;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenFactory;
import org.antlr.v4.runtime.TokenSource;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.misc.Pair;

/**
 * プリプロセッサのトークン列を ANTLR の構文解析器へ渡す (方針 ARC-8)。
 *
 * <p>構文解析器は字句解析器を持たない。カラムも継続行も {@code COPY} も「島」も、
 * ここへ来る前に消えている。この橋渡しがやることは 2 つだけである。
 *
 * <h2>予約語を見分ける</h2>
 * <p>{@link SourceTokenKind#WORD} は予約語と利用者定義語の両方を含む。文法が
 * {@code tokens} で宣言した名前を引き、当たれば予約語、当たらなければ
 * {@code IDENTIFIER} とする。<b>COBOL 語のハイフンは下線に読み替える</b> ({@code COMP-3}
 * は {@code COMP_3})。照合は大文字と小文字を区別しない。
 *
 * <p>予約語の表を文法から引き出しているのは、<b>表と文法がずれないようにする</b>ためである。
 * 別に持つと、文法へ語を足したときに表の更新を忘れる。
 *
 * <h2>出自を持ち回る</h2>
 * <p>トークンには {@link OriginToken} として元の {@link SourceToken} を持たせる。
 * ANTLR の行と桁は 1 本の文字列を前提にしているが、こちらは {@code COPY} で
 * 複数のファイルにまたがる。診断はファイル名まで含めて元へ戻せなければならない (要件 FR-094)。
 */
public final class SourceTokenSource implements TokenSource {

    /** 数字定数の綴り。COBOL は小数点の前に必ず数字を要求する。 */
    private static final Pattern NUMERIC = Pattern.compile("[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");

    /**
     * 文法が宣言しているが予約語ではない名前。区切り文字、島、そして語の種別そのものである。
     * これらを予約語として引くと、{@code NUMBER} という名前のデータ項目が
     * 数字定数になってしまう。
     */
    private static final Set<String> NOT_RESERVED_WORDS = Set.of(
            "PERIOD", "COMMA", "SEMICOLON", "LPAREN", "RPAREN",
            "PICTURE_STRING", "EXEC_BLOCK",
            "IDENTIFIER", "LITERAL", "NUMBER");

    private static final Map<String, Integer> RESERVED_WORDS = reservedWords(CobolParser.VOCABULARY);

    private final List<SourceToken> tokens;
    private final Pair<TokenSource, CharStream> stream = new Pair<>(this, null);
    private int index;
    private TokenFactory<?> factory = CommonTokenFactory.DEFAULT;

    public SourceTokenSource(List<SourceToken> tokens) {
        this.tokens = List.copyOf(tokens);
    }

    /** 文法が宣言した予約語の表。COBOL の綴り (ハイフン) から字句の種別を引く。 */
    private static Map<String, Integer> reservedWords(Vocabulary vocabulary) {
        Map<String, Integer> words = new HashMap<>();
        for (int type = 1; type <= vocabulary.getMaxTokenType(); type++) {
            String name = vocabulary.getSymbolicName(type);
            if (name == null || NOT_RESERVED_WORDS.contains(name)) {
                continue;
            }
            words.put(name, type);
        }
        return Map.copyOf(words);
    }

    @Override
    public Token nextToken() {
        if (index >= tokens.size()) {
            return endOfFile();
        }
        SourceToken source = tokens.get(index++);
        return new OriginToken(stream, typeOf(source), source);
    }

    private Token endOfFile() {
        SourceToken last = tokens.isEmpty() ? null : tokens.get(tokens.size() - 1);
        return new OriginToken(stream, Token.EOF, last, "<EOF>");
    }

    /** トークンの種別を決める。予約語かどうかの判別はここだけで行う。 */
    private static int typeOf(SourceToken token) {
        return switch (token.kind()) {
            case LITERAL -> CobolParser.LITERAL;
            case PICTURE_STRING -> CobolParser.PICTURE_STRING;
            case EXEC_BLOCK -> CobolParser.EXEC_BLOCK;
            case SEPARATOR -> separatorType(token);
            case WORD -> wordType(token.text());
        };
    }

    private static int separatorType(SourceToken token) {
        return switch (token.text().charAt(0)) {
            case '.' -> CobolParser.PERIOD;
            case ',' -> CobolParser.COMMA;
            case ';' -> CobolParser.SEMICOLON;
            case '(' -> CobolParser.LPAREN;
            case ')' -> CobolParser.RPAREN;
            default -> throw new IllegalStateException("unexpected separator: " + token);
        };
    }

    private static int wordType(String text) {
        Integer reserved = RESERVED_WORDS.get(text.toUpperCase(Locale.ROOT).replace('-', '_'));
        if (reserved != null) {
            return reserved;
        }
        return NUMERIC.matcher(text).matches() ? CobolParser.NUMBER : CobolParser.IDENTIFIER;
    }

    // ---- TokenSource の残り。文字の流れを持たないため、位置は OriginToken が担う ----

    @Override
    public int getLine() {
        return index < tokens.size() ? tokens.get(index).origin().line() : 0;
    }

    @Override
    public int getCharPositionInLine() {
        return index < tokens.size() ? tokens.get(index).origin().column() - 1 : 0;
    }

    @Override
    public CharStream getInputStream() {
        return null;
    }

    @Override
    public String getSourceName() {
        return tokens.isEmpty() ? "<empty>" : tokens.get(0).origin().fileName();
    }

    @Override
    public void setTokenFactory(TokenFactory<?> tokenFactory) {
        this.factory = tokenFactory;
    }

    @Override
    public TokenFactory<?> getTokenFactory() {
        return factory;
    }
}
