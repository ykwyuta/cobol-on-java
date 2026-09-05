package dev.cobolonjava.job.jcl;

import java.util.ArrayList;
import java.util.List;

/**
 * オペランド欄の切り分け (要件 FR-131)。
 *
 * <p>コンマで区切るが、<b>括弧と引用符の中は区切らない</b>。
 * {@code COND=((4,LT),(8,GT,STEP1))} は 1 つのオペランドであり、
 * {@code PARM='A,B'} も 1 つである。
 */
public final class JclOperands {

    private JclOperands() {
    }

    /** 深さ 0 のコンマで切り分ける。 */
    public static List<String> split(String operands) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < operands.length(); i++) {
            char c = operands.charAt(i);
            if (quoted) {
                current.append(c);
                if (c == '\'') {
                    // 引用符 2 つは引用符 1 つを表す。閉じたことにはならない
                    if (i + 1 < operands.length() && operands.charAt(i + 1) == '\'') {
                        current.append(operands.charAt(++i));
                    } else {
                        quoted = false;
                    }
                }
                continue;
            }
            switch (c) {
                case '\'' -> {
                    quoted = true;
                    current.append(c);
                }
                case '(' -> {
                    depth++;
                    current.append(c);
                }
                case ')' -> {
                    depth--;
                    current.append(c);
                }
                case ',' -> {
                    if (depth == 0) {
                        out.add(current.toString());
                        current.setLength(0);
                    } else {
                        current.append(c);
                    }
                }
                default -> current.append(c);
            }
        }
        if (!current.isEmpty() || !out.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    /** 括弧を外す。括弧で囲まれていなければそのまま返す。 */
    public static String unwrap(String value) {
        String text = value.trim();
        if (text.length() >= 2 && text.charAt(0) == '(' && text.endsWith(")")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /** 引用符を外し、二重の引用符を 1 つへ戻す。 */
    public static String unquote(String value) {
        String text = value.trim();
        if (text.length() >= 2 && text.charAt(0) == '\'' && text.endsWith("'")) {
            return text.substring(1, text.length() - 1).replace("''", "'");
        }
        return text;
    }

    /** {@code KEY=VALUE} の鍵。{@code =} がなければオペランド全体が鍵である。 */
    public static String key(String operand) {
        int equals = indexOfAssignment(operand);
        return (equals < 0 ? operand : operand.substring(0, equals)).trim();
    }

    /** {@code KEY=VALUE} の値。{@code =} がなければ空文字列。 */
    public static String value(String operand) {
        int equals = indexOfAssignment(operand);
        return equals < 0 ? "" : operand.substring(equals + 1).trim();
    }

    /** 括弧と引用符の外にある最初の {@code =}。 */
    private static int indexOfAssignment(String operand) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < operand.length(); i++) {
            char c = operand.charAt(i);
            if (quoted) {
                quoted = c != '\'';
                continue;
            }
            switch (c) {
                case '\'' -> quoted = true;
                case '(' -> depth++;
                case ')' -> depth--;
                case '=' -> {
                    if (depth == 0) {
                        return i;
                    }
                }
                default -> {
                    // 何もしない
                }
            }
        }
        return -1;
    }
}
