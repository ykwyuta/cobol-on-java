package dev.cobolonjava.db2.jdbc;

/** driver-managed UOWのconnection取得、完了、reset、解放に失敗した。 */
public final class DriverManagedJdbcException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public DriverManagedJdbcException(String message, Throwable cause) {
        super(message, cause);
    }
}
