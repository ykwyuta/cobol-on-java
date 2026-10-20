package dev.cobolonjava.hlasm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code OPSYN} (HLASM Language Reference "OPSYN instruction")。
 *
 * <p>{@code 名前 OPSYN 命令} は、そこから後ろで「名前」を「命令」として読ませる。演算項が無ければ「名前」を
 * 消す (以後その命令は使えない)。定義はその時点の意味の<b>写し</b>であり、あとで「命令」のほうを
 * 別の OPSYN で変えても、先に作った名前は元の意味のままである ({@code OLDADD OPSYN AHI} のあとで
 * {@code AHI OPSYN SR} としても、{@code OLDADD} は加算のまま)。
 *
 * <p>マクロの展開より後ろで読み替えるので、マクロの名前を OPSYN で付け替えること、OPSYN で作った名前で
 * マクロを呼ぶことはできない。そのときは知らない命令として断られる (暫定判断 P-233)。
 */
final class OperationSynonyms {

    /** 消された命令の印。 */
    private static final String DELETED = "";

    /** OPSYN で別名を付けられる組み立ての命令 (機械命令のほか)。 */
    private static final Set<String> DIRECTIVES = Set.of("DC", "DS", "EQU", "ORG", "LTORG", "USING", "DROP",
            "CNOP", "DCB", "OPEN", "CLOSE", "PUT");

    private OperationSynonyms() {
    }

    static List<Statement> apply(List<Statement> statements) {
        Map<String, String> synonyms = new HashMap<>();
        List<Statement> out = new ArrayList<>(statements.size());
        for (Statement statement : statements) {
            String operation = statement.operation();
            if (operation.equals("OPSYN")) {
                define(statement, synonyms);
                continue;
            }
            String target = synonyms.get(operation);
            if (target == null) {
                out.add(statement);
            } else if (target.equals(DELETED)) {
                throw new AssemblyException(statement.line(),
                        "the operation " + operation + " was deleted by OPSYN");
            } else {
                out.add(new Statement(statement.label(), target, statement.operands(), statement.line(),
                        statement.identification()));
            }
        }
        return out;
    }

    private static void define(Statement statement, Map<String, String> synonyms) {
        String name = statement.label();
        if (name == null) {
            throw new AssemblyException(statement.line(), "OPSYN requires a name");
        }
        name = name.toUpperCase(Locale.ROOT);
        String operand = statement.operands().trim().toUpperCase(Locale.ROOT);
        if (operand.isEmpty()) {
            synonyms.put(name, DELETED);
            return;
        }
        // 写しを取る。operand がすでに別名なら、その時点の意味を引き継ぐ
        String meaning = synonyms.getOrDefault(operand, operand);
        if (meaning.equals(DELETED)
                || (Instructions.find(meaning) == null && !DIRECTIVES.contains(meaning))) {
            throw new AssemblyException(statement.line(),
                    "OPSYN requires a machine instruction or an assembler instruction: " + operand);
        }
        synonyms.put(name, meaning);
    }
}
