package dev.cobolonjava.hlasm;

/**
 * 組み立てを続けられない誤り。
 *
 * <p>位置を必ず持つ。CLAUDE.md の「理由が細かくなったら、数えるのをやめて行を見る」を
 * HLASM でも成り立たせるためである。
 */
public final class AssemblyException extends RuntimeException {

    private final int line;

    public AssemblyException(int line, String message) {
        super(message);
        this.line = line;
    }

    public int line() {
        return line;
    }
}
