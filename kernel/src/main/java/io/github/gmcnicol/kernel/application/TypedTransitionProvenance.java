package io.github.gmcnicol.kernel.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Complete validated provenance passed to an Application typed Event projector. */
public record TypedTransitionProvenance<P, E>(
        String tenantId,
        UUID intentId,
        UUID actionOfferId,
        TypedSubject<?> subject,
        String actionId,
        int sequence,
        Instant occurredAt,
        P previousProjection,
        E event,
        P resultingProjection,
        CanonicalEvidence eventEvidence,
        CanonicalEvidence resultingProjectionEvidence) {
    public TypedTransitionProvenance {
        if (tenantId == null || tenantId.isBlank() || actionId == null || actionId.isBlank() || sequence < 1) {
            throw new IllegalArgumentException("Typed transition provenance is incomplete");
        }
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(actionOfferId, "actionOfferId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(previousProjection, "previousProjection");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(resultingProjection, "resultingProjection");
        Objects.requireNonNull(eventEvidence, "eventEvidence");
        Objects.requireNonNull(resultingProjectionEvidence, "resultingProjectionEvidence");
    }
}
