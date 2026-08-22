package io.github.gmcnicol.kernel.application;

/** Signals transient handler infrastructure failure that may succeed on a later attempt. */
public final class RetryableIntentException extends RuntimeException {

    public RetryableIntentException(String message, Throwable cause) {
        super(message, cause);
    }

    public RetryableIntentException(String message) {
        super(message);
    }
}
