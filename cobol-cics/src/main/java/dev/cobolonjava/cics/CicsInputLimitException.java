package dev.cobolonjava.cics;

/** CICS taskを開始する前に検出した入力上限違反。 */
public final class CicsInputLimitException extends IllegalArgumentException {

    private final String resource;
    private final long actual;
    private final long limit;

    public CicsInputLimitException(String resource, long actual, long limit) {
        super(resource + " exceeds configured limit: actual=" + actual + ", limit=" + limit);
        this.resource = resource;
        this.actual = actual;
        this.limit = limit;
    }

    public String resource() {
        return resource;
    }

    public long actual() {
        return actual;
    }

    public long limit() {
        return limit;
    }
}
