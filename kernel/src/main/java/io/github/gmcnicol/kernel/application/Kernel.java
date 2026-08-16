package io.github.gmcnicol.kernel.application;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** Stable entry point for Application-owned state and Kernel workflow mechanics. */
public interface Kernel {

    EvaluationSnapshot evaluate(ProjectedState state, Instant evaluatedAt);

    <I, P> TypedEvaluationSnapshot<I, P> evaluate(TypedProjectedState<I, P> state, Instant evaluatedAt);

    AuthorisationEnvelope authorise(String tenantId, UUID snapshotId, Principal principal, Instant authorisedAt);

    PresentationEnvelope present(String tenantId, UUID snapshotId, Principal principal, Instant presentedAt);

    Intent accept(UUID actionOfferId, UUID intentId, CandidatePayload payload);

    <C> Intent accept(UUID actionOfferId, UUID intentId, TypedCandidatePayload<C> payload);

    Optional<Intent> processNext(Instant processedAt);

    List<Intent> processDue(Instant processedAt);

    Optional<EvaluationSnapshot> processNextReevaluation(Instant evaluatedAt);

    List<IntentView> findIntents(IntentQuery query);

    List<IntentAuditEntry> findIntentAudit(IntentAuditQuery query);
}
