package dev.cobolonjava.compiler.source;

import java.util.ArrayList;
import java.util.List;

/**
 * 翻訳リストの体裁だけを決める文を落とす ({@code EJECT}、{@code SKIP1}〜{@code SKIP3}、
 * {@code TITLE '見出し'})。
 *
 * <p>どれも Enterprise COBOL の翻訳指示文であり、<b>プログラムの意味を持たない</b>。
 * 改ページ、空行、ページの見出しを決めるだけである。どの部にも、文と文のあいだにも書ける
 * ので、構文解析まで残すと文法のあらゆる場所で受けることになる。語の並びのうちに落とす。
 *
 * <p>うしろの終止符は書いても書かなくてもよい。<b>同じ行にある終止符</b>だけを指示文の
 * ものとして落とす。次の行の終止符は、前の文を終える終止符かもしれない。
 *
 * <p>{@code TITLE} は直後が文字定数のときだけ指示文として読む。ほかの位置の
 * {@code TITLE} には触らない。
 */
final class ListingDirectives {

    private ListingDirectives() {
    }

    static NormalizedSource strip(NormalizedSource source) {
        List<TextWord> words = PreprocessorLexer.lex(source);
        List<TextWord> out = new ArrayList<>(words.size());
        boolean changed = false;
        for (int i = 0; i < words.size(); i++) {
            TextWord word = words.get(i);
            boolean spacing = word.isWord("EJECT") || word.isWord("SKIP1")
                    || word.isWord("SKIP2") || word.isWord("SKIP3");
            boolean title = word.isWord("TITLE") && i + 1 < words.size()
                    && words.get(i + 1).kind() == TextWordKind.LITERAL;
            if (!spacing && !title) {
                out.add(word);
                continue;
            }
            changed = true;
            if (title) {
                i++;
            }
            if (i + 1 < words.size() && words.get(i + 1).isSeparator('.')
                    && sameLine(words.get(i + 1), word)) {
                i++;
            }
            if (i + 1 < words.size()) {
                // 落とした語のぶん、次の語は前の語と離れている
                words.set(i + 1, words.get(i + 1).withPrecededBySpace(true));
            }
        }
        return changed ? PreprocessorLexer.emit(out) : source;
    }

    private static boolean sameLine(TextWord a, TextWord b) {
        return a.origin().line() == b.origin().line()
                && a.origin().fileName().equals(b.origin().fileName());
    }
}
