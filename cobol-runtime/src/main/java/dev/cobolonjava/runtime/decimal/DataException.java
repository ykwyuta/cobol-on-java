package dev.cobolonjava.runtime.decimal;

/**
 * 数値項目に不正なバイト列が含まれていたことを表す。
 * ホストでは 10 進演算例外 (システム ABEND {@code S0C7}) として現れる (要件 FR-141)。
 */
public class DataException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DataException(String message) {
        super(message);
    }
}
