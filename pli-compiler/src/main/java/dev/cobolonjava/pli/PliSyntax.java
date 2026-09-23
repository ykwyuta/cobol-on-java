package dev.cobolonjava.pli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bank-of-Z が使う構造化 PL/I サブセットの字句解析と構文木。 */
final class PliSyntax {

    private PliSyntax() {
    }

    static ParseResult parse(String fileName, String source) {
        try {
            return new Parser(fileName, new Lexer(fileName, source).scan()).parse();
        } catch (ParseFailure failure) {
            return new ParseResult(null, List.of(new Diagnostic(Diagnostic.Severity.ERROR,
                    fileName, failure.token.line(), failure.token.column(), failure.getMessage())));
        }
    }

    record ParseResult(Program program, List<Diagnostic> diagnostics) {
        boolean succeeded() {
            return program != null && diagnostics.stream()
                    .noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
        }
    }

    /**
     * @param main {@code OPTIONS(MAIN)} を書いたか。主手続きの POINTER の引数は、渡された記憶域の
     *             番地を値に持つ (IMS の PCB の並び)。そうでない手続きの引数は参照で渡った変数そのもの
     */
    record Program(String name, List<String> parameters, List<Stmt> body,
                   Map<String, Procedure> procedures, boolean main) {
    }

    record Procedure(String name, List<String> parameters, List<Stmt> body) {
    }

    sealed interface Stmt permits Declare, Assign, Put, If, Loop, IterativeLoop, Call, Return,
            Block, GoTo, Label, OnEndFile, FileOperation, Sql {
    }

    record Declare(List<Decl> declarations) implements Stmt {
    }

    enum Type { GROUP, CHAR, BINARY, DECIMAL, BIT, POINTER, FILE, ENTRY, PICTURE }

    /**
     * 宣言 1 つ。
     *
     * @param aligned {@code ALIGNED} なら真、{@code UNALIGNED} なら偽、書かなければ {@code null}
     *                (型ごとの既定、構造からは受け継ぐ。LRM "ALIGNED and UNALIGNED attributes")
     * @param varying {@code CHAR(n) VARYING}。記憶域は長さの半語と n byte (LRM Table 39)
     * @param dimensions 配列の次元ごとの上下限。配列でなければ空
     * @param initialItems 配列の {@code INITIAL} の並び。スカラーなら空で、{@code initial} を使う
     */
    record Decl(String name, int level, Type type, int precision, int scale,
                Expr initial, String basedOn, Boolean aligned, boolean varying,
                List<Bound> dimensions, List<InitItem> initialItems) {

        /** 配列の要素の数。配列でなければ 1。 */
        int count() {
            int count = 1;
            for (Bound bound : dimensions) count *= bound.extent();
            return count;
        }
    }

    /** 配列の 1 つの次元の上下限 (LRM "DIMENSION attribute")。 */
    record Bound(int low, int high) {
        int extent() {
            return high - low + 1;
        }
    }

    /**
     * 配列の {@code INITIAL} の 1 項目。{@code (3)0} の 3 が反復の係数で、{@code (*)} は残りの
     * 要素すべて ({@code count} が 0)。
     */
    record InitItem(int count, Expr value) {
    }

    /**
     * 代入。{@code subscripts} は配列の要素の添字、または擬似変数 {@code SUBSTR(x, i, n)} の引数。
     * どちらでもなければ空である。
     */
    record Assign(String target, List<Expr> subscripts, Expr value) implements Stmt {
    }

    /**
     * {@code PUT} 文。出力先は SYSPRINT か、{@code STRING} に書いた文字の変数である。
     *
     * @param string {@code STRING(変数)} の変数名。SYSPRINT へ書くなら {@code null}
     * @param page   {@code PAGE} を書いたか
     * @param skip   {@code SKIP(n)} の n。書かなければ 0
     * @param edit   {@code EDIT} なら真、{@code LIST} (または値なし) なら偽
     * @param format {@code EDIT} の書式並び
     */
    record Put(String string, boolean page, int skip, boolean edit, List<Expr> values,
               List<FormatItem> format) implements Stmt {
    }

    /**
     * {@code EDIT} の書式項目。いまは {@code A} / {@code A(w)} / {@code X(w)} / {@code F(w)} /
     * {@code F(w,d)} だけを持つ。
     *
     * @param width    欄の幅。{@code A} で省いたときは -1
     * @param fraction {@code F} の小数の桁数。省けば 0
     */
    record FormatItem(char code, int width, int fraction) {
    }

    record If(Expr condition, Stmt whenTrue, Stmt whenFalse) implements Stmt {
    }

    /**
     * 繰り返す do-group (LRM "DO statement" の Type 2 と Type 4)。
     *
     * @param whileCondition 繰り返す<b>前</b>に調べる条件。無ければ {@code null}
     * @param untilCondition 繰り返した<b>後</b>に調べる条件。無ければ {@code null}。
     *                       どちらも無ければ {@code DO LOOP} (無限の繰り返し) である
     */
    record Loop(Expr whileCondition, Expr untilCondition, List<Stmt> body) implements Stmt {
    }

    record IterativeLoop(String control, Expr start, Expr finish, Expr step,
                         List<Stmt> body) implements Stmt {
    }

    record Call(String name, List<Expr> arguments) implements Stmt {
    }

    record Return() implements Stmt {
    }

    record Block(List<Stmt> body) implements Stmt {
    }

    record GoTo(String label) implements Stmt {
    }

    record Label(String name) implements Stmt {
    }

