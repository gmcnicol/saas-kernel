package io.github.gmcnicol.kernel.application;

public enum IntentFailureReason {
    STATE_OR_SEMANTIC_STALE,
    NOT_APPLICABLE,
    AUTHORISATION_DENIED,
    DETERMINISTIC_FAILURE,
    TRANSIENT_ATTEMPTS_EXHAUSTED
}
