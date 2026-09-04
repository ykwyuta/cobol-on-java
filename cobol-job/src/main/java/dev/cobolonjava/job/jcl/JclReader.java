package dev.cobolonjava.job.jcl;

import dev.cobolonjava.job.JobDiagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JCL のカードを読む (要件 FR-131)。
 *
 * <p>やることは 3 つある。<b>注記を落とし、継続をつなぎ、埋め込みデータを切り出す</b>。
 * ここまでで書式の都合は尽きており、意味を取る側は 1 文ずつ見ればよくなる。
 *
 * <h2>カードの書式</h2>
 * <pre>
 * //名前     操作 オペランド                    注記
 * 12345678901234567890
 * </pre>
 *
 * <p>1〜2 桁が {@code //}。3 桁目が {@code *} なら注記カードである。名前欄は 3 桁目から
 * 始まり、空白まで。そのあとが操作、さらに空白のあとがオペランドである。
 * オペランドの外に空白が現れたら、そこから先は注記になる。73 桁目から先は見ない。
 *
 * <h2>継続</h2>
 * <p>オペランドがコンマで終わっていれば次のカードへ続く。続きのカードは名前欄が空である。
 */
public final class JclReader {

    /** JCL のカードは 72 桁までである。73 桁目から先は通番であり、意味を持たない。 */
    private static final int CARD_WIDTH = 72;

    private final String[] lines;
    private final List<JobDiagnostic> diagnostics;
    private int at;

    private JclReader(String[] lines, List<JobDiagnostic> diagnostics) {
        this.lines = lines;
        this.diagnostics = diagnostics;
    }

    /** JCL の本文をカードへ切る。 */
    public static List<JclCard> read(String text, List<JobDiagnostic> diagnostics) {
        return new JclReader(text.split("\n", -1), diagnostics).read();
    }

    private List<JclCard> read() {
        List<JclCard> cards = new ArrayList<>();
        while (at < lines.length) {
            int number = at + 1;
            String card = card(lines[at]);
            at++;
            if (card.isEmpty()) {
                continue;
            }
            if (card.startsWith("//*")) {
                continue;
            }
            if (card.startsWith("/*")) {
                // 区切りカード。埋め込みデータの外に現れたものは読み飛ばす
                continue;
            }
            if (!card.startsWith("//")) {
                diagnostics.add(new JobDiagnostic(number,
                        "a JCL statement starts with // : " + card));
                continue;
            }
            JclCard built = statement(card, number);
            if (built == null) {
                continue;
            }
            if (built.operation().equals("DD")) {
                byte[] inline = inlineOf(built);
                if (inline != null) {
                    built = new JclCard(built.name(), built.operation(), built.operands(),
                            inline, built.line());
                }
            }
            cards.add(built);
        }
        return cards;
    }

    /**
     * 1 文を読む。継続があればつなぐ。
     *
     * @return 名前欄と操作が取れなければ {@code null}
     */
    private JclCard statement(String card, int number) {
        String body = card.substring(2);
        String name = null;
        if (!body.isEmpty() && !Character.isWhitespace(body.charAt(0))) {
            int end = indexOfBlank(body);
            name = body.substring(0, end < 0 ? body.length() : end);
            body = end < 0 ? "" : body.substring(end);
        }
        String rest = body.stripLeading();
        if (rest.isEmpty()) {
            diagnostics.add(new JobDiagnostic(number, "a JCL statement needs an operation"));
            return null;
        }
        int end = indexOfBlank(rest);
        String operation = (end < 0 ? rest : rest.substring(0, end)).toUpperCase(Locale.ROOT);
        String operands = end < 0 ? "" : operands(rest.substring(end).stripLeading());
        return new JclCard(name, operation, joinContinuations(operands), number);
    }

    /**
     * オペランド欄を切り出す。
     *
     * <p>括弧と引用符の外に空白が現れたら、そこから先は注記である。
     */
    private static String operands(String text) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                quoted = c != '\'';
                continue;
            }
            switch (c) {
                case '\'' -> quoted = true;
                case '(' -> depth++;
                case ')' -> depth--;
                case ' ' -> {
                    if (depth == 0) {
                        return text.substring(0, i);
                    }
                }
                default -> {
                    // 何もしない
                }
            }
        }
        return text;
    }

    /** コンマで終わっていれば、次のカードのオペランドをつなぐ。 */
    private String joinContinuations(String operands) {
        StringBuilder sb = new StringBuilder(operands);
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',' && at < lines.length) {
            String next = card(lines[at]);
            if (!next.startsWith("//") || next.startsWith("//*")) {
                break;
            }
            String body = next.substring(2);
            if (!body.isEmpty() && !Character.isWhitespace(body.charAt(0))) {
                // 名前欄が書かれていれば新しい文である。継続ではない
                break;
            }
            at++;
            sb.append(operands(body.stripLeading()));
        }
        return sb.toString();
    }

    /**
     * 埋め込みデータを切り出す (要件 FR-131 の {@code SYSIN DD *})。
     *
     * <p>{@code DD *} は {@code /*} か次の文まで、{@code DD DATA} は {@code /*} までである。
     * {@code DATA} と書くのは、データの中に {@code //} で始まる行があるときである。
     *
     * @return 埋め込みデータでなければ {@code null}
     */
    private byte[] inlineOf(JclCard card) {
        String operands = card.operands().trim();
        boolean star = operands.equals("*") || operands.startsWith("*,");
        boolean data = operands.equals("DATA") || operands.startsWith("DATA,");
        if (!star && !data) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        while (at < lines.length) {
            String line = this.card(lines[at]);
            if (line.startsWith("/*") || (star && line.startsWith("//"))) {
                if (line.startsWith("/*")) {
                    at++;
                }
                break;
            }
            at++;
            sb.append(line).append('\n');
        }
        return dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.encode(sb.toString());
    }

    /** 72 桁までを取り、行末の空白と改行を落とす。 */
    private static String card(String line) {
        String text = line.replace("\r", "");
        if (text.length() > CARD_WIDTH) {
            text = text.substring(0, CARD_WIDTH);
        }
        return text.stripTrailing();
    }

    private static int indexOfBlank(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
