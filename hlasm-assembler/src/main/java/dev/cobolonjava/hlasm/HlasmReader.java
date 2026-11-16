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
 * この層に依存する形にはしていない。共通化はまだ行っていない (P-170)。
 *
 * <p>最初の命令に {@code ICTL} があれば、そのソースの桁の割当てを変更する。
 */
public final class HlasmReader {

    private record Columns(int begin, int end, int continuation) {
        String body(String line, int start) {
            return line.length() <= start ? "" : line.substring(start, Math.min(end, line.length()));
        }

        boolean continued(String line) {
            return end < 80 && line.length() > end && line.charAt(end) != ' ';
        }
    }

    private HlasmReader() {
    }

    public static List<Statement> read(String source) {
        String[] lines = source.split("\r?\n", -1);
        List<Statement> out = new ArrayList<>();
        Columns columns = new Columns(0, 71, 15);
        boolean firstStatement = true;
        int i = 0;
        while (i < lines.length) {
            String raw = lines[i];
            int first = i + 1;
            String body = columns.body(raw, columns.begin());
            if (body.toUpperCase(Locale.ROOT).matches("^\\*PROCESS\\s+.*")) {
                out.add(new Statement(null, "*PROCESS", body.substring(8).strip(), first));
                i++;
                continue;
            }
            if (body.isBlank() || body.startsWith("*") || body.startsWith(".*")) {
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
                if (!firstStatement || label != null || columns.continued(raw)) {
                    throw new AssemblyException(first, "ICTL must be the first statement and have no label");
                }
                String operands = body.substring(operationEnd).strip();
                columns = inputColumns(operands, first);
                out.add(new Statement(null, operation, operands, first));
                firstStatement = false;
                i++;
                continue;
            }
            firstStatement = false;

            OperandField field = new OperandField();
            // 条件付きアセンブリの式は引用符の外にも空白を含む。
            boolean expression = "AIF".equals(operation) || "SETA".equals(operation)
                    || "SETB".equals(operation) || "SETC".equals(operation);
            field.take(body, skipBlanks(body, operationEnd), expression);
            boolean continued = columns.continued(raw);
            while (continued) {
                if (columns.continuation() < 0) {
                    throw new AssemblyException(first, "continuation disabled by ICTL");
                }
                i++;
                if (i >= lines.length) {
                    throw new AssemblyException(i, "the continuation line is missing");
                }
                String next = lines[i];
                String part = columns.body(next, 0);
                int from = columns.continuation();
                if (part.length() > columns.begin()
                        && !part.substring(columns.begin(), Math.min(from, part.length())).isBlank()) {
                    throw new AssemblyException(i + 1,
                            "a continuation line must start in column " + (from + 1));
                }
                if (field.continues() && part.length() > from) {
                    field.take(part, from, expression);
                }
                continued = columns.continued(next);
            }
            if (field.quoted) {
                throw new AssemblyException(first, "a quoted string is not closed");
            }
            String identification = (raw.length() > 72
                    ? raw.substring(72, Math.min(80, raw.length())) : "");
            identification = String.format("%-8s", identification);
            out.add(new Statement(label, operation, field.text.toString().stripTrailing(),
                    first, identification));
            i++;
        }
        return out;
    }

    private static Columns inputColumns(String operands, int line) {
        String[] fields = operands.split("\\s+", 2)[0].split(",", -1);
        if (fields.length > 3 || operands.isEmpty()) {
            throw new AssemblyException(line, "ICTL requires begin[,end[,continue]]");
        }
        int begin = column(fields, 0, 1, line);
        int end = column(fields, 1, 71, line);
        int continuation = column(fields, 2, 0, line);
        if (begin < 1 || begin > 40 || end < 41 || end > 80 || end < begin + 5
                || (continuation != 0 && (continuation <= begin || continuation > 40))) {
            throw new AssemblyException(line, "invalid ICTL columns");
        }
        return new Columns(begin - 1, end, end == 80 ? -1 : continuation - 1);
    }

    private static int column(String[] fields, int index, int fallback, int line) {
        if (index >= fields.length || fields[index].isEmpty()) {
            return fallback;
        }
        try {
            if (!fields[index].matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            return Integer.parseInt(fields[index]);
        } catch (NumberFormatException invalid) {
            throw new AssemblyException(line, "invalid ICTL column: " + fields[index]);
        }
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
        void take(String line, int from, boolean keepBlanks) {
            for (int k = from; k < line.length(); k++) {
                char c = line.charAt(k);
                if (c == ' ' && !quoted && !keepBlanks) {
                    return;
                }
                // 属性の印かどうかは後ろの字でも決まるので、行の残りを付けて渡す
                if (c == '\''
                        && Quotes.isDelimiter(text.toString() + line.substring(k), text.length(),
                                quoted)) {
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
