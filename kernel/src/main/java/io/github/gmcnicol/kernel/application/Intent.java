package io.github.gmcnicol.kernel.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record Intent(
        UUID id,
        UUID actionOfferId,
        IntentStatus status,
        Instant acceptedAt,
        Optional<IntentFailureReason> failureReason) {

    public Intent(UUID id, UUID actionOfferId, IntentStatus status, Instant acceptedAt) {
        this(id, actionOfferId, status, acceptedAt, Optional.empty());
    }

    public Intent {
        if (failureReason == null) {
            throw new IllegalArgumentException("Intent failure reason cannot be null");
        }
    }
}
