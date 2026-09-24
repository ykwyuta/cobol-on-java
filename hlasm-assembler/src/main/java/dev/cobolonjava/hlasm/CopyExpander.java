package dev.cobolonjava.hlasm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** COPY メンバを固定形式の原文へ挿入する。メンバ名の再帰は断る。 */
final class CopyExpander {

    private static final int MAX_DEPTH = 64;
    private static final Set<String> DIRECTIVES = Set.of(
            "CSECT", "START", "DSECT", "USING", "DROP", "DC", "DS", "EQU", "ORG",
            "LTORG", "END", "PRINT", "SPACE", "EJECT", "TITLE", "POP", "PUSH",
            "AMODE", "RMODE", "MACRO", "MEND", "MEXIT", "AIF", "AGO", "ANOP",
            "ACTR", "SETA", "SETB", "SETC", "GBLA", "GBLB", "GBLC", "LCLA",
            "LCLB", "LCLC", "MNOTE", "ENTRY", "EXTRN", "WXTRN", "COM", "RSECT",
            "CNOP", "COPY", "ICTL", "OPSYN");

    private CopyExpander() {
    }

    static String expand(String source, SourceLibrary library) {
        return loadLibraryMacros(expandCopies(source, library, new ArrayList<>()).source(), library);
    }

    /** 展開中に名前が確定したマクロを読むときは、COPY だけを先に展開する。 */
    static String expandMemberCopies(String source, SourceLibrary library) {
        return expandCopies(source, library, new ArrayList<>()).source();
    }

    static boolean isDirective(String operation) {
        return DIRECTIVES.contains(operation);
    }

    private record ExpandedSource(String source, boolean ended) {
    }

    private static ExpandedSource expandCopies(String source, SourceLibrary library,
                                               List<String> stack) {
        String[] lines = source.split("\r?\n", -1);
        List<Statement> statements = HlasmReader.read(source);
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        for (int k = 0; k < statements.size(); k++) {
            Statement statement = statements.get(k);
            int start = statement.line() - 1;
            int end = k + 1 < statements.size()
                    ? statements.get(k + 1).line() - 1 : lines.length;
            append(lines, cursor, start, result);
            if (statement.operation().equals("COPY")) {
                String member = statement.operands().trim().toUpperCase(Locale.ROOT);
                if (!member.matches("[A-Z@$#][A-Z0-9@$#]{0,7}")) {
                    throw new AssemblyException(statement.line(),
                            "COPY requires a literal library member name: " + member);
                }
                if (stack.contains(member) || stack.size() >= MAX_DEPTH) {
                    throw new AssemblyException(statement.line(),
                            "recursive COPY member: " + member);
                }
                String included;
                try {
                    included = library.find(member);
                } catch (RuntimeException failure) {
                    throw new AssemblyException(statement.line(),
                            "cannot read COPY member " + member + ": " + failure.getMessage());
                }
                if (included == null) {
                    throw new AssemblyException(statement.line(),
                            "COPY member not found: " + member);
                }
                stack.add(member);
                ExpandedSource expanded;
                try {
                    expanded = expandCopies(included, library, stack);
                } catch (AssemblyException failure) {
                    throw new AssemblyException(statement.line(),
                            "COPY " + member + " line " + failure.line() + ": "
                                    + failure.getMessage());
                } finally {
                    stack.remove(stack.size() - 1);
                }
                result.append(expanded.source());
                if (expanded.ended()) {
                    return new ExpandedSource(result.toString(), true);
                }
                if (!result.isEmpty() && result.charAt(result.length() - 1) != '\n') {
                    result.append('\n');
                }
            } else {
                append(lines, start, end, result);
                if (statement.operation().equals("END")) {
                    return new ExpandedSource(result.toString(), true);
                }
            }
            cursor = end;
        }
        append(lines, cursor, lines.length, result);
        return new ExpandedSource(result.toString(), false);
    }

    /** ライブラリマクロは呼出し名と同じメンバから読む。依存マクロも閉包まで辿る。 */
    private static String loadLibraryMacros(String source, SourceLibrary library) {
        StringBuilder definitions = new StringBuilder();
        Set<String> loaded = new HashSet<>();
        boolean changed;
        do {
            changed = false;
            List<Statement> statements = HlasmReader.read(definitions + source);
            for (int k = 0; k < statements.size(); k++) {
                Statement statement = statements.get(k);
                if (k > 0 && statements.get(k - 1).operation().equals("MACRO")) {
                    continue; // プロトタイプは呼出しではない
                }
                String name = statement.operation();
                if (name.startsWith("&") || DIRECTIVES.contains(name)
                        || Instructions.find(name) != null || loaded.contains(name)) {
                    continue;
                }
                String member;
                try {
                    member = library.find(name);
                } catch (RuntimeException failure) {
                    throw new AssemblyException(statement.line(),
                            "cannot read macro member " + name + ": " + failure.getMessage());
                }
                if (member == null) {
                    continue;
                }
                String body = expandCopies(member, library, new ArrayList<>()).source();
                List<Statement> macro = HlasmReader.read(body);
                if (macro.size() < 3 || !macro.get(0).operation().equals("MACRO")
                        || !macro.get(1).operation().equals(name)
                        || !macro.get(macro.size() - 1).operation().equals("MEND")) {
                    throw new AssemblyException(statement.line(),
                            "library member " + name + " must define macro " + name);
                }
                definitions.append(body);
                if (!body.endsWith("\n")) {
                    definitions.append('\n');
                }
                loaded.add(name);
                changed = true;
            }
        } while (changed);
        return definitions + source;
    }

    private static void append(String[] lines, int from, int to, StringBuilder out) {
        for (int k = from; k < to; k++) {
            out.append(lines[k]).append('\n');
        }
    }
}
