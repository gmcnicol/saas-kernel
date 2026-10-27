package io.github.gmcnicol.kernel.application;

/** Application-only candidate. Never return or log its raw value. Identity is mapped from authoritative rows. */
public record ReadCandidate<P>(String tenantId, TypedSubject<?> scope, TypedSubject<?> record, P value) {
    @Override public String toString() { return "ReadCandidate[redacted]"; }
}
