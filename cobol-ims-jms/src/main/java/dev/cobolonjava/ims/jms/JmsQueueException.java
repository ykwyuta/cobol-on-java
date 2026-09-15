package dev.cobolonjava.ims.jms;

/** 電文のキューを読めない、送れない、確定できない。 */
public final class JmsQueueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JmsQueueException(String message, Throwable cause) {
        super(message, cause);
    }
}
