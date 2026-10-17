package io.github.gmcnicol.kernel.application;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** Stable entry point for Application-owned state and Kernel workflow mechanics. */
public interface Kernel {

    <I, P> TypedEvaluationSnapshot<I, P> evaluate(TypedProjectedState<I, P> state, Instant evaluatedAt);

    <I, P> TypedAuthorisationEnvelope<I, P> authorise(
            String tenantId, UUID snapshotId, Principal principal, Instant authorisedAt,
            ProjectionType<I, P> projectionType);

    <I, P> TypedPresentationEnvelope<I, P> present(
            String tenantId, UUID snapshotId, Principal principal, Instant presentedAt,
            ProjectionType<I, P> projectionType);

    <C> Intent accept(UUID actionOfferId, UUID intentId, TypedCandidatePayload<C> payload);

    Intent accept(
            String tenantId, Principal principal, UUID actionOfferId, UUID intentId,
            TypedCandidatePayload<?> payload);

    /**
     * Accepts and completes exactly one named Intent in the active caller-owned transaction.
     *
     * <p>The transaction must use the Kernel's configured datasource and transaction manager. This method neither
     * opens an independent transaction nor drains other due work. {@link ExactIntentCompletion#COMPLETED} is
     * provisional until the caller commits; rollback removes the Intent, Event receipts, projection updates, and
     * Application writes made in that transaction. Repeating an identical committed request returns
     * {@link ExactIntentCompletion#ALREADY_SUCCEEDED}; changing request content under the same Intent identity
     * raises {@link IntentConflictException}.
     *
     * @param tenantId tenant that received the Action Offer
     * @param principal principal to which the Action Offer was issued
     * @param actionOfferId exact Action Offer being invoked
     * @param intentId caller-assigned idempotency identity
     * @param candidate typed Candidate Payload for the offered Action
     * @param executedAt explicit acceptance and execution time, and part of request identity
     * @return whether this transaction completed the Intent or reconciled an identical committed success
     * @throws IllegalStateException when no active transaction participates in the Kernel datasource
     * @throws IntentConflictException when the Intent identity is already bound to different request content
     * @throws IntentRejectedException when the exact Intent cannot complete successfully
     */
    <C> ExactIntentCompletion execute(
            String tenantId, Principal principal, UUID actionOfferId, UUID intentId,
            TypedCandidatePayload<C> candidate, Instant executedAt);

    Optional<Intent> processNext(Instant processedAt);

    List<Intent> processDue(Instant processedAt);

    Optional<TypedEvaluationSnapshot<?, ?>> processNextReevaluation(Instant evaluatedAt);

    List<IntentView> findIntents(IntentQuery query);

    List<IntentAuditEntry> findIntentAudit(IntentAuditQuery query);

    List<IntentEvent> findIntentEvents(String tenantId, UUID intentId);
}
