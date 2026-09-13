package dev.cobolonjava.db2;

/** 閉鎖済みtaskまたは予期しないUOW状態への操作。 */
public final class UnitOfWorkStateException extends IllegalStateException {

    public UnitOfWorkStateException(String message) {
        super(message);
    }
}
