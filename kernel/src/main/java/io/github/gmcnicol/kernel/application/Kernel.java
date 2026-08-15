package io.github.gmcnicol.kernel.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Stable entry point for Application-owned state and Kernel workflow mechanics. */
public interface Kernel {

    EvaluationSnapshot evaluate(ProjectedState state, Instant evaluatedAt);

    AuthorisationEnvelope authorise(String tenantId, UUID snapshotId, Principal principal, Instant authorisedAt);

    Intent accept(UUID actionOfferId, UUID intentId, CandidatePayload payload);

    Optional<Intent> processNext(Instant processedAt);
}
