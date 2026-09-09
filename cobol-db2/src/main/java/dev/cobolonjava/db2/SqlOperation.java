package dev.cobolonjava.db2;

/** 初期SQL planで区別する実行操作。 */
public enum SqlOperation {
    SELECT_ONE,
    INSERT,
    UPDATE,
    DELETE,
    OPEN_CURSOR,
    FETCH_CURSOR,
    CLOSE_CURSOR
}
