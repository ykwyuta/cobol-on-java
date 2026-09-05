package dev.cobolonjava.job;

/**
 * ジョブの記述で見つかった誤り。
 *
 * @param line    行番号 (1 起点)
 * @param message 何が悪いか
 */
public record JobDiagnostic(int line, String message) {

    @Override
    public String toString() {
        return line + ": " + message;
    }
}
