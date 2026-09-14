package dev.cobolonjava.cics.bms;

/**
 * BMSマクロを中立モデルへ変換できなかった。
 *
 * <p>未知のoperandや値を黙って捨てると、画面の意味論が欠けたまま動く。FR-162は属性を
 * 「欠落なく」保持することを求めているので、読めないものは位置つきで断る。
 */
public final class BmsDefinitionException extends RuntimeException {

    private final int line;

    public BmsDefinitionException(int line, String message) {
        super("line " + line + ": " + message);
        this.line = line;
    }

    /** 原文の物理行番号 (1始まり)。 */
    public int line() {
        return line;
    }
}
