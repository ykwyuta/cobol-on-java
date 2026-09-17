package dev.cobolonjava.ims.gen;

/**
 * DBDGEN / PSBGEN の原文を読めない。
 *
 * <p>位置 (原文の行) と理由を分けて持つ。数える道具は理由だけでまとめるので、行を文面の頭に混ぜない
 * (覚え書き 6「診断の引用は尻を残す」)。
 */
public final class ImsGenerationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int line;
    private final String reason;

    public ImsGenerationException(int line, String reason) {
        super("line " + line + ": " + reason);
        this.line = line;
        this.reason = reason;
    }

    /** 原文の行 (1 起点)。 */
    public int line() {
        return line;
    }

    /** 位置を含まない理由。 */
    public String reason() {
        return reason;
    }
}
