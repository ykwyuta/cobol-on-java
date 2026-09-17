package dev.cobolonjava.ims.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DBDGEN / PSBGEN の原文をマクロ命令の並びに読む。
 *
 * <p>形は High Level Assembler の固定形式による。1〜71 桁が文で、72 桁が空白でなければ次の行へ続く。
 * 続きの行は 16 桁から書く。73 桁以降は順序番号であり読まない。1 桁が {@code *} の行と {@code .*} で
 * 始まる行は注記である。
 *
 * <p>演算項欄は空白で終わり (引用符の中の空白を除く)、そのあとは注記である。演算項欄がコンマで終わった
 * 行だけが、次の行へ演算項を続ける。コンマで終わらなければ、続きの行は注記の続きである。
 */
public final class MacroReader {

    /** 文の最後の桁 (71 桁)。 */
    private static final int STATEMENT_COLUMNS = 71;
    /** 続きの印を書く桁 (72 桁) の添字。 */
    private static final int CONTINUATION_INDEX = 71;
    /** 続きの行が書き始める桁 (16 桁) の添字。 */
    private static final int CONTINUED_START = 15;

    private MacroReader() {
    }

    public static List<MacroStatement> read(String source) {
        String[] lines = source.split("\r?\n", -1);
        List<MacroStatement> out = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String raw = lines[i];
            if (raw.isBlank() || raw.startsWith("*") || raw.startsWith(".*")) {
                i++;
                continue;
            }
            int first = i + 1;
            String body = statementPart(raw);
            int p = 0;
            String label = null;
            if (body.charAt(0) != ' ') {
                p = blankOrEnd(body, 0);
                label = body.substring(0, p).toUpperCase(Locale.ROOT);
            }
            p = skipBlanks(body, p);
            if (p >= body.length()) {
                throw new ImsGenerationException(first, "a statement requires an operation");
            }
            int operationEnd = blankOrEnd(body, p);
            String operation = body.substring(p, operationEnd).toUpperCase(Locale.ROOT);

            OperandField field = new OperandField();
            field.take(body, skipBlanks(body, operationEnd));
            boolean continued = continued(raw);
            while (continued) {
                i++;
                if (i >= lines.length) {
                    throw new ImsGenerationException(i, "the continuation line is missing");
                }
                String next = lines[i];
                String part = statementPart(next);
                if (!part.substring(0, Math.min(CONTINUED_START, part.length())).isBlank()) {
                    throw new ImsGenerationException(i + 1, "a continuation line must start in column 16");
                }
                if (field.continues() && part.length() > CONTINUED_START) {
                    field.take(part, CONTINUED_START);
                }
                continued = continued(next);
            }
            if (field.quoted) {
                throw new ImsGenerationException(first, "a quoted string is not closed");
            }
            out.add(new MacroStatement(label, operation, split(field.text.toString(), first), first));
            i++;
        }
        return out;
    }

    /** 演算項を最上位のコンマで分ける。括弧と引用符の中のコンマでは分けない。 */
    static List<String> split(String text, int line) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) {
            return out;
        }
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == '\'') {
                // 2 つ重ねた引用符は 2 度切り替わるので、引用符の中に留まる
                quoted = !quoted;
            } else if (quoted) {
                continue;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (--depth < 0) {
                    throw new ImsGenerationException(line, "unbalanced parentheses in operands: " + text);
                }
            } else if (c == ',' && depth == 0) {
                out.add(text.substring(start, k));
                start = k + 1;
            }
        }
        if (quoted || depth != 0) {
            throw new ImsGenerationException(line, "unbalanced parentheses or quotes in operands: " + text);
        }
        out.add(text.substring(start));
        return out;
    }

    /** 演算項欄を行をまたいで集める。 */
    private static final class OperandField {

        private final StringBuilder text = new StringBuilder();
        private boolean quoted;
        private boolean ended;

        /** {@code from} から演算項欄の終わりまでを足す。 */
        void take(String line, int from) {
            ended = false;
            for (int k = from; k < line.length(); k++) {
                char c = line.charAt(k);
                if (c == ' ' && !quoted) {
                    ended = true;
                    return;
                }
                if (c == '\'') {
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

    private static String statementPart(String raw) {
        return raw.length() > STATEMENT_COLUMNS ? raw.substring(0, STATEMENT_COLUMNS) : raw;
    }

    private static boolean continued(String raw) {
        return raw.length() > CONTINUATION_INDEX && raw.charAt(CONTINUATION_INDEX) != ' ';
    }

    private static int skipBlanks(String text, int from) {
        int k = from;
        while (k < text.length() && text.charAt(k) == ' ') {
            k++;
        }
        return k;
    }

    private static int blankOrEnd(String text, int from) {
        int blank = text.indexOf(' ', from);
        return blank < 0 ? text.length() : blank;
    }
}
