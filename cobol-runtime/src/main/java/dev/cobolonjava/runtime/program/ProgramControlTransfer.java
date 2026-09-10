package dev.cobolonjava.runtime.program;

/** subsystemによる非局所的だが正常なprogram制御移送。 */
public abstract class ProgramControlTransfer extends RuntimeException {

    protected ProgramControlTransfer(String message) {
        super(message, null, false, false);
    }
}
