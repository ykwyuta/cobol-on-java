package dev.cobolonjava.ims.gen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * アセンブラのマクロ命令 1 文 (DBDGEN / PSBGEN の原文の 1 文)。
 *
 * <p>演算項は {@code KEY=値} のキーワード形と、位置で決まる形がある。キーワードの名前は大文字で持ち、
 * 値は書かれた綴りのまま持つ。括弧の並び {@code (A,B,(C,D))} は {@link #elements} で 1 段ずつ分ける。
 *
 * @param label     名前欄。書かれていなければ {@code null}
 * @param operation 命令欄 (大文字)
 * @param operands  演算項。最上位のコンマで分けたもの
 * @param line      文が始まる原文の行 (1 起点)
 */
public record MacroStatement(String label, String operation, List<String> operands, int line) {

    private static final Pattern KEYWORD = Pattern.compile("[A-Za-z@#$][A-Za-z0-9@#$]*");

    public MacroStatement {
        operands = List.copyOf(operands);
    }

    /** キーワード形の演算項。書いた順に並ぶ。同じ名前を 2 度書いた文は断る。 */
    public Map<String, String> keywords() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String operand : operands) {
            int equal = keywordEnd(operand);
            if (equal < 0) {
                continue;
            }
            String name = operand.substring(0, equal).toUpperCase(Locale.ROOT);
            if (out.put(name, operand.substring(equal + 1)) != null) {
                throw new ImsGenerationException(line, operation + " keyword " + name + " is written twice");
            }
        }
        return out;
    }

    /** 位置で決まる演算項。空の演算項 (省略) も位置を持つので残す。 */
    public List<String> positional() {
        List<String> out = new ArrayList<>();
        for (String operand : operands) {
            if (keywordEnd(operand) < 0) {
                out.add(operand);
            }
        }
        return out;
    }

    /**
     * 括弧の並びを 1 段だけ分ける。括弧で囲まれていなければ値そのもの 1 つを返す。
     *
     * <p>{@code ((CUST,SNGL))} は {@code [(CUST,SNGL)]} になり、もう 1 度分けると {@code [CUST, SNGL]} になる。
     */
    public List<String> elements(String value) {
        if (value.length() < 2 || value.charAt(0) != '(' || closingOf(value) != value.length() - 1) {
            return List.of(value);
        }
        return MacroReader.split(value.substring(1, value.length() - 1), line);
    }

    /** {@code =} の位置。前が名前の綴りでなければ (位置で決まる演算項なら) -1。 */
    private static int keywordEnd(String operand) {
        int equal = operand.indexOf('=');
        if (equal <= 0 || !KEYWORD.matcher(operand.substring(0, equal)).matches()) {
            return -1;
        }
        return equal;
    }

    /** 先頭の開き括弧に対応する閉じ括弧の位置。引用符の中は数えない。 */
    private static int closingOf(String value) {
        int depth = 0;
        boolean quoted = false;
        for (int k = 0; k < value.length(); k++) {
            char c = value.charAt(k);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')' && --depth == 0) {
                return k;
            }
        }
        return -1;
    }
}
