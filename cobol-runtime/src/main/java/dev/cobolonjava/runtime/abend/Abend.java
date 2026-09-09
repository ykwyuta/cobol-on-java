package dev.cobolonjava.runtime.abend;

/**
 * 実行を打ち切る (要件 FR-141)。
 *
 * <p>すでに例外の型を持っている条件 (数値の誤り、ゼロ除算、呼び先が無いなど) は、
 * その型が {@link AbendCause} を実装して自分のコードを名乗る。これを直に投げるのは、
 * <b>型を作るほどの中身がない条件</b>のためである。
 */
public final class Abend extends RuntimeException implements AbendCause {

    private static final long serialVersionUID = 1L;

    private final AbendCode code;

    public Abend(AbendCode code, String message) {
        super(code.text() + " " + message);
        this.code = code;
    }

    @Override
    public AbendCode abendCode() {
        return code;
    }

    /**
     * この誤りが異常終了になるなら、そのコード。
     *
     * <p>原因を包んだ例外も辿る。ランタイムの中で {@code UncheckedIOException} などに
     * 包まれても、<b>もとの条件のコードが残る</b>ようにするためである。
     *
     * @return 異常終了にならない誤りなら {@code null}
     */
    public static AbendCode codeOf(Throwable failure) {
        for (Throwable at = failure; at != null; at = at.getCause()) {
            if (at instanceof AbendCause cause) {
                return cause.abendCode();
            }
            if (at.getCause() == at) {
                break;
            }
        }
        return null;
    }
}
