package io.github.gmcnicol.kernel.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Typed Candidate Payload bound to one generated Action descriptor. */
public record TypedCandidatePayload<C>(
        ActionType<?, C, ?> actionType,
        C value,
        Optional<W3cTraceContext> traceContext,
        Optional<UUID> priorIntentId) {
    public TypedCandidatePayload(ActionType<?, C, ?> actionType, C value) {
        this(actionType, value, Optional.empty(), Optional.empty());
    }

    public TypedCandidatePayload {
        Objects.requireNonNull(actionType, "actionType");
        actionType.candidateType().javaType().cast(Objects.requireNonNull(value, "value"));
        Objects.requireNonNull(traceContext, "traceContext");
        Objects.requireNonNull(priorIntentId, "priorIntentId");
    }
}
