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

    <P, C, E> TypedIntentEvidence<C, E> readIntentEvidence(
            String tenantId, UUID intentId, ActionType<P, C, E> actionType);

    Optional<Intent> processNext(Instant processedAt);

    List<Intent> processDue(Instant processedAt);

    Optional<TypedEvaluationSnapshot<?, ?>> processNextReevaluation(Instant evaluatedAt);

    List<IntentView> findIntents(IntentQuery query);

    List<IntentAuditEntry> findIntentAudit(IntentAuditQuery query);
}
