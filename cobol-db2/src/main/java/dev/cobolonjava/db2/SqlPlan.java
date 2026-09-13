package dev.cobolonjava.db2;

import java.util.Objects;

/** コンパイラが生成しadapterが実行する、値bindingとは分離した中立SQL計画。 */
public record SqlPlan(
        String statementId,
        String dialect,
        SqlOperation operation,
        String normalizedSql,
        CursorOptions cursorOptions) {

    public SqlPlan {
        if (statementId == null || statementId.isBlank()) {
            throw new IllegalArgumentException("statementId must not be blank");
        }
        if (dialect == null || dialect.isBlank()) {
            throw new IllegalArgumentException("dialect must not be blank");
        }
        Objects.requireNonNull(operation, "operation");
        if (normalizedSql == null || normalizedSql.isBlank()) {
            throw new IllegalArgumentException("normalizedSql must not be blank");
        }
        Objects.requireNonNull(cursorOptions, "cursorOptions");
        boolean cursorOperation = operation == SqlOperation.OPEN_CURSOR
                || operation == SqlOperation.FETCH_CURSOR
                || operation == SqlOperation.CLOSE_CURSOR;
        if (cursorOperation && cursorOptions.equals(CursorOptions.none())) {
            throw new IllegalArgumentException("cursor SQL requires cursor options");
        }
        if (!cursorOperation && !cursorOptions.equals(CursorOptions.none())) {
            throw new IllegalArgumentException("non-cursor SQL must not carry cursor options");
        }
    }
}
