package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.db2.SqlOperation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * EXEC SQL の中身を、実行計画に渡せる形へ分ける小さな SQL コプロセッサ (要件 FR-150, FR-153)。
 *
 * <p>SQL の文法全体は解析しない。先頭の語で文の種類を決め、host variable ({@code :名前}) を
 * {@code ?} に置き換え、SELECT / FETCH の INTO 句を出力へ分けるだけである。SQL の中身の
 * 正しさは実行時にデータベースが判断する。対応しない文は名前をつけて断る (暫定判断 P-121)。
 */
final class SqlBlockParser {

    private SqlBlockParser() {
    }

    sealed interface Parsed permits TableDeclaration, CursorDeclaration, Executable, Transaction {
    }

    /** {@code DECLARE 表 TABLE}。precompiler が SQL を照合するための宣言で、実行時の効果は無い。 */
    record TableDeclaration(String table) implements Parsed {
    }

    record CursorDeclaration(String cursor, boolean withHold, String sql, List<HostRef> inputs)
            implements Parsed {
        CursorDeclaration {
            inputs = List.copyOf(inputs);
        }
    }

    /** データベースへ送る文。cursor の文では {@code cursor} を持つ。 */
    record Executable(SqlOperation operation, String sql, String cursor,
                      List<HostRef> inputs, List<HostRef> outputs) implements Parsed {
        Executable {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }
    }

    record Transaction(boolean commit) implements Parsed {
    }

    /** host variable と、あれば null 標識の名前。 */
    record HostRef(String name, String indicator) {
    }

    private enum Kind {
        WORD, HOST, QUOTED, SYMBOL, SPACE
    }

    private record Token(Kind kind, String text, HostRef host, int depth) {
        boolean isWord(String word) {
            return kind == Kind.WORD && text.equalsIgnoreCase(word);
        }
    }

    static Parsed parse(String block) {
        Objects.requireNonNull(block, "block");
        String body = bodyOf(block);
        List<Token> tokens = tokenize(body);
        List<Token> words = tokens.stream().filter(t -> t.kind() != Kind.SPACE).toList();
        if (words.isEmpty()) {
            throw new IllegalArgumentException("EXEC SQL block is empty");
        }
        String first = words.get(0).text().toUpperCase(Locale.ROOT);
        return switch (first) {
            case "DECLARE" -> declaration(tokens, words);
            case "SELECT" -> selectInto(tokens);
            case "INSERT", "UPDATE", "DELETE" -> {
                rejectWord(words, "CURRENT", "positioned UPDATE / DELETE (WHERE CURRENT OF)");
                SqlOperation op = SqlOperation.valueOf(first);
                yield new Executable(op, text(tokens), null, hosts(tokens), List.of());
            }
            case "OPEN", "CLOSE" -> {
                if (words.size() != 2 || words.get(1).kind() != Kind.WORD) {
                    throw new IllegalArgumentException(first + " requires only a cursor name");
                }
                String cursor = words.get(1).text().toUpperCase(Locale.ROOT);
                yield new Executable(first.equals("OPEN") ? SqlOperation.OPEN_CURSOR
                        : SqlOperation.CLOSE_CURSOR, first + " " + cursor, cursor, List.of(), List.of());
            }
            case "FETCH" -> fetch(words);
            case "COMMIT", "ROLLBACK" -> {
                if (words.size() > 2 || (words.size() == 2 && !words.get(1).isWord("WORK"))) {
                    throw new IllegalArgumentException(first + " accepts only WORK");
                }
                yield new Transaction(first.equals("COMMIT"));
            }
            default -> throw new IllegalArgumentException("unsupported EXEC SQL statement: " + first);
        };
    }

