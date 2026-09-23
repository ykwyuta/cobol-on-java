package dev.cobolonjava.compiler;

import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.CompilerOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 翻訳オプションのうち、この処理系が<b>実際に効かせているか</b>を診断する (要件 FR-181、
 * 暫定判断 P-023)。
 *
 * <p>以前はどのオプションも受理して記録するだけだった。{@code ARITH(EXTEND)} を書いても
 * 中間結果は 30 桁で打ち切られ、{@code NUMPROC(PFD)} を書いても符号は正規化された。
 * 移行でいちばん気付きにくいのは「指定したのに効いていない」ことである。z/OS probe の
 * 変種 (CBLNUMP、CBLFUNE など) をこの処理系で流して、変種どうしに差が出ないことで
 * 見つかった。
 *
 * <p>分け方は 3 つである。
 * <ul>
 *   <li><b>黙って通す</b>: 効いているもの (SOURCEFORMAT、SSRANGE、CICS、SQL)、と
 *       この処理系の振る舞いと同じ値 (ARITH(COMPAT) と ARITH(EXTEND)、TRUNC(STD)、NUMPROC(NOPFD)、
 *       FLOAT(HEX)、CODEPAGE(1047)、DYNAM、QUOTE)</li>
 *   <li><b>警告して通す</b>: プログラムが計算する値を変えないもの。リスト、最適化、
 *       デバッグ情報、再入可能性 (RENT)。振る舞いの差が暫定判断に書いてあるもの
 *       (ADV は P-063、NODYNAM は P-032、NUMBER は P-080) もここに入れる。止めると、
 *       実資産 (Bank-of-Z の {@code CBL LIST,MAP,XREF} や {@code PROCESS NODYNAM})
 *       がそれだけで翻訳できなくなる</li>
 *   <li><b>断る</b>: 計算する値や記憶域のバイトを変えるのに、その値を実装していないもの。
 *       黙って既定で翻訳すると、実機と違う値を黙って返す (CLAUDE.md の増分の型 3)</li>
 * </ul>
 */
final class OptionSupport {

    /** 効いているオプション。値を問わない。 */
    private static final Set<String> EFFECTIVE = Set.of(
            "SOURCEFORMAT", "SSRANGE", "NOSSRANGE", "CICS", "NOCICS", "SQL", "NOSQL",
            "DYNAM");

    /** 値によって、この処理系の振る舞いと同じなら黙って通すオプション。 */
    private static final Map<String, Set<String>> IMPLEMENTED_VALUES = Map.of(
            "ARITH", Set.of("COMPAT", "C", "EXTEND", "E"),
            "TRUNC", Set.of("STD"),
            "NUMPROC", Set.of("NOPFD"),
            "FLOAT", Set.of("HEX", "S390"),
            "CODEPAGE", Set.of("1047", "01047", "IBM-1047"),
            "PGMNAME", Set.of("COMPAT", "CO"),
            "INTDATE", Set.of("ANSI"),
            "QUOTE", Set.of(""),
            "Q", Set.of(""));

    /** 値を変えるのに実装していない値を持ちうるオプション。値が違えば断る。 */
    private static final Set<String> RESULT_CHANGING = Set.of(
            "ARITH", "TRUNC", "NUMPROC", "FLOAT", "CODEPAGE", "APOST", "APOSTROPHE",
            "ZONEDATA", "NUMCHECK", "INTDATE", "PGMNAME", "CURRENCY");

    /** 計算する値を変えないので、警告して通すオプション。{@code NO} の付いた形も同じ。 */
    private static final Set<String> NO_EFFECT = Set.of(
            "LIST", "MAP", "XREF", "SOURCE", "OFFSET", "FLAG", "OPTIMIZE", "OPT", "TEST",
            "RENT", "NUMBER", "ADV", "DYNAM", "SEQ", "SEQUENCE", "OBJECT", "NAME", "LIB",
            "COMPILE", "TERMINAL", "TERM", "LINECOUNT", "SPACE", "WORD", "DATA", "DECK",
            "NSYMBOL",
            "STGOPT", "THREAD", "VBREF", "EXIT", "DUMP", "ADATA", "MDECK", "INVDATA",
            "RULES", "SUPPRMSG", "DISPSIGN", "HGPR", "AFP", "ARCH", "TUNE", "MAXPCF",
            "QUALIFY", "DLL", "EXPORTALL", "WSCLEAR", "LANGUAGE", "LANG", "BUFSIZE",
            "SIZE", "INITCHECK", "VLR", "SQLCCSID", "SQLIMS", "SERVICE");

    /** {@code NUMBER} のように、効いていない理由を暫定判断が書いているもの。 */
    private static final Map<String, String> REASONS = Map.of(
            "ADV", "carriage control is written as blank lines (P-063)",
            "NOADV", "carriage control is written as blank lines (P-063)",
            "NODYNAM", "every CALL is resolved at run time (P-032)",
            "NUMBER", "DEBUG-LINE is the line counted by this compiler (P-080)",
            "NORENT",
            "working storage is initialised the same way for RENT and NORENT (P-025)",
            "NSYMBOL", "national (N) literals are not supported yet");

    private OptionSupport() {
    }

    /** オプションの並びを診断する。止める診断があれば翻訳しない。 */
    static List<Diagnostic> check(CompilerOptions options) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (Map.Entry<String, String> option : options.values().entrySet()) {
            String name = option.getKey().toUpperCase(Locale.ROOT);
            String value = unquote(option.getValue()).toUpperCase(Locale.ROOT);
            String written = value.isEmpty() ? name : name + "(" + option.getValue() + ")";
            if (EFFECTIVE.contains(name)) {
                continue;
            }
            Set<String> implemented = IMPLEMENTED_VALUES.get(name);
            if (implemented != null && implemented.contains(value)) {
                continue;
            }
            if (RESULT_CHANGING.contains(name)) {
                diagnostics.add(new Diagnostic(null, "compiler option " + written
                        + " is not supported yet: it changes computed values, and this"
                        + " compiler would silently use its default (FR-181)"));
                continue;
            }
            String base = name.startsWith("NO") && NO_EFFECT.contains(name.substring(2))
                    ? name.substring(2) : name;
            if (NO_EFFECT.contains(base)) {
                String reason = REASONS.getOrDefault(name, "it has no effect on the result");
                diagnostics.add(Diagnostic.warning(null, "compiler option " + written
                        + " is accepted but not implemented: " + reason));
                continue;
            }
            diagnostics.add(Diagnostic.warning(null, "unknown compiler option " + written
                    + " is ignored"));
        }
        return diagnostics;
    }

    private static String unquote(String value) {
        String text = value == null ? "" : value.strip();
        return text.length() >= 2 && text.startsWith("'") && text.endsWith("'")
                ? text.substring(1, text.length() - 1) : text;
    }
}
