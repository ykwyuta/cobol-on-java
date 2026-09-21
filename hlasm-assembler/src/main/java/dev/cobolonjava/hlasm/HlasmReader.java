package dev.cobolonjava.hlasm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * HLASM の固定形式の原文を文の並びに読む。
 *
 * <p>形は 1〜71 桁が文、72 桁が空白でなければ次の行へ続き、続きの行は 16 桁から書く。
 * 73 桁以降は識別・順序欄であり読まない。1 桁が {@code *} の行と {@code .*} で始まる行は注記である。
 *
 * <p>演算項欄は引用符の外の空白で終わり、そのあとは注記である。<b>コンマで終わった行だけが</b>
 * 次の行へ演算項を続ける。コンマで終わらなければ、続きの行は注記の続きである。
 * この読み方は {@code cobol-ims} の {@code MacroReader} が DBDGEN / PSBGEN で採ったものと同じである。
 * DBDGEN も BMS も実体は HLASM のマクロ呼出しであり、同じ規則で読める。
 *
 * <p>同じ規則の実装をこの処理系が 3 つ持つことになるが、{@code cobol-ims} と {@code cobol-cics} が
 * この層に依存する形にはしていない。マクロ処理系を入れるとき (増分 2) に 1 つへまとめる。
 *
 * <p>桁の割当てを変える {@code ICTL} は受け取らない。固定形式の 1/16/71/72 桁を前提にした読み方を
 * 黙って適用すると、原文と違う文を組み立ててしまうためである。
 */
public final class HlasmReader {

    /** 文の最後の桁 (71 桁)。 */
    private static final int STATEMENT_COLUMNS = 71;
    /** 続きの印を書く桁 (72 桁) の添字。 */
    private static final int CONTINUATION_INDEX = 71;
    /** 続きの行が書き始める桁 (16 桁) の添字。 */
    private static final int CONTINUED_START = 15;

    private HlasmReader() {
    }

    public static List<Statement> read(String source) {
        String[] lines = source.split("\r?\n", -1);
        List<Statement> out = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String raw = lines[i];
            if (raw.isBlank() || raw.startsWith("*") || raw.startsWith(".*")) {
                i++;
                continue;
            }
            int first = i + 1;
            String body = statementPart(raw);
            if (body.isBlank()) {
                i++;
                continue;
            }
            int p = 0;
            String label = null;
            if (body.charAt(0) != ' ') {
                p = blankOrEnd(body, 0);
                label = body.substring(0, p).toUpperCase(Locale.ROOT);
            }
            p = skipBlanks(body, p);
            if (p >= body.length()) {
                throw new AssemblyException(first, "a statement requires an operation");
            }
            int operationEnd = blankOrEnd(body, p);
            String operation = body.substring(p, operationEnd).toUpperCase(Locale.ROOT);
            if ("ICTL".equals(operation)) {
                throw new AssemblyException(first, "ICTL is not supported: "
                        + "this reader assumes the fixed columns 1/16/71/72");
            }

            OperandField field = new OperandField();
            field.take(body, skipBlanks(body, operationEnd));
            boolean continued = continued(raw);
            while (continued) {
                i++;
                if (i >= lines.length) {
                    throw new AssemblyException(i, "the continuation line is missing");
                }
                String next = lines[i];
                String part = statementPart(next);
                if (!part.substring(0, Math.min(CONTINUED_START, part.length())).isBlank()) {
                    throw new AssemblyException(i + 1,
                            "a continuation line must start in column 16");
                }
                if (field.continues() && part.length() > CONTINUED_START) {
                    field.take(part, CONTINUED_START);
                }
                continued = continued(next);
            }
            if (field.quoted) {
                throw new AssemblyException(first, "a quoted string is not closed");
            }
            out.add(new Statement(label, operation, field.text.toString(), first));
            i++;
        }
        return out;
    }

    /** 1〜71 桁。73 桁以降の識別・順序欄は読まない。 */
    private static String statementPart(String line) {
        return line.length() <= STATEMENT_COLUMNS ? line : line.substring(0, STATEMENT_COLUMNS);
    }

    /** 72 桁が空白でなければ次の行へ続く。 */
    private static boolean continued(String line) {
        return line.length() > CONTINUATION_INDEX && line.charAt(CONTINUATION_INDEX) != ' ';
    }

    private static int blankOrEnd(String text, int from) {
        int k = text.indexOf(' ', from);
        return k < 0 ? text.length() : k;
    }

    private static int skipBlanks(String text, int from) {
        int k = from;
        while (k < text.length() && text.charAt(k) == ' ') {
            k++;
        }
        return k;
    }

    /** 演算項欄を行をまたいで集める。 */
    private static final class OperandField {

        private final StringBuilder text = new StringBuilder();
        private boolean quoted;

        /** {@code from} から演算項欄の終わり (引用符の外の空白) までを足す。 */
        void take(String line, int from) {
            for (int k = from; k < line.length(); k++) {
                char c = line.charAt(k);
                if (c == ' ' && !quoted) {
                    return;
                }
                if (c == '\'' && Quotes.isDelimiter(text.toString() + c, text.length())) {
                    quoted = !quoted;
                }
                text.append(c);
            }
        }

        /** 次の続きの行が演算項を続けるか。コンマで終わったか、引用符が開いたまま行が尽きたとき。 */
        boolean continues() {
            return quoted || (!text.isEmpty() && text.charAt(text.length() - 1) == ',');
        }
    }
}