    record OnEndFile(String file, List<Stmt> handler) implements Stmt {
    }

    enum FileAction { OPEN, READ, CLOSE }

    record FileOperation(FileAction action, String file, String target) implements Stmt {
    }

    record Sql(String source) implements Stmt {
    }

    sealed interface Expr permits Literal, Reference, Unary, Binary, Function {
    }

    record Literal(Object value) implements Expr {
    }

    /**
     * 2 ビット以上のビット列の定数。1 ビットのものは真偽値として持つ。以前はビット列の定数を
     * すべて真偽値にしていたので、{@code '10100000'B} が {@code '1'B} になっていた。
     */
    record BitString(String bits) {
    }

    /** {@code '...'B} の値。'0' と '1' のほかの字は誤りである。 */
    private static Object bitLiteral(Token token) {
        String bits = token.text();
        if (!bits.chars().allMatch(c -> c == '0' || c == '1')) {
            throw new ParseFailure(token, "bit string constant '" + bits + "'B has a character"
                    + " other than 0 and 1");
        }
        return bits.length() == 1 ? (Object) bits.equals("1") : new BitString(bits);
    }

    /**
     * 16 進で書いた文字の定数 {@code 'C1C2'X} の byte の並び (LRM "Hexadecimal character constant")。
     * 文字にするのは実行時のコードページである。翻訳時に文字へ直すと、コードページが違う実行で
     * 別の byte になる。
     */
    record HexString(byte[] bytes) {
    }

    /** {@code '...'X} の値。16 進の字が偶数個でなければ誤りである。 */
    private static HexString hexLiteral(Token token) {
        String digits = token.text();
        if (digits.length() % 2 != 0
                || !digits.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            throw new ParseFailure(token, "hexadecimal constant '" + digits
                    + "'X needs an even number of hexadecimal digits");
        }
        byte[] bytes = new byte[digits.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(digits.substring(i * 2, i * 2 + 2), 16);
        }
        return new HexString(bytes);
    }

    record Reference(String name) implements Expr {
    }

    record Unary(String operator, Expr operand) implements Expr {
    }

    record Binary(String operator, Expr left, Expr right) implements Expr {
    }

    record Function(String name, List<Expr> arguments) implements Expr {
    }

    private enum Kind { IDENT, NUMBER, STRING, SYMBOL, EOF }

    private record Token(Kind kind, String text, int line, int column) {
        boolean is(String value) {
            return kind != Kind.STRING && text.equalsIgnoreCase(value);
        }
    }

    private static final class Lexer {
        private final String fileName;
        private final String source;
        private final List<Token> tokens = new ArrayList<>();
        private int at;
        private int line = 1;
        private int column = 1;

        Lexer(String fileName, String source) {
            this.fileName = fileName;
            this.source = source;
        }

        List<Token> scan() {
            while (at < source.length()) {
                char c = source.charAt(at);
                if (Character.isWhitespace(c)) {
                    advance(c);
                } else if (c == '/' && peek(1) == '*') {
                    comment();
                } else if (c == '\'' || c == '"') {
                    string(c);
                } else if (Character.isDigit(c)) {
                    number();
                } else if (isIdentifierStart(c)) {
                    identifier();
                } else {
                    symbol();
                }
            }
            tokens.add(new Token(Kind.EOF, "<EOF>", line, column));
            return List.copyOf(tokens);
        }

        private void comment() {
            int startLine = line;
            int startColumn = column;
            advance('/');
            advance('*');
            while (at < source.length() && !(source.charAt(at) == '*' && peek(1) == '/')) {
                advance(source.charAt(at));
            }
            if (at >= source.length()) {
                throw new ParseFailure(new Token(Kind.SYMBOL, "/*", startLine, startColumn),
                        "unterminated comment in " + fileName);
            }
            advance('*');
            advance('/');
        }

        private void string(char quote) {
            int tokenLine = line;
            int tokenColumn = column;
            advance(quote);
            StringBuilder value = new StringBuilder();
            boolean closed = false;
            while (at < source.length()) {
                char c = source.charAt(at);
                if (c == quote) {
                    advance(c);
                    if (at < source.length() && source.charAt(at) == quote) {
                        value.append(quote);
                        advance(quote);
                        continue;
                    }
                    closed = true;
                    break;
                }
                value.append(c);
                advance(c);
            }
            if (!closed) {
                throw new ParseFailure(new Token(Kind.STRING, value.toString(), tokenLine,
                        tokenColumn), "unterminated string literal");
            }
            tokens.add(new Token(Kind.STRING, value.toString(), tokenLine, tokenColumn));
        }

        private void number() {
            int start = at;
            int tokenLine = line;
            int tokenColumn = column;
            while (at < source.length() && Character.isDigit(source.charAt(at))) {
                advance(source.charAt(at));
            }
            if (at < source.length() && source.charAt(at) == '.'
                    && Character.isDigit(peek(1))) {
                advance('.');
                while (at < source.length() && Character.isDigit(source.charAt(at))) {
                    advance(source.charAt(at));
                }
            }
            tokens.add(new Token(Kind.NUMBER, source.substring(start, at), tokenLine, tokenColumn));
        }

        private void identifier() {
            int start = at;
            int tokenLine = line;
            int tokenColumn = column;
            while (at < source.length() && isIdentifierPart(source.charAt(at))) {
                advance(source.charAt(at));
            }
            tokens.add(new Token(Kind.IDENT,
                    source.substring(start, at).toUpperCase(Locale.ROOT), tokenLine, tokenColumn));
        }

