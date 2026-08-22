package io.github.gmcnicol.kernel.application;

public class IntentConflictException extends RuntimeException {

    public IntentConflictException() {
        super("Intent ID already belongs to a different request");
    }
}
