package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.abend.AbendCause;
import dev.cobolonjava.runtime.abend.AbendCode;

/**
 * 行き先を書かない {@code GO TO} を、{@code ALTER} される前に通った (要件 FR-063)。
 *
 * <p>{@code GO TO.} とだけ書いた段落は、行き先が<b>まだ決まっていない</b>場所である。
 * {@code ALTER} が書き込むまで通ってはならない。規格はここを未定義としているが、
 * 未定義のまま次の段落へ流すと<b>そのあとの結果が何を意味するのか分からなくなる</b>。
 * 黙って進めるより、そこで止めるほうが誤りを早く見つけられる。
 */
public final class UnalteredGoToException extends RuntimeException implements AbendCause {

    private static final long serialVersionUID = 1L;

    /** 参照実装も、行き先の定まらない分岐は言語環境の条件として終わる。 */
    @Override
    public AbendCode abendCode() {
        return AbendCode.U4038;
    }

    public UnalteredGoToException(String paragraph) {
        super("GO TO in " + paragraph + " has no destination; ALTER it before it is reached");
    }
}
