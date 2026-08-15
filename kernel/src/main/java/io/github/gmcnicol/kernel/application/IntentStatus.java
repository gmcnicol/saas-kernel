package io.github.gmcnicol.kernel.application;

public enum IntentStatus {
    PENDING,
    CLAIMED,
    RETRY_WAIT,
    SUCCEEDED,
    STALE,
    FAILED
}
