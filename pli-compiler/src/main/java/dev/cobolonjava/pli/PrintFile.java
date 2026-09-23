package dev.cobolonjava.pli;

import dev.cobolonjava.runtime.program.ProgramContext;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * SYSPRINT。PL/I の PRINT ファイルを、行の並びとして標準出力へ書く。
 *
 * <p>ストリーム出力は<b>行の途中から続く</b>。{@code PUT LIST(A); PUT LIST(B);} は同じ行に並び、
 * 改行するのは {@code SKIP} を書いたときと、行幅を超えたときである。そのため今どの桁にいるかを
 * 文をまたいで覚えておく。以前は {@code PUT} 1 つを 1 行として書いていたので、{@code SKIP}
 * の無い {@code PUT} の次の {@code PUT SKIP} が前の行に続いていた。
 *
 * <p>list-directed の項目は左端と<b>既定の tab 位置</b>に揃う (LRM "PRINT attribute")。tab 位置と
 * 行幅は PLITABS の既定である (Programming Guide "Changing the format on PRINT files")。
 *
 * <p>同じ {@link ProgramContext} で動く PL/I のプログラムは同じ SYSPRINT を共有する。行を閉じるのは
 * いちばん外のプログラムが終わったときである。
 */
final class PrintFile {

    /** 既定の LINESIZE。 */
    static final int LINESIZE = 120;
    /** PLITABS の既定の tab 位置 (1 から数えた桁)。121 は LINESIZE を超えるので、実際は次の行になる。 */
    static final int[] TABS = {25, 49, 73, 97, 121};

    private static final Map<ProgramContext, PrintFile> OPEN =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 既定の PAGESIZE。61 行目を書こうとすると ENDPAGE になり、既定の動きは改ページである。 */
    static final int PAGESIZE = 60;
    /**
     * 改ページの印。ANS の制御文字 '1' を、POSIX の {@code asa} と同じく改ページ文字にして
     * 新しいページの最初の行の頭に置く。
     */
    static final String FORM_FEED = "\f";

    private final ProgramContext context;
    /** 今の行に書いた文字の数。 */
    private int column;
    /** 最初の行に着いたか。開いた直後はまだどの行にもいない。 */
    private boolean positioned;
    /** ページの中の今の行 (1 から)。 */
    private int line;
    /** 次に書く行を新しいページの頭にするか。 */
    private boolean pageThrow;
    private int depth;

    private PrintFile(ProgramContext context) {
        this.context = context;
    }

    static PrintFile of(ProgramContext context) {
        return OPEN.computeIfAbsent(context, PrintFile::new);
    }

    /** PL/I のプログラムが動き始めた。 */
    void enter() {
        depth++;
    }

    /** PL/I のプログラムが終わった。いちばん外なら、書きかけの行を閉じる。 */
    void leave() {
        if (--depth > 0) {
            return;
        }
        if (column > 0) {
            newline();
        }
        OPEN.remove(context);
    }

    /**
     * {@code SKIP(n)}。今の行を閉じ、n-1 行を空ける。
     *
     * <p>開いた直後はまだどの行にもいないので、{@code SKIP(1)} は 1 行目へ着くだけである
     * (空の行を作らない)。実機とは突き合わせていない (暫定判断 P-183)。
     */
    void skip(int lines) {
        int newlines = positioned ? lines : lines - 1;
        position();
        if (pageThrow && newlines > 0) {
            // PAGE のあとは新しいページの 1 行目にいる。SKIP はそこから数える
            throwPage();
        }
        for (int i = 0; i < newlines; i++) {
            newline();
        }
    }

    /**
     * {@code PAGE}。今の行を閉じ、次に書く行を新しいページの頭にする。
     *
     * <p>開いた直後は 1 ページ目の頭にいるので何もしない。ON ENDPAGE はまだ持たないので、
     * 行が PAGESIZE を超えたときも同じ既定の動き (改ページ) になる。
     */
    void page() {
        if (!positioned) {
            position();
            return;
        }
        if (column > 0) {
            newline();
        }
        pageThrow = true;
    }

    private void position() {
        if (!positioned) {
            positioned = true;
            line = 1;
        }
    }

    /**
     * list-directed の 1 項目。行の先頭でなければ、次の tab 位置へ進めてから書く。
     *
     * <p>項目のあいだには少なくとも 1 つ空白を置く (LRM "A blank separates successive data values")。
     * 項目がちょうど tab の手前で終わったとき、空白なしで続けるかは実機と突き合わせていない (P-183)。
     */
    void listItem(String text) {
        position();
        if (column > 0) {
            int tab = nextTab();
            if (tab < 0 || tab > LINESIZE) {
                newline();
            } else {
                write(" ".repeat(tab - 1 - column));
            }
        }
        write(text);
    }

    /** edit-directed の 1 項目。tab には揃えず、今の桁から続けて書く。 */
    void editItem(String text) {
        position();
        write(text);
    }

    private int nextTab() {
        for (int tab : TABS) {
            // tab は 1 から数える。今の行は column 桁まで埋まっている
            if (tab >= column + 2) {
                return tab;
            }
        }
        return -1;
    }

    /** 行幅を超えた分は次の行へ送る (LRM "LINESIZE": the excess characters on the next line)。 */
    private void write(String text) {
        int at = 0;
        while (at < text.length()) {
            if (column == LINESIZE) {
                newline();
            }
            if (column == 0 && (pageThrow || line > PAGESIZE)) {
                // 空白だけを飛ばした行 (SKIP) の数は数えるが、そこでは改ページしない。
                // ENDPAGE が SKIP の途中で起きたときの残りの扱いは実機と突き合わせていない (P-183)
                throwPage();
            }
            int end = Math.min(text.length(), at + LINESIZE - column);
            String part = text.substring(at, end);
            context.display(context.codePage().encode(part), false);
            column += part.length();
            at = end;
        }
    }

    private void throwPage() {
        context.display(context.codePage().encode(FORM_FEED), false);
        pageThrow = false;
        line = 1;
    }

    private void newline() {
        context.display(new byte[0], true);
        column = 0;
        line++;
    }
}
