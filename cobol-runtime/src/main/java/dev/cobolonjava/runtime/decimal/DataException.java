package dev.cobolonjava.runtime.decimal;

import dev.cobolonjava.runtime.abend.AbendCause;
import dev.cobolonjava.runtime.abend.AbendCode;

/**
 * 数値項目に不正なバイト列が含まれていたことを表す。
 * ホストでは 10 進演算例外 (システム ABEND {@code S0C7}) として現れる (要件 FR-141)。
 */
public class DataException extends RuntimeException implements AbendCause {

    private static final long serialVersionUID = 1L;

    @Override
    public AbendCode abendCode() {
        return AbendCode.S0C7;
    }

    public DataException(String message) {
        super(message);
    }
}
