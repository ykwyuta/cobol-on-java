package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;

/**
 * いま翻訳しているプログラムのコードページ ({@code CODEPAGE} オプション)。
 *
 * <p>文字定数のバイト列、初期値の像、図形定数は翻訳時にこのコードページで作る。以前は
 * どこでも IBM-1047 に固定しており、{@code CODEPAGE(939)} を断っていた (暫定判断 P-177)。
 * 翻訳の入口が決めて、翻訳のあいだだけ効く。翻訳系の奥まで引数で渡す代わりに、スレッドごとの
 * 置き場に置いている。定数を読む場所 ({@link LiteralValue}) が構文木の走査の底にあり、
 * そこまでの呼び出しがすべてコードページを知らないからである。
 */
public final class CompileCodePage {

    private static final ThreadLocal<CodePage> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> DBCS_SYMBOL = new ThreadLocal<>();

    /**
     * {@code NSYMBOL(DBCS)} か。そのとき {@code PIC N} と {@code N'..'} は国字ではなく DBCS である
     * (Enterprise COBOL 言語解説書 "NSYMBOL")。
     */
    public static boolean dbcsSymbol() {
        return Boolean.TRUE.equals(DBCS_SYMBOL.get());
    }

    public static void setDbcsSymbol(boolean value) {
        if (value) {
            DBCS_SYMBOL.set(true);
        } else {
            DBCS_SYMBOL.remove();
        }
    }

    private CompileCodePage() {
    }

    /** いまのコードページ。翻訳の外では既定 (IBM-1047)。 */
    public static CodePage current() {
        CodePage page = CURRENT.get();
        return page == null ? CodePages.DEFAULT : page;
    }

    /** 翻訳のあいだのコードページを決める。{@code null} で既定へ戻す。 */
    public static void set(CodePage page) {
        if (page == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(page);
        }
    }
}
