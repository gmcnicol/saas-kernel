package io.github.gmcnicol.kernel.application;

public class IntentRejectedException extends RuntimeException {

    public IntentRejectedException() {
        super("Intent rejected");
    }
}
