package dev.cobolonjava.hlasm;

import java.util.ArrayList;
import java.util.List;

/**
 * HLASM の原文 1 文。
 *
 * <p>演算項は分けずに書かれたまま持つ。機械命令の演算項は {@code 0(8,R1)} のように括弧と
 * コンマが入れ子になり、命令ごとに読み方が違うためである。最上位のコンマで分けるのは
 * {@link #operandList()} を呼んだ側の仕事とする。
 *
 * @param label     名前欄。書かれていなければ {@code null}
 * @param operation 命令欄 (大文字)
 * @param operands  演算項欄。書かれていなければ空文字
 * @param line      文が始まる原文の行 (1 起点)
 */
public record Statement(String label, String operation, String operands, int line) {

    public boolean hasOperands() {
        return !operands.isEmpty();
    }

    /** 最上位のコンマで分ける。括弧と引用符の中のコンマでは分けない。 */
    public List<String> operandList() {
        return split(operands, line);
    }

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
            if (c == '\'' && Quotes.isDelimiter(text, k, quoted)) {
                quoted = !quoted;
            } else if (quoted) {
                continue;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (--depth < 0) {
                    throw new AssemblyException(line, "unbalanced parentheses in operands: " + text);
                }
            } else if (c == ',' && depth == 0) {
                out.add(text.substring(start, k));
                start = k + 1;
            }
        }
        if (quoted || depth != 0) {
            throw new AssemblyException(line,
                    "unbalanced parentheses or quotes in operands: " + text);
        }
        out.add(text.substring(start));
        return out;
    }
}