        private void symbol() {
            int tokenLine = line;
            int tokenColumn = column;
            char first = source.charAt(at);
            String two = at + 1 < source.length() ? source.substring(at, at + 2) : "";
            if (List.of("^=", "¬=", "<=", ">=", "||", "**", "=>").contains(two)) {
                advance(first);
                advance(source.charAt(at));
                tokens.add(new Token(Kind.SYMBOL, two, tokenLine, tokenColumn));
            } else {
                advance(first);
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(first), tokenLine, tokenColumn));
            }
        }

        private char peek(int ahead) {
            return at + ahead < source.length() ? source.charAt(at + ahead) : '\0';
        }

        private void advance(char c) {
            at++;
            if (c == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }

        private static boolean isIdentifierStart(char c) {
            return Character.isLetter(c) || c == '_' || c == '$' || c == '#' || c == '@';
        }

        private static boolean isIdentifierPart(char c) {
            return isIdentifierStart(c) || Character.isDigit(c);
        }
    }

    private static final class Parser {
        private final String fileName;
        private final List<Token> tokens;
        private int at;

        Parser(String fileName, List<Token> tokens) {
            this.fileName = fileName;
            this.tokens = tokens;
        }

        ParseResult parse() {
            while (!check(Kind.EOF) && !procedureAhead()) {
                at++;
            }
            if (check(Kind.EOF)) {
                throw fail(peek(), "a labeled PROCEDURE was not found");
            }
            ProcedureBuilder main = parseProcedure();
            return new ParseResult(new Program(main.name, main.parameters,
                    List.copyOf(main.body), Map.copyOf(main.procedures), main.main), List.of());
        }

        private ProcedureBuilder parseProcedure() {
            String name = expect(Kind.IDENT, "procedure name").text();
            expect(":");
            // PROC は PROCEDURE の略である (LRM "PROCEDURE statement")。以前は断っていた
            if (!match("PROCEDURE") && !match("PROC")) {
                throw fail(peek(), "PROCEDURE expected, found " + peek().text());
            }
            List<String> parameters = new ArrayList<>();
            if (match("(")) {
                if (!check(")")) {
                    do {
                        parameters.add(qualifiedName());
                    } while (match(","));
                }
                expect(")");
            }
            // 入口属性のうち要るのは OPTIONS(MAIN) だけである。RETURNS その他は保持しない
            List<Token> attributes = collectToSemicolon();
            ProcedureBuilder result = new ProcedureBuilder(name, List.copyOf(parameters));
            for (int i = 0; i + 2 < attributes.size(); i++) {
                if (attributes.get(i).is("OPTIONS") && attributes.get(i + 1).is("(")) {
                    int close = findClosing(attributes, i + 1);
                    result.main = attributes.subList(i + 2, close).stream()
                            .anyMatch(token -> token.is("MAIN"));
                }
            }
            while (!check(Kind.EOF)) {
                if (procedureAhead()) {
                    ProcedureBuilder nested = parseProcedure();
                    result.procedures.put(nested.name, nested.freeze());
                    result.procedures.putAll(nested.procedures);
                    continue;
                }
                if (check("END") && (check(1, ";") || check(1, name))) {
                    next();
                    if (check(Kind.IDENT)) {
                        next();
                    }
                    expect(";");
                    return result;
                }
                result.body.add(statement());
            }
            throw fail(peek(), "procedure " + name + " has no matching END");
        }

        private Stmt statement() {
            if (check("%") && (check(1, "PAGE") || check(1, "SKIP") || check(1, "PRINT")
                    || check(1, "NOPRINT") || check(1, "PUSH") || check(1, "POP"))) {
                // 翻訳の listing だけを整える指示で、実行には何も起こさない (LRM "%PAGE directive" ほか)
                skipToSemicolon();
                return new Block(List.of());
            }
            if (match("DCL") || match("DECLARE")) {
                return declaration();
            }
            if (match("PUT")) {
                return put();
            }
            if (match("IF")) {
                return ifStatement();
            }
            if (match("DO")) {
                return loop();
            }
            if (match("BEGIN")) {
                expect(";");
                return new Block(blockBody());
            }
            if (match("CALL")) {
                return call();
            }
            if (match("RETURN")) {
                if (check("(")) {
                    // 値を返すのは RETURNS を持つ関数の手続きだけで、それはまだ持たない。
                    // 以前は値を読み飛ばしていた
                    throw fail(previous(), "RETURN with a value is not supported yet");
                }
                expect(";");
                return new Return();
            }
            if (match("GO")) {
                match("TO");
                String target = expect(Kind.IDENT, "label after GO TO").text();
                expect(";");
                return new GoTo(target);
            }
            if (match("ON")) {
                return onCondition();
            }
            if (match("OPEN")) {
                return fileOperation(FileAction.OPEN);
            }
            if (match("READ")) {
                return fileOperation(FileAction.READ);
            }
            if (match("CLOSE")) {
                return fileOperation(FileAction.CLOSE);
            }
            if (match("EXEC")) {
                if (!match("SQL")) {
                    throw fail(peek(), "EXEC " + peek().text() + " is not supported yet");
                }
                return new Sql(renderSql(collectToSemicolon()));
            }
            if (check(Kind.IDENT) && check(1, ":")) {
                String label = next().text();
                next();
                return new Label(label);
            }
            if (check(Kind.IDENT)) {
                int save = at;
                String target = qualifiedName();
                // A(I) = ... (配列の要素) と SUBSTR(X, I, N) = ... (擬似変数)。以前はどちらも
                // 「文を知らない」で断っていた
                List<Expr> subscripts = check("(") ? arguments() : List.of();
                if (match("=")) {
                    Expr value = expression();
                    expect(";");
                    return new Assign(target, subscripts, value);
                }
                at = save;
            }
            // 知らない文は読み飛ばさずに断る。以前は翻訳が通り、実行したときに初めて止まっていた。
            // それでは「翻訳できた」の数が、動かせない資産まで数えてしまう
            throw fail(peek(), "statement " + peek().text() + " is not supported yet");
        }

        private Stmt declaration() {
            List<Token> declaration = collectToSemicolon();
            return new Declare(parseDeclarations(declaration));
        }

        private List<Decl> parseDeclarations(List<Token> declaration) {
            List<List<Token>> parts = split(declaration, ",");
            List<Decl> out = new ArrayList<>();
            Type inheritedType = Type.GROUP;
            int inheritedPrecision = 0;
            int inheritedScale = 0;
            for (List<Token> part : parts) {
                if (part.isEmpty()) {
                    continue;
                }
                int p = 0;
                int level = 0;
                if (part.get(p).kind() == Kind.NUMBER && !part.get(p).text().contains(".")) {
                    level = Integer.parseInt(part.get(p++).text());
                }
                if (p >= part.size()) {
                    continue;
                }
                if (part.get(p).is("(")) {
                    // 名前リストは、同じ属性を各名前へ適用する。
                    int close = findClosing(part, p);
                    List<String> names = part.subList(p + 1, close).stream()
                            .filter(t -> t.kind() == Kind.IDENT).map(Token::text).toList();
                    List<Bound> dimensions = dimensions(part, close + 1, level);
                    TypeInfo info = typeInfo(part, close + 1, inheritedType,
                            inheritedPrecision, inheritedScale);
                    for (String name : names) {
                        out.add(declaration(name, level, info, part, dimensions));
                    }
                    inheritedType = info.type;
                    inheritedPrecision = info.precision;
                    inheritedScale = info.scale;
                    continue;
                }
                Token nameToken = part.get(p++);
                if (nameToken.kind() != Kind.IDENT && !nameToken.is("*")) {
                    continue;
                }
                List<Bound> dimensions = dimensions(part, p, level);
                TypeInfo info = typeInfo(part, p, level > 0 ? Type.GROUP : inheritedType,
                        inheritedPrecision, inheritedScale);
                out.add(declaration(nameToken.text(), level, info, part, dimensions));
                inheritedType = info.type;
                inheritedPrecision = info.precision;
                inheritedScale = info.scale;
            }
            return List.copyOf(out);
        }

        private Decl declaration(String name, int level, TypeInfo info, List<Token> part,
                                 List<Bound> dimensions) {
            if (!dimensions.isEmpty() && info.type == Type.BIT) {
                // UNALIGNED のビット列の配列は要素がビット単位で詰まる。byte ごとに置くと位置が
                // ずれるので、持つまでは断る
                throw new ParseFailure(part.get(0), "arrays of bit strings are not supported yet: "
                        + name);
            }
            return new Decl(name, level, info.type, info.precision, info.scale,
                    dimensions.isEmpty() ? initial(part) : null, basedOn(part), alignment(part),
                    varying(part), dimensions,
                    dimensions.isEmpty() ? List.of() : initialItems(part));
        }

        /**
         * 名の直後の {@code (n)}、{@code (lo:hi)}、{@code (n, m)} を次元として読む (LRM "DIMENSION
         * attribute")。以前は読み飛ばしていたので、{@code DCL A(10) FIXED BIN} が 1 つの変数に
         * なっていた。構造の中の配列はまだ持たないので断る。
         */
        private List<Bound> dimensions(List<Token> part, int at, int level) {
            if (at >= part.size() || !part.get(at).is("(")) {
                return List.of();
            }
            int close = findClosing(part, at);
            List<Bound> bounds = new ArrayList<>();
            for (List<Token> dimension : split(part.subList(at + 1, close), ",")) {
                List<List<Token>> ends = split(dimension, ":");
                int low = ends.size() == 2 ? bound(ends.get(0), part.get(at)) : 1;
                int high = bound(ends.get(ends.size() - 1), part.get(at));
                if (ends.size() > 2 || high < low) {
                    throw new ParseFailure(part.get(at), "bad array bounds");
                }
                bounds.add(new Bound(low, high));
            }
            if (level > 0) {
                throw new ParseFailure(part.get(at), "arrays in a structure are not supported yet");
            }
            return List.copyOf(bounds);
        }

        /** 次元の上限か下限。符号つきの整数だけを読む。{@code *} や式はまだ持たない。 */
        private static int bound(List<Token> tokens, Token where) {
            if (tokens.size() == 1 && tokens.get(0).kind() == Kind.NUMBER
                    && !tokens.get(0).text().contains(".")) {
                return Integer.parseInt(tokens.get(0).text());
            }
            if (tokens.size() == 2 && tokens.get(0).is("-")
                    && tokens.get(1).kind() == Kind.NUMBER) {
                return -Integer.parseInt(tokens.get(1).text());
            }
            throw new ParseFailure(where, "array bounds must be integer constants yet");
        }

        /**
         * 配列の {@code INITIAL(値, (n)値, (*)値)}。反復の係数は括弧に入れた整数か {@code *}。
         * 括弧の中が式なら、それは係数ではなく値である ({@code INIT((1+2))})。
         */
        private List<InitItem> initialItems(List<Token> tokens) {
            for (int i = 0; i + 1 < tokens.size(); i++) {
                if ((tokens.get(i).is("INIT") || tokens.get(i).is("INITIAL"))
                        && tokens.get(i + 1).is("(")) {
                    int close = findClosing(tokens, i + 1);
                    List<Token> inner = new ArrayList<>(tokens.subList(i + 2, close));
                    Token end = tokens.get(close);
                    inner.add(new Token(Kind.EOF, "<EOF>", end.line(), end.column()));
                    Parser values = new Parser(fileName, inner);
                    List<InitItem> items = new ArrayList<>();
                    do {
                        int count = 1;
                        if (values.check("(") && values.check(2, ")")
                                && (values.token(1).kind() == Kind.NUMBER
                                        || values.check(1, "*"))
                                && !values.check(3, ",") && !values.check(3, "<EOF>")) {
                            values.next();
                            Token factor = values.next();
                            values.next();
                            count = factor.is("*") ? 0 : Integer.parseInt(factor.text());
                        }
                        items.add(new InitItem(count, values.expression()));
                    } while (values.match(","));
                    if (!values.check(Kind.EOF)) {
                        throw values.fail(values.peek(), "unexpected " + values.peek().text()
                                + " in INITIAL");
                    }
                    return List.copyOf(items);
                }
            }
            return List.of();
        }

        /**
         * {@code VARYING} (略して {@code VAR}) を書いたか。以前は読み飛ばしていたので、
         * {@code OUT = OUT || X} が固定長の代入になり、OUT は空白のままだった。
         */
        private static boolean varying(List<Token> part) {
            // 宣言する名そのもの (先頭、または段番号の次) は属性ではない
            int name = !part.isEmpty() && part.get(0).kind() == Kind.NUMBER ? 1 : 0;
            for (int i = name + 1; i < part.size(); i++) {
                Token token = part.get(i);
                if (token.is("VARYING") || token.is("VAR")) {
                    // BIT VARYING はまだ持たない。黙って固定長にせず断る
                    if (part.stream().noneMatch(t -> t.is("CHAR") || t.is("CHARACTER"))) {
                        throw new ParseFailure(token, "VARYING is supported only for"
                                + " CHARACTER yet");
                    }
                    return true;
                }
            }
            return false;
        }

        private static Boolean alignment(List<Token> part) {
            for (Token token : part) {
                if (token.is("UNALIGNED") || token.is("UNAL")) return Boolean.FALSE;
                if (token.is("ALIGNED")) return Boolean.TRUE;
            }
            return null;
        }

        private static TypeInfo typeInfo(List<Token> tokens, int from, Type inherited,
                                         int inheritedPrecision, int inheritedScale) {
            for (int i = from; i < tokens.size(); i++) {
                if (tokens.get(i).is("CHAR") || tokens.get(i).is("CHARACTER")) {
                    return new TypeInfo(Type.CHAR, parenthesizedInt(tokens, i + 1, 1), 0);
                }
                if (tokens.get(i).is("BIT")) {
                    return new TypeInfo(Type.BIT, parenthesizedInt(tokens, i + 1, 1), 0);
                }
                if (tokens.get(i).is("POINTER")) {
                    return new TypeInfo(Type.POINTER, 0, 0);
                }
                if (tokens.get(i).is("FILE")) {
                    return new TypeInfo(Type.FILE, 0, 0);
                }
                if (tokens.get(i).is("ENTRY")) {
                    return new TypeInfo(Type.ENTRY, 0, 0);
                }
                if (tokens.get(i).is("PIC") || tokens.get(i).is("PICTURE")) {
                    if (i + 1 >= tokens.size() || tokens.get(i + 1).kind() != Kind.STRING) {
                        throw new ParseFailure(tokens.get(i), "PICTURE needs a quoted specification");
                    }
                    return picture(tokens.get(i + 1));
                }
                if (tokens.get(i).is("FIXED")) {
                    int j = i + 1;
                    if (j < tokens.size() && (tokens.get(j).is("BIN")
                            || tokens.get(j).is("BINARY"))) {
                        // 精度を省けば (15,0)、10 進は (5,0) (LRM Table 40, DEFAULT(IBM))。
                        // 以前は 2 進を 31、10 進を 15 とし、2 進の位取りを読んでいなかった
                        return new TypeInfo(Type.BINARY,
                                parenthesizedInt(tokens, j + 1, 15),
                                parenthesizedSecondInt(tokens, j + 1, 0));
                    }
                    if (j < tokens.size() && (tokens.get(j).is("DEC")
                            || tokens.get(j).is("DECIMAL"))) {
                        int precision = parenthesizedInt(tokens, j + 1, 5);
                        int scale = parenthesizedSecondInt(tokens, j + 1, 0);
                        return new TypeInfo(Type.DECIMAL, precision, scale);
                    }
                }
            }
            return new TypeInfo(inherited, inheritedPrecision, inheritedScale);
        }

        /**
         * {@code INITIAL(...)} の中身を式として読む (LRM "INITIAL attribute")。
         *
         * <p>以前は括弧の直後の字句 1 つだけを見ていたので、{@code INIT(-12345)} は負号で止まって
         * 初期値が無いことになり、黙って 0 になっていた。{@code INIT(ADDR(S))} も同じ理由で
         * NULL のままだった。z/OS probe の PLISTRM で見つかった。
         */
        private Expr initial(List<Token> tokens) {
            for (int i = 0; i + 1 < tokens.size(); i++) {
                if ((tokens.get(i).is("INIT") || tokens.get(i).is("INITIAL"))
                        && tokens.get(i + 1).is("(")) {
                    int close = findClosing(tokens, i + 1);
                    List<Token> inner = new ArrayList<>(tokens.subList(i + 2, close));
                    Token end = tokens.get(close);
                    inner.add(new Token(Kind.EOF, "<EOF>", end.line(), end.column()));
                    Parser values = new Parser(fileName, inner);
                    Expr value = values.expression();
                    if (!values.check(Kind.EOF)) {
                        throw values.fail(values.peek(), "INITIAL with more than one value"
                                + " needs an array");
                    }
                    return value;
                }
            }
            return null;
        }

        private static String basedOn(List<Token> tokens) {
            for (int i = 0; i + 2 < tokens.size(); i++) {
                if (tokens.get(i).is("BASED") && tokens.get(i + 1).is("(")) {
                    if (tokens.get(i + 2).is("ADDR") && i + 4 < tokens.size()) {
                        return tokens.get(i + 4).text();
                    }
                    return tokens.get(i + 2).text();
                }
            }
            return null;
        }

        /**
         * {@code PUT} の選択子は順を問わない。知らない選択子は<b>読み飛ばさずに断る</b>。
         * 以前は読み飛ばしていたので、{@code PUT SKIP(2) LIST(...)} や {@code PUT FILE(RPT) ...}
         * が何も出さずに通っていた。
         */
        private Stmt put() {
            String string = null;
            boolean page = false;
            int skip = 0;
            Boolean edit = null;
            List<Expr> values = List.of();
            List<FormatItem> format = List.of();
            while (!check(";")) {
                Token option = peek();
                if (match("PAGE")) {
                    page = true;
                } else if (match("SKIP")) {
                    skip = 1;
                    if (match("(")) {
                        Token count = expect(Kind.NUMBER, "SKIP count");
                        expect(")");
                        skip = Integer.parseInt(count.text());
                        if (skip < 1) {
                            // SKIP(0) は重ね打ち (復帰だけで改行しない)。標準出力では表せない
                            throw fail(count, "PUT SKIP(0) is not supported");
                        }
                    }
                } else if (match("STRING")) {
                    expect("(");
                    string = qualifiedName();
                    expect(")");
                } else if (match("FILE")) {
                    expect("(");
                    Token file = expect(Kind.IDENT, "file name");
                    expect(")");
                    if (!file.is("SYSPRINT")) {
                        throw fail(file, "PUT FILE(" + file.text() + ") is not supported yet;"
                                + " only SYSPRINT is");
                    }
                } else if ((check("LIST") || check("EDIT")) && edit == null) {
                    edit = next().is("EDIT");
                    values = arguments();
                    if (edit) {
                        format = formatList();
                    }
                } else {
                    throw fail(option, "PUT option " + option.text() + " is not supported yet");
                }
            }
            if (string != null && (page || skip > 0 || edit == null || !edit)) {
                // PAGE と SKIP はファイルにしか書けない。STRING への LIST は区切りと引用符の規則が
                // PRINT ファイルと違い、まだ持たない
                throw fail(peek(), "PUT STRING supports only EDIT without PAGE or SKIP");
            }
            expect(";");
            return new Put(string, page, skip, edit != null && edit, values, format);
        }

        private List<FormatItem> formatList() {
            expect("(");
            List<FormatItem> items = new ArrayList<>();
            do {
                Token item = expect(Kind.IDENT, "format item");
                int width = -1;
                int fraction = 0;
                boolean fractionGiven = false;
                if (match("(")) {
                    width = Integer.parseInt(expect(Kind.NUMBER, "field width").text());
                    if (match(",")) {
                        fraction = Integer.parseInt(
                                expect(Kind.NUMBER, "fractional digits").text());
                        fractionGiven = true;
                    }
                    expect(")");
                }
                if (item.is("A") && !fractionGiven) {
                    items.add(new FormatItem('A', width, 0));
                } else if (item.is("X") && width >= 0 && !fractionGiven) {
                    items.add(new FormatItem('X', width, 0));
                } else if (item.is("F") && width >= 0) {
                    // 3 つ目の scaling-factor は ")" を期待したところで断られる
                    items.add(new FormatItem('F', width, fraction));
                } else {
                    throw fail(item, "PUT EDIT format item " + item.text()
                            + " is not supported yet");
                }
            } while (match(","));
            expect(")");
            return List.copyOf(items);
        }

        private Stmt ifStatement() {
            Expr condition = expression();
            expect("THEN");
            Stmt whenTrue = statement();
            Stmt whenFalse = match("ELSE") ? statement() : null;
            return new If(condition, whenTrue, whenFalse);
        }

        private Stmt loop() {
            if (check(Kind.IDENT) && check(1, "=")) {
                String control = next().text();
                expect("=");
                Expr start = expression();
                expect("TO");
                Expr finish = expression();
                Expr step = match("BY") ? expression()
                        : new Literal(java.math.BigDecimal.ONE);
                expect(";");
                return new IterativeLoop(control, start, finish, step, blockBody());
            }
            if (match(";")) {
                // Type 1。繰り返さず、1 度だけ実行する。以前は条件の無い繰り返しとして扱い、
                // IF ... THEN DO; ... END; が上限まで回っていた
                return new Block(blockBody());
            }
            if (match("LOOP") || match("FOREVER")) {
                expect(";");
                return new Loop(null, null, blockBody());
            }
            Expr whileCondition = null;
            Expr untilCondition = null;
            // WHILE と UNTIL はどちらが先でもよく、両方書ける (LRM "DO statement" Type 2)。
            // 以前は 2 つ目を読み飛ばしていた
            for (int i = 0; i < 2; i++) {
                if (whileCondition == null && match("WHILE")) {
                    whileCondition = parenthesizedExpression();
                } else if (untilCondition == null && match("UNTIL")) {
                    untilCondition = parenthesizedExpression();
                }
            }
            if (whileCondition == null && untilCondition == null) {
                throw fail(peek(), "DO option " + peek().text() + " is not supported yet");
            }
            expect(";");
            return new Loop(whileCondition, untilCondition, blockBody());
        }

        private List<Stmt> blockBody() {
            List<Stmt> body = new ArrayList<>();
            while (!check("END") && !check(Kind.EOF)) {
                body.add(statement());
            }
            expect("END");
            if (check(Kind.IDENT)) {
                next();
            }
            expect(";");
            return List.copyOf(body);
        }

        private Stmt call() {
            String name = qualifiedName();
            List<Expr> args = check("(") ? arguments() : List.of();
            expect(";");
            return new Call(name, args);
        }

        private Stmt onCondition() {
            if (!match("ENDFILE")) {
                throw fail(peek(), "ON " + peek().text() + " is not supported yet");
            }
            expect("(");
            String file = qualifiedName();
            expect(")");
            expect("BEGIN");
            expect(";");
            return new OnEndFile(file, blockBody());
        }

        private Stmt fileOperation(FileAction action) {
            expect("FILE");
            expect("(");
            String file = qualifiedName();
            expect(")");
            String target = null;
            if (action == FileAction.READ) {
                expect("INTO");
                expect("(");
                target = qualifiedName();
                expect(")");
            }
            expect(";");
            return new FileOperation(action, file, target);
        }

        private Expr parenthesizedExpression() {
            expect("(");
            Expr value = expression();
            expect(")");
            return value;
        }

        private List<Expr> arguments() {
            expect("(");
            List<Expr> values = new ArrayList<>();
            if (!check(")")) {
                do {
                    values.add(expression());
                } while (match(","));
            }
            expect(")");
            return List.copyOf(values);
        }

        private Expr expression() {
            return binary(0);
        }

        private Expr binary(int minimum) {
            Expr left = unary();
            while (true) {
                int precedence = precedence(peek().text());
                if (precedence < minimum) {
                    break;
                }
                String operator = next().text();
                Expr right = binary(precedence + 1);
                left = new Binary(operator, left, right);
            }
            return left;
        }

        private Expr unary() {
            if (match("^") || match("¬") || match("-") || match("+")) {
                return new Unary(previous().text(), unary());
            }
            return primary();
        }

        private Expr primary() {
            if (match("(")) {
                Expr value = expression();
                expect(")");
                return value;
            }
            if (check(Kind.STRING)) {
                Token literal = next();
                String value = literal.text();
                if (match("B")) {
                    return new Literal(bitLiteral(literal));
                }
                if (match("X")) {
                    return new Literal(hexLiteral(literal));
                }
                return new Literal(value);
            }
            if (check(Kind.NUMBER)) {
                return new Literal(FixedValue.constant(next().text()));
            }
            if (check(Kind.IDENT)) {
                String name = qualifiedName();
                if (check("(")) {
                    return new Function(name, arguments());
                }
                return new Reference(name);
            }
            throw fail(peek(), "expression expected, found " + peek().text());
        }

        private String qualifiedName() {
            StringBuilder name = new StringBuilder(expect(Kind.IDENT, "identifier").text());
            while (match(".")) {
                name.append('.').append(expect(Kind.IDENT, "qualified identifier").text());
            }
            return name.toString();
        }

        private boolean procedureAhead() {
            return check(Kind.IDENT) && check(1, ":")
                    && (check(2, "PROCEDURE") || check(2, "PROC"));
        }

        private void skipBalanced() {
            expect("(");
            int depth = 1;
            while (depth > 0 && !check(Kind.EOF)) {
                if (match("(")) {
                    depth++;
                } else if (match(")")) {
                    depth--;
                } else {
                    next();
                }
            }
        }

        private List<Token> collectToSemicolon() {
            int start = at;
            int depth = 0;
            while (!check(Kind.EOF)) {
                if (check("(") ) {
                    depth++;
                } else if (check(")")) {
                    depth--;
                } else if (check(";") && depth == 0) {
                    List<Token> result = List.copyOf(tokens.subList(start, at));
                    at++;
                    return result;
                }
                at++;
            }
            throw fail(peek(), "semicolon expected");
        }

        private void skipToSemicolon() {
            collectToSemicolon();
        }

        private static List<List<Token>> split(List<Token> values, String separator) {
            List<List<Token>> parts = new ArrayList<>();
            int start = 0;
            int depth = 0;
            for (int i = 0; i < values.size(); i++) {
                if (values.get(i).is("(")) {
                    depth++;
                } else if (values.get(i).is(")")) {
                    depth--;
                } else if (values.get(i).is(separator) && depth == 0) {
                    parts.add(List.copyOf(values.subList(start, i)));
                    start = i + 1;
                }
            }
            parts.add(List.copyOf(values.subList(start, values.size())));
            return parts;
        }

        private static String renderSql(List<Token> tokens) {
            StringBuilder sql = new StringBuilder();
            Token previous = null;
            for (Token token : tokens) {
                // ':' の前は詰めない。詰めると INTO:HV になり、FETCH の INTO を読み取れない。
                // 標識変数は :HV :IND と空白を挟んでも同じ意味である
                boolean tight = token.kind() == Kind.SYMBOL
                        && List.of(",", ")", ".").contains(token.text());
                boolean afterTight = previous != null && previous.kind() == Kind.SYMBOL
                        && List.of("(", ".", ":").contains(previous.text());
                if (!sql.isEmpty() && !tight && !afterTight) sql.append(' ');
                if (token.kind() == Kind.STRING) {
                    sql.append('\'').append(token.text().replace("'", "''")).append('\'');
                } else {
                    sql.append(token.text());
                }
                previous = token;
            }
            return sql.toString();
        }

        private static int findClosing(List<Token> values, int open) {
            int depth = 0;
            for (int i = open; i < values.size(); i++) {
                if (values.get(i).is("(")) depth++;
                if (values.get(i).is(")") && --depth == 0) return i;
            }
            return values.size() - 1;
        }

        private static int parenthesizedInt(List<Token> values, int at, int fallback) {
            if (at + 1 < values.size() && values.get(at).is("(")
                    && values.get(at + 1).kind() == Kind.NUMBER) {
                return Integer.parseInt(values.get(at + 1).text().split("\\.")[0]);
            }
            return fallback;
        }

        private static int parenthesizedSecondInt(List<Token> values, int at, int fallback) {
            if (at + 3 < values.size() && values.get(at).is("(")
                    && values.get(at + 2).is(",") && values.get(at + 3).kind() == Kind.NUMBER) {
                return Integer.parseInt(values.get(at + 3).text().split("\\.")[0]);
            }
            return fallback;
        }

        private static int precedence(String operator) {
            return switch (operator.toUpperCase(Locale.ROOT)) {
                case "|" -> 1;
                case "&" -> 2;
                case "=", "^=", "¬=", "<", ">", "<=", ">=" -> 3;
                case "||" -> 4;
                case "+", "-" -> 5;
                case "*", "/" -> 6;
                default -> -1;
            };
        }

        private boolean match(String value) {
            if (!check(value)) return false;
            at++;
            return true;
        }

        private boolean check(String value) {
            return peek().is(value);
        }

        private boolean check(int ahead, String value) {
            return token(ahead).is(value);
        }

        private boolean check(Kind kind) {
            return peek().kind() == kind;
        }

        private Token expect(String value) {
            if (!check(value)) throw fail(peek(), value + " expected, found " + peek().text());
            return next();
        }

        private Token expect(Kind kind, String what) {
            if (!check(kind)) throw fail(peek(), what + " expected, found " + peek().text());
            return next();
        }

        private Token next() {
            return tokens.get(at++);
        }

        private Token previous() {
            return tokens.get(at - 1);
        }

        private Token peek() {
            return token(0);
        }

        private Token token(int ahead) {
            return tokens.get(Math.min(at + ahead, tokens.size() - 1));
        }

        private ParseFailure fail(Token token, String message) {
            return new ParseFailure(token, message + " in " + fileName);
        }
    }

    private record TypeInfo(Type type, int precision, int scale) {
    }

    /**
     * PICTURE の指定を読む。反復の係数 {@code (n)c} は c を n 個並べたものなので、開いてから字を
     * 数える。以前は '9' の字を数えていたので、{@code '(9)9'} (9 が 9 個) を 2 桁と読んでいた。
     *
     * <ul>
     *   <li>{@code 9} と高々 1 つの {@code V} だけなら数の PICTURE。FIXED DEC(桁数, V より右の桁数) の
     *       値を持ち、記憶域は数字だけ (V は場所を取らない)</li>
     *   <li>{@code X} / {@code A} / {@code 9} だけなら文字の PICTURE。CHAR と同じに扱う</li>
     *   <li>ほかの字 (Z、S、$、小数点、編集の字など) はまだ持たないので断る</li>
     * </ul>
     */
    private static TypeInfo picture(Token specification) {
        StringBuilder expanded = new StringBuilder();
        String text = specification.text().toUpperCase(Locale.ROOT);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                int close = text.indexOf(')', i);
                if (close < 0 || close + 1 >= text.length()) {
                    throw new ParseFailure(specification, "bad PICTURE repetition: '" + text + "'");
                }
                int count = Integer.parseInt(text.substring(i + 1, close).strip());
                expanded.append(String.valueOf(text.charAt(close + 1)).repeat(count));
                i = close + 1;
            } else if (c != ' ') {
                expanded.append(c);
            }
        }
        String picture = expanded.toString();
        if (!picture.isEmpty() && picture.matches("9*V?9*")) {
            int point = picture.indexOf('V');
            int digits = picture.replace("V", "").length();
            return new TypeInfo(Type.PICTURE, digits, point < 0 ? 0 : picture.length() - point - 1);
        }
        if (!picture.isEmpty() && picture.matches("[XA9]*")) {
            return new TypeInfo(Type.CHAR, picture.length(), 0);
        }
        throw new ParseFailure(specification, "PICTURE '" + specification.text()
                + "' is not supported yet; only 9 and V, or X, A and 9");
    }

    private static final class ProcedureBuilder {
        final String name;
        final List<String> parameters;
        final List<Stmt> body = new ArrayList<>();
        final Map<String, Procedure> procedures = new LinkedHashMap<>();
        boolean main;

        ProcedureBuilder(String name, List<String> parameters) {
            this.name = name;
            this.parameters = parameters;
        }

        Procedure freeze() {
            return new Procedure(name, parameters, List.copyOf(body));
        }
    }

    private static final class ParseFailure extends RuntimeException {
        final Token token;

        ParseFailure(Token token, String message) {
            super(message);
            this.token = token;
        }
    }
}