    private static String bodyOf(String block) {
        String trimmed = block.strip();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("EXEC") || !upper.endsWith("END-EXEC")) {
            throw new IllegalArgumentException("malformed EXEC SQL block");
        }
        String inner = trimmed.substring(4, trimmed.length() - "END-EXEC".length()).strip();
        if (!inner.regionMatches(true, 0, "SQL", 0, 3)
                || (inner.length() > 3 && !Character.isWhitespace(inner.charAt(3)))) {
            throw new IllegalArgumentException("malformed EXEC SQL block");
        }
        return inner.substring(3).strip();
    }

    private static Parsed declaration(List<Token> tokens, List<Token> words) {
        if (words.size() < 3 || words.get(1).kind() != Kind.WORD) {
            throw new IllegalArgumentException("DECLARE requires a name");
        }
        String name = words.get(1).text().toUpperCase(Locale.ROOT);
        // 表の名前は schema で修飾できる (STTESTER.CONTROL)。カーソルの名前は修飾しない
        StringBuilder qualified = new StringBuilder(name);
        int after = 2;
        while (after + 1 < words.size() && words.get(after).text().equals(".")
                && words.get(after + 1).kind() == Kind.WORD) {
            qualified.append('.').append(words.get(after + 1).text().toUpperCase(Locale.ROOT));
            after += 2;
        }
        if (after < words.size() && words.get(after).isWord("TABLE")) {
            return new TableDeclaration(qualified.toString());
        }
        if (!words.get(2).isWord("CURSOR")) {
            throw new IllegalArgumentException("unsupported DECLARE: " + words.get(2).text());
        }
        int index = 3;
        boolean withHold = false;
        if (index + 1 < words.size() && words.get(index).isWord("WITH")) {
            if (!words.get(index + 1).isWord("HOLD")) {
                throw new IllegalArgumentException(
                        "unsupported DECLARE CURSOR WITH " + words.get(index + 1).text());
            }
            withHold = true;
            index += 2;
        }
        if (index >= words.size() || !words.get(index).isWord("FOR")
                || index + 1 >= words.size() || !words.get(index + 1).isWord("SELECT")) {
            throw new IllegalArgumentException("DECLARE CURSOR requires FOR SELECT");
        }
        rejectWord(words, "UPDATE", "DECLARE CURSOR ... FOR UPDATE");
        Token select = words.get(index + 1);
        List<Token> rest = tokens.subList(tokens.indexOf(select), tokens.size());
        return new CursorDeclaration(name, withHold, text(rest), hosts(rest));
    }

    /** SELECT ... INTO :a, :b FROM ... の INTO 句を出力へ分ける。 */
    private static Parsed selectInto(List<Token> tokens) {
        int into = -1;
        int from = -1;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.depth() != 0) {
                continue;
            }
            if (into < 0 && token.isWord("INTO")) {
                into = i;
            } else if (into >= 0 && token.isWord("FROM")) {
                from = i;
                break;
            }
        }
        if (into < 0 || from < 0) {
            throw new IllegalArgumentException("SELECT outside a cursor requires INTO ... FROM");
        }
        List<HostRef> outputs = intoList(tokens.subList(into + 1, from));
        List<Token> sql = new ArrayList<>(tokens.subList(0, into));
        sql.addAll(tokens.subList(from, tokens.size()));
        return new Executable(SqlOperation.SELECT_ONE, text(sql), null, hosts(sql), outputs);
    }

    /** FETCH [NEXT] [FROM] cursor INTO :a, :b。 */
    private static Parsed fetch(List<Token> words) {
        int index = 1;
        if (index < words.size() && words.get(index).isWord("NEXT")) {
            index++;
        }
        if (index < words.size() && words.get(index).isWord("FROM")) {
            index++;
        }
        if (index + 1 >= words.size() || words.get(index).kind() != Kind.WORD
                || !words.get(index + 1).isWord("INTO")) {
            throw new IllegalArgumentException("FETCH requires [NEXT] [FROM] cursor INTO host variables");
        }
        String cursor = words.get(index).text().toUpperCase(Locale.ROOT);
        List<HostRef> outputs = intoList(words.subList(index + 2, words.size()));
        return new Executable(SqlOperation.FETCH_CURSOR, "FETCH " + cursor, cursor, List.of(), outputs);
    }

    private static List<HostRef> intoList(List<Token> tokens) {
        List<HostRef> out = new ArrayList<>();
        boolean expectHost = true;
        for (Token token : tokens) {
            if (token.kind() == Kind.SPACE) {
                continue;
            }
            if (expectHost && token.kind() == Kind.HOST) {
                out.add(token.host());
                expectHost = false;
            } else if (!expectHost && token.kind() == Kind.SYMBOL && token.text().equals(",")) {
                expectHost = true;
            } else {
                throw new IllegalArgumentException("INTO accepts only host variables: " + token.text());
            }
        }
        if (out.isEmpty() || expectHost) {
            throw new IllegalArgumentException("INTO requires host variables");
        }
        return out;
    }

    private static void rejectWord(List<Token> words, String word, String what) {
        if (words.stream().anyMatch(t -> t.isWord(word) && t.depth() == 0)) {
            throw new IllegalArgumentException(what + " is not supported yet");
        }
    }

    private static List<HostRef> hosts(List<Token> tokens) {
        return tokens.stream().filter(t -> t.kind() == Kind.HOST).map(Token::host).toList();
    }

    /** 語を並べ直した SQL。host variable は {@code ?}、空白の並びは 1 つにする。 */
    private static String text(List<Token> tokens) {
        StringBuilder out = new StringBuilder();
        for (Token token : tokens) {
            if (token.kind() == Kind.SPACE) {
                if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
            } else {
                out.append(token.kind() == Kind.HOST ? "?" : token.text());
            }
        }
        return out.toString().strip();
    }

    private static List<Token> tokenize(String body) {
        List<Token> out = new ArrayList<>();
        int depth = 0;
        int i = 0;
        int n = body.length();
        while (i < n) {
            char c = body.charAt(i);
            if (Character.isWhitespace(c)) {
                int start = i;
                while (i < n && Character.isWhitespace(body.charAt(i))) {
                    i++;
                }
                out.add(new Token(Kind.SPACE, body.substring(start, i), null, depth));
            } else if (c == '\'' || c == '"') {
                int start = i++;
                while (i < n) {
                    if (body.charAt(i) == c) {
                        if (i + 1 < n && body.charAt(i + 1) == c) {
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    i++;
                }
                if (i >= n) {
                    throw new IllegalArgumentException("unterminated literal in EXEC SQL");
                }
                i++;
                out.add(new Token(Kind.QUOTED, body.substring(start, i), null, depth));
            } else if (c == ':' && i + 1 < n && isNameStart(body.charAt(i + 1))) {
                int start = ++i;
                i = nameEnd(body, i);
                String name = body.substring(start, i).toUpperCase(Locale.ROOT);
                String indicator = null;
                if (i + 1 < n && body.charAt(i) == ':' && isNameStart(body.charAt(i + 1))) {
                    int indicatorStart = ++i;
                    i = nameEnd(body, i);
                    indicator = body.substring(indicatorStart, i).toUpperCase(Locale.ROOT);
                } else {
                    int j = i;
                    while (j < n && Character.isWhitespace(body.charAt(j))) {
                        j++;
                    }
                    if (body.regionMatches(true, j, "INDICATOR", 0, 9)) {
                        int k = j + 9;
                        while (k < n && Character.isWhitespace(body.charAt(k))) {
                            k++;
                        }
                        if (k + 1 < n && body.charAt(k) == ':' && isNameStart(body.charAt(k + 1))) {
                            int indicatorStart = k + 1;
                            i = nameEnd(body, indicatorStart);
                            indicator = body.substring(indicatorStart, i).toUpperCase(Locale.ROOT);
                        }
                    }
                }
                if (i < n && body.charAt(i) == '.') {
                    // :A.B は群の中の項目を指す。修飾名の解決はまだ持たないので断る
                    throw new IllegalArgumentException("qualified host variables are not supported yet: :"
                            + name + ".");
                }
                out.add(new Token(Kind.HOST, ":" + name, new HostRef(name, indicator), depth));
            } else if (isNameStart(c)) {
                int start = i;
                i = nameEnd(body, i);
                out.add(new Token(Kind.WORD, body.substring(start, i), null, depth));
            } else {
                if (c == ')') {
                    depth--;
                }
                out.add(new Token(Kind.SYMBOL, String.valueOf(c), null, depth));
                if (c == '(') {
                    depth++;
                }
                i++;
            }
        }
        return out;
    }

    private static boolean isNameStart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '#' || c == '@' || c == '$';
    }

    /** 名前の終わり。COBOL の名前は途中に - を含む。語の終わりの - は名前に含めない。 */
    private static int nameEnd(String body, int start) {
        int i = start;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (isNameStart(c)) {
                i++;
            } else if (c == '-' && i + 1 < body.length() && isNameStart(body.charAt(i + 1))) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }
}
