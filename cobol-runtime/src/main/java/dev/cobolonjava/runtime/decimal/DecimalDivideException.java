package dev.cobolonjava.runtime.decimal;

import dev.cobolonjava.runtime.abend.AbendCause;
import dev.cobolonjava.runtime.abend.AbendCode;

/**
 * 10 進除算でゼロ除算または商のオーバーフローが起きたことを表す。
 * ホストでは 10 進除算例外 (システム ABEND {@code S0CB}) として現れる (要件 FR-141)。
 *
 * <p>{@code ON SIZE ERROR} が指定されている場合、動詞層はこれを捕捉して
 * SIZE ERROR 条件へ変換する (要件 FR-043)。
 */
public class DecimalDivideException extends RuntimeException implements AbendCause {

    private static final long serialVersionUID = 1L;

    @Override
    public AbendCode abendCode() {
        return AbendCode.S0CB;
    }

    public DecimalDivideException(String message) {
        super(message);
    }
}
