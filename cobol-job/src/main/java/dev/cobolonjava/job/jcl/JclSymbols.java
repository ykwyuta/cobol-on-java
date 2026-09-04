package dev.cobolonjava.job.jcl;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * シンボリックパラメタの束 (要件 FR-131)。
 *
 * <p>{@code &名前} を値へ置き換える。値は 3 か所から来る。<b>手続きの既定値</b>、
 * <b>呼び出しでの上書き</b>、<b>{@code SET} で置いた値</b>である。強いのは上書き、
 * 次が {@code SET}、いちばん弱いのが既定値である。
 */
public final class JclSymbols {

    /** シンボリック名に使える文字。ホストの規則に合わせて英数字と 3 つの記号である。 */
    private static boolean isSymbolCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '#' || c == '@' || c == '$';
    }

    private final Map<String, String> values = new LinkedHashMap<>();

    public JclSymbols() {
    }

    private JclSymbols(Map<String, String> values) {
        this.values.putAll(values);
    }

    /** 値を置く。 */
    public void put(String name, String value) {
        values.put(name.toUpperCase(Locale.ROOT), value);
    }

    /** すでに値があれば置かない。手続きの既定値がこの入れ方をする。 */
    public void putIfAbsent(String name, String value) {
        values.putIfAbsent(name.toUpperCase(Locale.ROOT), value);
    }

    /** 写しを作る。手続きの中だけで効く束を作るために使う。 */
    public JclSymbols copy() {
        return new JclSymbols(values);
    }

    /** 名前に値があるか。 */
    public boolean has(String name) {
        return values.containsKey(name.toUpperCase(Locale.ROOT));
    }

    /**
     * 置き換えた文字列を返す。
     *
     * <p>{@code &名前} の終わりは、シンボリック名に使えない文字が来たところである。
     * 名前のあとに文字が続くときは {@code &名前.} と点で終わりを示す。この点は消える。
     *
     * <p>{@code &&} は<b>1 つの {@code &}</b> を表す。一時データセットの名前で使う。
     *
     * @param unresolved 値のなかった名前を集める入れ物
     */
    public String substitute(String text, java.util.List<String> unresolved) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '&') {
                sb.append(c);
                continue;
            }
            if (i + 1 < text.length() && text.charAt(i + 1) == '&') {
                sb.append('&');
                i++;
                continue;
            }
            int end = i + 1;
            while (end < text.length() && end - i <= 8 && isSymbolCharacter(text.charAt(end))) {
                end++;
            }
            String name = text.substring(i + 1, end);
            if (name.isEmpty()) {
                sb.append(c);
                continue;
            }
            String value = values.get(name.toUpperCase(Locale.ROOT));
            if (value == null) {
                unresolved.add(name);
                sb.append(c).append(name);
            } else {
                sb.append(value);
            }
            // 名前の終わりを示す点は、置き換えたあとに残さない
            i = end < text.length() && text.charAt(end) == '.' ? end : end - 1;
        }
        return sb.toString();
    }
}
