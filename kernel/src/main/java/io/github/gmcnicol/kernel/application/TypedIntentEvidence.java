package io.github.gmcnicol.kernel.application;

import java.util.List;
import java.util.Objects;

/** Current typed view of one Intent's immutable Candidate Payload and Events. */
public record TypedIntentEvidence<C, E>(C candidatePayload, List<E> events) {
    public TypedIntentEvidence {
        Objects.requireNonNull(candidatePayload, "candidatePayload");
        events = List.copyOf(events);
    }
}
