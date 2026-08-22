package io.github.gmcnicol.kernel.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record IntentAuditEntry(
        UUID id,
        UUID intentId,
        int sequence,
        Optional<IntentStatus> fromStatus,
        IntentStatus toStatus,
        Instant occurredAt,
        String reason,
        Optional<IntentFailureReason> failureReason,
        UUID correlation) {}
