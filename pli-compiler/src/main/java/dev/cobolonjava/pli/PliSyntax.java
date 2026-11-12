package dev.cobolonjava.pli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bank-of-Z が使う構造化 PL/I サブセットの字句解析と構文木。 */
final class PliSyntax {

    private PliSyntax() {
    }

    static ParseResult parse(String fileName, String source) {
        try {
            return new Parser(fileName, expandTypes(new Lexer(fileName, source).scan())).parse();
        } catch (ParseFailure failure) {
            return new ParseResult(null, List.of(new Diagnostic(Diagnostic.Severity.ERROR,
                    fileName, failure.token.line(), failure.token.column(), failure.getMessage())));
        }
    }

    /**
     * {@code DEFINE STRUCTURE} / {@code DEFINE ALIAS} で定義した型の {@code TYPE} と、
     * {@code LIKE} を、宣言の字句の並びの上で展開する (LRM "DEFINE STRUCTURE", "DEFINE ALIAS",
     * "LIKE attribute")。以前は LIKE の名があるかを確かめるだけで展開しておらず、
     * {@code DCL Q LIKE P} が中身の無い宣言になっていた。
     *
     * <p>LIKE は参照した構造の要素の名と属性を写す。参照した構造の大きい方の記憶域の種類と
     * 次元は写さない。展開は宣言の順によらない (後に書かれた構造も参照できる)。
     * DEFINE / TYPE / LIKE の無い原文は並びを変えない。
     */
    private static List<Token> expandTypes(List<Token> tokens) {
        if (tokens.stream().noneMatch(t -> t.kind() == Kind.IDENT
                && (t.is("DEFINE") || t.is("TYPE") || t.is("LIKE") || t.is("HANDLE")))) {
            return tokens;
        }
        // 文に分ける。最後は EOF
        List<List<Token>> statements = new ArrayList<>();
        int start = 0;
        int depth = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.is("(")) depth++;
            else if (t.is(")")) depth--;
            else if ((t.is(";") && depth <= 0) || t.kind() == Kind.EOF) {
                statements.add(new ArrayList<>(tokens.subList(start, i + 1)));
                start = i + 1;
                depth = 0;
            }
        }
        Map<String, List<List<Token>>> structures = new java.util.HashMap<>();
        Map<String, List<Token>> aliases = new java.util.HashMap<>();
        Map<String, Map<String, Integer>> ordinals = new java.util.HashMap<>();
        Map<String, String> ordinalVariables = new java.util.HashMap<>();
        List<List<Token>> kept = new ArrayList<>();
        for (List<Token> statement : statements) {
            int k = keywordAt(statement);
            if (k >= 0 && statement.get(k).is("DEFINE") && k + 1 < statement.size()
                    && statement.get(k + 1).is("ORDINAL")) {
                kept.add(ordinal(statement, k, ordinals));
            } else if (k >= 0 && statement.get(k).is("DEFINE")) {
                String defined = define(statement, k, structures, aliases);
                if (defined != null) {
                    // HANDLE と NEW のために、型の形の BASED の構造を合成して置く
                    kept.addAll(typedStructure(defined, structures.get(defined),
                            statement.get(k)));
                }
            } else {
                kept.add(statement);
            }
        }
        // HANDLE 型: 宣言を POINTER にし、H=>X を H->#T.X に、NEW(:T:) を NEW('#T') に読み替える
        Map<String, String> handles = new java.util.HashMap<>();
        for (int s = 0; s < kept.size(); s++) {
            List<Token> statement = kept.get(s);
            int k = keywordAt(statement);
            if (k >= 0 && (statement.get(k).is("DCL") || statement.get(k).is("DECLARE"))) {
                kept.set(s, rewriteParts(statement, k, part -> List.of(handle(part, structures,
                        handles))));
            }
        }
        for (int s = 0; s < kept.size(); s++) {
            kept.set(s, handleReferences(kept.get(s), handles, structures));
        }
        // TYPE を展開する
        for (int s = 0; s < kept.size(); s++) {
            List<Token> statement = kept.get(s);
            int k = keywordAt(statement);
            if (k >= 0 && (statement.get(k).is("DCL") || statement.get(k).is("DECLARE"))) {
                kept.set(s, rewriteParts(statement, k, part -> expandType(part, structures,
                        aliases, ordinals, ordinalVariables)));
            }
        }
        if (!ordinals.isEmpty()) {
            // 変数の型は TYPE を展開して分かる。そのあとで ORDINAL の参照を読み替える
            for (int s = 0; s < kept.size(); s++) {
                kept.set(s, ordinalReferences(kept.get(s), ordinals, ordinalVariables));
            }
        }
        // LIKE を展開する。LIKE で作った構造をさらに LIKE で参照できるよう、繰り返す
        for (int round = 0; round < 8; round++) {
            Map<String, List<List<Token>>> declared = new java.util.HashMap<>();
            Set<String> minor = new java.util.HashSet<>();
            for (List<Token> statement : kept) {
                int k = keywordAt(statement);
                if (k >= 0 && (statement.get(k).is("DCL") || statement.get(k).is("DECLARE"))) {
                    recordStructures(parts(statement, k), declared, minor);
                }
            }
            boolean changed = false;
            for (int s = 0; s < kept.size(); s++) {
                List<Token> statement = kept.get(s);
                int k = keywordAt(statement);
                if (k >= 0 && (statement.get(k).is("DCL") || statement.get(k).is("DECLARE"))) {
                    List<Token> rewritten = rewriteParts(statement, k,
                            part -> expandLike(part, declared, minor));
                    if (!rewritten.equals(statement)) {
                        kept.set(s, rewritten);
                        changed = true;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        List<Token> out = new ArrayList<>();
        kept.forEach(out::addAll);
        return out;
    }

    /** 文の頭の名札を飛ばしたところ。無ければ -1。 */
    private static int keywordAt(List<Token> statement) {
        int i = 0;
        while (i + 1 < statement.size() && statement.get(i).kind() == Kind.IDENT
                && statement.get(i + 1).is(":")) {
            i += 2;
        }
        return i < statement.size() && statement.get(i).kind() == Kind.IDENT ? i : -1;
    }

    /** 宣言の文の、深さ 0 の ',' で分けた項目。末尾の ';' は含めない。 */
    private static List<List<Token>> parts(List<Token> statement, int keyword) {
        List<List<Token>> parts = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;
        for (int i = keyword + 1; i < statement.size(); i++) {
            Token t = statement.get(i);
            if ((t.is(";") && depth == 0) || t.kind() == Kind.EOF) {
                break;
            }
            if (t.is("(")) depth++;
            if (t.is(")")) depth--;
            if (t.is(",") && depth == 0) {
                parts.add(current);
                current = new ArrayList<>();
            } else {
                current.add(t);
            }
        }
        parts.add(current);
        return parts;
    }

    private static List<Token> rewriteParts(List<Token> statement, int keyword,
                                            java.util.function.Function<List<Token>,
                                                    List<List<Token>>> rewrite) {
        List<Token> out = new ArrayList<>(statement.subList(0, keyword + 1));
        List<List<Token>> parts = parts(statement, keyword);
        Token last = statement.get(statement.size() - 1);
        boolean first = true;
        for (List<Token> part : parts) {
            for (List<Token> replaced : rewrite.apply(part)) {
                if (!first) {
                    out.add(new Token(Kind.SYMBOL, ",", replaced.isEmpty() ? last.line()
                            : replaced.get(0).line(), 0));
                }
                out.addAll(replaced);
                first = false;
            }
        }
        out.add(last);
        return out;
    }

    /** DEFINE を読む。DEFINE STRUCTURE なら型の名を返す。 */
    private static String define(List<Token> statement, int k,
                                 Map<String, List<List<Token>>> structures,
                                 Map<String, List<Token>> aliases) {
        Token kind = statement.get(k + 1);
        if (kind.is("ALIAS") && statement.size() > k + 3) {
            aliases.put(statement.get(k + 2).text(),
                    List.copyOf(statement.subList(k + 3, statement.size() - 1)));
            return null;
        }
        if (kind.is("STRUCTURE") || kind.is("STRUCT")) {
            List<List<Token>> parts = parts(statement, k + 1);
            List<Token> root = parts.get(0);
            int name = root.size() > 0 && root.get(0).kind() == Kind.NUMBER ? 1 : 0;
            if (name >= root.size() || root.get(name).kind() != Kind.IDENT) {
                throw new ParseFailure(kind, "DEFINE STRUCTURE needs a level-1 name");
            }
            if (root.size() > name + 1) {
                throw new ParseFailure(root.get(name + 1), "attributes of the DEFINE STRUCTURE"
                        + " name are not supported yet: " + root.get(name + 1).text());
            }
            structures.put(root.get(name).text(), List.copyOf(parts.subList(1, parts.size())));
            return root.get(name).text();
        }
        throw new ParseFailure(kind, "DEFINE " + kind.text() + " is not supported yet");
    }

    /**
     * {@code DEFINE ORDINAL T(要素 [VALUE(n)], ...)} (LRM "DEFINE ORDINAL")。要素は名前付きの
     * 定数になる。VALUE の無い要素は前の値 + 1 (最初は 0)。要素の定数の宣言の字句を返す。
     */
    private static List<Token> ordinal(List<Token> statement, int k,
                                       Map<String, Map<String, Integer>> ordinals) {
        Token at = statement.get(k + 1);
        if (k + 3 >= statement.size() || statement.get(k + 2).kind() != Kind.IDENT
                || !statement.get(k + 3).is("(")) {
            throw new ParseFailure(at, "DEFINE ORDINAL name(members) expected");
        }
        int close = k + 3;
        int depth = 0;
        for (int i = k + 3; i < statement.size(); i++) {
            if (statement.get(i).is("(")) depth++;
            if (statement.get(i).is(")") && --depth == 0) {
                close = i;
                break;
            }
        }
        Map<String, Integer> members = new LinkedHashMap<>();
        int next = 0;
        List<Token> declaration = new ArrayList<>();
        declaration.add(new Token(Kind.IDENT, "DCL", at.line(), at.column()));
        List<Token> inner = statement.subList(k + 4, close);
        List<List<Token>> items = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        depth = 0;
        for (Token t : inner) {
            if (t.is("(")) depth++;
            if (t.is(")")) depth--;
            if (t.is(",") && depth == 0) {
                items.add(current);
                current = new ArrayList<>();
            } else {
                current.add(t);
            }
        }
        items.add(current);
        for (List<Token> item : items) {
            if (item.isEmpty() || item.get(0).kind() != Kind.IDENT) {
                throw new ParseFailure(at, "ORDINAL member name expected");
            }
            int value = next;
            if (item.size() > 1) {
                if (item.size() != 5 || !item.get(1).is("VALUE") || !item.get(2).is("(")
                        || item.get(3).kind() != Kind.NUMBER || !item.get(4).is(")")) {
                    throw new ParseFailure(item.get(1), "ORDINAL member VALUE(integer) expected");
                }
                value = Integer.parseInt(item.get(3).text());
            }
            members.put(item.get(0).text(), value);
            next = value + 1;
            if (declaration.size() > 1) {
                declaration.add(new Token(Kind.SYMBOL, ",", at.line(), at.column()));
            }
            Token name = item.get(0);
            declaration.addAll(List.of(name, new Token(Kind.IDENT, "FIXED", name.line(), 0),
                    new Token(Kind.IDENT, "BIN", name.line(), 0),
                    new Token(Kind.SYMBOL, "(", name.line(), 0),
                    new Token(Kind.NUMBER, "31", name.line(), 0),
                    new Token(Kind.SYMBOL, ")", name.line(), 0),
                    new Token(Kind.IDENT, "VALUE", name.line(), 0),
                    new Token(Kind.SYMBOL, "(", name.line(), 0),
                    new Token(Kind.NUMBER, String.valueOf(value), name.line(), 0),
                    new Token(Kind.SYMBOL, ")", name.line(), 0)));
        }
        ordinals.put(statement.get(k + 2).text(), members);
        declaration.add(statement.get(statement.size() - 1));
        return declaration;
    }

    /**
     * ORDINALNAME / ORDINALSUCC / ORDINALPRED に型の要素の表を 2 つ目の引数として足し、
     * FIRST(T) / LAST(T) を値に置き換える。型は引数の中の ORDINAL の変数・要素・型の名で決める。
     */
    private static List<Token> ordinalReferences(List<Token> statement,
                                                 Map<String, Map<String, Integer>> ordinals,
                                                 Map<String, String> variables) {
        List<Token> out = new ArrayList<>(statement);
        for (int i = out.size() - 1; i >= 0; i--) {
            Token t = out.get(i);
            if (!(t.is("ORDINALNAME") || t.is("ORDINALSUCC") || t.is("ORDINALPRED"))
                    || i + 1 >= out.size() || !out.get(i + 1).is("(")) {
                continue;
            }
            int depth = 0;
            int close = -1;
            for (int j = i + 1; j < out.size(); j++) {
                if (out.get(j).is("(")) depth++;
                if (out.get(j).is(")") && --depth == 0) {
                    close = j;
                    break;
                }
            }
            String type = null;
            for (int j = i + 2; j < close && type == null; j++) {
                String word = out.get(j).text();
                if (variables.containsKey(word)) type = variables.get(word);
                else if (ordinals.containsKey(word)) type = word;
                else {
                    for (Map.Entry<String, Map<String, Integer>> o : ordinals.entrySet()) {
                        if (o.getValue().containsKey(word)) type = o.getKey();
                    }
                }
            }
            if (type == null) {
                throw new ParseFailure(t, t.text() + " needs an ORDINAL argument");
            }
            StringBuilder table = new StringBuilder();
            for (Map.Entry<String, Integer> member : ordinals.get(type).entrySet()) {
                if (!table.isEmpty()) table.append(',');
                table.append(member.getKey()).append('=').append(member.getValue());
            }
            out.add(close, new Token(Kind.STRING, table.toString(), t.line(), t.column()));
            out.add(close, new Token(Kind.SYMBOL, ",", t.line(), t.column()));
        }
        for (int i = out.size() - 4; i >= 0; i--) {
            Token t = out.get(i);
            if ((t.is("FIRST") || t.is("LAST")) && out.get(i + 1).is("(")
                    && ordinals.containsKey(out.get(i + 2).text()) && out.get(i + 3).is(")")) {
                List<Integer> values = new ArrayList<>(ordinals.get(out.get(i + 2).text())
                        .values());
                int value = t.is("FIRST") ? values.get(0) : values.get(values.size() - 1);
                for (int r = 0; r < 4; r++) out.remove(i);
                out.add(i, new Token(Kind.NUMBER, String.valueOf(value), t.line(), t.column()));
            }
        }
        return out;
    }

    /** {@code DCL 1 #T BASED(#T#P), 要素...; DCL #T#P POINTER;} の字句。 */
    private static List<List<Token>> typedStructure(String type, List<List<Token>> members,
                                                    Token at) {
        java.util.function.BiFunction<Kind, String, Token> t =
                (kind, text) -> new Token(kind, text, at.line(), at.column());
        List<Token> structure = new ArrayList<>(List.of(t.apply(Kind.IDENT, "DCL"),
                t.apply(Kind.NUMBER, "1"), t.apply(Kind.IDENT, "#" + type),
                t.apply(Kind.IDENT, "BASED"), t.apply(Kind.SYMBOL, "("),
                t.apply(Kind.IDENT, "#" + type + "#P"), t.apply(Kind.SYMBOL, ")")));
        for (List<Token> member : members) {
            structure.add(t.apply(Kind.SYMBOL, ","));
            structure.addAll(member);
        }
        structure.add(t.apply(Kind.SYMBOL, ";"));
        List<Token> pointer = List.of(t.apply(Kind.IDENT, "DCL"),
                t.apply(Kind.IDENT, "#" + type + "#P"), t.apply(Kind.IDENT, "POINTER"),
                t.apply(Kind.SYMBOL, ";"));
        return List.of(structure, pointer);
    }

    /** {@code H HANDLE T} / {@code H HANDLE(T)} を POINTER にし、H の型を覚える。 */
    private static List<Token> handle(List<Token> part, Map<String, List<List<Token>>> structures,
                                      Map<String, String> handles) {
        int at = indexAtDepth0(part, "HANDLE");
        if (at < 0) {
            return part;
        }
        int typeAt = at + 1 < part.size() && part.get(at + 1).is("(") ? at + 2 : at + 1;
        if (typeAt >= part.size() || part.get(typeAt).kind() != Kind.IDENT
                || !structures.containsKey(part.get(typeAt).text())) {
            throw new ParseFailure(part.get(at), "HANDLE needs a type defined by DEFINE"
                    + " STRUCTURE");
        }
        int name = levelOf(part) > 0 ? 1 : 0;
        handles.put(part.get(name).text(), part.get(typeAt).text());
        List<Token> out = new ArrayList<>(part.subList(0, at));
        out.add(new Token(Kind.IDENT, "POINTER", part.get(at).line(), part.get(at).column()));
        out.addAll(part.subList(typeAt + (typeAt == at + 2 ? 2 : 1), part.size()));
        return out;
    }

    private static List<Token> handleReferences(List<Token> statement, Map<String, String> handles,
                                                Map<String, List<List<Token>>> structures) {
        List<Token> out = new ArrayList<>();
        for (int i = 0; i < statement.size(); i++) {
            Token token = statement.get(i);
            if (token.kind() == Kind.IDENT && handles.containsKey(token.text())
                    && i + 2 < statement.size() && statement.get(i + 1).is("=>")
                    && statement.get(i + 2).kind() == Kind.IDENT) {
                out.add(token);
                out.add(new Token(Kind.SYMBOL, "->", token.line(), token.column()));
                out.add(new Token(Kind.IDENT, "#" + handles.get(token.text()), token.line(),
                        token.column()));
                out.add(new Token(Kind.SYMBOL, ".", token.line(), token.column()));
                out.add(statement.get(i + 2));
                i += 2;
                continue;
            }
            if (token.is("NEW") && i + 5 < statement.size() && statement.get(i + 1).is("(")
                    && statement.get(i + 2).is(":") && statement.get(i + 4).is(":")
                    && statement.get(i + 5).is(")")
                    && structures.containsKey(statement.get(i + 3).text())) {
                out.add(token);
                out.add(statement.get(i + 1));
                out.add(new Token(Kind.STRING, "#" + statement.get(i + 3).text(), token.line(),
                        token.column()));
                out.add(statement.get(i + 5));
                i += 5;
                continue;
            }
            out.add(token);
        }
        return out;
    }

    private static int levelOf(List<Token> part) {
        return !part.isEmpty() && part.get(0).kind() == Kind.NUMBER
                && !part.get(0).text().contains(".") ? Integer.parseInt(part.get(0).text()) : 0;
    }

    /** 要素の項目を、段を {@code shift} だけ下げて写す。 */
    private static List<Token> shifted(List<Token> member, int shift) {
        List<Token> copy = new ArrayList<>(member);
        int level = levelOf(member);
        Token old = member.get(0);
        copy.set(0, new Token(Kind.NUMBER, String.valueOf(level + shift), old.line(),
                old.column()));
        return copy;
    }

    private static int indexAtDepth0(List<Token> part, String word) {
        int depth = 0;
        for (int i = 0; i < part.size(); i++) {
            Token t = part.get(i);
            if (t.is("(")) depth++;
            else if (t.is(")")) depth--;
            else if (depth == 0 && t.kind() == Kind.IDENT && t.is(word)) return i;
        }
        return -1;
    }

    private static List<List<Token>> expandType(List<Token> part,
                                                Map<String, List<List<Token>>> structures,
                                                Map<String, List<Token>> aliases,
                                                Map<String, Map<String, Integer>> ordinals,
                                                Map<String, String> ordinalVariables) {
        int at = indexAtDepth0(part, "TYPE");
        if (at < 0 || at + 1 >= part.size() || part.get(at + 1).kind() != Kind.IDENT) {
            return List.of(part);
        }
        String type = part.get(at + 1).text();
        List<Token> rest = new ArrayList<>(part.subList(0, at));
        List<Token> after = part.subList(at + 2, part.size());
        if (ordinals.containsKey(type)) {
            // ORDINAL の変数は FIXED BIN(31) の値を持つ (Zeus と同じ。実機の大きさは
            // PRECISION で決まり、突き合わせていない)
            Token t = part.get(at);
            ordinalVariables.put(part.get(levelOf(part) > 0 ? 1 : 0).text(), type);
            rest.addAll(List.of(new Token(Kind.IDENT, "FIXED", t.line(), t.column()),
                    new Token(Kind.IDENT, "BIN", t.line(), t.column()),
                    new Token(Kind.SYMBOL, "(", t.line(), t.column()),
                    new Token(Kind.NUMBER, "31", t.line(), t.column()),
                    new Token(Kind.SYMBOL, ")", t.line(), t.column())));
            rest.addAll(after);
            return List.of(rest);
        }
        if (aliases.containsKey(type)) {
            rest.addAll(aliases.get(type));
            rest.addAll(after);
            return List.of(rest);
        }
        if (!structures.containsKey(type)) {
            throw new ParseFailure(part.get(at + 1), "type " + type + " is not defined");
        }
        return withMembers(part, rest, after, structures.get(type), 1);
    }

    /** 構造の写しを作る。根は宣言の名と自分の属性、要素は写した元の段から下げる。 */
    private static List<List<Token>> withMembers(List<Token> part, List<Token> head,
                                                 List<Token> after, List<List<Token>> members,
                                                 int sourceLevel) {
        if (!part.isEmpty() && part.get(levelOf(part) > 0 ? 1 : 0).is("(")) {
            throw new ParseFailure(part.get(0), "a factored declaration of a structure type"
                    + " is not supported yet");
        }
        int level = Math.max(1, levelOf(part));
        List<Token> root = new ArrayList<>();
        root.add(new Token(Kind.NUMBER, String.valueOf(level), part.get(0).line(),
                part.get(0).column()));
        root.addAll(levelOf(head) > 0 ? head.subList(1, head.size()) : head);
        root.addAll(after);
        List<List<Token>> out = new ArrayList<>();
        out.add(root);
        for (List<Token> member : members) {
            out.add(shifted(member, level - sourceLevel));
        }
        return out;
    }

    /** 宣言の文の中の、段 1 の構造の要素の項目と、小構造の名を集める。 */
    private static void recordStructures(List<List<Token>> parts,
                                         Map<String, List<List<Token>>> declared,
                                         Set<String> minor) {
        for (int i = 0; i < parts.size(); i++) {
            List<Token> part = parts.get(i);
            int level = levelOf(part);
            if (level == 0 || part.size() < 2 || part.get(1).kind() != Kind.IDENT) {
                continue;
            }
            int end = i + 1;
            while (end < parts.size() && levelOf(parts.get(end)) > level) end++;
            if (end == i + 1) {
                continue;
            }
            if (level == 1) {
                declared.put(part.get(1).text(), List.copyOf(parts.subList(i + 1, end)));
            } else {
                minor.add(part.get(1).text());
            }
        }
    }

    private static List<List<Token>> expandLike(List<Token> part,
                                                Map<String, List<List<Token>>> declared,
                                                Set<String> minor) {
        int at = indexAtDepth0(part, "LIKE");
        if (at < 0 || at + 1 >= part.size() || part.get(at + 1).kind() != Kind.IDENT) {
            return List.of(part);
        }
        String reference = part.get(at + 1).text();
        if (at + 2 < part.size() && part.get(at + 2).is(".")) {
            throw new ParseFailure(part.get(at + 1), "LIKE of a qualified name is not"
                    + " supported yet: " + reference);
        }
        if (minor.contains(reference) && !declared.containsKey(reference)) {
            throw new ParseFailure(part.get(at + 1), "LIKE of a minor structure is not"
                    + " supported yet: " + reference);
        }
        List<List<Token>> members = declared.get(reference);
        if (members == null) {
            // 宣言されていない名と構造でない名は、読み終えてから名指しで断る
            return List.of(part);
        }
        List<Token> head = new ArrayList<>(part.subList(0, at));
        return withMembers(part, head, part.subList(at + 2, part.size()), members, 1);
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
                   Map<String, Procedure> procedures, boolean main,
                   Map<String, FileInfo> files, boolean noExecOps,
                   Map<String, List<GenericAlternative>> generics) {
    }

    /**
     * GENERIC の 1 つの選択肢 (LRM "GENERIC attribute")。{@code descriptors} は引数ごとの
     * FIXED / CHAR / BIT / POINTER / *。
     */
    record GenericAlternative(String entry, List<String> descriptors) {
        boolean matches(List<String> kinds) {
            return Parser.genericMatches(descriptors, kinds);
        }
    }

    /**
     * FILE の宣言に書いた属性のうち、開き方とレコードの形を決めるもの。
     *
     * @param output     {@code OUTPUT} を書いたか。OPEN に向きを書かなければこれで決まる
     * @param recordSize {@code ENV(... RECSIZE(n))} の n。書かなければ 0
     * @param variable   {@code ENV(V ...)} / {@code ENV(VB ...)} の可変長か。RECSIZE は 4 byte の
     *                   RDW を含む (Programming Guide "RECSIZE": for V-format, the maximum length
     *                   including the 4-byte prefix)
     */
    record FileInfo(boolean output, int recordSize, boolean variable, boolean keyed,
                    int keyLength, int keyLocation, boolean print) {
    }

    record Procedure(String name, List<String> parameters, List<Stmt> body) {
    }

    sealed interface Stmt permits Declare, Assign, Put, If, Loop, IterativeLoop, Call, Return,
            Block, GoTo, Label, OnEndFile, FileOperation, Sql, Leave, Iterate, Stop, Group, OnCondition, Revert, Signal,
            Prefixed, Select, Allocate, Free, AssignByName, Get, Fetch, Release {
    }

    /** {@code 構造 = 構造, BY NAME;} (LRM "Structure assignment BY NAME")。 */
    record AssignByName(String target, String source) implements Stmt {
    }

    /** {@code FETCH 入口 [TITLE(式)], ...;} (LRM "FETCH statement")。title は無ければ null。 */
    record Fetch(List<String> entries, List<Expr> titles) implements Stmt {
    }

    /** {@code RELEASE 入口, ...;} (LRM "RELEASE statement")。 */
    record Release(List<String> entries) implements Stmt {
    }

    /** 配列の断面の添字 {@code *} (LRM "Cross sections of arrays")。 */
    enum Asterisk { STAR }

    record Declare(List<Decl> declarations) implements Stmt {
    }

    /**
     * AREA(n) の記憶域は n byte に 16 byte の管理域を足したもの (LRM "AREA attribute")。
     * 管理域の頭の 4 byte に次の空きの位置を置く (この処理系の約束であり、実機の形ではない)。
     * OFFSET は AREA の頭からの byte の位置を FIXED BIN(31) の形で持つ。
     */
    enum Type { GROUP, CHAR, BINARY, DECIMAL, BIT, POINTER, FILE, ENTRY, PICTURE, AREA, OFFSET }

    /**
     * 宣言 1 つ。
     *
     * @param aligned {@code ALIGNED} なら真、{@code UNALIGNED} なら偽、書かなければ {@code null}
     *                (型ごとの既定、構造からは受け継ぐ。LRM "ALIGNED and UNALIGNED attributes")
     * @param varying {@code CHAR(n) VARYING}。記憶域は長さの半語と n byte (LRM Table 39)
     * @param dimensions 配列の次元ごとの上下限。配列でなければ空
     * @param initialItems 配列の {@code INITIAL} の並び。スカラーなら空で、{@code initial} を使う
     */
    /**
     * @param storage 記憶域の種類。{@code CONTROLLED}、{@code BASED}、または {@code null}
     *                (AUTOMATIC / STATIC)。CONTROLLED と、POINTER を書かない BASED は
     *                ALLOCATE で記憶域を得る (LRM "Storage control")
     */
    record Decl(String name, int level, Type type, int precision, int scale,
                Expr initial, String basedOn, Boolean aligned, boolean varying,
                List<Bound> dimensions, List<InitItem> initialItems, boolean unsigned,
                String storage, Defined defined, String unsupported, String picture) {

        Decl(String name, int level, Type type, int precision, int scale,
             Expr initial, String basedOn, Boolean aligned, boolean varying,
             List<Bound> dimensions, List<InitItem> initialItems) {
            this(name, level, type, precision, scale, initial, basedOn, aligned, varying,
                    dimensions, initialItems, false, null, null, null, null);
        }

        boolean controlled() {
            return "CONTROLLED".equals(storage);
        }

        /** 次元を ALLOCATE に書いたものへ置き換えた宣言。 */
        Decl withDimensions(List<Bound> bounds) {
            return new Decl(name, level, type, precision, scale, initial, basedOn, aligned,
                    varying, List.copyOf(bounds), initialItems, unsigned, storage, defined,
                    unsupported, picture);
        }

        /** 配列の要素の数。配列でなければ 1。 */
        int count() {
            int count = 1;
            for (Bound bound : dimensions) count *= bound.extent();
            return count;
        }
    }

    /**
     * {@code DEFINED 基 [(添字,...)] [POSITION(式)]} (LRM "DEFINED attribute")。
     *
     * @param subscripts 基の添字。iSUB は {@code ISUB_i} という名の参照にしてある。無ければ null
     * @param position   POSITION の式。無ければ null
     */
    record Defined(String base, List<Expr> subscripts, Expr position) {
    }

    /**
     * 配列の 1 つの次元の上下限 (LRM "DIMENSION attribute")。CONTROLLED の {@code (*)} は
     * {@link #STAR} で、上下限は ALLOCATE で決まる。
     */
    record Bound(int low, int high, Expr referExtent, String refer) {
        static final Bound STAR = new Bound(1, 0);

        Bound(int low, int high) {
            this(low, high, null, null);
        }

        /**
         * {@code 式 REFER(要素)} の上限 (LRM "REFER option")。上限は ALLOCATE のときに式で決まり、
         * そのあとは要素の今の値で決まる。宣言の時点の上限は仮に 1 とする。
         */
        static Bound refer(Expr referExtent, String refer) {
            return new Bound(1, 1, referExtent, refer);
        }

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
               List<FormatItem> format, boolean data, String file, Expr line) implements Stmt {

        Put(String string, boolean page, int skip, boolean edit, List<Expr> values,
            List<FormatItem> format) {
            this(string, page, skip, edit, values, format, false, null, null);
        }
    }

    /**
     * {@code GET STRING(s) EDIT(...)(...)} と {@code GET STRING(s) LIST(...)} (LRM "GET
     * statement")。ファイルからの GET はまだ持たない。
     */
    record Get(String string, boolean edit, List<Expr> targets, List<FormatItem> format)
            implements Stmt {
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
    record Loop(Expr whileCondition, Expr untilCondition, List<Stmt> body, String label)
            implements Stmt {
    }

    record IterativeLoop(String control, Expr start, Expr finish, Expr step,
                         List<Stmt> body, String label) implements Stmt {
    }

    record Call(String name, List<Expr> arguments) implements Stmt {
    }

    /** @param value 関数の手続きが返す値。{@code RETURN;} なら {@code null} */
    record Return(Expr value) implements Stmt {
    }

    /** いちばん内側の (名前を書けばその名前の) DO から出る。 */
    record Leave(String label) implements Stmt {
    }

    /** いちばん内側の DO の次の繰り返しへ進む。 */
    record Iterate(String label) implements Stmt {
    }

    /** 繰り返さない {@code DO; ... END;}。LEAVE で出られる (LRM "LEAVE statement")。 */
    record Group(String label, List<Stmt> body) implements Stmt {
    }

    /** 実行単位を終える。 */
    record Stop() implements Stmt {
    }

    record Block(List<Stmt> body) implements Stmt {
    }

    record GoTo(String label) implements Stmt {
    }

    record Label(String name) implements Stmt {
    }

    record OnEndFile(String file, List<Stmt> handler) implements Stmt {
    }

    /**
     * {@code ON 条件 on-unit}。条件は名と、ファイルや条件名の修飾 ({@code CONDITION(BAD)} の
     * BAD) で表す。{@code system} は {@code ON ... SYSTEM;} (既定の処置に戻す)。
     */
    record OnCondition(String condition, String qualifier, List<Stmt> handler, boolean system)
            implements Stmt {
    }

    record Revert(String condition, String qualifier) implements Stmt {
    }

    /**
     * {@code SELECT [(式)]; WHEN (式,...) 文 ... [OTHERWISE 文] END;}。主語の式が無ければ、
     * WHEN の式を真偽として見る (LRM "SELECT statement")。
     */
    record Select(Expr subject, List<When> whens, Stmt otherwise) implements Stmt {
    }

    record When(List<Expr> values, Stmt action) {
    }

    record Signal(String condition, String qualifier) implements Stmt {
    }

    /** {@code ALLOCATE 名[(上限,...)] [SET(locator)], ...;} (LRM "ALLOCATE statement")。 */
    record Allocate(List<AllocateItem> items) implements Stmt {
    }

    /**
     * @param extents ALLOCATE に書いた次元の上限 (下限は 1)。書かなければ空で、宣言の次元を使う
     * @param set     {@code SET(locator)} の POINTER の名。無ければ {@code null}
     */
    record AllocateItem(String name, List<Expr> extents, String set, String in) {
    }

    record Free(List<String> names) implements Stmt {
    }

    /** 条件の前置き {@code (SIZE): 文}。{@code enabled} は有効にする条件、{@code disabled} は無効。 */
    record Prefixed(List<String> enabled, List<String> disabled, Stmt statement)
            implements Stmt {
    }

    enum FileAction { OPEN, READ, WRITE, CLOSE, REWRITE, DELETE, LOCATE }

    /**
     * @param title {@code OPEN ... TITLE(式)} の式。ファイルを結ぶ DD 名を開くときに決める
     *              (LRM "TITLE option")。無ければ {@code null} で、ファイルの名が DD 名になる
     */
    record FileOperation(FileAction action, String file, String target, Expr title,
                         Expr key, Expr lineSize, Expr pageSize) implements Stmt {

        FileOperation(FileAction action, String file, String target, Expr title) {
            this(action, file, target, title, null, null, null);
        }

        FileOperation(FileAction action, String file, String target, Expr title, Expr key) {
            this(action, file, target, title, key, null, null);
        }
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
            if (List.of("^=", "¬=", "<=", ">=", "||", "**", "=>", "->").contains(two)) {
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
            collectDefaults();
            while (!check(Kind.EOF) && !procedureAhead()) {
                at++;
            }
            if (check(Kind.EOF)) {
                throw fail(peek(), "a labeled PROCEDURE was not found");
            }
            ProcedureBuilder main = parseProcedure();
            // LIKE と OFFSET が指す名は、あとに書かれていてもよい。全部読んでから確かめる
            for (NamedReference reference : namedReferences) {
                Boolean structure = declaredNames.get(reference.name.toUpperCase(Locale.ROOT));
                if (structure == null) {
                    throw new ParseFailure(reference.token, reference.name + " is not declared");
                }
                if (reference.structure && !structure) {
                    throw new ParseFailure(reference.token, reference.name
                            + " is not a structure (LIKE requires a structure)");
                }
            }
            for (Token reference : locateReferences) {
                if (!"BASED".equals(storageClasses.get(reference.text()))) {
                    throw new ParseFailure(reference, "LOCATE needs a BASED variable: "
                            + reference.text());
                }
            }
            for (StorageReference reference : storageReferences) {
                String storage = storageClasses.get(reference.name);
                if (storage == null) {
                    throw new ParseFailure(reference.token, reference.name + " is not declared");
                }
                if (reference.area && !areaNames.contains(reference.name)) {
                    throw new ParseFailure(reference.token, reference.name + " is not an AREA");
                }
                if (!reference.area && storage.isEmpty()) {
                    throw new ParseFailure(reference.token, reference.name
                            + " is not BASED or CONTROLLED");
                }
            }
            for (Map.Entry<Token, List<Expr>> call : calls) {
                List<GenericAlternative> alternatives = generics.get(call.getKey().text());
                if (alternatives == null) continue;
                List<String> kinds = new ArrayList<>();
                for (Expr argument : call.getValue()) {
                    kinds.add(argument instanceof Literal literal
                            ? (literal.value() instanceof String ? "CHAR"
                                    : literal.value() instanceof FixedValue ? "FIXED" : null)
                            : null);
                }
                if (kinds.stream().allMatch(java.util.Objects::nonNull) && alternatives.stream()
                        .noneMatch(a -> genericMatches(a.descriptors(), kinds))) {
                    throw new ParseFailure(call.getKey(), "no GENERIC alternative of "
                            + call.getKey().text() + " matches " + kinds);
                }
            }
            return new ParseResult(new Program(main.name, main.parameters,
                    List.copyOf(main.body), Map.copyOf(main.procedures), main.main,
                    Map.copyOf(files), main.noExecOps, Map.copyOf(generics)), List.of());
        }

        /** FILE の宣言の属性。名は大文字。 */
        private final Map<String, FileInfo> files = new java.util.HashMap<>();

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
            parameters.forEach(p -> parameterNames.add(p.toUpperCase(Locale.ROOT)));
            // 入口属性のうち要るのは OPTIONS(MAIN) だけである。RETURNS その他は保持しない
            List<Token> attributes = collectToSemicolon();
            ProcedureBuilder result = new ProcedureBuilder(name, List.copyOf(parameters));
            boolean outerReturns = returnsValue;
            returnsValue = attributes.stream().anyMatch(token -> token.is("RETURNS"));
            // 手続きの中の LEAVE は、外の手続きの DO へは出られない
            List<String> outerLoops = List.copyOf(loops);
            loops.clear();
            for (int i = 0; i + 2 < attributes.size(); i++) {
                if (attributes.get(i).is("OPTIONS") && attributes.get(i + 1).is("(")) {
                    int close = findClosing(attributes, i + 1);
                    result.main = attributes.subList(i + 2, close).stream()
                            .anyMatch(token -> token.is("MAIN"));
                    result.noExecOps = attributes.subList(i + 2, close).stream()
                            .anyMatch(token -> token.is("NOEXECOPS"));
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
                    returnsValue = outerReturns;
                    loops.addAll(outerLoops);
                    return result;
                }
                result.body.add(labelled());
            }
            throw fail(peek(), "procedure " + name + " has no matching END");
        }

        /** 文を 1 つ読み、それが名前なら次の文に渡すために覚える。 */
        private Stmt labelled() {
            Stmt statement = statement();
            pendingLabel = statement instanceof Label label ? label.name() : null;
            return statement;
        }

        private Stmt statement() {
            if (check("%") && (check(1, "PAGE") || check(1, "SKIP") || check(1, "PRINT")
                    || check(1, "NOPRINT") || check(1, "PUSH") || check(1, "POP"))) {
                // 翻訳の listing だけを整える指示で、実行には何も起こさない (LRM "%PAGE directive" ほか)
                skipToSemicolon();
                return new Block(List.of());
            }
            if (match(";")) {
                // 空の文 (null statement)。名札だけを置くときに書く (LRM "Null statement")
                return new Block(List.of());
            }
            if (match("DCL") || match("DECLARE")) {
                return declaration();
            }
            if (match("DFT") || match("DEFAULT")) {
                // 規則は宣言を読む前に集めてある (collectDefaults)
                skipToSemicolon();
                return new Block(List.of());
            }
            if (match("FETCH")) {
                List<String> entries = new ArrayList<>();
                List<Expr> titles = new ArrayList<>();
                do {
                    entries.add(expect(Kind.IDENT, "entry name after FETCH").text());
                    titles.add(match("TITLE") ? parenthesizedExpression() : null);
                } while (match(","));
                expect(";");
                return new Fetch(List.copyOf(entries), java.util.Collections.unmodifiableList(
                        new ArrayList<>(titles)));
            }
            if (match("RELEASE")) {
                List<String> entries = new ArrayList<>();
                do {
                    entries.add(expect(Kind.IDENT, "entry name after RELEASE").text());
                } while (match(","));
                expect(";");
                return new Release(List.copyOf(entries));
            }
            if (match("GET")) {
                if (!check("STRING")) {
                    throw fail(previous(), "statement GET without STRING is not supported yet");
                }
                next();
                expect("(");
                String string = qualifiedName();
                expect(")");
                boolean edit;
                if (match("EDIT")) {
                    edit = true;
                } else if (match("LIST")) {
                    edit = false;
                } else {
                    throw fail(peek(), "GET STRING supports only EDIT and LIST yet");
                }
                List<Expr> targets = arguments();
                List<FormatItem> format = edit ? formatList() : List.of();
                expect(";");
                return new Get(string, edit, targets, format);
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
                Expr value = null;
                if (check("(") && !returnsValue) {
                    // 値を返せるのは RETURNS を書いた手続きだけである (LRM "RETURN statement")
                    throw fail(previous(), "RETURN with a value is not allowed in a procedure"
                            + " without RETURNS");
                }
                if (match("(")) {
                    value = expression();
                    expect(")");
                }
                expect(";");
                return new Return(value);
            }
            if (match("LEAVE") || match("ITERATE")) {
                boolean leave = previous().is("LEAVE");
                Token verb = previous();
                String label = check(Kind.IDENT) ? next().text() : null;
                if (loops.isEmpty()) {
                    throw fail(verb, "LEAVE / ITERATE outside a DO group");
                }
                if (label != null && !loops.contains(label.toUpperCase(java.util.Locale.ROOT))) {
                    throw fail(verb, "no enclosing DO group is named " + label);
                }
                expect(";");
                return leave ? new Leave(label) : new Iterate(label);
            }
            if (check("STOP") && check(1, ";")) {
                next();
                next();
                return new Stop();
            }
            if (match("ALLOCATE") || match("ALLOC")) {
                List<AllocateItem> items = new ArrayList<>();
                do {
                    Token name = expect(Kind.IDENT, "name after ALLOCATE");
                    storageReferences.add(new StorageReference(name.text(), name, false));
                    List<Expr> extents = List.of();
                    if (check("(")) {
                        extents = arguments();
                    }
                    String set = null;
                    String area = null;
                    while (check("SET") || check("IN")) {
                        boolean in = next().is("IN");
                        expect("(");
                        Token locator = expect(Kind.IDENT, in ? "area name" : "locator name");
                        expect(")");
                        if (in) {
                            storageReferences.add(new StorageReference(locator.text(), locator,
                                    true));
                            area = locator.text();
                        } else {
                            set = locator.text();
                        }
                    }
                    items.add(new AllocateItem(name.text(), extents, set, area));
                } while (match(","));
                expect(";");
                return new Allocate(List.copyOf(items));
            }
            if (match("FREE")) {
                List<String> names = new ArrayList<>();
                do {
                    Token name = expect(Kind.IDENT, "name after FREE");
                    storageReferences.add(new StorageReference(name.text(), name, false));
                    names.add(name.text());
                } while (match(","));
                expect(";");
                return new Free(List.copyOf(names));
            }
            if (match("WRITE")) {
                return fileOperation(FileAction.WRITE);
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
            if (match("SELECT")) {
                Expr subject = check("(") ? parenthesizedExpression() : null;
                expect(";");
                List<When> whens = new ArrayList<>();
                Stmt otherwise = null;
                while (!check("END")) {
                    if (match("WHEN")) {
                        whens.add(new When(arguments(), labelled()));
                    } else if (match("OTHERWISE") || match("OTHER")) {
                        otherwise = labelled();
                    } else {
                        throw fail(peek(), "WHEN or OTHERWISE expected in SELECT, found "
                                + peek().text());
                    }
                }
                expect("END");
                if (check(Kind.IDENT)) {
                    next();
                }
                expect(";");
                return new Select(subject, List.copyOf(whens), otherwise);
            }
            if (match("REVERT") || match("SIGNAL")) {
                boolean revert = previous().is("REVERT");
                String condition = conditionName();
                String qualifier = qualifier();
                expect(";");
                return revert ? new Revert(condition, qualifier) : new Signal(condition, qualifier);
            }
            if (check("(") && check(1, Kind.IDENT) && conditionPrefixAhead()) {
                // 条件の前置き (SIZE): 文。その文のあいだだけ条件を有効・無効にする
                next();
                List<String> enabled = new ArrayList<>();
                List<String> disabled = new ArrayList<>();
                do {
                    String name = expect(Kind.IDENT, "condition prefix").text()
                            .toUpperCase(java.util.Locale.ROOT);
                    if (name.startsWith("NO")) {
                        disabled.add(name.substring(2));
                    } else {
                        enabled.add(name);
                    }
                } while (match(","));
                expect(")");
                expect(":");
                return new Prefixed(enabled, disabled, statement());
            }
            if (match("OPEN")) {
                return fileOperation(FileAction.OPEN);
            }
            if (match("LOCATE")) {
                // LOCATE 変数 FILE(f) [SET(p)] (LRM "LOCATE statement")。target に変数、
                // title の位置に SET の POINTER を置く
                Token variable = expect(Kind.IDENT, "BASED variable after LOCATE");
                locateReferences.add(variable);
                expect("FILE");
                expect("(");
                String file = qualifiedName();
                expect(")");
                Expr set = null;
                if (match("SET")) {
                    set = new Reference(parenthesizedExpressionName());
                }
                expect(";");
                return new FileOperation(FileAction.LOCATE, file, variable.text(), set, null);
            }
            if (match("REWRITE")) {
                return fileOperation(FileAction.REWRITE);
            }
            if (check("DELETE") && check(1, "FILE")) {
                next();
                return fileOperation(FileAction.DELETE);
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
                    if (match(",")) {
                        expect("BY");
                        expect("NAME");
                        expect(";");
                        if (!subscripts.isEmpty() || !(value instanceof Reference source)) {
                            throw fail(previous(), "BY NAME needs structure names on both sides");
                        }
                        return new AssignByName(target, source.name());
                    }
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

        /** 宣言した名と、それが構造 (下位の項目を持つ) か。 */
        private final Map<String, Boolean> declaredNames = new java.util.HashMap<>();

        /** LIKE や OFFSET(領域) が指す名。読み終えてから確かめる。 */
        private record NamedReference(String name, Token token, boolean structure) {
        }

        private final List<NamedReference> namedReferences = new ArrayList<>();

        /** 最上位の名の記憶域の種類 ({@link Decl#storage}、無ければ空)。 */
        private final Map<String, String> storageClasses = new java.util.HashMap<>();

        /** AREA を宣言した名。 */
        private final Set<String> areaNames = new java.util.HashSet<>();

        /** 引数の名。(*) の次元は引数なら書ける。 */
        private final Set<String> parameterNames = new java.util.HashSet<>();

        /** ALLOCATE と FREE が指す名と、IN に書いた名。読み終えてから確かめる。 */
        private record StorageReference(String name, Token token, boolean area) {
        }

        private final List<StorageReference> storageReferences = new ArrayList<>();

        /** GENERIC の名と選択肢。 */
        private final Map<String, List<GenericAlternative>> generics = new java.util.HashMap<>();

        /** CALL の名と引数。GENERIC の選択を読み終えてから確かめる。 */
        private final List<Map.Entry<Token, List<Expr>>> calls = new ArrayList<>();

        /** LOCATE の変数。BASED でなければならない。 */
        private final List<Token> locateReferences = new ArrayList<>();

        private String parenthesizedExpressionName() {
            expect("(");
            String name = expect(Kind.IDENT, "locator name").text();
            expect(")");
            return name;
        }

        /**
         * 宣言の属性のうち、翻訳で断るべき組み合わせを確かめる (LRM "Data attributes")。
         * 以前は知らない属性を読み飛ばしていたので、範囲外の精度や文字の COMPLEX を通していた。
         */
        private void checkAttributes(List<Token> part, TypeInfo info) {
            for (int i = 0; i < part.size(); i++) {
                Token token = part.get(i);
                if (token.is("LIKE") && i + 1 < part.size()) {
                    namedReferences.add(new NamedReference(part.get(i + 1).text(), part.get(i + 1),
                            true));
                }
                if (token.is("OFFSET") && i + 2 < part.size() && part.get(i + 1).is("(")) {
                    namedReferences.add(new NamedReference(part.get(i + 2).text(), part.get(i + 2),
                            false));
                }
                if (token.is("COMPLEX") && info.type != Type.BINARY && info.type != Type.DECIMAL) {
                    throw new ParseFailure(token, "COMPLEX requires an arithmetic type");
                }
            }
            if (info.type == Type.DECIMAL && (info.precision < 1 || info.precision > 31)) {
                throw new ParseFailure(part.get(0), "FIXED DECIMAL precision " + info.precision
                        + " is out of range (1 to 31)");
            }
            if (info.type == Type.BINARY && (info.precision < 1 || info.precision > 63)) {
                throw new ParseFailure(part.get(0), "FIXED BINARY precision " + info.precision
                        + " is out of range (1 to 63)");
            }
        }

        private List<Decl> parseDeclarations(List<Token> declaration) {
            List<Decl> decls = parseDeclarationList(declaration);
            checkRefer(decls, declaration.isEmpty() ? peek() : declaration.get(0));
            for (int i = 0; i < decls.size(); i++) {
                boolean structure = decls.get(i).level() > 0 && i + 1 < decls.size()
                        && decls.get(i + 1).level() > decls.get(i).level();
                declaredNames.merge(decls.get(i).name().toUpperCase(Locale.ROOT), structure,
                        Boolean::logicalOr);
            }
            return decls;
        }

        private List<Decl> parseDeclarationList(List<Token> declaration) {
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
                    TypeInfo info = typeInfo(part, close + 1, level > 0 ? inheritedType : null,
                            inheritedPrecision, inheritedScale);
                    for (String name : names) {
                        out.add(declaration(name, level, info.type == null
                                ? defaulted(name, part) : info, part, dimensions));
                    }
                    if (info.type == null) {
                        continue;
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
                TypeInfo info = typeInfo(part, p, level > 0 ? Type.GROUP : null,
                        inheritedPrecision, inheritedScale);
                if (info.type == null) {
                    // 型の属性の無い最上位の名。以前は前の項目の型を受け継いでいた。属性は
                    // ',' を越えて受け継がれない (括弧でくくった名の並びだけが分け合う)
                    info = defaulted(nameToken.text(), part);
                }
                out.add(declaration(nameToken.text(), level, info, part, dimensions));
                inheritedType = info.type;
                inheritedPrecision = info.precision;
                inheritedScale = info.scale;
            }
            return List.copyOf(out);
        }

        /** DEFAULT / DFT の RANGE(a:b) の規則。 */
        private record DefaultRule(char low, char high, List<Token> attributes) {
        }

        private final List<DefaultRule> defaultRules = new ArrayList<>();

        /** 型を既定で決めた FLOAT の名。宣言は通し、使ったら止める。 */
        private boolean defaultFloat;

        /**
         * 型の属性の無い最上位の名の型 (LRM "Language-specified defaults")。BUILTIN と
         * CONDITION の宣言は記憶域を持たない。DEFAULT RANGE の規則に合えばその属性、
         * そうでなければ I から N で始まる名は FIXED BINARY(15)、ほかは FLOAT DECIMAL(6) である。
         */
        private TypeInfo defaulted(String name, List<Token> part) {
            if (part.stream().anyMatch(t -> t.is("BUILTIN") || t.is("CONDITION")
                    || t.is("COND"))) {
                return new TypeInfo(Type.ENTRY, 0, 0);
            }
            char first = Character.toUpperCase(name.charAt(0));
            for (DefaultRule rule : defaultRules) {
                if (first >= rule.low() && first <= rule.high()) {
                    TypeInfo info = typeInfo(rule.attributes(), 0, null, 0, 0);
                    if (info.type != null) {
                        return info;
                    }
                    if (unsupportedAttribute(rule.attributes()) != null) {
                        defaultFloat = true;
                        return new TypeInfo(Type.DECIMAL, 6, 0);
                    }
                }
            }
            if (first >= 'I' && first <= 'N') {
                return new TypeInfo(Type.BINARY, 15, 0);
            }
            defaultFloat = true;
            return new TypeInfo(Type.DECIMAL, 6, 0);
        }

        /** {@code DEFAULT RANGE(a:b) 属性;} を、宣言を読む前に集める。 */
        private void collectDefaults() {
            for (int i = 0; i + 1 < tokens.size(); i++) {
                Token token = tokens.get(i);
                if (!(token.is("DFT") || token.is("DEFAULT"))
                        || (i > 0 && !tokens.get(i - 1).is(";"))) {
                    continue;
                }
                int end = i;
                while (end < tokens.size() && !tokens.get(end).is(";")) end++;
                List<Token> statement = tokens.subList(i + 1, end);
                if (statement.size() < 6 || !statement.get(0).is("RANGE")
                        || !statement.get(1).is("(") || !statement.get(3).is(":")
                        || !statement.get(5).is(")")) {
                    throw new ParseFailure(token, "only DEFAULT RANGE(a:b) attributes is"
                            + " supported yet");
                }
                defaultRules.add(new DefaultRule(Character.toUpperCase(statement.get(2).text()
                        .charAt(0)), Character.toUpperCase(statement.get(4).text().charAt(0)),
                        List.copyOf(statement.subList(6, statement.size()))));
            }
        }

        private Decl declaration(String name, int level, TypeInfo info, List<Token> part,
                                 List<Bound> dimensions) {
            generic(name, part);
            boolean floatByDefault = defaultFloat;
            defaultFloat = false;
            checkAttributes(part, info);
            if (info.type == Type.FILE) {
                int size = 0;
                for (int i = 0; i + 1 < part.size(); i++) {
                    if (part.get(i).is("RECSIZE")) {
                        size = parenthesizedInt(part, i + 1, 0);
                    }
                }
                int keyLength = 0;
                int keyLocation = 1;
                for (int i = 0; i + 1 < part.size(); i++) {
                    if (part.get(i).is("KEYLENGTH") || part.get(i).is("KEYLEN")) {
                        keyLength = parenthesizedInt(part, i + 1, 0);
                    }
                    if (part.get(i).is("KEYLOC")) {
                        keyLocation = parenthesizedInt(part, i + 1, 1);
                    }
                }
                boolean keyed = part.stream().anyMatch(token -> token.is("KEYED"));
                if (keyed && keyLength == 0) {
                    // 鍵の長さと位置はデータセット (VSAM の定義) からも決まる。それを読む道を
                    // まだ持たないので、ENV に書いていなければ断る
                    throw new ParseFailure(part.get(0), "a KEYED file needs ENV(KEYLENGTH(n))"
                            + " yet: " + name);
                }
                files.put(name.toUpperCase(java.util.Locale.ROOT), new FileInfo(
                        part.stream().anyMatch(token -> token.is("OUTPUT")), size,
                        part.stream().anyMatch(token -> token.is("V") || token.is("VB")),
                        keyed, keyLength, keyLocation,
                        part.stream().anyMatch(token -> token.is("PRINT"))));
            }
            if (!dimensions.isEmpty() && info.type == Type.GROUP) {
                // 構造の配列は、要素と要素の間の詰め物の規則を確かめていないので断る。
                // 要素がスカラーの配列 (2 A(5) FIXED BIN) は持つ
                throw new ParseFailure(part.get(0), "arrays of structures are not supported yet: "
                        + name);
            }
            // UNSIGNED の FIXED BINARY は符号ビットを持たず、記憶域は p ≦ 8 で 1 byte、
            // ≦ 16 で半語、≦ 32 で全語である (LRM "SIGNED and UNSIGNED attributes")
            boolean unsigned = info.type == Type.BINARY
                    && part.stream().anyMatch(token -> token.is("UNSIGNED"));
            String storage = part.stream().anyMatch(t -> t.is("CONTROLLED") || t.is("CTL"))
                    ? "CONTROLLED" : part.stream().anyMatch(t -> t.is("BASED")) ? "BASED"
                    : part.stream().anyMatch(t -> t.is("STATIC")) ? "STATIC" : null;
            if (dimensions.contains(Bound.STAR) && !"CONTROLLED".equals(storage)
                    && !parameterNames.contains(name.toUpperCase(Locale.ROOT))) {
                // (*) は上下限を後で決める。決めるのは ALLOCATE (CONTROLLED) か引数である
                throw new ParseFailure(part.get(0), "array bounds (*) require CONTROLLED"
                        + " or a parameter: " + name);
            }
            if (level <= 1) {
                storageClasses.put(name.toUpperCase(Locale.ROOT),
                        storage == null || storage.equals("STATIC") ? "" : storage);
                if (part.stream().anyMatch(t -> t.is("AREA"))) {
                    areaNames.add(name.toUpperCase(Locale.ROOT));
                }
            }
            return new Decl(name, level, info.type, info.precision, info.scale,
                    dimensions.isEmpty() ? initial(part) : null, basedOn(part), alignment(part),
                    varying(part), dimensions,
                    dimensions.isEmpty() ? List.of() : initialItems(part), unsigned, storage,
                    defined(part), floatByDefault ? "FLOAT" : unsupportedAttribute(part),
                    info.type == Type.PICTURE ? pictureSpecification(part) : null);
        }

        /**
         * 名の直後の {@code (n)}、{@code (lo:hi)}、{@code (n, m)} を次元として読む (LRM "DIMENSION
         * attribute")。以前は読み飛ばしていたので、{@code DCL A(10) FIXED BIN} が 1 つの変数に
         * なっていた。構造の中の配列は、要素がスカラーなら持つ (構造の配列は {@link #declaration} が断る)。
         */
        private List<Bound> dimensions(List<Token> part, int at, int level) {
            if (at >= part.size() || !part.get(at).is("(")) {
                return List.of();
            }
            int close = findClosing(part, at);
            List<Bound> bounds = new ArrayList<>();
            for (List<Token> dimension : split(part.subList(at + 1, close), ",")) {
                if (dimension.size() == 1 && dimension.get(0).is("*")) {
                    bounds.add(Bound.STAR);
                    continue;
                }
                int referAt = -1;
                for (int i = 0; i < dimension.size(); i++) {
                    if (dimension.get(i).is("REFER")) referAt = i;
                }
                if (referAt > 0) {
                    List<Token> inner = new ArrayList<>(dimension.subList(0, referAt));
                    Token last = dimension.get(referAt);
                    inner.add(new Token(Kind.EOF, "<EOF>", last.line(), last.column()));
                    Expr extent = new Parser(fileName, inner).expression();
                    if (referAt + 3 >= dimension.size() || !dimension.get(referAt + 1).is("(")
                            || dimension.get(referAt + 2).kind() != Kind.IDENT
                            || !dimension.get(referAt + 3).is(")")) {
                        throw new ParseFailure(dimension.get(referAt), "REFER(member) expected");
                    }
                    bounds.add(Bound.refer(extent, dimension.get(referAt + 2).text()));
                    continue;
                }
                List<List<Token>> ends = split(dimension, ":");
                int low = ends.size() == 2 ? bound(ends.get(0), part.get(at)) : 1;
                int high = bound(ends.get(ends.size() - 1), part.get(at));
                if (ends.size() > 2 || high < low) {
                    throw new ParseFailure(part.get(at), "bad array bounds");
                }
                bounds.add(new Bound(low, high));
            }
            return List.copyOf(bounds);
        }

        /**
         * REFER は BASED の構造の中にだけ書け、その対象は同じ構造の、前に宣言したスカラーの
         * 要素でなければならない (LRM "REFER option")。
         */
        private static void checkRefer(List<Decl> decls, Token where) {
            Decl root = null;
            for (int i = 0; i < decls.size(); i++) {
                Decl decl = decls.get(i);
                if (decl.level() <= 1) {
                    root = decl;
                }
                for (Bound bound : decl.dimensions()) {
                    if (bound.refer() == null) {
                        continue;
                    }
                    if (root == null || root == decl || !"BASED".equals(root.storage())) {
                        throw new ParseFailure(where, "REFER is allowed only in a BASED structure: "
                                + decl.name());
                    }
                    boolean found = false;
                    for (int j = decls.indexOf(root) + 1; j < i; j++) {
                        Decl member = decls.get(j);
                        if (member.name().equalsIgnoreCase(bound.refer())
                                && member.dimensions().isEmpty() && member.type() != Type.GROUP) {
                            found = true;
                        }
                    }
                    if (!found) {
                        throw new ParseFailure(where, "REFER object " + bound.refer()
                                + " must be a scalar member of the structure declared before "
                                + decl.name());
                    }
                }
            }
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
                    if (i + 2 < tokens.size() && tokens.get(i + 1).is("(")
                            && tokens.get(i + 2).is("*")) {
                        // CHAR(*) は引数の長さを受け継ぐ (LRM "Parameter attributes")。長さ -1 で表す
                        return new TypeInfo(Type.CHAR, -1, 0);
                    }
                    return new TypeInfo(Type.CHAR, parenthesizedInt(tokens, i + 1, 1), 0);
                }
                if (tokens.get(i).is("BIT")) {
                    return new TypeInfo(Type.BIT, parenthesizedInt(tokens, i + 1, 1), 0);
                }
                if (tokens.get(i).is("POINTER") || tokens.get(i).is("PTR")) {
                    return new TypeInfo(Type.POINTER, 0, 0);
                }
                if (tokens.get(i).is("AREA")) {
                    // 大きさを省けば 1000 byte (LRM "AREA attribute")
                    return new TypeInfo(Type.AREA, parenthesizedInt(tokens, i + 1, 1000), 0);
                }
                if (tokens.get(i).is("OFFSET")) {
                    return new TypeInfo(Type.OFFSET, 0, 0);
                }
                if (tokens.get(i).is("FILE")) {
                    return new TypeInfo(Type.FILE, 0, 0);
                }
                if (tokens.get(i).is("ENTRY")) {
                    // VARIABLE なら入口の値を持つ変数 (精度 1 で印す)。そうでなければ入口の宣言
                    return new TypeInfo(Type.ENTRY, tokens.stream()
                            .anyMatch(t -> t.is("VARIABLE")) ? 1 : 0, 0);
                }
                if (tokens.get(i).is("GENERIC")) {
                    return new TypeInfo(Type.ENTRY, 0, 0);
                }
                if (tokens.get(i).is("PIC") || tokens.get(i).is("PICTURE")) {
                    if (i + 1 >= tokens.size() || tokens.get(i + 1).kind() != Kind.STRING) {
                        throw new ParseFailure(tokens.get(i), "PICTURE needs a quoted specification");
                    }
                    return picture(tokens.get(i + 1));
                }
                if ((tokens.get(i).is("BIN") || tokens.get(i).is("BINARY")
                        || tokens.get(i).is("DEC") || tokens.get(i).is("DECIMAL"))
                        && tokens.subList(i + 1, tokens.size()).stream()
                                .anyMatch(t -> t.is("FIXED"))) {
                    // BIN FIXED(31) の語順。精度は FIXED の後ろにも BIN の後ろにも書ける。
                    // 以前は FIXED の直後に BIN が無いと型を見つけられず、前の項目の型を受け継いでいた
                    boolean binary = tokens.get(i).is("BIN") || tokens.get(i).is("BINARY");
                    int fixedAt = i + 1;
                    while (!tokens.get(fixedAt).is("FIXED")) fixedAt++;
                    int at = i + 1 < tokens.size() && tokens.get(i + 1).is("(") ? i + 1 : fixedAt + 1;
                    return binary
                            ? new TypeInfo(Type.BINARY, parenthesizedInt(tokens, at, 15),
                                    parenthesizedSecondInt(tokens, at, 0))
                            : new TypeInfo(Type.DECIMAL, parenthesizedInt(tokens, at, 5),
                                    parenthesizedSecondInt(tokens, at, 0));
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
                    boolean baseLater = tokens.subList(j, tokens.size()).stream()
                            .anyMatch(t -> t.is("BIN") || t.is("BINARY") || t.is("DEC")
                                    || t.is("DECIMAL"));
                    if (!baseLater) {
                        // FIXED だけなら DECIMAL である (LRM "Default attributes")。
                        // 以前は前の項目の型を受け継いでいた
                        return new TypeInfo(Type.DECIMAL, parenthesizedInt(tokens, j, 5),
                                parenthesizedSecondInt(tokens, j, 0));
                    }
                    if (j < tokens.size() && tokens.get(j).is("(")) {
                        // FIXED(p,q) BIN の語順。精度は FIXED の後ろにある
                        int close = j;
                        while (!tokens.get(close).is(")")) close++;
                        if (close + 1 < tokens.size() && (tokens.get(close + 1).is("BIN")
                                || tokens.get(close + 1).is("BINARY"))) {
                            return new TypeInfo(Type.BINARY, parenthesizedInt(tokens, j, 15),
                                    parenthesizedSecondInt(tokens, j, 0));
                        }
                        if (close + 1 < tokens.size() && (tokens.get(close + 1).is("DEC")
                                || tokens.get(close + 1).is("DECIMAL"))) {
                            return new TypeInfo(Type.DECIMAL, parenthesizedInt(tokens, j, 5),
                                    parenthesizedSecondInt(tokens, j, 0));
                        }
                    }
                }
            }
            return new TypeInfo(inherited, inheritedPrecision, inheritedScale);
        }

        /** GENERIC(入口 WHEN(記述,...), ...) を読む。 */
        private void generic(String name, List<Token> tokens) {
            int at = -1;
            for (int i = 0; i + 1 < tokens.size(); i++) {
                if (tokens.get(i).is("GENERIC") && tokens.get(i + 1).is("(")) at = i + 1;
            }
            if (at < 0) {
                return;
            }
            int close = findClosing(tokens, at);
            List<GenericAlternative> alternatives = new ArrayList<>();
            for (List<Token> item : split(tokens.subList(at + 1, close), ",")) {
                if (item.size() < 3 || item.get(0).kind() != Kind.IDENT || !item.get(1).is("WHEN")
                        || !item.get(2).is("(")) {
                    throw new ParseFailure(item.isEmpty() ? tokens.get(at) : item.get(0),
                            "GENERIC alternative 'entry WHEN(descriptors)' expected");
                }
                int end = findClosing(item, 2);
                List<String> descriptors = new ArrayList<>();
                for (List<Token> descriptor : split(item.subList(3, end), ",")) {
                    String word = descriptor.isEmpty() ? "*" : descriptor.get(0).text();
                    String kind = switch (word) {
                        case "FIXED", "BIN", "BINARY", "DEC", "DECIMAL" -> "FIXED";
                        case "CHAR", "CHARACTER" -> "CHAR";
                        case "BIT" -> "BIT";
                        case "POINTER", "PTR" -> "POINTER";
                        case "*" -> "*";
                        default -> throw new ParseFailure(descriptor.get(0),
                                "GENERIC descriptor " + word + " is not supported yet");
                    };
                    descriptors.add(kind);
                }
                alternatives.add(new GenericAlternative(item.get(0).text(), List.copyOf(descriptors)));
            }
            generics.put(name.toUpperCase(Locale.ROOT), List.copyOf(alternatives));
        }

        /** 選択肢の記述が引数の種類に合うか。種類が分からない (null) 引数はどれにも合うとみなす。 */
        static boolean genericMatches(List<String> descriptors, List<String> kinds) {
            if (descriptors.size() != kinds.size()) {
                return false;
            }
            for (int i = 0; i < kinds.size(); i++) {
                String d = descriptors.get(i);
                if (!d.equals("*") && kinds.get(i) != null && !d.equals(kinds.get(i))) {
                    return false;
                }
            }
            return true;
        }

        /** 宣言の PICTURE の指定の字。 */
        private static String pictureSpecification(List<Token> tokens) {
            for (int i = 0; i + 1 < tokens.size(); i++) {
                if ((tokens.get(i).is("PIC") || tokens.get(i).is("PICTURE"))
                        && tokens.get(i + 1).kind() == Kind.STRING) {
                    return tokens.get(i + 1).text();
                }
            }
            return null;
        }

        /** まだ持たない型の属性。宣言は翻訳で通し、実行でその名を使うと止まる。 */
        private static final Set<String> UNSUPPORTED_TYPES = Set.of("FLOAT", "COMPLEX", "CPLX", "WIDECHAR", "WCHAR", "GRAPHIC", "LABEL", "FORMAT",
                "TASK", "EVENT", "ORDINAL");

        /**
         * 宣言に書かれた、まだ持たない型の属性の名。無ければ null。
         *
         * <p>以前は知らない型の属性を読み飛ばし、前の項目の型を受け継いでいた。
         * {@code DCL F FLOAT DEC(6)} が構造になり、{@code DCL Z COMPLEX FIXED DEC(7,2)} が実数に
         * なっていた。宣言そのものは正しい PL/I なので翻訳では断らず、使ったときに止める。
         * {@code BIN} / {@code DEC} だけで {@code FIXED} の無いものは FLOAT である (LRM
         * "Default attributes")。
         */
        private static String unsupportedAttribute(List<Token> tokens) {
            int depth = 0;
            boolean base = false;
            boolean fixed = false;
            boolean picture = false;
            for (int i = 0; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                if (t.is("(")) depth++;
                else if (t.is(")")) depth--;
                else if (depth == 0 && t.kind() == Kind.IDENT) {
                    if (t.is("DEFINED") || t.is("DEF") || t.is("LIKE") || t.is("TYPE")) {
                        i++; // 次は名であり、属性ではない
                        continue;
                    }
                    if (UNSUPPORTED_TYPES.contains(t.text())) {
                        return t.text();
                    }
                    base |= t.is("BIN") || t.is("BINARY") || t.is("DEC") || t.is("DECIMAL");
                    fixed |= t.is("FIXED");
                    picture |= t.is("PIC") || t.is("PICTURE");
                }
            }
            return base && !fixed && !picture ? "FLOAT" : null;
        }

        /** DEFINED の基と添字と POSITION を読む。iSUB ({@code 1SUB}) は ISUB_1 の名にする。 */
        private Defined defined(List<Token> tokens) {
            int at = -1;
            int depth = 0;
            for (int i = 0; i < tokens.size(); i++) {
                if (tokens.get(i).is("(")) depth++;
                else if (tokens.get(i).is(")")) depth--;
                else if (depth == 0 && (tokens.get(i).is("DEFINED") || tokens.get(i).is("DEF"))) {
                    at = i;
                }
            }
            if (at < 0) {
                return null;
            }
            if (at + 1 >= tokens.size() || tokens.get(at + 1).kind() != Kind.IDENT) {
                throw new ParseFailure(tokens.get(at), "DEFINED needs a base variable");
            }
            List<Token> inner = new ArrayList<>();
            int end = at + 1;
            // 基の名 (修飾してよい)、添字、POSITION を、宣言の残りの属性から切り出す
            while (end < tokens.size()) {
                Token t = tokens.get(end);
                if (t.is("POSITION") || t.is("POS")) {
                    break;
                }
                if (t.is("(")) {
                    int close = findClosing(tokens, end);
                    for (int i = end; i <= close; i++) {
                        Token u = tokens.get(i);
                        if (u.kind() == Kind.NUMBER && i + 1 < close
                                && tokens.get(i + 1).is("SUB")) {
                            inner.add(new Token(Kind.IDENT, "ISUB_" + u.text(), u.line(),
                                    u.column()));
                            i++;
                        } else {
                            inner.add(u);
                        }
                    }
                    end = close + 1;
                    break;
                }
                if (t.kind() != Kind.IDENT && !t.is(".")) {
                    break;
                }
                if (t.kind() == Kind.IDENT && !inner.isEmpty() && !inner.get(inner.size() - 1)
                        .is(".")) {
                    break;
                }
                inner.add(t);
                end++;
            }
            Token last = tokens.get(Math.min(end, tokens.size()) - 1);
            inner.add(new Token(Kind.EOF, "<EOF>", last.line(), last.column()));
            Parser base = new Parser(fileName, inner);
            String name = base.qualifiedName();
            List<Expr> subscripts = base.check("(") ? base.arguments() : null;
            Expr position = null;
            if (end < tokens.size() && (tokens.get(end).is("POSITION") || tokens.get(end).is("POS"))
                    && end + 1 < tokens.size() && tokens.get(end + 1).is("(")) {
                int close = findClosing(tokens, end + 1);
                List<Token> positionTokens = new ArrayList<>(tokens.subList(end + 2, close));
                positionTokens.add(new Token(Kind.EOF, "<EOF>", tokens.get(close).line(),
                        tokens.get(close).column()));
                position = new Parser(fileName, positionTokens).expression();
            }
            return new Defined(name, subscripts, position);
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
                // VALUE は名前付きの定数である (LRM "VALUE attribute")。値の読みは INIT と同じに
                // する。以前は読み飛ばしていたので、定数が 0 や空白になっていた。定数へ代入しても
                // 断らない (翻訳の検査をまだ持たない)
                if ((tokens.get(i).is("INIT") || tokens.get(i).is("INITIAL")
                        || tokens.get(i).is("VALUE"))
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
            boolean data = false;
            String putFile = null;
            Expr line = null;
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
                        // SYSPRINT 以外は PRINT のストリームのファイルだけを持つ (実行で確かめる)
                        putFile = file.text();
                    }
                } else if (match("LINE")) {
                    line = parenthesizedExpression();
                } else if ((check("LIST") || check("EDIT")) && edit == null) {
                    edit = next().is("EDIT");
                    values = arguments();
                    if (edit) {
                        format = formatList();
                    }
                } else if (check("DATA") && edit == null && string != null) {
                    // data-directed (名=値;) は STRING への出力だけを持つ
                    next();
                    data = true;
                    edit = false;
                    values = arguments();
                } else {
                    throw fail(option, "PUT option " + option.text() + " is not supported yet");
                }
            }
            if (string != null && (page || skip > 0 || edit == null || (!edit && !data))) {
                // PAGE と SKIP はファイルにしか書けない。STRING への LIST は区切りと引用符の規則が
                // PRINT ファイルと違い、まだ持たない
                throw fail(peek(), "PUT STRING supports only EDIT without PAGE or SKIP");
            }
            expect(";");
            if (line != null && putFile == null) {
                throw fail(peek(), "PUT LINE is supported only for PRINT files other than"
                        + " SYSPRINT yet");
            }
            return new Put(string, page, skip, edit != null && edit, values, format, data, putFile,
                    line);
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

        /** いま翻訳している手続きが RETURNS を持つか。 */
        private boolean returnsValue;
        /** 囲んでいる DO の名前 (無ければ空)。内側が先頭。 */
        private final java.util.ArrayDeque<String> loops = new java.util.ArrayDeque<>();
        /** 直前に読んだ文の名前。次の DO がこれを自分の名前にする。 */
        private String pendingLabel;

        private Stmt loop() {
            String label = pendingLabel == null ? "" : pendingLabel;
            pendingLabel = null;
            loops.push(label.toUpperCase(java.util.Locale.ROOT));
            try {
                return loop(label.isEmpty() ? null : label);
            } finally {
                loops.pop();
            }
        }

        private Stmt loop(String label) {
            if (check(Kind.IDENT) && check(1, "=")) {
                String control = next().text();
                expect("=");
                Expr start = expression();
                expect("TO");
                Expr finish = expression();
                Expr step = match("BY") ? expression()
                        : new Literal(java.math.BigDecimal.ONE);
                expect(";");
                return new IterativeLoop(control, start, finish, step, blockBody(), label);
            }
            if (match(";")) {
                // Type 1。繰り返さず、1 度だけ実行する。以前は条件の無い繰り返しとして扱い、
                // IF ... THEN DO; ... END; が上限まで回っていた
                return new Group(label, blockBody());
            }
            if (match("LOOP") || match("FOREVER")) {
                expect(";");
                return new Loop(null, null, blockBody(), label);
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
            return new Loop(whileCondition, untilCondition, blockBody(), label);
        }

        private List<Stmt> blockBody() {
            List<Stmt> body = new ArrayList<>();
            while (!check("END") && !check(Kind.EOF)) {
                body.add(labelled());
            }
            expect("END");
            if (check(Kind.IDENT)) {
                next();
            }
            expect(";");
            return List.copyOf(body);
        }

        private Stmt call() {
            Token at = peek();
            String name = qualifiedName();
            List<Expr> args = check("(") ? arguments() : List.of();
            calls.add(Map.entry(at, args));
            expect(";");
            return new Call(name, args);
        }

        /** 持っている条件。ほかの条件は断る。 */
        private static final java.util.Set<String> CONDITIONS = java.util.Set.of(
                "ENDFILE", "ENDPAGE", "KEY", "CONVERSION", "CONV", "ZERODIVIDE", "ZDIV", "SIZE",
                "FIXEDOVERFLOW", "FOFL", "CONDITION", "COND", "ERROR");

        private String conditionName() {
            Token name = expect(Kind.IDENT, "condition name");
            String upper = name.text().toUpperCase(java.util.Locale.ROOT);
            if (!CONDITIONS.contains(upper)) {
                throw fail(name, "ON " + name.text() + " is not supported yet");
            }
            return switch (upper) {
                case "CONV" -> "CONVERSION";
                case "ZDIV" -> "ZERODIVIDE";
                case "FOFL" -> "FIXEDOVERFLOW";
                case "COND" -> "CONDITION";
                default -> upper;
            };
        }

        private String qualifier() {
            if (!match("(")) {
                return null;
            }
            String name = qualifiedName().toUpperCase(java.util.Locale.ROOT);
            expect(")");
            return name;
        }

        /** {@code (名 [, 名]...):} が続くか。 */
        private boolean conditionPrefixAhead() {
            int k = 1;
            while (check(k, Kind.IDENT) && check(k + 1, ",")) {
                k += 2;
            }
            return check(k, Kind.IDENT) && check(k + 1, ")") && check(k + 2, ":");
        }

        private Stmt onCondition() {
            String condition = conditionName();
            String qualifier = qualifier();
            match("SNAP");
            if (match("SYSTEM")) {
                expect(";");
                return new OnCondition(condition, qualifier, List.of(), true);
            }
            List<Stmt> handler;
            if (match("BEGIN")) {
                expect(";");
                handler = blockBody();
            } else {
                // on-unit は 1 つの文でもよい (LRM "ON statement")
                handler = List.of(statement());
            }
            if (condition.equals("ENDFILE")) {
                return new OnEndFile(qualifier, handler);
            }
            return new OnCondition(condition, qualifier, handler, false);
        }

        private Stmt fileOperation(FileAction action) {
            expect("FILE");
            expect("(");
            String file = qualifiedName();
            expect(")");
            String target = null;
            if (action == FileAction.READ || action == FileAction.WRITE
                    || action == FileAction.REWRITE) {
                expect(action == FileAction.READ ? "INTO" : "FROM");
                expect("(");
                target = qualifiedName();
                expect(")");
            }
            Expr key = null;
            if (action == FileAction.WRITE ? match("KEYFROM")
                    : (action != FileAction.OPEN && action != FileAction.CLOSE && match("KEY"))) {
                key = parenthesizedExpression();
            } else if (action == FileAction.DELETE) {
                throw fail(peek(), "DELETE needs KEY(...) yet");
            }
            Expr title = null;
            Expr lineSize = null;
            Expr pageSize = null;
            if (action == FileAction.OPEN) {
                // 向きと TITLE を読む。LINESIZE や PAGESIZE は断る (黙って捨てると行の形が変わる)
                while (!check(";")) {
                    if (match("OUTPUT") || match("INPUT") || match("UPDATE")) {
                        target = previous().text().toUpperCase(java.util.Locale.ROOT);
                    } else if (match("TITLE") && title == null) {
                        title = parenthesizedExpression();
                    } else if (match("LINESIZE") && lineSize == null) {
                        lineSize = parenthesizedExpression();
                    } else if (match("PAGESIZE") && pageSize == null) {
                        pageSize = parenthesizedExpression();
                    } else {
                        throw fail(peek(), "OPEN option " + peek().text()
                                + " is not supported yet");
                    }
                }
            }
            expect(";");
            return new FileOperation(action, file, target, title, key, lineSize, pageSize);
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
            Expr operand = primary();
            if (match("**")) {
                // べき乗は前置の演算子と同じいちばん高い優先度で、右から結ぶ
                // (LRM "Combinations of operations")。-2**2 は -(2**2) である
                return new Binary("**", operand, unary());
            }
            return operand;
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
                    Expr call = new Function(name, arguments());
                    while (check("(")) {
                        // TAB(1)(3): 入口の値を返したものを続けて呼ぶ
                        List<Expr> applied = new ArrayList<>();
                        applied.add(call);
                        applied.addAll(arguments());
                        call = new Function("#APPLY", List.copyOf(applied));
                    }
                    return call;
                }
                return new Reference(name);
            }
            if (check("*") && (check(1, ",") || check(1, ")"))) {
                // 添字の * は配列の断面である。添字の外の * は乗算なので、ここへは来ない
                next();
                return new Literal(Asterisk.STAR);
            }
            throw fail(peek(), "expression expected, found " + peek().text());
        }

        private String qualifiedName() {
            StringBuilder name = new StringBuilder(expect(Kind.IDENT, "identifier").text());
            while (match(".")) {
                name.append('.').append(expect(Kind.IDENT, "qualified identifier").text());
            }
            if (match("->")) {
                // locator で修飾した参照 Q->A (LRM "Locator qualification")。"Q->A" の名で運ぶ
                name.append("->").append(qualifiedName());
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

        private boolean check(int ahead, Kind kind) {
            return token(ahead).kind() == kind;
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
     *   <li>{@code X} / {@code A} / {@code 9} だけなら文字の PICTURE。CHAR と同じに扱う</li>
     *   <li>それ以外は数の PICTURE ({@link PliPicture})。FIXED DEC(数字の位置の数, V より右の数字の
     *       数 - F) の値を持ち、記憶域は編集した字の像である。以前は 9 と V だけを持っていた</li>
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
        if (!picture.isEmpty() && picture.matches("[XA9]*") && picture.matches(".*[XA].*")) {
            return new TypeInfo(Type.CHAR, picture.length(), 0);
        }
        PliPicture numeric;
        try {
            numeric = PliPicture.parse(specification.text());
        } catch (IllegalArgumentException invalid) {
            throw new ParseFailure(specification, "PICTURE '" + specification.text() + "': "
                    + invalid.getMessage());
        }
        if (numeric == null) {
            throw new ParseFailure(specification, "PICTURE '" + specification.text()
                    + "' mixes character and numeric characters");
        }
        return new TypeInfo(Type.PICTURE, numeric.precision, numeric.scale);
    }

    private static final class ProcedureBuilder {
        final String name;
        final List<String> parameters;
        final List<Stmt> body = new ArrayList<>();
        final Map<String, Procedure> procedures = new LinkedHashMap<>();
        boolean main;
        /** {@code OPTIONS(NOEXECOPS)}。PARM に実行時オプションを書かない (全部が引数)。 */
        boolean noExecOps;

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
