package dev.cobolonjava.verify.ccvs85;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * CCVS85 の配布物を、翻訳できる原文へ起こす (要件 NFR-040)。
 *
 * <p>配布物のプログラムは<b>そのままでは翻訳できない</b>。処理系ごとに違うところが 2 つ、
 * 印を付けたまま残してあるからである。配布物に同梱されている {@code EXEC85} という COBOL
 * プログラムがこの起こしをやる。ここでやっているのは同じことである。
 *
 * <h2>1. 7 桁目の英字は「選べる行」の印である</h2>
 * <p>7 桁目に英字があれば、その文字が指す機能を<b>使う処理系だけ</b>が通す行である。
 * 選んだ文字なら 7 桁目を空白にして活かし、選ばなければ注釈にする ({@code *} を置き、
 * 文字を 8 桁目へ逃がす)。
 *
 * <pre>
 * 032700S    EXIT PROGRAM.        → 選ばなければ  032700*S   EXIT PROGRAM.
 * </pre>
 *
 * <p>何も選ばなければ<b>いちばん素直な形</b>になる。上の例なら、副プログラムとして
 * 呼ばれる形ではなく、自分で {@code STOP RUN} する形である。
 *
 * <p>{@code D} は例外で、そのまま通す。COBOL の<b>デバッグ行</b>という別の決まりが
 * すでに 7 桁目の {@code D} を使っているからである。{@code *} も注釈としてそのまま通る。
 *
 * <h2>2. XXXXXnnn は処理系が埋める空欄である</h2>
 * <p>12 桁目から {@code XXXX}、16 桁目が {@code X}、17 桁目から 3 桁の番号が並んでいれば、
 * その番号の<b>差し込み札</b> (X-card) の文字で 12 桁目から 72 桁目までを置き換える。
 * 装置名やファイルの結び付けなど、処理系ごとに違うものがここに来る。
 *
 * <pre>
 * 003600     XXXXX082.            → 003600     COBOL-ON-JAVA.
 * </pre>
 *
 * <p>20 桁目が {@code .} なら文の終わりなので札の末尾に {@code .} を足し、空白なら
 * 逆に落とす。文の途中に置かれる札もあるからである。
 *
 * <h2>埋まらなかった空欄は必ず報せる</h2>
 * <p>札を用意していない番号があれば、置き換えずに<b>数えて報せる</b>。黙って
 * {@code XXXXX014} を残すと、処理系が受け付けられなかったのか、こちらが札を書き
 * 忘れたのかが区別できなくなる。<b>検査の道具が、処理系の失敗を作ってはならない</b>。
 */
public record Population(Set<Character> options, XCards cards) {

    /** 選べる行を 1 つも選ばない、いちばん素直な起こし方。 */
    public static Population plain(XCards cards) {
        return new Population(Set.of(), cards);
    }

    /**
     * 起こした結果。
     *
     * @param missing 札を用意していなかった番号
     */
    public record Result(String text, Set<Integer> missing) {
    }

    /** 部品 1 つを起こす。 */
    public Result apply(Ccvs85Archive.Member member) {
        StringBuilder out = new StringBuilder();
        Set<Integer> missing = new LinkedHashSet<>();
        for (String line : member.lines()) {
            out.append(substituted(optional(line), missing)).append('\n');
        }
        return new Result(out.toString(), missing);
    }

    /** 7 桁目の英字を見て、活かすか注釈にするかを決める。 */
    private String optional(String line) {
        char indicator = at(line, 7);
        if (indicator == ' ' || indicator == '*' || indicator == '/' || indicator == '-'
                || indicator == 'D' || !Character.isLetter(indicator)) {
            return line;
        }
        if (options.contains(indicator)) {
            return replace(line, 7, ' ');
        }
        // 選ばなかった行は消さずに注釈にする。
        //
        // <b>7 桁目だけを書き換える。</b>印を 8 桁目へ逃がすと、そこにある文字を
        // 潰してしまう。8 桁目は A 領域の先頭であり、段落見出しが始まる場所である。
        // 「ASPECIAL-NAMES.」の A を 8 桁目へ移すと「*APECIAL-NAMES.」になり、
        // <b>道具が原文を壊す</b>ことになる。
        return replace(line, 7, '*');
    }

    /**
     * 12 桁目からの差し込み札を埋める。
     *
     * <p><b>注釈になった行の札は要らない。</b>7 桁目の印で落とした行に札が載っていても、
     * その行は動かないのだから埋めるものが無い。ここを見落として「札が足りない」と
     * 数えると、<b>流せるプログラムを流さなくなる</b>。
     */
    private String substituted(String line, Set<Integer> missing) {
        if (at(line, 7) == '*' || at(line, 7) == '/') {
            return line;
        }
        if (!"XXXX".equals(range(line, 12, 15)) || at(line, 16) != 'X') {
            return line;
        }
        String digits = range(line, 17, 19);
        if (digits.length() != 3 || !digits.chars().allMatch(Character::isDigit)) {
            return line;
        }
        int number = Integer.parseInt(digits);
        String text = cards.text(number);
        if (text == null) {
            missing.add(number);
            return line;
        }
        String written = at(line, 20) == '.' ? withStop(text) : withoutStop(text);
        String head = pad(line, 11).substring(0, 11);
        String tail = line.length() > 72 ? line.substring(72) : "";
        String body = pad(written, 61).substring(0, 61);
        return head + body + tail;
    }

    /** 末尾に文の終わりを足す。すでにあればそのままである。 */
    private static String withStop(String text) {
        String trimmed = text.stripTrailing();
        return trimmed.endsWith(".") ? trimmed : trimmed + ".";
    }

    /** 末尾の文の終わりを落とす。 */
    private static String withoutStop(String text) {
        String trimmed = text.stripTrailing();
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    // ---- 桁の扱い。桁は 1 から数える ----

    private static char at(String line, int column) {
        return line.length() >= column ? line.charAt(column - 1) : ' ';
    }

    private static String range(String line, int from, int to) {
        if (line.length() < from) {
            return "";
        }
        return line.substring(from - 1, Math.min(to, line.length()));
    }

    private static String replace(String line, int column, char c) {
        String padded = pad(line, column);
        return padded.substring(0, column - 1) + c + padded.substring(column);
    }

    private static String pad(String line, int length) {
        return line.length() >= length ? line : line + " ".repeat(length - line.length());
    }

    /** 起こしたプログラムの並び。 */
    public List<Program> populate(Ccvs85Archive archive) {
        List<Program> out = new ArrayList<>();
        for (Ccvs85Archive.Member member : archive.programs()) {
            Result result = apply(member);
            out.add(new Program(member.name(), member.module(), result.text(), result.missing()));
        }
        return List.copyOf(out);
    }

    /**
     * 起こしたプログラム 1 本。
     *
     * @param missing 札を用意していなかった番号。空でなければ翻訳にかけてはならない
     */
    public record Program(String name, String module, String source, Set<Integer> missing) {
    }
}
