package io.github.gmcnicol.kernel.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Application-owned data needed by durable execution. Kernel never stores these values.
 * All methods run in the Kernel transaction and tenant context. Implementations must use the
 * same transactional database, enforce tenant ownership, and must not change the database role.
 * State is read from current domain records, never from a second snapshot store.
 * Pending input is the sole durable copy of unapplied input, not an evidence archive.
 */
public interface ApplicationWorkflowData {
    <I, P> Optional<TypedProjectedState<I, P>> readState(
            String tenantId, TypedSubject<I> subject, ProjectionType<I, P> type);

    /** Store only input needed to execute this Intent, atomically with acceptance. */
    <C> void saveCandidate(String tenantId, UUID intentId, Subject subject, TypedCandidatePayload<C> candidate);

    <C> C readCandidate(String tenantId, UUID intentId, ActionType<?, C, ?> action);

    /** Remove pending input atomically with every terminal outcome; retries retain it. */
    void discardCandidate(String tenantId, UUID intentId);
}
