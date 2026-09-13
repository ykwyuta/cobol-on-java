package dev.cobolonjava.runtime.interop;

/** 登録済み Java サブルーチンが checked exception で失敗した。 */
public final class JavaProgramException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JavaProgramException(ProgramId id, Exception cause) {
        super("registered Java program failed: " + id.value(), cause);
    }
}
